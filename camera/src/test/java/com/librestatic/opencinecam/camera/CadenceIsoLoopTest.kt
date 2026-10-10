/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceIsoLoopTest {
    private val ms = 1_000_000L
    private val range = 50..20_000

    /** Engaged with no settle window, the target already learned from three held frames at [luma]. */
    private fun engaged(luma: Double = 0.30, iso: Int = 10_000, loop: CadenceIsoLoop = CadenceIsoLoop(settleNs = 0)) = loop.also {
        it.engage(iso, range)
        repeat(3) { _ -> assertNull(it.update(0, luma)) }
    }

    @Test fun noTargetMeansTheIsoStaysFixed() {
        val loop = CadenceIsoLoop()
        loop.engage(10_000, range)
        assertFalse(loop.hasTarget)
        assertNull(loop.update(0, 0.05))
        assertNull(loop.update(1000 * ms, 0.9))
    }

    @Test fun noISOChangeBeforeThreeSettledFrames() {
        val loop = CadenceIsoLoop(settleNs = 0)
        loop.engage(10_000, range)
        assertNull(loop.update(0, 0.05))
        assertNull(loop.update(200 * ms, 0.90))
        assertFalse(loop.hasTarget)
        assertNull(loop.update(400 * ms, 0.20))   // third frame: the target is learned, still no change
        assertTrue(loop.hasTarget)
    }

    @Test fun theTargetIsTheMedianOfTheFirstThreeSettledFrames() {
        val loop = CadenceIsoLoop(settleNs = 0, minIntervalNs = 0)
        loop.engage(10_000, range)
        loop.update(0, 0.20); loop.update(1, 0.0003); loop.update(2, 0.21)   // 0.0003 is below the valid floor
        loop.update(3, 0.90)
        // Median of 0.20, 0.21, 0.90 is 0.21: a frame at 0.21 is on target.
        assertNull(loop.update(200 * ms, 0.21))
        assertEquals(11_800, loop.update(400 * ms, 0.21 / 2))
    }

    @Test fun anOutlierAmongTheLearningFramesIsRejected() {
        val loop = CadenceIsoLoop(settleNs = 0)
        loop.engage(5000, range)
        loop.update(0, 0.20); loop.update(1, 0.02); loop.update(2, 0.21)
        assertNull(loop.update(3, 0.20))   // target 0.20, not dragged by 0.02
    }

    @Test fun framesInsideTheSettleWindowDoNotCount() {
        val loop = CadenceIsoLoop().also { it.engage(10_000, range, nowNs = 1000 * ms) }
        repeat(5) { loop.update(1000 * ms + it * 50 * ms, 0.80) }
        assertFalse(loop.hasTarget)
        loop.update(1400 * ms, 0.20); loop.update(1450 * ms, 0.20); loop.update(1500 * ms, 0.20)
        assertTrue(loop.hasTarget)
        assertNull(loop.update(1550 * ms, 0.20))
    }

    @Test fun aDarkPreEngageHistoryNoLongerMatters() {
        // AE was still converging before the hold: 0.026, 0.0003, 0.050. The held frames read ~0.2
        // (initialIso preserved the brightness), so the loop leaves the ISO alone.
        val loop = CadenceIsoLoop(settleNs = 0)
        loop.engage(12_800, range)
        listOf(0.2, 0.19, 0.21, 0.2, 0.2, 0.21).forEachIndexed { i, l -> assertNull("$i", loop.update(i * 200 * ms, l)) }
    }

    @Test fun deadbandAroundTheTarget() {
        val loop = engaged()
        assertNull(loop.update(0, 0.30 * 1.05))
        assertNull(loop.update(200 * ms, 0.30 / 1.05))
    }

    @Test fun darkerFrameRaisesTheIsoByAtMostTheStepLimit() {
        val loop = engaged()
        // ratio 2.0 clamps to 1.18
        assertEquals(11_800, loop.update(0, 0.15))
    }

    @Test fun brighterFrameLowersTheIsoByAtMostTheStepLimit() {
        val loop = engaged()
        // ratio 0.5 clamps to 0.85
        assertEquals(8_500, loop.update(0, 0.60))
    }

    @Test fun smallErrorsMoveByTheirRatio() {
        val loop = engaged()
        assertEquals(10_900, loop.update(0, 0.30 / 1.09))
    }

    @Test fun reissuesOnlyOnAThreePercentChange() {
        val loop = CadenceIsoLoop(deadband = 0.0, settleNs = 0).also { it.engage(10_000, range); repeat(3) { _ -> it.update(0, 0.30) } }
        assertNull(loop.update(0, 0.30 / 1.02))            // 10200: 2 % away from the issued 10000
        assertEquals(10_404, loop.update(200 * ms, 0.30 / 1.02))   // 10200 * 1.02: 4 % away
    }

    @Test fun rateLimitedToOneReissuePer150Ms() {
        val loop = engaged()
        assertNotNull(loop.update(0, 0.15))
        assertNull(loop.update(100 * ms, 0.15))
        assertNotNull(loop.update(150 * ms, 0.15))
    }

    @Test fun neverLeavesTheSensitivityRange() {
        val loop = engaged(iso = 19_000)
        var t = 0L
        var last = 0
        repeat(40) { loop.update(t, 0.01)?.let { last = it }; t += 200 * ms }
        assertEquals(20_000, last)
        val low = engaged(iso = 80)
        t = 0; last = 80
        repeat(60) { low.update(t, 0.95)?.let { last = it }; t += 200 * ms }
        assertEquals(50, last)
    }

    @Test fun exposureCompensationScalesTheTarget() {
        val loop = engaged(luma = 0.30)
        // +1 EV: the target doubles to 0.60; a frame at 0.30 is now too dark.
        assertEquals(11_800, loop.update(0, 0.30, targetScale = 2.0))
    }

    @Test fun invalidFramesAreIgnored() {
        val loop = engaged()
        assertNull(loop.update(0, Double.NaN))
        assertNull(loop.update(0, 0.0))
        assertNull(loop.update(0, 5.0))
    }

    @Test fun releaseDropsTheTargetAndResetForgetsTheLastLuma() {
        val loop = engaged()
        loop.release()
        assertFalse(loop.hasTarget)
        loop.reset()
        loop.engage(5000, range)
        assertFalse(loop.hasTarget)
    }

    @Test fun initialIsoKeepsTheAutoExposure() {
        // ISO 12000 at 30 ms held to 16.57 ms: about 21730.
        val iso = CadenceIsoLoop.initialIso(12_000, 30_000_000L, 16_566_667L, 50..30_000)
        assertTrue("$iso", iso in 21_725..21_735)
        assertEquals(20_000, CadenceIsoLoop.initialIso(12_000, 30_000_000L, 16_566_667L, range))
        assertEquals(12_000, CadenceIsoLoop.initialIso(12_000, null, 16_566_667L, range))
        assertEquals(50, CadenceIsoLoop.initialIso(10, 1_000_000L, 16_000_000L, range))
    }

    @Test fun meanLumaIsBinWeightedAndNormalised() {
        assertEquals(0.5, CadenceIsoLoop.meanLuma(listOf(1f, 0f, 1f))!!, 1e-9)
        assertEquals(1.0, CadenceIsoLoop.meanLuma(listOf(0f, 0f, 3f))!!, 1e-9)
        assertEquals(0.0, CadenceIsoLoop.meanLuma(listOf(2f, 0f, 0f))!!, 1e-9)
        assertNull(CadenceIsoLoop.meanLuma(listOf(0f, 0f, 0f)))
        assertNull(CadenceIsoLoop.meanLuma(emptyList()))
    }

    @Test fun evScaleIsPowerOfTwo() {
        assertEquals(2.0, CadenceIsoLoop.evScale(1f / 3f, 3), 1e-6)
        assertEquals(1.0, CadenceIsoLoop.evScale(0.5f, 0), 1e-9)
        assertEquals(0.5, CadenceIsoLoop.evScale(0.5f, -2), 1e-9)
    }

    @Test fun framesRightAfterEngagingAndAfterAReissueAreIgnored() {
        val loop = CadenceIsoLoop(minIntervalNs = 0).also { it.engage(10_000, range, nowNs = 1000 * ms); repeat(3) { _ -> it.update(1400 * ms, 0.30) } }
        assertNull(loop.update(1100 * ms, 0.10))
        assertNull(loop.update(1399 * ms, 0.10))
        assertEquals(11_800, loop.update(1400 * ms, 0.10))
        // A stale pre-change frame right after the re-issue does not steer.
        assertNull(loop.update(1500 * ms, 0.10))
        assertNotNull(loop.update(1800 * ms, 0.10))
    }

    @Test fun extendedRangeUsesTheBoostCappedAtEightTimes() {
        assertEquals(71..12_800, CadenceIsoLoop.extendedRange(71..12_800, null))
        assertEquals(71..12_800, CadenceIsoLoop.extendedRange(71..12_800, 100..100))
        assertEquals(71..102_400, CadenceIsoLoop.extendedRange(71..12_800, 100..3199).let { it.first..it.last })
        assertEquals(71..25_600, CadenceIsoLoop.extendedRange(71..12_800, 100..200))
    }

    @Test fun splitSendsTheRestAsPostRawBoost() {
        val sensor = 71..12_800
        val boost = 100..3199
        assertEquals(6_000 to 100, CadenceIsoLoop.split(6_000, sensor, boost))
        assertEquals(12_800 to 200, CadenceIsoLoop.split(25_600, sensor, boost))
        assertEquals(12_800 to 3199, CadenceIsoLoop.split(900_000, sensor, boost))
        assertEquals(12_800 to null, CadenceIsoLoop.split(25_600, sensor, null))
        assertEquals(71 to 100, CadenceIsoLoop.split(10, sensor, boost))
    }
}
