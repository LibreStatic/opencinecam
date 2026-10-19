/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.PolicyMode

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
    /** Qualcomm exposure-time priority range in ns (vendor characteristics key), when the camera has it. */
    val qcomExposureRangeNs: LongRange? = null,
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
    val upper = caps.timeUpperNs(rate)
    if (mode != ExposureMode.ISO_PRIORITY && (range == null || upper == null || upper < range.first)) {
        return ResolvedExposure(ExposureMode.AUTO, antibanding = band, unavailable = true)
    }
    val resolvedTime = if (mode != ExposureMode.ISO_PRIORITY) requestedTime.coerceIn(requireNotNull(range).first, requireNotNull(upper)) else null
    return ResolvedExposure(mode, resolvedIso, resolvedTime, rate.frameDurationNs.takeIf { mode == ExposureMode.MANUAL }, band,
        clamped = (resolvedIso != null && resolvedIso != iso) || (resolvedTime != null && resolvedTime != requestedTime))
}

private fun ExposureCapabilities.timeUpperNs(rate: CaptureFrameRate): Long? =
    timeRangeNs?.last?.coerceAtMost((rate.frameDurationNs - 100_000L).coerceAtLeast(1))

/** Why a requested exposure was not applied as asked: a clamped value, an unsupported mode, or ranges the camera never reported. */
enum class ExposureGateCode(val code: String) {
    OUT_OF_RANGE("exposure-out-of-range"),
    MODE_UNSUPPORTED("exposure-mode-unsupported"),
    CAPABILITY_UNKNOWN("exposure-capability-unknown"),
}

/**
 * The exposure to send plus the outcome to disclose. [exposure] is the AUTO request (AE on, no manual sensor
 * values) when [rejected]; otherwise the resolved (possibly clamped or AUTO-fallback) exposure. [code] and
 * [message] are null when the request was honoured exactly.
 */
data class ExposureGate(val exposure: ResolvedExposure, val code: ExposureGateCode? = null, val message: String? = null, val rejected: Boolean = false)

/** STRICT rejects what [resolve] would coerce; ADAPTIVE applies the coerced value and names it in [ExposureGate.message]. */
fun ExposureSelection.gate(caps: ExposureCapabilities, rate: CaptureFrameRate, policy: PolicyMode): ExposureGate {
    val resolved = resolve(caps, rate)
    if (mode == ExposureMode.AUTO) return ExposureGate(resolved)
    val strict = policy == PolicyMode.STRICT
    fun outcome(code: ExposureGateCode, strictMessage: String, adaptiveMessage: String): ExposureGate =
        if (strict) ExposureGate(copy(mode = ExposureMode.AUTO).resolve(caps, rate), code, strictMessage, rejected = true)
        else ExposureGate(resolved, code, adaptiveMessage)
    if (resolved.unavailable) {
        val needsIso = mode != ExposureMode.SHUTTER_PRIORITY
        val needsTime = mode != ExposureMode.ISO_PRIORITY
        val unknown = (needsIso && caps.isoRange == null) || (needsTime && caps.timeRangeNs == null)
        val name = mode.name.lowercase().replace('_', ' ')
        return if (unknown) outcome(ExposureGateCode.CAPABILITY_UNKNOWN,
            "Exposure capability of this lens is unknown; $name exposure not applied",
            "Exposure capability of this lens is unknown; using auto exposure instead of $name")
        else outcome(ExposureGateCode.MODE_UNSUPPORTED,
            "This lens does not support $name exposure",
            "This lens does not support $name exposure; using auto exposure")
    }
    if (!resolved.clamped) return ExposureGate(resolved)
    val parts = mutableListOf<Pair<String, String>>()
    val iso = resolved.iso
    if (iso != null && iso != this.iso) parts += "ISO ${this.iso} outside ${caps.isoRange?.first}-${caps.isoRange?.last} on this lens" to "ISO ${this.iso} clamped to $iso on this lens"
    val time = resolved.timeNs
    val asked = requestedTimeNs(rate)
    if (time != null && time != asked) {
        val upper = caps.timeUpperNs(rate)
        parts += "Exposure time ${formatTime(asked)} outside ${formatTime(caps.timeRangeNs?.first)}-${formatTime(upper)} on this lens" to
            "Exposure time ${formatTime(asked)} clamped to ${formatTime(time)} on this lens"
    }
    return outcome(ExposureGateCode.OUT_OF_RANGE, parts.joinToString("; ") { it.first }, parts.joinToString("; ") { it.second })
}

private fun formatTime(ns: Long?): String = when {
    ns == null -> "?"
    ns >= 500_000_000L -> "%.1f s".format(java.util.Locale.ROOT, ns / 1e9)
    else -> "1/${Math.round(1e9 / ns)} s"
}
