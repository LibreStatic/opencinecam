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
    private fun engaged(luma: Double = 0.30, iso: Int = 10_000, loop: CadenceIsoLoop = CadenceIsoLoop(settleNs = 0, rampSettleNs = 0)) = loop.also {
        it.engage(iso, range)
        repeat(3) { _ -> it.update(0, luma) }
        assertTrue(it.hasTarget)
    }

    /** Ticks every 66 ms from [startMs] until the ramp lands; returns the issued (time, iso) pairs. */
    private fun CadenceIsoLoop.runRamp(startMs: Long): List<Pair<Long, Int>> {
        val issued = ArrayList<Pair<Long, Int>>()
        var t = startMs
        while (ramping && t < startMs + 10_000) { tick(t * ms)?.let { issued += t to it }; t += 66 }
        return issued
    }

    @Test fun noTargetMeansTheIsoStaysFixed() {
        val loop = CadenceIsoLoop()
        loop.engage(10_000, range)
        assertFalse(loop.hasTarget)
        loop.update(0, 0.05)
        assertNull(loop.tick(1000 * ms))
        assertFalse(loop.ramping)
    }

    @Test fun noPlanBeforeThreeSettledFrames() {
        val loop = CadenceIsoLoop(settleNs = 0)
        loop.engage(10_000, range)
        loop.update(0, 0.05); loop.update(200 * ms, 0.90)
        assertFalse(loop.hasTarget)
        loop.update(400 * ms, 0.20)
        assertTrue(loop.hasTarget)
        assertFalse(loop.ramping)
        assertNull(loop.tick(500 * ms))
    }

    @Test fun theTargetIsTheMedianOfTheFirstThreeSettledFrames() {
        val loop = CadenceIsoLoop(settleNs = 0, rampSettleNs = 0)
        loop.engage(10_000, range)
        loop.update(0, 0.20); loop.update(1, 0.0003); loop.update(2, 0.21); loop.update(3, 0.90)
        loop.update(200 * ms, 0.21)          // on target: no plan
        assertFalse(loop.ramping)
        loop.update(400 * ms, 0.21 / 1.5)    // clearly too dark: plan
        assertTrue(loop.ramping)
    }

    @Test fun anOutlierAmongTheLearningFramesIsRejected() {
        val loop = CadenceIsoLoop(settleNs = 0)
        loop.engage(5000, range)
        loop.update(0, 0.20); loop.update(1, 0.02); loop.update(2, 0.21)
        loop.update(3, 0.20)
        assertFalse(loop.ramping)
    }

    @Test fun framesInsideTheSettleWindowDoNotCount() {
        val loop = CadenceIsoLoop().also { it.engage(10_000, range, nowNs = 1000 * ms) }
        repeat(5) { loop.update(1000 * ms + it * 50 * ms, 0.80) }
        assertFalse(loop.hasTarget)
        loop.update(1400 * ms, 0.20); loop.update(1450 * ms, 0.20); loop.update(1500 * ms, 0.20)
        assertTrue(loop.hasTarget)
    }

    @Test fun aDarkPreEngageHistoryNoLongerMatters() {
        val loop = CadenceIsoLoop(settleNs = 0)
        loop.engage(12_800, range)
        listOf(0.2, 0.19, 0.21, 0.2, 0.2, 0.21).forEachIndexed { i, l -> loop.update(i * 200 * ms, l) }
        assertFalse(loop.ramping)
        assertNull(loop.tick(2000 * ms))
    }

    @Test fun deadbandAroundTheTarget() {
        val loop = engaged()
        loop.update(0, 0.30 * 1.05)
        loop.update(200 * ms, 0.30 / 1.05)
        assertFalse(loop.ramping)
    }

    @Test fun theDesiredIsoUsesTheRatioSquared() {
        val loop = engaged(iso = 10_000)
        // Frame too dark by 1.2x: desired = 10000 * 1.2^2 = 14400.
        loop.update(0, 0.30 / 1.2)
        assertEquals(14_400.0, loop.runRamp(0).last().second.toDouble(), 1.0)
        val bright = engaged(iso = 10_000)
        bright.update(0, 0.30 * 1.1)
        assertEquals(8_264.0, bright.runRamp(0).last().second.toDouble(), 1.0)   // 10000 / 1.1^2
    }

    @Test fun thePlanIsLimitedToHalfAndDoubleAndTheRange() {
        val dark = engaged(iso = 5_000)
        dark.update(0, 0.01)                       // ratio 30 -> factor clamps to 2
        assertEquals(10_000, dark.runRamp(0).last().second)
        val bright = engaged(iso = 5_000)
        bright.update(0, 0.95)                     // ratio 0.32 -> factor 0.5... clamped
        assertEquals(2_500, bright.runRamp(0).last().second)
        val top = engaged(iso = 19_000)
        top.update(0, 0.01)
        assertEquals(20_000, top.runRamp(0).last().second)
        val bottom = engaged(iso = 80)
        bottom.update(0, 0.95)
        assertEquals(50, bottom.runRamp(0).last().second)
    }

    @Test fun rampStepsAreAtMostFourPercentAndSixtySixMsApart() {
        val loop = engaged(iso = 10_000)
        loop.update(0, 0.30 / 1.5)                 // ratio 1.5 -> 2.25 -> clamped 2.0
        var previous = 10_000
        var previousTime: Long? = null
        for ((time, iso) in loop.runRamp(0)) {
            assertTrue("$previous -> $iso", iso <= previous * 1.0401 && iso >= previous)
            previousTime?.let { assertTrue("$time - $it", time - it >= 66) }
            previous = iso; previousTime = time
        }
        assertEquals(20_000, previous)
    }

    @Test fun tickBeforeSixtySixMsHasPassedDoesNothing() {
        val loop = engaged(iso = 10_000)
        loop.update(0, 0.15)
        assertNotNull(loop.tick(0))
        assertNull(loop.tick(65 * ms))
        assertNotNull(loop.tick(66 * ms))
    }

    @Test fun aBigChangeRampsOverAboutSeventeenSteps() {
        val loop = engaged(iso = 10_000)
        loop.update(0, 0.30 / 2.0)                 // desired x2
        val steps = loop.runRamp(0)
        assertTrue("${steps.size}", steps.size in 16..19)
        assertEquals(20_000, steps.last().second)
        // Never a jump: the largest single move is 4 %.
        steps.zipWithNext().forEach { (a, b) -> assertTrue(b.second <= a.second * 1.0401) }
    }

    @Test fun finishesWithinOnePercentOnTheExactPlan() {
        val loop = engaged(iso = 10_000)
        loop.update(0, 0.30 / 1.1)                 // plan 12100
        val steps = loop.runRamp(0)
        assertEquals(12_100.0, steps.last().second.toDouble(), 1.0)
        assertFalse(loop.ramping)
    }

    @Test fun analysisFramesAreIgnoredWhileRampingAndDuringTheSettle() {
        val loop = engaged(iso = 10_000, loop = CadenceIsoLoop(settleNs = 0))   // 250 ms ramp settle
        loop.update(0, 0.30 / 1.5)
        assertTrue(loop.ramping)
        // A conflicting frame while ramping changes nothing.
        loop.update(10 * ms, 0.90)
        val steps = loop.runRamp(0)
        assertEquals(20_000, steps.last().second)
        val landedAt = steps.last().first
        // Inside the settle: ignored. After it: plans again.
        loop.update((landedAt + 100) * ms, 0.90)
        assertFalse(loop.ramping)
        loop.update((landedAt + 400) * ms, 0.90)
        assertTrue(loop.ramping)
    }

    @Test fun exposureCompensationScalesTheTarget() {
        val loop = engaged(luma = 0.30)
        loop.update(0, 0.30, targetScale = 1.0)
        assertFalse(loop.ramping)
        loop.update(0, 0.30, targetScale = 2.0)    // +1 EV: the frame is now too dark
        assertTrue(loop.ramping)
    }

    @Test fun invalidFramesAreIgnored() {
        val loop = engaged()
        loop.update(0, Double.NaN); loop.update(0, 0.0); loop.update(0, 5.0)
        assertFalse(loop.ramping)
    }

    @Test fun releaseDropsTheTargetAndThePlan() {
        val loop = engaged()
        loop.update(0, 0.10)
        assertTrue(loop.ramping)
        loop.release()
        assertFalse(loop.hasTarget)
        assertFalse(loop.ramping)
        assertNull(loop.tick(1000 * ms))
        loop.reset()
        loop.engage(5000, range)
        assertFalse(loop.hasTarget)
    }

    @Test fun initialIsoKeepsTheAutoExposure() {
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

    @Test fun extendedRangeUsesTheBoostCappedAtEightTimes() {
        assertEquals(71..12_800, CadenceIsoLoop.extendedRange(71..12_800, null))
        assertEquals(71..12_800, CadenceIsoLoop.extendedRange(71..12_800, 100..100))
        assertEquals(71..102_400, CadenceIsoLoop.extendedRange(71..12_800, 100..3199))
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

    @Test fun theSensorShareStopsWhereEveryLensStops() {
        // The Razr Fold: logical 12800, ultrawide 6400, 1x 12800, tele 6400.
        assertEquals(6_400, CadenceIsoLoop.sharedSensorTop(12_800, listOf(6_400, 12_800, 6_400)))
        assertEquals(12_800, CadenceIsoLoop.sharedSensorTop(12_800, emptyList()))
        assertEquals(12_800, CadenceIsoLoop.sharedSensorTop(12_800, listOf(25_600)))
        assertEquals(12_800, CadenceIsoLoop.sharedSensorTop(12_800, listOf(0, 12_800)))
        assertEquals(6_400 to 250, CadenceIsoLoop.split(16_000, 71..6_400, 100..3199))
    }

    @Test fun initialIsoIncludesTheAutoPostRawBoost() {
        // AE: ISO 652 with ISP boost 200 at 1/33 s, held at 1/60: total gain 1304 x 1.8 = about 2347.
        val iso = CadenceIsoLoop.initialIso(652, 30_300_000L, 16_566_667L, 50..20_000, autoBoost = 200)
        assertTrue("$iso", iso in 2_375..2_395)
        assertEquals(CadenceIsoLoop.initialIso(652, 30_300_000L, 16_566_667L, 50..20_000),
            CadenceIsoLoop.initialIso(652, 30_300_000L, 16_566_667L, 50..20_000, autoBoost = 100))
        // A boost below 100 is not a thing: treated as none.
        assertEquals(1_000, CadenceIsoLoop.initialIso(1000, null, 1L, range, autoBoost = 50))
    }

    @Test fun theBurstRunsForAPendingGuardAndForAFreshHoldThatIsStillLearningOrRamping() {
        fun wanted(engaged: Boolean = false, pending: Boolean = false, since: Long = 0, learning: Boolean = false, ramping: Boolean = false,
                   eligible: Boolean = true, suspended: Boolean = false) =
            cadenceBurstWanted(eligible, suspended, engaged, pending, since * ms, learning, ramping)
        assertTrue(wanted(pending = true))
        assertFalse(wanted())
        assertTrue(wanted(engaged = true, since = 1000, learning = true))
        assertTrue(wanted(engaged = true, since = 1000, ramping = true))
        assertFalse(wanted(engaged = true, since = 1000))
        assertFalse(wanted(engaged = true, since = 1500, learning = true))
        assertFalse(wanted(pending = true, eligible = false))
        assertFalse(wanted(pending = true, suspended = true))
        assertFalse(wanted(engaged = true, since = 100, learning = true, suspended = true))
    }

    /** Engaged on the old lens at ISO 10000 and on target, with no settle except the lens one. */
    private fun onTarget(iso: Int = 10_000) = CadenceIsoLoop(settleNs = 0, rampSettleNs = 0, lensSettleNs = 150 * ms).also {
        it.engage(iso, range)
        repeat(4) { _ -> it.update(0, 0.30, frameNs = 0) }
        assertFalse(it.ramping)
    }

    @Test fun anUnknownLensIsCorrectedInOneJumpFromItsFirstFrame() {
        val loop = onTarget()
        loop.lensChanged(1000 * ms, switchFrameNs = 980 * ms)
        assertTrue(loop.lensPending)
        assertNull(loop.tick(1000 * ms))                     // nothing known: the ISO stays for now
        // Frames still in flight from the old lens are ignored, by their sensor timestamp.
        assertNull(loop.update(1010 * ms, 0.30, frameNs = 970 * ms))
        assertTrue(loop.lensPending)
        // The 1x comes out 1.5x too bright in luma (2.25x in linear): one jump to 10000 / 2.25.
        val ratio = loop.update(1020 * ms, 0.45, frameNs = 980 * ms)!!
        assertFalse(loop.lensPending)
        assertEquals(1.0 / 2.25, ratio, 1e-6)
        assertEquals(4_444, loop.tick(1030 * ms))            // at once, not 4 % at a time
        assertTrue(loop.awaitingEffect)
        // Frames until a result reports the new ISO still show the old one: ignored, however late.
        assertFalse(loop.noteResult(1000 * ms, 10_000))
        assertNull(loop.update(1200 * ms, 0.45, frameNs = 1100 * ms))
        assertTrue(loop.noteResult(1150 * ms, 4_450))        // the sensor x boost split rounds
        assertFalse(loop.awaitingEffect)
        assertNull(loop.update(1210 * ms, 0.45, frameNs = 1140 * ms))
        assertFalse(loop.ramping)
        // From the reported frame on, a small residue is fine-tuned by the ramp.
        loop.update(1220 * ms, 0.27, frameNs = 1150 * ms)
        assertTrue(loop.ramping)
        assertEquals(4_622, loop.tick(1301 * ms))
    }

    @Test fun aKnownRatioIsAppliedAtTheSwitchAndMeasuredOnceInEffect() {
        val loop = onTarget()
        loop.lensChanged(1000 * ms, expectedRatio = 0.4, switchFrameNs = 980 * ms)
        assertEquals(4_000, loop.tick(1000 * ms))
        assertTrue(loop.lensPending)
        // The new lens' frames at the old ISO (the flash) teach nothing.
        assertNull(loop.update(1100 * ms, 0.80, frameNs = 1000 * ms))
        assertTrue(loop.lensPending)
        loop.noteResult(1120 * ms, 4_000)
        // A little bright once in effect: the measurement corrects it and reports the real ratio.
        val ratio = loop.update(1200 * ms, 0.30 * 1.1, frameNs = 1120 * ms)!!
        assertEquals(4_000 / 1.21 / 10_000, ratio, 1e-3)
        assertEquals(3_305, loop.tick(1201 * ms))
    }

    @Test fun framesAtTheOldIsoAreBridgedUntilTheJumpIsInEffect() {
        val loop = onTarget()
        assertEquals(1.0, loop.frameGain(10_000), 0.0)        // no jump on its way
        loop.lensChanged(1000 * ms, expectedRatio = 0.4, switchFrameNs = 980 * ms)
        // The switch result itself already knows the jump, before the next request issues it.
        assertEquals(0.4, loop.frameGain(10_000), 1e-9)
        assertEquals(4_000, loop.tick(1000 * ms))
        assertEquals(0.4, loop.frameGain(10_000), 1e-9)
        // Once a result reports the new ISO its frames need nothing.
        assertTrue(loop.noteResult(1150 * ms, 4_000))
        assertEquals(1.0, loop.frameGain(4_000), 0.0)
    }

    @Test fun theFrameGainIsBounded() {
        val loop = onTarget()
        loop.lensChanged(1000 * ms, expectedRatio = 3.9, switchFrameNs = 980 * ms)
        assertEquals(CadenceIsoLoop.MAX_FRAME_GAIN, loop.frameGain(1_000), 0.0)
        assertEquals(1.0, loop.frameGain(0), 0.0)
    }

    @Test fun anUnknownLensIsBridgedOnlyOnceMeasured() {
        val loop = onTarget()
        loop.lensChanged(1000 * ms, switchFrameNs = 980 * ms)
        assertEquals(1.0, loop.frameGain(10_000), 0.0)
        loop.update(1020 * ms, 0.45, frameNs = 980 * ms)
        assertEquals(1.0 / 2.25, loop.frameGain(10_000), 1e-3)
    }

    @Test fun anUnreportedJumpIsMeasuredByTheClockAfterTheTimeout() {
        val loop = onTarget()
        loop.lensChanged(1000 * ms, expectedRatio = 0.4, switchFrameNs = 980 * ms)
        assertEquals(4_000, loop.tick(1000 * ms))
        assertNull(loop.update(1499 * ms, 0.30, frameNs = 1400 * ms))
        assertTrue(loop.awaitingEffect)
        assertEquals(0.4, loop.update(1500 * ms, 0.30, frameNs = 1400 * ms)!!, 1e-9)
        assertFalse(loop.awaitingEffect)
        assertFalse(loop.lensPending)
    }

    @Test fun framesWithoutATimestampFallBackToTheLensSettle() {
        val loop = onTarget()
        loop.lensChanged(1000 * ms)
        assertNull(loop.update(1100 * ms, 0.45))
        assertEquals(1.0 / 2.25, loop.update(1150 * ms, 0.45)!!, 1e-6)
        assertEquals(4_444, loop.tick(1151 * ms))
        loop.noteResult(1200 * ms, 4_444)
        assertNull(loop.update(1250 * ms, 0.27))             // the 150 ms after the jump
        loop.update(1301 * ms, 0.27)
        assertTrue(loop.ramping)
    }

    @Test fun theRampSettleStillAppliesToTimestampedFrames() {
        val loop = CadenceIsoLoop(settleNs = 0, rampSettleNs = 250 * ms)
        loop.engage(10_000, range)
        repeat(3) { loop.update(0, 0.30, frameNs = 0) }
        loop.lensChanged(100 * ms, switchFrameNs = 90 * ms)
        loop.update(110 * ms, 0.30, frameNs = 90 * ms)       // measured on target: no jump
        loop.update(200 * ms, 0.30 / 1.1, frameNs = 180 * ms)
        assertTrue(loop.ramping)
        var t = 200L
        while (loop.ramping) { loop.tick(t * ms); t += 66 }
        // Landed: frames are ignored for 250 ms, timestamps or not.
        loop.update((t + 100) * ms, 0.10, frameNs = (t + 90) * ms)
        assertFalse(loop.ramping)
    }

    @Test fun aSwitchOnTargetIsMeasuredWithoutAJump() {
        val loop = onTarget()
        loop.lensChanged(1000 * ms, expectedRatio = 0.5, switchFrameNs = 980 * ms)
        assertEquals(5_000, loop.tick(1000 * ms))
        loop.noteResult(1100 * ms, 5_000)
        assertEquals(0.5, loop.update(1150 * ms, 0.30, frameNs = 1100 * ms)!!, 1e-9)
        assertNull(loop.tick(1151 * ms))
        assertFalse(loop.lensPending)
        assertFalse(loop.awaitingEffect)
    }

    @Test fun theJumpIsBoundedAndAClampedMeasurementIsNotReported() {
        val loop = onTarget()
        loop.lensChanged(1000 * ms, switchFrameNs = 980 * ms)
        // Far too dark (a 9x linear error): the jump stops at 4x and the ratio is not trusted.
        assertNull(loop.update(1150 * ms, 0.10, frameNs = 1000 * ms))
        assertEquals(range.last, loop.tick(1151 * ms))       // 40000, then the ISO range
        val top = onTarget(iso = 15_000)
        top.lensChanged(1000 * ms, switchFrameNs = 980 * ms)
        // Within 4x but past the ISO ceiling: clamped to the range, not reported either.
        assertNull(top.update(1150 * ms, 0.20, frameNs = 1000 * ms))
        assertEquals(range.last, top.tick(1151 * ms))
    }

    @Test fun noRatioIsReportedFromALensThatWasOutOfIso() {
        val loop = onTarget(iso = range.last)
        // At the ceiling and still darker than the target: the ISO that would hold it is beyond the range.
        loop.update(500 * ms, 0.20, frameNs = 500 * ms)
        loop.lensChanged(1000 * ms, switchFrameNs = 980 * ms)
        // The new lens is brighter and lands within the range, but the ratio would be against an extrapolation.
        assertNull(loop.update(1150 * ms, 0.40, frameNs = 1000 * ms))
        assertFalse(loop.lensPending)
        assertTrue(loop.tick(1151 * ms)!! < range.last)       // the jump itself still happens
    }

    @Test fun aSecondSwitchBeforeTheFirstWasMeasuredKeepsTheOldBase() {
        val loop = onTarget()
        loop.lensChanged(1000 * ms, expectedRatio = 0.4, switchFrameNs = 980 * ms)
        assertEquals(4_000, loop.tick(1000 * ms))
        // Straight on to a third lens: the ratio is from the lens last measured (ISO 10000).
        loop.lensChanged(1050 * ms, expectedRatio = 1.6, switchFrameNs = 1040 * ms)
        assertFalse(loop.awaitingEffect)                     // the 4000 no longer matters
        assertEquals(16_000, loop.tick(1050 * ms))
        assertFalse(loop.noteResult(1060 * ms, 4_000))
        loop.noteResult(1150 * ms, 16_000)
        assertEquals(1.6, loop.update(1200 * ms, 0.30, frameNs = 1150 * ms)!!, 1e-9)
    }

    @Test fun theBaseIsTheIsoThatHeldTheTargetNotTheOneMidRamp() {
        val loop = onTarget()
        loop.update(100 * ms, 0.30 / 1.2, frameNs = 90 * ms) // the scene darkened: plans 10000 x 1.44
        assertTrue(loop.ramping)
        loop.tick(100 * ms)                                  // one step, to 10400
        loop.lensChanged(200 * ms, expectedRatio = 0.5, switchFrameNs = 190 * ms)
        assertFalse(loop.ramping)
        assertEquals(7_200, loop.tick(200 * ms))             // 14400 x 0.5, not 10400 x 0.5
    }

    @Test fun aSwitchWithoutATargetOnlyRestartsTheLearning() {
        val loop = CadenceIsoLoop(settleNs = 0, lensSettleNs = 150 * ms)
        loop.engage(10_000, range)
        loop.update(0, 0.30, frameNs = 0); loop.update(1, 0.30, frameNs = 1)
        loop.lensChanged(1000 * ms, expectedRatio = 0.4, switchFrameNs = 980 * ms)
        assertFalse(loop.lensPending)
        assertNull(loop.tick(1000 * ms))
        loop.update(1010 * ms, 0.30, frameNs = 970 * ms)     // the old lens: not learned
        loop.update(1020 * ms, 0.45, frameNs = 980 * ms)
        assertFalse(loop.hasTarget)                           // the old lens' two frames were dropped
        loop.update(1030 * ms, 0.45, frameNs = 990 * ms); loop.update(1040 * ms, 0.45, frameNs = 1000 * ms)
        assertEquals(0.45, loop.target!!, 1e-9)
    }

    @Test fun engageAndReleaseForgetAPendingLens() {
        val loop = onTarget()
        loop.lensChanged(1000 * ms, expectedRatio = 0.4, switchFrameNs = 980 * ms)
        loop.tick(1000 * ms)
        loop.release()
        assertFalse(loop.lensPending)
        assertFalse(loop.awaitingEffect)
        assertNull(loop.tick(1000 * ms))
        val again = onTarget()
        again.lensChanged(1000 * ms)
        again.engage(10_000, range, 1000 * ms)
        assertFalse(again.lensPending)
    }

    @Test fun theBurstRunsWhileASwitchedLensIsUnmeasured() {
        assertTrue(cadenceBurstWanted(true, false, true, false, 60_000 * ms, false, false, lensPending = true))
        assertFalse(cadenceBurstWanted(true, false, true, false, 60_000 * ms, false, false, lensPending = false))
        assertFalse(cadenceBurstWanted(true, true, true, false, 60_000 * ms, false, false, lensPending = true))
    }

    @Test fun lensRatiosAreRememberedInLogSpaceBothWays() {
        val ratios = LensIsoRatios()
        assertNull(ratios.ratio("0", "3", "2"))
        assertEquals(0.4, ratios.update("0", "3", "2", 0.4)!!, 1e-9)
        assertEquals(2.5, ratios.ratio("0", "2", "3")!!, 1e-9)
        // The next measurement moves halfway in log space: sqrt(0.4 x 0.225) = 0.3.
        assertEquals(0.3, ratios.update("0", "3", "2", 0.225)!!, 1e-9)
        assertEquals(1 / 0.3, ratios.ratio("0", "2", "3")!!, 1e-9)
        // Implausible values and a lens to itself are ignored; logical cameras are kept apart.
        assertEquals(0.3, ratios.update("0", "3", "2", 20.0)!!, 1e-9)
        assertEquals(0.3, ratios.update("0", "3", "2", 0.1)!!, 1e-9)
        assertNull(ratios.update("0", "2", "2", 0.5))
        assertNull(ratios.ratio("1", "3", "2"))
    }

    @Test fun theBurstPeriodIsNeverSlowerThanTheNormalOne() {
        assertEquals(250L, analysisPeriodMs(250, false))
        assertEquals(33L, analysisPeriodMs(250, true))
        assertEquals(20L, analysisPeriodMs(20, true))
    }

    @Test fun resumeContinuesFromTheRampsIsoKeepingTheTargetAndSettling() {
        val loop = CadenceIsoLoop(settleNs = 200 * ms, rampSettleNs = 0)
        loop.engage(2000, range, 0)
        repeat(3) { loop.update(200 * ms, 0.30) }
        assertEquals("learned", loop.targetSource)
        loop.resume(6000, 400 * ms)
        assertTrue(loop.hasTarget)
        loop.update(500 * ms, 0.15)                         // inside the 200 ms settle after the ramp
        assertFalse(loop.ramping)
        loop.update(600 * ms, 0.15)
        assertTrue(loop.ramping)
        assertTrue(loop.tick(600 * ms)!! in 5760..6240)     // walks from 6000, not from the engage ISO
    }

    @Test fun theLoopLearnsEvenBeforeAnyRampIsPlanned() {
        val loop = CadenceIsoLoop(settleNs = 0)
        loop.engage(5000, range)
        assertNull(loop.targetSource)
        repeat(3) { loop.update(0, 0.20) }
        assertEquals("learned", loop.targetSource)
    }

    private val isoLimits = 71..25_600

    @Test fun theExposureRampWalksGeometricallyFromAeToTheHeldShutter() {
        val ramp = CadenceExposureRamp()
        assertTrue(ramp.start(30 * ms, 1000, 16_567_000L, isoLimits, 0))
        assertEquals(13, ramp.totalSteps)
        assertNull(ramp.tick(32 * ms))                        // not 33 ms yet
        var t = 0L
        var last = 30 * ms
        var steps = 0
        var final: CadenceExposureRamp.Step? = null
        while (ramp.active) {
            t += 33 * ms
            val step = ramp.tick(t) ?: continue
            assertTrue(step.exposureNs < last)
            assertEquals(1000.0 * 30 * ms / step.exposureNs, step.iso.toDouble(), 2.0)   // exposure x ISO constant
            last = step.exposureNs; steps++; final = step
        }
        assertEquals(13, steps)
        assertTrue(final!!.done)
        assertEquals(16_567_000L, final.exposureNs)
        assertEquals(1811.0, final.iso.toDouble(), 2.0)
        assertNull(ramp.tick(t + 100 * ms))                   // inactive afterwards
    }

    @Test fun theExposureRampClampsTheIsoAndTakesNothingToRampWhenAeIsNotLonger() {
        val ramp = CadenceExposureRamp()
        assertTrue(ramp.start(33 * ms, 20_000, 16 * ms, isoLimits, 0))
        var t = 0L
        var step: CadenceExposureRamp.Step? = null
        while (ramp.active) { t += 33 * ms; ramp.tick(t)?.let { step = it } }
        assertEquals(25_600, step!!.iso)                      // would be ~41000
        assertFalse(CadenceExposureRamp().start(16 * ms, 1000, 16_567_000L, isoLimits, 0))
        val idle = CadenceExposureRamp()
        assertFalse(idle.active)
        idle.start(30 * ms, 1000, 16 * ms, isoLimits, 0)
        idle.stop()
        assertNull(idle.tick(100 * ms))
    }

    @Test fun theFrameDurationFollowsTheExposureButNeverBelowNominal() {
        assertEquals(30_100_000L, CadenceExposureRamp.frameDurationNs(30 * ms, 60))
        assertEquals(16_666_666L, CadenceExposureRamp.frameDurationNs(16_566_666L, 60))
        assertEquals(16_666_666L, CadenceExposureRamp.frameDurationNs(10 * ms, 60))
    }

    @Test fun onlyTheGpuReadbackDrivesTheLoopWhenThereIsAGpuPipeline() {
        assertTrue(cadenceLumaProducerAccepted(true, "gpu"))
        assertFalse(cadenceLumaProducerAccepted(true, "yuv"))
        assertTrue(cadenceLumaProducerAccepted(false, "yuv"))
        assertFalse(cadenceLumaProducerAccepted(false, "gpu"))
    }

    @Test fun theHiddenGainUpdateIsTheSquaredRatioBoundedPerUpdateAndInAbsoluteTerms() {
        assertEquals(2.2 * 1.21, HiddenAeGain.next(2.2, 0.22, 0.20), 1e-9)
        assertEquals(3.0, HiddenAeGain.next(1.5, 0.9, 0.1), 1e-9)             // x81 bounded to 2x
        assertEquals(1.5, HiddenAeGain.next(3.0, 0.1, 0.9), 1e-9)             // /81 bounded to 0.5x
        assertEquals(4.0, HiddenAeGain.next(3.5, 0.9, 0.1), 1e-9)             // absolute ceiling
        assertEquals(1.0, HiddenAeGain.next(1.2, 0.1, 0.9), 1e-9)             // absolute floor
        assertEquals(2.2, HiddenAeGain.next(2.2, 0.2, 0.0), 1e-9)             // unusable luma: unchanged
    }

    @Test fun theHiddenGainIsKeptPerCameraStartingFromThePrior() {
        val store = HiddenAeGain()
        assertEquals(HiddenAeGain.HIDDEN_AE_GAIN_PRIOR, store.gain("0"), 1e-9)
        val updated = store.update("0", 0.25, 0.20)
        assertEquals(2.2 * 1.5625, updated, 1e-9)                 // (1.25)^2
        assertEquals(updated, store.gain("0"), 1e-9)
        assertEquals(HiddenAeGain.HIDDEN_AE_GAIN_PRIOR, store.gain("2"), 1e-9)
    }

    @Test fun theStartIsoIsAeIsoTimesTheGainWithinTheRange() {
        assertEquals(1000 * 2.2, HiddenAeGain.startIso(1000, 2.2, isoLimits).toDouble(), 1.0)
        assertEquals(25_600, HiddenAeGain.startIso(20_000, 2.2, isoLimits))
        assertEquals(71, HiddenAeGain.startIso(10, 2.2, isoLimits))
    }

    @Test fun anExternalTargetIsUsedWithoutLearning() {
        val loop = CadenceIsoLoop(settleNs = 0)
        loop.engage(5000, range)
        loop.setTarget(0.30)
        assertEquals("ae", loop.targetSource)
        loop.update(0, 0.15)
        assertTrue(loop.ramping)
        loop.setTarget(0.0001)                                  // invalid: ignored
        assertEquals(0.30, loop.target!!, 1e-9)
    }
}
