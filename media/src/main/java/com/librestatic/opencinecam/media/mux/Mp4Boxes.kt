/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** A file this code cannot read or edit as an MP4. [reason] is a short, stable kebab-case code. */
class Mp4FormatException(
    val reason: String,
    detail: String = reason,
) : Exception(if (detail == reason) reason else "$reason: $detail")

/** The largest `moov` loaded into memory. A recorded take's is a few MB at most. */
internal const val MAX_MOOV_BYTES = 64L * 1024 * 1024

internal const val U32_MAX = 0xFFFFFFFFL

/** A top-level box of a file. */
internal class Mp4Box(
    val type: String,
    val offset: Long,
    val headerSize: Int,
    val size: Long,
    /** The box had size 0: it runs to the end of the file. */
    val openEnded: Boolean,
) {
    val payloadOffset: Long get() = offset + headerSize
    val end: Long get() = offset + size
}

/** A box inside an in-memory byte array; offsets index that array. */
internal class Mp4Child(
    val type: String,
    val offset: Int,
    val headerSize: Int,
    val size: Int,
) {
    val payload: Int get() = offset + headerSize
    val end: Int get() = offset + size

    fun requirePayload(length: Int) {
        if (headerSize.toLong() + length > size) throw Mp4FormatException("box-truncated", "'$type' needs $length payload bytes, has ${size - headerSize}")
    }
}

/** Lists the file's top-level boxes, checking that they tile the file exactly. */
internal fun scanTopLevelBoxes(file: Mp4Bytes): List<Mp4Box> {
    val fileSize = file.size()
    val header = ByteBuffer.allocate(8)
    val boxes = ArrayList<Mp4Box>()
    var position = 0L
    while (position < fileSize) {
        val left = fileSize - position
        if (left < 8) throw Mp4FormatException("trailing-bytes", "$left bytes at $position are not a box")
        header.clear()
        file.readFully(position, header)
        val size32 = header.getInt(0).toLong() and U32_MAX
        val type = fourcc(header.array(), 4)
        var headerSize = 8
        val size = when (size32) {
            0L -> left
            1L -> {
                if (left < 16) throw Mp4FormatException("box-too-short", "'$type' at $position has no room for its 64-bit size")
                headerSize = 16
                header.clear()
                file.readFully(position + 8, header)
                header.getLong(0)
            }
            else -> size32
        }
        if (size < headerSize) throw Mp4FormatException("box-too-short", "'$type' at $position has size $size")
        if (size > left) throw Mp4FormatException("box-past-end", "'$type' at $position has size $size, the file has $left bytes left")
        boxes += Mp4Box(type, position, headerSize, size, openEnded = size32 == 0L)
        position += size
    }
    return boxes
}

/** Lists the boxes in [data] between [start] and [end]. Fewer than 8 zero bytes at the end are tolerated, as old QuickTime writers leave them. */
internal fun childBoxes(data: ByteArray, start: Int, end: Int): List<Mp4Child> {
    val children = ArrayList<Mp4Child>()
    var position = start
    while (position < end) {
        val left = end - position
        if (left < 8) {
            if ((position until end).all { data[it].toInt() == 0 }) break
            throw Mp4FormatException("bad-child-box", "$left bytes at $position are not a box")
        }
        val size32 = u32(data, position)
        val type = fourcc(data, position + 4)
        var headerSize = 8
        val size = when (size32) {
            0L -> left.toLong()
            1L -> {
                if (left < 16) throw Mp4FormatException("bad-child-box", "'$type' at $position has no room for its 64-bit size")
                headerSize = 16
                u64(data, position + 8)
            }
            else -> size32
        }
        if (size < headerSize || size > left) throw Mp4FormatException("bad-child-box", "'$type' at $position has size $size, $left bytes left")
        children += Mp4Child(type, position, headerSize, size.toInt())
        position += size.toInt()
    }
    return children
}

internal fun childBoxes(data: ByteArray, parent: Mp4Child): List<Mp4Child> = childBoxes(data, parent.payload, parent.end)

internal fun findChild(data: ByteArray, parent: Mp4Child, type: String): Mp4Child? =
    childBoxes(data, parent).firstOrNull { it.type == type }

/** Follows [types] down from [parent], one container level each. */
internal fun findPath(data: ByteArray, parent: Mp4Child, vararg types: String): Mp4Child? {
    var current = parent
    for (type in types) current = findChild(data, current, type) ?: return null
    return current
}

/** Reads a top-level box whole. */
internal fun readBoxBytes(file: Mp4Bytes, box: Mp4Box): ByteArray {
    if (box.size > MAX_MOOV_BYTES) throw Mp4FormatException("moov-too-large", "'${box.type}' is ${box.size} bytes")
    val bytes = ByteArray(box.size.toInt())
    file.readFully(box.offset, ByteBuffer.wrap(bytes))
    return bytes
}

/** The single `moov` among [boxes]. */
internal fun findSingleMoov(boxes: List<Mp4Box>): Mp4Box {
    val moovs = boxes.filter { it.type == "moov" }
    if (moovs.isEmpty()) throw Mp4FormatException("no-moov")
    if (moovs.size > 1) throw Mp4FormatException("several-moov", "${moovs.size} moov boxes")
    return moovs[0]
}

/** What the editor reads from `mvhd`. Offsets index [Mp4Movie.bytes]. */
internal class Mp4MovieHeader(
    val child: Mp4Child,
    val version: Int,
    val creationTime: Long,
    val modificationTime: Long,
    val timescale: Long,
    val duration: Long,
    val nextTrackId: Long,
    val durationOffset: Int,
    val nextTrackIdOffset: Int,
)

/** What the editor reads from one `trak`. */
internal class Mp4TrackSummary(
    val trak: Mp4Child,
    /** `mdia/hdlr` handler type, or null when the track has none. */
    val handlerType: String?,
    val trackId: Long,
    /** `tkhd` duration, movie timescale. */
    val duration: Long,
)

/** A loaded `moov`: its bytes (header included), its direct children in order, and the parts the editor needs. */
internal class Mp4Movie(
    val box: Mp4Box,
    val bytes: ByteArray,
    val children: List<Mp4Child>,
    val header: Mp4MovieHeader,
    val tracks: List<Mp4TrackSummary>,
)

internal fun readMovie(file: Mp4Bytes, moov: Mp4Box): Mp4Movie {
    val bytes = readBoxBytes(file, moov)
    val children = childBoxes(bytes, moov.headerSize, bytes.size)
    val mvhd = children.firstOrNull { it.type == "mvhd" } ?: throw Mp4FormatException("no-mvhd")
    val header = parseMovieHeader(bytes, mvhd)
    val tracks = children.filter { it.type == "trak" }.map { parseTrackSummary(bytes, it) }
    return Mp4Movie(moov, bytes, children, header, tracks)
}

private fun parseMovieHeader(data: ByteArray, mvhd: Mp4Child): Mp4MovieHeader {
    mvhd.requirePayload(4)
    val p = mvhd.payload
    val header = when (val version = u8(data, p)) {
        0 -> {
            mvhd.requirePayload(100)
            Mp4MovieHeader(mvhd, version, u32(data, p + 4), u32(data, p + 8), u32(data, p + 12), u32(data, p + 16), u32(data, p + 96), p + 16, p + 96)
        }
        1 -> {
            mvhd.requirePayload(112)
            Mp4MovieHeader(mvhd, version, u64(data, p + 4), u64(data, p + 12), u32(data, p + 20), u64(data, p + 24), u32(data, p + 108), p + 24, p + 108)
        }
        else -> throw Mp4FormatException("mvhd-version", "mvhd version $version")
    }
    if (header.timescale <= 0) throw Mp4FormatException("bad-timescale", "mvhd timescale 0")
    return header
}

private fun parseTrackSummary(data: ByteArray, trak: Mp4Child): Mp4TrackSummary {
    val tkhd = findChild(data, trak, "tkhd") ?: throw Mp4FormatException("no-tkhd", "a trak at ${trak.offset} has no tkhd")
    tkhd.requirePayload(4)
    val p = tkhd.payload
    val trackId: Long
    val duration: Long
    when (val version = u8(data, p)) {
        0 -> {
            tkhd.requirePayload(24)
            trackId = u32(data, p + 12)
            duration = u32(data, p + 20)
        }
        1 -> {
            tkhd.requirePayload(36)
            trackId = u32(data, p + 20)
            duration = u64(data, p + 28)
        }
        else -> throw Mp4FormatException("tkhd-version", "tkhd version $version")
    }
    val hdlr = findPath(data, trak, "mdia", "hdlr")
    val handler = hdlr?.let {
        it.requirePayload(12)
        fourcc(data, it.payload + 8)
    }
    return Mp4TrackSummary(trak, handler, trackId, duration)
}

/** `mdhd` timescale. */
internal fun parseMediaTimescale(data: ByteArray, mdhd: Mp4Child): Long {
    mdhd.requirePayload(4)
    val p = mdhd.payload
    val timescale = when (val version = u8(data, p)) {
        0 -> {
            mdhd.requirePayload(24)
            u32(data, p + 12)
        }
        1 -> {
            mdhd.requirePayload(36)
            u32(data, p + 20)
        }
        else -> throw Mp4FormatException("mdhd-version", "mdhd version $version")
    }
    if (timescale <= 0) throw Mp4FormatException("bad-timescale", "mdhd timescale 0")
    return timescale
}

/** [value] from timescale [from] to timescale [to], rounded half up. */
internal fun rescale(value: Long, from: Long, to: Long): Long =
    BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(to)).divide(BigDecimal.valueOf(from), 0, RoundingMode.HALF_UP).toLong()

internal fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

internal fun u16(data: ByteArray, offset: Int): Int = (u8(data, offset) shl 8) or u8(data, offset + 1)

internal fun s32(data: ByteArray, offset: Int): Int =
    (u8(data, offset) shl 24) or (u8(data, offset + 1) shl 16) or (u8(data, offset + 2) shl 8) or u8(data, offset + 3)

internal fun u32(data: ByteArray, offset: Int): Long = s32(data, offset).toLong() and U32_MAX

/** Eight bytes as a signed long; 64-bit fields above 2^63 come back negative. */
internal fun u64(data: ByteArray, offset: Int): Long = (u32(data, offset) shl 32) or u32(data, offset + 4)

internal fun fourcc(data: ByteArray, offset: Int): String = String(data, offset, 4, StandardCharsets.ISO_8859_1)

/** Builds boxes into a growable byte array; [box] patches the size when its body returns. */
internal class Mp4BoxWriter(initialCapacity: Int = 1024) {
    private var data = ByteArray(initialCapacity.coerceAtLeast(16))

    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }

    fun u8(value: Int) {
        ensure(1)
        data[size++] = value.toByte()
    }

    fun u16(value: Int) {
        ensure(2)
        data[size++] = (value ushr 8).toByte()
        data[size++] = value.toByte()
    }

    fun u24(value: Int) {
        ensure(3)
        data[size++] = (value ushr 16).toByte()
        data[size++] = (value ushr 8).toByte()
        data[size++] = value.toByte()
    }

    fun u32(value: Long) {
        ensure(4)
        for (shift in 24 downTo 0 step 8) data[size++] = (value ushr shift).toByte()
    }

    fun u32(value: Int) = u32(value.toLong() and U32_MAX)

    fun u64(value: Long) {
        ensure(8)
        for (shift in 56 downTo 0 step 8) data[size++] = (value ushr shift).toByte()
    }

    fun bytes(source: ByteArray, offset: Int = 0, length: Int = source.size) {
        ensure(length)
        System.arraycopy(source, offset, data, size, length)
        size += length
    }

    fun fourcc(type: String) {
        require(type.length == 4) { "A box type has four characters: '$type'" }
        for (c in type) u8(c.code)
    }

    fun box(type: String, body: Mp4BoxWriter.() -> Unit) {
        val start = size
        u32(0)
        fourcc(type)
        this.body()
        val length = size - start
        data[start] = (length ushr 24).toByte()
        data[start + 1] = (length ushr 16).toByte()
        data[start + 2] = (length ushr 8).toByte()
        data[start + 3] = length.toByte()
    }

    fun fullBox(type: String, version: Int, flags: Int, body: Mp4BoxWriter.() -> Unit) = box(type) {
        u8(version)
        u24(flags)
        this.body()
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}
