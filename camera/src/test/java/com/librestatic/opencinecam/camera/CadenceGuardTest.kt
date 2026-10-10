/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceGuardTest {
    private val ms = 1_000_000L
    private val fps = 60
    private val nominal = 16_666_667L
    private val stretched = 30_180_000L   // the measured 4K60 dark-room result
    private val darkIso = 12_000
    private val brightIso = 800

    /** The settle window has its own tests; the rest model results that already come from the held request. */
    private fun zeroSettle() = CadenceGuard(settleNs = 0)

    private fun CadenceGuard.feed(frameDurationNs: Long?, iso: Int?, fromMs: Long, toMs: Long, stepMs: Long = 16): Boolean {
        var result = engaged
        var t = fromMs
        while (t <= toMs) { result = update(t * ms, fps, frameDurationNs, iso); t += stepMs }
        return result
    }

    private fun engagedGuard(): CadenceGuard = zeroSettle().also {
        assertTrue(it.feed(stretched, darkIso, 0, 300))
    }

    @Test fun startsDisengaged() {
        assertFalse(zeroSettle().engaged)
    }

    @Test fun engagesOnlyAfterTheStretchLastedLongEnough() {
        val guard = zeroSettle()
        assertFalse(guard.feed(stretched, darkIso, 0, 240))
        assertTrue(guard.feed(stretched, darkIso, 241, 270))
    }

    @Test fun engagesAtExactlyTheHoldTime() {
        val guard = zeroSettle()
        assertFalse(guard.update(0, fps, stretched, darkIso))
        assertFalse(guard.update(249 * ms, fps, stretched, darkIso))
        assertTrue(guard.update(250 * ms, fps, stretched, darkIso))
    }

    @Test fun aBriefStretchIsIgnored() {
        val guard = zeroSettle()
        assertFalse(guard.feed(stretched, darkIso, 0, 150))
        assertFalse(guard.feed(nominal, darkIso, 166, 200))
        // The timer restarted: another 150 ms of stretch is still not enough.
        assertFalse(guard.feed(stretched, darkIso, 216, 360))
    }

    @Test fun nothingBelowEightPercentOverTheNominalFrameTime() {
        val guard = zeroSettle()
        val justUnder = (nominal * 1.079).toLong()
        assertFalse(guard.feed(justUnder, darkIso, 0, 3000))
        assertFalse(guard.feed(nominal, darkIso, 3016, 4000))
        val justOver = (nominal * 1.081).toLong()
        assertTrue(guard.feed(justOver, darkIso, 4016, 4400))
    }

    @Test fun usesTheTargetRateOfEachSample() {
        val guard = zeroSettle()
        // 33 ms frames are nominal at 30 fps.
        var t = 0L
        while (t <= 1000) { assertFalse(guard.update(t * ms, 30, 33_333_333L, darkIso)); t += 33 }
        // ...and stretched at 60 fps.
        while (t <= 1400) { guard.update(t * ms, 60, 33_333_333L, darkIso); t += 16 }
        assertTrue(guard.engaged)
    }

    @Test fun staysEngagedWhileTheIsoStaysHigh() {
        val guard = engagedGuard()
        assertTrue(guard.feed(nominal, darkIso, 316, 5000))
        assertTrue(guard.feed(nominal, (darkIso * 0.6).toInt(), 5016, 9000))
    }

    @Test fun releasesWhenTheIsoStaysAtHalfTheReferenceForOneSecond() {
        val guard = engagedGuard()
        val light = darkIso / 2
        assertTrue(guard.feed(nominal, light, 316, 1300))
        assertFalse(guard.feed(nominal, light, 1301, 1340))
    }

    @Test fun releasesAtExactlyTheReleaseTime() {
        val guard = engagedGuard()
        assertTrue(guard.update(1000 * ms, fps, nominal, darkIso / 4))
        assertTrue(guard.update(1999 * ms, fps, nominal, darkIso / 4))
        assertFalse(guard.update(2000 * ms, fps, nominal, darkIso / 4))
    }

    @Test fun aBrightDipThatComesBackDarkDoesNotRelease() {
        val guard = engagedGuard()
        assertTrue(guard.feed(nominal, brightIso, 316, 1000))
        assertTrue(guard.feed(nominal, darkIso, 1016, 1100))
        // The release timer restarted at the dip's end.
        assertTrue(guard.feed(nominal, brightIso, 1116, 2000))
        assertFalse(guard.feed(nominal, brightIso, 2016, 2200))
    }

    @Test fun theReferenceFollowsTheHighestIsoSeenWhileEngaged() {
        val guard = zeroSettle().also { assertTrue(it.feed(stretched, 6000, 0, 300)) }
        // The scene darkens further under the held shutter: reference becomes 12000.
        assertTrue(guard.feed(nominal, darkIso, 316, 600))
        // 7000 is above half of 12000, so it is not "light to spare".
        assertTrue(guard.feed(nominal, 7000, 616, 4000))
        assertFalse(guard.feed(nominal, 5000, 4016, 5100))
    }

    @Test fun releasedGuardEngagesAgainFromScratch() {
        val guard = engagedGuard()
        assertFalse(guard.feed(nominal, brightIso, 316, 1400))
        assertFalse(guard.feed(stretched, darkIso, 1416, 1600))
        assertTrue(guard.feed(stretched, darkIso, 1616, 1700))
    }

    @Test fun invalidReadingsAreIgnored() {
        val guard = zeroSettle()
        assertFalse(guard.feed(null, darkIso, 0, 1000))
        assertFalse(guard.feed(stretched, null, 1016, 2000))
        assertFalse(guard.feed(0L, darkIso, 2016, 3000))
        assertFalse(guard.feed(-5L, darkIso, 3016, 4000))
        assertFalse(guard.feed(stretched, 0, 4016, 5000))
        assertFalse(guard.update(5016 * ms, 0, stretched, darkIso))
        // Invalid samples neither start nor cancel timers.
        assertFalse(guard.feed(stretched, darkIso, 6000, 6200))
        assertFalse(guard.feed(null, null, 6216, 7000))
        assertTrue(guard.update(6300 * ms, fps, stretched, darkIso))
    }

    @Test fun invalidReadingsDoNotReleaseAnEngagedGuard() {
        val guard = engagedGuard()
        assertTrue(guard.feed(null, brightIso, 316, 5000))
        assertTrue(guard.feed(nominal, null, 5016, 9000))
    }

    @Test fun aGapDropsThePendingEngageTimerButKeepsTheVerdict() {
        val guard = zeroSettle()
        assertFalse(guard.feed(stretched, darkIso, 0, 200))
        // 1.5 s without results (session restart): the 200 ms already counted are forgotten.
        assertFalse(guard.update(1700 * ms, fps, stretched, darkIso))
        assertFalse(guard.update(1900 * ms, fps, stretched, darkIso))
        assertTrue(guard.update(1950 * ms, fps, stretched, darkIso))
    }

    @Test fun aGapDropsThePendingReleaseTimerButKeepsTheVerdict() {
        val guard = engagedGuard()
        assertTrue(guard.feed(nominal, brightIso, 316, 1000))
        assertTrue(guard.update(3000 * ms, fps, nominal, brightIso))
        assertTrue(guard.update(3900 * ms, fps, nominal, brightIso))
        assertFalse(guard.update(4000 * ms, fps, nominal, brightIso))
    }

    @Test fun aGapKeepsAnEngagedVerdictWithNoSamplesNeeded() {
        val guard = engagedGuard()
        assertTrue(guard.update(60_000 * ms, fps, nominal, darkIso))
    }

    @Test fun resetForgetsTheVerdictAndTheTimers() {
        val guard = engagedGuard()
        guard.reset()
        assertFalse(guard.engaged)
        assertFalse(guard.update(400 * ms, fps, stretched, darkIso))
        assertFalse(guard.update(500 * ms, fps, stretched, darkIso))
        assertTrue(guard.update(650 * ms, fps, stretched, darkIso))
    }

    @Test fun resetClearsAPendingEngageTimer() {
        val guard = zeroSettle()
        guard.feed(stretched, darkIso, 0, 200)
        guard.reset()
        assertFalse(guard.update(210 * ms, fps, stretched, darkIso))
        assertFalse(guard.update(300 * ms, fps, stretched, darkIso))
    }

    @Test fun resetForgetsTheReferenceIso() {
        val guard = engagedGuard()
        guard.reset()
        // Engages again at a modest ISO: that is the new reference, 4000 is not "half" of 12000.
        assertTrue(guard.feed(stretched, 6000, 1000, 1300))
        assertTrue(guard.feed(nominal, 4000, 1316, 3000))
    }

    @Test fun nativePriorityNeedsAndroid16AndShutterPriority() {
        val priorities = setOf(ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY)
        assertTrue(CadenceGuard.nativePriority(36, priorities))
        assertTrue(CadenceGuard.nativePriority(37, priorities))
        assertFalse(CadenceGuard.nativePriority(35, priorities))
        assertFalse(CadenceGuard.nativePriority(36, setOf(ExposureMode.ISO_PRIORITY)))
        assertFalse(CadenceGuard.nativePriority(36, emptySet()))
    }

    @Test fun supportIsNativePriorityOrUsableManualSensor() {
        val native = ExposureCapabilities(priorities = setOf(ExposureMode.SHUTTER_PRIORITY))
        assertTrue(CadenceGuard.supported(36, native))
        assertFalse(CadenceGuard.supported(35, native))
        val manual = ExposureCapabilities(manual = true, isoRange = 50..20_000, timeRangeNs = 100_000L..500_000_000L)
        assertTrue(CadenceGuard.supported(36, manual))
        assertTrue(CadenceGuard.supported(34, manual))
        assertFalse(CadenceGuard.supported(36, manual.copy(manual = false)))
        assertFalse(CadenceGuard.supported(36, manual.copy(isoRange = null)))
        assertFalse(CadenceGuard.supported(36, manual.copy(timeRangeNs = null)))
        assertFalse(CadenceGuard.supported(36, ExposureCapabilities(priorities = setOf(ExposureMode.ISO_PRIORITY))))
    }

    @Test fun anEmulatedHoldWhoseIsoFollowsTheSceneStillReleases() {
        val guard = engagedGuard()                       // engaged at ISO 12000
        // The software loop lowers the held ISO as the room gets brighter: below half for 1 s releases.
        assertTrue(guard.feed(nominal, 9_000, 316, 1000))
        assertTrue(guard.feed(nominal, 5_000, 1016, 2000))
        assertFalse(guard.feed(nominal, 5_000, 2016, 2100))
    }

    @Test fun engagedReflectsLastVerdict() {
        val guard = zeroSettle()
        assertEquals(false, guard.engaged)
        guard.feed(stretched, darkIso, 0, 300)
        assertEquals(true, guard.engaged)
    }

    @Test fun theEngagingSampleDoesNotSeedTheReference() {
        val guard = CadenceGuard()
        // Automatic exposure stretches at ISO 25600; the hold then runs at 12800 under manual exposure.
        assertTrue(guard.feed(stretched, 25_600, 0, 300))
        assertTrue(guard.feed(nominal, 12_800, 316, 4000))
    }

    @Test fun resultsInTheSettleWindowAreIgnored() {
        val guard = CadenceGuard()
        assertTrue(guard.feed(stretched, 25_600, 0, 300))   // engaged at 256 ms
        // In-flight automatic results (and a very low ISO) during the first 400 ms change nothing.
        assertTrue(guard.feed(nominal, 100, 316, 650))
        // The reference is the highest ISO after the window; a quarter of it for 1 s releases.
        assertTrue(guard.feed(nominal, 12_800, 666, 1000))
        assertTrue(guard.feed(nominal, 3_000, 1016, 2000))
        assertFalse(guard.feed(nominal, 3_000, 2016, 2100))
    }

    @Test fun oscillationFromThePostEngageIsoDropIsGone() {
        val guard = CadenceGuard()
        assertTrue(guard.feed(stretched, 25_600, 0, 300))
        // The held ISO sits at 12800, 50 % of the engaging sample: it must not release.
        assertTrue(guard.feed(nominal, 12_800, 316, 10_000))
    }
}
