/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareTruthHudTest {
    @Test
    fun unknownAndUnsupportedControlsRemainLockedWithReason() {
        val controls = manualControls(
            mapOf("exposure" to "Manual", "focus" to "Auto"),
            ManualControlAvailability(exposure = ControlAvailability.SUPPORTED, focus = ControlAvailability.UNKNOWN, whiteBalance = ControlAvailability.UNSUPPORTED),
        )
        assertTrue(controls.first { it.key == "exposure" }.enabled)
        assertFalse(controls.first { it.key == "focus" }.enabled)
        assertTrue(controls.first { it.key == "focus" }.lockReason!!.contains("unknown"))
        assertTrue(controls.first { it.key == "whiteBalance" }.lockReason!!.contains("Unsupported"))
    }

    @Test
    fun hudRecordsRequestedReportedDiscrepancyAndLocks() {
        val hud = HardwareTruthHudState(requested = mapOf("iso" to "100"))
            .withReport("iso", "200")
            .lock("exposure")
        assertEquals("200", hud.reported["iso"])
        assertEquals(1, hud.discrepancyHistory.size)
        assertEquals(RouteTruth.LOGICAL, hud.routeTruth)
        assertTrue("exposure" in hud.lockedControls)
    }

    @Test
    fun routeTruthDistinguishesLogicalConfirmedUnknownAndMismatch() {
        assertEquals(RouteTruth.LOGICAL, routeTruthOf(null, "2"))
        assertEquals(RouteTruth.PHYSICAL_UNKNOWN, routeTruthOf("2", null))
        assertEquals(RouteTruth.PHYSICAL_CONFIRMED, routeTruthOf("2", "2"))
        assertEquals(RouteTruth.MISMATCH, routeTruthOf("2", "3"))
    }

    @Test
    fun mismatchedRouteIsRecordedOnceAndSurvivesRepeatedResults() {
        val hud = HardwareTruthHudState().withRoute("2", "3")
        assertEquals(RouteTruth.MISMATCH, hud.routeTruth)
        assertEquals(1, hud.discrepancyHistory.size)
        assertEquals("2", hud.discrepancyHistory.single().requested)
        assertEquals("3", hud.discrepancyHistory.single().reported)
        // The same pair again is not news: same state, same history.
        assertEquals(hud, hud.withRoute("2", "3"))
        val confirmed = hud.withRoute("2", "2")
        assertEquals(RouteTruth.PHYSICAL_CONFIRMED, confirmed.routeTruth)
        assertEquals(1, confirmed.discrepancyHistory.size)
        assertEquals(RouteTruth.LOGICAL, confirmed.withRoute(null, "2").routeTruth)
    }

    @Test
    fun discrepancyHistoryIsBounded() {
        var hud = HardwareTruthHudState()
        repeat(MAX_DISCREPANCY_HISTORY * 3) { index -> hud = hud.withRoute("2", "other-$index") }
        assertEquals(MAX_DISCREPANCY_HISTORY, hud.discrepancyHistory.size)
        assertEquals("other-${MAX_DISCREPANCY_HISTORY * 3 - 1}", hud.discrepancyHistory.last().reported)
        var reports = HardwareTruthHudState(requested = (0 until MAX_DISCREPANCY_HISTORY * 2).associate { "k$it" to "a" })
        repeat(MAX_DISCREPANCY_HISTORY * 2) { reports = reports.withReport("k$it", "b") }
        assertEquals(MAX_DISCREPANCY_HISTORY, reports.discrepancyHistory.size)
    }

    @Test
    fun activeLensFollowsZoomWithoutEverBeingADiscrepancy() {
        val routed = HardwareTruthHudState().withRoute("2", "2")
        val zoomed = routed.withActiveLens("3")
        assertEquals("3", zoomed.reported[ACTIVE_LENS_KEY])
        assertEquals(RouteTruth.PHYSICAL_CONFIRMED, zoomed.routeTruth)
        assertEquals(emptyList<HudDiscrepancy>(), zoomed.discrepancyHistory)
        assertEquals(zoomed, zoomed.withActiveLens("3"))
        assertEquals(null, zoomed.withActiveLens(null).reported[ACTIVE_LENS_KEY])
    }

    private fun sample(reported: String, within: Boolean = false) = ReadbackSample("android.sensor.sensitivity", "100", reported, within)

    @Test
    fun aWrongValueIsOneHistoryEntryUntilItMatchesAgain() {
        var hud = HardwareTruthHudState()
        repeat(500) { hud = hud.withReadback(listOf(sample("${200 + it % 3}"))) }
        assertEquals(1, hud.discrepancyHistory.size)
        assertEquals(setOf("android.sensor.sensitivity"), hud.openDiscrepancies)
        hud = hud.withReadback(listOf(sample("100", within = true)))
        assertTrue(hud.openDiscrepancies.isEmpty())
        hud = hud.withReadback(listOf(sample("400")))
        assertEquals(2, hud.discrepancyHistory.size)
        assertEquals("400", hud.discrepancyHistory.last().reported)
    }

    @Test
    fun aValueWithinToleranceIsNotADiscrepancyAndAClearedRequestClosesOne() {
        val near = HardwareTruthHudState().withReadback(listOf(sample("101", within = true)))
        assertTrue(near.discrepancyHistory.isEmpty())
        assertEquals("100", near.reported["android.sensor.sensitivity"])
        val open = HardwareTruthHudState().withReadback(listOf(sample("300")))
        assertTrue(open.withReadback(emptyList()).openDiscrepancies.isEmpty())
    }

    @Test
    fun warningsAreDeduplicatedAndBounded() {
        var hud = HardwareTruthHudState()
        repeat(5) { hud = hud.withWarning("CADENCE_DISCONTINUITY: cadence") }
        assertEquals(listOf("CADENCE_DISCONTINUITY: cadence"), hud.warnings)
        repeat(MAX_HUD_WARNINGS * 2) { hud = hud.withWarning("w$it") }
        assertEquals(MAX_HUD_WARNINGS, hud.warnings.size)
        assertEquals("w${MAX_HUD_WARNINGS * 2 - 1}", hud.warnings.last())
        assertEquals(hud, hud.withWarning(" "))
    }
}
