/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.math.floor

/**
 * Bounded SDR-monitor subset of Adobe Cube 1.0, using explicitly trilinear interpolation.
 * No shaper, HDR output, implicit color-space conversion or resizing of the table.
 * Values are RGB triples in red-fastest order. Import accepts CRLF and indented/inline
 * comments in addition to the specification's LF and full comment lines.
 */
class CubeLut private constructor(
    val size: Int,
    val title: String?,
    val sha256: String,
    private val minimum: FloatArray,
    private val maximum: FloatArray,
    private val table: FloatArray,
) {
    val domainMin: FloatArray get() = minimum.copyOf()
    val domainMax: FloatArray get() = maximum.copyOf()
    val values: FloatArray get() = table.copyOf()

    /** Input is normalized per channel against the declared domain, then clamped at its edges. */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val input = floatArrayOf(r, g, b)
        require(input.all { it.isFinite() }) { "Cube input must be finite." }
        val coordinates = DoubleArray(3) { axis ->
            ((input[axis].toDouble() - minimum[axis]) /
                (maximum[axis].toDouble() - minimum[axis])).coerceIn(0.0, 1.0) * (size - 1)
        }
        val low = IntArray(3) { floor(coordinates[it]).toInt() }
        val high = IntArray(3) { (low[it] + 1).coerceAtMost(size - 1) }
        val fraction = DoubleArray(3) { coordinates[it] - low[it] }
        return FloatArray(3) { channel ->
            var value = 0.0
            for (blue in 0..1) for (green in 0..1) for (red in 0..1) {
                val ri = if (red == 0) low[0] else high[0]
                val gi = if (green == 0) low[1] else high[1]
                val bi = if (blue == 0) low[2] else high[2]
                val weight = (if (red == 0) 1 - fraction[0] else fraction[0]) *
                    (if (green == 0) 1 - fraction[1] else fraction[1]) *
                    (if (blue == 0) 1 - fraction[2] else fraction[2])
                value += table[3 * (ri + size * (gi + size * bi)) + channel] * weight
            }
            value.toFloat()
        }
    }

    /** Locale-independent shortest Float round trips; export preserves mapping, not source bytes/hash. */
    fun export(): ByteArray = buildString {
        title?.let { append("TITLE \"").append(it).append("\"\n") }
        append("LUT_3D_SIZE ").append(size).append('\n')
        append("DOMAIN_MIN ").append(minimum.joinToString(" ")).append('\n')
        append("DOMAIN_MAX ").append(maximum.joinToString(" ")).append('\n')
        for (offset in table.indices step 3) {
            append(table[offset]).append(' ').append(table[offset + 1]).append(' ')
                .append(table[offset + 2]).append('\n')
        }
    }.toByteArray(Charsets.UTF_8).also { check(it.size <= MAX_BYTES) }

    companion object {
        const val MIN_SIZE = 2
        const val MAX_SIZE = 33
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_LINE_BYTES = 250
        const val MAX_DOMAIN_ABS = 1_000_000f
        private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        private val whitespace = Regex("[ \\t]+")

        fun parse(bytes: ByteArray): CubeLut {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Cube byte limit exceeded or empty input." }
            val source = bytes.copyOf()
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString()
            } catch (failure: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("Cube input is not valid UTF-8.", failure)
            }
            // Cube 1.0 text is Basic Latin. Reject controls/BOM rather than silently changing bytes.
            require(text.all { it == '\n' || it == '\r' || it == '\t' || it in ' '..'~' }) { "Unsupported Cube text character." }
            var size = 0
            var title: String? = null
            var minimum = floatArrayOf(0f, 0f, 0f)
            var maximum = floatArrayOf(1f, 1f, 1f)
            var table: FloatArray? = null
            var used = 0
            val headers = mutableSetOf<String>()
            for (raw in text.lineSequence()) {
                require(raw.length <= MAX_LINE_BYTES) { "Cube line exceeds 250 bytes." }
                var quoted = false
                var end = raw.length
                for (i in raw.indices) {
                    if (raw[i] == '\"') quoted = !quoted
                    if (raw[i] == '#' && !quoted) { end = i; break }
                }
                val line = raw.substring(0, end).trim(' ', '\t')
                if (line.isEmpty()) continue
                val parts = line.split(whitespace)
                val key = parts[0]
                if (key in setOf("TITLE", "LUT_3D_SIZE", "DOMAIN_MIN", "DOMAIN_MAX")) {
                    require(used == 0 && headers.add(key)) { "Repeated or late Cube header." }
                    when (key) {
                        "TITLE" -> {
                            val value = line.substring(key.length).trim(' ', '\t')
                            require(value.length >= 2 && value.first() == '\"' && value.last() == '\"' &&
                                value.substring(1, value.lastIndex).none { it == '\"' || it == '\t' }) { "Malformed Cube title." }
                            title = value.substring(1, value.lastIndex)
                        }
                        "LUT_3D_SIZE" -> {
                            require(parts.size == 2 && parts[1].matches(Regex("[0-9]+"))) { "Malformed Cube size." }
                            size = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Cube size overflow.")
                            require(size in MIN_SIZE..MAX_SIZE) { "Supported Cube size is 2..33." }
                            table = FloatArray(size * size * size * 3)
                        }
                        "DOMAIN_MIN" -> minimum = triple(parts.drop(1), output = false)
                        "DOMAIN_MAX" -> maximum = triple(parts.drop(1), output = false)
                    }
                } else {
                    require(number.matches(key)) { "Unsupported Cube keyword or shaper." }
                    val owned = requireNotNull(table) { "Cube data requires LUT_3D_SIZE first." }
                    require(used + 3 <= owned.size) { "Too many Cube entries." }
                    val rgb = triple(parts, output = true)
                    rgb.copyInto(owned, used)
                    used += 3
                }
            }
            val owned = requireNotNull(table) { "Missing Cube 3D table." }
            require(used == owned.size) { "Incomplete Cube 3D table." }
            require((0..2).all { minimum[it] < maximum[it] }) { "Cube domain must be strictly ordered." }
            val hash = MessageDigest.getInstance("SHA-256").digest(source)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return CubeLut(size, title, hash, minimum, maximum, owned)
        }

        private fun triple(parts: List<String>, output: Boolean): FloatArray {
            require(parts.size == 3) { "Cube entries require exactly three numbers." }
            return FloatArray(3) { index ->
                val token = parts[index]
                require(number.matches(token)) { "Malformed Cube number." }
                val value = token.toDoubleOrNull() ?: throw IllegalArgumentException("Malformed Cube number.")
                val nonzeroMantissa = token.substringBefore('e').substringBefore('E').any { it in '1'..'9' }
                require(value != 0.0 || !nonzeroMantissa) { "Cube number underflows Double." }
                require(value.isFinite() && if (output) value in 0.0..1.0
                    else value in -MAX_DOMAIN_ABS.toDouble()..MAX_DOMAIN_ABS.toDouble()) { "Cube number outside supported range." }
                val converted = value.toFloat()
                require(value == 0.0 || converted != 0f) { "Cube number underflows Float." }
                converted
            }
        }
    }
}
