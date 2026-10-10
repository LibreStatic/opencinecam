/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/**
 * Pure decisions for CONTROL_SETTINGS_OVERRIDE_ZOOM (API 34): while the user is moving zoom the
 * repeating request asks the HAL to apply the new zoom ratio ahead of the other settings, and
 * returns to OFF once zoom has been idle.
 */
object ZoomSettingsOverride {
    /** Frames of quiet after the last zoom change before the override is released. */
    const val IDLE_RELEASE_MS = 200L

    private const val RATIO_EPSILON = 0.001f

    /** True when the override may be used at all on this device and session. */
    fun eligible(
        sdkInt: Int,
        usesZoomRatioApi: Boolean,
        overrideZoomAdvertised: Boolean,
        highSpeedSession: Boolean,
    ): Boolean = sdkInt >= 34 && usesZoomRatioApi && overrideZoomAdvertised && !highSpeedSession

    /** True when a zoom change from [previousRatio] to [newRatio] should arm the override. */
    fun shouldArm(eligible: Boolean, previousRatio: Float, newRatio: Float): Boolean =
        eligible && kotlin.math.abs(newRatio - previousRatio) > RATIO_EPSILON

    /**
     * True when an armed override should return to OFF. Either the HAL reports it already applied
     * ([resultOverrideIsOff]) once the result carries the requested ratio, or zoom has been idle
     * long enough.
     */
    fun shouldRelease(
        armed: Boolean,
        nowMs: Long,
        lastChangeMs: Long,
        resultZoomRatio: Float?,
        requestedRatio: Float,
        resultOverrideIsOff: Boolean,
    ): Boolean {
        if (!armed) return false
        val elapsed = nowMs - lastChangeMs
        if (elapsed < 0) return true
        val reached = resultZoomRatio != null && kotlin.math.abs(resultZoomRatio - requestedRatio) <= RATIO_EPSILON
        return elapsed >= IDLE_RELEASE_MS && (reached || resultZoomRatio == null || resultOverrideIsOff) ||
            elapsed >= IDLE_RELEASE_MS * 5
    }
}
