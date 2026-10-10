/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ExposureSelection
import com.librestatic.opencinecam.camera.ShutterUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class FlickerSafeValueTest {
    @Test
    fun showsBothFrequenciesWhenNoSuggestionIsInUse() {
        assertEquals("50 Hz · 60 Hz", flickerSafeValue(ExposureSelection(), timeApplied = false))
        assertEquals("50 Hz · 60 Hz", flickerSafeValue(ExposureSelection(mode = ExposureMode.MANUAL, timeNs = 20_000_000L), timeApplied = true))
    }

    @Test
    fun namesTheSuggestionTheShutterMatches() {
        val manual = ExposureSelection(mode = ExposureMode.MANUAL, timeNs = 10_000_000L)
        assertEquals("1/100 · 50 Hz", flickerSafeValue(manual, timeApplied = true))
        assertEquals("1/120 · 60 Hz", flickerSafeValue(manual.copy(timeNs = 8_333_333L), timeApplied = true))
    }

    @Test
    fun ignoresAStoredTimeTheModeDoesNotApply() {
        val stored = ExposureSelection(timeNs = 10_000_000L)
        assertEquals("50 Hz · 60 Hz", flickerSafeValue(stored, timeApplied = false))
        assertEquals("50 Hz · 60 Hz", flickerSafeValue(stored.copy(shutterUnit = ShutterUnit.ANGLE), timeApplied = true))
    }
}
