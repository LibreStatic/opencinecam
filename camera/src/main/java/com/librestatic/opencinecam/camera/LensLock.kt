/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlin.math.abs

/**
 * A lens-locked session (PLAN-070, ADR-0036): the app addresses the logical camera [logicalId],
 * the engine opens its reported physical camera [physicalId] directly. [anchorRatio] is where the
 * physical lens sits on the logical zoom scale ([lensAnchorRatio]).
 */
data class LensLockBinding(
    val logicalId: String,
    val physicalId: String,
    val anchorRatio: Float,
)

/**
 * A lens of a logical camera that can be locked: listed by `getPhysicalCameraIds()`, facing the
 * same way, with a computable field of view. [physicalZoomRange] is the physical camera's own range.
 */
data class LensLockCandidate(
    val physicalId: String,
    val anchorRatio: Float,
    val focalMm: Float,
    val physicalZoomRange: ClosedFloatingPointRange<Float>,
) {
    fun binding(logicalId: String): LensLockBinding = LensLockBinding(logicalId, physicalId, anchorRatio)
}

/** Why a requested lens lock was not applied; passed to [Camera2PreviewListener.onLensLockUnavailable]. */
object LensLockUnavailable {
    const val HIGH_SPEED = "high-speed"
    const val NIGHT = "night"
    const val FORMAT = "format"
    const val LOG = "log"
    const val DESCRIPTOR = "descriptor"
    const val OPEN_FAILED = "open-failed"
    const val CONFIGURE_FAILED = "configure-failed"
}

/**
 * Pure mapping of the values requested for the logical camera onto the opened physical camera.
 * Sizes are (width, height) pairs so the rules are host-testable without `android.util.Size`.
 */
object LensLockMapping {
    private const val ASPECT_TOLERANCE = 0.01

    fun sameAspect(aW: Int, aH: Int, bW: Int, bH: Int): Boolean {
        if (aW <= 0 || aH <= 0 || bW <= 0 || bH <= 0) return false
        val a = aW.toDouble() / aH
        val b = bW.toDouble() / bH
        return abs(a - b) <= ASPECT_TOLERANCE * a
    }

    /**
     * The index in [candidates] (width, height, fps) for a requested [width]x[height] at [fps]:
     * the same size and rate; else the same rate and aspect at the largest size not above the
     * requested one; else, with [anyRate], the first candidate with the same rate. Null when none fits.
     */
    fun profileIndex(width: Int, height: Int, fps: Int, candidates: List<Triple<Int, Int, Int>>, anyRate: Boolean = true): Int? {
        candidates.indexOfFirst { (w, h, f) -> w == width && h == height && f == fps }.takeIf { it >= 0 }?.let { return it }
        val requestedArea = width.toLong() * height
        candidates.withIndex()
            .filter { (_, c) -> c.third == fps && sameAspect(c.first, c.second, width, height) && c.first.toLong() * c.second <= requestedArea }
            .maxByOrNull { (_, c) -> c.first.toLong() * c.second }
            ?.let { return it.index }
        if (!anyRate) return null
        return candidates.indexOfFirst { it.third == fps }.takeIf { it >= 0 }
    }

    /**
     * The still size for a requested [width]x[height] (null = the largest): the largest of [sizes]
     * with the same aspect, or null (the session's largest) when none matches.
     */
    fun stillSize(width: Int?, height: Int?, sizes: List<Pair<Int, Int>>): Pair<Int, Int>? {
        if (width == null || height == null) return null
        return sizes.filter { (w, h) -> sameAspect(w, h, width, height) }
            .maxByOrNull { (w, h) -> w.toLong() * h }
    }
}

/** The camera id device-profile quirks are keyed by: the logical id of a lens-locked session. */
fun quirkCameraIdOf(cameraId: String, lensLock: LensLockBinding?): String = lensLock?.logicalId ?: cameraId
