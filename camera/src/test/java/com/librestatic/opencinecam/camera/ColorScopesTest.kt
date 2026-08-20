/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorScopesTest {
    @Test
    fun computesDeterministicHistogramZebraAndFalseColorBands() {
        val frame = LumaFrame(5, 1, byteArrayOf(0.toByte(), 32.toByte(), 128.toByte(), 230.toByte(), 255.toByte()))
        val result = ColorScopeComputer().compute(frame) as ScopeComputation.Computed
        assertEquals(1, result.histogram.bins[0])
        assertEquals(1, result.histogram.bins[255])
        assertEquals(FalseColorBand.BLACK, result.overlay.falseColor[0])
        assertEquals(FalseColorBand.SHADOW, result.overlay.falseColor[1])
        assertEquals(FalseColorBand.MID, result.overlay.falseColor[2])
        assertEquals(FalseColorBand.HIGHLIGHT, result.overlay.falseColor[3])
        assertEquals(FalseColorBand.CLIP, result.overlay.falseColor[4])
        assertTrue(result.overlay.zebra[0])
        assertTrue(result.overlay.zebra[4])
    }

    @Test
    fun rejectsFramesAbovePerformanceBudget() {
        val rejected = ColorScopeComputer(maxPixels = 2).compute(LumaFrame(3, 1, ByteArray(3)))
        assertTrue(rejected is ScopeComputation.Rejected)
    }
}
