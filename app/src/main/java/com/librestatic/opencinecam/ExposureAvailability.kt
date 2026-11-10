/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.ExposureCapabilities
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ExposureSelection
import com.librestatic.opencinecam.camera.ShutterUnit

/**
 * Which exposure controls a camera really gives the operator. Settings and the capabilities page
 * both read this one answer, so one can never offer manual exposure while the other says it is
 * missing. Constrained high-speed sessions offer none: that route is not qualified for manual control.
 */
internal data class ExposureAvailability(
    val manual: Boolean = false,
    val isoPriority: Boolean = false,
    val shutterPriority: Boolean = false,
    val isoRange: IntRange? = null,
    val timeRangeNs: LongRange? = null,
) {
    val isoAdjustable: Boolean get() = manual || isoPriority
    val timeAdjustable: Boolean get() = manual || shutterPriority
    /** False when the camera only does automatic exposure. */
    val any: Boolean get() = isoAdjustable || timeAdjustable

    fun supports(mode: ExposureMode): Boolean = when (mode) {
        ExposureMode.AUTO -> true
        ExposureMode.MANUAL -> manual
        ExposureMode.ISO_PRIORITY -> isoPriority
        ExposureMode.SHUTTER_PRIORITY -> shutterPriority
    }

    /** True when [mode] sets ISO itself, so the ISO control shows the requested value. */
    fun appliesIso(mode: ExposureMode): Boolean = supports(mode) && (mode == ExposureMode.MANUAL || mode == ExposureMode.ISO_PRIORITY)

    /** True when [mode] sets the exposure time itself. */
    fun appliesTime(mode: ExposureMode): Boolean = supports(mode) && (mode == ExposureMode.MANUAL || mode == ExposureMode.SHUTTER_PRIORITY)
}

internal fun exposureAvailability(caps: ExposureCapabilities, constrainedHighSpeed: Boolean = false): ExposureAvailability {
    if (constrainedHighSpeed) return ExposureAvailability()
    val manual = caps.supports(ExposureMode.MANUAL)
    val isoPriority = caps.supports(ExposureMode.ISO_PRIORITY)
    val shutterPriority = caps.supports(ExposureMode.SHUTTER_PRIORITY)
    return ExposureAvailability(manual, isoPriority, shutterPriority,
        caps.isoRange.takeIf { manual || isoPriority }, caps.timeRangeNs.takeIf { manual || shutterPriority })
}

/**
 * What an exposure control displays: the requested value while the selected mode applies it,
 * otherwise the value the camera reports, marked [automatic]. Showing the stored request while
 * auto exposure runs is how Settings came to say ISO 100 while the camera said 200.
 */
internal data class ExposureReading<T>(val value: T?, val automatic: Boolean)

internal fun isoReading(availability: ExposureAvailability, mode: ExposureMode, requested: Int, reported: Int?): ExposureReading<Int> =
    if (availability.appliesIso(mode)) ExposureReading(requested, false) else ExposureReading(reported, true)

internal fun timeReading(availability: ExposureAvailability, mode: ExposureMode, requestedNs: Long, reportedNs: Long?): ExposureReading<Long> =
    if (availability.appliesTime(mode)) ExposureReading(requestedNs, false) else ExposureReading(reportedNs, true)

/** High-speed sessions leave exposure and white balance to the camera. */
internal fun CameraUiState.highSpeedSession(): Boolean =
    activeVideoProfile?.constrainedHighSpeed == true || activeLogProfile?.constrainedHighSpeed == true

/** The span the ISO and shutter controls move within: the camera's, or wide defaults when it reports none. */
internal data class ExposureControlRanges(val isoMin: Double, val isoMax: Double, val timeMin: Double, val timeMax: Double)

internal fun exposureControlRanges(descriptor: Camera2CameraDescriptor) = ExposureControlRanges(
    isoMin = descriptor.sensitivityRange?.lower?.toDouble() ?: 50.0,
    isoMax = descriptor.sensitivityRange?.upper?.toDouble() ?: 6400.0,
    timeMin = descriptor.exposureTimeRangeNs?.lower?.toDouble() ?: 100_000.0,
    timeMax = descriptor.exposureTimeRangeNs?.upper?.toDouble() ?: 1_000_000_000.0,
)

/**
 * What the ISO or shutter control starts from. In full auto and in the other value's priority mode
 * the camera picks the value itself ([autoHere]), so the control follows what it reports.
 */
internal data class ExposureControlValue(val autoHere: Boolean, val angle: Boolean, val iso: Int?, val timeNs: Long?)

internal fun exposureControlValue(iso: Boolean, state: CameraUiState, settings: CameraSettings): ExposureControlValue {
    val mode = settings.exposure.mode
    val autoHere = mode == ExposureMode.AUTO || mode == (if (iso) ExposureMode.SHUTTER_PRIORITY else ExposureMode.ISO_PRIORITY)
    return ExposureControlValue(
        autoHere = autoHere,
        angle = !iso && settings.exposure.shutterUnit == ShutterUnit.ANGLE,
        iso = if (autoHere) state.sensitivityIso ?: state.requestedIso else state.requestedIso ?: state.sensitivityIso,
        timeNs = if (autoHere) state.exposureTimeNs ?: state.requestedExposureTimeNs else state.requestedExposureTimeNs ?: state.exposureTimeNs,
    )
}

/**
 * The exposure after the operator sets ISO to [value]: ISO priority, or manual where the camera has
 * it and the shutter is already set by hand. Leaving full auto keeps the shutter the camera chose.
 */
internal fun exposureWithIso(settings: CameraSettings, state: CameraUiState, manual: Boolean, value: Int): ExposureSelection {
    val mode = settings.exposure.mode
    return settings.exposure.copy(
        mode = if (mode == ExposureMode.ISO_PRIORITY || !manual) ExposureMode.ISO_PRIORITY else ExposureMode.MANUAL,
        iso = value,
        timeNs = if (mode == ExposureMode.AUTO) state.exposureTimeNs ?: settings.exposure.timeNs else settings.exposure.timeNs,
    )
}

/** The shutter twin of [exposureWithIso]: set [timeNs] or, in angle units, [angleTenths]. */
internal fun exposureWithShutter(settings: CameraSettings, state: CameraUiState, manual: Boolean, timeNs: Long? = null, angleTenths: Int? = null): ExposureSelection {
    val mode = settings.exposure.mode
    val intent = settings.exposure.copy(
        mode = if (mode == ExposureMode.SHUTTER_PRIORITY || !manual) ExposureMode.SHUTTER_PRIORITY else ExposureMode.MANUAL,
        iso = if (mode == ExposureMode.AUTO) state.sensitivityIso ?: settings.exposure.iso else settings.exposure.iso,
    )
    return when {
        angleTenths != null -> intent.copy(angleTenths = angleTenths.coerceIn(1, 3600))
        timeNs != null -> intent.copy(timeNs = timeNs)
        else -> intent
    }
}
