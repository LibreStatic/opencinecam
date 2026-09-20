/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.storage.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

@Suppress("DEPRECATION")
class AudioRetirementTest {
    @Test fun wavDeadlineDefersCloseUntilItsActualWriterReturns() = stalledWriter(false)
    @Test fun flacDeadlineDefersCloseUntilItsActualFeederReturns() = stalledWriter(true)
    private fun stalledWriter(flac: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val settings = CameraSettings(audioBitDepth = AudioBitDepth.PCM_16, audioSource = AudioSourceSelection.MIC,
            automaticGainControlEnabled = false, noiseSuppressorEnabled = false, acousticEchoCancelerEnabled = false)
        val video = VideoOutput.create(context)
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val callback: (com.librestatic.opencinecam.camera.AudioLevelSnapshot) -> Unit = {
            entered.countDown(); check(resume.await(15, TimeUnit.SECONDS))
        }
        val audio: AudioSidecarRecorder = if (flac) FlacAudioSidecarRecorder.create(context, video.displayName, settings, onAudioLevel = callback)
            else WavAudioSidecarRecorder.create(context, video.displayName, settings, onAudioLevel = callback)
        fun audioRows() = requireNotNull(context.contentResolver.query(MediaStore.setIncludePending(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI),
            arrayOf("_id"), "${MediaStore.Audio.Media.DISPLAY_NAME} = ?", arrayOf(audio.displayName), null)).use { it.count }
        try {
            audio.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            val start = System.nanoTime()
            val failure = assertThrows(IllegalStateException::class.java) { audio.finish(true) }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue(failure.message.orEmpty().contains("did not retire"))
            assertTrue(elapsed in (if (flac) 4800L else 2800L)..(if (flac) 6500L else 4500L))
            assertEquals("The live worker retains its pending row until actual retirement", 1, audioRows())
            requireNotNull(context.contentResolver.query(MediaStore.setIncludePending(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI),
                arrayOf(MediaStore.Audio.Media.IS_PENDING), "${MediaStore.Audio.Media.DISPLAY_NAME} = ?", arrayOf(audio.displayName), null)).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
            assertNull(audio.finish(true)); assertNull(audio.publishPrepared())
            assertThrows(IllegalStateException::class.java) { WavAudioSidecarRecorder.create(context, video.displayName, settings) }
            assertThrows(IllegalStateException::class.java) { FlacAudioSidecarRecorder.create(context, video.displayName, settings) }
            resume.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (runCatching { AudioRetirementGate.requireIdle() }.isFailure && System.nanoTime() < deadline) Thread.sleep(10)
            AudioRetirementGate.requireIdle()
            assertEquals("Deferred cleanup must delete its row after the worker exits", 0, audioRows())
            audio.discard()
            val replacement = WavAudioSidecarRecorder.create(context, video.displayName, settings)
            replacement.discard(); assertEquals(0, audioRows())
            android.util.Log.i("AudioRetirementProbe", "flac=$flac elapsedMs=$elapsed abandoned=true pendingRowRetainedUntilWorkerExit=true gateRecovered=true lateSuccess=null audioRows=0")
        } finally {
            resume.countDown()
            runCatching { audio.discard() }
            video.finish(false)
        }
    }
}
