/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera
import org.junit.Assert.*
import org.junit.Test

class TimecodeContinuationTest {
    private class Memory : TimecodeContinuationStore {
        var value: TimecodeContinuation? = null; var saves = 0; var failLoad = false; var failSave = false
        override fun load(): TimecodeContinuation? { if (failLoad) error("read failed"); return value }
        override fun save(value: TimecodeContinuation) { if (failSave) error("write failed"); this.value = value; saves++ }
        override fun clear() { value = null }
    }
    private val rate = TimecodeRate(30, true)
    private val start = SmpteTimecode(0, 0, 59, 29, true)
    private fun tracker(store: Memory, mode: TimecodeMode = TimecodeMode.RECORD_RUN, remember: Boolean = true) = TimecodeTracker(store) { 0 }.apply {
        configure(rate, mode, start, true, remember)
    }
    private fun take(tracker: TimecodeTracker, success: Boolean = true) {
        val token = tracker.onRecordingStarted(1)
        tracker.observeEncodedProgress(EncodedRecordingProgress(1, 3, 0, 66666))
        tracker.onRecordingStopped(success, token)
    }
    private fun next(tracker: TimecodeTracker): String {
        tracker.onRecordingStarted(2); tracker.observeEncodedProgress(EncodedRecordingProgress(2, 1, 0, 0))
        return requireNotNull(tracker.currentDisplayTc()).format()
    }
    @Test fun completedRecordRunAndRegenResumeInNewTracker() {
        for (mode in listOf(TimecodeMode.RECORD_RUN, TimecodeMode.REGEN)) {
            val store = Memory(); take(tracker(store, mode))
            assertEquals("00:01:00;04", next(tracker(store, mode)))
        }
    }
    @Test fun failedPublicationPersistsConsumptionOnlyForRecordRun() {
        for (mode in listOf(TimecodeMode.RECORD_RUN, TimecodeMode.REGEN)) {
            val store = Memory(); take(tracker(store, mode), false)
            assertEquals(if (mode == TimecodeMode.RECORD_RUN) "00:01:00;04" else "00:00:59;29", next(tracker(store, mode)))
        }
    }
    @Test fun changingConfigurationDoesNotReuseAnIncompatibleCursor() {
        val store = Memory(); take(tracker(store))
        val restored = TimecodeTracker(store) { 0 }
        restored.configure(TimecodeRate(24), TimecodeMode.RECORD_RUN, SmpteTimecode(2, 0, 0, 0), true)
        assertEquals("02:00:00:00", next(restored))
    }
    @Test fun enabledToggleDoesNotInvalidateCompletedPosition() {
        val store = Memory(); val tracker = tracker(store); take(tracker)
        tracker.configure(rate, TimecodeMode.RECORD_RUN, start, false)
        assertEquals("00:01:00;04", next(tracker(store)))
    }
    @Test fun disablingRememberClearsStorageAndRestartsNextTake() {
        val store = Memory(); val tracker = tracker(store); take(tracker)
        tracker.configure(rate, TimecodeMode.RECORD_RUN, start, true, rememberPosition = false)
        assertNull(store.value); assertEquals("00:00:59;29", next(tracker))
        tracker.onRecordingStopped(true); assertNull(store.value)
        assertEquals("00:00:59;29", next(tracker(store)))
    }
    @Test fun resetRevisionCanRestartAtTheSameLabelAfterRecording() {
        val store = Memory(); val tracker = tracker(store); take(tracker)
        tracker.configure(rate, TimecodeMode.RECORD_RUN, start, true, resetRevision = 1)
        assertEquals("00:00:59;29", next(tracker))
    }
    @Test fun resetDuringRecIsDeferredAndCannotRelabelExistingFrames() {
        val store = Memory(); val tracker = tracker(store)
        tracker.onRecordingStarted(1); tracker.observeEncodedProgress(EncodedRecordingProgress(1, 3, 0, 66666))
        val savedBefore = store.value
        tracker.configure(rate, TimecodeMode.RECORD_RUN, start, true, resetRevision = 1)
        assertEquals(savedBefore, store.value); assertEquals("00:01:00;03", tracker.currentDisplayTc()!!.format())
        tracker.onRecordingStopped(true); assertEquals("00:00:59;29", next(tracker))
    }
    @Test fun readOrWriteFailureIsObservableWithoutBreakingInMemoryRecording() {
        for (read in listOf(true, false)) {
            val store = Memory().apply { failLoad = read; failSave = !read }
            val tracker = tracker(store); assertTrue(tracker.continuationStorageFailed)
            take(tracker); assertEquals("00:01:00;04", next(tracker))
        }
    }
    @Test fun lateCompletionDoesNotWriteOverNewerState() {
        val store = Memory(); val tracker = tracker(store); val old = tracker.onRecordingStarted(1)
        tracker.observeEncodedProgress(EncodedRecordingProgress(1, 3, 0, 66666)); tracker.onRecordingStopped(true, old)
        val token = tracker.onRecordingStarted(2); val writes = store.saves
        tracker.onRecordingStopped(false, old); assertEquals(writes, store.saves)
        assertEquals(token, tracker.recordingReport()!!.lifecycleToken)
    }
    @Test fun invalidContinuationCannotCreateOutOfDayCursors() {
        val config = TimecodeConfig(rate = rate, startValue = start)
        for (cursor in listOf(-1L, rate.framesPerDay, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { TimecodeContinuation(config, cursor, null) }
            assertThrows(IllegalArgumentException::class.java) { TimecodeContinuation(config, 0, cursor) }
        }
    }
    @Test fun freeRunRecreationRestartsReceiptClockRatherThanClaimingAStoredEpoch() {
        val store = Memory(); var now = 0L; val first = TimecodeTracker(store) { now }
        first.configure(rate, TimecodeMode.FREE_RUN, start, true); now = 10_000_000_000L
        assertNotEquals(start, first.currentDisplayTc())
        val second = TimecodeTracker(store) { now }; second.configure(rate, TimecodeMode.FREE_RUN, start, true)
        assertEquals(start, second.currentDisplayTc())
    }
}
