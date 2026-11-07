/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawQualificationLiveRateTest {
    /** Frames at [fps] from [startNs] for [seconds]; sensor timestamps start well above 0. */
    private fun steady(fps: Int, seconds: Double, startNs: Long = 5_000_000_000L): List<Long> =
        (0..(seconds * fps).toInt()).map { startNs + it * 1_000_000_000L / fps }

    private fun rate(ts: List<Long>, count: Int = ts.size, sinceLastNs: Long = 0L): Double? =
        RawQualificationLiveRate.fps(count, { ts[it] }, sinceLastNs = sinceLastNs)

    @Test fun aSteadyStreamReadsItsRate() {
        assertEquals(30.0, rate(steady(30, 5.0))!!, 0.01)
        assertEquals(24.0, rate(steady(24, 5.0))!!, 0.01)
        assertEquals(60.0, rate(steady(60, 5.0))!!, 0.01)
        // Shorter than the window: the frames there are.
        assertEquals(30.0, rate(steady(30, 0.5))!!, 0.01)
    }

    @Test fun fewerThanTwoFramesIsUnknown() {
        assertNull(rate(emptyList()))
        assertNull(rate(listOf(5_000_000_000L)))
        // Entries counted but not written yet (0) are left out.
        assertNull(rate(listOf(5_000_000_000L, 0L, 0L)))
        assertEquals(30.0, rate(steady(30, 1.0) + listOf(0L))!!, 0.01)
    }

    @Test fun onlyTheLastTwoSecondsCount() {
        // 15 fps for 10 s, then 30 fps for 3 s: the window holds only the 30 fps part.
        val slow = steady(15, 10.0)
        val fast = steady(30, 3.0, slow.last() + 1_000_000_000L / 30)
        assertEquals(30.0, rate(slow + fast)!!, 0.01)
    }

    @Test fun aGapInTheWindowLowersTheRate() {
        // 30 fps to 3 s, nothing for a second, 30 fps from 4 s to 4.5 s: the window from 2.5 s holds
        // 16 + 16 frames, 31 intervals over 2 s.
        val before = (0..90).map { it * 1_000_000_000L / 30 + 1L }
        val after = (0..15).map { 4_000_000_000L + it * 1_000_000_000L / 30 + 1L }
        assertEquals(15.5, rate(before + after)!!, 0.01)
    }

    @Test fun aStalledCameraReadsLowerThenZero() {
        val ts = steady(30, 3.0)
        // Arrival jitter within one frame interval is no stall.
        assertEquals(30.0, rate(ts, sinceLastNs = 30_000_000L)!!, 0.01)
        // A second without frames: about half the window is empty.
        val stalled = rate(ts, sinceLastNs = 1_000_000_000L)!!
        assertTrue("stalled rate $stalled", stalled in 14.0..16.5)
        // Longer than the window: nothing in it.
        assertEquals(0.0, rate(ts, sinceLastNs = 3_000_000_000L)!!, 0.0)
    }
}
