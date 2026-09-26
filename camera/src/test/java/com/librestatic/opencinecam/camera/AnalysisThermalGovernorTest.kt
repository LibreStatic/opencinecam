/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.os.PowerManager
import com.librestatic.opencinecam.core.model.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Decisions the engine's scope analysis takes through [ThermalAnalysisGovernor]. */
class AnalysisThermalGovernorTest {
    @Test
    fun platformThermalStatusMapsToModelScale() {
        assertEquals(ThermalStatus.NORMAL, thermalStatusFromPlatform(PowerManager.THERMAL_STATUS_NONE))
        assertEquals(ThermalStatus.NORMAL, thermalStatusFromPlatform(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(ThermalStatus.MODERATE, thermalStatusFromPlatform(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(ThermalStatus.SEVERE, thermalStatusFromPlatform(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(ThermalStatus.CRITICAL, thermalStatusFromPlatform(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals(ThermalStatus.CRITICAL, thermalStatusFromPlatform(PowerManager.THERMAL_STATUS_EMERGENCY))
        assertEquals(ThermalStatus.CRITICAL, thermalStatusFromPlatform(PowerManager.THERMAL_STATUS_SHUTDOWN))
        // The engine passes -1 when PowerManager is unavailable.
        assertEquals(ThermalStatus.UNKNOWN, thermalStatusFromPlatform(-1))
        assertEquals(ThermalStatus.UNKNOWN, thermalStatusFromPlatform(99))
    }

    @Test
    fun analysisKeepsRunningUpToModeratePressureAndWhenStatusIsUnknown() {
        val governor = ThermalAnalysisGovernor()
        for (status in listOf(PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE, -1, 99)) {
            assertNull(governor.observePlatformStatus(status))
        }
        assertEquals(AnalysisSuspension.NONE, governor.state)
    }

    @Test
    fun severeOrWorsePressureSuspendsAnalysisOnce() {
        for (status in listOf(PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN)) {
            val governor = ThermalAnalysisGovernor()
            assertEquals(AnalysisSuspension.THERMAL, governor.observePlatformStatus(status))
            assertEquals(AnalysisSuspension.THERMAL, governor.state)
            // Only changes are reported, so the listener is not re-notified while it stays hot.
            assertNull(governor.observePlatformStatus(status))
            assertNull(governor.observePlatformStatus(PowerManager.THERMAL_STATUS_SEVERE))
        }
    }

    @Test
    fun resumesOnlyBelowModerateWithHysteresis() {
        val governor = ThermalAnalysisGovernor()
        assertEquals(AnalysisSuspension.THERMAL, governor.observePlatformStatus(PowerManager.THERMAL_STATUS_SEVERE))
        // MODERATE and unknown hold the suspension: no flapping at the SEVERE boundary.
        assertNull(governor.observePlatformStatus(PowerManager.THERMAL_STATUS_MODERATE))
        assertNull(governor.observePlatformStatus(-1))
        assertEquals(AnalysisSuspension.THERMAL, governor.state)
        assertEquals(AnalysisSuspension.NONE, governor.observePlatformStatus(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(AnalysisSuspension.NONE, governor.state)
        // Not latched: a second escalation suspends again, a cool-down resumes again.
        assertNull(governor.observePlatformStatus(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(AnalysisSuspension.THERMAL, governor.observePlatformStatus(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals(AnalysisSuspension.NONE, governor.observePlatformStatus(PowerManager.THERMAL_STATUS_NONE))
    }
}
