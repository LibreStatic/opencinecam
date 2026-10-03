/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class HorizonRollMathTest {
    private val g = 9.81f

    /**
     * Gravity as the sensor reads it (device axes) when the display is turned [rotation] degrees
     * counter-clockwise and the screen's right edge is [rightDown] degrees below level.
     */
    private fun gravity(rotation: Int, rightDown: Float): Pair<Float, Float> {
        val t = Math.toRadians(rightDown.toDouble())
        // Upward reaction in screen axes (x right, y up), then back to device axes.
        val sx = (-g * sin(t)).toFloat(); val sy = (g * cos(t)).toFloat()
        return when (rotation) {
            90 -> sy to -sx
            180 -> -sx to -sy
            270 -> -sy to sx
            else -> sx to sy
        }
    }

    @Test fun surfaceRotationConstantsBecomeDegrees() {
        assertEquals(listOf(0, 90, 180, 270), (0..3).map { HorizonRollMath.surfaceRotationDegrees(it) })
    }

    @Test fun aLevelDeviceReadsZeroInEveryDisplayRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val (x, y) = gravity(rotation, 0f)
            assertEquals("rotation $rotation", 0f, HorizonRollMath.rollDegrees(x, y, 0f, rotation), .01f)
        }
    }

    @Test fun landscapeGravityOnTheDeviceXAxisIsLevelAtRotation90And270() {
        // Bug #5: a level phone in landscape reads gravity on its X axis. Treating the
        // Surface.ROTATION_90 constant (1) as degrees used the portrait axes and drew a vertical line.
        assertEquals(0f, HorizonRollMath.rollDegrees(g, 0f, 0f, HorizonRollMath.surfaceRotationDegrees(1)), .01f)
        assertEquals(0f, HorizonRollMath.rollDegrees(-g, 0f, 0f, HorizonRollMath.surfaceRotationDegrees(3)), .01f)
    }

    @Test fun tiltSignFollowsTheScreenInEveryRotation() {
        for (rotation in listOf(0, 90, 180, 270)) for (tilt in listOf(-30f, -5f, 5f, 30f)) {
            val (x, y) = gravity(rotation, tilt)
            assertEquals("rotation $rotation tilt $tilt", tilt, HorizonRollMath.rollDegrees(x, y, 0f, rotation), .01f)
        }
    }

    @Test fun screenGravityMapsEachRotation() {
        assertEquals(1f to 2f, HorizonRollMath.screenGravity(1f, 2f, 0))
        assertEquals(-2f to 1f, HorizonRollMath.screenGravity(1f, 2f, 90))
        assertEquals(-1f to -2f, HorizonRollMath.screenGravity(1f, 2f, 180))
        assertEquals(2f to -1f, HorizonRollMath.screenGravity(1f, 2f, 270))
        assertEquals(-2f to 1f, HorizonRollMath.screenGravity(1f, 2f, -270))
    }

    @Test fun theLevelLineIsHorizontalWhenLevelAndTurnsAgainstTheDevice() {
        val (a, b) = HorizonRollMath.horizonLineEnds(Offset(100f, 50f), 40f, 0f)
        assertEquals(Offset(60f, 50f), a)
        assertEquals(Offset(140f, 50f), b)
        // Right edge down by 10 degrees: the real horizon, and the line, rise to the right on screen.
        val (l, r) = HorizonRollMath.horizonLineEnds(Offset(100f, 50f), 40f, 10f)
        assertTrue("right end must be higher", r.y < l.y)
        assertEquals(-10f, HorizonRollMath.horizonLineDegrees(10f), 0f)
    }
}
