// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.raw

import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RAW10 frame codecs in `libocc_rawcodec.so` (OCC-PLAN-071 phase 2): two lossless,
 * and the near-lossless quantised block-pack.
 *
 * Frames are Android `RAW10` rows; the encoded container and both stream formats
 * are specified in `native-pipeline/include/opencinecam/raw_codecs.h`. Check
 * [available] first: without the native library (host JVM tests, Robolectric)
 * nothing here works and [newEncoder] throws.
 */
object NativeRawCodec {
    /** Codec ids, equal to the OCRAW frame header encodings. */
    enum class Codec(val encoding: Int) {
        /** ITU-T T.81 lossless JPEG, DNG CFA layout (DNG Compression 7). */
        LJ92(OcrawFrameHeader.ENCODING_LJ92),

        /** Same-colour delta, 16-sample blocks sized by their largest residual. */
        BLOCK_PACK(OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK),

        /**
         * [BLOCK_PACK] of noise-quantised codes, with the inverse table in the payload
         * ([RawQuantisation]). Encoders need [Encoder.setQuantisation]; decoding yields
         * the reconstructed DN.
         */
        BLOCK_PACK_QUANTISED(OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED),
    }

    /** Inner-loop implementation; [AUTO] picks NEON when the library has it. */
    enum class Kernel(internal val id: Int) { AUTO(0), SCALAR(1), NEON(2) }

    /** True when the native library loaded. */
    val available: Boolean get() = RawCodecJni.loaded

    /** True when the library was built with the NEON kernels (arm64). */
    val neonAvailable: Boolean get() = available && RawCodecJni.nativeNeonAvailable()

    /**
     * An encoder with its own pool of [threads] (the calling thread counts as one,
     * so `threads - 1` workers start now and stop on [Encoder.close]).
     */
    fun newEncoder(
        codec: Codec,
        threads: Int,
        strips: Int = 8,
        lj92Predictor: Int = 1,
        lj92OptimalHuffman: Boolean = true,
        kernel: Kernel = Kernel.AUTO,
    ): Encoder {
        check(available) { "libocc_rawcodec is not available" }
        require(threads in 1..64) { "threads must be 1..64" }
        require(strips in 1..64) { "strips must be 1..64" }
        require(lj92Predictor in 1..7) { "LJ92 predictor must be 1..7" }
        return Encoder(codec, threads, strips, lj92Predictor, lj92OptimalHuffman, kernel)
    }

    /**
     * A decoder for any codec with its own pool of [threads] (the calling thread
     * counts as one). The codec is chosen per call, since a take mixes encodings.
     */
    fun newDecoder(threads: Int): Decoder {
        check(available) { "libocc_rawcodec is not available" }
        require(threads in 1..64) { "threads must be 1..64" }
        return Decoder(threads)
    }

    /**
     * A decode-only thread pool. Not thread-safe: one decode at a time. Buffers
     * must be direct; their positions and limits are never changed.
     */
    class Decoder internal constructor(val threads: Int) : AutoCloseable {
        private var pool: Long = RawCodecJni.nativeCreatePool(threads)

        /**
         * Decodes `src[position, limit)`, encoded with [codec], into RAW10 rows of
         * [dstRowBytes] at `dst[position, ...)`. Returns false on malformed input.
         */
        fun decode(
            codec: Codec,
            src: ByteBuffer,
            width: Int,
            height: Int,
            dst: ByteBuffer,
            dstRowBytes: Int = width / 4 * 5,
        ): Boolean {
            check(pool != 0L) { "decoder is closed" }
            require(src.isDirect && dst.isDirect) { "buffers must be direct" }
            require(dstRowBytes >= width / 4 * 5) { "dstRowBytes too small" }
            require(dst.remaining().toLong() >= dstRowBytes.toLong() * height) { "destination too small" }
            return RawCodecJni.nativeDecode(
                pool, codec.encoding, src, src.position(), src.remaining(), width, height,
                dst, dst.position(), dstRowBytes,
            )
        }

        override fun close() {
            if (pool != 0L) {
                RawCodecJni.nativeDestroyPool(pool)
                pool = 0L
            }
        }
    }

    /**
     * One codec configuration and its thread pool. Not thread-safe: one encode or
     * decode at a time. Buffers must be direct; their positions and limits are
     * never changed.
     */
    class Encoder internal constructor(
        val codec: Codec,
        val threads: Int,
        val strips: Int,
        val lj92Predictor: Int,
        val lj92OptimalHuffman: Boolean,
        val kernel: Kernel,
    ) : AutoCloseable {
        private var pool: Long = RawCodecJni.nativeCreatePool(threads)

        /** [BLOCK_PACK_QUANTISED] tables: forward table, then the payload prefix (see the JNI). */
        private var quant: ByteBuffer? = null

        /** The tables set by [setQuantisation], or null. */
        var quantisation: RawQuantisation.Tables? = null
            private set

        /** The `isoUsed` set by [setQuantisation]. */
        var quantisationIso: Int = 0
            private set

        /**
         * Sets the tables of a [Codec.BLOCK_PACK_QUANTISED] encoder, used by every following
         * [encode] until the next call; [iso] is written to each frame's preamble. Copies the
         * tables once into a reused direct buffer, so call it when the ISO or noise model
         * changes, not per frame.
         */
        fun setQuantisation(tables: RawQuantisation.Tables, iso: Int) {
            check(codec == Codec.BLOCK_PACK_QUANTISED) { "only the quantised block-pack encoder takes tables" }
            require(iso >= 0) { "iso must be non-negative" }
            val buffer = quant ?: ByteBuffer.allocateDirect(2 * RawQuantisation.LUT_SIZE + RawQuantisation.prefixBytes(RawQuantisation.LUT_SIZE))
                .order(ByteOrder.LITTLE_ENDIAN)
                .also { quant = it }
            buffer.clear()
            tables.forward.forEach { buffer.putShort(it.toShort()) }
            RawQuantisation.writePrefix(tables, iso, buffer)
            quantisation = tables
            quantisationIso = iso
        }

        /**
         * [setQuantisation] from raw tables: [forward] ([RawQuantisation.LUT_SIZE] codes, each
         * below `inverse.size`), [inverse] (1..1024 DN of 0..1023), and the informative
         * preamble fields.
         */
        fun setQuantisation(forward: IntArray, inverse: IntArray, iso: Int, kMilli: Int, blackLevel: Int) =
            setQuantisation(RawQuantisation.Tables(forward, inverse, kMilli, blackLevel), iso)

        /** Worst-case encoded size of a [width] x [height] frame. */
        fun maxEncodedSize(width: Int, height: Int): Long =
            RawCodecJni.nativeMaxEncodedSize(codec.encoding, width, height, strips, lj92Predictor, lj92OptimalHuffman)

        /**
         * Encodes the RAW10 frame at `src[position, ...)` (rows of [rowBytes], tight
         * by default) into `dst[position, limit)`. Returns the encoded size, or 0
         * when it does not fit, in which case the caller stores the frame
         * uncompressed.
         */
        fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer, rowBytes: Int = width / 4 * 5): Int {
            val handle = open()
            require(src.isDirect && dst.isDirect) { "buffers must be direct" }
            require(width > 0 && height > 0 && width % 4 == 0 && height % 2 == 0) { "bad frame size ${width}x$height" }
            require(rowBytes >= width / 4 * 5) { "rowBytes too small" }
            require(src.remaining().toLong() >= rowBytes.toLong() * height) { "source too small" }
            check(codec != Codec.BLOCK_PACK_QUANTISED || quant != null) { "setQuantisation first" }
            val size = RawCodecJni.nativeEncode(
                handle, codec.encoding, src, src.position(), width, height, rowBytes,
                dst, dst.position(), dst.remaining(), strips, lj92Predictor, lj92OptimalHuffman, kernel.id, quant,
            )
            check(size >= 0) { "native encode rejected its arguments" }
            return size
        }

        /**
         * Decodes `src[position, limit)` into RAW10 rows of [dstRowBytes] at
         * `dst[position, ...)`. Returns false on malformed input.
         */
        fun decode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer, dstRowBytes: Int = width / 4 * 5): Boolean {
            val handle = open()
            require(src.isDirect && dst.isDirect) { "buffers must be direct" }
            require(dstRowBytes >= width / 4 * 5) { "dstRowBytes too small" }
            require(dst.remaining().toLong() >= dstRowBytes.toLong() * height) { "destination too small" }
            return RawCodecJni.nativeDecode(
                handle, codec.encoding, src, src.position(), src.remaining(), width, height,
                dst, dst.position(), dstRowBytes,
            )
        }

        private fun open(): Long {
            check(pool != 0L) { "encoder is closed" }
            return pool
        }

        override fun close() {
            if (pool != 0L) {
                RawCodecJni.nativeDestroyPool(pool)
                pool = 0L
            }
        }
    }
}
