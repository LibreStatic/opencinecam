/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

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
    private val target: Mp4AppendedMdat,
) : AutoCloseable {
    private val movie: Mp4Movie get() = target.movie
    private var unitSizes = IntArray(1024)
    private var unitCount = 0
    private var largestUnit = 0

    companion object {
        private const val FRAMES_PER_UNIT = 1024
        private const val UNITS_PER_CHUNK = 32
        private const val MAX_UNIT_BYTES_PER_CHANNEL = 8 * 1024

        /** Units are checked against this before the channel count is known, and against the real count in [finish]. */
        private const val MAX_CHANNELS = 8

        /** Reads the file's structure and starts a new `mdat` at its end. Throws [Mp4FormatException] and changes nothing when the file cannot take a track. */
        fun begin(file: Mp4Bytes): Mp4AudioAppender {
            val originalSize = file.size()
            val movie = readMovieForAppend(file)
            // Other tracks, such as camera motion metadata, are carried over unchanged.
            if (movie.tracks.any { it.handlerType == "soun" }) throw Mp4FormatException("has-audio-track")
            if (movie.tracks.none { it.handlerType == "vide" }) throw Mp4FormatException("no-video-track")
            return Mp4AudioAppender(Mp4AppendedMdat(file, movie, originalSize))
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
        target.checkOpen()
        val size = data.remaining()
        if (size == 0 || size > MAX_UNIT_BYTES_PER_CHANNEL * MAX_CHANNELS) throw Mp4FormatException("access-unit-size", "an access unit of $size bytes")
        target.append(data)
        if (unitCount == unitSizes.size) unitSizes = unitSizes.copyOf(unitCount * 2)
        unitSizes[unitCount++] = size
        if (size > largestUnit) largestUnit = size
    }

    /** Seals the `mdat`, writes the new `moov`, syncs, then retypes the old `moov` to `free` and syncs again. */
    fun finish(track: Mp4AacTrack) {
        target.checkOpen()
        if (unitCount == 0) throw Mp4FormatException("no-audio", "no access units were written")
        require(track.sampleRateHz > 0) { "Sample rate ${track.sampleRateHz}" }
        require(track.channels in 1..MAX_CHANNELS) { "Channel count ${track.channels}" }
        require(track.primingFrames >= 0) { "Priming frames ${track.primingFrames}" }
        if (largestUnit > MAX_UNIT_BYTES_PER_CHANNEL * track.channels) {
            throw Mp4FormatException("access-unit-size", "an access unit of $largestUnit bytes for ${track.channels} channels")
        }
        // Built before anything else is written, so a refused track leaves only the unsealed mdat for close() to seal.
        val moov = buildMovie(track)
        target.commit(moov)
    }

    /**
     * Before [finish]: leaves the file as it was read (the old moov is still the file's moov). Idempotent; a no-op after finish.
     *
     * Bytes already appended stay, since [Mp4Bytes] cannot truncate: the new `mdat` header is rewritten to run to the end of the
     * file, so it also swallows a new `moov` written before a failed (and undone) retype, and the file ends on a box boundary. Best effort:
     * I/O failures here are swallowed.
     */
    override fun close() = target.close()

    private fun audioEdits(track: Mp4AacTrack): List<Mp4Edit> {
        val movieTimescale = movie.header.timescale
        val rate = track.sampleRateHz.toLong()
        // A stream cannot present more than its units hold.
        val validFrames = minOf(track.validFrames, unitCount.toLong() * FRAMES_PER_UNIT - track.primingFrames)
        if (validFrames <= 0) throw Mp4FormatException("no-audio", "no valid frames after the priming")
        val edits = ArrayList<Mp4Edit>()
        if (track.startOffsetUs >= 0) {
            val empty = rescale(track.startOffsetUs, 1_000_000, movieTimescale)
            if (empty > 0) edits += Mp4Edit(empty, -1)
            edits += Mp4Edit(rescale(validFrames, rate, movieTimescale), track.primingFrames.toLong())
        } else {
            val skipped = -rescale(track.startOffsetUs, 1_000_000, rate)
            val remaining = validFrames - skipped
            if (remaining <= 0) throw Mp4FormatException("audio-before-video", "the audio ends ${track.startOffsetUs} µs before the video starts")
            edits += Mp4Edit(rescale(remaining, rate, movieTimescale), track.primingFrames + skipped)
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
        val trackId = target.newTrackId()
        val edits = audioEdits(track)
        val presentation = edits.sumOf { it.segmentDuration }
        return target.buildMovie(trackId, presentation, unitCount * 4) { audioTrak(this, track, trackId, edits, presentation) }
    }

    private fun audioTrak(w: Mp4BoxWriter, track: Mp4AacTrack, trackId: Long, edits: List<Mp4Edit>, presentation: Long) {
        val creation = movie.header.creationTime
        val modification = movie.header.modificationTime
        val timesFit = creation in 0..U32_MAX && modification in 0..U32_MAX
        val mediaDuration = unitCount.toLong() * FRAMES_PER_UNIT
        w.box("trak") {
            val tkhdVersion = if (timesFit && presentation <= U32_MAX) 0 else 1
            fullBox("tkhd", tkhdVersion, 0x000007) {
                writeTimes(tkhdVersion, creation, modification)
                u32(trackId)
                u32(0)
                if (tkhdVersion == 1) u64(presentation) else u32(presentation)
                u32(0)
                u32(0)
                u16(0) // layer
                u16(0) // alternate_group
                u16(0x0100) // volume 1.0
                u16(0)
                writeIdentityMatrix()
                u32(0) // width
                u32(0) // height
            }
            writeEditList(edits)
            box("mdia") {
                val mdhdVersion = if (timesFit && mediaDuration <= U32_MAX) 0 else 1
                fullBox("mdhd", mdhdVersion, 0) {
                    writeTimes(mdhdVersion, creation, modification)
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
                    writeSelfContainedDataInformation()
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
        w.writeSampleToChunk(unitCount, UNITS_PER_CHUNK)
        w.fullBox("stsz", 0, 0) {
            u32(0)
            u32(unitCount)
            for (i in 0 until unitCount) u32(unitSizes[i])
        }
        val chunkCount = (unitCount + UNITS_PER_CHUNK - 1) / UNITS_PER_CHUNK
        val offsets = LongArray(chunkCount)
        var position = target.payloadOffset
        for (i in 0 until unitCount) {
            if (i % UNITS_PER_CHUNK == 0) offsets[i / UNITS_PER_CHUNK] = position
            position += unitSizes[i]
        }
        w.writeChunkOffsets(offsets)
    }
}
