/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.storage.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual standalone files and published metadata, not synthesized PCM anchors or video alignment. */
class SidecarCaptureTimingTest {
    @Test fun wavMono48000PublishesMeasuredSourceTiming() = exercise(false, 48000, 1)
    @Test fun wavStereo44100PublishesMeasuredSourceTiming() = exercise(false, 44100, 2)
    @Test fun wavFloatStereo44100RetainsWholeFrames() = exercise(false, 44100, 2, AudioBitDepth.PCM_FLOAT)
    @Test fun flacMono48000PublishesMeasuredSourceTiming() = exercise(true, 48000, 1)
    @Test fun flacStereo44100PublishesMeasuredSourceTiming() = exercise(true, 44100, 2)

    private fun exercise(flac: Boolean, rate: Int, channels: Int, depth: AudioBitDepth = AudioBitDepth.PCM_16) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val settings = CameraSettings(audioBitDepth = depth, audioSampleRateHz = rate, audioChannels = channels,
            audioSource = AudioSourceSelection.MIC, automaticGainControlEnabled = false,
            acousticEchoCancelerEnabled = false, noiseSuppressorEnabled = false)
        val stem = "EPOCH_${if (flac) "FLAC" else "WAV"}_${rate}_${channels}_${depth.name}"
        val meter = AtomicInteger()
        val videoName = "${stem}_${System.nanoTime()}.mp4"
        val recorder: AudioSidecarRecorder = if (flac) FlacAudioSidecarRecorder.create(context, videoName, settings) { meter.incrementAndGet() }
            else WavAudioSidecarRecorder.create(context, videoName, settings) { meter.incrementAndGet() }
        val resolver = context.contentResolver
        try {
            recorder.start(); Thread.sleep(1250)
            val result = requireNotNull(recorder.finish(true))
            assertSame(result, recorder.finish(true))
            val timing = requireNotNull(result.captureTiming)
            assertTrue(timing.timestampObservations > 0); assertTrue(timing.epoch!!.timestampBacked)
            assertEquals(rate, timing.sampleRateHz); assertEquals(channels * depth.bits / 8, timing.frameBytes)
            assertEquals(result.frames, timing.capturedFrames); assertEquals(result.frames, timing.writtenFrames)
            assertEquals(result.frames * 1000000000L / rate, timing.durationNs)
            assertEquals(timing.epoch!!.frameZeroNs, timing.firstTimestampNs!! - timing.firstTimestampFrame!! * 1000000000L / rate)
            assertTrue(timing.lastTimestampNs!! >= timing.firstTimestampNs!!)
            assertTrue(timing.lastTimestampFrame!! >= timing.firstTimestampFrame!!)
            assertTrue(result.frames > rate / 2); assertTrue(meter.get() > 0)
            val metadataName = videoName.removeSuffix(".mp4") + ".audio.json"
            val metadataUri = requireNotNull(resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf("_id"), "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(metadataName), null)).use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst())
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0))
            }
            val json = JSONObject(requireNotNull(resolver.openInputStream(metadataUri)).bufferedReader().use { it.readText() })
            val saved = json.getJSONObject("captureTiming")
            assertEquals("AUDIORECORD_BOOTTIME_FIXED_SOURCE_EPOCH_V1", saved.getString("policy"))
            assertEquals(timing.epoch!!.frameZeroNs, saved.getLong("frameZeroNs"))
            assertEquals(result.frames, saved.getLong("writtenFrames"))
            assertFalse(saved.getBoolean("videoAlignmentApplied")); assertFalse(saved.getBoolean("waveformAlignmentVerified"))
            val file = File(context.cacheDir, stem + if (flac) ".flac" else ".wav")
            requireNotNull(resolver.openInputStream(result.uri)).use { input -> file.outputStream().use { input.copyTo(it) } }
            File(context.cacheDir, "$stem.json").writeText(json.put("testMeterUpdates", meter.get()).toString(2))
            android.util.Log.i("SidecarEpochProbe", "$stem frames=${result.frames} sourceNs=${timing.epoch!!.frameZeroNs} observations=${timing.timestampObservations}")
        } finally { recorder.discard() }
    }
}
