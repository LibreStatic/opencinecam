/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.net.Uri
import android.provider.MediaStore
import android.media.MediaExtractor
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.storage.*
import org.junit.Assert.*
import org.junit.Test

/** Real audio capture and owned provider rows; these are not VIDEO encoder qualification tests. */
class AudioTakeOwnershipTest {
    @Test fun wavIsRevokedWhenSubsequentVideoPublicationFails() = exercise(false, true)
    @Test fun flacIsRevokedWhenSubsequentVideoPublicationFails() = exercise(true, true)
    @Test fun wavSuccessRemainsReadableUntilExplicitDiscard() = exercise(false, false)
    @Test fun flacSuccessRemainsReadableUntilExplicitDiscard() = exercise(true, false)
    @Test fun emptyWavRejectsWholeTake() = exercise(false, false, empty = true)
    @Test fun emptyFlacRejectsWholeTake() = exercise(true, false, empty = true)

    private fun exercise(flac: Boolean, failVideo: Boolean, empty: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val resolver = context.contentResolver
        val video = VideoOutput.create(context)
        val settings = CameraSettings(audioBitDepth = AudioBitDepth.PCM_16, audioSampleRateHz = 48000,
            audioChannels = 1, audioSource = com.librestatic.opencinecam.media.audio.AudioSourceSelection.MIC,
            automaticGainControlEnabled = false, acousticEchoCancelerEnabled = false, noiseSuppressorEnabled = false)
        val audio: AudioSidecarRecorder = if (flac) FlacAudioSidecarRecorder.create(context, video.displayName, settings)
            else WavAudioSidecarRecorder.create(context, video.displayName, settings)
        fun namedRows(collection: Uri, name: String): Int = requireNotNull(resolver.query(collection,
            arrayOf("_id"), "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null)).use { it.count }
        val metadataName = video.displayName.removeSuffix(".mp4") + ".audio.json"
        fun audioRows() = namedRows(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audio.displayName)
        fun metadataRows() = namedRows(MediaStore.Downloads.EXTERNAL_CONTENT_URI, metadataName)
        fun videoRows() = namedRows(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video.displayName)
        try {
            if (!empty) { audio.start(); Thread.sleep(750) }
            var captured: AudioSidecarRecordingResult? = null
            val finish = {
                finalizeRecordingTake(true, { accepted -> audio.finish(accepted).also { captured = it } }, audio::discard,
                    { accepted ->
                        if (accepted) {
                            assertEquals(1, audioRows()); assertEquals(1, metadataRows())
                            if (failVideo) assertEquals(1, resolver.delete(video.uri, null, null))
                        }
                        video.finish(accepted)
                    })
            }
            if (failVideo || empty) {
                val failure = assertThrows(RuntimeException::class.java) { finish() }
                if (empty) assertTrue(failure.message.orEmpty().contains("Requested audio"))
                else { assertNotNull(captured); assertTrue(failure is SecurityException || failure is IllegalStateException) }
                assertEquals(0, videoRows()); assertEquals(0, audioRows()); assertEquals(0, metadataRows())
                assertNull(audio.finish(true))
            } else {
                val take = requireNotNull(finish())
                val result = requireNotNull(take.second)
                assertTrue(result.frames > 0); assertTrue(result.dataBytes > 0)
                assertSame(result, audio.finish(true)); audio.close()
                assertEquals(1, audioRows()); assertEquals(1, metadataRows())
                val extractor = MediaExtractor()
                try { extractor.setDataSource(context, result.uri, null); assertEquals(1, extractor.trackCount) }
                finally { extractor.release() }
                val file = java.io.File(context.cacheDir, if (flac) "ownership.flac" else "ownership.wav")
                requireNotNull(resolver.openInputStream(result.uri)).use { input -> file.outputStream().use { input.copyTo(it) } }
                android.util.Log.i("AudioTakeProbe", "container=${result.container} frames=${result.frames} bytes=${result.dataBytes} sampleRate=${result.sampleRateHz} cache=${file.name}")
                audio.discard(); audio.discard()
                assertNull(audio.finish(true)); assertEquals(0, audioRows()); assertEquals(0, metadataRows())
                assertEquals(1, resolver.delete(take.first, null, null))
            }
            android.util.Log.i("AudioTakeProbe", "flac=$flac failVideo=$failVideo empty=$empty videoRows=${videoRows()} audioRows=${audioRows()} metadataRows=${metadataRows()} laterSuccess=null")
        } finally {
            runCatching { audio.discard() }
            runCatching { video.finish(false) }
        }
    }
}
