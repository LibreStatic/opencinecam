/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** Reads the structure of a file a track is to be appended to. Throws [Mp4FormatException] when it cannot take one. */
internal fun readMovieForAppend(file: Mp4Bytes): Mp4Movie {
    val boxes = scanTopLevelBoxes(file)
    boxes.firstOrNull { it.openEnded }?.let { throw Mp4FormatException("open-ended-box", "'${it.type}' at ${it.offset} has size 0") }
    return readMovie(file, findSingleMoov(boxes))
}

/**
 * The part of appending a track that does not depend on its kind: the samples go into a new 64-bit `mdat` at the end of the
 * file, then [commit] writes the new `moov` after it, syncs, and only then retypes the old `moov` to `free`. The file is a
 * valid MP4 with the old `moov` until that four-byte write, and a valid MP4 with the new one after it. Not thread-safe.
 */
internal class Mp4AppendedMdat(
    private val file: Mp4Bytes,
    val movie: Mp4Movie,
    originalSize: Long,
) {
    val mdatOffset = originalSize
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(BUFFER_BYTES)

    /** The file position of the buffer's first byte. */
    private var bufferPosition = mdatOffset

    var payloadBytes = 0L
        private set
    private var started = false
    private var failed = false
    private var retyped = false

    /** The retype write was started and the old type is not known to be intact. */
    private var retyping = false
    private var closed = false

    /** Where the first sample goes. */
    val payloadOffset: Long get() = mdatOffset + MDAT_HEADER_BYTES

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        const val MDAT_HEADER_BYTES = 16
        private val MDAT = "mdat".toByteArray(StandardCharsets.ISO_8859_1)
        private val FREE = "free".toByteArray(StandardCharsets.ISO_8859_1)
        private val MOOV = "moov".toByteArray(StandardCharsets.ISO_8859_1)
    }

    fun checkOpen() {
        check(!closed && !retyped) { "The appender is finished or closed" }
        check(!failed) { "An earlier write failed" }
    }

    /** Appends [data]'s remaining bytes (at most the buffer's size) to the new `mdat`. */
    fun append(data: ByteBuffer) {
        val size = data.remaining()
        try {
            if (!started) {
                buffer.putInt(1)
                buffer.put(MDAT)
                buffer.putLong(MDAT_HEADER_BYTES.toLong())
                started = true
            }
            if (buffer.remaining() < size) flush()
            buffer.put(data.duplicate())
        } catch (e: Throwable) {
            failed = true
            throw e
        }
        payloadBytes += size
    }

    /** Seals the `mdat`, writes [moov] after it, syncs, then retypes the old `moov` to `free` and syncs again. */
    fun commit(moov: ByteArray) {
        try {
            flush()
            val mdatSize = MDAT_HEADER_BYTES + payloadBytes
            val largeSize = ByteBuffer.allocate(8)
            largeSize.putLong(0, mdatSize)
            file.writeFully(mdatOffset + 8, largeSize)
            file.writeFully(mdatOffset + mdatSize, ByteBuffer.wrap(moov))
            file.sync()
            retyping = true
            file.writeFully(movie.box.offset + 4, ByteBuffer.wrap(FREE))
            retyped = true
            file.sync()
        } catch (e: Throwable) {
            if (!retyped) {
                failed = true
                if (retyping) restoreMoovType(e)
            }
            throw e
        }
    }

    /**
     * The retype write failed and may have changed part of the type. Writes `moov` back so [close] can swallow the new `moov`; when
     * that fails too, the type is read back: a partial restore may have completed `moov`, and two movies would make the file
     * unreadable. Only when the old type is not `moov`, or cannot be read, does the new `moov` stay reachable as the file's movie.
     */
    private fun restoreMoovType(cause: Throwable) {
        try {
            file.writeFully(movie.box.offset + 4, ByteBuffer.wrap(MOOV))
            retyping = false
            file.sync()
        } catch (e: Throwable) {
            cause.addSuppressed(e)
            if (retyping) try {
                val type = ByteBuffer.allocate(MOOV.size)
                file.readFully(movie.box.offset + 4, type)
                if (type.array().contentEquals(MOOV)) retyping = false
            } catch (unreadable: Throwable) {
                cause.addSuppressed(unreadable)
            }
        }
    }

    /**
     * Before [commit]: leaves the file as it was read (the old moov is still the file's moov). Idempotent; a no-op after commit.
     *
     * Bytes already appended stay, since [Mp4Bytes] cannot truncate: the new `mdat` header is rewritten to run to the end of the
     * file, so it also swallows a new `moov` written before a failed (and undone) retype, and the file ends on a box boundary. Best effort:
     * I/O failures here are swallowed.
     */
    fun close() {
        if (closed) return
        closed = true
        // A retype that failed and could not be undone may have left the old moov unreadable: the new one must survive.
        if (retyped || retyping) return
        try {
            val end = file.size()
            if (end > mdatOffset) {
                val header = ByteBuffer.allocate(MDAT_HEADER_BYTES)
                header.putInt(1)
                header.put(MDAT)
                header.putLong(maxOf(MDAT_HEADER_BYTES.toLong(), end - mdatOffset))
                header.flip()
                file.writeFully(mdatOffset, header)
                file.sync()
            }
        } catch (ignored: IOException) {
        } catch (ignored: RuntimeException) {
        }
    }

    private fun flush() {
        if (buffer.position() == 0) return
        buffer.flip()
        val length = buffer.remaining()
        file.writeFully(bufferPosition, buffer)
        bufferPosition += length
        buffer.clear()
    }

    /** The id for a new track: past every existing one and the header's next id. */
    fun newTrackId(): Long {
        val trackId = maxOf(movie.tracks.maxOf { it.trackId }, movie.header.nextTrackId - 1) + 1
        if (trackId >= U32_MAX) throw Mp4FormatException("track-id", "no track id left after ${trackId - 1}")
        return trackId
    }

    /** The new `moov`: every existing child unchanged but `mvhd`, and the track [trak] writes after the last `trak`. */
    fun buildMovie(trackId: Long, presentation: Long, extraCapacity: Int, trak: Mp4BoxWriter.() -> Unit): ByteArray {
        val header = movie.header
        val data = movie.bytes
        val lastTrak = movie.children.indexOfLast { it.type == "trak" }
        val writer = Mp4BoxWriter(data.size + extraCapacity + 4096)
        writer.box("moov") {
            movie.children.forEachIndexed { index, child ->
                if (child.offset == header.child.offset) {
                    bytes(patchedMovieHeader(trackId, presentation))
                } else {
                    bytes(data, child.offset, child.size)
                }
                if (index == lastTrak) trak()
            }
        }
        return writer.toByteArray()
    }

    /** `mvhd` with the next track id past [trackId] and the duration extended to [presentation] when that is longer. */
    private fun patchedMovieHeader(trackId: Long, presentation: Long): ByteArray {
        val header = movie.header
        val start = header.child.offset
        val copy = movie.bytes.copyOfRange(start, header.child.end)
        putU32(copy, header.nextTrackIdOffset - start, trackId + 1)
        val duration = maxOf(header.duration, presentation)
        if (header.version == 1) {
            putU64(copy, header.durationOffset - start, duration)
        } else if (duration <= U32_MAX) {
            putU32(copy, header.durationOffset - start, duration)
        }
        return copy
    }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) target[offset + i] = (value ushr (24 - 8 * i)).toByte()
    }

    private fun putU64(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) target[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }
}

/** One `elst` entry; [segmentDuration] is in the movie timescale, [mediaTime] in the media's (−1 for an empty edit). */
internal class Mp4Edit(var segmentDuration: Long, val mediaTime: Long)

internal fun Mp4BoxWriter.writeEditList(edits: List<Mp4Edit>) {
    box("edts") {
        val elstVersion = if (edits.all { it.segmentDuration <= U32_MAX && it.mediaTime in -1L..Int.MAX_VALUE.toLong() }) 0 else 1
        fullBox("elst", elstVersion, 0) {
            u32(edits.size)
            for (edit in edits) {
                if (elstVersion == 1) {
                    u64(edit.segmentDuration)
                    u64(edit.mediaTime)
                } else {
                    u32(edit.segmentDuration)
                    u32(edit.mediaTime.toInt())
                }
                u16(1) // media_rate 1.0
                u16(0)
            }
        }
    }
}

internal fun Mp4BoxWriter.writeTimes(version: Int, creation: Long, modification: Long) {
    if (version == 1) {
        u64(creation)
        u64(modification)
    } else {
        u32(creation)
        u32(modification)
    }
}

internal fun Mp4BoxWriter.writeIdentityMatrix() {
    for (value in intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)) u32(value)
}

/** A self-contained `dinf`: one `url ` entry flagged as "media in this file". */
internal fun Mp4BoxWriter.writeSelfContainedDataInformation() {
    box("dinf") {
        fullBox("dref", 0, 0) {
            u32(1)
            fullBox("url ", 0, 1) {}
        }
    }
}

/** `stsc` for [sampleCount] samples in chunks of [perChunk], the last one possibly shorter: one or two entries. */
internal fun Mp4BoxWriter.writeSampleToChunk(sampleCount: Int, perChunk: Int) {
    val chunkCount = (sampleCount + perChunk - 1) / perChunk
    val lastChunkSamples = sampleCount - (chunkCount - 1) * perChunk
    fullBox("stsc", 0, 0) {
        if (chunkCount == 1) {
            u32(1)
            u32(1)
            u32(sampleCount)
            u32(1)
        } else {
            val shortLast = lastChunkSamples != perChunk
            u32(if (shortLast) 2 else 1)
            u32(1)
            u32(perChunk)
            u32(1)
            if (shortLast) {
                u32(chunkCount)
                u32(lastChunkSamples)
                u32(1)
            }
        }
    }
}

/** `stco`, or `co64` when the last chunk starts past 4 GiB. */
internal fun Mp4BoxWriter.writeChunkOffsets(offsets: LongArray) {
    if (offsets.last() <= U32_MAX) {
        fullBox("stco", 0, 0) {
            u32(offsets.size)
            for (offset in offsets) u32(offset)
        }
    } else {
        fullBox("co64", 0, 0) {
            u32(offsets.size)
            for (offset in offsets) u64(offset)
        }
    }
}
