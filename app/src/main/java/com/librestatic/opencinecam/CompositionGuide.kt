/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** Composition overlay grid variants. The OFF state is expressed by callers, not here. */
enum class CompositionGridMode {
    THIRDS,
    FOUR_BY_FOUR,
    DIAGONAL,
    GOLDEN_RATIO,
}

/** Pure geometry for the composition grid overlay. No Compose/Android dependency beyond Offset/Size. */
object CompositionGridGeometry {
    private val THIRDS = floatArrayOf(1f / 3f, 2f / 3f)
    private val QUARTERS = floatArrayOf(0.25f, 0.5f, 0.75f)
    private val GOLDEN = floatArrayOf(0.382f, 0.618f)
    private val EMPTY = FloatArray(0)

    /** Returns the normalized vertical division ratios in [0,1]. */
    fun verticalDivisions(mode: CompositionGridMode): FloatArray = when (mode) {
        CompositionGridMode.THIRDS -> THIRDS
        CompositionGridMode.FOUR_BY_FOUR -> QUARTERS
        CompositionGridMode.DIAGONAL -> EMPTY
        CompositionGridMode.GOLDEN_RATIO -> GOLDEN
    }

    /** Returns the normalized horizontal division ratios in [0,1]. */
    fun horizontalDivisions(mode: CompositionGridMode): FloatArray = when (mode) {
        CompositionGridMode.THIRDS -> THIRDS
        CompositionGridMode.FOUR_BY_FOUR -> QUARTERS
        CompositionGridMode.DIAGONAL -> EMPTY
        CompositionGridMode.GOLDEN_RATIO -> GOLDEN
    }

    /** True when the mode draws diagonal segments instead of vertical/horizontal lines. */
    fun isDiagonal(mode: CompositionGridMode): Boolean = mode == CompositionGridMode.DIAGONAL

    /** Returns the two diagonal segments for the given canvas size. */
    fun diagonals(size: Size): List<Pair<Offset, Offset>> = listOf(
        Offset(0f, 0f) to Offset(size.width, size.height),
        Offset(size.width, 0f) to Offset(0f, size.height),
    )
}

/** Continuous roll reading used by the horizon level overlay. */
data class HorizonRollSnapshot(
    /** Signed roll angle in degrees; positive tilts the right side down. NaN when unavailable. */
    val degrees: Float,
    val observedAtElapsedRealtimeMs: Long,
)

/** Thresholds for the horizon line color bands. */
object HorizonRollColors {
    const val LEVEL_BAND = 1f
    const val WARNING_BAND = 3f
}

/**
 * Pure horizon roll sensor math: transforms a gravity vector from sensor space to a signed roll
 * in display space, taking the display rotation into account. Returns NaN when the geometry
 * cannot be measured (for example a flat placement).
 */
object HorizonRollMath {
    /**
     * `Display.getRotation()` returns a `Surface.ROTATION_*` constant (0..3), not degrees. Passing
     * the constant straight to [rollDegrees] made every landscape rotation use the portrait axes,
     * so a level phone in landscape drew the line vertical.
     */
    fun surfaceRotationDegrees(surfaceRotation: Int): Int = (surfaceRotation and 3) * 90

    /**
     * Gravity in screen axes (x right, y up) for a display turned [displayRotationDegrees]
     * counter-clockwise from the device's natural orientation, as `Display.getRotation()` reports.
     */
    fun screenGravity(x: Float, y: Float, displayRotationDegrees: Int): Pair<Float, Float> =
        when ((displayRotationDegrees % 360 + 360) % 360) {
            90 -> -y to x
            180 -> -x to -y
            270 -> y to -x
            else -> x to y
        }

    /**
     * @param x sensor gravity X axis.
     * @param y sensor gravity Y axis.
     * @param z sensor gravity Z axis.
     * @param displayRotationDegrees 0, 90, 180 or 270; see [surfaceRotationDegrees].
     * @return Signed roll in degrees in the range [-90, 90], positive when the screen's right edge
     *   is lower, or NaN when not measurable.
     */
    fun rollDegrees(x: Float, y: Float, z: Float, displayRotationDegrees: Int): Float {
        if (z * z > 6f && x * x + y * y < 3f) return Float.NaN
        val (screenX, screenY) = screenGravity(x, y, displayRotationDegrees)
        var roll = Math.toDegrees(Math.atan2(-screenX.toDouble(), screenY.toDouble())).toFloat()
        // A device held upside down relative to the display reads past ±90; fold it back.
        while (roll > 90f) roll -= 180f
        while (roll < -90f) roll += 180f
        return roll
    }

    /**
     * Screen angle of the level line, clockwise positive as Compose draws it. The line stays
     * parallel to the real horizon, so it turns against the device: it meets the fixed level
     * marks exactly when the camera is level.
     */
    fun horizonLineDegrees(rollDegrees: Float): Float = -rollDegrees.coerceIn(-90f, 90f)

    /** End points of a level line of [halfLength] centred on [center] for a roll reading. */
    fun horizonLineEnds(center: Offset, halfLength: Float, rollDegrees: Float): Pair<Offset, Offset> {
        val radians = Math.toRadians(horizonLineDegrees(rollDegrees).toDouble())
        val dx = (halfLength * Math.cos(radians)).toFloat()
        val dy = (halfLength * Math.sin(radians)).toFloat()
        return Offset(center.x - dx, center.y - dy) to Offset(center.x + dx, center.y + dy)
    }

    /** Exponential smoothing toward the latest sample; null previous keeps the previous value. */
    fun smooth(previous: Float?, target: Float, alpha: Float): Float {
        if (target.isNaN()) return previous ?: Float.NaN
        if (previous == null || previous.isNaN()) return target
        return previous + (target - previous) * alpha.coerceIn(0f, 1f)
    }
}
