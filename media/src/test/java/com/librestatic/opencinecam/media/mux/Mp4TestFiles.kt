/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/** A run of a run-length table: [count] samples with [value] (a delta or a composition offset). */
internal data class Run(val count: Int, val value: Long)

/** One `elst` entry: [segmentDuration] in the movie timescale, [mediaTime] in the media's (−1 for an empty edit). */
internal data class TestEdit(val segmentDuration: Long, val mediaTime: Long)

/** The shape of a synthetic video-only MP4. */
internal class TestMp4Spec(
    val stts: List<Run> = listOf(Run(30, 40)),
    val ctts: List<Run>? = null,
    val cttsVersion: Int = 0,
    val edits: List<TestEdit>? = null,
    val elstVersion: Int = 0,
    val mediaTimescale: Long = 1_000,
    val mdhdVersion: Int = 0,
    val movieTimescale: Long = 1_000,
    val mvhdVersion: Int = 0,
    val videoTrackId: Long = 1,
    val nextTrackId: Long = 2,
    /** `tkhd` duration; by default the media duration in the movie timescale. */
    val tkhdDuration: Long? = null,
    /** `mvhd` duration; by default the `tkhd` duration. */
    val mvhdDuration: Long? = null,
    val moovFirst: Boolean = false,
    val largeMdatHeader: Boolean = false,
    val sampleBytes: Int = 64,
    /** Handler types of extra minimal tracks after the video one, e.g. "soun". */
    val extraTraks: List<String> = emptyList(),
    val omitMoov: Boolean = false,
    val secondMoov: Boolean = false,
    /** Ends the file with a `free` box of size 0. */
    val openEndedLast: Boolean = false,
    val trailing: ByteArray = ByteArray(0),
)

internal object TestMp4 {
    const val CREATION_TIME = 3_000_000_000L
    const val MODIFICATION_TIME = 3_000_000_100L

    fun ftyp(): ByteArray = Mp4BoxWriter().apply {
        box("ftyp") {
            fourcc("isom")
            u32(0x200)
            fourcc("isom")
            fourcc("mp42")
        }
    }.toByteArray()

    fun sampleCount(spec: TestMp4Spec): Int = spec.stts.sumOf { it.count }

    fun videoPayload(spec: TestMp4Spec): ByteArray = ByteArray(sampleCount(spec) * spec.sampleBytes) { ((it * 31 + 7) % 251).toByte() }

    fun mdatHeader(spec: TestMp4Spec, payloadSize: Long): ByteArray = Mp4BoxWriter().apply {
        if (spec.largeMdatHeader) {
            u32(1)
            fourcc("mdat")
            u64(16 + payloadSize)
        } else {
            u32(8 + payloadSize)
            fourcc("mdat")
        }
    }.toByteArray()

    /** The whole file. */
    fun build(spec: TestMp4Spec): ByteArray {
        val ftyp = ftyp()
        val payload = videoPayload(spec)
        val mdat = mdatHeader(spec, payload.size.toLong()) + payload
        val mdatHeaderSize = mdat.size - payload.size
        val parts = ArrayList<ByteArray>()
        parts += ftyp
        if (spec.omitMoov) {
            parts += mdat
        } else if (spec.moovFirst) {
            val probe = moov(spec, 0)
            val moov = moov(spec, (ftyp.size + probe.size + mdatHeaderSize).toLong())
            parts += moov
            parts += mdat
        } else {
            parts += mdat
            parts += moov(spec, (ftyp.size + mdatHeaderSize).toLong())
        }
        if (spec.secondMoov) parts += moov(spec, (ftyp.size + mdatHeaderSize).toLong())
        if (spec.openEndedLast) parts += byteArrayOf(0, 0, 0, 0, 'f'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 'e'.code.toByte(), 1, 2, 3, 4)
        parts += spec.trailing
        return parts.fold(ByteArray(0)) { all, part -> all + part }
    }

    fun mediaDuration(spec: TestMp4Spec): Long = spec.stts.sumOf { it.count * it.value }

    fun tkhdDuration(spec: TestMp4Spec): Long = spec.tkhdDuration ?: rescale(mediaDuration(spec), spec.mediaTimescale, spec.movieTimescale)

    /** The `moov`, with the video's single chunk at [dataOffset]. */
    fun moov(spec: TestMp4Spec, dataOffset: Long): ByteArray = Mp4BoxWriter().apply {
        val trackDuration = tkhdDuration(spec)
        box("moov") {
            fullBox("mvhd", spec.mvhdVersion, 0) {
                writeTimes(spec.mvhdVersion)
                u32(spec.movieTimescale)
                if (spec.mvhdVersion == 1) u64(spec.mvhdDuration ?: trackDuration) else u32(spec.mvhdDuration ?: trackDuration)
                u32(0x00010000)
                u16(0x0100)
                u16(0)
                u32(0)
                u32(0)
                matrix()
                repeat(6) { u32(0) }
                u32(spec.nextTrackId)
            }
            videoTrak(spec, dataOffset, trackDuration)
            spec.extraTraks.forEachIndexed { index, handler -> minimalTrak(handler, spec.videoTrackId + 1 + index) }
            box("udta") { box("free") { u32(0x12345678) } }
        }
    }.toByteArray()

    private fun Mp4BoxWriter.writeTimes(version: Int) {
        if (version == 1) {
            u64(CREATION_TIME)
            u64(MODIFICATION_TIME)
        } else {
            u32(CREATION_TIME)
            u32(MODIFICATION_TIME)
        }
    }

    private fun Mp4BoxWriter.matrix() {
        for (value in intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)) u32(value)
    }

    private fun Mp4BoxWriter.tkhd(trackId: Long, duration: Long) {
        fullBox("tkhd", 0, 3) {
            writeTimes(0)
            u32(trackId)
            u32(0)
            u32(duration)
            u32(0)
            u32(0)
            u16(0)
            u16(0)
            u16(0)
            u16(0)
            matrix()
            u32(1920 shl 16)
            u32(1080 shl 16)
        }
    }

    private fun Mp4BoxWriter.hdlr(handler: String) {
        fullBox("hdlr", 0, 0) {
            u32(0)
            fourcc(handler)
            u32(0)
            u32(0)
            u32(0)
            bytes("TestHandler\u0000".toByteArray(Charsets.US_ASCII))
        }
    }

    private fun Mp4BoxWriter.mdhd(version: Int, timescale: Long, duration: Long) {
        fullBox("mdhd", version, 0) {
            writeTimes(version)
            u32(timescale)
            if (version == 1) u64(duration) else u32(duration)
            u16(0x55C4)
            u16(0)
        }
    }

    private fun Mp4BoxWriter.videoTrak(spec: TestMp4Spec, dataOffset: Long, trackDuration: Long) {
        val samples = sampleCount(spec)
        box("trak") {
            tkhd(spec.videoTrackId, trackDuration)
            spec.edits?.let { edits ->
                box("edts") {
                    fullBox("elst", spec.elstVersion, 0) {
                        u32(edits.size)
                        for (edit in edits) {
                            if (spec.elstVersion == 1) {
                                u64(edit.segmentDuration)
                                u64(edit.mediaTime)
                            } else {
                                u32(edit.segmentDuration)
                                u32(edit.mediaTime.toInt())
                            }
                            u16(1)
                            u16(0)
                        }
                    }
                }
            }
            box("mdia") {
                mdhd(spec.mdhdVersion, spec.mediaTimescale, mediaDuration(spec))
                hdlr("vide")
                box("minf") {
                    fullBox("vmhd", 0, 1) {
                        u16(0)
                        u16(0)
                        u16(0)
                        u16(0)
                    }
                    box("dinf") {
                        fullBox("dref", 0, 0) {
                            u32(1)
                            fullBox("url ", 0, 1) {}
                        }
                    }
                    box("stbl") {
                        fullBox("stsd", 0, 0) {
                            u32(1)
                            box("avc1") { bytes(ByteArray(78) { it.toByte() }) }
                        }
                        fullBox("stts", 0, 0) {
                            u32(spec.stts.size)
                            for (run in spec.stts) {
                                u32(run.count)
                                u32(run.value)
                            }
                        }
                        spec.ctts?.let { runs ->
                            fullBox("ctts", spec.cttsVersion, 0) {
                                u32(runs.size)
                                for (run in runs) {
                                    u32(run.count)
                                    u32(run.value.toInt())
                                }
                            }
                        }
                        fullBox("stsc", 0, 0) {
                            u32(1)
                            u32(1)
                            u32(samples)
                            u32(1)
                        }
                        fullBox("stsz", 0, 0) {
                            u32(spec.sampleBytes)
                            u32(samples)
                        }
                        fullBox("stco", 0, 0) {
                            u32(1)
                            u32(dataOffset)
                        }
                    }
                }
            }
        }
    }

    private fun Mp4BoxWriter.minimalTrak(handler: String, trackId: Long) {
        box("trak") {
            tkhd(trackId, 0)
            box("mdia") {
                mdhd(0, 48_000, 0)
                hdlr(handler)
            }
        }
    }
}

/** Opens [file] for the duration of [block]. */
internal fun <T> withMp4(file: File, write: Boolean = false, block: (Mp4Bytes) -> T): T =
    RandomAccessFile(file, if (write) "rw" else "r").use { block(FileChannelMp4Bytes(it.channel)) }

internal fun sha256(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String =
    MessageDigest.getInstance("SHA-256").apply { update(bytes, from, to - from) }.digest().joinToString("") { "%02x".format(it) }

/** An in-memory, sparse [Mp4Bytes]: unwritten ranges read as zeros, so a file can claim gigabytes it never stores. */
internal class SparseMp4Bytes : Mp4Bytes {
    private val writes = ArrayList<Pair<Long, ByteArray>>()
    private var length = 0L

    override fun size(): Long = length

    override fun readFully(position: Long, buffer: ByteBuffer) {
        val count = buffer.remaining()
        if (position + count > length) throw EOFException("Read past $length")
        val out = ByteArray(count)
        for ((start, data) in writes) {
            val from = maxOf(start, position)
            val to = minOf(start + data.size, position + count)
            if (from < to) System.arraycopy(data, (from - start).toInt(), out, (from - position).toInt(), (to - from).toInt())
        }
        buffer.put(out)
    }

    override fun writeFully(position: Long, buffer: ByteBuffer) {
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        writes += position to data
        length = maxOf(length, position + data.size)
    }

    override fun sync() {}

    fun read(position: Long, count: Int): ByteArray {
        val out = ByteBuffer.allocate(count)
        readFully(position, out)
        return out.array()
    }
}

/**
 * Passes through to [inner], but throws on the writes keyed in [failedWrites] (1-based) after writing only as many of their first
 * bytes as the value says, and on the [failOnSync]th sync (1-based, 0 for none).
 */
internal class FailingMp4Bytes(
    private val inner: Mp4Bytes,
    private val failedWrites: Map<Int, Int>,
    private val failOnSync: Int = 0,
) : Mp4Bytes {
    constructor(inner: Mp4Bytes, failOnWrite: Int, partialBytes: Int = 0) : this(inner, mapOf(failOnWrite to partialBytes))

    var writes = 0
        private set
    var syncs = 0
        private set

    override fun size(): Long = inner.size()

    override fun readFully(position: Long, buffer: ByteBuffer) = inner.readFully(position, buffer)

    override fun writeFully(position: Long, buffer: ByteBuffer) {
        writes++
        val partialBytes = failedWrites[writes]
        if (partialBytes != null) {
            val count = minOf(partialBytes, buffer.remaining())
            if (count > 0) inner.writeFully(position, buffer.duplicate().apply { limit(position() + count) })
            throw IOException("Injected failure on write $writes after $count bytes")
        }
        inner.writeFully(position, buffer)
    }

    override fun sync() {
        syncs++
        if (syncs == failOnSync) throw IOException("Injected failure on sync $syncs")
        inner.sync()
    }
}
