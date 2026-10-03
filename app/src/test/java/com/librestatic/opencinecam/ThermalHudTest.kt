/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalHudTest {
    @Test fun statusMapsToTheShutdownRiskLevel() {
        assertEquals(ThermalHudLevel.NORMAL, thermalHudReading(PowerManager.THERMAL_STATUS_NONE, null).level)
        assertEquals(ThermalHudLevel.NORMAL, thermalHudReading(PowerManager.THERMAL_STATUS_LIGHT, null).level)
        assertEquals(ThermalHudLevel.ELEVATED, thermalHudReading(PowerManager.THERMAL_STATUS_MODERATE, null).level)
        assertEquals(ThermalHudLevel.HIGH, thermalHudReading(PowerManager.THERMAL_STATUS_SEVERE, null).level)
        assertEquals(ThermalHudLevel.CRITICAL, thermalHudReading(PowerManager.THERMAL_STATUS_CRITICAL, null).level)
        assertEquals(ThermalHudLevel.CRITICAL, thermalHudReading(PowerManager.THERMAL_STATUS_EMERGENCY, null).level)
        assertEquals(ThermalHudLevel.CRITICAL, thermalHudReading(PowerManager.THERMAL_STATUS_SHUTDOWN, null).level)
    }

    @Test fun headroomIsShownAsLoadAndWarnsBeforeTheStatusChanges() {
        val cool = thermalHudReading(PowerManager.THERMAL_STATUS_NONE, 0.42f)
        assertEquals(ThermalHudReading(ThermalHudLevel.NORMAL, 42), cool)
        val rising = thermalHudReading(PowerManager.THERMAL_STATUS_LIGHT, 0.9f)
        assertEquals(ThermalHudReading(ThermalHudLevel.ELEVATED, 90), rising)
        // A worse status always wins over an optimistic forecast.
        assertEquals(ThermalHudLevel.HIGH, thermalHudReading(PowerManager.THERMAL_STATUS_SEVERE, 0.2f).level)
    }

    @Test fun missingOrInvalidHeadroomFallsBackToStatusOnly() {
        assertNull(thermalHudReading(PowerManager.THERMAL_STATUS_NONE, Float.NaN).loadPercent)
        assertNull(thermalHudReading(PowerManager.THERMAL_STATUS_NONE, -1f).loadPercent)
        assertEquals(999, thermalHudReading(PowerManager.THERMAL_STATUS_CRITICAL, 42f).loadPercent)
    }

    @Test fun aPercentCalmerThanTheStatusIsReplacedByTheLevel() {
        // Forced/real SEVERE while the forecast still reads 43 % must not look reassuring.
        assertFalse(thermalHudReading(PowerManager.THERMAL_STATUS_SEVERE, 0.43f).showsPercent)
        assertTrue(thermalHudReading(PowerManager.THERMAL_STATUS_NONE, 0.43f).showsPercent)
        assertTrue(thermalHudReading(PowerManager.THERMAL_STATUS_LIGHT, 0.9f).showsPercent)
        assertTrue(thermalHudReading(PowerManager.THERMAL_STATUS_SEVERE, 1.05f).showsPercent)
        assertFalse(thermalHudReading(PowerManager.THERMAL_STATUS_NONE, null).showsPercent)
    }

    @Test fun theChipOnlyShowsWhenTheHeatMatters() {
        fun visible(status: Int, headroom: Float?, recording: Boolean) =
            thermalHudVisible(status, thermalHudReading(status, headroom), recording)
        // A cool device shows nothing, recording or not.
        assertFalse(visible(PowerManager.THERMAL_STATUS_NONE, 0.42f, recording = true))
        assertFalse(visible(PowerManager.THERMAL_STATUS_LIGHT, null, recording = true))
        // A rising forecast alone only matters while a take runs.
        assertFalse(visible(PowerManager.THERMAL_STATUS_LIGHT, 0.9f, recording = false))
        assertTrue(visible(PowerManager.THERMAL_STATUS_LIGHT, 0.9f, recording = true))
        // Heat the platform itself reports is always shown.
        assertTrue(visible(PowerManager.THERMAL_STATUS_MODERATE, null, recording = false))
        assertTrue(visible(PowerManager.THERMAL_STATUS_CRITICAL, null, recording = false))
    }
}
