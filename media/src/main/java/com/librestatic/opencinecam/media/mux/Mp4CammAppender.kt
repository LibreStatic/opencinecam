/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Adds a CAMM (Camera Motion Metadata) track to a finished MP4 in place, without copying the video or audio.
 *
 * Each packet is one sample: a little-endian `uint16 reserved, uint16 type` and its payload, as the Street View CAMM
 * specification defines them. The packets go into a new `mdat` at the end of the file and a new `moov` follows it, carrying
 * every existing `trak` unchanged; only then is the old `moov` retyped to `free`. The file is a valid MP4 with the old `moov`
 * until that four-byte write, and a valid MP4 with the new one after it. Not thread-safe.
 *
 * The sample tables are kept in primitive arrays: a ten-minute take carries hundreds of thousands of packets.
 */
class Mp4CammAppender private constructor(
    private val target: Mp4AppendedMdat,
) : Closeable {
    private val movie: Mp4Movie get() = target.movie

    /** Delta from each packet to the next, microseconds, read unsigned; the last packet's is set in [finish]. */
    private var deltas = IntArray(INITIAL_CAPACITY)

    /** Each packet's size in bytes. */
    private var sizes = ByteArray(INITIAL_CAPACITY)
    private var count = 0
    private var firstTimeUs = 0L
    private var lastTimeUs = 0L
    private val packet: ByteBuffer = ByteBuffer.allocate(MAX_PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    companion object {
        const val TYPE_EXPOSURE = 1
        const val TYPE_GYRO = 2
        const val TYPE_ACCELERATION = 3
        const val TYPE_MAGNETIC_FIELD = 7

        private const val TIMESCALE = 1_000_000L
        private const val PACKETS_PER_CHUNK = 1024
        private const val INITIAL_CAPACITY = 4096
        private const val HEADER_BYTES = 4
        private const val MAX_PACKET_BYTES = HEADER_BYTES + 12

        /** Reads the file's structure and starts a new `mdat` at its end. Throws [Mp4FormatException] and changes nothing when the file cannot take the track. */
        fun begin(file: Mp4Bytes): Mp4CammAppender {
            val originalSize = file.size()
            val movie = readMovieForAppend(file)
            if (movie.tracks.none { it.handlerType == "vide" }) throw Mp4FormatException("no-video-track")
            // The specification allows one CAMM track per file.
            if (movie.tracks.any { isCammTrack(movie.bytes, it) }) throw Mp4FormatException("has-camm-track")
            return Mp4CammAppender(Mp4AppendedMdat(file, movie, originalSize))
        }

        /** A `meta` track whose first sample entry is `camm`. */
        internal fun isCammTrack(data: ByteArray, track: Mp4TrackSummary): Boolean {
            if (track.handlerType != "meta") return false
            val stsd = findPath(data, track.trak, "mdia", "minf", "stbl", "stsd") ?: return false
            stsd.requirePayload(8)
            return childBoxes(data, stsd.payload + 8, stsd.end).firstOrNull()?.type == "camm"
        }
    }

    /** Appends a gyroscope (2, rad/s), accelerometer (3, m/s²) or magnetometer (7, µT) packet, in camera axes: X right, Y down, Z forward. */
    fun writePacket(timeUs: Long, type: Int, a: Float, b: Float, c: Float) {
        require(type == TYPE_GYRO || type == TYPE_ACCELERATION || type == TYPE_MAGNETIC_FIELD) { "CAMM vector packet type $type" }
        target.checkOpen()
        checkTime(timeUs)
        packet.clear()
        packet.putShort(0.toShort()).putShort(type.toShort()).putFloat(a).putFloat(b).putFloat(c)
        append(timeUs)
    }

    /** Appends an exposure packet (1): pixel exposure time and rolling shutter skew, nanoseconds, timed at the first used scanline's exposure start. */
    fun writeExposure(timeUs: Long, exposureNs: Int, skewNs: Int) {
        target.checkOpen()
        checkTime(timeUs)
        packet.clear()
        packet.putShort(0.toShort()).putShort(TYPE_EXPOSURE.toShort()).putInt(exposureNs).putInt(skewNs)
        append(timeUs)
    }

    /**
     * Seals the `mdat`, writes the new `moov`, syncs, then retypes the old `moov` to `free` and syncs again. Returns the packets
     * written. Throws [IllegalStateException] when there are none; the file is then as [close] leaves it.
     */
    fun finish(): Int {
        target.checkOpen()
        check(count > 0) { "No CAMM packets were written" }
        // Built before anything else is written, so a refused track leaves only the unsealed mdat for close() to seal.
        val moov = buildMovie()
        if (moov.size > MAX_MOOV_BYTES) throw Mp4FormatException("moov-too-large", "a moov of ${moov.size} bytes for $count packets")
        target.commit(moov)
        return count
    }

    /**
     * Before [finish]: leaves the file as it was read (the old moov is still the file's moov). Idempotent; a no-op after finish.
     * Bytes already appended stay inside a sealed `mdat`, as with [Mp4AudioAppender.close].
     */
    override fun close() = target.close()

    private fun checkTime(timeUs: Long) {
        require(timeUs >= 0) { "CAMM packet time $timeUs µs is before the movie starts" }
        require(count == 0 || timeUs >= lastTimeUs) { "CAMM packet time $timeUs µs is before the previous one, $lastTimeUs µs" }
        // stts deltas are 32-bit.
        require(count == 0 || timeUs - lastTimeUs <= U32_MAX) { "A gap of ${timeUs - lastTimeUs} µs between CAMM packets" }
    }

    private fun append(timeUs: Long) {
        packet.flip()
        val size = packet.remaining()
        target.append(packet)
        if (count == sizes.size) {
            check(count < Int.MAX_VALUE / 2) { "Too many CAMM packets" }
            sizes = sizes.copyOf(count * 2)
            deltas = deltas.copyOf(count * 2)
        }
        if (count == 0) firstTimeUs = timeUs else deltas[count - 1] = (timeUs - lastTimeUs).toInt()
        sizes[count] = size.toByte()
        lastTimeUs = timeUs
        count++
    }

    private fun delta(index: Int): Long = deltas[index].toLong() and U32_MAX

    private fun buildMovie(): ByteArray {
        // The last packet lasts as long as the one before it; a single packet, or one at the same time as the previous, lasts 1 µs.
        val lastDelta = if (count > 1 && deltas[count - 2] != 0) deltas[count - 2] else 1
        deltas[count - 1] = lastDelta
        val mediaDuration = lastTimeUs - firstTimeUs + (lastDelta.toLong() and U32_MAX)
        val movieTimescale = movie.header.timescale
        val edits = ArrayList<Mp4Edit>()
        if (firstTimeUs > 0) {
            // A late first packet is placed with an empty edit, as the audio appender places a late audio start.
            val empty = rescale(firstTimeUs, TIMESCALE, movieTimescale)
            if (empty > 0) edits += Mp4Edit(empty, -1)
            edits += Mp4Edit(rescale(mediaDuration, TIMESCALE, movieTimescale), 0)
        }
        val presentation = if (edits.isEmpty()) rescale(mediaDuration, TIMESCALE, movieTimescale) else edits.sumOf { it.segmentDuration }
        val trackId = target.newTrackId()
        return target.buildMovie(trackId, presentation, count * 9) { cammTrak(this, trackId, edits, presentation, mediaDuration) }
    }

    private fun cammTrak(w: Mp4BoxWriter, trackId: Long, edits: List<Mp4Edit>, presentation: Long, mediaDuration: Long) {
        val creation = movie.header.creationTime
        val modification = movie.header.modificationTime
        val timesFit = creation in 0..U32_MAX && modification in 0..U32_MAX
        w.box("trak") {
            val tkhdVersion = if (timesFit && presentation <= U32_MAX) 0 else 1
            // Enabled and in the movie, as the audio track is; never in a preview.
            fullBox("tkhd", tkhdVersion, 0x000003) {
                writeTimes(tkhdVersion, creation, modification)
                u32(trackId)
                u32(0)
                if (tkhdVersion == 1) u64(presentation) else u32(presentation)
                u32(0)
                u32(0)
                u16(0) // layer
                u16(0) // alternate_group
                u16(0) // volume
                u16(0)
                writeIdentityMatrix()
                u32(0) // width
                u32(0) // height
            }
            if (edits.isNotEmpty()) writeEditList(edits)
            box("mdia") {
                val mdhdVersion = if (timesFit && mediaDuration <= U32_MAX) 0 else 1
                fullBox("mdhd", mdhdVersion, 0) {
                    writeTimes(mdhdVersion, creation, modification)
                    u32(TIMESCALE)
                    if (mdhdVersion == 1) u64(mediaDuration) else u32(mediaDuration)
                    u16(0x55C4) // 'und'
                    u16(0)
                }
                fullBox("hdlr", 0, 0) {
                    u32(0)
                    fourcc("meta")
                    u32(0)
                    u32(0)
                    u32(0)
                    bytes("CameraMetadataMotionHandler\u0000".toByteArray(StandardCharsets.US_ASCII))
                }
                box("minf") {
                    fullBox("nmhd", 0, 0) {}
                    writeSelfContainedDataInformation()
                    box("stbl") { sampleTable(this) }
                }
            }
        }
    }

    private fun sampleTable(w: Mp4BoxWriter) {
        w.fullBox("stsd", 0, 0) {
            u32(1)
            // CameraMotionMetadataSampleEntry adds nothing to SampleEntry.
            box("camm") {
                bytes(ByteArray(6))
                u16(1) // data_reference_index
            }
        }
        w.fullBox("stts", 0, 0) {
            var runs = 0
            for (i in 0 until count) if (i == 0 || deltas[i] != deltas[i - 1]) runs++
            u32(runs)
            var i = 0
            while (i < count) {
                var j = i + 1
                while (j < count && deltas[j] == deltas[i]) j++
                u32(j - i)
                u32(delta(i))
                i = j
            }
        }
        w.writeSampleToChunk(count, PACKETS_PER_CHUNK)
        val constant = (1 until count).all { sizes[it] == sizes[0] }
        w.fullBox("stsz", 0, 0) {
            if (constant) {
                u32(sizes[0].toInt())
                u32(count)
            } else {
                u32(0)
                u32(count)
                for (i in 0 until count) u32(sizes[i].toInt())
            }
        }
        val offsets = LongArray((count + PACKETS_PER_CHUNK - 1) / PACKETS_PER_CHUNK)
        var position = target.payloadOffset
        for (i in 0 until count) {
            if (i % PACKETS_PER_CHUNK == 0) offsets[i / PACKETS_PER_CHUNK] = position
            position += sizes[i]
        }
        w.writeChunkOffsets(offsets)
    }
}
