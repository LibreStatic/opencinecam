/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class TimelapseTimelineTest {
    @Test fun pauseDropsSourceImagesAndResumeStartsOneNewIntervalWithoutMissedSlots() {
        val clock = TimelapseTimeline(TimelapseCapture(100_000_000, CaptureFrameRate(30000,1001)))
        assertEquals(0L, clock.select(100)); assertTrue(clock.setPaused(true))
        assertNull(clock.select(20_000_000_100)); assertEquals(1L,clock.selectedFrames)
        assertTrue(clock.setPaused(false))
        assertEquals(projectFrameTimestampNs(1,clock.capture.projectRate),clock.select(30_000_000_100))
        assertNull(clock.select(30_050_000_100))
        assertEquals(projectFrameTimestampNs(2,clock.capture.projectRate),clock.select(30_100_000_100))
        assertEquals(0L,clock.missedIntervals)
    }
    @Test fun repeatedPauseRetainsRealMissesButDoesNotConsumeFrameLimit() {
        val clock = TimelapseTimeline(TimelapseCapture(100_000_000, CaptureFrameRate(30),3))
        clock.select(100); clock.select(900_000_100); assertEquals(8L,clock.missedIntervals)
        clock.setPaused(true);assertNull(clock.select(1_000_000_100));assertFalse(clock.complete)
        clock.setPaused(false);assertNotNull(clock.select(99_000_000_100))
        assertEquals(8L,clock.missedIntervals); assertTrue(clock.complete); assertFalse(clock.setPaused(true))
    }
    @Test fun resumeDoesNotAcceptOldOrDuplicateSensorImages() {
        val clock = TimelapseTimeline(TimelapseCapture(100_000_000, CaptureFrameRate(30)))
        clock.select(100);clock.setPaused(true);clock.setPaused(false)
        assertNull(clock.select(100));assertNull(clock.select(99));assertNull(clock.select(0))
        assertNotNull(clock.select(101));assertEquals(0L,clock.missedIntervals)
    }
    @Test fun imagesAlreadyObservedDuringPauseCannotBecomeTheResumeFrame() {
        val clock = TimelapseTimeline(TimelapseCapture(100_000_000, CaptureFrameRate(30)))
        clock.select(100);clock.setPaused(true);assertNull(clock.select(1_000_000_100))
        clock.setPaused(false)
        assertNull(clock.select(1_000_000_100));assertNull(clock.select(900_000_100))
        assertNotNull(clock.select(1_000_000_101));assertEquals(2L,clock.selectedFrames)
    }
    @Test fun denseSourceFramesAreSelectedBeforeEncodingAtTheCaptureInterval() {
        val clock = TimelapseTimeline(TimelapseCapture(500_000_000, CaptureFrameRate(30)))
        val samples = (0..300).mapNotNull { clock.select(1_000_000_000L + it * 10_000_000L) }
        assertEquals(7, samples.size)
        assertEquals(listOf(0L, 33_333_333L, 66_666_666L, 100_000_000L, 133_333_333L, 166_666_666L, 200_000_000L), samples)
        assertEquals(0L, clock.missedIntervals)
    }
    @Test fun lateFramesNeverDuplicateAnImageToCatchUp() {
        val clock = TimelapseTimeline(TimelapseCapture(100_000_000, CaptureFrameRate(25)))
        assertEquals(0L, clock.select(1_000_000_000))
        assertEquals(40_000_000L, clock.select(1_950_000_000))
        assertEquals(8L, clock.missedIntervals)
        assertNull(clock.select(1_951_000_000))
        assertEquals(80_000_000L, clock.select(2_000_000_000))
    }
    @Test fun duplicateZeroAndReorderedSensorTimestampsDoNotAdvanceTheTimeline() {
        val clock = TimelapseTimeline(TimelapseCapture(100_000_000, CaptureFrameRate(30)))
        assertNull(clock.select(0)); assertNull(clock.select(-1)); assertEquals(0L, clock.select(10))
        assertNull(clock.select(10)); assertNull(clock.select(9)); assertEquals(1L, clock.selectedFrames)
    }
    @Test fun frameLimitStopsOnSubmittedSelectionCountNotElapsedTime() {
        val clock = TimelapseTimeline(TimelapseCapture(100_000_000, CaptureFrameRate(30), 3))
        for (i in 0..2) assertNotNull(clock.select(100L + i * 1_000_000_000L))
        assertTrue(clock.complete); assertNull(clock.select(Long.MAX_VALUE)); assertEquals(3L, clock.selectedFrames)
        assertEquals(18L, clock.missedIntervals)
    }
    @Test fun fractionalProjectRateHasNoCumulativeRoundingDrift() {
        val rate = CaptureFrameRate(30_000, 1001)
        assertEquals(1_001_000_000_000L, projectFrameTimestampNs(30_000, rate))
        assertEquals(28_828_800_000_000L, projectFrameTimestampNs(864_000, rate))
        val steps = (1L..300L).map { projectFrameTimestampNs(it, rate) - projectFrameTimestampNs(it - 1, rate) }.toSet()
        assertEquals(setOf(33_366_666L, 33_366_667L), steps)
    }
    @Test fun clocksRestartAtZeroForEveryTakeAndDoNotShareSensorOrigins() {
        val capture = TimelapseCapture(100_000_000, CaptureFrameRate(24))
        assertEquals(0L, TimelapseTimeline(capture).select(999_000_000))
        assertEquals(0L, TimelapseTimeline(capture).select(12_000_000_000))
    }
    @Test fun invalidIntervalsRatesAndLimitsFailBeforeARecordingStarts() {
        assertThrows(IllegalArgumentException::class.java) { TimelapseCapture(1, CaptureFrameRate(30)) }
        assertThrows(IllegalArgumentException::class.java) { TimelapseCapture(100_000_000, CaptureFrameRate(121)) }
        assertThrows(IllegalArgumentException::class.java) { TimelapseCapture(100_000_000, CaptureFrameRate(30), 1) }
        assertThrows(ArithmeticException::class.java) { projectFrameTimestampNs(Long.MAX_VALUE, CaptureFrameRate(1)) }
    }
}
