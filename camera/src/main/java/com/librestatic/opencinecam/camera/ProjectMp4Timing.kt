/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Positional access keeps sample payloads and chunk offsets unchanged. */
interface ProjectMp4File {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
    fun write(offset: Long, bytes: ByteArray)
}

/**
 * Finalize a single silent, nonfragmented, no-B-frame project track at exact rational cadence.
 * MediaMuxer may smooth near-equal sample durations; input PTS alone do not prove file cadence.
 * Preflight every box before writing. Failure leaves the unfinalized take eligible for deletion.
 * No box moves or changes size; encoded sample bytes, sample counts and offsets stay untouched.
 */
fun finalizeProjectMp4Timing(file: ProjectMp4File, rate: CaptureFrameRate, expectedFrames: Long) {
    require(expectedFrames > 0)
    val originalSize = file.size
    data class Box(val type: String, val data: Long, val end: Long)
    fun bytes(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= originalSize - length)
        return file.read(offset, length).also { require(it.size == length) }
    }
    fun u32(offset: Long): Long = ByteBuffer.wrap(bytes(offset, 4)).int.toLong() and 0xffffffffL
    fun i64(offset: Long): Long = ByteBuffer.wrap(bytes(offset, 8)).long
    fun children(start: Long, end: Long): List<Box> {
        val found = mutableListOf<Box>(); var at = start
        while (at < end) {
            require(found.size < 1024 && end - at >= 8)
            val header = bytes(at, 8); val shortSize = ByteBuffer.wrap(header).int.toLong() and 0xffffffffL
            val headerSize = if (shortSize == 1L) 16 else 8
            val size = when (shortSize) { 0L -> end - at; 1L -> i64(at + 8); else -> shortSize }
            require(size >= headerSize && size <= end - at)
            found += Box(String(header, 4, 4, Charsets.US_ASCII), at + headerSize, at + size)
            at += size
        }
        require(at == end); return found
    }
    fun inside(box: Box) = children(box.data, box.end)
    fun one(boxes: List<Box>, type: String) = boxes.single { it.type == type }
    fun within(box: Box, offset: Long, length: Int) { require(offset >= box.data && offset <= box.end - length) }
    val changes = mutableListOf<Pair<Long, ByteArray>>()
    fun put(box: Box, offset: Long, value: Long, wide: Boolean = false) {
        val length = if (wide) 8 else 4; within(box, offset, length)
        require(value >= 0 && (wide || value <= 0xffffffffL))
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        if (wide) buffer.putLong(value) else buffer.putInt(value.toInt())
        changes += offset to buffer.array()
    }
    fun version(box: Box): Int = bytes(box.data, 1)[0].toInt().also { require(it == 0 || it == 1) }
    val top = children(0, originalSize)
    require(top.none { it.type == "moof" } && top.any { it.type == "mdat" })
    val moov = one(top, "moov"); val movie = inside(moov)
    require(movie.none { it.type == "mvex" })
    val mvhd = one(movie, "mvhd"); val trak = one(movie, "trak"); val track = inside(trak)
    val mdia = one(track, "mdia"); val media = inside(mdia); val mdhd = one(media, "mdhd")
    val hdlr = one(media, "hdlr"); within(hdlr, hdlr.data + 8, 4)
    require(String(bytes(hdlr.data + 8, 4), Charsets.US_ASCII) == "vide")
    val stbl = one(inside(one(media, "minf")), "stbl"); val samples = inside(stbl)
    val stsz = one(samples, "stsz"); within(stsz, stsz.data + 8, 4)
    require(u32(stsz.data + 8) == expectedFrames)
    require(stsz.end - stsz.data == 12L + if (u32(stsz.data + 4) == 0L) Math.multiplyExact(expectedFrames, 4L) else 0L)
    val stts = one(samples, "stts"); within(stts, stts.data, 8)
    require(u32(stts.data) == 0L)
    val entries = u32(stts.data + 4)
    require(entries in 1..1_000_000 && stts.end - stts.data == 8 + entries * 8)
    var counted = 0L
    for (i in 0 until entries) {
        val at = stts.data + 8 + i * 8
        val count = u32(at); require(count > 0); counted = Math.addExact(counted, count)
        put(stts, at + 4, rate.denominator.toLong())
    }
    require(counted == expectedFrames)
    for (ctts in samples.filter { it.type == "ctts" }) {
        version(ctts); within(ctts, ctts.data, 8)
        val count = u32(ctts.data + 4)
        require(count <= 1_000_000 && ctts.end - ctts.data == 8 + count * 8)
        for (i in 0 until count) require(u32(ctts.data + 12 + i * 8) == 0L) { "Project retiming requires no composition offsets" }
    }
    val duration = Math.multiplyExact(expectedFrames, rate.denominator.toLong())
    val mdVersion = version(mdhd)
    put(mdhd, mdhd.data + if (mdVersion == 0) 12 else 20, rate.numerator.toLong())
    put(mdhd, mdhd.data + if (mdVersion == 0) 16 else 24, duration, mdVersion == 1)
    val movieVersion = version(mvhd)
    val scaleAt = mvhd.data + if (movieVersion == 0) 12 else 20
    within(mvhd, scaleAt, 4); val movieScale = u32(scaleAt); require(movieScale > 0)
    val movieDuration = java.math.BigInteger.valueOf(duration).multiply(java.math.BigInteger.valueOf(movieScale))
        .add(java.math.BigInteger.valueOf(rate.numerator.toLong() - 1)).divide(java.math.BigInteger.valueOf(rate.numerator.toLong()))
    require(movieDuration.bitLength() <= 63)
    put(mvhd, mvhd.data + if (movieVersion == 0) 16 else 24, movieDuration.toLong(), movieVersion == 1)
    val tkhd = one(track, "tkhd"); val trackVersion = version(tkhd)
    put(tkhd, tkhd.data + if (trackVersion == 0) 20 else 28, movieDuration.toLong(), trackVersion == 1)
    for (edts in track.filter { it.type == "edts" }) {
        val elst = one(inside(edts), "elst"); val editVersion = version(elst)
        within(elst, elst.data + 4, 4); require(u32(elst.data + 4) == 1L)
        val mediaTimeAt = elst.data + if (editVersion == 0) 12 else 16
        within(elst, mediaTimeAt, if (editVersion == 0) 8 else 12)
        require((if (editVersion == 0) u32(mediaTimeAt) else i64(mediaTimeAt)) == 0L)
        require(u32(mediaTimeAt + if (editVersion == 0) 4 else 8) == 0x00010000L)
        put(elst, elst.data + 8, movieDuration.toLong(), editVersion == 1)
    }
    require(file.size == originalSize)
    changes.forEach { (offset, value) -> file.write(offset, value) }
    require(file.size == originalSize)
    changes.forEach { (offset, value) -> check(file.read(offset, value.size).contentEquals(value)) }
}
