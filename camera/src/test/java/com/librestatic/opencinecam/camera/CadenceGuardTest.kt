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

    private fun CadenceGuard.feed(frameDurationNs: Long?, iso: Int?, fromMs: Long, toMs: Long, stepMs: Long = 16): Boolean {
        var result = engaged
        var t = fromMs
        while (t <= toMs) { result = update(t * ms, fps, frameDurationNs, iso); t += stepMs }
        return result
    }

    private fun engagedGuard(): CadenceGuard = CadenceGuard().also {
        assertTrue(it.feed(stretched, darkIso, 0, 300))
    }

    @Test fun startsDisengaged() {
        assertFalse(CadenceGuard().engaged)
    }

    @Test fun engagesOnlyAfterTheStretchLastedLongEnough() {
        val guard = CadenceGuard()
        assertFalse(guard.feed(stretched, darkIso, 0, 240))
        assertTrue(guard.feed(stretched, darkIso, 241, 270))
    }

    @Test fun engagesAtExactlyTheHoldTime() {
        val guard = CadenceGuard()
        assertFalse(guard.update(0, fps, stretched, darkIso))
        assertFalse(guard.update(249 * ms, fps, stretched, darkIso))
        assertTrue(guard.update(250 * ms, fps, stretched, darkIso))
    }

    @Test fun aBriefStretchIsIgnored() {
        val guard = CadenceGuard()
        assertFalse(guard.feed(stretched, darkIso, 0, 150))
        assertFalse(guard.feed(nominal, darkIso, 166, 200))
        // The timer restarted: another 150 ms of stretch is still not enough.
        assertFalse(guard.feed(stretched, darkIso, 216, 360))
    }

    @Test fun nothingBelowEightPercentOverTheNominalFrameTime() {
        val guard = CadenceGuard()
        val justUnder = (nominal * 1.079).toLong()
        assertFalse(guard.feed(justUnder, darkIso, 0, 3000))
        assertFalse(guard.feed(nominal, darkIso, 3016, 4000))
        val justOver = (nominal * 1.081).toLong()
        assertTrue(guard.feed(justOver, darkIso, 4016, 4400))
    }

    @Test fun usesTheTargetRateOfEachSample() {
        val guard = CadenceGuard()
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
        val guard = CadenceGuard().also { assertTrue(it.feed(stretched, 6000, 0, 300)) }
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
        val guard = CadenceGuard()
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
        val guard = CadenceGuard()
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
        val guard = CadenceGuard()
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

    @Test fun supportNeedsAndroid16AndShutterPriority() {
        val priorities = setOf(ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY)
        assertTrue(CadenceGuard.supported(36, priorities))
        assertTrue(CadenceGuard.supported(37, priorities))
        assertFalse(CadenceGuard.supported(35, priorities))
        assertFalse(CadenceGuard.supported(36, setOf(ExposureMode.ISO_PRIORITY)))
        assertFalse(CadenceGuard.supported(36, emptySet()))
    }

    @Test fun engagedReflectsLastVerdict() {
        val guard = CadenceGuard()
        assertEquals(false, guard.engaged)
        guard.feed(stretched, darkIso, 0, 300)
        assertEquals(true, guard.engaged)
    }
}
