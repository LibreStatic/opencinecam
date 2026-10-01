/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */
package com.librestatic.opencinecam

import android.Manifest
import android.content.ContentUris
import android.media.AudioRecord
import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.Camera2PreviewListener
import com.librestatic.opencinecam.camera.Camera2EmbeddedAudioConfig
import java.lang.reflect.Proxy
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.AudioChannelLevel
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.service.PreviewAudioMonitor
import com.librestatic.opencinecam.storage.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AudioRecordingGainDeviceTest {
    @Test fun wav16StereoManualGainPublishesItsPcmReceiptAndMetadata() = record(AudioBitDepth.PCM_16, 2, false)
    @Test fun wav24ManualGainPublishesItsPcmReceiptAndMetadata() = record(AudioBitDepth.PCM_24, 1, false)
    @Test fun wavFloatManualGainPreservesFloatEncodingAndExcludesAgc() = record(AudioBitDepth.PCM_FLOAT, 1, false)
    @Test fun flacStereoManualGainPublishesItsPcmReceiptAndMetadata() = record(AudioBitDepth.PCM_16, 2, true)

    @Test fun unavailablePcm24RejectsBeforePublishingAnyAudioRow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue("This negative fixture requires a device without PCM24 input (this device supports it; run on hardware lacking 24-bit capture)", AudioRecord.getMinBufferSize(
            48000, AudioFormat.CHANNEL_IN_MONO, AudioBitDepth.PCM_24.androidEncoding) <= 0)
        val stem = "E15_UNAVAILABLE_${UUID.randomUUID()}"
        val settings = CameraSettings(audioOutputFormat = AudioOutputFormat.WAV_PCM, audioBitDepth = AudioBitDepth.PCM_24,
            audioSampleRateHz = 48000, audioChannels = 1, audioRecordingGain = DigitalRecordingGain(true, 6))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            WavAudioSidecarRecorder.create(context, "$stem.mp4", settings)
        }
        assertEquals("Unsupported WAV configuration.", failure.message)
        requireNotNull(context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf("_id"),
            "${MediaStore.MediaColumns.DISPLAY_NAME} IN (?, ?)", arrayOf("$stem.wav", "$stem.audio.json"), null)).use {
            assertEquals(0, it.count)
        }
    }

    @Test fun previewManualZeroDbHasActualPcmReceiptAndRetiresItsMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val gain = DigitalRecordingGain(true, 0)
        val levels = CopyOnWriteArrayList<AudioLevelSnapshot>()
        val seen = CountDownLatch(2)
        val failures = CopyOnWriteArrayList<Throwable>()
        val monitor = PreviewAudioMonitor.create(context, CameraSettings(audioBitDepth = AudioBitDepth.PCM_16,
            audioChannels = 2, audioSource = AudioSourceSelection.MIC, audioRecordingGain = gain,
            automaticGainControlEnabled = true), { levels += it; seen.countDown() }, { failures += it })
        fun field(name: String) = PreviewAudioMonitor::class.java.getDeclaredField(name).apply { isAccessible = true }.get(monitor)
        try {
            monitor.start()
            assertTrue(seen.await(15, TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
            assertTrue(levels.all { it.appliedRecordingGain == gain && it.channels.size == 2 })
        } finally { monitor.closeAsync().get(15, TimeUnit.SECONDS) }
        val lifecycle = field("lifecycle")
        val worker = lifecycle.javaClass.getDeclaredField("worker").apply { isAccessible = true }.get(lifecycle) as Thread
        assertFalse(worker.isAlive)
        assertEquals(AudioRecord.STATE_UNINITIALIZED, (field("audioRecord") as AudioRecord).state)
    }

    @Test fun mediaRecorderManualGainRejectsBeforeTakingTheOutputDescriptor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = Camera2PreviewEngine(context)
        val failures = mutableListOf<String>()
        val listener = Proxy.newProxyInstance(Camera2PreviewListener::class.java.classLoader,
            arrayOf(Camera2PreviewListener::class.java)) { _, method, arguments ->
            if (method.name == "onFailure") failures += arguments!![0] as String
            null
        } as Camera2PreviewListener
        Camera2PreviewEngine::class.java.getDeclaredField("listener").apply { isAccessible = true }.set(engine, listener)
        val file = File(context.cacheDir, "e15-gain-reject-${UUID.randomUUID()}.mp4")
        val pristine = byteArrayOf(1, 2, 3, 4)
        file.writeBytes(pristine)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                assertFalse(engine.startVideo(descriptor, Camera2EmbeddedAudioConfig(AudioSourceSelection.MIC.androidSource,
                    48000, 1, 128000, null, recordingGain = DigitalRecordingGain(true, 0))))
                assertEquals(listOf("audio-manual-gain-requires-pcm"), failures)
                assertTrue(descriptor.fileDescriptor.valid())
                assertArrayEquals(pristine, file.readBytes())
            }
        } finally { engine.closeAsync().get(15, TimeUnit.SECONDS); file.delete() }
    }

    private fun record(depth: AudioBitDepth, channels: Int, flac: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val gain = DigitalRecordingGain(true, 6)
        val settings = CameraSettings(audioOutputFormat = if (flac) AudioOutputFormat.FLAC else AudioOutputFormat.WAV_PCM,
            audioBitDepth = depth, audioSampleRateHz = 48000, audioChannels = channels,
            audioSource = AudioSourceSelection.MIC, automaticGainControlEnabled = true,
            audioRecordingGain = gain, noiseSuppressorEnabled = false, acousticEchoCancelerEnabled = false)
        val stem = "E15_${if (flac) "FLAC" else "WAV"}_${depth.name}_${channels}"
        val videoName = "${stem}_${UUID.randomUUID()}.mp4"
        val levels = CopyOnWriteArrayList<AudioLevelSnapshot>()
        val observed = CountDownLatch(3)
        val onLevel: (AudioLevelSnapshot) -> Unit = { levels += it; observed.countDown() }
        val recorder: AudioSidecarRecorder = if (flac) FlacAudioSidecarRecorder.create(context, videoName, settings, onAudioLevel = onLevel)
            else WavAudioSidecarRecorder.create(context, videoName, settings, onAudioLevel = onLevel)
        try {
            recorder.start()
            assertTrue("Actual PCM callbacks must arrive", observed.await(15, TimeUnit.SECONDS))
            val result = requireNotNull(recorder.finish(true))
            assertSame(result, recorder.finish(true))
            assertEquals(gain, result.recordingGain)
            assertFalse("Manual overrides requested AGC", result.automaticGainControlEnabled)
            assertEquals(depth, result.bitDepth); assertEquals(channels, result.channels)
            assertTrue(result.frames > 0)
            assertTrue(levels.size >= 3)
            assertTrue(levels.all { it.appliedRecordingGain == gain && it.channels.size == channels })
            val timing = requireNotNull(result.captureTiming)
            assertEquals(result.frames, timing.writtenFrames)
            assertEquals(result.frames, timing.capturedFrames)
            val resolver = context.contentResolver
            val metadataName = videoName.removeSuffix(".mp4") + ".audio.json"
            val metadataUri = requireNotNull(resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf("_id"), "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(metadataName), null)).use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst())
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0))
            }
            val json = JSONObject(requireNotNull(resolver.openInputStream(metadataUri)).bufferedReader().use { it.readText() })
            val saved = json.getJSONObject("recordingGain")
            assertTrue(saved.getBoolean("manualEnabled")); assertEquals(6, saved.getInt("appliedDigitalDb"))
            assertTrue(saved.getBoolean("agcExcludedByManual")); assertTrue(saved.isNull("analogInputGain"))
            assertTrue(saved.isNull("listeningVolume")); assertFalse(json.getBoolean("automaticGainControlEnabled"))
            val directory = File(context.filesDir, "e15-gain-fixture").apply { mkdirs() }
            val file = File(directory, stem + if (flac) ".flac" else ".wav")
            requireNotNull(resolver.openInputStream(result.uri)).use { input -> file.outputStream().use { input.copyTo(it) } }
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            File(directory, "$stem.json").writeText(json.put("fixtureSha256", hash).put("meterCallbacks", levels.size).toString(2))
            android.util.Log.i("AudioGainProbe", "$stem frames=${result.frames} gain=6 AGC=false hash=$hash")
        } finally { recorder.discard() }
    }

    @Test fun queuedOldProducerCannotPublishGainAfterReplacementOrDestruction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = CaptureService()
        fun field(name: String) = CaptureService::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field("cameraState").get(service) as MutableStateFlow<CameraUiState>
        val newConsumer = CaptureService::class.java.getDeclaredMethod("newAudioLevelConsumer").apply { isAccessible = true }
        val old = AudioLevelSnapshot(listOf(AudioChannelLevel(-1f, -6f)), true, 1, appliedRecordingGain = DigitalRecordingGain(true, 24))
        val observedOff = com.librestatic.opencinecam.camera.AudioEffectObservation(true,
            com.librestatic.opencinecam.camera.AudioEffectState.DISABLED,
            com.librestatic.opencinecam.camera.AudioEffectImplementation.PLATFORM, true)
        val fresh = old.copy(clipped = false, capturedAtElapsedRealtimeMs = 2, appliedRecordingGain = DigitalRecordingGain(true, -12),
            effects = com.librestatic.opencinecam.camera.AudioEffectsSnapshot(observedOff, observedOff, observedOff))
        try {
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST") val stale = newConsumer.invoke(service) as (AudioLevelSnapshot) -> Unit
                stale(old)
                @Suppress("UNCHECKED_CAST") val current = newConsumer.invoke(service) as (AudioLevelSnapshot) -> Unit
                current(fresh)
            }
            instrumentation.waitForIdleSync()
            assertEquals(fresh, state.value.audioLevels)
            assertFalse("A stale clipped frame must not latch the replacement", state.value.audioClipLatched)
            lateinit var current: (AudioLevelSnapshot) -> Unit
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST")
                val next = newConsumer.invoke(service) as (AudioLevelSnapshot) -> Unit
                current = next
                assertNull("Replacement has no applied PCM before its first receipt", state.value.audioLevels)
                assertFalse(state.value.audioMonitoringActive)
                current(fresh)
            }
            instrumentation.waitForIdleSync()
            assertEquals(fresh, state.value.audioLevels)
            val retire = CaptureService::class.java.getDeclaredMethod("retireAudioLevelConsumer", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            instrumentation.runOnMainSync {
                current(old)
                retire.invoke(service, false)
            }
            instrumentation.waitForIdleSync()
            assertEquals(fresh, state.value.audioLevels)
            assertFalse("Retired producer without a successor stays inactive", state.value.audioMonitoringActive)
            assertFalse(state.value.audioClipLatched)
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST") val after = newConsumer.invoke(service) as (AudioLevelSnapshot) -> Unit
                after(old)
                field("serviceDestroyed").setBoolean(service, true)
            }
            instrumentation.waitForIdleSync()
            assertNull(state.value.audioLevels)
            assertFalse(state.value.audioMonitoringActive)
        } finally { (field("executor").get(service) as ThreadPoolExecutor).shutdownNow() }
    }
}
