/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Stable Camera2 values (API 35), without Android dependencies so host tests can use them. */
const val AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY = 6
const val LOW_LIGHT_BOOST_STATE_ACTIVE = 1

/** Where the request is going; video graphs keep a fixed frame rate, so they never get the boost. */
enum class LowLightBoostScene { PREVIEW_OR_STILL, VIDEO }

/** Why a requested boost falls back to plain AE_MODE_ON; the Settings explanation reads this. */
enum class LowLightBoostInactive { MANUAL_EXPOSURE, AE_PRIORITY, VIDEO_FIXED_FRAME_RATE, HIGH_SPEED, FRAME_RATE_ABOVE_30 }

/** Frame rate ceiling above which the boost (which may lower the frame rate) is not requested. */
const val LOW_LIGHT_BOOST_MAX_FPS = 30

/** [active] is whether the request uses the boost AE mode; [inactive] is the reason when it was asked for but not used. */
data class LowLightBoostDecision(val active: Boolean, val inactive: LowLightBoostInactive? = null)

/**
 * The boost only replaces plain automatic exposure. Per the API it may lower the frame rate, so
 * it is withheld whenever the frame rate is fixed on purpose (video graphs, high-speed) or the
 * target range tops out above 30 fps. The decision is silent when the camera cannot do it or the
 * user did not ask: [LowLightBoostDecision.inactive] stays null then.
 */
fun resolveLowLightBoost(
    supported: Boolean,
    enabled: Boolean,
    exposureMode: ExposureMode,
    scene: LowLightBoostScene,
    highSpeed: Boolean,
    maxTargetFps: Int,
): LowLightBoostDecision {
    if (!supported || !enabled) return LowLightBoostDecision(false)
    val reason = when {
        exposureMode == ExposureMode.MANUAL -> LowLightBoostInactive.MANUAL_EXPOSURE
        exposureMode != ExposureMode.AUTO -> LowLightBoostInactive.AE_PRIORITY
        highSpeed -> LowLightBoostInactive.HIGH_SPEED
        scene == LowLightBoostScene.VIDEO -> LowLightBoostInactive.VIDEO_FIXED_FRAME_RATE
        maxTargetFps > LOW_LIGHT_BOOST_MAX_FPS -> LowLightBoostInactive.FRAME_RATE_ABOVE_30
        else -> null
    }
    return LowLightBoostDecision(active = reason == null, inactive = reason)
}

/** Boost support from the camera's advertised AE modes; the SDK gate is applied by the caller. */
fun lowLightBoostSupported(sdkInt: Int, aeModes: Set<Int>): Boolean =
    sdkInt >= 35 && AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY in aeModes

/** Result state to the HUD flag: null when the camera did not submit the boost or reported nothing. */
fun lowLightBoostActive(submittedAeMode: Int?, state: Int?): Boolean? =
    if (submittedAeMode != AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY || state == null) null
    else state == LOW_LIGHT_BOOST_STATE_ACTIVE
