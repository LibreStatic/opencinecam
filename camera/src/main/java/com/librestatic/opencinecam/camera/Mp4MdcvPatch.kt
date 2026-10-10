/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer

/**
 * Corrects the mastering minimum luminance in the `mdcv` box of an HDR10 MP4.
 *
 * Android's MPEG4Writer multiplies both mastering luminances from KEY_HDR_STATIC_INFO by 10000.
 * That is right for the maximum (cd/m² to 0.0001 cd/m²) but the minimum is already in 0.0001
 * cd/m², so 0.005 cd/m² lands in the container as 50 cd/m². The HEVC SEI is correct; only the
 * box is rewritten, and only when it holds exactly the value that bug produces.
 */
internal object Mp4MdcvPatch {
    enum class Outcome { PATCHED, ALREADY_CORRECT, UNEXPECTED_VALUE, NOT_FOUND }

    /** Positional access to the finished file; the muxer must be stopped. */
    interface Io {
        val size: Long
        /** Reads up to [length] bytes at file [position] into [into] at [offset]; returns the count, or -1 at EOF. */
        fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int
        fun write(position: Long, bytes: ByteArray)
    }

    private const val MDCV_SIZE = 32
    private const val MIN_LUMINANCE_OFFSET = 28 // header 8, primaries and white point 16, max 4
    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024
    private const val VISUAL_SAMPLE_ENTRY_BODY = 78
    private val CONTAINERS = setOf("trak", "mdia", "minf", "stbl")
    private val VIDEO_ENTRIES = setOf("hvc1", "hev1")

    fun patch(io: Io, minimumUnits: Int = Hdr10Video.MASTERING_MIN_UNITS): Outcome {
        val moov = findMoov(io) ?: return Outcome.NOT_FOUND
        val offset = findMdcv(moov.second, 8, moov.second.size, "moov") ?: return Outcome.NOT_FOUND
        val stored = ByteBuffer.wrap(moov.second, offset + MIN_LUMINANCE_OFFSET, 4).int
        return when (stored) {
            minimumUnits -> Outcome.ALREADY_CORRECT
            minimumUnits * 10_000 -> {
                io.write(moov.first + offset + MIN_LUMINANCE_OFFSET, ByteBuffer.allocate(4).putInt(minimumUnits).array())
                Outcome.PATCHED
            }
            else -> Outcome.UNEXPECTED_VALUE
        }
    }

    /** The file offset and bytes of the top-level `moov` box, walking top-level headers only. */
    private fun findMoov(io: Io): Pair<Long, ByteArray>? {
        var position = 0L
        val header = ByteArray(16)
        while (position + 8 <= io.size) {
            if (readFully(io, position, header, 8) < 8) return null
            val buffer = ByteBuffer.wrap(header)
            var size = buffer.int.toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.US_ASCII)
            when (size) {
                1L -> {
                    if (readFully(io, position + 8, header, 8) < 8) return null
                    size = ByteBuffer.wrap(header, 0, 8).long
                }
                0L -> size = io.size - position
            }
            if (size < 8 || position + size > io.size) return null
            if (type == "moov") {
                if (size > MAX_MOOV_BYTES) return null
                val bytes = ByteArray(size.toInt())
                return if (readFully(io, position, bytes, bytes.size) == bytes.size) position to bytes else null
            }
            position += size
        }
        return null
    }

    /** Offset of the `mdcv` box inside [bytes], descending moov → trak → … → hvc1. */
    private fun findMdcv(bytes: ByteArray, start: Int, end: Int, parent: String): Int? {
        var position = start
        while (position + 8 <= end) {
            val size = ByteBuffer.wrap(bytes, position, 4).int.toLong() and 0xFFFFFFFFL
            if (size < 8 || position + size > end) return null
            val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
            val boxEnd = (position + size).toInt()
            val found = when {
                type == "mdcv" && parent in VIDEO_ENTRIES -> if (size.toInt() == MDCV_SIZE) position else null
                type in CONTAINERS -> findMdcv(bytes, position + 8, boxEnd, type)
                // stsd is a full box (version/flags) followed by an entry count.
                type == "stsd" -> findMdcv(bytes, position + 16, boxEnd, type)
                type in VIDEO_ENTRIES && parent == "stsd" -> findMdcv(bytes, position + 8 + VISUAL_SAMPLE_ENTRY_BODY, boxEnd, type)
                else -> null
            }
            if (found != null) return found
            position = boxEnd
        }
        return null
    }

    private fun readFully(io: Io, position: Long, into: ByteArray, length: Int): Int {
        var done = 0
        while (done < length) {
            val read = io.read(position + done, into, done, length - done)
            if (read <= 0) break
            done += read
        }
        return done
    }
}
