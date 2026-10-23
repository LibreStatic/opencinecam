/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.core.model.CalibrationFlag

/** Where a lens calibration run is. [CaptureService] owns it; the capture card and the settings page render it. */
enum class LensCalibrationPhase { IDLE, PREPARING, FINDING_SWITCH_POINTS, MEASURING, SOLVING, RESULT, FAILED }

enum class LensCalibrationLensStatus { PENDING, REFERENCE, MEASURING, DONE, SKIPPED, FAILED }

enum class LensCalibrationFailure {
    /** The selected camera is not a logical multi-camera, or reports fewer than two lenses. */
    NO_MULTI_CAMERA,
    /** The run needs the GPU video pipeline and the camera could not provide it. */
    UNSUPPORTED_MODE,
    CAMERA_LOST,
    /** AE/AWB or the active lens never settled long enough to measure. */
    SCENE_UNSTABLE,
    /** Every lens failed validation (low texture, clipping, parallax). */
    LOW_QUALITY,
    INTERNAL,
}

/** One physical lens in a run or in the saved calibration. */
data class LensCalibrationLensRow(
    val physicalId: String,
    /** Where the lens starts on the logical camera's zoom scale, e.g. 0.5, 1 or 2. */
    val nominalRatio: Float,
    val status: LensCalibrationLensStatus,
    /** 0..1 within this lens' measurement. */
    val progress: Float = 0f,
    val colorDeltaEBefore: Float? = null,
    val colorDeltaEAfter: Float? = null,
    val alignPxBefore: Float? = null,
    val alignPxAfter: Float? = null,
    /** 0..1 from the validator. */
    val score: Float? = null,
    val flags: Set<CalibrationFlag> = emptySet(),
)

data class LensCalibrationUiState(
    val phase: LensCalibrationPhase = LensCalibrationPhase.IDLE,
    val logicalCameraId: String? = null,
    val referencePhysicalId: String? = null,
    /** The lenses of the current run (or of the result waiting to be saved). */
    val lenses: List<LensCalibrationLensRow> = emptyList(),
    val failure: LensCalibrationFailure? = null,
    /** When the calibration saved for the selected camera on this build was made; null when none. */
    val savedCreatedAtMs: Long? = null,
    val savedLenses: List<LensCalibrationLensRow> = emptyList(),
    /** The scene's illuminant has drifted from the saved calibration's. */
    val illuminantMismatch: Boolean = false,
) {
    /** A run blocks recording, zoom and lens changes until it ends. */
    val running: Boolean
        get() = phase == LensCalibrationPhase.PREPARING || phase == LensCalibrationPhase.FINDING_SWITCH_POINTS ||
            phase == LensCalibrationPhase.MEASURING || phase == LensCalibrationPhase.SOLVING
}
