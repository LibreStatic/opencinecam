/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.math.BigInteger
import java.nio.ByteBuffer

/** Values must come from source accounting and a verified codec configuration, not guessed priming. */
data class AacSourceWindow(
    val sampleRateHz: Int,
    val sourceFrames: Long,
    val primingFrames: Long,
    val expectedPackets: Long,
    val presentationOffsetUs: Long = 0,
) {
    init {
        require(sampleRateHz in 8000..192000 && sourceFrames > 0 && primingFrames >= 0)
        require(expectedPackets > 0 && presentationOffsetUs >= 0)
        Math.addExact(sourceFrames, primingFrames)
    }
}

data class AacSourceWindowResult(
    val sourceFrames: Long,
    val primingFrames: Long,
    val encodedFrames: Long,
    val remainderFrames: Long,
    val movieTimescale: Long,
    val presentationDurationTicks: Long,
)

/**
 * Rebuild only moov: retain every encoded byte/chunk offset, explicitly select the known PCM window.
 * Uses an exact common movie timescale and roll sample groups alongside the edit list. No codec or
 * microphone delay is inferred here. Raw decoder buffers may still need clipping to the edit end.
 * All parsing and replacement construction precede writes; a write failure invalidates the pending
 * take. Append the replacement before retiring the old moov. This is not crash-atomic publication.
 */
fun finalizeAacSourceWindow(file: ProjectMp4File, window: AacSourceWindow): AacSourceWindowResult {
    val originalSize = file.size
    val top = mutableListOf<Triple<String, Long, Long>>()
    var cursor = 0L
    while (cursor < originalSize) {
        require(top.size < 1024 && originalSize - cursor >= 8)
        val header = file.read(cursor, 8); require(header.size == 8)
        val shortSize = u32(header, 0)
        val size = when (shortSize) {
            0L -> error("Open-ended top-level MP4 box prevents append finalization")
            1L -> { require(originalSize - cursor >= 16); i64(file.read(cursor + 8, 8), 0) }
            else -> shortSize
        }
        require(size >= (if (shortSize == 1L) 16 else 8) && size <= originalSize - cursor)
        top += Triple(String(header, 4, 4, Charsets.US_ASCII), cursor, size)
        cursor += size
    }
    require(top.none { it.first == "moof" } && top.any { it.first == "mdat" })
    val old = top.single { it.first == "moov" }
    require(old.third in 8..MAX_AAC_MOOV_BYTES.toLong())
    val original = file.read(old.second, old.third.toInt()); require(original.size == old.third.toInt())
    val root = WindowBox.parse(original).single()
    require(root.type == "moov")
    val movie = root.children()
    require(movie.none { it.type == "mvex" })
    val mvhd = movie.single { it.type == "mvhd" }
    val oldScale = headerScale(mvhd); require(oldScale > 0)
    val newScale = lcm(lcm(oldScale, window.sampleRateHz.toLong()), 1_000_000)
    require(newScale <= 0xffffffffL) { "Exact movie timescale exceeds MP4 limits" }
    val audioDuration = Math.multiplyExact(window.sourceFrames, newScale / window.sampleRateHz)
    val offsetTicks = Math.multiplyExact(window.presentationOffsetUs, newScale / 1_000_000)
    val audioTrackDuration = Math.addExact(offsetTicks, audioDuration)
    val tracks = movie.filter { it.type == "trak" }
    require(tracks.isNotEmpty() && tracks.size <= 2)
    var audioCount = 0
    var encodedFrames = 0L
    var movieDuration = 0L
    val replacements = tracks.associateWith { track ->
        val parts = track.children()
        val tkhd = parts.single { it.type == "tkhd" }
        val mdia = parts.single { it.type == "mdia" }
        val media = mdia.children()
        val handler = media.single { it.type == "hdlr" }.payload
        require(handler.size >= 12)
        val kind = String(handler, 8, 4, Charsets.US_ASCII)
        require(kind == "soun" || kind == "vide")
        if (kind == "vide") {
            val duration = scaleExact(headerDuration(tkhd), oldScale, newScale)
            movieDuration = maxOf(movieDuration, duration)
            WindowBox.container("trak", parts.map {
                when (it.type) {
                    "tkhd" -> wideHeader(it, duration)
                    "edts" -> rescaleEdits(it, oldScale, newScale)
                    else -> it
                }
            })
        } else {
            audioCount++; require(audioCount == 1)
            val mdhd = media.single { it.type == "mdhd" }
            require(headerScale(mdhd) == window.sampleRateHz.toLong())
            val minf = media.single { it.type == "minf" }
            val info = minf.children()
            val stbl = info.single { it.type == "stbl" }
            val samples = stbl.children()
            require(samples.none { it.type in setOf("ctts", "sgpd", "sbgp") })
            val stsd = samples.single { it.type == "stsd" }.payload
            require(stsd.size >= 8 && u32(stsd, 0) == 0L && u32(stsd, 4) == 1L)
            val entry = WindowBox.parse(stsd.copyOfRange(8, stsd.size)).single()
            require(entry.type == "mp4a" && entry.payload.size >= 28)
            require(u32(entry.payload, 8) == 0L) { "Only version-zero AAC sample entries are supported" }
            validateLcDescription(entry, window.sampleRateHz)
            val entryRate = u32(entry.payload, 24)
            require(entryRate == window.sampleRateHz.toLong() * 65536L)
            val stsz = samples.single { it.type == "stsz" }.payload
            require(stsz.size >= 12 && u32(stsz, 0) == 0L)
            val packets = u32(stsz, 8)
            require(packets == window.expectedPackets)
            require(stsz.size.toLong() == 12L + if (u32(stsz, 4) == 0L) Math.multiplyExact(packets, 4L) else 0L)
            val stts = samples.single { it.type == "stts" }.payload
            require(stts.size >= 8 && u32(stts, 0) == 0L)
            val entries = u32(stts, 4)
            require(entries in 1..1_000_000 && stts.size.toLong() == 8L + entries * 8L)
            var counted = 0L
            for (index in 0 until entries.toInt()) {
                val count = u32(stts, 8 + index * 8); val delta = u32(stts, 12 + index * 8)
                // AAC-LC access units in this route represent 1024 source-rate frames.
                require(count > 0 && delta == 1024L)
                counted = Math.addExact(counted, count)
                encodedFrames = Math.addExact(encodedFrames, Math.multiplyExact(count, delta))
            }
            require(counted == packets && headerDuration(mdhd) >= encodedFrames)
            // MediaMuxer can include the empty track-start interval in mdhd. The sample table
            // describes the encoded media itself; the replacement edit carries its placement.
            val newMdhd = wideHeader(mdhd, encodedFrames, window.sampleRateHz.toLong())
            val mediaRanges = top.filter { it.first == "mdat" }.map {
                val header = if (u32(file.read(it.second, 4), 0) == 1L) 16 else 8
                (it.second + header) until (it.second + it.third)
            }
            validateAudioChunks(samples, packets, stsz, mediaRanges)
            require(Math.addExact(window.primingFrames, window.sourceFrames) <= encodedFrames) {
                "Encoded AAC does not contain the requested source window"
            }
            val sgpd = WindowBox("sgpd", ints(0x01000000, fourcc("roll"), 2, 1) + byteArrayOf(-1, -1))
            val sbgp = WindowBox("sbgp", ints(0, fourcc("roll"), 1, packets.toInt(), 1))
            val newStbl = WindowBox.container("stbl", samples + listOf(sgpd, sbgp))
            val newMinf = WindowBox.container("minf", info.map { if (it === stbl) newStbl else it })
            val newMdia = WindowBox.container("mdia", media.map {
                when { it === minf -> newMinf; it === mdhd -> newMdhd; else -> it }
            })
            val edits = mutableListOf<ByteArray>()
            if (offsetTicks > 0) edits += edit(offsetTicks, -1)
            edits += edit(audioDuration, window.primingFrames)
            val edts = WindowBox.container("edts", listOf(WindowBox("elst", ints(0x01000000, edits.size) + edits.fold(byteArrayOf()) { a, v -> a + v })))
            movieDuration = maxOf(movieDuration, audioTrackDuration)
            WindowBox.container("trak", parts.filterNot { it.type == "edts" }.map {
                when {
                    it === tkhd -> wideHeader(it, audioTrackDuration)
                    it === mdia -> newMdia
                    else -> it
                }
            } + edts)
        }
    }
    require(audioCount == 1)
    val replacement = WindowBox.container("moov", movie.map {
        when {
            it === mvhd -> wideHeader(it, movieDuration, newScale)
            replacements.containsKey(it) -> replacements.getValue(it)
            else -> it
        }
    }).encode()
    require(replacement.size <= MAX_AAC_MOOV_BYTES && file.size == originalSize)
    file.write(originalSize, replacement)
    check(file.read(originalSize, replacement.size).contentEquals(replacement))
    file.write(old.second + 4, "free".toByteArray(Charsets.US_ASCII))
    check(file.read(old.second + 4, 4).contentEquals("free".toByteArray(Charsets.US_ASCII)))
    check(file.size == Math.addExact(originalSize, replacement.size.toLong()))
    return AacSourceWindowResult(window.sourceFrames, window.primingFrames, encodedFrames,
        encodedFrames - window.primingFrames - window.sourceFrames, newScale, audioTrackDuration)
}

private const val MAX_AAC_MOOV_BYTES = 64 * 1024 * 1024
private fun u32(bytes: ByteArray, at: Int): Long = ByteBuffer.wrap(bytes, at, 4).int.toLong() and 0xffffffffL
private fun i64(bytes: ByteArray, at: Int): Long = ByteBuffer.wrap(bytes, at, 8).long
private fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it) } }.array()
private fun fourcc(value: String): Int = ByteBuffer.wrap(value.toByteArray(Charsets.US_ASCII)).int
private fun version(box: WindowBox): Int {
    require(box.payload.size >= 4)
    return (box.payload[0].toInt() and 255).also { require(it in 0..1) }
}
private fun headerScale(box: WindowBox): Long {
    require(box.type == "mvhd" || box.type == "mdhd")
    val at = if (version(box) == 0) 12 else 20
    require(box.payload.size >= at + 8)
    return u32(box.payload, at)
}
private fun headerDuration(box: WindowBox): Long {
    val wide = version(box) == 1
    val at = if (box.type == "tkhd") { if (wide) 28 else 20 } else { if (wide) 24 else 16 }
    require(box.payload.size >= at + if (wide) 8 else 4)
    return (if (wide) i64(box.payload, at) else u32(box.payload, at)).also { require(it >= 0) }
}
private fun wideHeader(box: WindowBox, duration: Long, scale: Long? = null): WindowBox {
    val p = box.payload; val wide = version(box) == 1; require(duration >= 0)
    val timeBytes = if (wide) 8 else 4
    val creation = if (wide) i64(p, 4) else u32(p, 4)
    val modification = if (wide) i64(p, 12) else u32(p, 8)
    val extra = if (box.type == "tkhd") 8 else 4
    val oldDurationAt = 4 + timeBytes * 2 + extra
    require(p.size >= oldDurationAt + timeBytes)
    val result = ByteBuffer.allocate(p.size + if (wide) 0 else 12)
    result.putInt((u32(p, 0).toInt() and 0x00ffffff) or 0x01000000).putLong(creation).putLong(modification)
    if (box.type == "tkhd") result.put(p, 4 + timeBytes * 2, 8)
    else result.putInt(requireNotNull(scale).also { require(it in 1..0xffffffffL) }.toInt())
    result.putLong(duration).put(p, oldDurationAt + timeBytes, p.size - oldDurationAt - timeBytes)
    return WindowBox(box.type, result.array())
}
private fun rescaleEdits(box: WindowBox, from: Long, to: Long): WindowBox {
    val children = box.children(); val elst = children.single { it.type == "elst" }
    val wide = version(elst) == 1; val p = elst.payload
    require(p.size >= 8)
    val count = u32(p, 4); val size = if (wide) 20 else 12
    require(count in 1..1024 && p.size.toLong() == 8 + count * size)
    val entries = (0 until count.toInt()).map { index ->
        val at = 8 + index * size
        val duration = if (wide) i64(p, at) else u32(p, at)
        val time = if (wide) i64(p, at + 8) else ByteBuffer.wrap(p, at + 4, 4).int.toLong()
        require(duration >= 0 && time >= -1 && u32(p, at + size - 4) == 0x10000L)
        edit(scaleExact(duration, from, to), time)
    }
    val replacement = WindowBox("elst", ints(0x01000000, count.toInt()) + entries.fold(byteArrayOf()) { a, v -> a + v })
    return WindowBox.container("edts", children.map { if (it === elst) replacement else it })
}
private fun edit(duration: Long, time: Long): ByteArray = ByteBuffer.allocate(20).putLong(duration).putLong(time).putInt(0x10000).array()
private fun lcm(a: Long, b: Long): Long = Math.multiplyExact(a / BigInteger.valueOf(a).gcd(BigInteger.valueOf(b)).toLong(), b)
private fun scaleExact(value: Long, from: Long, to: Long): Long = Math.multiplyExact(value, to / from).also { require(to % from == 0L) }
private class WindowBox(val type: String, val payload: ByteArray) {
    fun children(): List<WindowBox> = parse(payload)
    fun encode(): ByteArray = ints(Math.addExact(8, payload.size), fourcc(type)) + payload
    companion object {
        fun container(type: String, children: List<WindowBox>): WindowBox {
            val total = children.fold(0) { count, box -> Math.addExact(count, Math.addExact(8, box.payload.size)) }
            require(total <= MAX_AAC_MOOV_BYTES)
            val output = ByteBuffer.allocate(total)
            children.forEach { output.putInt(8 + it.payload.size).putInt(fourcc(it.type)).put(it.payload) }
            return WindowBox(type, output.array())
        }
        fun parse(bytes: ByteArray): List<WindowBox> {
            val result = mutableListOf<WindowBox>(); var at = 0
            while (at < bytes.size) {
                require(bytes.size - at >= 8 && result.size < 1024)
                val short = u32(bytes, at)
                val header = if (short == 1L) 16 else 8
                require(bytes.size - at >= header)
                val size = if (short == 1L) i64(bytes, at + 8) else short
                require(size >= header && size <= bytes.size - at)
                result += WindowBox(String(bytes, at + 4, 4, Charsets.US_ASCII), bytes.copyOfRange(at + header, at + size.toInt()))
                at += size.toInt()
            }
            return result
        }
    }
}

private fun validateLcDescription(entry: WindowBox, sampleRate: Int) {
    val esds = WindowBox.parse(entry.payload.copyOfRange(28, entry.payload.size)).single { it.type == "esds" }.payload
    require(esds.size >= 4 && u32(esds, 0) == 0L)
    fun descriptor(bytes: ByteArray, offset: Int, tag: Int): ByteArray {
        require(offset < bytes.size && bytes[offset].toInt() and 255 == tag)
        var at = offset + 1; var length = 0; var octets = 0
        do {
            require(at < bytes.size && octets++ < 4)
            val part = bytes[at++].toInt() and 255
            length = (length shl 7) or (part and 127)
        } while (part and 128 != 0)
        require(length <= bytes.size - at)
        return bytes.copyOfRange(at, at + length)
    }
    val es = descriptor(esds, 4, 3)
    require(es.size >= 3 && es[2].toInt() and 0xe0 == 0) { "Extended ES descriptors are not generated by this route" }
    val config = descriptor(es, 3, 4)
    require(config.size >= 13 && config[0].toInt() and 255 == 0x40 && (config[1].toInt() ushr 2) and 63 == 5)
    val asc = descriptor(config, 13, 5)
    var bit = 0
    fun bits(count: Int): Int {
        require(bit + count <= asc.size * 8)
        var result = 0
        repeat(count) { result = (result shl 1) or ((asc[bit / 8].toInt() ushr (7 - bit % 8)) and 1); bit++ }
        return result
    }
    require(bits(5) == 2) { "Source window requires AAC-LC" }
    val rateIndex = bits(4)
    val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    val rate = if (rateIndex == 15) bits(24) else rates.getOrNull(rateIndex)
    require(rate == sampleRate)
    val channels = bits(4)
    require(channels in 1..2 && channels == (ByteBuffer.wrap(entry.payload, 16, 2).short.toInt() and 65535))
    require(bits(3) == 0) { "Unsupported AAC frame-length/core-coder flags" }
}

private fun validateAudioChunks(samples: List<WindowBox>, packets: Long, sizes: ByteArray, mediaRanges: List<LongRange>) {
    val offsets = samples.single { it.type == "co64" || it.type == "stco" }
    val wide = offsets.type == "co64"; val data = offsets.payload
    require(data.size >= 8 && u32(data, 0) == 0L)
    val chunks = u32(data, 4); val step = if (wide) 8 else 4
    require(chunks in 1..1_000_000 && data.size.toLong() == 8L + chunks * step)
    val map = samples.single { it.type == "stsc" }.payload
    require(map.size >= 8 && u32(map, 0) == 0L)
    val entries = u32(map, 4)
    require(entries in 1..chunks && map.size.toLong() == 8L + entries * 12L)
    var previous = 0L
    for (index in 0 until entries.toInt()) {
        val first = u32(map, 8 + index * 12)
        require(first > previous && first <= chunks && (index != 0 || first == 1L))
        require(u32(map, 12 + index * 12) > 0 && u32(map, 16 + index * 12) == 1L)
        previous = first
    }
    var entry = 0; var consumed = 0L
    val fixed = u32(sizes, 4)
    for (index in 0 until chunks.toInt()) {
        if (entry + 1 < entries && index + 1L == u32(map, 8 + (entry + 1) * 12)) entry++
        val count = u32(map, 12 + entry * 12)
        require(count <= packets - consumed)
        var length = 0L
        if (fixed > 0) length = Math.multiplyExact(count, fixed)
        else for (sample in consumed until consumed + count) {
            val size = u32(sizes, Math.toIntExact(12 + sample * 4))
            require(size > 0)
            length = Math.addExact(length, size)
        }
        val offset = if (wide) i64(data, 8 + index * 8) else u32(data, 8 + index * 4)
        require(offset >= 0)
        val end = Math.addExact(offset, length)
        require(mediaRanges.any { offset >= it.first && end - 1 <= it.last }) { "AAC chunk is outside media payload" }
        consumed += count
    }
    require(consumed == packets)
}

/** Read the declared presentation window, not MediaExtractor's seek-dependent pre-roll packets. */
fun inspectAacSourceWindow(file: ProjectMp4File): AacSourceWindow {
    val size = file.size
    var at = 0L; var movie: WindowBox? = null; var boxes = 0
    while (at < size) {
        require(size - at >= 8 && boxes++ < 1024)
        val header = file.read(at, 8); require(header.size == 8)
        val short = u32(header, 0)
        val length = if (short == 1L) { require(size - at >= 16); i64(file.read(at + 8, 8), 0) } else short
        require(length >= (if (short == 1L) 16 else 8) && length <= size - at)
        if (String(header, 4, 4, Charsets.US_ASCII) == "moov") {
            require(movie == null && length <= MAX_AAC_MOOV_BYTES)
            movie = WindowBox.parse(file.read(at, length.toInt())).single()
        }
        at += length
    }
    val children = requireNotNull(movie).children()
    val scale = headerScale(children.single { it.type == "mvhd" }); require(scale > 0)
    val audio = children.filter { it.type == "trak" }.single { track ->
        val handler = track.children().single { it.type == "mdia" }.children().single { it.type == "hdlr" }.payload
        require(handler.size >= 12)
        String(handler, 8, 4, Charsets.US_ASCII) == "soun"
    }.children()
    val media = audio.single { it.type == "mdia" }.children()
    val mdhd = media.single { it.type == "mdhd" }
    val rate = headerScale(mdhd); require(rate in 8000..192000)
    val table = media.single { it.type == "minf" }.children().single { it.type == "stbl" }.children()
    val sgpd = table.single { it.type == "sgpd" }.payload
    require(sgpd.contentEquals(ints(0x01000000, fourcc("roll"), 2, 1) + byteArrayOf(-1, -1)))
    val stsz = table.single { it.type == "stsz" }.payload; require(stsz.size >= 12)
    val packets = u32(stsz, 8)
    require(table.single { it.type == "sbgp" }.payload.contentEquals(ints(0, fourcc("roll"), 1, packets.toInt(), 1)))
    val elst = audio.single { it.type == "edts" }.children().single { it.type == "elst" }.payload
    require(elst.size >= 8 && u32(elst, 0) == 0x01000000L)
    val count = u32(elst, 4); require(count in 1..2 && elst.size.toLong() == 8 + count * 20)
    var offset = 0L
    if (count == 2L) {
        offset = i64(elst, 8)
        require(offset > 0 && i64(elst, 16) == -1L && u32(elst, 24) == 0x10000L)
    }
    val last = 8 + (count.toInt() - 1) * 20
    val duration = i64(elst, last); val priming = i64(elst, last + 8)
    require(duration > 0 && priming >= 0 && u32(elst, last + 16) == 0x10000L)
    fun convert(value: Long, numerator: Long): Long {
        val result = BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator)).divideAndRemainder(BigInteger.valueOf(scale))
        require(result[1] == BigInteger.ZERO && result[0].bitLength() <= 63)
        return result[0].toLong()
    }
    val source = convert(duration, rate)
    require(Math.addExact(source, priming) <= headerDuration(mdhd))
    return AacSourceWindow(rate.toInt(), source, priming, packets, convert(offset, 1_000_000))
}
