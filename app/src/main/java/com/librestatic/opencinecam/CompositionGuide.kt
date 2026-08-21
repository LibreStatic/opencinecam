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
     * @param x sensor gravity X axis.
     * @param y sensor gravity Y axis.
     * @param z sensor gravity Z axis.
     * @param displayRotation One of Surface.ROTATION_0/90/180/270.
     * @return Signed roll in degrees in the range [-90, 90], or NaN when not measurable.
     */
    fun rollDegrees(x: Float, y: Float, z: Float, displayRotation: Int): Float {
        if (z * z > 6f && x * x + y * y < 3f) return Float.NaN
        val rollRad = when (displayRotation) {
            90 -> Math.atan2(-y.toDouble(), -x.toDouble())
            180 -> Math.atan2(x.toDouble(), -y.toDouble())
            270 -> Math.atan2(y.toDouble(), x.toDouble())
            else -> Math.atan2(-x.toDouble(), y.toDouble())
        }.toFloat()
        var roll = Math.toDegrees(rollRad.toDouble()).toFloat()
        // Normalize to [-90, 90] — atan2 can return angles outside this range when the
        // gravity vector points into the third/fourth quadrant after display rotation.
        while (roll > 90f) roll -= 180f
        while (roll < -90f) roll += 180f
        return roll
    }

    /** Exponential smoothing toward the latest sample; null previous keeps the previous value. */
    fun smooth(previous: Float?, target: Float, alpha: Float): Float {
        if (target.isNaN()) return previous ?: Float.NaN
        if (previous == null || previous.isNaN()) return target
        return previous + (target - previous) * alpha.coerceIn(0f, 1f)
    }
}
