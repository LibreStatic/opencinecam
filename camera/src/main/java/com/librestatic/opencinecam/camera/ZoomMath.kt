/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Optical anchor for a logical camera. [ratio] is the zoom multiplier relative to the logical
 * camera's primary physical sensor (lowest focal length that the logical camera reports as its
 * own). [focalLengthMm] is the physical sensor focal length and [physicalCameraId] the Camera2
 * physical id that backs it (null for a synthetic/digital-only anchor such as 1x when metadata
 * is unavailable).
 */
data class ZoomAnchor(
    val ratio: Float,
    val focalLengthMm: Float,
    val physicalCameraId: String?,
)

/** Pure rectangle bounds without Android dependency, for deterministic unit tests. */
data class CropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Converts an android.graphics.Rect to [CropRect]. */
fun android.graphics.Rect.toCropRect(): CropRect = CropRect(left, top, right, bottom)

/** Converts a [CropRect] to android.graphics.Rect. */
fun CropRect.toAndroidRect(): android.graphics.Rect = android.graphics.Rect(left, top, right, bottom)

/**
 * User-selectable policy for how pinch/rocker movement crosses optical-anchor boundaries.
 *
 * - [MANUAL_PRESETS]: the user explicitly selects an optical sector; pinch and rocker movement
 *   is clamped to the sector between the active anchor and the next anchor boundary (geometric
 *   mean between adjacent anchor ratios). Tapping an anchor button jumps sectors.
 * - [AUTOMATIC]: pinch and rocker roam the full advertised range and the HAL decides when to
 *   switch physical sensors. We do not promise to fix a physical sensor; the OEM may still
 *   optimize transitions internally.
 */
enum class ZoomLensSwitchMode { MANUAL_PRESETS, AUTOMATIC }

/**
 * Pure zoom math shared by Camera2 and unit tests. No Android dependency except android.graphics.Rect
 * for crop math, isolated so the rest of the object can be exercised deterministically.
 */
object ZoomMath {
    /**
     * Derives optical anchors for a logical camera from the physical sensor focal lengths and
     * the logical camera's reference focal length. The reference is the logical camera's own
     * reported focal length (usually the primary sensor's). Each physical sensor contributes
     * one anchor at ratio = referenceFocal / physicalFocal (a *shorter* physical focal means a
     * *wider* field of view, i.e. ratio < 1). Anchors are sorted ascending by ratio and
     * de-duplicated. A 1x anchor is always present.
     *
     * If [physicalFocals] is empty or the reference is unavailable, only the 1x anchor is
     * returned, leaving the rest of the range to digital zoom.
     */
    fun deriveAnchors(
        referenceFocalMm: Float?,
        physicalFocals: List<Pair<String, Float>>,
        rangeMin: Float = Float.NEGATIVE_INFINITY,
        rangeMax: Float = Float.POSITIVE_INFINITY,
    ): List<ZoomAnchor> {
        if (referenceFocalMm == null || referenceFocalMm <= 0f || physicalFocals.isEmpty()) {
            return listOf(ZoomAnchor(1f.coerceIn(rangeMin, rangeMax), referenceFocalMm ?: 0f, null))
        }
        val ratios = physicalFocals.mapNotNull { (id, focal) ->
            if (focal <= 0f) return@mapNotNull null
            // A shorter physical focal = wider field of view = lower zoom ratio.
            val ratio = focal / referenceFocalMm
            ZoomAnchor(ratio.coerceIn(rangeMin, rangeMax), focal, id)
        }
        val withUnity = (ratios + ZoomAnchor(1f, referenceFocalMm, null))
            .distinctBy { roundAnchors(it.ratio) }
            .sortedBy { it.ratio }
        return withUnity
    }

    /**
     * Returns the nearest anchor to [ratio]. Ties resolve to the lower ratio (wider lens) for a
     * more conservative jump.
     */
    fun nearestAnchor(ratio: Float, anchors: List<ZoomAnchor>): ZoomAnchor {
        if (anchors.isEmpty()) return ZoomAnchor(1f, 0f, null)
        return anchors.minByOrNull { abs(it.ratio - ratio) }!!
    }

    /**
     * Returns the lower/upper sector boundary for [ratio] under [ZoomLensSwitchMode.MANUAL_PRESETS].
     * The boundary between two adjacent anchors is their geometric mean so movement feels
     * symmetric in the log (octave) domain. When [ratio] is beyond the extreme anchors the
     * boundary is the anchor itself (no further sector jump via pinch). Returns the full range
     * in AUTOMATIC mode.
     */
    fun sectorBounds(
        ratio: Float,
        anchors: List<ZoomAnchor>,
        mode: ZoomLensSwitchMode,
        rangeMin: Float,
        rangeMax: Float,
    ): ClosedFloatingPointRange<Float> {
        if (mode == ZoomLensSwitchMode.AUTOMATIC || anchors.size <= 1) return rangeMin..rangeMax
        val sorted = anchors.sortedBy { it.ratio }
        val lower = sorted.lastOrNull { it.ratio <= ratio } ?: sorted.first()
        val upper = sorted.firstOrNull { it.ratio > ratio } ?: sorted.last()
        if (lower === upper) {
            // Beyond the last anchor: allow digital up to rangeMax.
            return lower.ratio..rangeMax
        }
        val mid = geometricMean(lower.ratio, upper.ratio)
        return if (ratio < mid) {
            lower.ratio..mid
        } else {
            mid..upper.ratio
        }
    }

    /** Coerces [ratio] into [range], handling inverted/empty ranges gracefully. */
    fun coerce(ratio: Float, range: ClosedFloatingPointRange<Float>): Float {
        if (range.start >= range.endInclusive) return range.start
        return ratio.coerceIn(range.start, range.endInclusive)
    }

    /**
     * Multiplies the current ratio by [factor] and coerces into [range]. Used by the rocker.
     */
    fun multiply(ratio: Float, factor: Float, range: ClosedFloatingPointRange<Float>): Float =
        coerce(ratio * factor, range)

    /**
     * Quadratic speed curve for the lateral rocker. [normalizedOffset] is in -1..1 where 0 is
     * the rest (center) position and +/-1 are the extremes. A small dead zone around 0 yields 0.
     * Returns a zoom speed in octaves per second: 0 up to [maxOctavesPerSecond] at the extremes.
     * The curve is quadratic so fine control near the center is gentle and ramps up at the edges.
     */
    fun rockerSpeedOctavesPerSecond(
        normalizedOffset: Float,
        maxOctavesPerSecond: Float = 2f,
        deadZone: Float = 0.06f,
    ): Float {
        val magnitude = abs(normalizedOffset)
        if (magnitude < deadZone) return 0f
        val signed = if (normalizedOffset < 0f) -1f else 1f
        val normalized = (magnitude - deadZone) / (1f - deadZone)
        return signed * maxOctavesPerSecond * normalized * normalized
    }

    /**
     * Converts a speed in octaves/second and an elapsed [deltaSeconds] into a multiplicative
     * factor for the current ratio. Positive speed zooms in (factor > 1), negative zooms out.
     */
    fun rockerFactor(speedOctavesPerSecond: Float, deltaSeconds: Float): Float {
        return exp(speedOctavesPerSecond * deltaSeconds)
    }

    /** Geometric mean used as the sector boundary between adjacent anchors. */
    fun geometricMean(a: Float, b: Float): Float = sqrt(a * b)

    /**
     * Computes the SCALER_CROP_REGION for API 29 (no CONTROL_ZOOM_RATIO) from the sensor active
     * array, centered on the given [zoomRatio] (>= 1). Returns null when the ratio is 1 or the
     * active array is null. The crop is centered and clamped to the sensor bounds.
     */
    fun cropRegionFor(
        activeArray: android.graphics.Rect?,
        zoomRatio: Float,
    ): android.graphics.Rect? = cropRegionForPure(activeArray?.toCropRect(), zoomRatio)?.toAndroidRect()

    /** Pure variant of [cropRegionFor] without Android dependencies, for unit tests. */
    fun cropRegionForPure(
        activeArray: CropRect?,
        zoomRatio: Float,
    ): CropRect? {
        if (activeArray == null || zoomRatio <= 1f) return null
        val fullW = activeArray.width
        val fullH = activeArray.height
        val cropW = (fullW / zoomRatio).toInt().coerceAtLeast(1)
        val cropH = (fullH / zoomRatio).toInt().coerceAtLeast(1)
        val left = activeArray.left + (fullW - cropW) / 2
        val top = activeArray.top + (fullH - cropH) / 2
        return CropRect(left, top, left + cropW, top + cropH)
    }

    /**
     * Derives the effective zoom ratio from a SCALER_CROP_REGION result (API 29) by comparing
     * the crop width to the sensor active array width.
     */
    fun ratioFromCropRegion(
        activeArray: android.graphics.Rect?,
        crop: android.graphics.Rect?,
    ): Float? = ratioFromCropRegionPure(activeArray?.toCropRect(), crop?.toCropRect())

    /** Pure variant of [ratioFromCropRegion] without Android dependencies, for unit tests. */
    fun ratioFromCropRegionPure(
        activeArray: CropRect?,
        crop: CropRect?,
    ): Float? {
        if (activeArray == null || crop == null) return null
        if (crop.width <= 0 || crop.height <= 0) return null
        val ratioW = activeArray.width.toFloat() / crop.width
        val ratioH = activeArray.height.toFloat() / crop.height
        return ratioW.coerceAtMost(ratioH)
    }

    private fun roundAnchors(ratio: Float): Int = (ratio * 100f).toInt()
}
