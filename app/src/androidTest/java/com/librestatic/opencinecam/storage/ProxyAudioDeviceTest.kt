/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportResult
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.AacCalibrationEvidence
import com.librestatic.opencinecam.camera.AacCodecCalibrator
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real AAC codec evidence + immutable GOP video. No microphone, pixel oracle or audio re-encode. */
@androidx.annotation.OptIn(UnstableApi::class)
class ProxyAudioDeviceTest {
    @Test fun realAacPacketsAndAllGopVideoTimestampsSurviveProxyTranscode(): Unit = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val video = createPreciseGopFixture(context)
        val source = File(context.cacheDir, "proxy-audio-source-${UUID.randomUUID()}.mp4")
        val output = File(context.cacheDir, "proxy-audio-candidate-${UUID.randomUUID()}.mp4")
        val videoHash = hash(video)
        val previousWorkers = workers()
        try {
            val config = AacCodecCalibrator.selectConfig(48_000, 1, 96_000, 16_384)
            val evidence = mutableListOf<AacCalibrationEvidence>()
            val qualification = AacCodecCalibrator.qualify(config, onEvidence = { evidence += it })
            assertEquals(config, qualification.config)
            assertTrue(qualification.minimumCorrelation >= 0.98)
            assertEquals(2, evidence.size)
            val audio = evidence.single { it.sourceFrames == 25_013 }
            assertTrue(audio.packets.size in 2..256)
            assertTrue(audio.codecSpecificData.isNotEmpty())
            // Fixture clock: AAC-LC packets contain1024 samples. Integer-microsecond encoder
            // timestamps may differ by1us after origin subtraction; MediaMuxer stores48kHz ticks.
            // Qualify that representation, then write its explicit sample grid before export.
            val origin = audio.packets.first().ptsUs
            val rawAudioPts = audio.packets.map { Math.subtractExact(it.ptsUs, origin) }
            val audioPts = audio.packets.indices.map { it.toLong() * 1024L * 1_000_000L / 48_000L }
            rawAudioPts.zip(audioPts).forEachIndexed { index, (raw, grid) ->
                assertTrue("AAC packet$index encoder-origin PTS$raw differs from1024-sample grid$grid",
                    raw in (grid - 1)..(grid + 1))
            }
            assertTrue(rawAudioPts.zipWithNext().all { (a, b) -> b > a })
            Log.i("E17ProxyAudioProbe", "originalAacOriginUs=$origin rawNormalizedPtsUs=$rawAudioPts fixtureSampleGridPtsUs=$audioPts encoderGridDeltaAtMost1us=true")
            assertEquals(0L, audioPts.first())
            assertTrue(audioPts.all { it >= 0 })
            assertTrue(audioPts.zipWithNext().all { (a, b) -> b > a })
            val inputVideo = probeProxyMedia(context, Uri.fromFile(video))
            assertEquals(3_400_000L, inputVideo.video.durationUs)
            mux(video, source, audio, audioPts, inputVideo.video.durationUs)
            val measured = probeProxyMedia(context, Uri.fromFile(source))
            assertEquals(preciseGopPacketPtsUs, measured.video.timestampsUs)
            assertEquals(preciseGopPtsUs, measured.video.timestampsUs.sorted())
            assertEquals(inputVideo.video.durationUs, measured.video.durationUs)
            assertEquals(inputVideo.video.packetDigest, measured.video.packetDigest)
            assertEquals(inputVideo.video.codecData, measured.video.codecData)
            val measuredAudio = requireNotNull(measured.audio)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, measuredAudio.mime)
            assertEquals(48_000, measuredAudio.sampleRate); assertEquals(1, measuredAudio.channels)
            assertEquals(audioPts, measuredAudio.timestampsUs)
            assertEquals(listOf(digest(audio.codecSpecificData)), measuredAudio.codecData)
            val packetHash = MessageDigest.getInstance("SHA-256")
            audio.packets.forEach { packet ->
                packetHash.update(ByteBuffer.allocate(4).putInt(packet.data.size).array())
                packetHash.update(packet.data)
            }
            assertEquals(packetHash.digest().proxyHex(), measuredAudio.packetDigest)
            assertTrue(measuredAudio.durationUs > audioPts.last())
            assertTrue("Short real AAC evidence ends before the complete video", measuredAudio.durationUs < measured.video.durationUs)
            val sourceHash = hash(source)
            val report = withTimeout(90_000) {
                ProxyTranscoder(context).transcode(Uri.fromFile(source), output, 96, 64, 1_000_000)
            }
            val actual = probeProxyMedia(context, Uri.fromFile(output))
            Log.i("E17ProxyAudioProbe", "originalAacOriginUs=$origin fixtureSampleGridPtsUs=$audioPts sourceAudio=$measuredAudio outputAudio=${actual.audio} report=$report")
            assertEquals(ExportResult.CONVERSION_PROCESS_TRANSCODED, report.videoConversionProcess)
            assertEquals(ExportResult.CONVERSION_PROCESS_TRANSMUXED, report.audioConversionProcess)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, report.audioMimeType)
            assertNull(report.audioEncoderName)
            assertEquals(12, report.videoFrameCount)
            assertEquals(output.length(), report.fileSizeBytes)
            verifyProxyCorrespondence(measured, actual, VideoDisplayGeometry(96, 64))
            assertEquals("Exact final-frame duration", measured.video.durationUs, actual.video.durationUs)
            assertEquals("Every AAC packet, CSD, PTS, duration and channel/rate preserved", measuredAudio, actual.audio)
            assertEquals(sourceHash, hash(source)); assertEquals(videoHash, hash(video))
            assertTrue("Proxy application looper retired", (workers() - previousWorkers).isEmpty())
            Log.i("E17ProxyAudioProbe", "realAac48000Mono=true packets=${audio.packets.size} allAudioBytesCsdPtsDurationPreserved=true video12PtsAndDurationPreserved=true sourceSha=$sourceHash videoAssetSha=$videoHash noPixelFidelityClaim=true retired=true")
        } finally {
            try { assertTrue("Proxy workers retired before cleanup", (workers() - previousWorkers).isEmpty()) }
            finally {
                val leftovers = listOf(output, source, video).filter { it.exists() && !it.delete() }
                assertTrue("Fixture cleanup failed: $leftovers", leftovers.isEmpty())
            }
        }
    }

    private fun mux(video: File, source: File, audio: AacCalibrationEvidence, audioPts: List<Long>, videoDurationUs: Long) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(video.path)
            assertEquals(1, extractor.trackCount)
            val writer = MediaMuxer(source.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val videoTrack = writer.addTrack(extractor.getTrackFormat(0))
            val audioTrack = writer.addTrack(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                audio.config.sampleRateHz, audio.config.channels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(audio.codecSpecificData))
            })
            writer.start(); started = true
            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(64 * 1024)
            var count = 0
            while (extractor.sampleTime >= 0) {
                assertTrue(++count <= 12)
                assertEquals(0, extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))
                buffer.clear()
                assertTrue(extractor.sampleSize in 1..buffer.capacity().toLong())
                val size = extractor.readSampleData(buffer, 0)
                assertEquals(extractor.sampleSize, size.toLong())
                writer.writeSampleData(videoTrack, buffer, MediaCodec.BufferInfo().apply {
                    set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                })
                if (!extractor.advance()) break
            }
            assertEquals(12, count)
            // Explicit last-sample duration for MediaMuxer; preserve the asset's measured3.4s.
            writer.writeSampleData(videoTrack, ByteBuffer.allocate(0), MediaCodec.BufferInfo().apply {
                set(0, 0, videoDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            })
            audio.packets.forEachIndexed { index, packet ->
                assertTrue(packet.data.isNotEmpty())
                assertEquals(0, packet.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM))
                writer.writeSampleData(audioTrack, ByteBuffer.wrap(packet.data), MediaCodec.BufferInfo().apply {
                    set(0, packet.data.size, audioPts[index], packet.flags)
                })
            }
            writer.stop(); started = false
        } finally {
            try { if (started) muxer?.stop() }
            finally { try { muxer?.release() } finally { extractor.release() } }
        }
    }

    private fun hash(file: File) = digest(file.readBytes())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).proxyHex()
    private fun workers() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("proxy-transcoder-") }.toSet()
}
