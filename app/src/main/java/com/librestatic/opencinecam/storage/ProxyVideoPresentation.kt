/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.ProjectMp4File
import java.math.BigInteger
import java.nio.ByteBuffer

/** Independent movie-clock endpoint. MediaExtractor KEY_DURATION can be a media span, not this endpoint.
 * Sidecar input is restricted to a nonfragmented MP4 and at most one leading empty edit plus one
 * unit-rate media edit. Repeated edits/rate changes need explicit mapping and are rejected. */
internal fun inspectProxyVideoEndUs(file: ProjectMp4File): Long = readProxyVideoEndUs(file, null)

/** Repair only Media3's known tkhd span/endpoint mismatch on an owned candidate, before AAC
 * finalization recomputes mvhd. The edit list must independently agree with the source endpoint. */
internal fun finalizeProxyVideoTrackHeader(file: ProjectMp4File, expectedEndUs: Long) {
    require(expectedEndUs > 0)
    check(readProxyVideoEndUs(file, expectedEndUs) == expectedEndUs)
    check(inspectProxyVideoEndUs(file) == expectedEndUs)
}

private fun readProxyVideoEndUs(file: ProjectMp4File, repairEndpointUs: Long?): Long {
    data class Box(val type: String, val data: Long, val end: Long)
    val size = file.size
    fun read(at: Long, length: Int): ByteArray {
        require(at >= 0 && length >= 0 && at <= size - length)
        return file.read(at, length).also { require(it.size == length) }
    }
    fun u32(at: Long) = ByteBuffer.wrap(read(at, 4)).int.toLong() and 0xffffffffL
    fun i64(at: Long) = ByteBuffer.wrap(read(at, 8)).long
    fun children(start: Long, end: Long): List<Box> {
        val boxes = mutableListOf<Box>(); var at = start
        while (at < end) {
            require(boxes.size < 1024 && end - at >= 8)
            val header = read(at, 8); val short = u32(at)
            val headerSize = if (short == 1L) 16 else 8
            require(end - at >= headerSize)
            val length = when (short) { 0L -> end - at; 1L -> i64(at + 8); else -> short }
            require(length >= headerSize && length <= end - at)
            boxes += Box(String(header, 4, 4, Charsets.US_ASCII), at + headerSize, at + length)
            at += length
        }
        return boxes
    }
    fun inside(box: Box) = children(box.data, box.end)
    fun one(boxes: List<Box>, type: String) = boxes.single { it.type == type }
    fun within(box: Box, at: Long, length: Int) { require(at >= box.data && at <= box.end - length) }
    fun version(box: Box): Int {
        within(box, box.data, 4)
        return read(box.data, 1)[0].toInt().also { require(it in 0..1) }
    }
    fun duration(box: Box, narrowOffset: Int, wideOffset: Int): Long {
        val wide = version(box) == 1; val at = box.data + if (wide) wideOffset else narrowOffset
        within(box, at, if (wide) 8 else 4)
        return (if (wide) i64(at) else u32(at)).also { require(it > 0 && (wide || it != 0xffffffffL)) }
    }
    val top = children(0, size)
    require(top.none { it.type == "moof" } && top.any { it.type == "mdat" })
    val movie = inside(one(top, "moov")); require(movie.none { it.type == "mvex" })
    val mvhd = one(movie, "mvhd"); val scaleAt = mvhd.data + if (version(mvhd) == 0) 12 else 20
    within(mvhd, scaleAt, 4); val scale = u32(scaleAt); require(scale > 0)
    val tracks = movie.filter { it.type == "trak" }.filter { trak ->
        val media = inside(one(inside(trak), "mdia")); val handler = one(media, "hdlr")
        within(handler, handler.data + 8, 4)
        String(read(handler.data + 8, 4), Charsets.US_ASCII) == "vide"
    }
    val track = inside(tracks.single()); val tkhd = one(track, "tkhd")
    val ticks = duration(tkhd, 20, 28)
    var endpointTicks = ticks
    var leadingGap = 0L
    val edits = track.filter { it.type == "edts" }; require(edits.size <= 1)
    if (edits.isNotEmpty()) {
        val elst = one(inside(edits.single()), "elst"); val wide = version(elst) == 1
        within(elst, elst.data + 4, 4); val count = u32(elst.data + 4)
        val stride = if (wide) 20 else 12
        require(count in 1..2 && elst.end - elst.data == 8 + count * stride)
        var total = 0L
        for (i in 0 until count) {
            val at = elst.data + 8 + i * stride
            val span = if (wide) i64(at) else u32(at)
            val mediaAt = at + if (wide) 8 else 4
            val mediaTime = if (wide) i64(mediaAt) else ByteBuffer.wrap(read(mediaAt, 4)).int.toLong()
            require(span > 0 && u32(mediaAt + if (wide) 8 else 4) == 0x10000L)
            require(if (i == count - 1) mediaTime >= 0 else mediaTime == -1L)
            if (mediaTime == -1L) leadingGap = span
            total = Math.addExact(total, span)
        }
        endpointTicks = total
        require(total == ticks || (repairEndpointUs != null && leadingGap > 0 && Math.addExact(ticks, leadingGap) == total)) {
            "Video track and edit endpoints disagree"
        }
    }
    val micros = BigInteger.valueOf(endpointTicks).multiply(BigInteger.valueOf(1_000_000))
        .add(BigInteger.valueOf(scale - 1)).divide(BigInteger.valueOf(scale))
    require(micros.bitLength() <= 63)
    val endpoint = micros.toLong()
    if (repairEndpointUs != null) {
        require(endpoint == repairEndpointUs) { "Candidate edit endpoint differs from source" }
        if (endpointTicks != ticks) {
            val wide = version(tkhd) == 1
            require(wide || endpointTicks <= 0xffffffffL)
            val at = tkhd.data + if (wide) 28 else 20
            val bytes = ByteBuffer.allocate(if (wide) 8 else 4).apply {
                if (wide) putLong(endpointTicks) else putInt(endpointTicks.toInt())
            }.array()
            file.write(at, bytes)
            check(file.size == size && file.read(at, bytes.size).contentEquals(bytes))
        }
    }
    return endpoint
}
