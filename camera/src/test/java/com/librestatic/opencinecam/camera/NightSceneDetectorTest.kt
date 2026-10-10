/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightSceneDetectorTest {
    private val ms = 1_000_000L
    private val dim = 6800 to 33_333_333L      // index about 227
    private val daylight = 100 to 2_000_000L   // index 0.2
    private val between = 800 to 33_333_333L   // index about 26.7: above leave, below enter

    private fun NightSceneDetector.feed(sample: Pair<Int, Long>, fromMs: Long, toMs: Long, stepMs: Long = 33): Boolean {
        var result = dark
        var t = fromMs
        while (t <= toMs) { result = update(t * ms, sample.first, sample.second); t += stepMs }
        return result
    }

    @Test fun startsBrightAndNeedsASustainedDarkSceneToTurnDark() {
        val detector = NightSceneDetector()
        assertFalse(detector.dark)
        assertFalse(detector.feed(dim, 0, 900))
        assertTrue(detector.feed(dim, 901, 1100))
    }

    @Test fun aBriefDarkSpikeIsIgnored() {
        val detector = NightSceneDetector()
        detector.feed(dim, 0, 500)
        detector.feed(daylight, 501, 700)
        assertFalse(detector.feed(dim, 701, 1400))
    }

    @Test fun leavingDarkNeedsASustainedBrightSceneAndTheBandBetweenKeepsTheVerdict() {
        val detector = NightSceneDetector()
        assertTrue(detector.feed(dim, 0, 1100))
        assertTrue(detector.feed(between, 1101, 5000))
        assertTrue(detector.feed(daylight, 5001, 6400))
        assertFalse(detector.feed(daylight, 6401, 6700))
    }

    @Test fun theBandBetweenDoesNotMakeABrightSceneDark() {
        val detector = NightSceneDetector()
        assertFalse(detector.feed(between, 0, 10_000))
    }

    @Test fun missingOrInvalidReadingsAreIgnored() {
        val detector = NightSceneDetector()
        detector.feed(dim, 0, 1100)
        assertTrue(detector.update(2000 * ms, null, 1L))
        assertTrue(detector.update(2010 * ms, 100, null))
        assertTrue(detector.update(2020 * ms, 0, 1_000L))
        assertTrue(detector.dark)
    }

    @Test fun aGapInSamplesRestartsThePendingTimerButKeepsTheVerdict() {
        val detector = NightSceneDetector()
        detector.feed(dim, 0, 800)
        // Night was engaged: no results for a while, then the scene is still dark.
        assertFalse(detector.update(5000 * ms, dim.first, dim.second))
        assertFalse(detector.update(5900 * ms, dim.first, dim.second))
        assertTrue(detector.update(6000 * ms, dim.first, dim.second))
        // Frozen while no results arrive.
        assertTrue(detector.update(60_000 * ms, null, null))
    }

    @Test fun resetReturnsToBright() {
        val detector = NightSceneDetector()
        detector.feed(dim, 0, 1100)
        detector.reset()
        assertFalse(detector.dark)
    }

    @Test fun modeRequestRules() {
        for (transient in listOf(false, true)) {
            assertFalse(nightPhotoRequested(NightPhotoMode.OFF, transient))
            assertTrue(nightPhotoRequested(NightPhotoMode.ON, transient))
        }
        assertFalse(nightPhotoRequested(NightPhotoMode.AUTO, false))
        assertTrue(nightPhotoRequested(NightPhotoMode.AUTO, true))
        assertEquals(3, NightPhotoMode.entries.size)
    }
}
