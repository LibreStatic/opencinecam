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
