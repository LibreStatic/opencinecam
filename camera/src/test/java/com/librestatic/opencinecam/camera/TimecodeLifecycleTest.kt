/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class TimecodeLifecycleTest {
    private val rate = TimecodeRate(30, true)
    private val start = SmpteTimecode(0, 0, 59, 29, true)
    private fun tracker(mode: TimecodeMode = TimecodeMode.RECORD_RUN, enabled: Boolean = true) = TimecodeTracker { 0 }.apply {
        configure(rate, mode, start, enabled)
    }
    private fun progress(count: Long, id: Long = 1) = EncodedRecordingProgress(id, count,
        if (count == 0L) null else 0L, if (count == 0L) null else (count - 1) * 33367)

    @Test fun recordRunCountsMuxedImagesAcrossDropFrameBoundaryAndTakes() {
        val tracker = tracker()
        tracker.onRecordingStarted(1); assertNull(tracker.currentDisplayTc())
        assertTrue(tracker.observeEncodedProgress(progress(3)))
        val report = requireNotNull(tracker.recordingReport())
        assertEquals("00:00:59;29", report.firstFrame!!.format())
        assertEquals("00:01:00;03", report.lastFrame!!.format())
        tracker.onRecordingStopped(true)
        tracker.onRecordingStarted(2); tracker.observeEncodedProgress(progress(1, 2))
        assertEquals("00:01:00;04", tracker.currentDisplayTc()!!.format())
    }

    @Test fun pauseAndDuplicateObservationsDoNotAdvanceRecordRun() {
        var now = 0L
        val tracker = TimecodeTracker { now }; tracker.configure(rate, TimecodeMode.RECORD_RUN, start, true)
        tracker.onRecordingStarted(1); tracker.observeEncodedProgress(progress(3))
        now += 10_000_000_000L
        repeat(10) { assertTrue(tracker.observeEncodedProgress(progress(3))) }
        assertEquals("00:01:00;03", tracker.currentDisplayTc()!!.format())
    }

    @Test fun staleAndRegressedProgressCannotMutateTake() {
        val tracker = tracker(); tracker.onRecordingStarted(1)
        assertFalse(tracker.observeEncodedProgress(progress(3, 2)))
        assertTrue(tracker.observeEncodedProgress(progress(3)))
        for (bad in listOf(progress(2), progress(4, 2), EncodedRecordingProgress(1, 4, 1, 100101),
            EncodedRecordingProgress(1, 4, 0, 66734), EncodedRecordingProgress(1, 3, 0, 99999))) {
            assertFalse(tracker.observeEncodedProgress(bad)); assertEquals(progress(3), tracker.recordingReport()!!.progress)
        }
    }

    @Test fun failedPublicationConsumesRecordRunButDoesNotAdvanceRegen() {
        for (mode in listOf(TimecodeMode.RECORD_RUN, TimecodeMode.REGEN)) {
            val tracker = tracker(mode); tracker.onRecordingStarted(1); tracker.observeEncodedProgress(progress(3))
            tracker.onRecordingStopped(false); tracker.onRecordingStarted(2); tracker.observeEncodedProgress(progress(1, 2))
            assertEquals(if (mode == TimecodeMode.RECORD_RUN) "00:01:00;04" else "00:00:59;29", tracker.currentDisplayTc()!!.format())
        }
    }

    @Test fun regenCommitsOnlyAfterPublicationAndFinishIsIdempotent() {
        val tracker = tracker(TimecodeMode.REGEN); tracker.onRecordingStarted(1); tracker.observeEncodedProgress(progress(3))
        repeat(3) { assertEquals("00:01:00;03", tracker.recordingReport()!!.lastFrame!!.format()) }
        tracker.onRecordingStopped(true); tracker.onRecordingStopped(false); tracker.onRecordingStopped(true)
        tracker.onRecordingStarted(2); tracker.observeEncodedProgress(progress(1, 2))
        assertEquals("00:01:00;04", tracker.currentDisplayTc()!!.format())
    }

    @Test fun lastDeferredConfigurationAppliesAfterFinishNotDuringTake() {
        val tracker = tracker(); tracker.onRecordingStarted(1); tracker.observeEncodedProgress(progress(3))
        tracker.configure(TimecodeRate(24), TimecodeMode.REGEN, SmpteTimecode(2, 0, 0, 0), false)
        tracker.configure(TimecodeRate(25), TimecodeMode.RECORD_RUN, SmpteTimecode(3, 0, 0, 0), true)
        assertEquals(rate, tracker.recordingReport()!!.config.rate)
        assertEquals("00:01:00;03", tracker.currentDisplayTc()!!.format())
        tracker.onRecordingStopped(true); tracker.onRecordingStarted(2); tracker.observeEncodedProgress(progress(1, 2))
        assertEquals("03:00:00:00", tracker.currentDisplayTc()!!.format())
    }

    @Test fun disabledTakeStaysDisabledAndDoesNotConsumeCursor() {
        val tracker = tracker(enabled = false); tracker.onRecordingStarted(1); tracker.observeEncodedProgress(progress(3))
        tracker.configure(rate, TimecodeMode.RECORD_RUN, start, true)
        assertFalse(tracker.recordingReport()!!.config.enabled); assertNull(tracker.currentDisplayTc())
        tracker.onRecordingStopped(true); tracker.onRecordingStarted(2); tracker.observeEncodedProgress(progress(1, 2))
        assertEquals(start, tracker.currentDisplayTc())
    }

    @Test fun emptyAndUnavailableTakeNeverInventFramesOrAdvanceCursor() {
        val tracker = tracker()
        tracker.onRecordingStarted(); assertNull(tracker.recordingReport()!!.progress)
        tracker.onRecordingStopped(true)
        tracker.onRecordingStarted(1); tracker.observeEncodedProgress(progress(0)); assertNull(tracker.recordingReport()!!.firstFrame)
        tracker.onRecordingStopped(false)
        tracker.onRecordingStarted(2); tracker.observeEncodedProgress(progress(1, 2)); assertEquals(start, tracker.currentDisplayTc())
    }

    @Test fun wrapAndLongCountDoNotOverflow() {
        for (mode in listOf(TimecodeMode.RECORD_RUN, TimecodeMode.REGEN)) {
            val tracker = tracker(mode); tracker.onRecordingStarted(1)
            tracker.observeEncodedProgress(EncodedRecordingProgress(1, Long.MAX_VALUE, 0, Long.MAX_VALUE))
            val expected = SmpteTimecode.fromTotalFrames(start.toTotalFrames(rate) + Long.MAX_VALUE % rate.framesPerDay, rate)
            tracker.onRecordingStopped(true); tracker.onRecordingStarted(2); tracker.observeEncodedProgress(progress(1, 2))
            assertEquals(expected, tracker.currentDisplayTc())
        }
    }

    @Test fun freeRunAdvancesDuringPauseButDoesNotClaimSourceFrameLabels() {
        var now = 0L; val tracker = TimecodeTracker { now }
        tracker.configure(TimecodeRate(30), TimecodeMode.FREE_RUN, SmpteTimecode(0, 0, 0, 0), true)
        tracker.onRecordingStarted(1); tracker.observeEncodedProgress(progress(3)); now = 10_000_000_000L
        assertEquals("00:00:10:00", tracker.currentDisplayTc()!!.format())
        assertNull(tracker.recordingReport()!!.firstFrame); assertNull(tracker.recordingReport()!!.lastFrame)
    }

    @Test fun invalidDeferredConfigDoesNotOverwriteValidPendingConfiguration() {
        val tracker = tracker(); tracker.onRecordingStarted(1)
        tracker.configure(TimecodeRate(24), TimecodeMode.RECORD_RUN, SmpteTimecode(2, 0, 0, 0), true)
        assertThrows(IllegalArgumentException::class.java) {
            tracker.configure(rate, TimecodeMode.RECORD_RUN, SmpteTimecode(0, 1, 0, 0, true), true)
        }
        assertThrows(IllegalStateException::class.java) { tracker.onRecordingStarted(2) }
        tracker.onRecordingStopped(false); tracker.onRecordingStarted(2); tracker.observeEncodedProgress(progress(1, 2))
        assertEquals("02:00:00:00", tracker.currentDisplayTc()!!.format())
    }

    @Test fun malformedProgressIsRejectedBeforePublication() {
        for (bad in listOf<() -> EncodedRecordingProgress>(
            { EncodedRecordingProgress(0, 0, null, null) }, { EncodedRecordingProgress(1, -1, null, null) },
            { EncodedRecordingProgress(1, 0, 0, 0) }, { EncodedRecordingProgress(1, 1, null, null) },
            { EncodedRecordingProgress(1, 1, 0, 1) }, { EncodedRecordingProgress(1, 2, 10, 9) })) {
            assertThrows(IllegalArgumentException::class.java) { bad() }
        }
    }

    @Test fun lateCompletionCannotRetireANewerTake() {
        val tracker = tracker(TimecodeMode.REGEN)
        val first = tracker.onRecordingStarted(1); tracker.observeEncodedProgress(progress(3))
        tracker.onRecordingStopped(true, first)
        val second = tracker.onRecordingStarted(2); tracker.observeEncodedProgress(progress(1, 2))
        assertNotEquals(first, second)
        tracker.onRecordingStopped(false, first); tracker.onRecordingStopped(true, first)
        assertEquals(second, tracker.recordingReport()!!.lifecycleToken)
        assertEquals("00:01:00;04", tracker.currentDisplayTc()!!.format())
        tracker.onRecordingStopped(true, second)
        tracker.onRecordingStarted(3); tracker.observeEncodedProgress(progress(1, 3))
        assertEquals("00:01:00;05", tracker.currentDisplayTc()!!.format())
    }
}
