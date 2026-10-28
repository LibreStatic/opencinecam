/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlin.math.abs
import kotlin.math.roundToInt

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

/**
 * Why a requested lens lock was not served by a direct physical open. HIGH_SPEED and NIGHT are passed to
 * [Camera2PreviewListener.onLensLockUnavailable]; the others send the lock to the GPU-crop route
 * ([Camera2PreviewListener.onLensLockCrop]) and reach the listener as unavailable only when that
 * route cannot be built either.
 */
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

/**
 * The GPU-crop route of the lens lock (PLAN-070 slice 4, ADR-0036): when the physical camera cannot
 * be opened or configured, the logical camera runs with its HAL zoom pinned at the lens anchor (so
 * SAT selects that lens) and every further zoom is a centred crop on the GPU and in the stills. The
 * UI keeps the direct route's range, anchor x physical range. Pure, so it is host-testable.
 */
object LensLockCrop {
    /** Below this crop factor a still is delivered as the HAL encoded it. */
    const val RECODE_THRESHOLD = 1.01f

    /** The HAL zoom ratio the session is pinned at: [anchor] coerced into the logical camera's range. */
    fun pinnedRatio(anchor: Float, logicalRange: ClosedFloatingPointRange<Float>?): Float {
        val safe = if (anchor.isFinite() && anchor > 0f) anchor else 1f
        return logicalRange?.let { ZoomMath.coerce(safe, it) } ?: safe
    }

    /**
     * The crop factor for the logical-scale [ui] ratio: [ui] / [pinned], coerced to 1 (no crop) up to
     * the top of [uiRange]. With the HAL pinned exactly at the anchor this is ui / anchor coerced to
     * [1, physical range end]; dividing by the pinned ratio keeps the framing right when the logical
     * range forced the pin away from the anchor.
     */
    fun cropFactor(ui: Float, pinned: Float, uiRange: ClosedFloatingPointRange<Float>): Float {
        if (!(pinned > 0f) || !pinned.isFinite()) return 1f
        val max = (uiRange.endInclusive / pinned).takeIf { it.isFinite() && it >= 1f } ?: 1f
        val raw = ui / pinned
        return if (raw.isFinite()) raw.coerceIn(1f, max) else 1f
    }

    /** The logical-scale ratio the operator sees: the HAL ratio times the GPU crop. */
    fun uiRatio(halRatio: Float, cropFactor: Float): Float = halRatio * cropFactor.coerceAtLeast(1f)

    /**
     * The centred UV scale for [factor] as a column-major 4x4 (`uv' = 0.5 + (uv - 0.5) / factor`),
     * meant to act first on the output UV: the texture matrix uploaded is `T x C`. Identity at 1.
     */
    fun uvMatrix(factor: Float, out: FloatArray = FloatArray(16)): FloatArray {
        require(out.size >= 16)
        val f = if (factor.isFinite() && factor > 1f) factor else 1f
        val s = 1f / f
        val t = 0.5f * (1f - s)
        out.fill(0f)
        out[0] = s; out[5] = s; out[10] = 1f; out[15] = 1f
        out[12] = t; out[13] = t
        return out
    }

    /** The size a [width]x[height] still keeps under [factor]: W/f x H/f rounded to even, never upscaled. */
    fun stillSize(width: Int, height: Int, factor: Float): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val f = if (factor.isFinite() && factor > 1f) factor else 1f
        fun side(v: Int): Int {
            if (f == 1f) return v
            val cap = if (v >= 2) v - v % 2 else v
            val even = (v / f / 2f).roundToInt() * 2
            return even.coerceIn(minOf(2, cap), cap)
        }
        return side(width) to side(height)
    }

    /**
     * The centred crop of a [width]x[height] still under [factor], composed with [aspect]: the aspect
     * crop is taken inside the zoomed frame and the result is centred in the source, so the rectangle
     * satisfies [PhotoAspectReport]'s centring rule. Without the aspect crop it is the zoomed frame.
     */
    fun stillCrop(width: Int, height: Int, factor: Float, aspect: PhotoAspectSelection = PhotoAspectSelection()): PhotoCropRect {
        val (zoomW, zoomH) = stillSize(width, height, factor)
        val inner = aspect.centeredCrop(zoomW, zoomH)
        return PhotoCropRect((width - inner.width) / 2, (height - inner.height) / 2, inner.width, inner.height)
    }
}

/**
 * Debounces the GPU-crop route's lens-switch warning: the HAL's active physical camera must differ
 * from the locked one for [threshold] consecutive results to raise it, and match for as many to
 * clear it. Results without an active id are ignored. Not thread-safe; one capture callback thread.
 */
class LensSwitchDebounce(private val threshold: Int = DEFAULT_THRESHOLD) {
    init { require(threshold > 0) }

    var switched = false
        private set
    private var streak = 0

    /** Feeds one result; returns the new state when it changes, else null. */
    fun offer(activePhysicalId: String?, lockedPhysicalId: String): Boolean? {
        if (activePhysicalId == null) return null
        val mismatch = activePhysicalId != lockedPhysicalId
        if (mismatch == switched) {
            streak = 0
            return null
        }
        if (++streak < threshold) return null
        streak = 0
        switched = mismatch
        return switched
    }

    fun reset() {
        switched = false
        streak = 0
    }

    companion object {
        const val DEFAULT_THRESHOLD = 15
    }
}
