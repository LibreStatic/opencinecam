/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.media.AudioRecord
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.AcousticEchoCanceler
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.camera.AudioEffectState
import com.librestatic.opencinecam.media.audio.*
import com.librestatic.opencinecam.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AudioEffectsSidecarDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun wav16ReportsPublicEffectsAndActualSoftwareOrPlatformAgc() = record(false, AudioBitDepth.PCM_16, false)
    @Test fun wavFloatNeverReportsSoftwareAgcThatDoesNotProcessFloat() = record(false, AudioBitDepth.PCM_FLOAT, false)
    @Test fun flacManualPreservesRequestedAgcButReportsActualDisabledOrUnavailable() = record(true, AudioBitDepth.PCM_16, true)

    private fun record(flac: Boolean, depth: AudioBitDepth, manual: Boolean) {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val settings = settings(flac, depth, manual)
        val seen = CountDownLatch(3)
        val failure = AtomicReference<Throwable?>()
        lateinit var recorder: AudioSidecarRecorder
        val onLevel: (AudioLevelSnapshot) -> Unit = { snapshot ->
            try {
                val effects = requireNotNull(snapshot.effects)
                assertTrue(effects.noiseSuppressor.requested); assertTrue(effects.automaticGainControl.requested)
                assertTrue(effects.acousticEchoCanceler.requested)
                @Suppress("UNCHECKED_CAST") val handles = field(recorder, "effects") as List<AudioEffect>
                fun verify(value: AudioEffectObservation, handle: AudioEffect?, available: Boolean) {
                    if (value.implementation == AudioEffectImplementation.SOFTWARE) {
                        assertEquals(AudioEffectState.ENABLED, value.state)
                        assertNotNull(field(recorder, "softAgc"))
                        assertNotEquals(AudioBitDepth.PCM_FLOAT, depth)
                        assertFalse(manual)
                        if (handle != null) assertFalse(handle.enabled)
                    } else if (handle != null) {
                        assertEquals(AudioEffectImplementation.PLATFORM, value.implementation)
                        assertEquals(if (handle.enabled) AudioEffectState.ENABLED else AudioEffectState.DISABLED, value.state)
                        assertEquals(handle.hasControl(), value.hasControl)
                    } else {
                        assertEquals(AudioEffectImplementation.NONE, value.implementation)
                        assertEquals(if (available) AudioEffectState.FAILED else AudioEffectState.UNAVAILABLE, value.state)
                        assertNull(value.hasControl)
                    }
                }
                verify(effects.noiseSuppressor, handles.filterIsInstance<NoiseSuppressor>().singleOrNull(), NoiseSuppressor.isAvailable())
                verify(effects.automaticGainControl, handles.filterIsInstance<AutomaticGainControl>().singleOrNull(), AutomaticGainControl.isAvailable())
                verify(effects.acousticEchoCanceler, handles.filterIsInstance<AcousticEchoCanceler>().singleOrNull(), AcousticEchoCanceler.isAvailable())
                if (manual) assertNotEquals(AudioEffectState.ENABLED, effects.automaticGainControl.state)
                if (depth == AudioBitDepth.PCM_FLOAT) assertNull(field(recorder, "softAgc"))
            } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
            seen.countDown()
        }
        val name = "E15_EFFECTS_${UUID.randomUUID()}.mp4"
        recorder = if (flac) FlacAudioSidecarRecorder.create(context, name, settings, onAudioLevel = onLevel)
            else WavAudioSidecarRecorder.create(context, name, settings, onAudioLevel = onLevel)
        try {
            recorder.start(); assertTrue(seen.await(15, TimeUnit.SECONDS))
            val prepared = requireNotNull(recorder.prepareCompletion())
            assertNull(failure.get())
            val result = prepared.result; assertTrue(result.frames > 0)
            assertEquals(AudioRecord.STATE_UNINITIALIZED, (field(recorder, "audioRecord") as AudioRecord).state)
            val effects = requireNotNull(result.audioEffects)
            val at = requireNotNull(result.audioEffectsObservedAtElapsedRealtimeMs)
            assertTrue(at > 0); assertTrue(at <= SystemClock.elapsedRealtime())
            val metadataUri = Uri.parse(prepared.artifacts.single { it.role == CaptureArtifactRole.AUDIO_METADATA }.uri)
            val json = JSONObject(requireNotNull(context.contentResolver.openInputStream(metadataUri)).bufferedReader().use { it.readText() })
            val actual = json.getJSONObject("audioEffects")
            assertEquals(audioEffectsJson(effects, at).toString(), actual.toString())
            assertEquals("LAST_CAPTURED_PCM_BEFORE_RETIREMENT", actual.getString("observation"))
            assertTrue(actual.isNull("hiddenHalProcessing"))
            assertEquals(effects.noiseSuppressor.state == AudioEffectState.ENABLED, json.getBoolean("noiseSuppressorEnabled"))
            assertEquals(effects.automaticGainControl.state == AudioEffectState.ENABLED, json.getBoolean("automaticGainControlEnabled"))
            assertEquals(effects.acousticEchoCanceler.state == AudioEffectState.ENABLED, json.getBoolean("acousticEchoCancelerEnabled"))
            assertSame(result, recorder.publishPrepared())
        } finally { recorder.discard() }
    }

    @Test fun wavManualLosingConfirmedAgcDisableAbortsAndRetiresWithoutPublication() = lostObservation(false)
    @Test fun flacManualLosingConfirmedAgcDisableAbortsAndRetiresWithoutPublication() = lostObservation(true)

    private fun lostObservation(flac: Boolean) {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val injected = CountDownLatch(1)
        lateinit var recorder: AudioSidecarRecorder
        val name = "E15_EFFECT_LOSS_${UUID.randomUUID()}"
        val onLevel: (AudioLevelSnapshot) -> Unit = {
            // Force an unavailable getter observation after a real PCM receipt, on the owning
            // producer thread. This models lost confirmation, not a physical HAL control takeover.
            recorder.javaClass.getDeclaredField("hardwareAgcReader").apply { isAccessible = true }
                .set(recorder, AudioEffectObservationReader(true, null, true, configurationFailed = true))
            recorder.javaClass.getDeclaredField("hasHardwareAgc").apply { isAccessible = true }.setBoolean(recorder, true)
            injected.countDown()
        }
        recorder = if (flac) FlacAudioSidecarRecorder.create(context, "$name.mp4", settings(true, AudioBitDepth.PCM_16, true), onAudioLevel = onLevel)
            else WavAudioSidecarRecorder.create(context, "$name.mp4", settings(false, AudioBitDepth.PCM_16, true), onAudioLevel = onLevel)
        try {
            recorder.start(); assertTrue(injected.await(15, TimeUnit.SECONDS))
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (field(recorder, if (flac) "failure" else "writerFailure") == null) {
                check(SystemClock.elapsedRealtime() < deadline); Thread.sleep(10)
            }
            val problem = assertThrows(IllegalStateException::class.java) { recorder.finish(true) }
            assertTrue(problem.message.orEmpty().contains("AGC"))
            assertEquals(AudioRecord.STATE_UNINITIALIZED, (field(recorder, "audioRecord") as AudioRecord).state)
            requireNotNull(context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf("_id"),
                "${MediaStore.MediaColumns.DISPLAY_NAME} IN (?, ?)", arrayOf(name + if (flac) ".flac" else ".wav", "$name.audio.json"), null)).use {
                assertEquals(0, it.count)
            }
        } finally { recorder.discard() }
    }

    @Test fun metadataKeepsUnavailableUnknownFailureAndRequestedSeparate() {
        val unknown = AudioEffectObservation(true, AudioEffectState.UNKNOWN, AudioEffectImplementation.NONE)
        val missing = AudioEffectObservation(false, AudioEffectState.UNAVAILABLE, AudioEffectImplementation.NONE)
        val software = AudioEffectObservation(true, AudioEffectState.ENABLED, AudioEffectImplementation.SOFTWARE, configurationFailed = true)
        val json = audioEffectsJson(AudioEffectsSnapshot(unknown, software, missing), 123)
        assertEquals("UNKNOWN", json.getJSONObject("noiseSuppressor").getString("state"))
        assertTrue(json.getJSONObject("noiseSuppressor").getBoolean("requested"))
        assertTrue(json.getJSONObject("noiseSuppressor").isNull("hasControl"))
        assertEquals("SOFTWARE", json.getJSONObject("automaticGainControl").getString("implementation"))
        assertTrue(json.getJSONObject("automaticGainControl").getBoolean("configurationFailed"))
        assertEquals("UNAVAILABLE", json.getJSONObject("acousticEchoCanceler").getString("state"))
        assertFalse(json.getJSONObject("acousticEchoCanceler").getBoolean("requested"))
    }

    private fun settings(flac: Boolean, depth: AudioBitDepth, manual: Boolean) = CameraSettings(
        audioOutputFormat = if (flac) AudioOutputFormat.FLAC else AudioOutputFormat.WAV_PCM,
        audioBitDepth = depth, audioSampleRateHz = 48000, audioChannels = 1, audioSource = AudioSourceSelection.MIC,
        audioRecordingGain = DigitalRecordingGain(manual, 0), noiseSuppressorEnabled = true,
        automaticGainControlEnabled = true, acousticEchoCancelerEnabled = true)
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
