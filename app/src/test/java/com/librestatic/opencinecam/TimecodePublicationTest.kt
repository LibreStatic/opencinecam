/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam
import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.storage.finalizeRecordingTake
import org.junit.Assert.*
import org.junit.Test

class TimecodePublicationTest {
    @Test fun failedVideoOrAudioPublicationPreservesRegenButConsumesRecordRun() {
        for (mode in listOf(TimecodeMode.RECORD_RUN, TimecodeMode.REGEN)) for (audioFails in listOf(true, false)) {
            val tracker = TimecodeTracker { 0 }; tracker.configure(TimecodeRate(30), mode, SmpteTimecode(1, 0, 0, 0), true)
            tracker.onRecordingStarted(1); tracker.observeEncodedProgress(EncodedRecordingProgress(1, 3, 0, 66666))
            var discarded = false; var aborted = false
            assertThrows(IllegalStateException::class.java) {
                try {
                    val published = finalizeRecordingTake(true,
                        finishAudio = { accepted -> if (accepted && audioFails) error("audio publish") else "audio" },
                        discardAudio = { discarded = true },
                        finishVideo = { accepted -> if (accepted) error("video publish") else { aborted = true; null as String? } })
                    tracker.onRecordingStopped(published != null)
                } finally { tracker.onRecordingStopped(false) }
            }
            assertTrue(discarded); assertTrue(aborted)
            tracker.onRecordingStarted(2); tracker.observeEncodedProgress(EncodedRecordingProgress(2, 1, 0, 0))
            assertEquals(if (mode == TimecodeMode.RECORD_RUN) "01:00:00:03" else "01:00:00:00", tracker.currentDisplayTc()!!.format())
        }
    }
    @Test fun publishedPairCommitsRegenExactlyOnce() {
        val tracker = TimecodeTracker { 0 }; tracker.configure(TimecodeRate(30), TimecodeMode.REGEN, SmpteTimecode(1, 0, 0, 0), true)
        tracker.onRecordingStarted(1); tracker.observeEncodedProgress(EncodedRecordingProgress(1, 3, 0, 66666))
        try {
            val published = finalizeRecordingTake(true, { "audio" }, { error("unexpected compensation") }, { "video" })
            assertEquals("video" to "audio", published); tracker.onRecordingStopped(published != null)
        } finally { tracker.onRecordingStopped(false) }
        tracker.onRecordingStarted(2); tracker.observeEncodedProgress(EncodedRecordingProgress(2, 1, 0, 0))
        assertEquals("01:00:00:03", tracker.currentDisplayTc()!!.format())
    }
}
