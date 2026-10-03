/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusWaveformTest {
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
