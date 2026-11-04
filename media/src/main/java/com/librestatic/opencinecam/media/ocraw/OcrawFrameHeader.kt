// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 64-byte little-endian header that starts every `RAW_FRAME` (0x0010)
 * payload; the frame data follows it. See `docs/formats/opencine-raw-v1.md`.
 *
 * | Offset | Size | Field |
 * |---|---|---|
 * | 0 | 2 | header version (1) |
 * | 2 | 2 | encoding |
 * | 4 | 4 | width (pixels) |
 * | 8 | 4 | height (rows) |
 * | 12 | 4 | row bytes |
 * | 16 | 4 | crop left (pixels, within the sensor pixel array) |
 * | 20 | 4 | crop top (rows) |
 * | 24 | 8 | sensor timestamp ns |
 * | 32 | 8 | frame number |
 * | 40 | 4 | data length |
 * | 44 | 20 | reserved, zero |
 */
data class OcrawFrameHeader(
    val width: Int,
    val height: Int,
    val rowBytes: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val sensorTimestampNs: Long,
    val frameNumber: Long,
    val dataLength: Int,
    val encoding: Int = ENCODING_RAW10,
    val headerVersion: Int = VERSION,
) {
    init {
        require(headerVersion == VERSION) { "unsupported frame header version $headerVersion" }
        require(encoding in 0..0xffff) { "encoding must fit uint16" }
        require(width > 0 && height > 0) { "frame dimensions must be positive" }
        require(rowBytes > 0) { "row bytes must be positive" }
        require(cropLeft >= 0 && cropTop >= 0) { "crop origin must be non-negative" }
        require(cropLeft % 2 == 0 && cropTop % 2 == 0) { "crop origin must be even to keep the CFA phase" }
        require(sensorTimestampNs >= 0) { "sensor timestamp must be non-negative" }
        require(frameNumber >= 0) { "frame number must be non-negative" }
        require(dataLength >= 0) { "data length must be non-negative" }
        require(dataLength <= OcrawCodec.MAX_CHUNK_PAYLOAD - BYTES) { "frame exceeds the bounded payload" }
        if (encoding == ENCODING_RAW10) {
            require(width % 4 == 0) { "RAW10 width must be a multiple of 4" }
            require(rowBytes == width / 4 * 5) { "RAW10 rows are tightly packed: rowBytes must be width * 5 / 4" }
            require(dataLength.toLong() == rowBytes.toLong() * height) { "RAW10 data length must be rowBytes * height" }
        }
    }

    /** Writes the 64 bytes at `dst.position()` and advances it; [dst]'s byte order is not changed. */
    fun writeTo(dst: ByteBuffer) {
        require(dst.remaining() >= BYTES) { "frame header needs $BYTES bytes" }
        val base = dst.position()
        val order = dst.order()
        dst.order(ByteOrder.LITTLE_ENDIAN)
        try {
            dst.putShort(base, headerVersion.toShort())
            dst.putShort(base + 2, encoding.toShort())
            dst.putInt(base + 4, width)
            dst.putInt(base + 8, height)
            dst.putInt(base + 12, rowBytes)
            dst.putInt(base + 16, cropLeft)
            dst.putInt(base + 20, cropTop)
            dst.putLong(base + 24, sensorTimestampNs)
            dst.putLong(base + 32, frameNumber)
            dst.putInt(base + 40, dataLength)
            for (offset in 44 until BYTES step 4) dst.putInt(base + offset, 0)
        } finally {
            dst.order(order)
        }
        dst.position(base + BYTES)
    }

    fun encode(): ByteArray = ByteBuffer.allocate(BYTES).also(::writeTo).array()

    companion object {
        const val BYTES = 64
        const val VERSION = 1

        /** Android `ImageFormat.RAW10` packing, rows tight, no stride padding. */
        const val ENCODING_RAW10 = 0

        /**
         * Lossless JPEG (ITU-T T.81 SOF3) strips in DNG CFA layout, in the container of
         * `native-pipeline/include/opencinecam/raw_codecs.h`. [rowBytes] stays the RAW10 row
         * length of the decoded frame; [dataLength] is the encoded size.
         */
        const val ENCODING_LJ92 = 1

        /** The same-colour delta Bayer block-pack codec, same container and [rowBytes] rule as [ENCODING_LJ92]. */
        const val ENCODING_BAYER_BLOCK_PACK = 2

        /**
         * Near-lossless block-pack: RAW10 DN mapped through a noise-adaptive table before
         * [ENCODING_BAYER_BLOCK_PACK] coding. The frame data is a preamble, the inverse table and
         * the block-pack container of the codes (`raw_codecs.h`); same [rowBytes] rule as
         * [ENCODING_LJ92]. A reader that knows only encoding 2 must not decode it as such.
         */
        const val ENCODING_BLOCK_PACK_QUANTISED = 3

        /** Tightly packed RAW10 row length for [width] pixels. */
        fun raw10RowBytes(width: Int): Int {
            require(width > 0 && width % 4 == 0) { "RAW10 width must be a positive multiple of 4" }
            return width / 4 * 5
        }

        /** A RAW10 header for a tightly packed `width x height` frame. */
        fun raw10(
            width: Int,
            height: Int,
            cropLeft: Int,
            cropTop: Int,
            sensorTimestampNs: Long,
            frameNumber: Long,
        ): OcrawFrameHeader {
            val rowBytes = raw10RowBytes(width)
            val length = rowBytes.toLong() * height
            require(length <= OcrawCodec.MAX_CHUNK_PAYLOAD - BYTES) { "frame exceeds the bounded payload" }
            return OcrawFrameHeader(width, height, rowBytes, cropLeft, cropTop, sensorTimestampNs, frameNumber,
                length.toInt())
        }

        /**
         * Reads 64 bytes at `src.position()` and advances it. Throws
         * [IllegalArgumentException] for a truncated or invalid header.
         * Reserved bytes are ignored so later writers may use them.
         */
        fun readFrom(src: ByteBuffer): OcrawFrameHeader {
            require(src.remaining() >= BYTES) { "truncated frame header" }
            val base = src.position()
            val order = src.order()
            src.order(ByteOrder.LITTLE_ENDIAN)
            val header = try {
                val version = src.getShort(base).toInt() and 0xffff
                require(version == VERSION) { "unsupported frame header version $version" }
                val width = src.getInt(base + 4)
                val height = src.getInt(base + 8)
                val rowBytes = src.getInt(base + 12)
                val cropLeft = src.getInt(base + 16)
                val cropTop = src.getInt(base + 20)
                val timestamp = src.getLong(base + 24)
                val frameNumber = src.getLong(base + 32)
                val dataLength = src.getInt(base + 40)
                OcrawFrameHeader(
                    width = width,
                    height = height,
                    rowBytes = rowBytes,
                    cropLeft = cropLeft,
                    cropTop = cropTop,
                    sensorTimestampNs = timestamp,
                    frameNumber = frameNumber,
                    dataLength = dataLength,
                    encoding = src.getShort(base + 2).toInt() and 0xffff,
                    headerVersion = version,
                )
            } finally {
                src.order(order)
            }
            src.position(base + BYTES)
            return header
        }

        fun decode(encoded: ByteArray, offset: Int = 0): OcrawFrameHeader {
            require(offset >= 0 && encoded.size - offset >= BYTES) { "truncated frame header" }
            return readFrom(ByteBuffer.wrap(encoded, offset, BYTES))
        }
    }
}
