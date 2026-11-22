/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.media.mux.Mp4Bytes
import com.librestatic.opencinecam.media.mux.Mp4CammAppender
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** PLAN-072, ADR-0037: rebuilding the moov of a take cut short by a pulled drive, over a synthetic MPEG4Writer file. */
class Mp4MoovRebuilderTest {
    // ---- fakes and builders --------------------------------------------------------------------------------------

    private class MemoryAccess(var data: ByteArray) : Mp4Access {
        override val size: Long get() = data.size.toLong()
        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return 0
            val n = minOf(length.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), into, offset, n)
            return n
        }
        override fun write(position: Long, data: ByteArray) {
            val end = position.toInt() + data.size
            if (end > this.data.size) this.data = this.data.copyOf(end)
            System.arraycopy(data, 0, this.data, position.toInt(), data.size)
        }
    }

    private fun i32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()

    private class Bits {
        val out = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun bit(b: Int) { cur = (cur shl 1) or b; if (++n == 8) { out.write(cur); cur = 0; n = 0 } }
        fun u(bits: Int, v: Long) { for (i in bits - 1 downTo 0) bit(((v shr i) and 1).toInt()) }
        fun ue(v: Int) { val x = v + 1; val len = 32 - Integer.numberOfLeadingZeros(x); u(len - 1, 0); u(len, x.toLong()) }
        fun finish(): ByteArray { bit(1); while (n != 0) bit(0); return out.toByteArray() }
    }

    private val annexB = byteArrayOf(0, 0, 0, 1)

    private fun hevcSps(): ByteArray {
        val b = Bits()
        b.u(16, 0x4201); b.u(4, 0); b.u(3, 0); b.u(1, 1)
        b.u(2, 0); b.u(1, 0); b.u(5, 2) // Main 10
        b.u(32, 0x20000000L); b.u(48, 0xB00000000000L); b.u(8, 153)
        b.ue(0); b.ue(1); b.ue(1920); b.ue(1080); b.bit(0)
        b.ue(2); b.ue(2) // 10-bit
        return b.finish()
    }

    private val vps = byteArrayOf(0x40, 1, 0x0C, 0x01, 0x7F)
    private val sps = hevcSps()
    private val pps = byteArrayOf(0x44, 1, 0x7F, 0x33)
    private val hevcCsd = annexB + vps + annexB + sps + annexB + pps
    private val asc = byteArrayOf(0x11, 0x90.toByte())

    private fun hevcNal(type: Int, first: Boolean, body: Int, seed: Int) =
        i32(3 + body) + byteArrayOf((type shl 1).toByte(), 1, if (first) 0x80.toByte() else 0x10) + ByteArray(body) { (1 + (it + seed) % 200).toByte() }

    private fun hevcAu(key: Boolean, seed: Int, sei: Boolean = false, slices: Int = 1, size: Int = 80): ByteArray {
        var out = ByteArray(0)
        if (sei) out += hevcNal(39, false, 6, seed)
        for (s in 0 until slices) out += hevcNal(if (key) 19 else 1, s == 0, size + s, seed + s)
        return out
    }

    private fun aac(size: Int, seed: Int) = ByteArray(size) { (if (it == 0) 0x21 else 1 + (it + seed) % 200).toByte() }

    private class Synthetic(val file: ByteArray, val mdatStart: Long, val chunkStarts: List<Pair<TrackKind, Long>>, val auSizes: List<Int>, val audioSizes: List<Int>, val payloadEnd: Int)

    /** ftyp, free, 64-bit placeholder mdat, three video chunks of 4/3/3 AUs interleaved with audio chunks of 3/2/2 frames, a torn tail. */
    private fun synthetic(tail: ByteArray, avc: Boolean = false, audio: Boolean = true): Synthetic {
        val file = ByteArrayOutputStream()
        file.write(i32(24) + "ftyp".toByteArray() + "isom".toByteArray() + i32(0) + "isom".toByteArray() + "iso2".toByteArray())
        file.write(i32(3325) + "free".toByteArray() + ByteArray(3317))
        val mdatStart = file.size().toLong()
        file.write(i32(1) + "mdat".toByteArray() + ByteArray(8) { 0x3f })
        val starts = ArrayList<Pair<TrackKind, Long>>()
        val auSizes = ArrayList<Int>()
        val audioSizes = ArrayList<Int>()
        var seed = 0
        fun video(vararg aus: ByteArray) {
            starts += TrackKind.VIDEO to file.size().toLong()
            for (au in aus) { file.write(au); auSizes += au.size }
        }
        fun audioChunk(vararg sizes: Int) {
            if (!audio) return
            starts += TrackKind.AUDIO to file.size().toLong()
            for (s in sizes) { file.write(aac(s, seed++)); audioSizes += s }
        }
        fun au(key: Boolean, sei: Boolean = false, slices: Int = 1) = if (avc) avcAu(key, seed++, sei, slices) else hevcAu(key, seed++, sei, slices)
        video(au(true, sei = true), au(false), au(false, slices = 2), au(false, sei = true))
        audioChunk(300, 310, 305)
        video(au(true), au(false), au(false))
        audioChunk(301, 299)
        video(au(true, sei = true), au(false, slices = 2), au(false))
        audioChunk(298, 302)
        val payloadEnd = file.size()
        file.write(tail)
        return Synthetic(file.toByteArray(), mdatStart, starts, auSizes, audioSizes, payloadEnd)
    }

    private fun avcNal(type: Int, first: Boolean, body: Int, seed: Int) =
        i32(2 + body) + byteArrayOf((0x60 or type).toByte(), if (first) 0x88.toByte() else 0x10) + ByteArray(body) { (1 + (it + seed) % 200).toByte() }

    private fun avcAu(key: Boolean, seed: Int, sei: Boolean, slices: Int): ByteArray {
        var out = avcNal(9, false, 1, seed) // AUD
        if (sei) out += avcNal(6, false, 5, seed)
        for (s in 0 until slices) out += avcNal(if (key) 5 else 1, s == 0, 70 + s, seed + s)
        return out
    }

    private fun tornTail(): ByteArray = i32(1000) + byteArrayOf(0x02, 0x01, 0x80.toByte()) + ByteArray(120) { 7 }

    private fun indexFor(s: Synthetic, avc: Boolean = false, bFrames: Boolean = false, audio: Boolean = true, extraVideo: Int = 0): TakeSampleIndex {
        val keyAus = setOf(0, 4, 7)
        val ptsSeq = if (bFrames) longArrayOf(0, 100_000, 33_333, 66_666, 133_333, 233_333, 166_666, 200_000, 266_666, 300_000) else LongArray(10) { it * 33_333L }
        val v = s.auSizes.mapIndexed { i, size -> IndexedSample(size, ptsSeq[i], if (i in keyAus) 1 else 0) } +
            List(extraVideo) { IndexedSample(50, 400_000L + it, 0) }
        val a = s.audioSizes.mapIndexed { i, size -> IndexedSample(size, 21_333L * i, 1) }
        val videoInfo = if (avc) {
            TakeTrackInfo("video/avc", 1920, 1080, 90, csd0 = annexB + byteArrayOf(0x67, 0x64, 0, 0x28, 0xAC.toByte()), csd1 = annexB + byteArrayOf(0x68, 0xEE.toByte()))
        } else {
            TakeTrackInfo("video/hevc", 1920, 1080, 90, colorStandard = 1, colorRange = 2, colorTransfer = 3, pixelAspectWidth = 4, pixelAspectHeight = 3, csd0 = hevcCsd)
        }
        val tracks = mutableMapOf(TrackKind.VIDEO to videoInfo)
        val samples = mutableMapOf(TrackKind.VIDEO to v)
        if (audio) {
            tracks[TrackKind.AUDIO] = TakeTrackInfo("audio/mp4a-latm", sampleRate = 48000, channelCount = 2, csd0 = asc)
            samples[TrackKind.AUDIO] = a
        }
        return TakeSampleIndex(tracks, samples)
    }

    // ---- moov parsing --------------------------------------------------------------------------------------------

    private inner class Box(val type: String, val start: Int, val end: Int, val data: ByteArray) {
        val payload: ByteArray get() = data.copyOfRange(start + 8, end)
        fun children(skip: Int = 0): List<Box> = parse(data, start + 8 + skip, end)
        fun child(type: String): Box = children().first { it.type == type }
        fun find(vararg path: String): Box = path.fold(this) { b, t -> b.child(t) }
        fun buf(skip: Int = 0): ByteBuffer = ByteBuffer.wrap(data, start + 8 + skip, end - start - 8 - skip)
    }

    private fun parse(data: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            val size = ByteBuffer.wrap(data, p, 4).int
            out += Box(String(data, p + 4, 4, Charsets.ISO_8859_1), p, p + size, data)
            p += size
        }
        return out
    }

    private fun top(data: ByteArray): List<Box> {
        val out = ArrayList<Box>()
        var p = 0
        while (p + 8 <= data.size) {
            var size = ByteBuffer.wrap(data, p, 4).int.toLong() and 0xFFFFFFFFL
            if (size == 1L) size = ByteBuffer.wrap(data, p + 8, 8).long
            if (size > data.size - p) size = (data.size - p).toLong()
            out += Box(String(data, p + 4, 4, Charsets.ISO_8859_1), p, p + size.toInt(), data)
            p += size.toInt()
        }
        return out
    }

    private fun stsz(trak: Box): List<Int> {
        val b = trak.find("mdia", "minf", "stbl", "stsz").buf(4)
        b.int
        val n = b.int
        return List(n) { b.int }
    }

    private fun co64(trak: Box): List<Long> {
        val b = trak.find("mdia", "minf", "stbl", "co64").buf(4)
        return List(b.int) { b.long }
    }

    private fun stsc(trak: Box): List<Pair<Int, Int>> {
        val b = trak.find("mdia", "minf", "stbl", "stsc").buf(4)
        return List(b.int) { val first = b.int; val per = b.int; b.int; first to per }
    }

    private fun trak(moov: Box, handler: String): Box? = moov.children().filter { it.type == "trak" }.firstOrNull {
        String(it.find("mdia", "hdlr").payload, 8, 4, Charsets.US_ASCII) == handler
    }

    private fun rebuild(s: Synthetic, index: TakeSampleIndex): Pair<MemoryAccess, Mp4RebuildResult> {
        val access = MemoryAccess(s.file.copyOf())
        return access to Mp4MoovRebuilder.rebuildMoov(access, index)
    }

    // ---- tests ---------------------------------------------------------------------------------------------------

    @Test fun rebuildsHevcAndAacTakeWithTornTail() {
        val s = synthetic(tornTail())
        val (access, result) = rebuild(s, indexFor(s))
        assertEquals(Mp4RebuildResult.Rebuilt(10, 7, (result as Mp4RebuildResult.Rebuilt).durationUs), result)
        val boxes = top(access.data)
        assertEquals(listOf("ftyp", "free", "mdat", "moov"), boxes.map { it.type })
        val mdat = boxes[2]
        val moov = boxes[3]
        assertEquals(s.file.size, moov.start)
        // mdat size covers up to the moov; the placeholder is replaced, nothing else of the header moved.
        assertEquals(s.file.size - s.mdatStart, ByteBuffer.wrap(access.data, s.mdatStart.toInt() + 8, 8).long)
        assertEquals(1, ByteBuffer.wrap(access.data, s.mdatStart.toInt(), 4).int)
        assertEquals(s.file.size, mdat.end)
        // no byte before the moov changed, apart from the 8 size bytes
        for (i in 0 until s.file.size) {
            if (i in (s.mdatStart.toInt() + 8) until (s.mdatStart.toInt() + 16)) continue
            assertEquals("byte $i", s.file[i], access.data[i])
        }

        val video = trak(moov, "vide")!!
        val audio = trak(moov, "soun")!!
        assertEquals(s.auSizes, stsz(video))
        assertEquals(s.audioSizes, stsz(audio))
        val videoStarts = s.chunkStarts.filter { it.first == TrackKind.VIDEO }.map { it.second }
        val audioStarts = s.chunkStarts.filter { it.first == TrackKind.AUDIO }.map { it.second }
        assertEquals(videoStarts, co64(video))
        assertEquals(audioStarts, co64(audio))
        assertEquals(listOf(1 to 4, 2 to 3), stsc(video))
        assertEquals(listOf(1 to 3, 2 to 2), stsc(audio))
        // stss: IDRs at samples 1, 5 and 8; no ctts for monotonic times
        val stss = video.find("mdia", "minf", "stbl", "stss").buf(4)
        assertEquals(listOf(1, 5, 8), List(stss.int) { stss.int })
        assertTrue(video.find("mdia", "minf", "stbl").children().none { it.type == "ctts" })
        val ats = audio.find("mdia", "minf", "stbl", "stts").buf(4)
        assertEquals(1, ats.int); assertEquals(7, ats.int); assertEquals(1024, ats.int)
        // each real sample range is inside the mdat payload and not in the torn tail
        for (o in co64(video) + co64(audio)) assertTrue(o in (s.mdatStart + 16) until s.payloadEnd.toLong())
    }

    @Test fun descriptorsCarryTheCodecConfiguration() {
        val s = synthetic(tornTail())
        val (access, _) = rebuild(s, indexFor(s))
        val moov = top(access.data).last()
        val video = trak(moov, "vide")!!
        val entry = video.find("mdia", "minf", "stbl", "stsd").children(8).single()
        assertEquals("hvc1", entry.type)
        val hvcC = entry.children(78).first { it.type == "hvcC" }.payload
        assertEquals(1, hvcC[0].toInt())
        assertEquals(2, hvcC[1].toInt() and 0x1F) // Main 10
        assertArrayEquals(byteArrayOf(0x20, 0, 0, 0), hvcC.copyOfRange(2, 6))
        assertEquals(153, hvcC[12].toInt() and 0xFF)
        assertEquals(1, hvcC[16].toInt() and 3) // 4:2:0
        assertEquals(2, hvcC[17].toInt() and 7) // luma 10-bit
        assertEquals(2, hvcC[18].toInt() and 7)
        assertEquals(3, hvcC[21].toInt() and 3) // 4-byte lengths
        assertEquals(3, hvcC[22].toInt())
        val buf = ByteBuffer.wrap(hvcC, 23, hvcC.size - 23)
        val arrays = mutableMapOf<Int, ByteArray>()
        repeat(3) {
            val type = buf.get().toInt() and 0x3F
            assertEquals(1, buf.short.toInt())
            arrays[type] = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
        }
        assertArrayEquals(vps, arrays[32]); assertArrayEquals(sps, arrays[33]); assertArrayEquals(pps, arrays[34])
        val extras = entry.children(78).map { it.type }
        assertTrue("colr" in extras && "pasp" in extras)
        val colr = entry.children(78).first { it.type == "colr" }.payload
        assertEquals("nclx", String(colr, 0, 4))
        assertEquals(listOf(1, 1, 1), listOf(4, 6, 8).map { ByteBuffer.wrap(colr, it, 2).short.toInt() })
        assertEquals(0, colr[10].toInt()) // limited range

        val esds = trak(moov, "soun")!!.find("mdia", "minf", "stbl", "stsd").children(8).single().children(28).single().payload
        assertEquals("mp4a", trak(moov, "soun")!!.find("mdia", "minf", "stbl", "stsd").children(8).single().type)
        assertTrue(String(esds, Charsets.ISO_8859_1).contains(String(byteArrayOf(5, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 2, 0x11, 0x90.toByte()), Charsets.ISO_8859_1)))
        // rotation 90 in the tkhd matrix, sizes 1920x1080, consistent durations
        val tkhd = video.child("tkhd").buf(4)
        tkhd.position(tkhd.position() + 36)
        assertEquals(0x10000, tkhd.getInt(tkhd.position() + 4))
        val mvhdDur = moov.child("mvhd").buf(4).let { it.getInt(it.position() + 12) }
        val tkhdDur = video.child("tkhd").buf(4).let { it.getInt(it.position() + 16) }
        val mdhd = video.find("mdia", "mdhd").buf(4)
        val mdhdDur = mdhd.getInt(mdhd.position() + 12)
        assertEquals(tkhdDur, mvhdDur)
        assertEquals(tkhdDur.toLong(), Math.round(mdhdDur * 1000 / 90000.0))
    }

    @Test fun bFramePtsGiveCtts() {
        val s = synthetic(tornTail())
        val (access, _) = rebuild(s, indexFor(s, bFrames = true))
        val video = trak(top(access.data).last(), "vide")!!
        val ctts = video.find("mdia", "minf", "stbl", "ctts")
        assertEquals(1, ctts.payload[0].toInt()) // negative offsets need version 1
        val b = ctts.buf(4)
        val offsets = buildList { repeat(b.int) { val count = b.int; val v = b.int; repeat(count) { add(v) } } }
        assertEquals(10, offsets.size)
        // dts = sorted pts, so pts - dts rebuilds the display order
        val pts = longArrayOf(0, 100_000, 33_333, 66_666, 133_333, 233_333, 166_666, 200_000, 266_666, 300_000).map { Math.round(it * 90000 / 1e6) }
        val dts = pts.sorted()
        assertEquals(pts.indices.map { (pts[it] - dts[it]).toInt() }, offsets)
    }

    @Test fun avcVariant() {
        val s = synthetic(tornTail(), avc = true)
        val (access, result) = rebuild(s, indexFor(s, avc = true))
        assertTrue(result is Mp4RebuildResult.Rebuilt)
        val video = trak(top(access.data).last(), "vide")!!
        assertEquals(s.auSizes, stsz(video))
        val entry = video.find("mdia", "minf", "stbl", "stsd").children(8).single()
        assertEquals("avc1", entry.type)
        val avcC = entry.children(78).first { it.type == "avcC" }.payload
        assertEquals(0x64, avcC[1].toInt()); assertEquals(0x28, avcC[3].toInt())
        assertEquals(0xE1, avcC[5].toInt() and 0xFF)
    }

    @Test fun videoOnlyTake() {
        val s = synthetic(tornTail(), audio = false)
        val (access, result) = rebuild(s, indexFor(s, audio = false))
        assertEquals(10, (result as Mp4RebuildResult.Rebuilt).videoSamples)
        assertEquals(0, result.audioSamples)
        val moov = top(access.data).last()
        assertNull(trak(moov, "soun"))
        assertEquals(1, moov.children().count { it.type == "trak" })
    }

    @Test fun zeroTailAndShortIndexStopWhereTheyShould() {
        val zeros = synthetic(ByteArray(4096))
        assertEquals(10, (rebuild(zeros, indexFor(zeros)).second as Mp4RebuildResult.Rebuilt).videoSamples)
        // an index shorter than the file: only the first two video samples and one audio frame are known
        val s = synthetic(tornTail())
        val full = indexFor(s)
        val shortIdx = TakeSampleIndex(full.tracks, mapOf(TrackKind.VIDEO to full.samplesOf(TrackKind.VIDEO).take(2), TrackKind.AUDIO to full.samplesOf(TrackKind.AUDIO)))
        val (access, result) = rebuild(s, shortIdx)
        assertEquals(2, (result as Mp4RebuildResult.Rebuilt).videoSamples)
        assertEquals(0, result.audioSamples) // the walk reaches audio only after the third access unit
        assertEquals(s.auSizes.take(2), stsz(trak(top(access.data).last(), "vide")!!))
    }

    @Test fun cleanEndOfFileKeepsOnlyAUsMatchingTheIndex() {
        val s = synthetic(ByteArray(0))
        val (_, result) = rebuild(s, indexFor(s))
        assertEquals(10, (result as Mp4RebuildResult.Rebuilt).videoSamples)
        assertEquals(7, result.audioSamples)
    }

    @Test fun existingMoovIsLeftAlone() {
        val s = synthetic(tornTail())
        val (access, _) = rebuild(s, indexFor(s))
        val done = access.data.copyOf()
        assertEquals(Mp4RebuildResult.NotNeeded, Mp4MoovRebuilder.rebuildMoov(access, indexFor(s)))
        assertArrayEquals(done, access.data)
    }

    @Test fun aFinishedTakeWithACammTrackIsLeftAlone() {
        val s = synthetic(ByteArray(0))
        val (access, _) = rebuild(s, indexFor(s))
        // The CAMM track is only appended to a finished take; a later recovery pass must see a complete movie.
        val bytes = object : Mp4Bytes {
            override fun size(): Long = access.size
            override fun readFully(position: Long, buffer: ByteBuffer) {
                val n = access.read(position, buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
                if (n < buffer.remaining()) throw java.io.EOFException("Read past ${access.size}")
                buffer.position(buffer.position() + n)
            }
            override fun writeFully(position: Long, buffer: ByteBuffer) {
                val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
                access.write(position, data)
            }
            override fun sync() {}
        }
        Mp4CammAppender.begin(bytes).use { appender ->
            repeat(50) { appender.writePacket(it * 5_000L, 2, 0.1f, 0.2f, 0.3f) }
            appender.finish()
        }
        val done = access.data.copyOf()
        val moov = top(done).single { it.type == "moov" }
        assertTrue(trak(moov, "meta") != null && trak(moov, "vide") != null && trak(moov, "soun") != null)

        assertEquals(Mp4RebuildResult.NotNeeded, Mp4MoovRebuilder.rebuildMoov(access, indexFor(s)))
        assertArrayEquals(done, access.data)
    }

    @Test fun nothingWritableIsAFailure() {
        val notMp4 = MemoryAccess(ByteArray(100) { 5 })
        assertTrue(Mp4MoovRebuilder.rebuildMoov(notMp4, TakeSampleIndex(emptyMap(), emptyMap())) is Mp4RebuildResult.Failed)
        val s = synthetic(tornTail())
        val noInfo = TakeSampleIndex(emptyMap(), emptyMap())
        val (access, result) = rebuild(s, noInfo)
        assertTrue(result is Mp4RebuildResult.Failed)
        assertArrayEquals(s.file, access.data)
    }

    @Test fun ffprobeAcceptsTheRebuiltFile() {
        assumeTrue("ffprobe not installed", have("ffprobe"))
        val s = synthetic(tornTail())
        val (access, _) = rebuild(s, indexFor(s))
        val file = File.createTempFile("rebuilt", ".mp4", scratch())
        try {
            file.writeBytes(access.data)
            val (code, out) = run("ffprobe", "-v", "error", "-show_streams", file.path, mergeErrors = false, timeoutSeconds = 30)
            assertEquals(out, 0, code)
            assertTrue(out, "codec_type=video" in out && "codec_type=audio" in out)
            assertTrue(out, "codec_name=hevc" in out && "codec_name=aac" in out)
        } finally {
            file.delete()
        }
    }

    // ---- a sized mdat with a torn tail, zero padding, a torn access unit at EOF, an interrupted append ---------------

    /** [synthetic] with its mdat sized to the payload (as the muxer sizes it at stop), then [tail]. */
    private fun sized(tail: ByteArray): Synthetic {
        val s = synthetic(ByteArray(0))
        val file = s.file.copyOf()
        ByteBuffer.wrap(file, s.mdatStart.toInt() + 8, 8).putLong(file.size - s.mdatStart)
        return Synthetic(file + tail, s.mdatStart, s.chunkStarts, s.auSizes, s.audioSizes, s.payloadEnd)
    }

    /** The moov is the last top-level box and complete: its declared size runs exactly to EOF. */
    private fun assertReachableMoov(data: ByteArray) {
        val last = top(data).last()
        assertEquals("moov", last.type)
        assertEquals(data.size - last.start, ByteBuffer.wrap(data, last.start, 4).int)
    }

    @Test fun aSizedMdatWithATornMoovGetsAFreeOverTheTornBoxAndAReachableMoov() {
        // The muxer's own moov was cut by the pull: its declared size runs past EOF.
        val s = sized(i32(5000) + "moov".toByteArray() + ByteArray(200) { 3 })
        val (access, result) = rebuild(s, indexFor(s))
        assertEquals(10, (result as Mp4RebuildResult.Rebuilt).videoSamples)
        assertEquals(7, result.audioSamples)
        val boxes = top(access.data)
        assertEquals(listOf("ftyp", "free", "mdat", "free", "moov"), boxes.map { it.type })
        assertEquals(s.payloadEnd, boxes[3].start)
        assertEquals(s.file.size, boxes[4].start)
        assertEquals(s.file.size - s.payloadEnd, ByteBuffer.wrap(access.data, s.payloadEnd, 4).int)
        assertReachableMoov(access.data)
        // Nothing up to the end of the mdat changed.
        assertArrayEquals(s.file.copyOf(s.payloadEnd), access.data.copyOf(s.payloadEnd))
        assertEquals(s.auSizes, stsz(trak(boxes[4], "vide")!!))
        assertEquals(Mp4RebuildResult.NotNeeded, Mp4MoovRebuilder.rebuildMoov(access, indexFor(s)))
    }

    @Test fun completeBoxesBeforeTheTornOneAreKept() {
        val kept = i32(16) + "skip".toByteArray() + ByteArray(8) { 9 }
        val s = sized(kept + i32(5000) + "moov".toByteArray() + ByteArray(40))
        val (access, result) = rebuild(s, indexFor(s))
        assertTrue(result is Mp4RebuildResult.Rebuilt)
        val boxes = top(access.data)
        assertEquals(listOf("ftyp", "free", "mdat", "skip", "free", "moov"), boxes.map { it.type })
        assertEquals(s.payloadEnd + 16, boxes[4].start)
        assertArrayEquals(kept, access.data.copyOfRange(s.payloadEnd, s.payloadEnd + 16))
        assertReachableMoov(access.data)
    }

    @Test fun aSizedMdatWithAFewStrayBytesIsPaddedToAFree() {
        val s = sized(byteArrayOf(1, 2, 3))
        val (access, result) = rebuild(s, indexFor(s))
        assertTrue(result is Mp4RebuildResult.Rebuilt)
        val boxes = top(access.data)
        assertEquals(listOf("ftyp", "free", "mdat", "free", "moov"), boxes.map { it.type })
        assertEquals(8, boxes[3].end - boxes[3].start)
        assertEquals(s.payloadEnd + 8, boxes[4].start)
        assertReachableMoov(access.data)
        assertArrayEquals(s.file.copyOf(s.payloadEnd), access.data.copyOf(s.payloadEnd))
    }

    /** Writes like [MemoryAccess] until [failAt], the first write longer than 16 bytes, which lands half and throws. */
    private class InterruptedAccess(val memory: MemoryAccess) : Mp4Access by memory {
        var interrupted = false
        override fun write(position: Long, data: ByteArray) {
            if (!interrupted && data.size > 16) {
                interrupted = true
                memory.write(position, data.copyOf(data.size / 2))
                throw java.io.IOException("the drive went away")
            }
            memory.write(position, data)
        }
    }

    /** Lands only the first four bytes of its first write, then throws: a cut inside the tail header. */
    private class CutHeaderAccess(val memory: MemoryAccess) : Mp4Access by memory {
        var cut = false
        override fun write(position: Long, data: ByteArray) {
            if (!cut) {
                cut = true
                memory.write(position, data.copyOf(minOf(4, data.size)))
                throw java.io.IOException("the drive went away")
            }
            memory.write(position, data)
        }
    }

    @Test fun aCutInTheTailHeaderNeverLeavesATornMoovThatLooksComplete() {
        val s = sized(i32(5000) + "moov".toByteArray() + ByteArray(200) { 3 })
        val first = CutHeaderAccess(MemoryAccess(s.file.copyOf()))
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { Mp4MoovRebuilder.rebuildMoov(first, indexFor(s)) }
        val retry = MemoryAccess(first.memory.data)
        assertTrue(Mp4MoovRebuilder.rebuildMoov(retry, indexFor(s)) is Mp4RebuildResult.Rebuilt)
    }

    @Test fun aWriteErrorPropagatesAndARetryAfterACutAppendRebuildsAReachableMoov() {
        for (s in listOf(sized(i32(5000) + "moov".toByteArray() + ByteArray(200) { 3 }), synthetic(tornTail()))) {
            val first = InterruptedAccess(MemoryAccess(s.file.copyOf()))
            org.junit.Assert.assertThrows(java.io.IOException::class.java) { Mp4MoovRebuilder.rebuildMoov(first, indexFor(s)) }
            assertTrue(first.memory.data.size > s.file.size)
            // Sample bytes are untouched whatever the cut.
            assertArrayEquals(s.file.copyOfRange(s.mdatStart.toInt() + 16, s.payloadEnd), first.memory.data.copyOfRange(s.mdatStart.toInt() + 16, s.payloadEnd))

            val retry = MemoryAccess(first.memory.data)
            val result = Mp4MoovRebuilder.rebuildMoov(retry, indexFor(s))
            assertEquals(10, (result as Mp4RebuildResult.Rebuilt).videoSamples)
            assertEquals(7, result.audioSamples)
            assertReachableMoov(retry.data)
            assertEquals(s.auSizes, stsz(trak(top(retry.data).last(), "vide")!!))
        }
    }

    @Test fun anAccessUnitBeforeZeroPaddingIsKeptOnlyAtItsIndexedSize() {
        val (head, _) = mp4Head()
        val au = hevcAu(true, 1)
        val file = head + au + ByteArray(4096)
        fun index(size: Int) = TakeSampleIndex(
            mapOf(TrackKind.VIDEO to TakeTrackInfo("video/hevc", 1920, 1080, csd0 = hevcCsd)),
            mapOf(TrackKind.VIDEO to listOf(IndexedSample(size, 0, 1))),
        )
        val complete = Mp4MoovRebuilder.rebuildMoov(MemoryAccess(file.copyOf()), index(au.size))
        assertEquals(1, (complete as Mp4RebuildResult.Rebuilt).videoSamples)
        // The indexed picture was longer: slices are missing before the zeros, so it is not recovered.
        val short = MemoryAccess(file.copyOf())
        assertTrue(Mp4MoovRebuilder.rebuildMoov(short, index(au.size + 20)) is Mp4RebuildResult.Failed)
        assertArrayEquals(file, short.data)
    }

    @Test fun aTornAccessUnitAtTheEndIsNotTakenForAnAudioFrameOfItsLength() {
        val (head, _) = mp4Head()
        val aus = listOf(hevcAu(true, 1), hevcAu(false, 2), hevcAu(false, 3))
        val frames = listOf(aac(300, 1), aac(310, 2))
        val torn = aus[2].copyOf(40) // the first slice's length says 83 bytes; 36 of them remain
        val file = head + aus[0] + aus[1] + frames[0] + frames[1] + torn
        val index = TakeSampleIndex(
            mapOf(TrackKind.VIDEO to TakeTrackInfo("video/hevc", 1920, 1080, csd0 = hevcCsd), TrackKind.AUDIO to TakeTrackInfo("audio/mp4a-latm", sampleRate = 48000, channelCount = 2, csd0 = asc)),
            mapOf(
                TrackKind.VIDEO to aus.mapIndexed { i, au -> IndexedSample(au.size, i * 33_333L, if (i == 0) 1 else 0) },
                // The next audio frame is exactly as long as what is left of the torn access unit.
                TrackKind.AUDIO to (frames.map { it.size } + torn.size).mapIndexed { i, size -> IndexedSample(size, i * 21_333L, 1) },
            ),
        )
        val access = MemoryAccess(file)
        val result = Mp4MoovRebuilder.rebuildMoov(access, index)
        assertEquals(2, (result as Mp4RebuildResult.Rebuilt).videoSamples)
        assertEquals(2, result.audioSamples)
        assertEquals(listOf(300, 310), stsz(trak(top(access.data).last(), "soun")!!))
    }

    // ---- scale, mono AAC, the real channel, a real encoder -------------------------------------------------------

    private fun mp4Head(): Pair<ByteArray, Long> {
        val head = ByteArrayOutputStream()
        head.write(i32(24) + "ftyp".toByteArray() + "isom".toByteArray() + i32(0) + "isom".toByteArray() + "iso2".toByteArray())
        head.write(i32(3325) + "free".toByteArray() + ByteArray(3317))
        val mdatStart = head.size().toLong()
        head.write(i32(1) + "mdat".toByteArray() + ByteArray(8) { 0x3f })
        return head.toByteArray() to mdatStart
    }

    @Test fun anHourOfSamplesRebuildsInSeconds() {
        val (head, _) = mp4Head()
        val file = ByteArrayOutputStream()
        file.write(head)
        val video = ArrayList<IndexedSample>()
        val audio = ArrayList<IndexedSample>()
        var v = 0
        var a = 0
        while (v < 110_000) {
            for (i in 0 until 30) {
                if (v >= 110_000) break
                val au = hevcNal(if (v % 30 == 0) 19 else 1, true, 5, v)
                file.write(au)
                // jittered times make the stts runs as long as the sample count
                video += IndexedSample(au.size, v * 33_333L + (v * 7 % 11), if (v % 30 == 0) 1 else 0)
                v++
            }
            for (i in 0 until 46) {
                if (a >= 170_000) break
                file.write(aac(20, a))
                audio += IndexedSample(20, a * 21_333L, 1)
                a++
            }
        }
        val index = TakeSampleIndex(
            mapOf(TrackKind.VIDEO to TakeTrackInfo("video/hevc", 1920, 1080, csd0 = hevcCsd), TrackKind.AUDIO to TakeTrackInfo("audio/mp4a-latm", sampleRate = 48000, channelCount = 2, csd0 = asc)),
            mapOf(TrackKind.VIDEO to video, TrackKind.AUDIO to audio),
        )
        val access = MemoryAccess(file.toByteArray())
        val started = System.nanoTime()
        val result = Mp4MoovRebuilder.rebuildMoov(access, index)
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds < 20)
        result as Mp4RebuildResult.Rebuilt
        assertEquals(110_000, result.videoSamples)
        assertEquals(audio.size, result.audioSamples)
    }

    @Test fun monoAacThatParsesAsASmallFirstSliceIsWalkedAsAudio() {
        val (head, _) = mp4Head()
        val file = ByteArrayOutputStream()
        file.write(head)
        val aus = listOf(hevcAu(true, 1), hevcAu(false, 2), hevcAu(false, 3))
        // frame bytes read as a 4-byte length of 32 and a first-slice HEVC header (type 1)
        fun monoFrame(seed: Int) = byteArrayOf(0, 0, 0, 32, 0x02, 1, 0x80.toByte()) + ByteArray(293) { (0x55 + (seed + it) % 3).toByte() }
        file.write(aus[0]); file.write(aus[1])
        val frames = List(3) { monoFrame(it) }
        frames.forEach { file.write(it) }
        file.write(aus[2])
        val index = TakeSampleIndex(
            mapOf(TrackKind.VIDEO to TakeTrackInfo("video/hevc", 1920, 1080, csd0 = hevcCsd), TrackKind.AUDIO to TakeTrackInfo("audio/mp4a-latm", sampleRate = 48000, channelCount = 1, csd0 = asc)),
            mapOf(
                TrackKind.VIDEO to aus.mapIndexed { i, au -> IndexedSample(au.size, i * 33_333L, if (i == 0) 1 else 0) },
                TrackKind.AUDIO to frames.mapIndexed { i, f -> IndexedSample(f.size, i * 21_333L, 1) },
            ),
        )
        val access = MemoryAccess(file.toByteArray())
        val result = Mp4MoovRebuilder.rebuildMoov(access, index)
        assertEquals(3, (result as Mp4RebuildResult.Rebuilt).videoSamples)
        assertEquals(3, result.audioSamples)
        val moov = top(access.data).last()
        assertEquals(listOf(300, 300, 300), stsz(trak(moov, "soun")!!))
        assertEquals(aus.map { it.size }, stsz(trak(moov, "vide")!!))
    }

    @Test fun fileChannelAccessReadsWritesAndRebuilds() {
        val s = synthetic(tornTail())
        val expected = rebuild(s, indexFor(s)).first.data
        val file = File.createTempFile("channel", ".mp4", File(System.getProperty("user.home"), ".cache/claude-tmp/libremagic").apply { mkdirs() })
        try {
            file.writeBytes(s.file)
            java.io.RandomAccessFile(file, "rw").use { raf ->
                val access = FileChannelMp4Access(raf.channel)
                assertEquals(s.file.size.toLong(), access.size)
                val buf = ByteArray(10)
                assertEquals(4, access.read(s.file.size - 4L, buf, 0, 10)) // across the end
                assertArrayEquals(s.file.copyOfRange(s.file.size - 4, s.file.size), buf.copyOf(4))
                assertEquals(0, access.read(s.file.size + 5L, buf, 0, 10))
                access.write(s.file.size.toLong(), byteArrayOf(9, 8, 7))
                access.sync()
                assertEquals(s.file.size + 3L, access.size)
                access.write(s.file.size.toLong(), ByteArray(3)) // restore as zeros, then undo the extension
                raf.setLength(s.file.size.toLong())
                val result = Mp4MoovRebuilder.rebuildMoov(access, indexFor(s))
                assertTrue(result is Mp4RebuildResult.Rebuilt)
            }
            assertArrayEquals(expected, file.readBytes())
        } finally {
            file.delete()
        }
    }

    private fun scratch() = File(System.getProperty("user.home"), ".cache/claude-tmp/libremagic").apply { mkdirs() }

    /**
     * Runs [cmd] with its output in a file, so a process that stalls cannot block the test on a full pipe: the timed
     * wait comes first, and a process still alive after it is killed.
     */
    private fun run(vararg cmd: String, mergeErrors: Boolean = true, timeoutSeconds: Long = 120): Pair<Int, String> {
        val log = File.createTempFile("process", ".log", scratch())
        val errors = File.createTempFile("process", ".err", scratch())
        val builder = ProcessBuilder(*cmd).redirectOutput(log)
        if (mergeErrors) builder.redirectErrorStream(true) else builder.redirectError(errors)
        val p = builder.start()
        try {
            check(p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "${cmd.first()} did not finish in $timeoutSeconds s" }
            return p.exitValue() to log.readText()
        } finally {
            if (p.isAlive) {
                p.destroyForcibly()
                p.waitFor(10, TimeUnit.SECONDS)
            }
            log.delete()
            errors.delete()
        }
    }

    private fun have(tool: String) = try { run("which", tool).first == 0 } catch (e: Exception) { false }

    private class OriginalTrack(val sizes: List<Int>, val offsets: List<Long>, val ptsUs: List<Long>, val keys: Set<Int>)

    private fun originalTrack(trak: Box): OriginalTrack {
        val sizes = stsz(trak)
        val chunkOffsets = co64OrStco(trak)
        val runs = stsc(trak)
        val offsets = ArrayList<Long>()
        var sample = 0
        for ((i, chunk) in chunkOffsets.withIndex()) {
            val per = runs.last { it.first <= i + 1 }.second
            var o = chunk
            repeat(per) { if (sample < sizes.size) { offsets += o; o += sizes[sample]; sample++ } }
        }
        val mdhd = trak.find("mdia", "mdhd").buf(4)
        val timescale = mdhd.getInt(mdhd.position() + 8).toLong()
        val stbl = trak.find("mdia", "minf", "stbl")
        val stts = stbl.child("stts").buf(4)
        val dts = ArrayList<Long>()
        var t = 0L
        repeat(stts.int) { val n = stts.int; val d = stts.int; repeat(n) { dts += t; t += d } }
        val offs = LongArray(dts.size)
        stbl.children().firstOrNull { it.type == "ctts" }?.let { c ->
            val b = c.buf(4)
            var i = 0
            repeat(b.int) { val n = b.int; val d = b.int; repeat(n) { offs[i++] = d.toLong() } }
        }
        val keys = stbl.children().firstOrNull { it.type == "stss" }?.let { k ->
            val b = k.buf(4)
            List(b.int) { b.int - 1 }.toSet()
        } ?: sizes.indices.toSet()
        return OriginalTrack(sizes, offsets, dts.indices.map { (dts[it] + offs[it]) * 1_000_000 / timescale }, keys)
    }

    private fun co64OrStco(trak: Box): List<Long> {
        val stbl = trak.find("mdia", "minf", "stbl")
        stbl.children().firstOrNull { it.type == "co64" }?.let { c -> val b = c.buf(4); return List(b.int) { b.long } }
        val b = stbl.child("stco").buf(4)
        return List(b.int) { b.int.toLong() and 0xFFFFFFFFL }
    }

    private fun annexBConfig(trak: Box): ByteArray {
        val entry = trak.find("mdia", "minf", "stbl", "stsd").children(8).single()
        val hvcC = entry.children(78).first { it.type == "hvcC" }.payload
        val buf = ByteBuffer.wrap(hvcC, 22, hvcC.size - 22)
        val out = ByteArrayOutputStream()
        repeat(buf.get().toInt() and 0xFF) {
            buf.get()
            repeat(buf.short.toInt()) {
                val nal = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
                out.write(annexB); out.write(nal)
            }
        }
        return out.toByteArray()
    }

    @Test fun rebuildsARealEncoderFileCutShort() {
        // libx265 defaults give 4-byte length-prefixed HEVC with B-frames (ctts) and prefix SEI, and mono AAC-LC.
        assumeTrue("ffmpeg/ffprobe missing", have("ffmpeg") && have("ffprobe"))
        assumeTrue("libx265 missing", run("ffmpeg", "-hide_banner", "-encoders").second.contains("libx265"))
        val dir = File(System.getProperty("user.home"), ".cache/claude-tmp/libremagic").apply { mkdirs() }
        val src = File.createTempFile("fixture", ".mp4", dir)
        val out = File.createTempFile("fixture-rebuilt", ".mp4", dir)
        try {
            val (code, log) = run(
                "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2", "-ac", "1",
                "-c:v", "libx265", "-x265-params", "keyint=15:min-keyint=15:log-level=error", "-pix_fmt", "yuv420p", "-tag:v", "hvc1",
                "-c:a", "aac", "-f", "mp4", src.path,
            )
            assertEquals(log, 0, code)
            val original = src.readBytes()
            val boxes = top(original)
            val moovBox = boxes.first { it.type == "moov" }
            val mdatBox = boxes.first { it.type == "mdat" }
            assertTrue("moov must be last", moovBox.start > mdatBox.start)
            val vtrak = trak(moovBox, "vide")!!
            val atrak = trak(moovBox, "soun")!!
            val ov = originalTrack(vtrak)
            val oa = originalTrack(atrak)

            val videoInfo = TakeTrackInfo("video/hevc", 320, 240, csd0 = annexBConfig(vtrak))
            val audioInfo = TakeTrackInfo("audio/mp4a-latm", sampleRate = 48000, channelCount = 1)
            val index = TakeSampleIndex(
                mapOf(TrackKind.VIDEO to videoInfo, TrackKind.AUDIO to audioInfo),
                mapOf(
                    TrackKind.VIDEO to ov.sizes.indices.map { IndexedSample(ov.sizes[it], ov.ptsUs[it], if (it in ov.keys) 1 else 0) },
                    TrackKind.AUDIO to oa.sizes.indices.map { IndexedSample(oa.sizes[it], oa.ptsUs[it], 1) },
                ),
            )

            // Cut inside the last sample before the moov and put back the placeholder mdat size.
            val last = (ov.offsets.indices.map { Triple(ov.offsets[it], ov.sizes[it], true) } + oa.offsets.indices.map { Triple(oa.offsets[it], oa.sizes[it], false) })
                .maxByOrNull { it.first }!!
            val cut = (last.first + last.second / 2).toInt()
            val cutBytes = original.copyOf(cut)
            val wide = ByteBuffer.wrap(cutBytes, mdatBox.start, 4).int == 1
            if (wide) ByteBuffer.wrap(cutBytes, mdatBox.start + 8, 8).putLong(0x3f3f3f3f3f3f3f3fL) else ByteBuffer.wrap(cutBytes, mdatBox.start, 4).putInt(0)

            val access = MemoryAccess(cutBytes)
            val result = Mp4MoovRebuilder.rebuildMoov(access, index)
            result as Mp4RebuildResult.Rebuilt
            val completeVideo = ov.offsets.indices.count { ov.offsets[it] + ov.sizes[it] <= cut }
            val completeAudio = oa.offsets.indices.count { oa.offsets[it] + oa.sizes[it] <= cut }
            assertEquals(completeVideo, result.videoSamples)
            assertEquals(completeAudio, result.audioSamples)

            val rebuiltMoov = top(access.data).last { it.type == "moov" }
            val rv = originalTrack(trak(rebuiltMoov, "vide")!!)
            val ra = originalTrack(trak(rebuiltMoov, "soun")!!)
            assertEquals(ov.sizes.take(completeVideo), rv.sizes)
            assertEquals(ov.offsets.take(completeVideo), rv.offsets)
            assertEquals(oa.sizes.take(completeAudio), ra.sizes)
            assertEquals(oa.offsets.take(completeAudio), ra.offsets)
            assertEquals(ov.keys.filter { it < completeVideo }.toSet(), rv.keys)

            out.writeBytes(access.data)
            val decode = run("ffmpeg", "-v", "error", "-i", out.path, "-f", "null", "-")
            assertEquals(decode.second, "", decode.second.trim())
            val frames = run("ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", out.path)
            assertEquals(completeVideo, frames.second.trim().toInt())
        } finally {
            src.delete()
            out.delete()
        }
    }
}
