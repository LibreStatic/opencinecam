/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.media.mux.Mp4VideoTiming
import com.librestatic.opencinecam.storage.appendWavAsAac
import com.librestatic.opencinecam.storage.createPreciseGopFixture
import com.librestatic.opencinecam.storage.createWavHeader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/** PLAN-074 decision 10: a WAV encoded to AAC and appended in place to a video-only MP4. */
class TakeAudioAppendDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun appendsAtTheVideoStart() = appendAndCheck(offsetUs = 0)

    @Test fun appendsAfterTheVideoStart() = appendAndCheck(offsetUs = 500_000)

    private fun appendAndCheck(offsetUs: Long) {
        val rate = 48_000
        val channels = 2
        val frames = 2L * rate
        val video = writableVideoFixture()
        val wav = File(context.cacheDir, "append-${UUID.randomUUID()}.wav")
        try {
            writeSineWav(wav, rate, channels, frames)
            var seen: Mp4VideoTiming? = null
            val result = ParcelFileDescriptor.open(video, ParcelFileDescriptor.MODE_READ_WRITE).use { videoFd ->
                ParcelFileDescriptor.open(wav, ParcelFileDescriptor.MODE_READ_ONLY).use { wavFd ->
                    appendWavAsAac(videoFd.fileDescriptor, wavFd.fileDescriptor, 192_000, { timing -> seen = timing; offsetUs })
                }
            }
            val timing = requireNotNull(seen)
            assertSame(timing, result.video)
            assertEquals(rate, result.sampleRateHz)
            assertEquals(channels, result.channels)
            assertEquals(frames, result.pcmFrames)
            assertEquals(offsetUs, result.startOffsetUs)
            assertTrue(result.accessUnits * 1024 >= result.primingFrames + frames)
            assertTrue(result.encoderName.isNotBlank())

            val unitUs = 1024L * 1_000_000 / rate
            val expectedUs = minOf(frames * 1_000_000 / rate, timing.durationUs - offsetUs)
            assertTrue("video ${timing.durationUs} µs too short for the offset", expectedUs > 0)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(video.path)
                assertEquals(2, extractor.trackCount)
                val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                assertEquals(1, mimes.count { it == MediaFormat.MIMETYPE_VIDEO_AVC })
                assertEquals(1, mimes.count { it == MediaFormat.MIMETYPE_AUDIO_AAC })
                val audioTrack = mimes.indexOf(MediaFormat.MIMETYPE_AUDIO_AAC)
                val format = extractor.getTrackFormat(audioTrack)
                assertEquals(rate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(channels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                extractor.selectTrack(audioTrack)
                assertTrue("decoded no PCM", decodesPcm(extractor, format))
            } finally {
                extractor.release()
            }
            // The extractor's track duration is the media's, priming and drain included; the edit list is what plays.
            val edits = audioEdits(video.readBytes())
            val empty = edits.filter { it.second == -1L }
            val played = edits.filter { it.second >= 0 }
            assertEquals(1, played.size)
            assertEquals(result.primingFrames.toLong(), played.single().second)
            if (offsetUs > 0) assertEquals(listOf(offsetUs), empty.map { it.first }) else assertTrue(empty.isEmpty())
            val durationUs = played.single().first
            assertTrue("audio $durationUs µs, expected $expectedUs ± $unitUs", abs(durationUs - expectedUs) <= unitUs)
        } finally {
            video.delete()
            wav.delete()
        }
    }

    /** The sound track's edit list as (segment duration in µs, media time in its samples; −1 for an empty edit). */
    private fun audioEdits(data: ByteArray): List<Pair<Long, Long>> {
        val v = ByteBuffer.wrap(data)
        fun type(at: Int) = String(data, at + 4, 4, Charsets.US_ASCII)
        fun size(at: Int, end: Int): Long = when (val s = v.getInt(at).toLong() and 0xffff_ffffL) {
            0L -> (end - at).toLong()
            1L -> v.getLong(at + 8)
            else -> s
        }
        fun kids(start: Int, end: Int): List<Int> = buildList {
            var p = start
            while (p + 8 <= end) {
                val s = size(p, end)
                require(s >= 8 && p + s <= end)
                add(p)
                p = Math.toIntExact(p + s)
            }
        }
        fun kidsOf(at: Int) = kids(at + 8, Math.toIntExact(at + size(at, data.size)))
        fun child(at: Int, name: String) = kidsOf(at).single { type(it) == name }
        val moov = kids(0, data.size).single { type(it) == "moov" }
        val mvhd = child(moov, "mvhd")
        val movieTimescale = (if (data[mvhd + 8].toInt() == 1) v.getInt(mvhd + 28) else v.getInt(mvhd + 20)).toLong()
        // hdlr: header, version and flags, pre_defined, then handler_type.
        val trak = kidsOf(moov).filter { type(it) == "trak" }
            .single { t -> String(data, child(child(t, "mdia"), "hdlr") + 16, 4, Charsets.US_ASCII) == "soun" }
        val elst = child(child(trak, "edts"), "elst")
        val version = data[elst + 8].toInt()
        val count = v.getInt(elst + 12)
        return (0 until count).map { i ->
            if (version == 1) {
                val at = elst + 16 + i * 20
                v.getLong(at) * 1_000_000 / movieTimescale to v.getLong(at + 8)
            } else {
                val at = elst + 16 + i * 12
                (v.getInt(at).toLong() and 0xffff_ffffL) * 1_000_000 / movieTimescale to v.getInt(at + 4).toLong()
            }
        }
    }

    /** The read-only GOP fixture copied to a file the append may edit. */
    private fun writableVideoFixture(): File {
        val source = createPreciseGopFixture(context)
        try {
            val copy = File(context.cacheDir, "append-${UUID.randomUUID()}.mp4")
            copy.writeBytes(source.readBytes())
            return copy
        } finally {
            source.setWritable(true)
            source.delete()
        }
    }

    private fun writeSineWav(file: File, rate: Int, channels: Int, frames: Long) {
        val dataBytes = frames * channels * 2
        file.outputStream().channel.use { out ->
            out.write(createWavHeader(dataBytes, rate, 16, channels, floatingPoint = false))
            val chunk = ByteBuffer.allocate(4096 * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
            var frame = 0L
            while (frame < frames) {
                chunk.clear()
                while (chunk.hasRemaining() && frame < frames) {
                    val left = (sin(2 * PI * 440.0 * frame / rate) * 12_000).roundToInt().toShort()
                    val right = (sin(2 * PI * 660.0 * frame / rate) * 12_000).roundToInt().toShort()
                    chunk.putShort(left)
                    if (channels == 2) chunk.putShort(right)
                    frame++
                }
                chunk.flip()
                while (chunk.hasRemaining()) out.write(chunk)
            }
        }
        assertEquals(44 + dataBytes, file.length())
    }

    /** Decodes the selected track's first access units and answers whether any PCM came out. */
    private fun decodesPcm(extractor: MediaExtractor, format: MediaFormat): Boolean {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var started = false
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var inputEos = false
            var decodedBytes = 0
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!inputEos) {
                    val index = codec.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index))
                        val size = if (fed < 16) extractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                            fed++
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 1000)
                if (index >= 0) {
                    decodedBytes += info.size
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) break
                }
            }
            return decodedBytes > 0
        } finally {
            try { if (started) codec.stop() } finally { codec.release() }
        }
    }
}
