/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/**
 * The Qualcomm/Motorola "auto frame rate" (dynamic FPS) HAL feature. Measured on the Razr 2026-10-10
 * with `dumpsys media.camera` while the stock camera recorded 4K60 with aeTargetFpsRange [60,60]:
 *
 * - ON: request `com.lenovo.moto.control.IQdynamicFPS_enable` = 1, session parameters
 *   `org.codeaurora.qcamera3.sessionParameters.EnableVIULL` = 1 and `...dynamicFPSConfig` =
 *   [2, 30, 60]. In a dim room the sensor ran 30 fps and the file was a true 30 fps.
 * - OFF: `IQdynamicFPS_enable` = 0, no EnableVIULL: true 60 fps.
 *
 * A fixed cadence is mandatory for professional recording, so the app never leaves the choice to the
 * HAL: unless the operator turns "Low-light auto frame rate" on (regular VIDEO only), every request
 * and session of a supporting camera carries an explicit 0.
 */
object DynamicFps {
    const val ENABLE_KEY = "com.lenovo.moto.control.IQdynamicFPS_enable"
    const val LEGACY_ENABLE_KEY = "com.lenovo.moto.control.dynamicFPS_enable"
    const val SUPPORT_KEY = "com.lenovo.moto.control.IQdynamicFPS_support"
    const val VIULL_KEY = "org.codeaurora.qcamera3.sessionParameters.EnableVIULL"
    const val VIULL_CAPABILITY_KEY = "org.codeaurora.qcamera3.platformCapabilities.EnableVIULL"
    const val CONFIG_KEY = "org.codeaurora.qcamera3.sessionParameters.dynamicFPSConfig"

    /** Lowest rate the HAL may drop to, as the stock camera sends it. */
    private const val MIN_FLOOR_FPS = 15
    private const val LEADING_VALUE = 2f

    /** `dynamicFPSConfig` for a target rate: [2, target / 2 (at least 15), target]; 60 gives [2, 30, 60]. */
    fun config(targetFps: Int): FloatArray {
        val target = targetFps.coerceAtLeast(1)
        val floor = (target / 2).coerceAtLeast(MIN_FLOOR_FPS).coerceAtMost(target)
        return floatArrayOf(LEADING_VALUE, floor.toFloat(), target.toFloat())
    }

    /** The static `IQdynamicFPS_support` value: only an explicit 1 means the HAL implements it. */
    fun staticSupport(support: IntArray?): Boolean = support?.firstOrNull() == 1

    /**
     * The descriptor gate: the static support flag is 1 and the enable request key plus both
     * session keys resolve. Missing any of them would make the ON state partial.
     */
    fun offered(staticSupport: Boolean, enableKey: Boolean, viullKey: Boolean, configKey: Boolean): Boolean =
        staticSupport && enableKey && viullKey && configKey

    /**
     * Auto frame rate is allowed for regular VIDEO only. LOG and HLG (the OCLog GPU graph) need a
     * constant cadence for their pipeline, and HFR, timelapse and photo paths never want it.
     */
    fun allowed(videoMode: Boolean, logGraph: Boolean, constrainedHighSpeed: Boolean): Boolean =
        videoMode && !logGraph && !constrainedHighSpeed

    /** The effective request: the operator's choice, only when [allowed]. */
    fun enabled(requested: Boolean, allowed: Boolean): Boolean = requested && allowed
}
