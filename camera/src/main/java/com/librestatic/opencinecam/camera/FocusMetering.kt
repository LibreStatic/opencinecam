/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlin.math.roundToInt

/** Camera-active-array bounds expressed without Android dependencies for deterministic tests. */
data class SensorBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right > left)
        require(bottom > top)
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class FocusMeteringArea(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Pure display-to-sensor mapper shared by Camera2 and local unit tests. */
object FocusMeteringMapper {
    fun map(
        normalizedX: Float,
        normalizedY: Float,
        activeArray: SensorBounds,
        streamWidth: Int,
        streamHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
        regionFraction: Float = 0.10f,
        cropRegion: SensorBounds? = null,
    ): FocusMeteringArea {
        require(streamWidth > 0 && streamHeight > 0)
        require(regionFraction > 0f && regionFraction <= 1f)
        val rotation = if (frontFacing) {
            normalize(sensorOrientationDegrees + displayRotationDegrees)
        } else {
            normalize(sensorOrientationDegrees - displayRotationDegrees)
        }

        // The user taps the displayed image. Undo the conventional front-camera mirror first,
        // then undo the sensor-to-display quarter-turn to return to sensor coordinates.
        val displayX = (if (frontFacing) 1f - normalizedX else normalizedX).coerceIn(0f, 1f)
        val displayY = normalizedY.coerceIn(0f, 1f)
        val (sensorX, sensorY) = when (rotation) {
            0 -> displayX to displayY
            90 -> displayY to (1f - displayX)
            180 -> (1f - displayX) to (1f - displayY)
            270 -> (1f - displayY) to displayX
            else -> error("Unsupported rotation $rotation")
        }

        val crop = cropRegion ?: centerCrop(activeArray, streamWidth.toFloat() / streamHeight)
        val centerX = crop.left + sensorX * crop.width
        val centerY = crop.top + sensorY * crop.height
        val side = (minOf(crop.width, crop.height) * regionFraction).roundToInt().coerceAtLeast(1)
            .coerceAtMost(minOf(crop.width, crop.height))
        val half = side / 2
        val left = (centerX.roundToInt() - half).coerceIn(crop.left, crop.right - side)
        val top = (centerY.roundToInt() - half).coerceIn(crop.top, crop.bottom - side)
        return FocusMeteringArea(left, top, left + side, top + side)
    }

    private fun centerCrop(bounds: SensorBounds, targetAspect: Float): SensorBounds {
        val currentAspect = bounds.width.toFloat() / bounds.height
        return if (currentAspect > targetAspect) {
            val width = (bounds.height * targetAspect).roundToInt().coerceIn(1, bounds.width)
            val left = bounds.left + (bounds.width - width) / 2
            SensorBounds(left, bounds.top, left + width, bounds.bottom)
        } else {
            val height = (bounds.width / targetAspect).roundToInt().coerceIn(1, bounds.height)
            val top = bounds.top + (bounds.height - height) / 2
            SensorBounds(bounds.left, top, bounds.right, top + height)
        }
    }

    private fun normalize(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "Focus orientation must be a multiple of 90 degrees." }
        return normalized
    }
}

