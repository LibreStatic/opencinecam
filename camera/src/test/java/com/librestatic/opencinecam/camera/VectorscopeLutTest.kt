/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorscopeLutTest {
    @Test
    fun vectorscopeProducesNormalizedChromaPointsWithinBudget() {
        val frame = RgbFrame(2, 1, byteArrayOf(255.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 255.toByte()))
        val result = VectorScopeComputer(gridSize = 8).compute(frame) as VectorScopeResult.Computed
        assertEquals(2, result.points.sumOf { it.count })
        assertTrue(result.points.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
        assertTrue(VectorScopeComputer(maxPixels = 1).compute(frame) is VectorScopeResult.Rejected)
    }

    @Test
    fun monitoringLutCopiesInputAndPreservesRecordingBuffer() {
        val input = byteArrayOf(0.toByte(), 127.toByte(), 255.toByte())
        val original = input.copyOf()
        val lut = MonitoringLut(LutProvenance("identity", "fixture", "1"), ByteArray(256) { it.toByte() })
        val result = MonitoringLutBoundary().apply(input, lut) as MonitoringLutResult.Applied
        assertArrayEquals(original, input)
        assertArrayEquals(original, result.frame.data)
        assertTrue(result.frame.recordingBufferUntouched)
        assertEquals("identity", result.frame.provenance.id)
    }
}
