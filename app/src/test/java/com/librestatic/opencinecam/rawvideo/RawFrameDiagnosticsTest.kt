/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.rawvideo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFrameDiagnosticsTest {
    private val nominal60 = RawFrameDiagnosticsCalc.nominalFrameNs(60)

    /** Timestamps at [nominal] steps, with each gap index in [doubled] twice as long. */
    private fun timestamps(gaps: Int, nominal: Long, doubled: Set<Int> = emptySet(), tripled: Set<Int> = emptySet()): LongArray {
        val ts = LongArray(gaps + 1)
        ts[0] = 5_000_000_000L
        for (i in 0 until gaps) ts[i + 1] = ts[i] + nominal * when (i) { in tripled -> 3; in doubled -> 2; else -> 1 }
        return ts
    }

    @Test fun aCleanRunHasNoLateGaps() {
        val ts = timestamps(600, nominal60)
        val gaps = RawFrameDiagnosticsCalc.gaps(ts, ts.size, nominal60)
        assertEquals(600, gaps.gapCount)
        assertEquals(600, gaps.upTo1_25)
        assertEquals(0, gaps.late)
        assertTrue(gaps.firstLate.isEmpty())
        assertArrayEquals(IntArray(4), gaps.lateByQuarter)
    }

    @Test fun droppedFramesAreBinnedListedAndSpreadOverQuarters() {
        // 400 gaps; one 33 ms gap early, three in the last quarter, one triple.
        val ts = timestamps(400, nominal60, doubled = setOf(10, 350, 360, 370), tripled = setOf(200))
        val gaps = RawFrameDiagnosticsCalc.gaps(ts, ts.size, nominal60)
        assertEquals(400, gaps.gapCount)
        assertEquals(395, gaps.upTo1_25)
        assertEquals(4, gaps.upTo2_25)
        assertEquals(1, gaps.upTo3_25)
        assertEquals(0, gaps.larger)
        assertEquals(5, gaps.late)
        assertEquals(listOf(10, 200, 350, 360, 370), gaps.firstLate.map { it.index })
        assertEquals(2 * nominal60, gaps.firstLate[0].gapNs)
        assertEquals(12 * nominal60, gaps.firstLate[0].offsetNs)
        assertArrayEquals(intArrayOf(1, 0, 1, 3), gaps.lateByQuarter)
    }

    @Test fun onlyTheFirstLateGapsAreListedAndCountLimitsTheInput() {
        val many = (0 until 100 step 2).toSet()
        val ts = timestamps(100, nominal60, doubled = many)
        val gaps = RawFrameDiagnosticsCalc.gaps(ts, ts.size, nominal60)
        assertEquals(50, gaps.late)
        assertEquals(RawFrameDiagnosticsCalc.FIRST_LATE_LIMIT, gaps.firstLate.size)
        // Only the first 11 timestamps (10 gaps, 5 doubled) are read.
        assertEquals(5, RawFrameDiagnosticsCalc.gaps(ts, 11, nominal60).late)
        assertEquals(0, RawFrameDiagnosticsCalc.gaps(ts, 1, nominal60).gapCount)
    }

    @Test fun aGapJustPastOneAndAHalfFramesIsLateButStillInTheSecondBin() {
        val ts = longArrayOf(0, nominal60, nominal60 + nominal60 * 3 / 2 + 1, nominal60 * 4 + nominal60 * 3 / 2 + 1)
        val gaps = RawFrameDiagnosticsCalc.gaps(ts, ts.size, nominal60)
        assertEquals(1, gaps.upTo1_25)
        assertEquals(1, gaps.upTo2_25)
        assertEquals(1, gaps.upTo3_25)
        assertEquals(2, gaps.late)
    }

    @Test fun statsAndPercentilesOfTheCaptureResultsAndCopies() {
        val exposure = longArrayOf(8_000_000, 0, 20_000_000, 10_000_000)
        val iso = longArrayOf(400, 0, 800, 100)
        val duration = longArrayOf(16_666_666, 0, 33_333_333, 16_666_666)
        val copies = LongArray(100) { (it + 1) * 100_000L }
        val d = RawFrameDiagnosticsCalc.of(60, timestamps(4, nominal60), 5, exposure, iso, duration, 4, copies, 100,
            readerCpuNs = 500_000_000L, readerWallNs = 1_000_000_000L)
        assertEquals(4, d.captureResults)
        // Unreported (0) values are left out.
        assertEquals(RawFrameStat(8_000_000, 10_000_000, 20_000_000), d.exposureNs)
        assertEquals(RawFrameStat(100, 400, 800), d.sensitivity)
        assertEquals(RawFrameStat(16_666_666, 16_666_666, 33_333_333), d.frameDurationNs)
        assertEquals(1, d.exposureOverFrame)
        assertEquals(5_000_000L, d.copyP50Ns)
        assertEquals(9_900_000L, d.copyP99Ns)
        assertEquals(0.5, d.readerCpuCores!!, 1e-9)
    }

    @Test fun emptyInputsGiveNulls() {
        val d = RawFrameDiagnosticsCalc.of(30, LongArray(0), 0, LongArray(0), LongArray(0), LongArray(0), 0, LongArray(0), 0, null, null)
        assertNull(d.exposureNs)
        assertNull(d.copyP50Ns)
        assertNull(d.readerCpuCores)
        assertEquals(0, d.gaps.gapCount)
        assertEquals(0, d.exposureOverFrame)
    }
}
