/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhiteBalanceSelectionTest {

    @Test
    fun labelsAreHumanReadable() {
        assertEquals("AUTO", WhiteBalanceSelection.Auto.label())
        assertEquals("5600K", WhiteBalanceSelection.Kelvin(5600).label())
        assertEquals("DAY", WhiteBalanceSelection.Preset(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT).label())
        assertEquals("CLOUD", WhiteBalanceSelection.Preset(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT).label())
        assertEquals("TUNG", WhiteBalanceSelection.Preset(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT).label())
        assertEquals("FLUO", WhiteBalanceSelection.Preset(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT).label())
    }

    @Test
    fun snapKelvinRoundsToNearest100AndClamps() {
        val range = 1500..10000
        assertEquals(5600, snapKelvinTo100(5650, range))
        assertEquals(3200, snapKelvinTo100(3250, range))
        assertEquals(1500, snapKelvinTo100(100, range))
        assertEquals(10000, snapKelvinTo100(11000, range))
        assertEquals(1500, snapKelvinTo100(1500, range))
        assertEquals(10000, snapKelvinTo100(10000, range))
    }

    @Test
    fun snapKelvinReturnsNullWhenRangeIsNull() {
        assertNull(snapKelvinTo100(5600, null))
    }

    @Test
    fun snapKelvinReturnsNullForDegenerateRange() {
        assertNull(snapKelvinTo100(5000, 5000..5000))
    }

    @Test
    fun adaptToKeepsKelvinWhenInRange() {
        val range = 1500..10000
        val adapted = WhiteBalanceSelection.Kelvin(5600).adaptTo(range)
        assertEquals(WhiteBalanceSelection.Kelvin(5600), adapted)
    }

    @Test
    fun adaptToClampsKelvinWhenOutOfRange() {
        val range = 2000..8000
        val adapted = WhiteBalanceSelection.Kelvin(6500).adaptTo(range)
        assertEquals(WhiteBalanceSelection.Kelvin(6500), adapted)
        val adaptedLow = WhiteBalanceSelection.Kelvin(1000).adaptTo(range)
        assertEquals(WhiteBalanceSelection.Kelvin(2000), adaptedLow)
        val adaptedHigh = WhiteBalanceSelection.Kelvin(10000).adaptTo(range)
        assertEquals(WhiteBalanceSelection.Kelvin(8000), adaptedHigh)
    }

    @Test
    fun adaptToFallsBackToAutoWhenKelvinUnsupported() {
        val adapted = WhiteBalanceSelection.Kelvin(5600).adaptTo(null)
        assertEquals(WhiteBalanceSelection.Auto, adapted)
    }

    @Test
    fun adaptToPreservesAutoAndPresets() {
        assertEquals(WhiteBalanceSelection.Auto, WhiteBalanceSelection.Auto.adaptTo(1500..10000))
        assertEquals(WhiteBalanceSelection.Auto, WhiteBalanceSelection.Auto.adaptTo(null))
        val preset = WhiteBalanceSelection.Preset(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)
        assertEquals(preset, preset.adaptTo(1500..10000))
        assertEquals(preset, preset.adaptTo(null))
    }
}

