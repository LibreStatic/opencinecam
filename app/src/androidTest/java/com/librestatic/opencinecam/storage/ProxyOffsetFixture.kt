/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaCodec
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.nio.ByteBuffer

/** Repackages actual VFR/B-frame packets with a known shared-clock offset; no codec replacement. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun offsetProxyVideoFixture(context: Context, output: File, offsetUs: Long) {
    require(offsetUs > 0 && !output.exists())
    val source = createPreciseGopFixture(context)
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(source.path); extractor.selectTrack(0)
        val format = extractor.getTrackFormat(0)
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val track = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            while (extractor.sampleTime >= 0) {
                buffer.clear(); val size = extractor.readSampleData(buffer, 0)
                check(size > 0); buffer.position(0); buffer.limit(size)
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) C.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(track, buffer, MediaCodec.BufferInfo().apply { set(0, size, extractor.sampleTime + offsetUs, flags) })
                if (!extractor.advance()) break
            }
            muxer.writeSampleData(track, ByteBuffer.allocate(0), MediaCodec.BufferInfo().apply {
                set(0, 0, 3_400_000L + offsetUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            })
            muxer.stop()
            // Platform muxer normalizes a lone track. Encode the shared-clock gap explicitly.
            prependFixtureEmptyEdit(output, offsetUs)
        } finally { muxer.release() }
    } finally { extractor.release(); check(source.delete()) }
}

/** Fixture-only v0 MP4 empty-edit insertion. Media bytes are never rewritten; chunk offsets are patched if mdat moves. */
private fun prependFixtureEmptyEdit(file: File, offsetUs: Long) {
    val data = file.readBytes()
    val v = ByteBuffer.wrap(data)
    fun type(at: Int) = String(data, at + 4, 4, Charsets.US_ASCII)
    fun size(at: Int, end: Int): Int = when (val s = v.getInt(at)) {
        0 -> end - at
        1 -> Math.toIntExact(v.getLong(at + 8))
        else -> s
    }
    fun kids(start: Int, end: Int): List<Int> = buildList {
        var p = start
        while (p + 8 <= end) { val s = size(p, end); require(s >= 8 && p + s <= end); add(p); p += s }
    }
    fun kidsOf(at: Int) = kids(at + 8, at + size(at, data.size))
    fun child(at: Int, name: String) = kidsOf(at).single { type(it) == name }
    fun childOrNull(at: Int, name: String) = kidsOf(at).singleOrNull { type(it) == name }

    val top = kids(0, data.size)
    val moov = top.single { type(it) == "moov" }
    val mdat = top.single { type(it) == "mdat" }
    val moovEnd = moov + size(moov, data.size)
    val mvhd = child(moov, "mvhd")
    val trak = child(moov, "trak")
    val tkhd = child(trak, "tkhd")
    require(data[mvhd + 8] == 0.toByte() && data[tkhd + 8] == 0.toByte())
    val ticks = Math.multiplyExact(offsetUs, v.getInt(mvhd + 20).toLong())
    require(ticks % 1_000_000 == 0L)
    val gap = Math.toIntExact(ticks / 1_000_000)

    // 1. Build the insertion (read tkhd duration before it is patched).
    val edts = childOrNull(trak, "edts")
    val insertAt: Int
    val insert: ByteArray
    val grow: List<Int>
    if (edts != null) {
        val elst = child(edts, "elst")
        require(data[elst + 8] == 0.toByte())
        v.putInt(elst + 12, v.getInt(elst + 12) + 1)
        insertAt = elst + 16
        insert = ByteBuffer.allocate(12).putInt(gap).putInt(-1).putInt(0x0001_0000).array()
        grow = listOf(moov, trak, edts, elst)
    } else {
        // No edit list means identity presentation; keep that as the second, normal edit.
        insertAt = tkhd + size(tkhd, data.size)
        insert = ByteBuffer.allocate(48)
            .putInt(48).put("edts".toByteArray(Charsets.US_ASCII))
            .putInt(40).put("elst".toByteArray(Charsets.US_ASCII)).putInt(0).putInt(2)
            .putInt(gap).putInt(-1).putInt(0x0001_0000)
            .putInt(v.getInt(tkhd + 28)).putInt(0).putInt(0x0001_0000)
            .array()
        grow = listOf(moov, trak)
    }
    val delta = insert.size

    // 2. Decide where the extra bytes come from.
    val free = top.firstOrNull { it == moovEnd && type(it) == "free" && size(it, data.size) >= delta + 8 }
    if (free == null && mdat > moov) {
        // mdat moves by delta: patch every chunk offset (positions are still the original ones here).
        for (t in kidsOf(moov).filter { type(it) == "trak" }) {
            val stbl = child(child(child(t, "mdia"), "minf"), "stbl")
            childOrNull(stbl, "stco")?.let { b ->
                repeat(v.getInt(b + 12)) { i -> val p = b + 16 + 4 * i; v.putInt(p, Math.addExact(v.getInt(p), delta)) }
            }
            childOrNull(stbl, "co64")?.let { b ->
                repeat(v.getInt(b + 12)) { i -> val p = b + 16 + 8 * i; v.putLong(p, Math.addExact(v.getLong(p), delta.toLong())) }
            }
        }
    } // else: mdat precedes moov (Samsung) or free absorbs delta: no media byte moves.

    // 3. Patch durations and container sizes in place (all before insertAt, so positions are stable).
    for (d in listOf(mvhd + 24, tkhd + 28)) v.putInt(d, Math.addExact(v.getInt(d), gap))
    for (box in grow) v.putInt(box, Math.addExact(v.getInt(box), delta))

    // 4. Splice.
    val out = java.io.ByteArrayOutputStream(data.size + delta)
    out.write(data, 0, insertAt)
    out.write(insert)
    if (free != null) {
        val freeSize = size(free, data.size)
        out.write(data, insertAt, free - insertAt)
        out.write(ByteBuffer.allocate(8).putInt(freeSize - delta).put("free".toByteArray(Charsets.US_ASCII)).array())
        out.write(data, free + 8 + delta, data.size - (free + 8 + delta))
        check(out.size() == data.size)
    } else {
        out.write(data, insertAt, data.size - insertAt)
    }
    file.writeBytes(out.toByteArray())
}
