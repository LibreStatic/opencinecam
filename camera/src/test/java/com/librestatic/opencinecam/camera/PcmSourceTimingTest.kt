/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class PcmSourceTimingTest {
    @Test fun unavailableTimestampsDoNotInventASourceEpoch() {
        val timing = PcmSourceTiming(48000, 2)
        timing.captured(960); timing.timestampUnavailable(); timing.written(960)
        val report = timing.report()
        assertNull(report.epoch); assertEquals(1L, report.unavailableTimestamps)
        assertEquals(480L, report.writtenFrames); assertEquals(10000000L, report.durationNs)
        timing.requireComplete(480)
    }
    @Test fun delayedFirstObservationMapsFrameZeroNotReadCompletion() {
        val timing = PcmSourceTiming(48000, 4)
        timing.captured(19200); timing.written(19200)
        timing.observeTimestamp(9600, 5200000000L)
        val report = timing.report()
        assertEquals(5000000000L, report.epoch!!.frameZeroNs)
        assertEquals(9600L, report.firstTimestampFrame)
        assertEquals(4800L, report.capturedFrames)
    }
    @Test fun laterObservationsMeasureResidualWithoutMovingAnchor() {
        val timing = PcmSourceTiming(48000, 2)
        timing.observeTimestamp(4800, 5100000000L)
        timing.observeTimestamp(9600, 5200000234L)
        val report = timing.report()
        assertEquals(5000000000L, report.epoch!!.frameZeroNs)
        assertEquals(234L, report.epoch!!.maxResidualNs)
        assertEquals(2L, report.timestampObservations)
        assertEquals(4800L, report.firstTimestampFrame); assertEquals(9600L, report.lastTimestampFrame)
    }
    @Test fun stereoFloatCountsInterleavedFramesAtRationalRate() {
        val timing = PcmSourceTiming(44100, 8)
        timing.captured(44101 * 8); timing.written(44101 * 8)
        assertEquals(44101L, timing.report().writtenFrames)
        assertEquals(1000022675L, timing.report().durationNs)
    }
    @Test fun packed24BitRejectsPartialInterleavedFramesWithoutCountingThem() {
        val timing = PcmSourceTiming(48000, 6)
        assertThrows(IllegalArgumentException::class.java) { timing.captured(7) }
        assertEquals(0L, timing.report().capturedFrames)
        timing.captured(12); timing.written(12); timing.requireComplete(2)
    }
    @Test fun unwrittenSourcePreventsSuccessfulPublication() {
        val timing = PcmSourceTiming(48000, 2)
        timing.captured(200); timing.written(100)
        assertThrows(IllegalStateException::class.java) { timing.requireComplete(50) }
        timing.written(100); timing.requireComplete(100)
        assertThrows(IllegalStateException::class.java) { timing.requireComplete(99) }
    }
    @Test fun outputCannotRunAheadOfCapture() {
        val timing = PcmSourceTiming(48000, 2)
        assertThrows(IllegalStateException::class.java) { timing.written(2) }
        assertEquals(0L, timing.report().writtenFrames)
    }
    @Test fun sourceTimestampRegressionRejectsTheTake() {
        val timing = PcmSourceTiming(48000, 2)
        timing.observeTimestamp(4800, 5100000000L)
        assertThrows(IllegalStateException::class.java) { timing.observeTimestamp(4799, 5200000000L) }
        assertEquals(1L, timing.report().timestampObservations)
    }
    @Test fun temporaryTimestampAbsencePreservesMeasuredEpoch() {
        val timing = PcmSourceTiming(48000, 2)
        timing.observeTimestamp(4800, 5100000000L); timing.timestampUnavailable()
        assertTrue(timing.report().epoch!!.timestampBacked)
        assertEquals(5000000000L, timing.report().epoch!!.frameZeroNs)
    }
    @Test fun invalidPcmGeometryFailsBeforeCapture() {
        assertThrows(IllegalArgumentException::class.java) { PcmSourceTiming(0, 2) }
        assertThrows(IllegalArgumentException::class.java) { PcmSourceTiming(48000, 0) }
        assertThrows(IllegalArgumentException::class.java) { PcmSourceTiming(48000, 33) }
    }
}
