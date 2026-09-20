/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Camera-executor-confined protocol; elapsed-time deadlines belong to the platform owner. */
internal class PhotoCaptureSequence(needsPrecapture: Boolean) {
    enum class Ae { CONVERGED, FLASH_REQUIRED, PRECAPTURE, OTHER }
    enum class Stage { TRIGGER_PENDING, METERING, CAPTURING, COMPLETE, CANCELLED }
    var stage = if (needsPrecapture) Stage.TRIGGER_PENDING else Stage.CAPTURING
        private set
    private var triggerFrame: Long? = null
    var sensorTimestampNs: Long? = null
        private set

    fun triggerCompleted(frame: Long) {
        if (stage != Stage.TRIGGER_PENDING || frame < 0) return
        triggerFrame = frame
        stage = Stage.METERING
    }
    fun observe(frame: Long, ae: Ae, charging: Boolean): Boolean {
        val minimum = triggerFrame ?: return false
        if (stage != Stage.METERING || frame < minimum || charging ||
            ae !in setOf(Ae.CONVERGED, Ae.FLASH_REQUIRED)) return false
        stage = Stage.CAPTURING
        return true
    }
    fun result(timestampNs: Long): Boolean {
        if (stage != Stage.CAPTURING || sensorTimestampNs != null || timestampNs <= 0) return false
        sensorTimestampNs = timestampNs
        return true
    }
    fun complete(timestampNs: Long): Boolean {
        if (stage != Stage.CAPTURING || sensorTimestampNs != timestampNs) return false
        stage = Stage.COMPLETE
        return true
    }
    fun cancel() { if (stage != Stage.COMPLETE) stage = Stage.CANCELLED }
}

/** OFF means no pulse; retain only a continuous TORCH actually applied by shared controls. */
internal fun photoStillPlan(
    selection: PhotoFlashSelection,
    resolved: PhotoFlashResolution.Plan,
    appliedFlashMode: Int?,
    appliedStrength: Int?,
): PhotoFlashResolution.Plan = if (selection.mode == PhotoFlashMode.OFF && appliedFlashMode == 2) {
    resolved.copy(flashMode = 2, strength = appliedStrength)
} else resolved

/** Camera2 SINGLE would otherwise fire on every repeating preview request. */
internal fun photoMeteringRepeatPlan(plan: PhotoFlashResolution.Plan): PhotoFlashResolution.Plan =
    if (plan.flashMode == 1) plan.copy(flashMode = 0, strength = null) else plan

/** Null rejects a requested manual exposure that shared controls could not actually apply. */
internal fun photoExposureMode(requested: ExposureMode?, submittedAeMode: Int?): ExposureMode? = when {
    requested == ExposureMode.ISO_PRIORITY || requested == ExposureMode.SHUTTER_PRIORITY -> requested
    requested == ExposureMode.MANUAL && submittedAeMode != 0 -> null
    submittedAeMode == 0 -> ExposureMode.MANUAL
    else -> ExposureMode.AUTO
}
