/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import kotlin.math.abs

/** A rectangle in 0..1 frame fractions; may extend beyond the frame until clamped. */
data class NormRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

/**
 * Camera2 never crops RAW_SENSOR streams, so the framing the user saw through digital zoom is
 * recorded in the DNG as DefaultCrop instead. This is the pure math behind that.
 */
object RawZoomCrop {
    private const val UNITY_TOLERANCE = 0.05
    private const val FULL_FRAME_EPSILON = 0.005

    /** Field of view of the processed outputs relative to the logical active array. */
    fun processedFieldOfView(activeWidth: Int, activeHeight: Int, cropRegion: CropRect?, zoomRatio: Float?): NormRect {
        val w = activeWidth.toDouble()
        val h = activeHeight.toDouble()
        if (cropRegion == null) {
            val zoom = zoomRatio?.toDouble()?.takeIf { it > 0.0 } ?: 1.0
            return NormRect(0.5 - 0.5 / zoom, 0.5 - 0.5 / zoom, 0.5 + 0.5 / zoom, 0.5 + 0.5 / zoom)
        }
        val zoom = zoomRatio?.toDouble()?.takeIf { it > 0.0 } ?: 1.0
        fun map(v: Int, size: Double) = 0.5 + (v / size - 0.5) / zoom
        return NormRect(map(cropRegion.left, w), map(cropRegion.top, h), map(cropRegion.right, w), map(cropRegion.bottom, h))
    }

    /** Horizontal field-of-view width, proportional to the returned value; null when data is unusable. */
    fun fovWidth(physicalWidthMm: Float?, activeWidth: Int, pixelArrayWidth: Int, focalMm: Float?): Double? {
        if (physicalWidthMm == null || focalMm == null || physicalWidthMm <= 0f || focalMm <= 0f ||
            activeWidth <= 0 || pixelArrayWidth <= 0) return null
        return physicalWidthMm.toDouble() * activeWidth / pixelArrayWidth / focalMm
    }

    /** logical FOV width / active physical FOV width; scales within 5% of 1 are exactly 1. */
    fun rawLensScale(logicalFovWidth: Double?, physicalFovWidth: Double?): Double {
        if (logicalFovWidth == null || physicalFovWidth == null || logicalFovWidth <= 0.0 || physicalFovWidth <= 0.0) return 1.0
        val scale = logicalFovWidth / physicalFovWidth
        return if (abs(scale - 1.0) <= UNITY_TOLERANCE) 1.0 else scale
    }

    /** The crop to record in the RAW frame, or null when it is effectively the full frame. */
    fun rawCrop(fov: NormRect, rawLensScale: Double): NormRect? {
        fun map(v: Double) = (0.5 + (v - 0.5) * rawLensScale).coerceIn(0.0, 1.0)
        val rect = NormRect(map(fov.left), map(fov.top), map(fov.right), map(fov.bottom))
        if (rect.width <= 0.0 || rect.height <= 0.0) return null
        val full = rect.left <= FULL_FRAME_EPSILON && rect.top <= FULL_FRAME_EPSILON &&
            rect.right >= 1.0 - FULL_FRAME_EPSILON && rect.bottom >= 1.0 - FULL_FRAME_EPSILON
        return if (full) null else rect
    }
}
