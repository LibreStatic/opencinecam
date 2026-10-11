/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.concurrent.ConcurrentHashMap

/**
 * Qualcomm's exposure-time priority with AE on (the Razr Fold's HAL exposes it although it
 * advertises no Android 16 priority mode): with `select_priority` = 1 and `use_iso_exp_priority` =
 * the exposure in ns, AE keeps that exposure and steers the ISO and its ISP gain itself.
 */
object QcomExposurePriority {
    private const val SECTION = "org.codeaurora.qcamera3.iso_exp_priority."
    const val USE_KEY = SECTION + "use_iso_exp_priority"
    const val SELECT_KEY = SECTION + "select_priority"
    const val RANGE_KEY = SECTION + "exposure_time_range"

    /** select_priority value for exposure-time priority (0 is ISO priority). */
    const val SELECT_EXPOSURE_PRIORITY = 1

    /** The characteristics' int64[2] range as a valid LongRange, or null. */
    fun parseRange(raw: LongArray?): LongRange? =
        raw?.takeIf { it.size == 2 && it[0] > 0 && it[1] >= it[0] }?.let { it[0]..it[1] }
}

/** How the FPS LOCK holds the shutter, best first. */
enum class CadenceHoldPath { NATIVE, QCOM, EMULATED }

/** Cameras whose HAL ignored the Qualcomm exposure priority; the emulated hold is used for them for the process. */
internal val qcomExposurePriorityUnusable: MutableSet<String> = ConcurrentHashMap.newKeySet()

/**
 * Preference order: Android's native priority, then the Qualcomm exposure priority (its range must
 * contain [heldExposureNs] and the camera must not be marked unusable), then the emulated hold if
 * the camera has manual sensor control. Null when none is possible.
 */
fun cadenceHoldPath(
    sdkInt: Int,
    caps: ExposureCapabilities,
    heldExposureNs: Long,
    qcomUnusable: Boolean,
): CadenceHoldPath? = when {
    CadenceGuard.nativePriority(sdkInt, caps.priorities) -> CadenceHoldPath.NATIVE
    !qcomUnusable && caps.qcomExposureRangeNs?.let { heldExposureNs in it } == true -> CadenceHoldPath.QCOM
    caps.supports(ExposureMode.MANUAL) -> CadenceHoldPath.EMULATED
    else -> null
}

/** True when the HAL ignored the request: the result exposure is still over 1.1x the held one. */
fun qcomHoldIgnored(resultExposureNs: Long?, heldExposureNs: Long): Boolean =
    resultExposureNs != null && resultExposureNs > heldExposureNs * 1.1
