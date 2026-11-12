// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Near-lossless quantisation for the quantised block-pack frame encoding
 * ([com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED]).
 *
 * Before block-pack, each RAW10 DN is replaced by a code from a monotonic table
 * built from the sensor's noise model: walking up the DNs, the code advances by
 * `min(1, 1 / (k * sigma(DN)))` per DN, so neighbouring DNs closer than about
 * `k` noise sigmas share a code (and DNs in the dark, where `k * sigma < 1`, keep
 * their own). A code decodes to the midpoint of its DNs. The construction is the
 * one validated on the Razr (`occbench`): at `k = 0.5` the error stays within
 * about half a sigma.
 *
 * The frame payload (preamble, inverse table, block-pack container of the codes)
 * is specified in `native-pipeline/include/opencinecam/raw_codecs.h`; this object
 * builds the tables, reads and writes the payload prefix, and maps decoded codes
 * back to DN in pure Kotlin. Nothing here needs the native library.
 */
object RawQuantisation {
    /** Entries of the forward table: every RAW10 DN. */
    const val LUT_SIZE = 1024

    /** `quantVersion` of the payload preamble. */
    const val VERSION = 1

    /** Bytes of the preamble before the inverse table. */
    const val PREAMBLE_BYTES = 12

    /** The default step, in noise sigmas. */
    const val DEFAULT_K = 0.5

    /** Largest RAW10 sample. */
    private const val SAMPLE_MAX = 1023

    /**
     * The sensor noise model in DN²: `variance(DN) = a + b * max(0, DN - black)`
     * (read noise plus shot noise above the black level).
     */
    data class NoiseModel(val a: Double, val b: Double) {
        init {
            require(a.isFinite() && b.isFinite()) { "noise model must be finite" }
        }

        /** The noise sigma at [dn], 0 where the variance is not positive. */
        fun sigma(dn: Int, blackLevel: Int): Double = sqrt(max(0.0, a + b * max(0, dn - blackLevel)))
    }

    /**
     * The forward table ([LUT_SIZE] entries, DN to code) and the inverse table
     * ([count] entries, code to reconstructed DN) of one noise model, black level
     * and step.
     */
    class Tables(
        val forward: IntArray,
        val inverse: IntArray,
        val kMilli: Int,
        val blackLevel: Int,
    ) {
        init {
            require(forward.size == LUT_SIZE) { "forward table needs $LUT_SIZE entries" }
            require(inverse.size in 1..LUT_SIZE) { "inverse table needs 1..$LUT_SIZE entries" }
            require(forward.all { it in inverse.indices }) { "every code needs an inverse entry" }
            require(inverse.all { it in 0..SAMPLE_MAX }) { "inverse entries must be RAW10 samples" }
            require(kMilli in 0..0xffff) { "kMilli must fit uint16" }
            require(blackLevel in 0..0xffff) { "black level must fit uint16" }
        }

        /** The number of codes, `N` in the payload. */
        val count: Int get() = inverse.size

        /** The DN a reader gets back for [dn]. */
        fun reconstruct(dn: Int): Int = inverse[forward[dn]]
    }

    /**
     * Builds the tables for [noise] above [blackLevel] with a step of [k] sigmas.
     * Mirrors the benchmark's construction exactly:
     *
     * ```
     * t = 0; for i in 0..1023: c = round(t); code[i] = c; t += min(1, 1 / (k * sigma(i)))
     * rec[c] = midpoint of the DNs with code c, rounded up; N = code[1023] + 1
     * ```
     */
    fun build(noise: NoiseModel, blackLevel: Int, k: Double = DEFAULT_K): Tables {
        require(blackLevel in 0..SAMPLE_MAX) { "black level must be 0..$SAMPLE_MAX" }
        require(k > 0.0 && k * 1000 <= 0xffff) { "k must be in (0, 65.535]" }
        val forward = IntArray(LUT_SIZE)
        val lo = IntArray(LUT_SIZE) { Int.MAX_VALUE }
        val hi = IntArray(LUT_SIZE) { -1 }
        var t = 0.0
        for (i in 0 until LUT_SIZE) {
            // Math.round is round-half-up, as lround for t >= 0.
            val c = Math.round(t).toInt()
            forward[i] = c
            lo[c] = min(lo[c], i)
            hi[c] = max(hi[c], i)
            t += min(1.0, 1.0 / (k * noise.sigma(i, blackLevel))) // sigma 0 -> step 1
        }
        val count = forward[LUT_SIZE - 1] + 1
        val inverse = IntArray(count) { c -> if (hi[c] < 0) c else (lo[c] + hi[c] + 1) / 2 }
        return Tables(forward, inverse, (k * 1000).roundToInt(), blackLevel)
    }

    /** The payload prefix: preamble and inverse table, `12 + 2 * N` bytes. */
    fun prefixBytes(count: Int): Int = PREAMBLE_BYTES + 2 * count

    /** A parsed payload prefix. [iso] is the unsigned 32-bit `isoUsed`. */
    class Prefix(val count: Int, val iso: Long, val kMilli: Int, val blackLevel: Int, val inverse: IntArray) {
        val bytes: Int get() = prefixBytes(count)
    }

    /**
     * Writes the payload prefix of [tables] for [iso] at `dst.position()` and
     * advances it; [dst]'s byte order is not changed.
     */
    fun writePrefix(tables: Tables, iso: Int, dst: ByteBuffer) {
        require(iso >= 0) { "iso must be non-negative" }
        require(dst.remaining() >= prefixBytes(tables.count)) { "prefix needs ${prefixBytes(tables.count)} bytes" }
        val base = dst.position()
        val order = dst.order()
        dst.order(ByteOrder.LITTLE_ENDIAN)
        try {
            dst.put(base, VERSION.toByte())
            dst.put(base + 1, 0.toByte())
            dst.putShort(base + 2, tables.count.toShort())
            dst.putInt(base + 4, iso)
            dst.putShort(base + 8, tables.kMilli.toShort())
            dst.putShort(base + 10, tables.blackLevel.toShort())
            tables.inverse.forEachIndexed { c, dn -> dst.putShort(base + PREAMBLE_BYTES + 2 * c, dn.toShort()) }
        } finally {
            dst.order(order)
        }
        dst.position(base + prefixBytes(tables.count))
    }

    /**
     * Reads the payload prefix at `src.position()` without moving it, or null when
     * it is malformed: an unknown version, a count outside 1..[LUT_SIZE], fewer
     * bytes than the prefix, or an inverse entry above 1023.
     */
    fun readPrefix(src: ByteBuffer): Prefix? {
        val base = src.position()
        if (src.remaining() < PREAMBLE_BYTES) return null
        val le = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (le.get(base).toInt() and 0xff != VERSION) return null
        val count = le.getShort(base + 2).toInt() and 0xffff
        if (count !in 1..LUT_SIZE || src.remaining() < prefixBytes(count)) return null
        val inverse = IntArray(count) { le.getShort(base + PREAMBLE_BYTES + 2 * it).toInt() and 0xffff }
        if (inverse.any { it > SAMPLE_MAX }) return null
        return Prefix(
            count = count,
            iso = le.getInt(base + 4).toLong() and 0xffffffffL,
            kMilli = le.getShort(base + 8).toInt() and 0xffff,
            blackLevel = le.getShort(base + 10).toInt() and 0xffff,
            inverse = inverse,
        )
    }

    /**
     * Maps the codes of tight RAW10 `buffer[position, position + bytes)` through
     * [inverse] in place. Returns false, leaving the buffer partly mapped, when a
     * code is [inverse]`.size` or more (a corrupt frame).
     */
    fun mapCodes(buffer: ByteBuffer, bytes: Int, inverse: IntArray): Boolean {
        require(bytes % 5 == 0 && bytes <= buffer.remaining()) { "bytes must be whole RAW10 groups within the buffer" }
        val base = buffer.position()
        val values = IntArray(4)
        var at = base
        val end = base + bytes
        while (at < end) {
            val low = buffer.get(at + 4).toInt() and 0xff
            for (k in 0 until 4) {
                val code = (buffer.get(at + k).toInt() and 0xff) shl 2 or ((low shr (2 * k)) and 3)
                if (code >= inverse.size) return false
                values[k] = inverse[code]
            }
            for (k in 0 until 4) buffer.put(at + k, (values[k] shr 2).toByte())
            buffer.put(at + 4, ((values[0] and 3) or ((values[1] and 3) shl 2) or ((values[2] and 3) shl 4) or ((values[3] and 3) shl 6)).toByte())
            at += 5
        }
        return true
    }

    /**
     * Decodes a quantised payload `src[position, limit)` to tight RAW10 DN at
     * `dst[position, ...)` with [decodeCodes], a block-pack decoder of the
     * container (`(container, dst) -> ok`), then [mapCodes]. Returns false when the
     * prefix, the container or a code is malformed. Positions are not changed.
     */
    fun decodeWith(
        src: ByteBuffer,
        width: Int,
        height: Int,
        dst: ByteBuffer,
        decodeCodes: (container: ByteBuffer, dst: ByteBuffer) -> Boolean,
    ): Boolean {
        val prefix = readPrefix(src) ?: return false
        val container = src.duplicate().apply { position(src.position() + prefix.bytes) }
        if (!decodeCodes(container, dst)) return false
        return mapCodes(dst.duplicate(), width / 4 * 5 * height, prefix.inverse)
    }
}
