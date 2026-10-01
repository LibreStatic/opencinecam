/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.hardware.camera2.CaptureRequest
import com.librestatic.opencinecam.camera.Antibanding
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import java.util.Locale
import kotlin.math.roundToInt

/** How far a camera feeds one OpenCineCam feature. */
enum class AppFeatureStatus { ACTIVE, PARTIAL, UNAVAILABLE }

/** OpenCineCam features that depend on what the camera advertises; [usage] says what each one powers. */
enum class AppFeature(val title: Int, val usage: Int) {
    VIDEO(R.string.caps_feature_video, R.string.caps_usage_video),
    HIGH_SPEED(R.string.caps_feature_high_speed, R.string.caps_usage_high_speed),
    OCLOG(R.string.caps_feature_oclog, R.string.caps_usage_oclog),
    MANUAL_EXPOSURE(R.string.caps_feature_manual_exposure, R.string.caps_usage_manual_exposure),
    EXPOSURE_PRIORITY(R.string.caps_feature_exposure_priority, R.string.caps_usage_exposure_priority),
    EXPOSURE_COMPENSATION(R.string.caps_feature_ev, R.string.caps_usage_ev),
    ANTIBANDING(R.string.caps_feature_antibanding, R.string.caps_usage_antibanding),
    AE_LOCK(R.string.caps_feature_ae_lock, R.string.caps_usage_ae_lock),
    FOCUS(R.string.caps_feature_focus, R.string.caps_usage_focus),
    METERING_REGIONS(R.string.caps_feature_regions, R.string.caps_usage_regions),
    WHITE_BALANCE(R.string.caps_feature_white_balance, R.string.caps_usage_white_balance),
    AWB_LOCK(R.string.caps_feature_awb_lock, R.string.caps_usage_awb_lock),
    ZOOM(R.string.caps_feature_zoom, R.string.caps_usage_zoom),
    LENS_SWITCH(R.string.caps_feature_lens_switch, R.string.caps_usage_lens_switch),
    HFR_ZOOM(R.string.caps_feature_hfr_zoom, R.string.caps_usage_hfr_zoom),
    STABILIZATION(R.string.caps_feature_stabilization, R.string.caps_usage_stabilization),
    NOISE_EDGE(R.string.caps_feature_noise_edge, R.string.caps_usage_noise_edge),
    RAW(R.string.caps_feature_raw, R.string.caps_usage_raw),
    HEIC(R.string.caps_feature_heic, R.string.caps_usage_heic),
    TORCH(R.string.caps_feature_torch, R.string.caps_usage_torch),
    PHOTO_FLASH(R.string.caps_feature_flash, R.string.caps_usage_flash),
    SENSOR_CLOCK(R.string.caps_feature_sensor_clock, R.string.caps_usage_sensor_clock),
}

/** [detail] holds only numbers, units and API names, so it reads the same in every language. */
data class AppFeatureFinding(val feature: AppFeature, val status: AppFeatureStatus, val detail: String = "")

/**
 * Maps the descriptor the capture engine negotiated (not the raw characteristics) onto app
 * features, so the page shows exactly what OpenCineCam will offer for this camera.
 */
fun auditCameraCapabilities(d: Camera2CameraDescriptor): List<AppFeatureFinding> = AppFeature.entries.map { feature ->
    when (feature) {
        AppFeature.VIDEO -> {
            val regular = d.videoProfiles.filterNot { it.constrainedHighSpeed }
            val largest = regular.maxByOrNull { it.size.width.toLong() * it.size.height }
            if (largest == null) feature.unavailable()
            else feature.active("max ${largest.size.width}×${largest.size.height} · ${fpsList(regular.map { it.fps })} fps")
        }
        AppFeature.HIGH_SPEED -> {
            val hs = d.videoProfiles.filter { it.constrainedHighSpeed }
            if (hs.isEmpty()) feature.unavailable()
            else feature.active(hs.groupBy { it.fps }.toSortedMap().entries.joinToString(" · ") { (fps, profiles) ->
                val top = profiles.maxBy { it.size.width.toLong() * it.size.height }.size
                "$fps fps ${top.width}×${top.height}"
            })
        }
        AppFeature.OCLOG -> {
            val hlg = d.logProfiles.filter { it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020 }
            val isp = d.logProfiles.filter { it.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP }
            val verified = "${d.logProfiles.count { it.isVerified }}/${d.logProfiles.size} verified"
            when {
                hlg.isNotEmpty() -> feature.active("HLG10 · ${hlg.size} profiles" + (if (isp.isNotEmpty()) " · HFR ISP ${isp.size}" else "") + " · $verified")
                isp.isNotEmpty() -> feature.partial("HFR ISP only · ${isp.size} profiles · $verified")
                else -> feature.unavailable()
            }
        }
        AppFeature.MANUAL_EXPOSURE -> {
            val caps = d.exposureCapabilities
            val iso = caps.isoRange?.takeIf { it.first > 0 && it.last >= it.first }
            val time = caps.timeRangeNs?.takeIf { it.first > 0 && it.last >= it.first }
            when {
                caps.manual && iso != null && time != null ->
                    feature.active("ISO ${iso.first}–${iso.last} · ${shutter(time.first)}–${shutter(time.last)}")
                caps.manual -> feature.partial("MANUAL_SENSOR")
                else -> feature.unavailable()
            }
        }
        AppFeature.EXPOSURE_PRIORITY -> {
            val names = d.exposureCapabilities.priorities.sorted().map {
                when (it) {
                    ExposureMode.ISO_PRIORITY -> "ISO"
                    ExposureMode.SHUTTER_PRIORITY -> "Shutter"
                    else -> it.name
                }
            }
            if (names.isEmpty()) feature.unavailable() else feature.active(names.joinToString(" · "))
        }
        AppFeature.EXPOSURE_COMPENSATION -> {
            val range = d.aeCompensationRange
            if (range == null || range.lower >= range.upper || d.aeCompensationStep <= 0f) feature.unavailable()
            else feature.active("${ev(range.lower * d.aeCompensationStep)} … ${ev(range.upper * d.aeCompensationStep)} EV · " +
                "${d.aeCompensationStepNumerator}/${d.aeCompensationStepDenominator} EV")
        }
        AppFeature.ANTIBANDING -> {
            val modes = d.exposureCapabilities.antibanding
            val labels = listOf(Antibanding.AUTO to "AUTO", Antibanding.HZ50 to "50 Hz", Antibanding.HZ60 to "60 Hz", Antibanding.OFF to "OFF")
                .filter { it.first in modes }.map { it.second }
            when {
                Antibanding.HZ50 in modes && Antibanding.HZ60 in modes -> feature.active(labels.joinToString(" · "))
                labels.isNotEmpty() -> feature.partial(labels.joinToString(" · "))
                else -> feature.unavailable()
            }
        }
        AppFeature.AE_LOCK -> if (d.aeLockSupported) feature.active() else feature.unavailable()
        AppFeature.FOCUS -> {
            val minimum = d.minimumFocusDistance ?: 0f
            val manual = minimum > 0f && CaptureRequest.CONTROL_AF_MODE_OFF in d.availableAfModes
            val closest = if (minimum > 0f) "≥ ${focusDistance(minimum)}" else ""
            when {
                manual && d.afLockSupported -> feature.active(closest)
                manual || d.availableAfModes.any { it != CaptureRequest.CONTROL_AF_MODE_OFF } -> feature.partial(closest)
                else -> feature.unavailable("fixed focus")
            }
        }
        AppFeature.METERING_REGIONS -> when {
            d.maxAfRegions > 0 && d.maxAeRegions > 0 -> feature.active("AF ${d.maxAfRegions} · AE ${d.maxAeRegions}")
            d.maxAfRegions > 0 || d.maxAeRegions > 0 -> feature.partial("AF ${d.maxAfRegions} · AE ${d.maxAeRegions}")
            else -> feature.unavailable()
        }
        AppFeature.WHITE_BALANCE -> {
            val kelvin = d.kelvinRange
            // AUTO and OFF are not presets; the remaining AWB modes are what the preset chips offer.
            val presets = d.availableAwbModes.count { it != CaptureRequest.CONTROL_AWB_MODE_AUTO && it != CaptureRequest.CONTROL_AWB_MODE_OFF }
            when {
                kelvin != null -> feature.active("${kelvin.first}–${kelvin.last} K" + (if (d.tintSupported) " · tint" else "") + " · $presets presets")
                presets > 0 -> feature.partial("$presets presets")
                else -> feature.unavailable()
            }
        }
        AppFeature.AWB_LOCK -> if (d.awbLockSupported) feature.active() else feature.unavailable()
        AppFeature.ZOOM -> {
            val range = d.effectiveZoomRange
            when {
                range == null -> feature.unavailable()
                d.supportsZoomRatioApi -> feature.active("${ratio(range.start)}–${ratio(range.endInclusive)}")
                else -> feature.partial("SCALER_CROP_REGION ${ratio(range.start)}–${ratio(range.endInclusive)}")
            }
        }
        AppFeature.LENS_SWITCH ->
            if (d.opticalAnchors.size > 1) feature.active(d.opticalAnchors.joinToString(" · ") { "${ratio(it.ratio)} (${mm(it.focalLengthMm)})" })
            else feature.unavailable()
        AppFeature.HFR_ZOOM -> when {
            d.videoProfiles.none { it.constrainedHighSpeed } -> feature.unavailable()
            d.supportsHfrZoom -> feature.active()
            else -> feature.unavailable()
        }
        AppFeature.STABILIZATION -> {
            val ip = d.imageProcessingCapabilities
            val ois = ip.opticalModes.any { it != CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF }
            val eis = ip.videoModes.any { it == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON }
            val preview = ip.videoModes.any { it == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION }
            val labels = listOfNotNull("OIS".takeIf { ois }, "EIS".takeIf { eis }, "PREVIEW_STABILIZATION".takeIf { preview })
            if (labels.isEmpty()) feature.unavailable() else feature.active(labels.joinToString(" · "))
        }
        AppFeature.NOISE_EDGE -> {
            val ip = d.imageProcessingCapabilities
            val noise = ip.noiseModes.sorted().joinToString(", ") { PROCESSING_MODE[it] ?: it.toString() }
            val edge = ip.edgeModes.sorted().joinToString(", ") { PROCESSING_MODE[it] ?: it.toString() }
            val detail = listOfNotNull(noise.takeIf { it.isNotEmpty() }?.let { "NR $it" }, edge.takeIf { it.isNotEmpty() }?.let { "EDGE $it" })
                .joinToString(" · ")
            val canTurnOff = 0 in ip.noiseModes && 0 in ip.edgeModes
            when {
                canTurnOff -> feature.active(detail)
                detail.isNotEmpty() -> feature.partial(detail)
                else -> feature.unavailable()
            }
        }
        AppFeature.RAW -> {
            val raw = d.rawSize
            if (d.supportsRaw && raw != null) feature.active("DNG ${raw.width}×${raw.height}") else feature.unavailable()
        }
        AppFeature.HEIC -> d.heicSize?.let { feature.active("${it.width}×${it.height}") } ?: feature.unavailable()
        AppFeature.TORCH -> {
            val torch = d.torchCapabilities
            when {
                torch.adjustable -> feature.active("1–${torch.maxLevel}")
                torch.available -> feature.partial("on/off")
                else -> feature.unavailable()
            }
        }
        AppFeature.PHOTO_FLASH -> {
            val flash = d.photoFlashCapabilities
            when {
                !flash.available -> feature.unavailable()
                flash.singleMax > 1 -> feature.active("1–${flash.singleMax}")
                else -> feature.active()
            }
        }
        AppFeature.SENSOR_CLOCK ->
            if (d.timestampSourceRealtime) feature.active("REALTIME") else feature.partial("UNKNOWN")
    }
}

private fun AppFeature.active(detail: String = "") = AppFeatureFinding(this, AppFeatureStatus.ACTIVE, detail)
private fun AppFeature.partial(detail: String = "") = AppFeatureFinding(this, AppFeatureStatus.PARTIAL, detail)
private fun AppFeature.unavailable(detail: String = "") = AppFeatureFinding(this, AppFeatureStatus.UNAVAILABLE, detail)

private val PROCESSING_MODE = mapOf(0 to "OFF", 1 to "FAST", 2 to "HQ", 3 to "MINIMAL", 4 to "ZSL")

private fun fpsList(values: List<Int>): String = values.distinct().sorted().joinToString("/")

/** Exposure times as photographers read them: fractions below one second, seconds above. */
internal fun shutter(ns: Long): String = when {
    ns >= 1_000_000_000L -> String.format(Locale.ROOT, "%.1f s", ns / 1e9).replace(".0 s", " s")
    else -> "1/${(1e9 / ns).roundToInt()}"
}

/** Closest focus from LENS_INFO_MINIMUM_FOCUS_DISTANCE (diopters): centimetres up close, metres beyond. */
internal fun focusDistance(diopters: Float): String {
    val cm = 100f / diopters
    return if (cm < 100f) "${cm.roundToInt()} cm" else String.format(Locale.ROOT, "%.1f m", cm / 100f).replace(".0 m", " m")
}

private fun ev(value: Float): String = String.format(Locale.ROOT, "%+.1f", value)
private fun ratio(value: Float): String = String.format(Locale.ROOT, "%.1f×", value).replace(".0×", "×")
private fun mm(value: Float): String = String.format(Locale.ROOT, "%.1f mm", value).replace(".0 mm", " mm")
