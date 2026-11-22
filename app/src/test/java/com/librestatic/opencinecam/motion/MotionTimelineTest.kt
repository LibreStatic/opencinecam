/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionTimelineTest {
    private val frameNs = 33_333_333L

    /** [count] frames at 30 fps captured from [startNs], presented from [ptsStartNs]. */
    private fun frames(count: Int, startNs: Long, ptsStartNs: Long, captureStepNs: Long = frameNs) =
        LongArray(count) { startNs + it * captureStepNs } to LongArray(count) { ptsStartNs + it * frameNs }

    @Test fun identityTakeIsRebasedToTheFirstFrameAndOffset() {
        val (sensor, pts) = frames(30, 1_000_000_000L, 5_000_000_000L)
        val timeline = MotionTimeline.fromFrames(sensor, pts, fileOffsetUs = 2_000, clockOffsetNs = 0)
        assertEquals(30, timeline.anchorCount)
        assertEquals(2_000L, timeline.fileUs(1_000_000_000L))
        assertEquals(12_000L, timeline.fileUs(1_010_000_999L))
        assertEquals(2_000L + 166_666L, timeline.fileUs(sensor[5]))
        assertEquals(1.0, timeline.rateScale(1_500_000_000L)!!, 0.0)
        assertEquals(1.0, timeline.globalRateScale, 0.0)
    }

    @Test fun pauseFitsItsWholeSensorSpanIntoOneFrameInterval() {
        val (before, beforePts) = frames(30, 1_000_000_000L, 0L)
        val resume = before.last() + 3_000_000_000L
        val sensor = before + LongArray(30) { resume + it * frameNs }
        val pts = beforePts + LongArray(30) { beforePts.last() + (it + 1) * frameNs }
        val timeline = MotionTimeline.fromFrames(sensor, pts, fileOffsetUs = 0, clockOffsetNs = 0)

        val pauseStartUs = timeline.fileUs(before.last())!!
        assertEquals(966_666L, pauseStartUs)
        assertEquals(983_333L, timeline.fileUs(before.last() + 1_500_000_000L))
        assertEquals(pauseStartUs + 33_333L, timeline.fileUs(resume))
        assertEquals(3_000_000_000.0 / frameNs, timeline.rateScale(before.last() + 1)!!, 1e-9)
        assertEquals(1.0, timeline.rateScale(resume)!!, 0.0)
        assertEquals("one pause segment does not move the median", 1.0, timeline.globalRateScale, 0.0)
    }

    @Test fun timeLapseMapsOneSecondIntervalsToThirtyFps() {
        val (sensor, pts) = frames(10, 0L, 0L, captureStepNs = 1_000_000_000L)
        val timeline = MotionTimeline.fromFrames(sensor, pts, fileOffsetUs = 0, clockOffsetNs = 0)
        assertEquals(16_666L, timeline.fileUs(500_000_000L))
        assertEquals(33_333L, timeline.fileUs(1_000_000_000L))
        assertEquals(1e9 / frameNs, timeline.rateScale(2_000_000_000L)!!, 1e-9)
        assertEquals(1e9 / frameNs, timeline.globalRateScale, 1e-9)
    }

    @Test fun slowMotionFromFirstAndLastFrame() {
        val timeline = MotionTimeline.linear(0L, 0L, 1_000_000_000L, 8_000_000L, clockOffsetNs = 0)
        assertEquals(4_000_000L, timeline.fileUs(500_000_000L))
        assertEquals(0.125, timeline.rateScale(1L)!!, 0.0)
        assertEquals(0.125, timeline.globalRateScale, 0.0)
        // Extrapolation past the last frame keeps the slope for one last-segment span.
        assertEquals(12_000_000L, timeline.fileUs(1_500_000_000L))
        assertEquals(0.125, timeline.rateScale(1_500_000_000L)!!, 0.0)
    }

    @Test fun clockOffsetMovesCameraTimesOntoTheSensorClock() {
        val (camera, pts) = frames(3, 1_000_000_000L, 0L)
        val timeline = MotionTimeline.fromFrames(camera, pts, fileOffsetUs = 0, clockOffsetNs = 250_000L)
        assertNull(timeline.fileUs(camera[0]))
        assertEquals(0L, timeline.fileUs(camera[0] + 250_000L))
        assertEquals(33_333L, timeline.fileUs(camera[1] + 250_000L))
        assertEquals(250_000L, timeline.clockOffsetNs)
    }

    @Test fun beforeFirstIsNullAndAfterLastExtrapolatesOneFrame() {
        val (sensor, pts) = frames(3, 1_000_000_000L, 0L)
        val timeline = MotionTimeline.fromFrames(sensor, pts, fileOffsetUs = 0, clockOffsetNs = 0)
        assertNull(timeline.fileUs(sensor[0] - 1))
        assertNull(timeline.rateScale(sensor[0] - 1))
        assertEquals(66_666L, timeline.fileUs(sensor[2]))
        assertEquals(99_999L, timeline.fileUs(sensor[2] + frameNs - 1))
        assertNull(timeline.fileUs(sensor[2] + frameNs))
    }

    @Test fun singleAnchorHasSlopeOneForOneSecond() {
        val timeline = MotionTimeline.fromFrames(longArrayOf(1_000L), longArrayOf(77L), fileOffsetUs = 10, clockOffsetNs = 0)
        assertEquals(10L, timeline.fileUs(1_000L))
        assertEquals(10L + 999_999L, timeline.fileUs(1_000L + 999_999_999L))
        assertNull(timeline.fileUs(1_000L + 1_000_000_000L))
        assertEquals(1.0, timeline.rateScale(5_000L)!!, 0.0)
        assertEquals(1.0, timeline.globalRateScale, 0.0)
    }

    @Test fun noAnchorMapsNothing() {
        val timeline = MotionTimeline.fromFrames(LongArray(0), LongArray(0), fileOffsetUs = 0, clockOffsetNs = 0)
        assertEquals(0, timeline.anchorCount)
        assertNull(timeline.fileUs(0L))
        assertEquals(1.0, timeline.globalRateScale, 0.0)
    }

    @Test fun nonMonotonicAnchorsAreDropped() {
        val timeline = MotionTimeline.fromAnchors(
            sensorNs = longArrayOf(0L, 1_000_000L, 1_000_000L, 500_000L, 2_000_000L, 3_000_000L, 4_000_000L),
            fileUs = longArrayOf(0L, 1_000L, 1_500L, 1_200L, 900L, 3_000L, 4_000L),
            clockOffsetNs = 0,
        )
        // Kept: (0, 0), (1 ms, 1 ms), (3 ms, 3 ms), (4 ms, 4 ms).
        assertEquals(4, timeline.anchorCount)
        assertEquals(2_000L, timeline.fileUs(2_000_000L))
        assertEquals(1.0, timeline.rateScale(2_000_000L)!!, 0.0)
    }

    @Test fun medianOfEvenSegmentsAveragesTheMiddleTwo() {
        val timeline = MotionTimeline.fromAnchors(
            sensorNs = longArrayOf(0L, 1_000L, 3_000L, 6_000L, 10_000L),
            fileUs = longArrayOf(0L, 1L, 2L, 3L, 4L),
            clockOffsetNs = 0,
        )
        assertEquals(2.5, timeline.globalRateScale, 1e-12)
    }
}
