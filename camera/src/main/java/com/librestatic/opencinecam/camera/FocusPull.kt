/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/**
 * Easing curve applied to the linear interpolation between two focus marks.
 * LINEAR moves at a constant rate; the ease variants shape the start/end of the transition.
 */
enum class FocusPullEasing { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT }

/**
 * Plan for a single rack-focus transition from [fromDiopters] to [toDiopters].
 * The animator calls [onUpdate] with the interpolated diopter value at ~30 Hz and
 * [onComplete] when the transition finishes.
 */
data class FocusPullPlan(
    val fromDiopters: Float,
    val toDiopters: Float,
    val durationMs: Long,
    val easing: FocusPullEasing,
)

/**
 * Pure focus-pull interpolation logic. The caller (Camera2PreviewEngine) owns the Handler
 * and schedules ticks; this class just converts elapsed time into a diopter value.
 */
class FocusPullAnimator {

    private var plan: FocusPullPlan? = null
    private var startTimeMs: Long = 0L
    @Volatile private var cancelled = false

    val isActive: Boolean get() = plan != null && !cancelled

    fun start(plan: FocusPullPlan, nowMs: Long) {
        this.plan = plan
        this.startTimeMs = nowMs
        this.cancelled = false
    }

    fun cancel() {
        cancelled = true
        plan = null
    }

    /**
     * Computes the interpolated focus distance for the given clock value.
     * Returns null when the transition has not started, was cancelled, or has completed
     * (in which case the caller should apply the final [FocusPullPlan.toDiopters] value
     * and call [complete] to clear the state).
     */
    fun tick(nowMs: Long): Float? {
        val activePlan = plan ?: return null
        if (cancelled) return null
        val elapsed = (nowMs - startTimeMs).coerceAtLeast(0L)
        if (elapsed >= activePlan.durationMs) {
            return activePlan.toDiopters
        }
        val progress = if (activePlan.durationMs <= 0L) 1f else elapsed.toFloat() / activePlan.durationMs
        val eased = applyEasing(progress, activePlan.easing)
        return lerp(activePlan.fromDiopters, activePlan.toDiopters, eased)
    }

    /** Returns true if the transition has reached or exceeded its duration. */
    fun isComplete(nowMs: Long): Boolean {
        val activePlan = plan ?: return true
        if (cancelled) return true
        return (nowMs - startTimeMs) >= activePlan.durationMs
    }

    /** Clears the active plan after the caller has applied the final value. */
    fun complete() {
        plan = null
        cancelled = false
    }

    private fun applyEasing(progress: Float, easing: FocusPullEasing): Float = when (easing) {
        FocusPullEasing.LINEAR -> progress
        FocusPullEasing.EASE_IN -> progress * progress
        FocusPullEasing.EASE_OUT -> 1f - (1f - progress) * (1f - progress)
        FocusPullEasing.EASE_IN_OUT -> {
            if (progress < 0.5f) 2f * progress * progress
            else 1f - (-2f * progress + 2f).let { it * it } / 2f
        }
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t.coerceIn(0f, 1f)
}
