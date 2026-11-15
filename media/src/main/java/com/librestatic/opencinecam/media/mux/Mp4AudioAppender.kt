/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** A finished AAC-LC stream to add to an MP4. */
class Mp4AacTrack(
    val sampleRateHz: Int,
    val channels: Int,
    /** The AudioSpecificConfig the encoder produced (csd-0). */
    val audioSpecificConfig: ByteArray,
    val bitrateBps: Int,
    /** PCM frames at the start of the stream that are encoder delay, not sound. */
    val primingFrames: Int,
    /** PCM frames of real sound after the priming. */
    val validFrames: Long,
    /** Where the first valid PCM frame sits on the file's timeline, microseconds; negative when the audio started before the video. */
    val startOffsetUs: Long,
)

/**
 * Adds an AAC track to a video-only MP4 in place, without copying the video.
 *
 * The access units go into a new `mdat` at the end of the file and a new `moov` follows it; only then is the old `moov` retyped to
 * `free`. The file is a valid MP4 with the old `moov` until that four-byte write, and a valid MP4 with the new one after it.
 * Not thread-safe.
 */
class Mp4AudioAppender private constructor(
    private val file: Mp4Bytes,
    private val movie: Mp4Movie,
    originalSize: Long,
) : AutoCloseable {
    private val mdatOffset = originalSize
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(BUFFER_BYTES)

    /** The file position of the buffer's first byte. */
    private var bufferPosition = mdatOffset
    private var unitSizes = IntArray(1024)
    private var unitCount = 0
    private var payloadBytes = 0L
    private var largestUnit = 0
    private var started = false
    private var failed = false
    private var retyped = false

    /** The retype write was started and the old type is not known to be intact. */
    private var retyping = false
    private var closed = false

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        private const val MDAT_HEADER_BYTES = 16
        private const val FRAMES_PER_UNIT = 1024
        private const val UNITS_PER_CHUNK = 32
        private const val MAX_UNIT_BYTES_PER_CHANNEL = 8 * 1024

        /** Units are checked against this before the channel count is known, and against the real count in [finish]. */
        private const val MAX_CHANNELS = 8
        private val MDAT = "mdat".toByteArray(StandardCharsets.ISO_8859_1)
        private val FREE = "free".toByteArray(StandardCharsets.ISO_8859_1)
        private val MOOV = "moov".toByteArray(StandardCharsets.ISO_8859_1)

        /** Reads the file's structure and starts a new `mdat` at its end. Throws [Mp4FormatException] and changes nothing when the file cannot take a track. */
        fun begin(file: Mp4Bytes): Mp4AudioAppender {
            val originalSize = file.size()
            val boxes = scanTopLevelBoxes(file)
            boxes.firstOrNull { it.openEnded }?.let { throw Mp4FormatException("open-ended-box", "'${it.type}' at ${it.offset} has size 0") }
            val movie = readMovie(file, findSingleMoov(boxes))
            if (movie.tracks.any { it.handlerType == "soun" }) throw Mp4FormatException("has-audio-track")
            if (movie.tracks.none { it.handlerType == "vide" }) throw Mp4FormatException("no-video-track")
            return Mp4AudioAppender(file, movie, originalSize)
        }

        /** An MPEG-4 descriptor length in its minimal form: one byte under 128, otherwise 7-bit groups with the high bit on all but the last. */
        internal fun encodeDescriptorLength(length: Int): ByteArray {
            require(length in 0..0x0FFFFFFF) { "Descriptor length $length out of range" }
            if (length < 128) return byteArrayOf(length.toByte())
            val groups = ArrayList<Int>()
            var rest = length
            while (rest > 0) {
                groups.add(rest and 0x7F)
                rest = rest ushr 7
            }
            groups.reverse()
            return ByteArray(groups.size) { i -> (if (i < groups.size - 1) groups[i] or 0x80 else groups[i]).toByte() }
        }

        private fun descriptor(tag: Int, body: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + encodeDescriptorLength(body.size) + body

        /** The `esds` payload after its version and flags: ES_Descriptor > DecoderConfigDescriptor > DecoderSpecificInfo, SLConfigDescriptor. */
        internal fun esDescriptor(track: Mp4AacTrack): ByteArray {
            val bufferSize = 768 * track.channels
            val bitrate = track.bitrateBps
            val config = byteArrayOf(
                0x40,
                // streamType 5 (audio), upstream 0, reserved 1.
                0x15,
                (bufferSize ushr 16).toByte(), (bufferSize ushr 8).toByte(), bufferSize.toByte(),
                (bitrate ushr 24).toByte(), (bitrate ushr 16).toByte(), (bitrate ushr 8).toByte(), bitrate.toByte(),
                (bitrate ushr 24).toByte(), (bitrate ushr 16).toByte(), (bitrate ushr 8).toByte(), bitrate.toByte(),
            )
            val decoderConfig = descriptor(0x04, config + descriptor(0x05, track.audioSpecificConfig))
            val slConfig = descriptor(0x06, byteArrayOf(0x02))
            return descriptor(0x03, byteArrayOf(0, 0, 0) + decoderConfig + slConfig)
        }
    }

    /** Appends one AAC access unit (1024 PCM frames each). */
    fun writeAccessUnit(data: ByteBuffer) {
        checkOpen()
        val size = data.remaining()
        if (size == 0 || size > MAX_UNIT_BYTES_PER_CHANNEL * MAX_CHANNELS) throw Mp4FormatException("access-unit-size", "an access unit of $size bytes")
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
        if (unitCount == unitSizes.size) unitSizes = unitSizes.copyOf(unitCount * 2)
        unitSizes[unitCount++] = size
        payloadBytes += size
        if (size > largestUnit) largestUnit = size
    }

    /** Seals the `mdat`, writes the new `moov`, syncs, then retypes the old `moov` to `free` and syncs again. */
    fun finish(track: Mp4AacTrack) {
        checkOpen()
        if (unitCount == 0) throw Mp4FormatException("no-audio", "no access units were written")
        require(track.sampleRateHz > 0) { "Sample rate ${track.sampleRateHz}" }
        require(track.channels in 1..MAX_CHANNELS) { "Channel count ${track.channels}" }
        require(track.primingFrames >= 0) { "Priming frames ${track.primingFrames}" }
        if (largestUnit > MAX_UNIT_BYTES_PER_CHANNEL * track.channels) {
            throw Mp4FormatException("access-unit-size", "an access unit of $largestUnit bytes for ${track.channels} channels")
        }
        // Built before anything else is written, so a refused track leaves only the unsealed mdat for close() to seal.
        val moov = buildMovie(track)
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
     * Before [finish]: leaves the file as it was read (the old moov is still the file's moov). Idempotent; a no-op after finish.
     *
     * Bytes already appended stay, since [Mp4Bytes] cannot truncate: the new `mdat` header is rewritten to run to the end of the
     * file, so it also swallows a new `moov` written before a failed (and undone) retype, and the file ends on a box boundary. Best effort:
     * I/O failures here are swallowed.
     */
    override fun close() {
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

    private fun checkOpen() {
        check(!closed && !retyped) { "The appender is finished or closed" }
        check(!failed) { "An earlier write failed" }
    }

    private fun flush() {
        if (buffer.position() == 0) return
        buffer.flip()
        val length = buffer.remaining()
        file.writeFully(bufferPosition, buffer)
        bufferPosition += length
        buffer.clear()
    }

    /** One `elst` entry; [segmentDuration] is in the movie timescale, [mediaTime] in the audio's (−1 for an empty edit). */
    private class Edit(var segmentDuration: Long, val mediaTime: Long)

    private fun audioEdits(track: Mp4AacTrack): List<Edit> {
        val movieTimescale = movie.header.timescale
        val rate = track.sampleRateHz.toLong()
        // A stream cannot present more than its units hold.
        val validFrames = minOf(track.validFrames, unitCount.toLong() * FRAMES_PER_UNIT - track.primingFrames)
        if (validFrames <= 0) throw Mp4FormatException("no-audio", "no valid frames after the priming")
        val edits = ArrayList<Edit>()
        if (track.startOffsetUs >= 0) {
            val empty = rescale(track.startOffsetUs, 1_000_000, movieTimescale)
            if (empty > 0) edits += Edit(empty, -1)
            edits += Edit(rescale(validFrames, rate, movieTimescale), track.primingFrames.toLong())
        } else {
            val skipped = -rescale(track.startOffsetUs, 1_000_000, rate)
            val remaining = validFrames - skipped
            if (remaining <= 0) throw Mp4FormatException("audio-before-video", "the audio ends ${track.startOffsetUs} µs before the video starts")
            edits += Edit(rescale(remaining, rate, movieTimescale), track.primingFrames + skipped)
        }
        val videoDuration = movie.tracks.filter { it.handlerType == "vide" }.maxOf { it.duration }
        val end = edits.sumOf { it.segmentDuration }
        if (videoDuration > 0 && end > videoDuration) {
            val last = edits.last()
            last.segmentDuration -= end - videoDuration
            if (last.segmentDuration <= 0) throw Mp4FormatException("audio-after-video", "the audio starts after the video ends")
        }
        return edits
    }

    private fun buildMovie(track: Mp4AacTrack): ByteArray {
        val header = movie.header
        val data = movie.bytes
        val trackId = maxOf(movie.tracks.maxOf { it.trackId }, header.nextTrackId - 1) + 1
        if (trackId >= U32_MAX) throw Mp4FormatException("track-id", "no track id left after ${trackId - 1}")
        val edits = audioEdits(track)
        val presentation = edits.sumOf { it.segmentDuration }
        val lastTrak = movie.children.indexOfLast { it.type == "trak" }
        val writer = Mp4BoxWriter(data.size + unitCount * 4 + 4096)
        writer.box("moov") {
            movie.children.forEachIndexed { index, child ->
                if (child.offset == header.child.offset) {
                    bytes(patchedMovieHeader(trackId, presentation))
                } else {
                    bytes(data, child.offset, child.size)
                }
                if (index == lastTrak) audioTrak(this, track, trackId, edits, presentation)
            }
        }
        return writer.toByteArray()
    }

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

    private fun audioTrak(w: Mp4BoxWriter, track: Mp4AacTrack, trackId: Long, edits: List<Edit>, presentation: Long) {
        val creation = movie.header.creationTime
        val modification = movie.header.modificationTime
        val timesFit = creation in 0..U32_MAX && modification in 0..U32_MAX
        val mediaDuration = unitCount.toLong() * FRAMES_PER_UNIT
        w.box("trak") {
            val tkhdVersion = if (timesFit && presentation <= U32_MAX) 0 else 1
            fullBox("tkhd", tkhdVersion, 0x000007) {
                writeTimes(this, tkhdVersion, creation, modification)
                u32(trackId)
                u32(0)
                if (tkhdVersion == 1) u64(presentation) else u32(presentation)
                u32(0)
                u32(0)
                u16(0) // layer
                u16(0) // alternate_group
                u16(0x0100) // volume 1.0
                u16(0)
                writeIdentityMatrix(this)
                u32(0) // width
                u32(0) // height
            }
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
            box("mdia") {
                val mdhdVersion = if (timesFit && mediaDuration <= U32_MAX) 0 else 1
                fullBox("mdhd", mdhdVersion, 0) {
                    writeTimes(this, mdhdVersion, creation, modification)
                    u32(track.sampleRateHz)
                    if (mdhdVersion == 1) u64(mediaDuration) else u32(mediaDuration)
                    u16(0x55C4) // 'und'
                    u16(0)
                }
                fullBox("hdlr", 0, 0) {
                    u32(0)
                    fourcc("soun")
                    u32(0)
                    u32(0)
                    u32(0)
                    bytes("SoundHandler\u0000".toByteArray(StandardCharsets.US_ASCII))
                }
                box("minf") {
                    fullBox("smhd", 0, 0) {
                        u16(0) // balance
                        u16(0)
                    }
                    box("dinf") {
                        fullBox("dref", 0, 0) {
                            u32(1)
                            fullBox("url ", 0, 1) {}
                        }
                    }
                    box("stbl") { sampleTable(this, track) }
                }
            }
        }
    }

    private fun sampleTable(w: Mp4BoxWriter, track: Mp4AacTrack) {
        w.fullBox("stsd", 0, 0) {
            u32(1)
            box("mp4a") {
                bytes(ByteArray(6))
                u16(1) // data_reference_index
                u32(0)
                u32(0)
                u16(track.channels)
                u16(16)
                u16(0)
                u16(0)
                u32(if (track.sampleRateHz > 0xFFFF) 0L else track.sampleRateHz.toLong() shl 16)
                fullBox("esds", 0, 0) { bytes(esDescriptor(track)) }
            }
        }
        w.fullBox("stts", 0, 0) {
            u32(1)
            u32(unitCount)
            u32(FRAMES_PER_UNIT)
        }
        val chunkCount = (unitCount + UNITS_PER_CHUNK - 1) / UNITS_PER_CHUNK
        val lastChunkUnits = unitCount - (chunkCount - 1) * UNITS_PER_CHUNK
        w.fullBox("stsc", 0, 0) {
            if (chunkCount == 1) {
                u32(1)
                u32(1)
                u32(unitCount)
                u32(1)
            } else {
                val shortLast = lastChunkUnits != UNITS_PER_CHUNK
                u32(if (shortLast) 2 else 1)
                u32(1)
                u32(UNITS_PER_CHUNK)
                u32(1)
                if (shortLast) {
                    u32(chunkCount)
                    u32(lastChunkUnits)
                    u32(1)
                }
            }
        }
        w.fullBox("stsz", 0, 0) {
            u32(0)
            u32(unitCount)
            for (i in 0 until unitCount) u32(unitSizes[i])
        }
        val offsets = LongArray(chunkCount)
        var position = mdatOffset + MDAT_HEADER_BYTES
        for (i in 0 until unitCount) {
            if (i % UNITS_PER_CHUNK == 0) offsets[i / UNITS_PER_CHUNK] = position
            position += unitSizes[i]
        }
        if (offsets.last() <= U32_MAX) {
            w.fullBox("stco", 0, 0) {
                u32(chunkCount)
                for (offset in offsets) u32(offset)
            }
        } else {
            w.fullBox("co64", 0, 0) {
                u32(chunkCount)
                for (offset in offsets) u64(offset)
            }
        }
    }

    private fun writeTimes(w: Mp4BoxWriter, version: Int, creation: Long, modification: Long) {
        if (version == 1) {
            w.u64(creation)
            w.u64(modification)
        } else {
            w.u32(creation)
            w.u32(modification)
        }
    }

    private fun writeIdentityMatrix(w: Mp4BoxWriter) {
        for (value in intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)) w.u32(value)
    }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) target[offset + i] = (value ushr (24 - 8 * i)).toByte()
    }

    private fun putU64(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) target[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }
}
