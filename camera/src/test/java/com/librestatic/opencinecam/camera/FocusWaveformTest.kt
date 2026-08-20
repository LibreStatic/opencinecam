/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusWaveformTest {
    @Test
    fun focusPeakingReturnsCalibratedCoordinatesAndHonorsControls() {
        val frame = LumaFrame(3, 2, byteArrayOf(0.toByte(), 0.toByte(), 255.toByte(), 0.toByte(), 0.toByte(), 255.toByte()))
        val result = FocusPeakingComputer().compute(frame, FocusPeakingControls(threshold = 0.4, maxPoints = 1)) as FocusPeakingResult.Computed
        assertEquals(1, result.points.size)
        assertTrue(result.points.single().x in 0.0..1.0)
        assertTrue(result.points.single().y in 0.0..1.0)
        assertTrue(FocusPeakingComputer().compute(frame, FocusPeakingControls(enabled = false)) is FocusPeakingResult.Disabled)
    }

    @Test
    fun waveformUsesNormalizedColumnCoordinatesAndBounds() {
        val frame = LumaFrame(4, 2, byteArrayOf(0.toByte(), 64.toByte(), 128.toByte(), 255.toByte(), 0.toByte(), 64.toByte(), 128.toByte(), 255.toByte()))
        val waveform = WaveformComputer().compute(frame, columns = 2) as WaveformResult.Computed
        assertEquals(2, waveform.columns.size)
        assertEquals(0.0, waveform.columns.first().minimum, 0.001)
        assertEquals(1.0, waveform.columns.last().maximum, 0.001)
        assertTrue(WaveformComputer(maxPixels = 2).compute(frame) is WaveformResult.Rejected)
    }
}
