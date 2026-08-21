/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CompositionGuideTest {
    @Test
    fun thirdsReturnsTwoThirdDivisions() {
        val v = CompositionGridGeometry.verticalDivisions(CompositionGridMode.THIRDS)
        assertEquals(2, v.size)
        assertEquals(1f / 3f, v[0], 0.001f)
        assertEquals(2f / 3f, v[1], 0.001f)
        val h = CompositionGridGeometry.horizontalDivisions(CompositionGridMode.THIRDS)
        assertEquals(v.toList(), h.toList())
    }

    @Test
    fun fourByFourReturnsQuarterDivisions() {
        val v = CompositionGridGeometry.verticalDivisions(CompositionGridMode.FOUR_BY_FOUR)
        assertEquals(3, v.size)
        assertEquals(0.25f, v[0], 0.001f)
        assertEquals(0.5f, v[1], 0.001f)
        assertEquals(0.75f, v[2], 0.001f)
    }

    @Test
    fun goldenRatioReturnsPhiDivisions() {
        val v = CompositionGridGeometry.verticalDivisions(CompositionGridMode.GOLDEN_RATIO)
        assertEquals(2, v.size)
        assertEquals(0.382f, v[0], 0.001f)
        assertEquals(0.618f, v[1], 0.001f)
    }

    @Test
    fun diagonalReturnsNoDivisions() {
        assertTrue(CompositionGridGeometry.verticalDivisions(CompositionGridMode.DIAGONAL).isEmpty())
        assertTrue(CompositionGridGeometry.horizontalDivisions(CompositionGridMode.DIAGONAL).isEmpty())
        assertTrue(CompositionGridGeometry.isDiagonal(CompositionGridMode.DIAGONAL))
    }

    @Test
    fun diagonalsSpanFullCanvasCorners() {
        val size = Size(100f, 50f)
        val diags = CompositionGridGeometry.diagonals(size)
        assertEquals(2, diags.size)
        assertEquals(Offset(0f, 0f), diags[0].first)
        assertEquals(Offset(100f, 50f), diags[0].second)
        assertEquals(Offset(100f, 0f), diags[1].first)
        assertEquals(Offset(0f, 50f), diags[1].second)
    }

    @Test
    fun rollDegreesUprightIsNearZero() {
        val roll = HorizonRollMath.rollDegrees(0f, 9.8f, 0f, 0)
        assertTrue("roll=$roll", abs(roll) < 0.5f)
    }

    @Test
    fun rollDegreesTiltedRightIsSignificant() {
        val roll = HorizonRollMath.rollDegrees(5f, 9.8f, 0f, 0)
        assertTrue("roll=$roll", abs(roll) > 20f)
    }

    @Test
    fun rollDegreesFlatReturnsNaN() {
        val roll = HorizonRollMath.rollDegrees(0f, 0f, 9.8f, 0)
        assertTrue(roll.isNaN())
    }

    @Test
    fun rollDegreesRotatesWithDisplayRotation90() {
        val roll0 = HorizonRollMath.rollDegrees(5f, 9.8f, 0f, 0)
        val roll90 = HorizonRollMath.rollDegrees(5f, 9.8f, 0f, 90)
        assertTrue(abs(abs(roll0) - abs(roll90)) < 1f || roll0 * roll90 < 0f)
    }

    @Test
    fun smoothConvergesTowardTarget() {
        var v: Float? = 0f
        repeat(20) { v = HorizonRollMath.smooth(v, 10f, 0.3f) }
        assertEquals(10f, v!!, 0.5f)
    }

    @Test
    fun smoothPreservesPreviousOnNaN() {
        val v = HorizonRollMath.smooth(5f, Float.NaN, 0.5f)
        assertEquals(5f, v, 0.001f)
    }

    @Test
    fun smoothNullPreviousAdoptsTarget() {
        val v = HorizonRollMath.smooth(null, 7f, 0.5f)
        assertEquals(7f, v, 0.001f)
    }
}
