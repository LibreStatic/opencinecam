/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraDevice

/**
 * Whether a camera lost to a fatal device or service error is reopened in place instead of failing the preview.
 *
 * The Razr's vendor camera provider can die under load (a pinch zoom in a 120 fps RAW session) and come back
 * about 3 s later; the camera device then reports [CameraDevice.StateCallback.ERROR_CAMERA_DEVICE]. Only that and
 * [CameraDevice.StateCallback.ERROR_CAMERA_SERVICE] are retried: the other codes (in use, too many cameras,
 * disabled by policy) would fail the same way again. A take or an accepted still in progress is never retried:
 * the failure path salvages or ends it. [maxAttempts] reopens are allowed in a row; a failure [resetAfterMs] or
 * more after the last one starts a new row. Not thread safe: used on the camera executor only.
 */
internal class CameraReopenPolicy(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val resetAfterMs: Long = RESET_AFTER_MS,
) {
    private var attempts = 0
    private var lastFailureAtMs: Long? = null

    /** Records a camera error [error] at [nowMs] and returns whether the camera should be reopened. */
    fun shouldReopen(error: Int, takeInProgress: Boolean, nowMs: Long): Boolean {
        val last = lastFailureAtMs
        if (last == null || nowMs - last >= resetAfterMs) attempts = 0
        lastFailureAtMs = nowMs
        if (takeInProgress || !retryable(error) || attempts >= maxAttempts) return false
        attempts++
        return true
    }

    companion object {
        const val MAX_ATTEMPTS = 2
        const val RESET_AFTER_MS = 60_000L

        /** Before the availability wait: the provider's death reaches the camera service a moment after the device error. */
        const val SETTLE_MS = 1_500L

        /** The longest wait for the camera to be reported available again; the reopen is tried anyway after it. */
        const val AVAILABILITY_TIMEOUT_MS = 10_000L

        fun retryable(error: Int): Boolean =
            error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE || error == CameraDevice.StateCallback.ERROR_CAMERA_SERVICE
    }
}
