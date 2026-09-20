/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import org.junit.Assert.*
import org.junit.Test

class VideoFrameTimelineTest {
    @Test fun presentationOrderIsIndependentOfDecodeOrderAndDoesNotAssumeFixedFps() {
        val timeline = VideoFrameTimeline(listOf(0L, 100_000L, 33_333L, 170_000L))
        assertEquals(listOf(0L, 33_333L, 100_000L, 170_000L), timeline.timestampsUs)
        assertEquals(0, timeline.indexAt(Long.MIN_VALUE))
        assertEquals(0, timeline.indexAt(33_332L))
        assertEquals(1, timeline.indexAt(33_333L))
        assertEquals(1, timeline.indexAt(99_999L))
        assertEquals(2, timeline.indexAt(100_000L))
        assertEquals(3, timeline.indexAt(Long.MAX_VALUE))
    }
    @Test fun steppingClampsWithoutIntegerOverflowAndRejectsInvalidCurrentIndex() {
        val timeline = VideoFrameTimeline(listOf(123L, 321L, 678L))
        assertEquals(0, timeline.step(0, -1))
        assertEquals(1, timeline.step(0, 1))
        assertEquals(1, timeline.step(2, -1))
        assertEquals(2, timeline.step(2, 1))
        assertEquals(2, timeline.step(2, Int.MAX_VALUE))
        assertEquals(0, timeline.step(2, Int.MIN_VALUE))
        assertThrows(IllegalArgumentException::class.java) { timeline.step(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { timeline.step(3, -1) }
    }
    @Test fun emptyNegativeAndDuplicateTimestampsAreExplicitErrors() {
        assertThrows(IllegalArgumentException::class.java) { VideoFrameTimeline(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { VideoFrameTimeline(listOf(-1L)) }
        assertThrows(IllegalArgumentException::class.java) { VideoFrameTimeline(listOf(7L, 2L, 7L)) }
    }
    @Test fun indexCopiesInputAndExposesAnUnmodifiableList() {
        val values = mutableListOf(3L, 1L)
        val timeline = VideoFrameTimeline(values)
        values.clear()
        assertEquals(listOf(1L, 3L), timeline.timestampsUs)
        assertThrows(UnsupportedOperationException::class.java) { (timeline.timestampsUs as MutableList<Long>).clear() }
    }
    @Test fun singleFrameFloorAndStepsStayAtTheOnlyRealTimestamp() {
        val timeline = VideoFrameTimeline(listOf(9_007_199_254_740_993L))
        assertEquals(0, timeline.indexAt(0))
        assertEquals(0, timeline.indexAt(Long.MAX_VALUE))
        assertEquals(0, timeline.step(0, Int.MAX_VALUE))
        assertEquals(9_007_199_254_740_993L, timeline.timestampsUs.single())
    }
    @Test fun iteratorStopsAtTheExplicitIndexBoundInsteadOfMaterializingAnInfiniteLibrary() {
        var count = 0
        val infinite = Iterable { object : Iterator<Long> {
            override fun hasNext() = true
            override fun next() = (++count).toLong()
        } }
        assertThrows(IllegalArgumentException::class.java) { VideoFrameTimeline(infinite) }
        assertEquals(VideoFrameTimeline.MAX_FRAMES + 1, count)
        assertEquals(VideoFrameTimeline.MAX_FRAMES,
            VideoFrameTimeline((0 until VideoFrameTimeline.MAX_FRAMES).map(Int::toLong)).timestampsUs.size)
    }
}
