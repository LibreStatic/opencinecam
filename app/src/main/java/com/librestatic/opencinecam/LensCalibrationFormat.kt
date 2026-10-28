/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.core.model.CalibrationFlag
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** "0.5×", "1×", "2×": a lens' place on the logical camera's zoom scale. */
internal fun lensRatioLabel(ratio: Float): String {
    val whole = ratio.roundToInt()
    return if (abs(ratio - whole) < 0.05f) "$whole×" else String.format(Locale.ROOT, "%.1f×", ratio)
}

/** One decimal, in the reader's own number format. */
internal fun calibrationNumber(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)

/** "6.1 → 1.2", or a lone value when only one side is known, or null when neither is. */
internal fun calibrationBeforeAfter(before: Float?, after: Float?): String? = when {
    before != null && after != null -> "${calibrationNumber(before)} → ${calibrationNumber(after)}"
    after != null -> calibrationNumber(after)
    before != null -> calibrationNumber(before)
    else -> null
}

internal fun LensCalibrationLensRow.qualityPercent(): Int? = score?.let { (it.coerceIn(0f, 1f) * 100f).roundToInt() }

internal fun LensCalibrationFailure.messageRes(): Int = when (this) {
    LensCalibrationFailure.NO_MULTI_CAMERA -> R.string.lens_cal_fail_no_multi_camera
    LensCalibrationFailure.UNSUPPORTED_MODE -> R.string.lens_cal_fail_unsupported_mode
    LensCalibrationFailure.CAMERA_LOST -> R.string.lens_cal_fail_camera_lost
    LensCalibrationFailure.SCENE_UNSTABLE -> R.string.lens_cal_fail_scene_unstable
    LensCalibrationFailure.LOW_QUALITY -> R.string.lens_cal_fail_low_quality
    LensCalibrationFailure.INTERNAL -> R.string.lens_cal_fail_internal
}

internal fun CalibrationFlag.labelRes(): Int = when (this) {
    CalibrationFlag.LOW_TEXTURE -> R.string.lens_cal_flag_low_texture
    CalibrationFlag.LOW_CHROMA -> R.string.lens_cal_flag_low_chroma
    CalibrationFlag.CLIPPED -> R.string.lens_cal_flag_clipped
    CalibrationFlag.UNSTABLE_AE -> R.string.lens_cal_flag_unstable_ae
    CalibrationFlag.PARALLAX_SUSPECT -> R.string.lens_cal_flag_parallax
    CalibrationFlag.DIAGONAL_FALLBACK -> R.string.lens_cal_flag_diagonal
}

internal fun LensCalibrationPhase.titleRes(): Int = when (this) {
    LensCalibrationPhase.PREPARING, LensCalibrationPhase.IDLE -> R.string.lens_cal_phase_preparing
    LensCalibrationPhase.FINDING_SWITCH_POINTS -> R.string.lens_cal_phase_finding
    LensCalibrationPhase.MEASURING -> R.string.lens_cal_phase_measuring
    LensCalibrationPhase.SOLVING -> R.string.lens_cal_phase_solving
    LensCalibrationPhase.RESULT -> R.string.lens_cal_result_title
    LensCalibrationPhase.FAILED -> R.string.lens_cal_failed_title
}

internal fun LensCalibrationLensRow.isReferenceIn(state: LensCalibrationUiState): Boolean =
    status == LensCalibrationLensStatus.REFERENCE || (state.referencePhysicalId != null && physicalId == state.referencePhysicalId)
