/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.transfers.CapturePublicationReceipt
import com.librestatic.opencinecam.transfers.CapturePublicationState
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*

/** Container/publication and real platform decoder assertions only. Neither physical A/V
 * synchronization nor microphone quality follows from these checks. No rows or files are changed. */
internal object ServiceAudioMatrixAssertions {
    fun assertPublished(context: Context, publication: CapturePublicationReceipt,
        format: AudioOutputFormat, silent: Boolean): String {
        assertEquals(CapturePublicationState.COMMITTED, publication.state)
        val expectedRoles = if (silent || format == AudioOutputFormat.AAC_MP4) {
            setOf(CaptureArtifactRole.VIDEO, CaptureArtifactRole.VIDEO_METADATA)
        } else CaptureArtifactRole.entries.toSet()
        assertEquals(expectedRoles, publication.artifacts.map { it.role }.toSet())
        assertEquals(expectedRoles.size, publication.artifacts.size)
        assertEquals(publication.artifacts.size, publication.artifacts.map { it.uri }.toSet().size)
        publication.artifacts.forEach { artifact ->
            requireNotNull(context.contentResolver.query(Uri.parse(artifact.uri),
                arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)).use { row ->
                assertEquals(1, row.count); assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0)); assertEquals(artifact.displayName, row.getString(1))
            }
            requireNotNull(context.contentResolver.openFileDescriptor(Uri.parse(artifact.uri), "r")).use {
                assertTrue("Published ${artifact.role} must have bytes", it.statSize > 0)
            }
        }
        val video = Uri.parse(publication.artifacts.single { it.role == CaptureArtifactRole.VIDEO }.uri)
        val videoTracks = tracks(context, video)
        val videoMime = videoTracks.single { it.startsWith("video/") }
        if (silent) {
            assertEquals(listOf(videoMime), videoTracks)
            return "requested=${format.name} silent=true videoMime=$videoMime audioMime=none decodedFrames=0 roles=${roles(expectedRoles)}"
        }
        val audioUri: Uri
        val expectedMime: String
        if (format == AudioOutputFormat.AAC_MP4) {
            expectedMime = MediaFormat.MIMETYPE_AUDIO_AAC
            assertEquals(2, videoTracks.size)
            assertEquals(setOf(videoMime, expectedMime), videoTracks.toSet())
            audioUri = video
        } else {
            assertEquals(listOf(videoMime), videoTracks)
            audioUri = Uri.parse(publication.artifacts.single { it.role == CaptureArtifactRole.AUDIO }.uri)
            expectedMime = if (format == AudioOutputFormat.WAV_PCM) MediaFormat.MIMETYPE_AUDIO_RAW else MediaFormat.MIMETYPE_AUDIO_FLAC
            assertEquals(listOf(expectedMime), tracks(context, audioUri))
        }
        val audio = if (format == AudioOutputFormat.WAV_PCM) validateWav(context, audioUri)
            else decode(context, audioUri, expectedMime)
        return "requested=${format.name} silent=false videoMime=$videoMime audioMime=$expectedMime " +
            "pcmMime=audio/raw sampleRateHz=${audio.rate} channels=${audio.channels} pcmBits=${audio.bits} pcmEncoding=${audio.encoding} " +
            "decodedFrames=${if (format == AudioOutputFormat.WAV_PCM) 0 else audio.frames} " +
            "validatedPcmFrames=${audio.frames} outputEos=${audio.eos} roles=${roles(expectedRoles)}"
    }

    private fun roles(value: Set<CaptureArtifactRole>) = value.sortedBy { it.ordinal }.joinToString(",") { it.name }
    private fun tracks(context: Context, uri: Uri): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).map { requireNotNull(extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)) }
        } finally { extractor.release() }
    }
    private data class Pcm(val rate: Int, val channels: Int, val bits: Int, val frames: Long, val eos: Boolean, val encoding: Int)

    private fun decode(context: Context, uri: Uri, mime: String): Pcm {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        fun checkDeadline() { check(SystemClock.elapsedRealtime() < deadline) { "Platform audio decoder deadline exceeded: $mime" } }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime }
            val inputFormat = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start(); started = true
            var inputEos = false
            var outputEos = false
            var frames = 0L
            var actual: Pcm? = null
            val info = MediaCodec.BufferInfo()
            while (!outputEos) {
                checkDeadline()
                if (!inputEos) {
                    val index = decoder.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val input = requireNotNull(decoder.getInputBuffer(index)).apply { clear() }
                        val count = extractor.readSampleData(input, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            assertEquals("Unencrypted local audio input required", 0, extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED)
                            assertTrue(count > 0 && count <= input.capacity())
                            val pts = extractor.sampleTime
                            assertTrue(pts >= 0)
                            decoder.queueInputBuffer(index, 0, count, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val layout = pcmLayout(decoder.outputFormat)
                        actual?.let { assertEquals(it.copy(frames = 0, eos = false), layout) }
                        actual = layout
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val layout = pcmLayout(decoder.outputFormat)
                                actual?.let { assertEquals(it.copy(frames = 0, eos = false), layout) }
                                actual = layout
                                val output = requireNotNull(decoder.getOutputBuffer(index))
                                assertTrue(info.offset >= 0 && info.size <= output.capacity() - info.offset)
                                val frameBytes = layout.channels * (layout.bits / 8)
                                assertEquals("Decoded PCM must contain complete channel frames", 0, info.size % frameBytes)
                                // Read actual decoded output, without retaining an unbounded PCM copy.
                                val readable = output.duplicate().apply { position(info.offset); limit(info.offset + info.size) }
                                while (readable.hasRemaining()) readable.get()
                                frames = Math.addExact(frames, (info.size / frameBytes).toLong())
                            }
                            outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { decoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            checkDeadline()
            assertTrue("Decoder consumes explicit input EOS", inputEos)
            assertTrue("Decoder must produce PCM samples and output EOS", outputEos && frames > 0)
            val result = requireNotNull(actual)
            assertEquals(inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), result.rate)
            assertEquals(inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT), result.channels)
            return result.copy(frames = frames, eos = true)
        } finally {
            // The deadline rejects late evidence; it never substitutes for actual native cleanup.
            try { codec?.let { try { if (started) it.stop() } finally { it.release() } } }
            finally { extractor.release() }
        }
    }

    private fun pcmLayout(format: MediaFormat): Pcm {
        assertEquals(MediaFormat.MIMETYPE_AUDIO_RAW, format.getString(MediaFormat.KEY_MIME))
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        assertTrue(rate > 0 && channels in 1..8)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            else AudioFormat.ENCODING_PCM_16BIT
        val bits = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> error("Unexpected platform PCM encoding: $encoding")
        }
        return Pcm(rate, channels, bits, 0, false, encoding)
    }

    private fun validateWav(context: Context, uri: Uri): Pcm {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        val header = ByteArray(44)
        requireNotNull(context.contentResolver.openInputStream(uri)).use { DataInputStream(it).readFully(header) }
        fun tag(at: Int) = String(header, at, 4, Charsets.US_ASCII)
        fun u16(at: Int) = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(at).toInt() and 0xffff
        fun u32(at: Int) = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(at).toLong() and 0xffff_ffffL
        // The production sidecar writer emits this fixed RIFF/fmt/data layout, not an arbitrary WAV importer.
        assertEquals("RIFF", tag(0)); assertEquals("WAVE", tag(8)); assertEquals("fmt ", tag(12)); assertEquals("data", tag(36))
        assertEquals(16L, u32(16))
        val encoding = u16(20)
        val channels = u16(22)
        val rate = u32(24)
        val blockAlign = u16(32)
        val bits = u16(34)
        assertTrue(encoding == 1 || encoding == 3)
        assertTrue(channels in 1..2 && rate in 1..Int.MAX_VALUE.toLong())
        assertTrue(bits in setOf(16, 24, 32)); if (encoding == 3) assertEquals(32, bits)
        assertEquals(channels * bits / 8, blockAlign)
        assertEquals(rate * blockAlign, u32(28))
        val dataBytes = u32(40)
        assertTrue(dataBytes > 0); assertEquals(0L, dataBytes % blockAlign)
        assertEquals(dataBytes + 36, u32(4))
        requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")).use { assertEquals(dataBytes + 44, it.statSize) }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_RAW, format.getString(MediaFormat.KEY_MIME))
            assertEquals(rate.toInt(), format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(channels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(1024 * 1024)
            var bytes = 0L
            while (true) {
                check(SystemClock.elapsedRealtime() < deadline) { "WAV sample validation deadline exceeded" }
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) break
                assertTrue(count > 0 && count <= buffer.capacity())
                assertEquals("WAV packets must contain complete PCM frames", 0, count % blockAlign)
                bytes = Math.addExact(bytes, count.toLong())
                assertTrue(bytes <= dataBytes)
                extractor.advance()
            }
            assertEquals(dataBytes, bytes)
            val pcmEncoding = if (encoding == 3) AudioFormat.ENCODING_PCM_FLOAT else when (bits) {
                16 -> AudioFormat.ENCODING_PCM_16BIT
                24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                else -> AudioFormat.ENCODING_PCM_32BIT
            }
            return Pcm(rate.toInt(), channels, bits, bytes / blockAlign, true, pcmEncoding)
        } finally { extractor.release() }
    }
}
