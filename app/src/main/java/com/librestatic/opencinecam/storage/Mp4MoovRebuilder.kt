/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random access to the MP4 being repaired: a [FileChannelMp4Access] in production, an in-memory fake in tests. */
interface Mp4Access {
    val size: Long

    /** Reads up to [length] bytes at [position]; fewer only at the end of the file. */
    fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int

    /** Writes [data] at [position], extending the file when it passes the end. */
    fun write(position: Long, data: ByteArray)

    /** Makes the writes so far durable before the next step depends on them. */
    fun sync() {}
}

class FileChannelMp4Access(private val channel: FileChannel) : Mp4Access {
    override val size: Long get() = channel.size()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        val buffer = ByteBuffer.wrap(into, offset, length)
        var total = 0
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, position + total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    override fun write(position: Long, data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        var at = position
        while (buffer.hasRemaining()) at += channel.write(buffer, at)
    }

    override fun sync() = channel.force(true)
}

sealed interface Mp4RebuildResult {
    /** A `moov` was appended; the counts are the samples it references. */
    data class Rebuilt(val videoSamples: Int, val audioSamples: Int, val durationUs: Long) : Mp4RebuildResult

    /** The file already has a complete `moov`; it was not touched. */
    data object NotNeeded : Mp4RebuildResult

    /** Nothing was changed: the file could not be repaired, found out before any write. */
    data class Failed(val reason: String) : Mp4RebuildResult
}

/**
 * PLAN-072, ADR-0037: rebuilds the `moov` of a take whose drive was pulled mid-recording.
 *
 * MPEG4Writer writes `ftyp`, a `free` that reserves the moov space, then an `mdat` whose header is a 64-bit box with a
 * placeholder size; the `moov` is written only at stop. What is left after a pull is the mdat bytes, chunks that
 * alternate between tracks, and a torn tail. [rebuildMoov] walks the payload with the [TakeSampleIndex] kept during the
 * take, appends a `moov` at EOF (after the torn tail, which stays unreferenced inside the mdat) and then rewrites only
 * the mdat size. Sample data is never moved, truncated or overwritten, and the size goes last so a failure midway
 * leaves the samples as they were.
 *
 * A sized mdat followed by a torn box (the muxer's own `moov` cut by the pull, or a few stray bytes) gets that box's
 * header turned into a `free` that runs to EOF first, so the `moov` appended after it is reachable; complete boxes
 * before it are kept. A retry after an append that was cut finds the partial `moov` as the torn box and does the same.
 *
 * Everything that can fail before a write ([Mp4RebuildResult.Failed]) leaves the file unchanged. An [java.io.IOException]
 * from a write or a sync propagates: the drive may have gone away, and the caller decides whether to retry. It never
 * overwrites sample bytes, but the container metadata may then be incomplete.
 *
 * The walk: a position is video when the bytes parse as a NAL (4-byte length that fits, forbidden bit 0, a sane type),
 * otherwise it is audio, whose frame size comes from the index (raw AAC has no framing in the file). Video sample
 * boundaries come from the bitstream, not from the index sizes, because the muxer may have converted start codes: an
 * access unit starts at an AUD, at a first-slice VCL NAL, or at the parameter sets and SEI that precede one. Each
 * access unit is paired in order with the next index video sample for its time and key flag. The walk stops at the
 * first incomplete sample, at zeros, or when the index runs out.
 */
object Mp4MoovRebuilder {
    fun rebuildMoov(access: Mp4Access, index: TakeSampleIndex): Mp4RebuildResult {
        val layout = when (val parsed = parseTopLevel(access)) {
            is TopLevel.Done -> return parsed.result
            is TopLevel.Found -> parsed
        }
        val walk = MdatWalk(access, layout.payloadStart, layout.payloadEnd, index).run()
        if (walk.samples.isEmpty()) return Mp4RebuildResult.Failed("no complete sample in the mdat")
        val moov = try {
            MoovBuilder(index, walk).build()
        } catch (e: IllegalArgumentException) {
            return Mp4RebuildResult.Failed(e.message ?: "bad track configuration")
        }
        val newMdatSize = access.size - layout.mdatStart
        if (layout.rewriteSize && layout.headerLength == 8 && newMdatSize > 0xFFFFFFFFL) {
            return Mp4RebuildResult.Failed("mdat too large for a 32-bit header")
        }
        // From here on a write or sync that fails throws (see the class comment).
        layout.tailStart?.let { tailStart ->
            // The torn box becomes a free running to EOF (at least a header long) before the moov goes after it: written
            // first, it can never be read as a complete box swallowing part of the new moov. The type goes before the
            // size: a write cut between them leaves a free or a torn box, never a torn moov resized to look complete.
            val gap = access.size - tailStart
            val large = gap > 0xFFFFFFFFL
            val freeSize = maxOf(gap, if (large) 16L else 8L)
            access.write(tailStart + 4, FREE)
            access.sync()
            if (large) {
                access.write(tailStart, ByteBuffer.allocate(4).putInt(1).array())
                access.write(tailStart + 8, ByteBuffer.allocate(8).putLong(freeSize).array())
            } else {
                access.write(tailStart, ByteBuffer.allocate(4).putInt(freeSize.toInt()).array())
            }
            access.sync()
        }
        val moovStart = access.size
        access.write(moovStart, moov.bytes)
        access.sync()
        if (layout.rewriteSize) {
            val field = ByteBuffer.allocate(8)
            if (layout.headerLength == 16) {
                access.write(layout.mdatStart + 8, field.putLong(newMdatSize).array())
            } else {
                access.write(layout.mdatStart, field.putInt(newMdatSize.toInt()).array(), 4)
            }
            access.sync()
        }
        return Mp4RebuildResult.Rebuilt(walk.videoCount, walk.audioCount, moov.durationUs)
    }

    private fun Mp4Access.write(position: Long, data: ByteArray, length: Int) = write(position, data.copyOf(length))

    private val FREE = "free".toByteArray(Charsets.ISO_8859_1)

    // ---- top level -----------------------------------------------------------------------------------------------

    private sealed interface TopLevel {
        class Done(val result: Mp4RebuildResult) : TopLevel

        class Found(
            val mdatStart: Long,
            val headerLength: Int,
            val payloadStart: Long,
            val payloadEnd: Long,
            /** True when the mdat size is a placeholder, 0 (to EOF) or runs past the file, so it must be rewritten. */
            val rewriteSize: Boolean,
            /** After a sized mdat, where the first incomplete box (or fewer than 8 bytes) starts; null when the boxes reach EOF. */
            val tailStart: Long? = null,
        ) : TopLevel
    }

    private fun parseTopLevel(access: Mp4Access): TopLevel {
        val size = access.size
        var pos = 0L
        var first = true
        val head = ByteArray(16)
        while (pos + 8 <= size) {
            val n = access.read(pos, head, 0, 16)
            if (n < 8) break
            val buffer = ByteBuffer.wrap(head)
            val size32 = buffer.int.toLong() and 0xFFFFFFFFL
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            if (first && type != "ftyp") return TopLevel.Done(Mp4RebuildResult.Failed("not an MP4: first box is '$type'"))
            first = false
            var headerLength = 8
            val boxSize = when (size32) {
                0L -> size - pos
                1L -> {
                    if (n < 16) break
                    headerLength = 16
                    ByteBuffer.wrap(head, 8, 8).long
                }
                else -> size32
            }
            val valid = boxSize >= headerLength && boxSize <= size - pos
            when (type) {
                "moov" -> return TopLevel.Done(
                    if (valid) Mp4RebuildResult.NotNeeded else Mp4RebuildResult.Failed("truncated moov"),
                )
                "mdat" -> {
                    val end = if (valid) pos + boxSize else size
                    var tailStart: Long? = null
                    if (valid && end < size) {
                        // A sized mdat with boxes after it: a moov may follow; scan on.
                        when (val rest = scanAfterMdat(access, end)) {
                            Tail.HasMoov -> return TopLevel.Done(Mp4RebuildResult.NotNeeded)
                            Tail.Complete -> Unit
                            is Tail.Torn -> tailStart = rest.start
                        }
                    }
                    return TopLevel.Found(pos, headerLength, pos + headerLength, end, rewriteSize = !valid || size32 == 0L, tailStart)
                }
                else -> if (!valid) break
            }
            pos += boxSize
        }
        return TopLevel.Done(Mp4RebuildResult.Failed("no mdat found"))
    }

    private sealed interface Tail {
        /** A complete `moov` follows the mdat. */
        data object HasMoov : Tail

        /** Only complete boxes, ending exactly at EOF. */
        data object Complete : Tail

        /** The box at [start] is incomplete (its size runs past EOF, is unusable, or fewer than 8 bytes remain). */
        class Torn(val start: Long) : Tail
    }

    private fun scanAfterMdat(access: Mp4Access, from: Long): Tail {
        var pos = from
        val head = ByteArray(16)
        val size = access.size
        while (pos < size) {
            if (size - pos < 8 || access.read(pos, head, 0, 16) < 8) return Tail.Torn(pos)
            val size32 = ByteBuffer.wrap(head).int.toLong() and 0xFFFFFFFFL
            val moov = String(head, 4, 4, Charsets.ISO_8859_1) == "moov"
            // A box "to EOF" would swallow what is appended: only a moov is accepted as one.
            if (size32 == 0L) return if (moov) Tail.HasMoov else Tail.Torn(pos)
            val boxSize = when {
                size32 != 1L -> size32
                size - pos < 16 -> return Tail.Torn(pos)
                else -> ByteBuffer.wrap(head, 8, 8).long
            }
            if (boxSize < (if (size32 == 1L) 16 else 8) || boxSize > size - pos) return Tail.Torn(pos)
            if (moov) return Tail.HasMoov
            pos += boxSize
        }
        return Tail.Complete
    }

    // ---- the mdat walk -------------------------------------------------------------------------------------------

    internal class WalkedSample(val track: TrackKind, val offset: Long, val size: Int, val ptsUs: Long, val key: Boolean)

    internal class Walk(val samples: List<WalkedSample>) {
        val videoCount = samples.count { it.track == TrackKind.VIDEO }
        val audioCount = samples.size - videoCount
        fun of(track: TrackKind): List<WalkedSample> = samples.filter { it.track == track }
    }

    private enum class NalStatus { INVALID, TRUNCATED, OK }

    private class Nal(val status: NalStatus, val length: Int = 0, val type: Int = 0, val vcl: Boolean = false, val firstSlice: Boolean = false) {
        val total: Int get() = 4 + length
    }

    private class MdatWalk(
        private val access: Mp4Access,
        private val start: Long,
        private val end: Long,
        private val index: TakeSampleIndex,
    ) {
        private val hevc = index.tracks[TrackKind.VIDEO]?.mime == "video/hevc"
        private val videoIndex = index.samplesOf(TrackKind.VIDEO)
        private val audioIndex = index.samplesOf(TrackKind.AUDIO)

        // A NAL longer than a generous multiple of the largest indexed sample is not a NAL: raw AAC bytes read as a
        // 4-byte length are usually huge, and this keeps a 5 GB take from fooling the "fits in the file" test.
        private val maxNal = (videoIndex.maxOfOrNull { it.size } ?: 0).toLong() * 2 + 1024

        private val window = ByteArray(WINDOW)
        private var windowStart = -1L
        private var windowLength = 0

        /** Bytes [position, position + length) clipped to [end], or null when fewer than [length] remain. */
        private fun peek(position: Long, length: Int, out: ByteArray): Boolean {
            if (position < start || position + length > end) return false
            if (position < windowStart || position + length > windowStart + windowLength) {
                windowStart = position
                windowLength = access.read(position, window, 0, minOf(WINDOW.toLong(), end - position).toInt())
                if (windowLength < length) {
                    windowStart = -1
                    return false
                }
            }
            System.arraycopy(window, (position - windowStart).toInt(), out, 0, length)
            return true
        }

        private val tmp = ByteArray(8)

        private fun nalAt(position: Long): Nal {
            if (!peek(position, 4, tmp)) return Nal(NalStatus.INVALID)
            val length = ByteBuffer.wrap(tmp).int.toLong() and 0xFFFFFFFFL
            if (length < (if (hevc) 2 else 1) || length > maxNal) return Nal(NalStatus.INVALID)
            val headBytes = minOf(3L, length).toInt()
            if (!peek(position + 4, headBytes, tmp)) return Nal(NalStatus.INVALID)
            val b0 = tmp[0].toInt() and 0xFF
            val b1 = if (headBytes > 1) tmp[1].toInt() and 0xFF else 0
            val b2 = if (headBytes > 2) tmp[2].toInt() and 0xFF else 0
            if (b0 and 0x80 != 0) return Nal(NalStatus.INVALID)
            val type: Int
            val vcl: Boolean
            val first: Boolean
            if (hevc) {
                type = (b0 shr 1) and 0x3F
                if (type !in 0..9 && type !in 16..21 && type !in 32..40) return Nal(NalStatus.INVALID)
                if (b1 and 7 < 1) return Nal(NalStatus.INVALID)
                vcl = type < 32
                first = vcl && b2 and 0x80 != 0
            } else {
                type = b0 and 0x1F
                if (type !in 1..12) return Nal(NalStatus.INVALID)
                vcl = type in 1..5
                first = vcl && b1 and 0x80 != 0 // first_mb_in_slice == 0: ue(v) "1"
            }
            val status = if (position + 4 + length > end) NalStatus.TRUNCATED else NalStatus.OK
            return Nal(status, length.toInt(), type, vcl, first)
        }

        /** Whether [nal] opens a new access unit once the current one has its picture. */
        private fun opensAu(nal: Nal): Boolean =
            if (hevc) nal.firstSlice || nal.type in intArrayOf(32, 33, 34, 35, 39)
            else nal.firstSlice || nal.type in intArrayOf(6, 7, 8, 9)

        private fun zerosAt(position: Long): Boolean {
            val probe = ByteArray(16)
            val n = minOf(16L, end - position).toInt()
            if (n <= 0 || !peek(position, n, probe)) return false
            for (i in 0 until n) if (probe[i].toInt() != 0) return false
            return true
        }

        /** Size of the access unit at [position], or null when it is incomplete. */
        private fun accessUnitAt(position: Long, indexSize: Int): Int? {
            var p = position
            var hasVcl = false
            var count = 0
            while (true) {
                val nal = nalAt(p)
                if (nal.status == NalStatus.INVALID) {
                    // Zero padding after the last picture: as at the end, only the indexed size proves it complete.
                    if (zerosAt(p)) return if (hasVcl && (p - position) == indexSize.toLong()) (p - position).toInt() else null
                    break
                }
                if (count > 0 && hasVcl && opensAu(nal)) break
                if (nal.status == NalStatus.TRUNCATED) return null
                hasVcl = hasVcl || nal.vcl
                p += nal.total
                count++
                if (p == end) {
                    // Cannot tell a complete last picture from one missing slices: trust only the indexed size.
                    return if (hasVcl && (p - position) == indexSize.toLong()) (p - position).toInt() else null
                }
            }
            return if (hasVcl) (p - position).toInt() else null
        }

        /** Whether audio frame [a] fits at [pos]: size, not zeros, and what follows is a valid position. */
        private fun audioPlausible(pos: Long, a: Int): Boolean {
            if (a >= audioIndex.size) return false
            val size = audioIndex[a].size
            if (size <= 0 || pos + size > end || zerosAt(pos)) return false
            val next = pos + size
            return next == end || zerosAt(next) ||
                nalAt(next).let { it.status != NalStatus.INVALID && opensAu(it) } ||
                a + 1 < audioIndex.size // more audio expected, possibly the torn one at the tail
        }

        /** Whether a NAL at [position] is complete and opens an access unit. */
        private fun opensAuAt(position: Long): Boolean = nalAt(position).let { it.status == NalStatus.OK && opensAu(it) }

        /**
         * Whether audio frame [a] at [pos], where the bytes look like an access unit that is not complete, really is
         * audio: the frames from there must lead to a complete NAL that opens an access unit. Reaching EOF, zeros or an
         * implausible frame first proves nothing: a torn access unit at the end can be as long as the next audio frame.
         */
        private fun audioConfirmed(pos: Long, a: Int): Boolean {
            var p = pos
            var frame = a
            repeat(MAX_CONFIRMING_FRAMES) {
                if (frame >= audioIndex.size) return false
                val size = audioIndex[frame].size
                if (size <= 0 || p + size > end || zerosAt(p)) return false
                p += size
                frame++
                if (opensAuAt(p)) return true
            }
            return false
        }

        fun run(): Walk {
            val samples = ArrayList<WalkedSample>()
            var pos = start
            var v = 0
            var a = 0
            while (pos < end) {
                val nal = nalAt(pos)
                var videoSize = -1
                if (nal.status != NalStatus.INVALID && opensAu(nal)) {
                    if (v >= videoIndex.size) break
                    val size = accessUnitAt(pos, videoIndex[v].size)
                    // Mono AAC can open with bytes that parse as a small first-slice NAL: when the walked size
                    // disagrees with the index and audio fits, it is audio (a converted start code still sizes
                    // differently, so video stays the answer when audio is implausible).
                    if (size != null && (size == videoIndex[v].size || !audioPlausible(pos, a))) videoSize = size
                    // An access unit cut at EOF must not pass for audio that happens to fit: audio needs a video NAL after it.
                    else if (size == null && !audioConfirmed(pos, a)) break
                }
                if (videoSize >= 0) {
                    samples += WalkedSample(TrackKind.VIDEO, pos, videoSize, videoIndex[v].presentationTimeUs, videoIndex[v].isKeyFrame)
                    pos += videoSize
                    v++
                } else {
                    if (!audioPlausible(pos, a)) break
                    val size = audioIndex[a].size
                    samples += WalkedSample(TrackKind.AUDIO, pos, size, audioIndex[a].presentationTimeUs, true)
                    pos += size
                    a++
                }
            }
            return Walk(samples)
        }

        private companion object {
            const val WINDOW = 256 * 1024

            /** Audio frames [audioConfirmed] follows looking for the next access unit: more than an interleaved chunk holds. */
            const val MAX_CONFIRMING_FRAMES = 128
        }
    }

    // ---- the moov ------------------------------------------------------------------------------------------------

    internal class Moov(val bytes: ByteArray, val durationUs: Long)

    private const val MOVIE_TIMESCALE = 1000L
    private const val VIDEO_TIMESCALE = 90_000L

    private class MoovBuilder(private val index: TakeSampleIndex, private val walk: Walk) {
        private class Prepared(val id: Int, val kind: TrackKind, val info: TakeTrackInfo, val samples: List<WalkedSample>, val startUs: Long)

        fun build(): Moov {
            val tracks = ArrayList<Prepared>()
            for ((id, kind) in listOf(TrackKind.VIDEO, TrackKind.AUDIO).withIndex()) {
                val samples = walk.of(kind)
                if (samples.isEmpty()) continue
                val info = requireNotNull(index.tracks[kind]) { "no ${kind.name.lowercase()} track info in the index" }
                val startUs = if (kind == TrackKind.VIDEO) samples.minOf { it.ptsUs } else samples.first().ptsUs
                tracks += Prepared(id + 1, kind, info, samples, startUs)
            }
            val originUs = tracks.minOf { it.startUs }
            val built = tracks.map { buildTrack(it, originUs) }
            val movieDuration = built.maxOf { it.movieDuration }
            val moovPayload = ByteArrayOutputStream()
            moovPayload.write(mvhd(movieDuration, tracks.size + 1))
            for (t in built) moovPayload.write(t.trak)
            return Moov(box("moov", moovPayload.toByteArray()), movieDuration * 1000)
        }

        private class BuiltTrack(val trak: ByteArray, val movieDuration: Long)

        private fun buildTrack(t: Prepared, originUs: Long): BuiltTrack {
            val video = t.kind == TrackKind.VIDEO
            val timescale = if (video) VIDEO_TIMESCALE else t.info.sampleRate.toLong()
            require(timescale > 0) { "audio track without a sample rate" }
            val dts: LongArray
            val ctts: LongArray?
            val duration: Long
            if (video) {
                val ticks = LongArray(t.samples.size) { Math.round((t.samples[it].ptsUs - t.startUs) * timescale / 1_000_000.0) }
                val monotonic = (1 until ticks.size).all { ticks[it] >= ticks[it - 1] }
                dts = if (monotonic) ticks else ticks.sortedArray()
                ctts = if (monotonic) null else LongArray(ticks.size) { ticks[it] - dts[it] }
                val last = if (dts.size > 1) dts[dts.size - 1] - dts[dts.size - 2] else timescale / 30
                duration = dts.last() + maxOf(last, 1)
            } else {
                dts = LongArray(t.samples.size) { it * 1024L }
                ctts = null
                duration = t.samples.size * 1024L
            }
            val deltas = LongArray(dts.size) { if (it + 1 < dts.size) dts[it + 1] - dts[it] else if (it > 0) dts[it] - dts[it - 1] else duration }
            if (video && dts.size > 1) deltas[dts.size - 1] = duration - dts.last()
            val durationMs = Math.round(duration * MOVIE_TIMESCALE / timescale.toDouble())
            val offsetMs = Math.round((t.startUs - originUs) / 1000.0)
            val delay = if (video) 0L else (t.info.encoderDelayFrames ?: 0).toLong().coerceIn(0, duration - 1)
            val delayMs = Math.round(delay * MOVIE_TIMESCALE / timescale.toDouble())
            val trackDuration = offsetMs + durationMs - delayMs

            val chunks = chunks(t.samples)
            val stbl = ByteArrayOutputStream()
            stbl.write(sampleEntryBox(t))
            stbl.write(sttsBox(deltas))
            if (ctts != null) stbl.write(cttsBox(ctts))
            if (video) stssBox(t.samples)?.let { stbl.write(it) }
            stbl.write(stscBox(chunks))
            stbl.write(stszBox(t.samples))
            stbl.write(co64Box(chunks))

            val mediaHeader = if (video) fullBox("vmhd", 0, 1, bytes(0, 0, 0, 0, 0, 0, 0, 0)) else fullBox("smhd", 0, 0, ByteArray(4))
            val minf = box("minf", mediaHeader + dinf() + box("stbl", stbl.toByteArray()))
            val mdia = box("mdia", mdhd(timescale, duration) + hdlr(if (video) "vide" else "soun", if (video) "VideoHandler" else "SoundHandler") + minf)
            val edits = elst(offsetMs, delay, durationMs - delayMs)
            val trak = box("trak", tkhd(t, trackDuration) + (edits ?: ByteArray(0)) + mdia)
            return BuiltTrack(trak, trackDuration)
        }

        private fun elst(offsetMs: Long, delayTicks: Long, mediaMs: Long): ByteArray? {
            if (offsetMs == 0L && delayTicks == 0L) return null
            val entries = ByteArrayOutputStream()
            var count = 0
            if (offsetMs > 0) {
                entries.write(ints(offsetMs.toInt(), -1, 0x10000))
                count++
            }
            entries.write(ints(mediaMs.toInt(), delayTicks.toInt(), 0x10000))
            count++
            return box("edts", fullBox("elst", 0, 0, ints(count) + entries.toByteArray()))
        }

        private class Chunk(val offset: Long, var count: Int)

        private fun chunks(samples: List<WalkedSample>): List<Chunk> {
            // Chunks are runs of this track's samples with no other track's bytes between them.
            val all = walk.samples
            val result = ArrayList<Chunk>()
            var open: Chunk? = null
            var previousEnd = -1L
            for (s in all) {
                if (s.track != samples.first().track) {
                    open = null
                    continue
                }
                if (open == null || s.offset != previousEnd) {
                    open = Chunk(s.offset, 0)
                    result += open
                }
                open.count++
                previousEnd = s.offset + s.size
            }
            return result
        }

        private fun mvhd(duration: Long, nextTrackId: Int): ByteArray = fullBox(
            "mvhd", 0, 0,
            ints(0, 0, MOVIE_TIMESCALE.toInt(), duration.toInt(), 0x00010000) + bytes(0x01, 0x00) + ByteArray(10) +
                identityMatrix() + ByteArray(24) + ints(nextTrackId),
        )

        private fun tkhd(t: Prepared, duration: Long): ByteArray {
            val video = t.kind == TrackKind.VIDEO
            val matrix = if (video) rotationMatrix(t.info.rotationDegrees) else identityMatrix()
            val body = ints(0, 0, t.id, 0, duration.toInt()) + ByteArray(8) + bytes(0, 0, 0, 0) +
                (if (video) bytes(0, 0) else bytes(1, 0)) + bytes(0, 0) + matrix +
                ints(if (video) t.info.width shl 16 else 0, if (video) t.info.height shl 16 else 0)
            return fullBox("tkhd", 0, 3, body)
        }

        private fun mdhd(timescale: Long, duration: Long): ByteArray =
            fullBox("mdhd", 0, 0, ints(0, 0, timescale.toInt(), duration.toInt()) + bytes(0x55, 0xC4, 0, 0))

        private fun hdlr(type: String, name: String): ByteArray =
            fullBox("hdlr", 0, 0, ints(0) + type.toByteArray(Charsets.US_ASCII) + ByteArray(12) + name.toByteArray(Charsets.US_ASCII) + bytes(0))

        private fun dinf(): ByteArray = box("dinf", fullBox("dref", 0, 0, ints(1) + fullBox("url ", 0, 1, ByteArray(0))))

        private fun sampleEntryBox(t: Prepared): ByteArray {
            val entry = if (t.kind == TrackKind.VIDEO) videoEntry(t.info) else audioEntry(t.info)
            return fullBox("stsd", 0, 0, ints(1) + entry)
        }

        private fun videoEntry(info: TakeTrackInfo): ByteArray {
            val hevc = info.mime == "video/hevc"
            require(hevc || info.mime == "video/avc") { "unsupported video mime ${info.mime}" }
            require(info.width > 0 && info.height > 0) { "video size missing" }
            val config = if (hevc) hvcC(info) else avcC(info)
            val extras = ByteArrayOutputStream()
            nclx(info)?.let { extras.write(it) }
            val pw = info.pixelAspectWidth
            val ph = info.pixelAspectHeight
            if (pw != null && ph != null && pw > 0 && ph > 0 && pw != ph) extras.write(box("pasp", ints(pw, ph)))
            val head = ByteArray(6) + bytes(0, 1) + ByteArray(16) + bytes(info.width shr 8, info.width, info.height shr 8, info.height) +
                ints(0x00480000, 0x00480000, 0, 0).copyOfRange(0, 8) + ints(0) + bytes(0, 1) + ByteArray(32) + bytes(0, 0x18, 0xFF, 0xFF)
            return box(if (hevc) "hvc1" else "avc1", head + config + extras.toByteArray())
        }

        private fun nclx(info: TakeTrackInfo): ByteArray? {
            if (info.colorStandard == null && info.colorTransfer == null && info.colorRange == null) return null
            val (primaries, matrix) = when (info.colorStandard) {
                1 -> 1 to 1 // BT709
                2 -> 5 to 5 // BT601 PAL
                4 -> 6 to 6 // BT601 NTSC
                6 -> 9 to 9 // BT2020
                else -> 2 to 2
            }
            val transfer = when (info.colorTransfer) {
                1 -> 8 // linear
                3 -> 1 // SDR video (BT.709 OETF)
                6 -> 16 // ST 2084
                7 -> 18 // HLG
                else -> 2
            }
            val full = if (info.colorRange == 1) 0x80 else 0
            return box("colr", "nclx".toByteArray(Charsets.US_ASCII) + bytes(primaries shr 8, primaries, transfer shr 8, transfer, matrix shr 8, matrix, full))
        }

        private fun audioEntry(info: TakeTrackInfo): ByteArray {
            require(info.mime == "audio/mp4a-latm") { "unsupported audio mime ${info.mime}" }
            val asc = info.csd0 ?: ascFor(info)
            val head = ByteArray(6) + bytes(0, 1) + ByteArray(8) + bytes(0, info.channelCount, 0, 16) + ByteArray(4) + ints(info.sampleRate shl 16)
            return box("mp4a", head + esds(asc))
        }

        private fun ascFor(info: TakeTrackInfo): ByteArray {
            val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            val sr = rates.indexOf(info.sampleRate)
            require(sr >= 0 && info.channelCount in 1..7) { "audio csd-0 missing" }
            val v = (2 shl 11) or (sr shl 7) or (info.channelCount shl 3)
            return bytes(v shr 8, v)
        }

        private fun esds(asc: ByteArray): ByteArray {
            fun desc(tag: Int, body: ByteArray) = bytes(tag, 0x80, 0x80, 0x80, body.size) + body
            val decoderSpecific = desc(5, asc)
            val decoderConfig = desc(4, bytes(0x40, 0x15, 0, 0, 0) + ints(0, 0) + decoderSpecific)
            val es = desc(3, bytes(0, 0, 0) + decoderConfig + desc(6, bytes(2)))
            return fullBox("esds", 0, 0, es)
        }

        private fun runTable(type: String, version: Int, runs: List<Pair<Long, Int>>): ByteArray {
            val body = ByteBuffer.allocate(4 + runs.size * 8).putInt(runs.size)
            for (r in runs) body.putInt(r.second).putInt(r.first.toInt())
            return fullBox(type, version, 0, body.array())
        }

        private fun sttsBox(deltas: LongArray): ByteArray = runTable("stts", 0, runLengths(deltas))

        private fun cttsBox(offsets: LongArray): ByteArray =
            runTable("ctts", if (offsets.any { it < 0 }) 1 else 0, runLengths(offsets))

        private fun runLengths(values: LongArray): List<Pair<Long, Int>> {
            val runs = ArrayList<Pair<Long, Int>>()
            for (v in values) {
                if (runs.isNotEmpty() && runs.last().first == v) runs[runs.size - 1] = v to runs.last().second + 1
                else runs += v to 1
            }
            return runs
        }

        private fun stssBox(samples: List<WalkedSample>): ByteArray? {
            val keys = samples.indices.filter { samples[it].key }.ifEmpty { listOf(0) }
            if (keys.size == samples.size) return null
            val body = ByteBuffer.allocate(4 + keys.size * 4).putInt(keys.size)
            for (k in keys) body.putInt(k + 1)
            return fullBox("stss", 0, 0, body.array())
        }

        private fun stscBox(chunks: List<Chunk>): ByteArray {
            val entries = ByteArrayOutputStream()
            var count = 0
            var previous = -1
            for ((i, c) in chunks.withIndex()) {
                if (c.count == previous) continue
                entries.write(ints(i + 1, c.count, 1))
                previous = c.count
                count++
            }
            return fullBox("stsc", 0, 0, ints(count) + entries.toByteArray())
        }

        private fun stszBox(samples: List<WalkedSample>): ByteArray {
            val body = ByteBuffer.allocate(8 + samples.size * 4).putInt(0).putInt(samples.size)
            for (s in samples) body.putInt(s.size)
            return fullBox("stsz", 0, 0, body.array())
        }

        private fun co64Box(chunks: List<Chunk>): ByteArray {
            val body = ByteBuffer.allocate(4 + chunks.size * 8).putInt(chunks.size)
            for (c in chunks) body.putLong(c.offset)
            return fullBox("co64", 0, 0, body.array())
        }
    }

    // ---- parameter sets ------------------------------------------------------------------------------------------

    /** NAL units of an Annex B buffer, start codes and trailing zero bytes removed. A buffer with no start code is one NAL. */
    internal fun annexBNals(data: ByteArray?): List<ByteArray> {
        if (data == null || data.isEmpty()) return emptyList()
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else i++
        }
        if (starts.isEmpty()) return listOf(data)
        return starts.mapIndexedNotNull { n, s ->
            var e = if (n + 1 < starts.size) starts[n + 1] - 3 else data.size
            while (e > s && data[e - 1].toInt() == 0) e--
            if (e > s) data.copyOfRange(s, e) else null
        }
    }

    private fun hvcC(info: TakeTrackInfo): ByteArray {
        val nals = listOf(info.csd0, info.csd1, info.csd2).flatMap { annexBNals(it) }
        val vps = nals.filter { it.isNotEmpty() && (it[0].toInt() shr 1) and 0x3F == 32 }
        val sps = nals.filter { it.isNotEmpty() && (it[0].toInt() shr 1) and 0x3F == 33 }
        val pps = nals.filter { it.isNotEmpty() && (it[0].toInt() shr 1) and 0x3F == 34 }
        require(sps.isNotEmpty() && pps.isNotEmpty()) { "HEVC csd-0 lacks an SPS or PPS" }
        val h = try { parseHevcSps(sps.first()) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("unparseable HEVC SPS: ${e.message}") }
        val out = ByteArrayOutputStream()
        out.write(1)
        out.write((h.profileSpace shl 6) or (h.tier shl 5) or h.profileIdc)
        out.write(h.compatibility)
        out.write(h.constraints)
        out.write(h.level)
        out.write(bytes(0xF0, 0x00)) // min_spatial_segmentation_idc 0
        out.write(0xFC) // parallelismType unknown
        out.write(0xFC or h.chromaFormat)
        out.write(0xF8 or (h.bitDepthLuma - 8))
        out.write(0xF8 or (h.bitDepthChroma - 8))
        out.write(bytes(0, 0)) // avgFrameRate unknown
        out.write((h.numTemporalLayers shl 3) or (h.temporalIdNested shl 2) or 3)
        val arrays = listOf(32 to vps, 33 to sps, 34 to pps).filter { it.second.isNotEmpty() }
        out.write(arrays.size)
        for ((type, list) in arrays) {
            out.write(0x80 or type)
            out.write(bytes(list.size shr 8, list.size))
            for (nal in list) {
                out.write(bytes(nal.size shr 8, nal.size))
                out.write(nal)
            }
        }
        return box("hvcC", out.toByteArray())
    }

    internal class HevcSpsInfo(
        val profileSpace: Int,
        val tier: Int,
        val profileIdc: Int,
        val compatibility: ByteArray,
        val constraints: ByteArray,
        val level: Int,
        val chromaFormat: Int,
        val bitDepthLuma: Int,
        val bitDepthChroma: Int,
        val numTemporalLayers: Int,
        val temporalIdNested: Int,
    )

    internal fun parseHevcSps(nal: ByteArray): HevcSpsInfo {
        val r = Bits(unescape(nal))
        r.skip(16)
        r.skip(4)
        val maxSubLayersMinus1 = r.u(3)
        val nested = r.u(1)
        val profileSpace = r.u(2)
        val tier = r.u(1)
        val profileIdc = r.u(5)
        val compatibility = ByteArray(4) { r.u(8).toByte() }
        val constraints = ByteArray(6) { r.u(8).toByte() }
        val level = r.u(8)
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = r.u(1) == 1
            levelPresent[i] = r.u(1) == 1
        }
        if (maxSubLayersMinus1 > 0) r.skip(2 * (8 - maxSubLayersMinus1))
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) r.skip(88)
            if (levelPresent[i]) r.skip(8)
        }
        r.ue() // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma == 3) r.skip(1)
        r.ue(); r.ue() // width, height
        if (r.u(1) == 1) repeat(4) { r.ue() }
        val luma = r.ue() + 8
        val chromaDepth = r.ue() + 8
        require(chroma in 0..3 && luma in 8..16 && chromaDepth in 8..16) { "implausible SPS" }
        return HevcSpsInfo(profileSpace, tier, profileIdc, compatibility, constraints, level, chroma, luma, chromaDepth, maxSubLayersMinus1 + 1, nested)
    }

    private fun avcC(info: TakeTrackInfo): ByteArray {
        val nals = listOf(info.csd0, info.csd1, info.csd2).flatMap { annexBNals(it) }
        val sps = nals.filter { it.isNotEmpty() && it[0].toInt() and 0x1F == 7 }
        val pps = nals.filter { it.isNotEmpty() && it[0].toInt() and 0x1F == 8 }
        require(sps.isNotEmpty() && pps.isNotEmpty() && sps.first().size >= 4) { "AVC csd lacks an SPS or PPS" }
        val s = sps.first()
        val out = ByteArrayOutputStream()
        out.write(1)
        out.write(s[1].toInt() and 0xFF)
        out.write(s[2].toInt() and 0xFF)
        out.write(s[3].toInt() and 0xFF)
        out.write(0xFF)
        out.write(0xE0 or sps.size)
        for (n in sps) { out.write(bytes(n.size shr 8, n.size)); out.write(n) }
        out.write(pps.size)
        for (n in pps) { out.write(bytes(n.size shr 8, n.size)); out.write(n) }
        val profile = s[1].toInt() and 0xFF
        if (profile in intArrayOf(100, 110, 122, 144)) {
            val r = Bits(unescape(s))
            r.skip(32) // NAL header and profile, constraints, level
            r.ue() // seq_parameter_set_id
            val chroma = r.ue()
            if (chroma == 3) r.skip(1)
            val luma = r.ue()
            val chromaDepth = r.ue()
            out.write(0xFC or chroma)
            out.write(0xF8 or luma)
            out.write(0xF8 or chromaDepth)
            out.write(0)
        }
        return box("avcC", out.toByteArray())
    }

    private fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (b in nal) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class Bits(private val data: ByteArray) {
        private var pos = 0

        fun skip(n: Int) {
            pos += n
            require(pos <= data.size * 8) { "SPS ended early" }
        }

        fun u(n: Int): Int {
            require(pos + n <= data.size * 8) { "SPS ended early" }
            var v = 0
            repeat(n) { v = (v shl 1) or ((data[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1); pos++ }
            return v
        }

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) require(++zeros <= 31) { "Exp-Golomb too long" }
            return ((1L shl zeros) - 1 + u(zeros).toLong()).toInt()
        }
    }

    // ---- box helpers ---------------------------------------------------------------------------------------------

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun ints(vararg v: Int): ByteArray = ByteBuffer.allocate(v.size * 4).apply { v.forEach { putInt(it) } }.array()

    private fun box(type: String, payload: ByteArray): ByteArray =
        ints(8 + payload.size) + type.toByteArray(Charsets.US_ASCII) + payload

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray =
        box(type, ints((version shl 24) or flags) + payload)

    private fun identityMatrix(): ByteArray = ints(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)

    private fun rotationMatrix(degrees: Int): ByteArray = when (((degrees % 360) + 360) % 360) {
        90 -> ints(0, 0x10000, 0, -0x10000, 0, 0, 0, 0, 0x40000000)
        180 -> ints(-0x10000, 0, 0, 0, -0x10000, 0, 0, 0, 0x40000000)
        270 -> ints(0, -0x10000, 0, 0x10000, 0, 0, 0, 0, 0x40000000)
        else -> identityMatrix()
    }
}
