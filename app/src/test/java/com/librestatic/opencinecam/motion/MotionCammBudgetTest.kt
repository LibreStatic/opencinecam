/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionCammBudgetTest {
    /** Packets of a take of [seconds] with gyroscope and accelerometer each filling every bin. */
    private fun take(seconds: Long): (Long) -> Long = { binUs -> 2 * seconds * 1_000_000L / binUs }

    @Test fun aTakeWithinBudgetKeepsMillisecondBins() {
        assertEquals(1_000L, MotionCammBudget.chooseBinUs(packetsAt = take(600)))
    }

    @Test fun aLongerTakeFallsBackToTheFinestBinThatFits() {
        assertEquals(2_000L, MotionCammBudget.chooseBinUs(packetsAt = take(601)))
        assertEquals(4_000L, MotionCammBudget.chooseBinUs(packetsAt = take(2_400)))
        assertEquals(16_000L, MotionCammBudget.chooseBinUs(packetsAt = take(9_600)))
    }

    @Test fun aTakeOverBudgetAtEveryBinGetsNoTrack() {
        assertNull(MotionCammBudget.chooseBinUs(packetsAt = take(9_601)))
    }

    @Test fun binsAreTriedFinestFirstAndStopAtTheFirstFit() {
        val asked = mutableListOf<Long>()
        val bin = MotionCammBudget.chooseBinUs(maxPackets = 10) { binUs ->
            asked += binUs
            if (binUs >= 4_000L) 10 else 11
        }
        assertEquals(4_000L, bin)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), asked)
    }

    @Test fun packetsCountEveryVectorAndExposure() {
        fun series(n: Int) = MappedSeries(LongArray(n) { it.toLong() }, FloatArray(n), FloatArray(n), FloatArray(n))
        val camm = CammSeries(series(5), series(3), series(1), longArrayOf(0, 10), intArrayOf(1, 2), intArrayOf(0, 0))
        assertEquals(11L, MotionCammBudget.packets(camm))
    }
}
