/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Capture rate, not project/timecode rate. Current Android profiles pass an integer denominator. */
data class CaptureFrameRate(val numerator: Int, val denominator: Int = 1) {
    init { require(numerator in 1..1_000_000 && denominator in 1..1_000_000) }
    val frameDurationNs: Long get() = 1_000_000_000L * denominator / numerator
}

enum class ShutterUnit { TIME, ANGLE }
enum class Antibanding { AUTO, OFF, HZ50, HZ60 }

/**
 * Qualcomm AE metering (org.codeaurora.qcamera3.exposure_metering). The vendor values follow the
 * HAL's cam_auto_exposure_mode_type: 0 frame average, 1 centre weighted, 2 spot. Moto Razr Fold
 * advertises available_modes = [0 1 2] (int32[3]) and the int32 request key exposure_metering_mode
 * (0x80960000). Further HAL values (smart, user, advanced spot) are not mapped and are omitted.
 */
enum class ExposureMetering(val vendorValue: Int) {
    FRAME_AVERAGE(0), CENTER_WEIGHTED(1), SPOT(2);

    companion object {
        const val REQUEST_KEY_NAME = "org.codeaurora.qcamera3.exposure_metering.exposure_metering_mode"
        const val AVAILABLE_MODES_KEY_NAME = "org.codeaurora.qcamera3.exposure_metering.available_modes"

        fun fromVendor(value: Int?): ExposureMetering? = entries.firstOrNull { it.vendorValue == value }
    }
}

/** Modes OpenCineCam offers: none unless the request key exists, and only advertised, known values. */
fun meteringModesOf(requestKeyPresent: Boolean, advertised: IntArray?): Set<ExposureMetering> =
    if (!requestKeyPresent || advertised == null) emptySet() else advertised.asList().mapNotNull(ExposureMetering::fromVendor).toSet()

data class ExposureSelection(
    val mode: ExposureMode = ExposureMode.AUTO,
    val iso: Int = 100,
    val timeNs: Long = 16_666_667L,
    val shutterUnit: ShutterUnit = ShutterUnit.TIME,
    val angleTenths: Int = 1800,
    val antibanding: Antibanding = Antibanding.AUTO,
    /** Requests the low-light boost AE mode where it applies; see [resolveLowLightBoost]. */
    val lowLightBoost: Boolean = false,
    /** Null leaves the device's own metering untouched. */
    val metering: ExposureMetering? = null,
) {
    init { require(iso > 0 && timeNs > 0 && angleTenths in 1..3600) }
    fun requestedTimeNs(rate: CaptureFrameRate): Long = if (shutterUnit == ShutterUnit.TIME) timeNs else
        (1_000_000_000L * rate.denominator * angleTenths / (3600L * rate.numerator)).coerceAtLeast(1)
}

data class ExposureCapabilities(
    val manual: Boolean = false,
    val isoRange: IntRange? = null,
    val timeRangeNs: LongRange? = null,
    val priorities: Set<ExposureMode> = emptySet(),
    val antibanding: Set<Antibanding> = emptySet(),
    val meteringModes: Set<ExposureMetering> = emptySet(),
    /** CONTROL_POST_RAW_SENSITIVITY_BOOST range when the request key is available (100 = no boost). */
    val postRawBoostRange: IntRange? = null,
) {
    private val validIso get() = isoRange?.let { it.first > 0 && it.last >= it.first } == true
    private val validTime get() = timeRangeNs?.let { it.first > 0 && it.last >= it.first } == true
    fun supports(mode: ExposureMode): Boolean = when (mode) {
        ExposureMode.AUTO -> true
        ExposureMode.MANUAL -> manual && validIso && validTime
        ExposureMode.ISO_PRIORITY -> mode in priorities && validIso
        ExposureMode.SHUTTER_PRIORITY -> mode in priorities && validTime
    }
}

data class ResolvedExposure(
    val mode: ExposureMode,
    val iso: Int? = null,
    val timeNs: Long? = null,
    val frameDurationNs: Long? = null,
    val antibanding: Antibanding? = null,
    val unavailable: Boolean = false,
    val clamped: Boolean = false,
    /** Set only while AE is automatic (including priority modes) and the device advertises the mode. */
    val metering: ExposureMetering? = null,
)

/** Every manual request supplies both values; unavailable native priority is explicitly disclosed. */
fun ExposureSelection.resolve(caps: ExposureCapabilities, rate: CaptureFrameRate): ResolvedExposure {
    val resolved = resolveExposure(caps, rate)
    val meter = metering?.takeIf { it in caps.meteringModes && resolved.mode != ExposureMode.MANUAL }
    return if (meter == null) resolved else resolved.copy(metering = meter)
}

private fun ExposureSelection.resolveExposure(caps: ExposureCapabilities, rate: CaptureFrameRate): ResolvedExposure {
    val band = antibanding.takeIf { it in caps.antibanding }
    if (!caps.supports(mode)) return ResolvedExposure(ExposureMode.AUTO, antibanding = band, unavailable = true)
    if (mode == ExposureMode.AUTO) return ResolvedExposure(mode, antibanding = band)
    val resolvedIso = if (mode != ExposureMode.SHUTTER_PRIORITY) iso.coerceIn(requireNotNull(caps.isoRange)) else null
    val requestedTime = requestedTimeNs(rate)
    val range = caps.timeRangeNs
    val upper = range?.last?.coerceAtMost((rate.frameDurationNs - 100_000L).coerceAtLeast(1))
    if (mode != ExposureMode.ISO_PRIORITY && (range == null || upper == null || upper < range.first)) {
        return ResolvedExposure(ExposureMode.AUTO, antibanding = band, unavailable = true)
    }
    val resolvedTime = if (mode != ExposureMode.ISO_PRIORITY) requestedTime.coerceIn(requireNotNull(range).first, requireNotNull(upper)) else null
    return ResolvedExposure(mode, resolvedIso, resolvedTime, rate.frameDurationNs.takeIf { mode == ExposureMode.MANUAL }, band,
        clamped = (resolvedIso != null && resolvedIso != iso) || (resolvedTime != null && resolvedTime != requestedTime))
}
