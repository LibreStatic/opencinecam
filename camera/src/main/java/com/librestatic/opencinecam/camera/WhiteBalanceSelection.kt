/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CaptureRequest

/**
 * Unified white-balance selection model.
 *
 * [Auto] delegates AWB to the camera's automatism; [Kelvin] drives the API 36+
 * COLOR_CORRECTION_MODE_CCT path with a direct color temperature request; [Preset] keeps the
 * legacy CONTROL_AWB_MODE_* presets (DAYLIGHT, CLOUDY, INCANDESCENT, FLUORESCENT) for devices
 * without direct CCT support.
 */
sealed interface WhiteBalanceSelection {
    data object Auto : WhiteBalanceSelection
    data class Kelvin(val kelvin: Int) : WhiteBalanceSelection
    data class Preset(val awbMode: Int) : WhiteBalanceSelection
}

/**
 * Human-readable label for a [WhiteBalanceSelection] suitable for HUD chips and status bars:
 * "AUTO" for automatic WB, "${kelvin}K" for direct Kelvin, or the legacy preset abbreviations.
 */
fun WhiteBalanceSelection.label(): String = when (this) {
    is WhiteBalanceSelection.Auto -> "AUTO"
    is WhiteBalanceSelection.Kelvin -> "${kelvin}K"
    is WhiteBalanceSelection.Preset -> when (awbMode) {
        CaptureRequest.CONTROL_AWB_MODE_AUTO -> "AUTO"
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "DAY"
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUD"
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "TUNG"
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "FLUO"
        else -> "WB$awbMode"
    }
}

/**
 * Snaps a raw Kelvin value to the nearest 100 K step and clamps it to the device range.
 * Returns null when [range] is null (Kelvin unsupported on this camera).
 */
fun snapKelvinTo100(raw: Int, range: IntRange?): Int? {
    if (range == null) return null
    if (range.last <= range.first) return null
    val snapped = (raw / 100) * 100
    return snapped.coerceIn(range.first, range.last)
}

/** Preset Kelvin temperatures offered as quick chips when direct CCT is supported. */
val KELVIN_PRESETS: List<Int> = listOf(3200, 4300, 5600, 6500)

/**
 * Adapts a remembered [WhiteBalanceSelection] to the capability of the newly selected camera.
 * A Kelvin request that the new camera cannot honor falls back to [WhiteBalanceSelection.Auto].
 */
fun WhiteBalanceSelection.adaptTo(kelvinRange: IntRange?): WhiteBalanceSelection = when (this) {
    is WhiteBalanceSelection.Kelvin -> {
        val snapped = snapKelvinTo100(kelvin, kelvinRange)
        if (snapped != null) WhiteBalanceSelection.Kelvin(snapped) else WhiteBalanceSelection.Auto
    }
    else -> this
}
