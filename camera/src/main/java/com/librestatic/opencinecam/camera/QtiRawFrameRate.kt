/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CaptureRequest

/**
 * Applies the Qualcomm CamX frame-rate override ([QtiRawFrameRateKeys]) to a RAW_VIDEO request. The
 * keys are built by name (they are not advertised); only a camera whose device profile carries
 * `QuirkEffect.QTI_RAW_FRAME_RATE_OVERRIDE` may ever get them.
 */
object QtiRawFrameRate {
    private val disableFpsLimits: CaptureRequest.Key<Byte>? by lazy {
        runCatching { CaptureRequest.Key(QtiRawFrameRateKeys.DISABLE_FPS_LIMITS, Byte::class.javaObjectType) }.getOrNull()
    }
    private val overrideSensorFrameDuration: CaptureRequest.Key<Long>? by lazy {
        runCatching { CaptureRequest.Key(QtiRawFrameRateKeys.OVERRIDE_SENSOR_FRAME_DURATION, Long::class.javaObjectType) }.getOrNull()
    }

    /**
     * Sets both keys on [builder] for a frame duration of [frameDurationNs]; false when either could
     * not be built or set (an unknown vendor tag throws on other HALs), in which case neither is left set.
     */
    fun apply(builder: CaptureRequest.Builder, frameDurationNs: Long): Boolean {
        val disable = disableFpsLimits ?: return false
        val duration = overrideSensorFrameDuration ?: return false
        return runCatching {
            builder.set(disable, 1.toByte())
            builder.set(duration, frameDurationNs)
        }.onFailure { clear(builder) }.isSuccess
    }

    /** Removes both keys from [builder], ignoring a HAL that never knew them. */
    fun clear(builder: CaptureRequest.Builder) {
        disableFpsLimits?.let { key -> runCatching { builder.set(key, null) } }
        overrideSensorFrameDuration?.let { key -> runCatching { builder.set(key, null) } }
    }
}
