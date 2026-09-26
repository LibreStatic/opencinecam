/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.os.PowerManager
import com.librestatic.opencinecam.core.model.RuntimeAction
import com.librestatic.opencinecam.core.model.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decisions the engine's YUV analysis reader takes through [observeLatestFrameReader]. */
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
    fun readerKeepsAnalysisUpToModeratePressureAndWhenStatusIsUnknown() {
        val governor = AnalysisPerformanceGovernor(queueCapacity = 2)
        for (status in listOf(PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE, -1)) {
            assertEquals(AnalysisGovernorAction.KEEP, governor.observeLatestFrameReader(status).action)
        }
        assertFalse(governor.isDisabled())
    }

    @Test
    fun severeOrWorsePressureDisablesAnalysisForTheRestOfTheGraph() {
        for (status in listOf(PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_SHUTDOWN)) {
            val governor = AnalysisPerformanceGovernor(queueCapacity = 2)
            val decision = governor.observeLatestFrameReader(status)
            assertEquals(AnalysisGovernorAction.DISABLE, decision.action)
            assertEquals(RuntimeAction.DEGRADE_MONITORING, decision.runtimeAction)
            assertTrue(governor.isDisabled())
            // Latched: cooling down does not re-enable the scopes within the same graph.
            assertEquals(AnalysisGovernorAction.DISABLE, governor.observeLatestFrameReader(PowerManager.THERMAL_STATUS_NONE).action)
        }
    }
}
