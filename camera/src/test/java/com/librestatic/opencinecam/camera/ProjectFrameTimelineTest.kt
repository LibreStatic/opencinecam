/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera
import org.junit.Assert.*
import org.junit.Test
class ProjectFrameTimelineTest {
    @Test fun captureThirtyProjectTwentyFiveRetimesEveryRealImageWithoutDroppingFrames() {
        val timeline = ProjectFrameTimeline(CaptureFrameRate(25))
        val pts = (0L until 300).map { timeline.select(1_000_000_000L + it * 1_000_000_000L / 30) }
        assertEquals(300L, timeline.selectedFrames); assertEquals(0L, pts.first()); assertEquals(11_960_000_000L, pts.last())
    }
    @Test fun fractionalProjectClocksStayExactAcrossLongTakes() {
        for (n in listOf(24000,30000,60000)) {
            val clock = ProjectFrameTimeline(CaptureFrameRate(n,1001))
            for (i in 0L..60000) assertEquals(projectFrameTimestampNs(i,clock.rate),clock.select(10+i*1_000_000))
        }
    }
    @Test fun reorderedDuplicateAndInvalidSensorTimesDoNotInventProjectFrames() {
        val clock=ProjectFrameTimeline(CaptureFrameRate(30)); assertNull(clock.select(0)); assertNull(clock.select(-1))
        assertEquals(0L,clock.select(100)); assertNull(clock.select(100)); assertNull(clock.select(99))
        assertEquals(33_333_333L,clock.select(200)); assertEquals(2L,clock.selectedFrames)
    }
    @Test fun lateInputCreatesNoCatchUpDuplicatesAndEachTakeHasItsOwnEpoch() {
        val rate=CaptureFrameRate(24000,1001); val a=ProjectFrameTimeline(rate)
        a.select(1); assertEquals(41_708_333L,a.select(9_000_000_000)); assertEquals(2L,a.selectedFrames)
        assertEquals(0L,ProjectFrameTimeline(rate).select(9_000_000_000))
    }
    @Test fun rateBoundsAreValidatedBeforeEncoderWork() {
        assertThrows(IllegalArgumentException::class.java) { ProjectFrameTimeline(CaptureFrameRate(121)) }
        assertThrows(IllegalArgumentException::class.java) { ProjectFrameTimeline(CaptureFrameRate(1,2)) }
    }
}
