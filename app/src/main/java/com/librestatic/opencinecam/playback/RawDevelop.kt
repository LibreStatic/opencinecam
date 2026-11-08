/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The CFA arrangement of a take. [siteChannels] gives the colour of each 2×2 site in CFA order
 * (column, row) = (0,0), (1,0), (0,1), (1,1), the order of `blackLevel` and of the TIFF CFAPattern:
 * 0 red, 1 green, 2 blue. [MONO] has no colour; it develops grey.
 */
enum class RawCfa(val siteChannels: IntArray) {
    RGGB(intArrayOf(0, 1, 1, 2)),
    GRBG(intArrayOf(1, 0, 2, 1)),
    GBRG(intArrayOf(1, 2, 0, 1)),
    BGGR(intArrayOf(2, 1, 1, 0)),
    MONO(intArrayOf(1, 1, 1, 1));

    /** The CFA site of pixel ([x], [y]) of a frame whose origin keeps the sensor's phase. */
    fun site(x: Int, y: Int): Int = (y and 1) * 2 + (x and 1)

    companion object {
        /**
         * Parses the `cfa` value like `ocraw_to_dng.py` (`cfa_name`), Android's integer codes included.
         * A missing value is RGGB, as the converter assumes; NIR is MONO, as the writer stores it.
         * Arrangements the converter refuses (RGB) fall back to RGGB so a preview still shows.
         */
        fun parse(value: String?): RawCfa = when (value?.trim()?.uppercase()) {
            "RGGB", "0" -> RGGB
            "GRBG", "1" -> GRBG
            "GBRG", "2" -> GBRG
            "BGGR", "3" -> BGGR
            "MONO", "5", "NIR", "6" -> MONO
            else -> RGGB
        }
    }
}

/**
 * Everything the GPU develop stage needs for one frame. Arrays are fresh copies and are not mutated.
 *
 * @property blackLevel four black levels in CFA order, in RAW10 codes.
 * @property whiteLevel the saturation code.
 * @property wbGains R, G, B gains with green = 1, applied to the demosaicked camera RGB.
 * @property cameraToRec709 row-major 3×3 from white-balanced camera RGB to linear Rec.709 (D65).
 */
class RawDevelopParams(
    val cfa: RawCfa,
    val blackLevel: FloatArray,
    val whiteLevel: Float,
    val wbGains: FloatArray,
    val cameraToRec709: FloatArray,
) {
    init {
        require(blackLevel.size == 4) { "four black levels in CFA order" }
        require(wbGains.size == 3) { "three white balance gains" }
        require(cameraToRec709.size == 9) { "a 3x3 matrix" }
    }

    /** [cameraToRec709] in the column-major order `glUniformMatrix3fv` expects without transposing. */
    fun cameraToRec709ColumnMajor(): FloatArray = FloatArray(9) { cameraToRec709[(it % 3) * 3 + it / 3] }
}

/**
 * The static colour description of a take, parsed once from its `METADATA` map with the defaults of
 * `ocraw_to_dng.py` (`static_colour`): RGGB without `cfa`, white 1023 when `whiteLevel` is missing or
 * not positive, black 0, and the identity when `colorMatrix1` is missing. Unparseable values count as
 * missing, so a damaged take still previews.
 */
class RawStaticColour(
    val cfa: RawCfa,
    val blackLevel: DoubleArray,
    val whiteLevel: Double,
    val colorMatrix1: DoubleArray,
    val forwardMatrix1: DoubleArray?,
) {
    companion object {
        fun from(metadata: Map<String, String>): RawStaticColour {
            val white = metadata["whiteLevel"]?.let(RawDevelop::parseNumber)?.takeIf { it > 0 } ?: RawDevelop.DEFAULT_WHITE
            return RawStaticColour(
                cfa = RawCfa.parse(metadata["cfa"]),
                blackLevel = RawDevelop.parseNumbers(metadata["blackLevel"], 4) ?: DoubleArray(4),
                whiteLevel = white,
                colorMatrix1 = RawDevelop.parseNumbers(metadata["colorMatrix1"], 9) ?: RawDevelop.IDENTITY.copyOf(),
                forwardMatrix1 = RawDevelop.parseNumbers(metadata["forwardMatrix1"], 9),
            )
        }
    }
}

/**
 * Develops RAW video frames for review the way the DNG converter describes them
 * (`desktop-tools/ocraw_to_dng.py`, PLAN-071 §7 decision 4). This object holds the maths; the
 * shaders in [RawPlaybackRenderer] mirror [developHalf] and [developBilinear] step for step:
 *
 * 1. unpack RAW10 ([raw10At]);
 * 2. per CFA site, (raw − black) / (white − black), clamped to 0..1;
 * 3. demosaic: one RGB pixel per 2×2 quad (greens averaged) or bilinear at full resolution;
 * 4. multiply by the white balance gains and clip at 1, so saturated highlights stay white;
 * 5. camera → linear Rec.709 through [RawDevelopParams.cameraToRec709], negatives clipped;
 * 6. [tone]: a highlight shoulder, then the sRGB transfer.
 */
object RawDevelop {
    const val DEFAULT_WHITE = 1023.0

    /** Linear values up to the knee pass unchanged; above it they roll off towards 1. */
    const val SHOULDER_KNEE = 0.75f

    internal val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** CIE XYZ of D50, the profile connection white of DNG forward matrices. */
    internal val D50 = doubleArrayOf(0.96422, 1.0, 0.82521)
    internal val D65 = doubleArrayOf(0.95047, 1.0, 1.08883)

    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )

    private val REC709_TO_XYZ_D65 = doubleArrayOf(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041,
    )

    /** XYZ (D50) → linear Rec.709/sRGB (D65), Bradford-adapted. */
    val XYZ_D50_TO_REC709: DoubleArray = multiply(invert(REC709_TO_XYZ_D65), bradford(D50, D65))

    // ---------------------------------------------------------------- parameters

    /**
     * The develop parameters of one frame. [frame] is its `FRAME_METADATA`: `dynamicBlackLevel`
     * (or `blackLevel`) and `whiteLevel` override the static levels, and `neutral` sets the white
     * balance. A frame without a neutral uses [fallbackNeutral] (the session passes the nearest
     * frame's), else no gain.
     */
    fun params(colour: RawStaticColour, frame: Map<String, String>, fallbackNeutral: DoubleArray? = null): RawDevelopParams {
        val black = parseNumbers(frame["dynamicBlackLevel"] ?: frame["blackLevel"], 4) ?: colour.blackLevel
        val white = frame["whiteLevel"]?.let(::parseNumber)?.takeIf { it > 0 } ?: colour.whiteLevel
        if (colour.cfa == RawCfa.MONO) {
            return RawDevelopParams(colour.cfa, black.toFloats(), white.toFloat(), floatArrayOf(1f, 1f, 1f), IDENTITY.toFloats())
        }
        val neutral = neutral(frame) ?: fallbackNeutral?.takeIf(::validNeutral)
        return RawDevelopParams(
            cfa = colour.cfa,
            blackLevel = black.toFloats(),
            whiteLevel = white.toFloat(),
            wbGains = whiteBalanceGains(neutral).toFloats(),
            cameraToRec709 = multiply(XYZ_D50_TO_REC709, cameraToXyzD50(colour, neutral)).toFloats(),
        )
    }

    /** The frame's as-shot neutral (camera RGB of white), or null when absent or unusable. */
    fun neutral(frame: Map<String, String>): DoubleArray? = parseNumbers(frame["neutral"], 3)?.takeIf(::validNeutral)

    /** 1 / neutral, scaled so green is 1; unity without a neutral. */
    fun whiteBalanceGains(neutral: DoubleArray?): DoubleArray {
        if (neutral == null || !validNeutral(neutral)) return doubleArrayOf(1.0, 1.0, 1.0)
        return doubleArrayOf(neutral[1] / neutral[0], 1.0, neutral[1] / neutral[2])
    }

    /**
     * White-balanced camera RGB → XYZ (D50). `forwardMatrix1` is exactly that by definition. Without
     * it, `colorMatrix1` (XYZ → camera) is inverted and, as the DNG specification does, scaled so the
     * camera neutral has Y = 1 and Bradford-adapted from that white to D50.
     */
    fun cameraToXyzD50(colour: RawStaticColour, neutral: DoubleArray?): DoubleArray {
        colour.forwardMatrix1?.let { return it.copyOf() }
        val cameraToXyz = invertOrNull(colour.colorMatrix1) ?: IDENTITY
        val cameraNeutral = neutral?.takeIf(::validNeutral) ?: doubleArrayOf(1.0, 1.0, 1.0)
        // Balanced RGB (1,1,1) is the camera neutral.
        val balancedToXyz = multiply(cameraToXyz, diagonal(cameraNeutral))
        val white = apply(balancedToXyz, doubleArrayOf(1.0, 1.0, 1.0))
        // A matrix that puts the neutral outside positive XYZ is unusable: treat the camera as Rec.709.
        if (white.any { !(it > 0.0) || !it.isFinite() }) return multiply(bradford(D65, D50), REC709_TO_XYZ_D65)
        val scaled = DoubleArray(9) { balancedToXyz[it] / white[1] }
        return multiply(bradford(DoubleArray(3) { white[it] / white[1] }, D50), scaled)
    }

    /** Bradford chromatic adaptation from white [from] to white [to] (XYZ). */
    fun bradford(from: DoubleArray, to: DoubleArray): DoubleArray {
        val coneFrom = apply(BRADFORD, from)
        val coneTo = apply(BRADFORD, to)
        val scale = diagonal(DoubleArray(3) { coneTo[it] / coneFrom[it] })
        return multiply(invert(BRADFORD), multiply(scale, BRADFORD))
    }

    // ---------------------------------------------------------------- tone

    /** The review tone curve for one linear Rec.709 value: [shoulder], then the sRGB transfer. */
    fun tone(linear: Float): Float = srgbOetf(shoulder(max(linear, 0f)))

    fun shoulder(x: Float): Float {
        if (x <= SHOULDER_KNEE) return x
        val span = 1f - SHOULDER_KNEE
        return SHOULDER_KNEE + span * (1f - exp(-(x - SHOULDER_KNEE) / span))
    }

    fun srgbOetf(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return if (c < 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    // ---------------------------------------------------------------- CPU reference develop

    /** The RAW10 code of pixel ([x], [y]) in tight rows of [rowBytes], starting at [buffer]'s position. */
    fun raw10At(buffer: ByteBuffer, rowBytes: Int, x: Int, y: Int): Int {
        val row = buffer.position() + y * rowBytes
        val group = row + (x ushr 2) * 5
        val lane = x and 3
        val msb = buffer.get(group + lane).toInt() and 0xff
        val low = buffer.get(group + 4).toInt() and 0xff
        return (msb shl 2) or ((low ushr (lane * 2)) and 3)
    }

    /** The whole frame as codes, row-major. Reference and test helper; playback never widens frames. */
    fun unpackRaw10(buffer: ByteBuffer, header: OcrawFrameHeader): IntArray =
        IntArray(header.width * header.height) { raw10At(buffer, header.rowBytes, it % header.width, it / header.width) }

    /** Step 2: one site's code normalised to 0..1. */
    fun normalise(code: Int, site: Int, params: RawDevelopParams): Float {
        val black = params.blackLevel[site]
        val range = params.whiteLevel - black
        if (range <= 0f) return 0f
        return ((code - black) / range).coerceIn(0f, 1f)
    }

    /** Steps 4–6 on demosaicked camera RGB; returns sRGB-encoded R, G, B. */
    fun finish(camera: FloatArray, params: RawDevelopParams): FloatArray {
        val balanced = FloatArray(3) { min(camera[it] * params.wbGains[it], 1f) }
        val m = params.cameraToRec709
        return FloatArray(3) { row ->
            tone(m[row * 3] * balanced[0] + m[row * 3 + 1] * balanced[1] + m[row * 3 + 2] * balanced[2])
        }
    }

    /** The real-time develop: (width/2)×(height/2) pixels, interleaved sRGB R, G, B, top row first. */
    fun developHalf(buffer: ByteBuffer, header: OcrawFrameHeader, params: RawDevelopParams): FloatArray {
        val outWidth = header.width / 2
        val outHeight = header.height / 2
        val out = FloatArray(outWidth * outHeight * 3)
        for (qy in 0 until outHeight) for (qx in 0 until outWidth) {
            val sum = FloatArray(3)
            val count = FloatArray(3)
            var total = 0f
            for (site in 0 until 4) {
                val x = qx * 2 + (site and 1)
                val y = qy * 2 + (site shr 1)
                val value = normalise(raw10At(buffer, header.rowBytes, x, y), site, params)
                val channel = params.cfa.siteChannels[site]
                sum[channel] += value; count[channel] += 1f; total += value
            }
            val camera = if (params.cfa == RawCfa.MONO) FloatArray(3) { total * 0.25f }
                else FloatArray(3) { sum[it] / max(count[it], 1f) }
            finish(camera, params).copyInto(out, (qy * outWidth + qx) * 3)
        }
        return out
    }

    /**
     * The frame viewer develop: bilinear demosaic at full resolution. A pixel keeps its own channel;
     * each other channel is the mean of that colour's sites in the 3×3 neighbourhood. Edges mirror by
     * two pixels so the CFA phase holds. Interleaved sRGB R, G, B, top row first.
     */
    fun developBilinear(buffer: ByteBuffer, header: OcrawFrameHeader, params: RawDevelopParams): FloatArray {
        val width = header.width
        val height = header.height
        val out = FloatArray(width * height * 3)
        val cfa = params.cfa
        fun valueAt(x: Int, y: Int): Float = normalise(raw10At(buffer, header.rowBytes, x, y), cfa.site(x, y), params)
        for (y in 0 until height) for (x in 0 until width) {
            val centre = valueAt(x, y)
            val camera = if (cfa == RawCfa.MONO) FloatArray(3) { centre } else {
                val sum = FloatArray(3)
                val count = FloatArray(3)
                for (dy in -1..1) for (dx in -1..1) {
                    val sx = mirror(x + dx, width)
                    val sy = mirror(y + dy, height)
                    val channel = cfa.siteChannels[cfa.site(sx, sy)]
                    sum[channel] += valueAt(sx, sy); count[channel] += 1f
                }
                val own = cfa.siteChannels[cfa.site(x, y)]
                FloatArray(3) { if (it == own) centre else sum[it] / max(count[it], 1f) }
            }
            finish(camera, params).copyInto(out, (y * width + x) * 3)
        }
        return out
    }

    /** Out-of-frame neighbours step back by two, keeping the CFA phase. */
    internal fun mirror(value: Int, size: Int): Int = when {
        value < 0 -> min(value + 2, size - 1)
        value >= size -> max(value - 2, 0)
        else -> value
    }

    /**
     * The viewport (x, y, width, height, GL bottom-left origin) that fits a frame of the given
     * aspect inside the surface, centred, with black bars on the remaining sides.
     */
    fun letterbox(frameWidth: Int, frameHeight: Int, surfaceWidth: Int, surfaceHeight: Int): IntArray {
        if (frameWidth <= 0 || frameHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) return intArrayOf(0, 0, 0, 0)
        val scale = min(surfaceWidth.toDouble() / frameWidth, surfaceHeight.toDouble() / frameHeight)
        val width = min(surfaceWidth, max(1, (frameWidth * scale).roundToInt()))
        val height = min(surfaceHeight, max(1, (frameHeight * scale).roundToInt()))
        return intArrayOf((surfaceWidth - width) / 2, (surfaceHeight - height) / 2, width, height)
    }

    /**
     * The size a [width]×[height] frame shows at once turned by [orientationDegrees] (the take's
     * `orientation`, clockwise): swapped for a quarter turn, as given otherwise.
     */
    fun displaySize(width: Int, height: Int, orientationDegrees: Int): IntArray =
        if (orientationDegrees == 90 || orientationDegrees == 270) intArrayOf(height, width) else intArrayOf(width, height)

    /**
     * The 2×2 matrix, column-major as `glUniformMatrix2fv` takes it, that maps a point of the shown
     * picture to the stored frame point to sample, both as fractions with y down and centred on 0.5:
     * `stored = M * (shown - 0.5) + 0.5`. It undoes the clockwise [orientationDegrees] that shows the
     * frame upright; anything but 90, 180 or 270 is the identity.
     */
    fun orientationMatrix(orientationDegrees: Int): FloatArray = when (orientationDegrees) {
        // Shown (x, y) comes from stored (y, 1 - x).
        90 -> floatArrayOf(0f, -1f, 1f, 0f)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f)
        // Shown (x, y) comes from stored (1 - y, x).
        270 -> floatArrayOf(0f, 1f, -1f, 0f)
        else -> floatArrayOf(1f, 0f, 0f, 1f)
    }

    /** [orientationMatrix] applied to the shown point ([x], [y]), as the blit shader does: the stored point. */
    fun orientedSample(x: Float, y: Float, orientationDegrees: Int): FloatArray {
        val m = orientationMatrix(orientationDegrees)
        val dx = x - 0.5f
        val dy = y - 0.5f
        return floatArrayOf(m[0] * dx + m[2] * dy + 0.5f, m[1] * dx + m[3] * dy + 0.5f)
    }

    /** Mip levels for a [width]×[height] texture, down to 1×1. */
    internal fun mipLevels(width: Int, height: Int): Int {
        var levels = 1
        var size = max(width, height)
        while (size > 1) { size = size shr 1; levels++ }
        return levels
    }

    // ---------------------------------------------------------------- parsing and 3×3 maths

    /** A metadata number: an integer, a `.` decimal or an `n/d` rational with positive `d`; null if invalid. */
    fun parseNumber(text: String): Double? {
        val trimmed = text.trim()
        val slash = trimmed.indexOf('/')
        val value = if (slash >= 0) {
            val numerator = trimmed.substring(0, slash).toLongOrNull() ?: return null
            val denominator = trimmed.substring(slash + 1).toLongOrNull() ?: return null
            if (denominator <= 0) return null
            numerator.toDouble() / denominator
        } else trimmed.toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() }
    }

    /** Exactly [count] space-separated numbers, or null when absent or malformed. */
    fun parseNumbers(text: String?, count: Int): DoubleArray? {
        val parts = text?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")) ?: return null
        if (parts.size != count) return null
        return DoubleArray(count) { parseNumber(parts[it]) ?: return null }
    }

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { index ->
        val row = index / 3
        val col = index % 3
        a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
    }

    fun apply(m: DoubleArray, v: DoubleArray): DoubleArray = DoubleArray(3) { row ->
        m[row * 3] * v[0] + m[row * 3 + 1] * v[1] + m[row * 3 + 2] * v[2]
    }

    fun invert(m: DoubleArray): DoubleArray = requireNotNull(invertOrNull(m)) { "singular matrix" }

    fun invertOrNull(m: DoubleArray): DoubleArray? {
        val (a, b, c, d, e) = m
        val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val c0 = e * i - f * h
        val c1 = f * g - d * i
        val c2 = d * h - e * g
        val det = a * c0 + b * c1 + c * c2
        if (!det.isFinite() || kotlin.math.abs(det) < 1e-12) return null
        return doubleArrayOf(
            c0 / det, (c * h - b * i) / det, (b * f - c * e) / det,
            c1 / det, (a * i - c * g) / det, (c * d - a * f) / det,
            c2 / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    private fun diagonal(v: DoubleArray) = doubleArrayOf(v[0], 0.0, 0.0, 0.0, v[1], 0.0, 0.0, 0.0, v[2])

    private fun validNeutral(n: DoubleArray) = n.size == 3 && n.all { it > 0.0 && it.isFinite() }

    private fun DoubleArray.toFloats() = FloatArray(size) { this[it].toFloat() }
}
