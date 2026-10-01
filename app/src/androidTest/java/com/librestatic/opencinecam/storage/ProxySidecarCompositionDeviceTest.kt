/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real Media3 composition diagnostics. Synthetic capture anchors are not a physical sync claim. */
class ProxySidecarCompositionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun wavPartialGridComposesWithEveryVfrVideoFrame() = compose(false)
    @Test fun flacPartialGridComposesWithEveryVfrVideoFrame() = compose(true)

    @Test fun wavAudioLeadsVideoWithoutRebasingVideoPts() = compose(false, 250_000)
    @Test fun flacAudioLeadsVideoWithoutRebasingVideoPts() = compose(true, 250_000)

    @Test fun wav24BitComposesWithoutRejectingSourceDepth() = compose(false, type = ProxyPcmSampleType.S24_LE)
    @Test fun wavFloatComposesWithExplicitNormalization() = compose(false, type = ProxyPcmSampleType.F32_LE)
    @Test fun flac24BitComposesFromIndependentEncodedAsset() = compose(true, type = ProxyPcmSampleType.S24_LE)

    @Test fun wavAudioTailLongerThanVideoRemainsPresented() = compose(false, frames = 192347)
    @Test fun flacAudioTailLongerThanVideoRemainsPresented() = compose(true, frames = 192347)

    // Rev64: cells outside the Rev63 verified window (rate, channels, wider gaps in both directions).
    @Test fun wav44100HzComposesWithExplicitPresentationWindow() = compose(false, rate = 44_100, frames = 45_089)
    @Test fun flac44100HzComposesWithExplicitPresentationWindow() = compose(true, rate = 44_100, frames = 45_089)
    @Test fun wavMonoComposesWithoutChannelAssumptions() = compose(false, channels = 1)
    @Test fun flacMonoComposesWithoutChannelAssumptions() = compose(true, channels = 1)
    @Test fun wavOneSecondVideoLeadComposesWithDelayedInteriorAudio() = compose(false, audioStartUs = 1_000_000)
    @Test fun wavOneSecondAudioLeadComposesWithExplicitSharedClock() = compose(false, 1_000_000)

    private fun compose(flac: Boolean, videoOffsetUs: Long = 0, type: ProxyPcmSampleType = ProxyPcmSampleType.S16_LE,
        frames: Int = 48347, rate: Int = 48_000, channels: Int = 2, audioStartUs: Long? = null) = runBlocking<Unit> {
        val evidencePrefix = proxyTestEvidencePrefix()
        val video = if (videoOffsetUs == 0L) createPreciseGopFixture(context) else
            File(context.cacheDir, "offset-video-${UUID.randomUUID()}.mp4").also { offsetProxyVideoFixture(context, it, videoOffsetUs) }
        val directory = File(context.cacheDir, "sidecar-composition-${UUID.randomUUID()}").also { check(it.mkdir()) }
        try {
            val signal = if (frames <= 65536) com.librestatic.opencinecam.camera.aacCalibrationSignal(frames, rate, channels)
            else ShortArray(frames * channels) { index ->
                val frame = index / channels; val channel = index % channels
                val t = frame.toDouble() / rate
                (12000 * kotlin.math.sin(2 * Math.PI * ((220 + channel * 127) * t + 230 * t * t))).toInt().toShort()
            }
            val bytes = java.nio.ByteBuffer.allocate(signal.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .apply { signal.forEach { putShort(it) } }.array()
            val audio = File(directory, if (flac) "source.flac" else "source.wav")
            val sourceRaw = writeProxyDepthFixture(audio, bytes, flac, type, rate, channels)
            val videoUri = Uri.fromFile(video)
            val audioUri = Uri.fromFile(audio)
            val beforeVideoHash = proxyHash(context, videoUri)
            val beforeAudioHash = proxyHash(context, audioUri)
            val before = probeProxyMedia(context, videoUri)
            val pcm = probeProxyPcm(context, audioUri)
            if (videoOffsetUs > 0) {
                val diagnostic = File(context.getExternalFilesDir(null), "$evidencePrefix-input-${if (flac) "flac" else "wav"}-$videoOffsetUs-$type.mp4")
                video.copyTo(diagnostic)
                Log.i("E17SidecarComposition", "offsetInput expected=$videoOffsetUs actual=$before path=${diagnostic.absolutePath}")
            }
            assertEquals(videoOffsetUs, before.video.timestampsUs.min())
            // MediaExtractor's KEY_DURATION excludes a leading empty edit on older platforms and includes it on
            // newer ones (API 36 reports 3.4 s + offset). The exact shared-clock end is asserted via videoEnd below.
            assertTrue("Unexpected video duration ${before.video.durationUs}",
                before.video.durationUs == 3_400_000L || before.video.durationUs == 3_400_000L + videoOffsetUs)
            val videoEnd = probeProxyVideoEndUs(context, videoUri)
            assertEquals(3_400_000L + videoOffsetUs, videoEnd)
            val audioStart = audioStartUs ?: if (videoOffsetUs == 0L) 125_000L else 0L
            val durationFloor = frames * 1_000_000L / rate
            val endFloor = audioStart + durationFloor
            val remainder = frames * 1_000_000L % rate
            val endCeiling = endFloor + if (remainder > 0) 1 else 0
            val outputEnd = maxOf(videoEnd, endCeiling)
            val timeline = ProxySidecarTimeline(rate, frames.toLong(), audioStart, before.video.timestampsUs.min(),
                videoEnd, durationFloor, endFloor, remainder, rate, endCeiling, outputEnd,
                audioStart, before.video.timestampsUs.min(), outputEnd - endCeiling, outputEnd - videoEnd)
            val output = File(directory, "proxy.mp4")
            val report = withTimeout(90_000) { ProxyTranscoder(context).transcode(videoUri, output, 96, 64, 1_000_000,
                ProxySidecarExportInput(audioUri, pcm, timeline)) }
            if (videoOffsetUs > 0) {
                output.copyTo(File(context.getExternalFilesDir(null), "$evidencePrefix-output-${if (flac) "flac" else "wav"}-$videoOffsetUs-$type.mp4"))
            }
            val after = probeProxyMedia(context, Uri.fromFile(output))
            Log.i("E17SidecarComposition", "flac=$flac report=$report before=$before after=$after timeline=$timeline")
            assertEquals(before.video.timestampsUs.sorted(), after.video.timestampsUs.sorted())
            assertEquals(before.video.durationUs, after.video.durationUs)
            assertEquals(videoEnd, probeProxyVideoEndUs(context, Uri.fromFile(output)))
            assertEquals("audio/mp4a-latm", after.audio!!.mime)
            assertEquals(rate, after.audio.sampleRate); assertEquals(channels, after.audio.channels)
            assertEquals(beforeVideoHash, proxyHash(context, videoUri))
            assertEquals(beforeAudioHash, proxyHash(context, audioUri))
            val evidence = File(context.getExternalFilesDir(null), "$evidencePrefix-composition-${if (flac) "flac" else "wav"}-$videoOffsetUs-$type-$rate-$channels-$frames-$audioStart")
            check(evidence.mkdir()) { "Refusing to overwrite existing composition evidence" }
            output.copyTo(File(evidence, "proxy.mp4"))
            video.copyTo(File(evidence, "source.mp4"))
            audio.copyTo(File(evidence, audio.name))
            File(evidence, "source-depth.pcm").writeBytes(sourceRaw)
            val normalized = normalizeProxyPcm16(java.nio.ByteBuffer.wrap(sourceRaw), type)
            File(evidence, "source.pcm").writeBytes(ByteArray(normalized.remaining()).also { normalized.get(it) })
            File(evidence, "observations.txt").writeText("$report\n$timeline\n$after\n")
        } finally {
            check(video.delete())
            check(directory.listFiles()!!.all { it.delete() }); check(directory.delete())
        }
    }
}
