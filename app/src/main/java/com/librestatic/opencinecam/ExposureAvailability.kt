/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.ExposureCapabilities
import com.librestatic.opencinecam.camera.ExposureMode

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
