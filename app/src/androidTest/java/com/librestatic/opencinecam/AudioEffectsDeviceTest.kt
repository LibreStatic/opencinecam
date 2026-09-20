/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.audiofx.AudioEffect
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.AudioEffectImplementation
import com.librestatic.opencinecam.camera.AudioEffectObservationReader
import com.librestatic.opencinecam.camera.AudioEffectState
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.Camera2EmbeddedAudioConfig
import com.librestatic.opencinecam.camera.CaptureEpochClock
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.service.PreviewAudioMonitor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Public effect/session observations; absence is not proof of disabled hidden HAL processing. */
class AudioEffectsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun grant() = instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)

    @Test fun previewReportsRetainedEffectsAndReleasedGettersNeverBecomeDisabledEvidence() {
        grant()
        val levels = CopyOnWriteArrayList<AudioLevelSnapshot>(); val failures = CopyOnWriteArrayList<Throwable>()
        val seen = CountDownLatch(2)
        val monitor = PreviewAudioMonitor.create(context, CameraSettings(audioSource = AudioSourceSelection.MIC,
            noiseSuppressorEnabled = true, acousticEchoCancelerEnabled = true, automaticGainControlEnabled = true,
            audioRecordingGain = DigitalRecordingGain(true, 0)), { levels += it; seen.countDown() }, { failures += it })
        @Suppress("UNCHECKED_CAST") val effects = field(monitor, "effects") as List<AudioEffect>
        val retained = effects.map { AudioEffectObservationReader(true, it, true) }
        try {
            monitor.start(); assertTrue(seen.await(15, TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
            val actual = requireNotNull(levels.last().effects)
            assertTrue(actual.noiseSuppressor.requested); assertTrue(actual.acousticEchoCanceler.requested)
            assertTrue(actual.automaticGainControl.requested)
            assertNotEquals(AudioEffectImplementation.SOFTWARE, actual.automaticGainControl.implementation)
            if (actual.automaticGainControl.implementation == AudioEffectImplementation.PLATFORM)
                assertEquals(AudioEffectState.DISABLED, actual.automaticGainControl.state)
            else assertEquals(AudioEffectState.UNAVAILABLE, actual.automaticGainControl.state)
            for (reader in retained) {
                val value = reader.read()
                assertEquals(AudioEffectImplementation.PLATFORM, value.implementation)
                assertNotNull(value.hasControl)
                assertTrue(value.state in setOf(AudioEffectState.ENABLED, AudioEffectState.DISABLED))
            }
        } finally { monitor.closeAsync().get(15, TimeUnit.SECONDS) }
        assertEquals(AudioRecord.STATE_UNINITIALIZED, (field(monitor, "audioRecord") as AudioRecord).state)
        // This deliberate adapter test is not how production obtains final recording metadata.
        for (reader in retained) assertEquals(AudioEffectState.FAILED, reader.read().state)
        assertTrue(levels.all { it.effects != null })
    }

    @Test fun floatPreviewNeverClaimsSoftwareAgcThatItsPcmLoopDoesNotApply() {
        grant()
        val levels = CopyOnWriteArrayList<AudioLevelSnapshot>(); val failures = CopyOnWriteArrayList<Throwable>()
        val seen = CountDownLatch(2)
        val monitor = PreviewAudioMonitor.create(context, CameraSettings(audioSource = AudioSourceSelection.MIC,
            audioOutputFormat = AudioOutputFormat.WAV_PCM, audioBitDepth = AudioBitDepth.PCM_FLOAT,
            automaticGainControlEnabled = true), { levels += it; seen.countDown() }, { failures += it })
        try {
            monitor.start(); assertTrue(seen.await(15, TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
            assertNull(field(monitor, "softAgc"))
            assertTrue(levels.all { it.effects?.automaticGainControl?.implementation != AudioEffectImplementation.SOFTWARE })
        } finally { monitor.closeAsync().get(15, TimeUnit.SECONDS) }
    }

    @Test fun embeddedAacObservesRealEffectsWhileEncodingAndRetiresAfterAudioEos() {
        grant()
        val levels = CopyOnWriteArrayList<AudioLevelSnapshot>(); val failures = CopyOnWriteArrayList<Throwable>()
        val retirementFailures = CopyOnWriteArrayList<Throwable>(); val seen = CountDownLatch(2)
        val config = Camera2EmbeddedAudioConfig(AudioSourceSelection.MIC.androidSource, 48000, 1, 128000, null,
            enableAutomaticGainControl = true, onAudioLevel = { levels += it; seen.countDown() },
            enableNoiseSuppressor = true, enableAcousticEchoCanceler = true)
        val clock = CaptureEpochClock(true, 48000).apply { videoInput(android.os.SystemClock.elapsedRealtimeNanos()) }
        val type = Class.forName("com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline\$EmbeddedAac")
        val companion = type.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val create = companion.javaClass.declaredMethods.single { it.name == "create" }.apply { isAccessible = true }
        val audio = create.invoke(companion, context, config, clock,
            { failure: Throwable -> retirementFailures += failure }, { false })
        val codec = field(audio, "codec") as MediaCodec
        val eos = CountDownLatch(1); val packet = CountDownLatch(1); val packets = AtomicInteger()
        val draining = AtomicBoolean(true)
        val drainer = Thread({
            try {
                val info = MediaCodec.BufferInfo()
                while (draining.get()) {
                    val index = codec.dequeueOutputBuffer(info, 10000)
                    if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                packets.incrementAndGet(); packet.countDown()
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos.countDown(); break }
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            } catch (failure: Throwable) { failures += failure }
        }, "AudioEffectsAacDrainFixture")
        var started = false
        try {
            type.getDeclaredMethod("start", kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
                .invoke(audio, { failure: Throwable -> failures += failure })
            started = true; drainer.start()
            assertTrue(seen.await(15, TimeUnit.SECONDS)); assertTrue(packet.await(15, TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
            val snapshot = requireNotNull(levels.last().effects)
            assertTrue(snapshot.noiseSuppressor.requested); assertTrue(snapshot.automaticGainControl.requested)
            assertTrue(snapshot.acousticEchoCanceler.requested)
            assertTrue(levels.all { it.effects != null })
            if (field(audio, "softAgc") != null) {
                assertEquals(AudioEffectImplementation.SOFTWARE, snapshot.automaticGainControl.implementation)
                assertEquals(AudioEffectState.ENABLED, snapshot.automaticGainControl.state)
            }
            type.getDeclaredMethod("requestStop").apply { isAccessible = true }.invoke(audio)
            assertTrue(eos.await(15, TimeUnit.SECONDS))
        } finally {
            if (started) type.getDeclaredMethod("requestStop").apply { isAccessible = true }.invoke(audio)
            if (drainer.isAlive) eos.await(15, TimeUnit.SECONDS)
            draining.set(false); if (started) drainer.join(15000)
            assertFalse("Drain must stop before codec release", drainer.isAlive)
            (audio as AutoCloseable).close()
        }
        assertTrue(retirementFailures.toString(), retirementFailures.isEmpty())
        assertTrue(failures.toString(), failures.isEmpty()); assertTrue(packets.get() > 0)
        assertEquals(AudioRecord.STATE_UNINITIALIZED, (field(audio, "audioRecord") as AudioRecord).state)
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
