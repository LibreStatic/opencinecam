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
}
