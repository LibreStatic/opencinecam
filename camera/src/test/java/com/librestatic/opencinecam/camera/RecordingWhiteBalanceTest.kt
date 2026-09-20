/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class RecordingWhiteBalanceTest {
    private fun RecordingWhiteBalanceGate.frame(token: Long, now: Long = 1, requested: Boolean = false,
        reported: Boolean? = false, state: Int? = 2, timestamp: Long? = if (requested) 124 else 123, auto: Boolean = true) =
        observe(token, now, auto, auto, requested, reported, state, timestamp)

    @Test fun convergedThenConfirmedLockEstablishesSensorBoundary() {
        val gate = RecordingWhiteBalanceGate(); val token = gate.begin(0)
        assertEquals(RecordingWhiteBalanceStatus.LOCKING, gate.frame(token))
        assertTrue(gate.lockRequested)
        assertEquals(RecordingWhiteBalanceStatus.LOCKED, gate.frame(token, requested = true, reported = true, state = 3))
        assertEquals(124L, gate.minimumSensorTimestampNs)
    }
    @Test fun submittedLockUnknownResultAndWrongAwbModeNeverConfirm() {
        val gate = RecordingWhiteBalanceGate(); val token = gate.begin(0); gate.frame(token)
        for (reported in listOf(null, false)) assertEquals(RecordingWhiteBalanceStatus.LOCKING, gate.frame(token, requested = true, reported = reported, state = 3))
        assertEquals(RecordingWhiteBalanceStatus.LOCKING, gate.frame(token, requested = false, reported = true, state = 3))
        assertEquals(RecordingWhiteBalanceStatus.LOCKING, gate.frame(token, requested = true, reported = true, state = 3, auto = false))
        assertNull(gate.minimumSensorTimestampNs)
    }
    @Test fun searchingAndUnknownDoNotLock() {
        val gate = RecordingWhiteBalanceGate(); val token = gate.begin(0)
        for (state in listOf(null, 0, 1, 3)) assertEquals(RecordingWhiteBalanceStatus.CONVERGING, gate.frame(token, state = state))
        assertFalse(gate.lockRequested)
    }
    @Test fun timestampMustBePositiveAndPresent() {
        val gate = RecordingWhiteBalanceGate(); val token = gate.begin(0); gate.frame(token)
        for (stamp in listOf(null, -1L, 0L)) assertEquals(RecordingWhiteBalanceStatus.LOCKING, gate.frame(token, requested = true, reported = true, state = 3, timestamp = stamp))
    }
    @Test fun timeoutBoundaryWinsOverLateConfirmation() {
        val gate = RecordingWhiteBalanceGate(); val token = gate.begin(0); gate.frame(token)
        assertEquals(RecordingWhiteBalanceStatus.FAILED, gate.frame(token, now = 3000, requested = true, reported = true, state = 3))
        assertNull(gate.minimumSensorTimestampNs)
        assertFalse(gate.lockRequested)
    }
    @Test fun staleGenerationAndCancellationCannotCompleteNewTake() {
        val gate = RecordingWhiteBalanceGate(); val old = gate.begin(0); gate.frame(old)
        gate.reset(); val next = gate.begin(100)
        gate.frame(old, now = 101, requested = true, reported = true, state = 3)
        assertFalse(gate.expire(old, 10000))
        assertEquals(RecordingWhiteBalanceStatus.CONVERGING, gate.status)
        assertEquals(RecordingWhiteBalanceStatus.LOCKING, gate.frame(next, now = 101))
        gate.reset(); assertEquals(RecordingWhiteBalanceStatus.IDLE, gate.status)
    }
    @Test fun resetClearsHeldBoundaryAndClockRollbackExpires() {
        val gate = RecordingWhiteBalanceGate(); val token = gate.begin(100)
        assertTrue(gate.expire(token, 99)); gate.reset()
        assertNull(gate.minimumSensorTimestampNs); assertFalse(gate.lockRequested)
    }
    @Test fun lockResultMustBeNewerThanTheConvergedUnlockedFrame() {
        val gate = RecordingWhiteBalanceGate(); val token = gate.begin(0)
        assertEquals(RecordingWhiteBalanceStatus.CONVERGING, gate.frame(token, timestamp = null))
        gate.frame(token, timestamp = 200)
        for (stamp in listOf(100L, 199L, 200L)) assertEquals(RecordingWhiteBalanceStatus.LOCKING,
            gate.frame(token, requested = true, reported = true, state = 3, timestamp = stamp))
        assertEquals(RecordingWhiteBalanceStatus.LOCKED, gate.frame(token, requested = true, reported = true, state = 3, timestamp = 201))
    }

    @Test fun fixedWbAlsoWaitsForMatchingResultAndFreshTimestampWithoutLockingAuto() {
        val gate = RecordingWhiteBalanceGate(); val token = gate.begin(0)
        assertEquals(RecordingWhiteBalanceStatus.CONVERGING, gate.observeFixed(token, 1, false, 100))
        assertEquals(RecordingWhiteBalanceStatus.CONVERGING, gate.observeFixed(token, 2, true, null))
        assertEquals(RecordingWhiteBalanceStatus.FIXED, gate.observeFixed(token, 3, true, 123))
        assertEquals(123L, gate.minimumSensorTimestampNs)
        assertFalse(gate.lockRequested)
        assertFalse(gate.expire(token, 4000))
    }

    @Test fun queuedGpuFramesBeforeLockAreRejectedButMonitoringCanContinue() {
        assertFalse(recordingFrameMeetsWhiteBalanceBoundary(99, 100))
        assertFalse(recordingFrameMeetsWhiteBalanceBoundary(0, 100))
        assertTrue(recordingFrameMeetsWhiteBalanceBoundary(100, 100))
        assertTrue(recordingFrameMeetsWhiteBalanceBoundary(101, 100))
        assertTrue(recordingFrameMeetsWhiteBalanceBoundary(99, null))
    }
}
