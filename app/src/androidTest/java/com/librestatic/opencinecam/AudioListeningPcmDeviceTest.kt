/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.media.audio.*
import com.librestatic.opencinecam.service.AudioListeningController
import com.librestatic.opencinecam.storage.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Owned AudioRecord feeds both file and real AudioTrack. Exact offered PCM retained for independent decode. */
class AudioListeningPcmDeviceTest {
    @Test fun wav16StereoFileEqualsOfferedPcmDespiteLiveListeningVolume() = record(AudioBitDepth.PCM_16, 2, false)
    @Test fun wavFloatFilePreservesOfferedFloatBytesWhileListeningUsesPrivatePcm16() = record(AudioBitDepth.PCM_FLOAT, 1, false)
    @Test fun flacFileEqualsOfferedPcmDespiteLiveListeningVolume() = record(AudioBitDepth.PCM_16, 2, true)

    private fun record(depth: AudioBitDepth, channels: Int, flac: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val speaker = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val status = AtomicReference(AudioListeningStatus())
        val heard = CountDownLatch(1)
        val controller = AudioListeningController(context, { value ->
            status.set(value)
            if (value.phase == AudioListeningPhase.ACTIVE && value.acceptedFrames > 0) heard.countDown()
        }, {})
        val options = AudioListeningSettings(true, 30, AudioListeningOutput.SPEAKER)
        controller.configure(options, speaker.id); controller.reconnect()
        val sampleRate = if (depth == AudioBitDepth.PCM_16 && !flac) 44100 else 48000
        val gain = DigitalRecordingGain(true, 6)
        val settings = CameraSettings(audioOutputFormat = if (flac) AudioOutputFormat.FLAC else AudioOutputFormat.WAV_PCM,
            audioBitDepth = depth, audioSampleRateHz = sampleRate, audioChannels = channels, audioSource = AudioSourceSelection.MIC,
            audioRecordingGain = gain, automaticGainControlEnabled = true,
            noiseSuppressorEnabled = false, acousticEchoCancelerEnabled = false)
        val tapped = ByteArrayOutputStream(); val packets = AtomicInteger(); val callbackFailure = AtomicReference<Throwable?>()
        val meterSeen = CountDownLatch(4)
        val onLevel: (AudioLevelSnapshot) -> Unit = { snapshot ->
            try {
                assertEquals(channels, snapshot.channels.size)
                assertTrue(snapshot.channels.all { it.vuDbfs?.isFinite() == true && it.ppmDbfs?.isFinite() == true })
                assertEquals(gain, snapshot.appliedRecordingGain)
            } catch (failure: Throwable) { callbackFailure.compareAndSet(null, failure) }
            meterSeen.countDown()
        }
        val sink = PcmListeningSink { buffer, bytes, encoding, rate, count ->
            try {
                assertTrue(buffer.isReadOnly); assertEquals(channels, count); assertEquals(sampleRate, rate)
                assertTrue(tapped.size() + bytes <= 4 * 1024 * 1024)
                val pcm = ByteArray(bytes); buffer.duplicate().apply { clear(); limit(bytes) }.get(pcm)
                tapped.write(pcm)
                val volume = if (packets.incrementAndGet() % 2 == 0) 0 else 100
                controller.configure(options.copy(volumePercent = volume), speaker.id)
                controller.offer(buffer, bytes, encoding, rate, count)
            } catch (failure: Throwable) { callbackFailure.set(failure); false }
        }
        val stem = "E15_LISTEN_${if (flac) "FLAC" else "WAV"}_${depth.name}_$channels"
        val videoName = "${stem}_${UUID.randomUUID()}.mp4"
        var recorder: AudioSidecarRecorder? = null
        try {
            recorder = if (flac) FlacAudioSidecarRecorder.create(context, videoName, settings, onAudioLevel = onLevel, listeningSink = sink)
                else WavAudioSidecarRecorder.create(context, videoName, settings, onAudioLevel = onLevel, listeningSink = sink)
            val meter = recorder.javaClass.getDeclaredField("levelMeter").apply { isAccessible = true }.get(recorder)
            assertEquals(sampleRate, meter.javaClass.getDeclaredField("sampleRateHz").apply { isAccessible = true }.getInt(meter))
            recorder.start()
            assertTrue("Actual PCM meters must arrive", meterSeen.await(15, TimeUnit.SECONDS))
            assertTrue("Actual AudioTrack route/write must become ACTIVE: ${status.get()}", heard.await(10, TimeUnit.SECONDS))
            val result = requireNotNull(recorder.finish(true))
            assertNull(callbackFailure.get()); assertEquals(gain, result.recordingGain)
            assertFalse(result.automaticGainControlEnabled); assertEquals(depth, result.bitDepth)
            val bytes = tapped.toByteArray()
            assertEquals(result.frames * channels * (depth.bits / 8), bytes.size.toLong())
            assertTrue(packets.get() >= 2)
            assertEquals(speaker.id, status.get().effectiveDeviceId)
            val directory = File(context.filesDir, "e15-listening-pcm").apply { mkdirs() }
            val file = File(directory, stem + if (flac) ".flac" else ".wav")
            requireNotNull(context.contentResolver.openInputStream(result.uri)).use { input -> file.outputStream().use { input.copyTo(it) } }
            File(directory, "$stem.pcm").writeBytes(bytes)
            val json = JSONObject().put("frames", result.frames).put("channels", channels).put("rate", sampleRate)
                .put("encoding", depth.name).put("gainDb", 6).put("listeningVolumeValues", "0,100")
                .put("effectiveDeviceId", speaker.id).put("packets", packets.get()).put("acceptedFrames", status.get().acceptedFrames)
                .put("fileSha256", sha(file.readBytes())).put("pcmSha256", sha(bytes))
            File(directory, "$stem.json").writeText(json.toString(2))
            android.util.Log.i("ListeningPcmProbe", "$stem $json")
        } finally {
            try { recorder?.discard() } finally { controller.closeAsync().get(15, TimeUnit.SECONDS) }
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
