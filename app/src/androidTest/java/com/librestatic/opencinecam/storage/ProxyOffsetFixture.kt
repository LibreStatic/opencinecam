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

/** Fixture-only v0 MP4 edit insertion; consumes free space, preserving every media byte/offset. */
private fun prependFixtureEmptyEdit(file: File, offsetUs: Long) {
    val data = file.readBytes()
    val view = ByteBuffer.wrap(data)
    fun children(start: Int, end: Int): List<Int> {
        val result = mutableListOf<Int>(); var position = start
        while (position + 8 <= end) {
            val size = view.getInt(position)
            if (size == 1) break // mdat has an extended size and is never edited.
            require(size >= 8 && position + size <= end)
            result += position; position += size
        }
        return result
    }
    fun type(at: Int) = String(data, at + 4, 4, Charsets.US_ASCII)
    fun child(at: Int, name: String) = children(at + 8, at + view.getInt(at)).single { type(it) == name }
    val top = children(0, data.size)
    val moov = top.single { type(it) == "moov" }
    val free = top.single { type(it) == "free" }
    require(free == moov + view.getInt(moov) && view.getInt(free) >= 20)
    val trak = child(moov, "trak"); val edts = child(trak, "edts"); val elst = child(edts, "elst")
    val mvhd = child(moov, "mvhd"); val tkhd = child(trak, "tkhd")
    require(data[elst + 8] == 0.toByte() && view.getInt(elst + 12) == 1)
    require(data[mvhd + 8] == 0.toByte() && data[tkhd + 8] == 0.toByte())
    val ticks = Math.multiplyExact(offsetUs, view.getInt(mvhd + 20).toLong())
    require(ticks % 1_000_000 == 0L)
    val gap = Math.toIntExact(ticks / 1_000_000)
    for (atom in listOf(moov, trak, edts, elst)) view.putInt(atom, view.getInt(atom) + 12)
    view.putInt(elst + 12, 2)
    for (duration in listOf(mvhd + 24, tkhd + 28)) view.putInt(duration, Math.addExact(view.getInt(duration), gap))
    val insert = elst + 16
    val result = ByteArray(data.size)
    data.copyInto(result, 0, 0, insert)
    ByteBuffer.wrap(result).apply { position(insert) }.putInt(gap).putInt(-1).putShort(1).putShort(0)
    data.copyInto(result, insert + 12, insert, free)
    ByteBuffer.wrap(result).putInt(free + 12, view.getInt(free) - 12)
    data.copyInto(result, free + 16, free + 4, free + 8)
    data.copyInto(result, free + 20, free + 20, data.size)
    file.writeBytes(result)
}
