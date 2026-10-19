/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.hardware.camera2.CaptureRequest
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.Antibanding
import com.librestatic.opencinecam.camera.ExposureMetering
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ImageProcessingDefaults
import com.librestatic.opencinecam.camera.PictureControlReadback
import com.librestatic.opencinecam.camera.ResolvedExposure
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import kotlin.math.abs

/** One value the camera reported back differently from what OpenCineCam asked for. */
internal sealed interface ReadbackDifference {
    data class Mode(val reported: ExposureMode) : ReadbackDifference
    data class Iso(val reported: Int) : ReadbackDifference
    data class ExposureTime(val reportedNs: Long) : ReadbackDifference
    /** [reported] is null for a mode code OpenCineCam does not know. */
    data class Banding(val reported: Antibanding?) : ReadbackDifference
    /** [reported] is null for a vendor value OpenCineCam does not map. */
    data class Metering(val reported: ExposureMetering?) : ReadbackDifference
    data class Temperature(val reportedK: Int) : ReadbackDifference
    data class Optical(val reported: Int) : ReadbackDifference
    data class Video(val reported: Int) : ReadbackDifference
    data class Noise(val reported: Int) : ReadbackDifference
    data class Edge(val reported: Int) : ReadbackDifference
    data class Saturation(val reported: Int) : ReadbackDifference
    data class Sharpness(val reported: Int) : ReadbackDifference
}

/** Sensors round exposure to whole lines, so a report this close to the request is the same shutter. */
private const val TIME_TOLERANCE = 0.01
/** The reported colour temperature is an estimate; this close it matches the request. */
private const val TEMPERATURE_TOLERANCE_K = 100
/** The lens settles on a manual focus distance over several frames; this close (in diopters) it is the requested focus. */
private const val FOCUS_TOLERANCE_DIOPTERS = 0.3f
/** The measured frame rate is a smoothed estimate from sensor timestamps; this close (relative) it is the requested rate. */
private const val FPS_TOLERANCE = 0.05

internal fun antibandingOf(code: Int?): Antibanding? = when (code) {
    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO -> Antibanding.AUTO
    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF -> Antibanding.OFF
    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ -> Antibanding.HZ50
    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ -> Antibanding.HZ60
    else -> null
}

/**
 * Where the camera's exposure differs from the request. Automatic exposure picks its own ISO and
 * time, so only values the request set are compared, and an unknown report never counts.
 */
internal fun exposureReadbackDifferences(
    requested: ResolvedExposure,
    reportedMode: ExposureMode?,
    reportedIso: Int?,
    reportedTimeNs: Long?,
    reportedAntibanding: Int?,
    reportedMetering: Int? = null,
): List<ReadbackDifference> = buildList {
    if (reportedMode != null && reportedMode != requested.mode) add(ReadbackDifference.Mode(reportedMode))
    val iso = requested.iso
    if (iso != null && reportedIso != null && reportedIso != iso) add(ReadbackDifference.Iso(reportedIso))
    val time = requested.timeNs
    if (time != null && reportedTimeNs != null && abs(reportedTimeNs - time) > time * TIME_TOLERANCE) {
        add(ReadbackDifference.ExposureTime(reportedTimeNs))
    }
    val band = requested.antibanding
    if (band != null && reportedAntibanding != null && antibandingOf(reportedAntibanding) != band) {
        add(ReadbackDifference.Banding(antibandingOf(reportedAntibanding)))
    }
    val metering = requested.metering
    if (metering != null && reportedMetering != null && reportedMetering != metering.vendorValue) {
        add(ReadbackDifference.Metering(ExposureMetering.fromVendor(reportedMetering)))
    }
}

/** Only a fixed colour temperature can disagree; automatic and presets have nothing to compare. */
internal fun whiteBalanceReadbackDifferences(requested: WhiteBalanceSelection, reportedK: Int?): List<ReadbackDifference> =
    if (requested is WhiteBalanceSelection.Kelvin && reportedK != null && abs(reportedK - requested.kelvin) > TEMPERATURE_TOLERANCE_K) {
        listOf(ReadbackDifference.Temperature(reportedK))
    } else emptyList()

/** Compares the request actually submitted to the camera, not the stored preference. */
internal fun imageProcessingReadbackDifferences(submitted: ImageProcessingDefaults?, reported: ImageProcessingDefaults): List<ReadbackDifference> {
    if (submitted == null) return emptyList()
    fun differs(sent: Int?, got: Int?): Int? = got.takeIf { sent != null && got != null && got != sent }
    return listOfNotNull(
        differs(submitted.optical, reported.optical)?.let(ReadbackDifference::Optical),
        differs(submitted.video, reported.video)?.let(ReadbackDifference::Video),
        differs(submitted.noise, reported.noise)?.let(ReadbackDifference::Noise),
        differs(submitted.edge, reported.edge)?.let(ReadbackDifference::Edge),
    )
}

/** Only a vendor value the request set can disagree; a cleared key has nothing to compare. */
internal fun pictureControlReadbackDifferences(readback: PictureControlReadback?): List<ReadbackDifference> {
    if (readback == null) return emptyList()
    return listOfNotNull(
        readback.reportedSaturation?.takeIf { readback.submittedSaturation != null && it != readback.submittedSaturation }?.let(ReadbackDifference::Saturation),
        readback.reportedSharpness?.takeIf { readback.submittedSharpness != null && it != readback.submittedSharpness }?.let(ReadbackDifference::Sharpness),
    )
}

/**
 * The pairs the HUD judges, one per control the user actually requested: exposure mode, ISO and time
 * (the time within [TIME_TOLERANCE]), antibanding, metering, a fixed colour temperature (within
 * [TEMPERATURE_TOLERANCE_K]), the AF mode and, under manual focus, the focus distance (within
 * [FOCUS_TOLERANCE_DIOPTERS]), the frame rate (within [FPS_TOLERANCE]), the processing modes actually submitted, and the vendor picture
 * controls. Automatic values, unknown reports and cleared requests produce no pair.
 */
internal fun readbackSamples(state: CameraUiState): List<ReadbackSample> = buildList {
    fun add(key: String, requested: Any?, reported: Any?, within: Boolean) {
        if (requested != null && reported != null) add(ReadbackSample(key, requested.toString(), reported.toString(), within))
    }
    state.reportedExposureMode?.let { add("android.control.aeMode", state.requestedExposureMode.name, it.name, it == state.requestedExposureMode) }
    add("android.sensor.sensitivity", state.requestedIso, state.sensitivityIso, state.requestedIso == state.sensitivityIso)
    val time = state.requestedExposureTimeNs
    val reportedTime = state.exposureTimeNs
    if (time != null && reportedTime != null) add("android.sensor.exposureTime", "$time ns", "$reportedTime ns", abs(reportedTime - time) <= time * TIME_TOLERANCE)
    val exposure = state.effectiveSettings?.exposure
    val band = exposure?.antibanding
    val reportedBand = state.reportedAntibanding
    if (band != null && reportedBand != null) add("android.control.aeAntibandingMode", band.name, antibandingOf(reportedBand)?.name ?: "#$reportedBand", antibandingOf(reportedBand) == band)
    val metering = exposure?.metering
    val reportedMetering = state.reportedMeteringMode
    if (metering != null && reportedMetering != null) add("org.codeaurora.qcamera3.exposure_metering.exposure_metering_mode",
        metering.name, ExposureMetering.fromVendor(reportedMetering)?.name ?: "#$reportedMetering", reportedMetering == metering.vendorValue)
    val wb = state.requestedWhiteBalance
    val reportedK = state.reportedColorTemperatureK
    if (wb is WhiteBalanceSelection.Kelvin && reportedK != null) add("android.colorCorrection.colorTemperature", "${wb.kelvin} K", "$reportedK K", abs(reportedK - wb.kelvin) <= TEMPERATURE_TOLERANCE_K)
    add("android.control.afMode", state.submittedAfMode, state.reportedAfMode, state.submittedAfMode == state.reportedAfMode)
    val focus = state.requestedFocusDiopters
    val reportedFocus = state.focusDistanceDiopters
    if (focus != null && reportedFocus != null) add("android.lens.focusDistance", "$focus dpt", "$reportedFocus dpt", abs(reportedFocus - focus) <= FOCUS_TOLERANCE_DIOPTERS)
    // Dynamic range has no reported side in the UI state (only the requested profile), so it has no pair.
    state.effectiveFps?.let { fps -> add("opencinecam.frameRate", "${state.targetFps} fps", "%.2f fps".format(java.util.Locale.ROOT, fps), abs(fps - state.targetFps) <= state.targetFps * FPS_TOLERANCE) }
    state.submittedImageProcessing?.let { sent ->
        add("android.lens.opticalStabilizationMode", sent.optical, state.reportedOpticalStabilization, sent.optical == state.reportedOpticalStabilization)
        add("android.control.videoStabilizationMode", sent.video, state.reportedVideoStabilization, sent.video == state.reportedVideoStabilization)
        add("android.noiseReduction.mode", sent.noise, state.reportedNoiseReduction, sent.noise == state.reportedNoiseReduction)
        add("android.edge.mode", sent.edge, state.reportedEdgeEnhancement, sent.edge == state.reportedEdgeEnhancement)
    }
    state.pictureControlReadback?.let { picture ->
        add("org.codeaurora.qcamera3.saturation.use_saturation", picture.submittedSaturation, picture.reportedSaturation, picture.submittedSaturation == picture.reportedSaturation)
        add("org.codeaurora.qcamera3.sharpness.strength", picture.submittedSharpness, picture.reportedSharpness, picture.submittedSharpness == picture.reportedSharpness)
    }
}

private val OPTICAL_MODES = mapOf(0 to "OFF", 1 to "ON")
private val VIDEO_MODES = mapOf(0 to "OFF", 1 to "ON", 2 to "PREVIEW_STABILIZATION")
private val PROCESSING_MODES = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "MINIMAL", 4 to "ZERO_SHUTTER_LAG")
private val TONEMAP_MODES = mapOf(0 to "CONTRAST_CURVE", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "GAMMA_VALUE", 4 to "PRESET_CURVE")
private val AWB_MODES = mapOf(1 to "AUTO", 2 to "INCANDESCENT", 3 to "FLUORESCENT", 4 to "WARM_FLUORESCENT", 5 to "DAYLIGHT",
    6 to "CLOUDY_DAYLIGHT", 7 to "TWILIGHT", 8 to "SHADE")

/**
 * Every request next to what the camera reported in its latest result, under the Camera2 key
 * names, so it reads the same in every language. This is the full detail that Settings sums up
 * in one line; it belongs to the capabilities page and its copied report.
 */
internal fun requestedReportedLines(state: CameraUiState): List<Pair<String, String>> {
    val sent = state.submittedImageProcessing
    val wb = state.requestedWhiteBalance
    fun code(value: Int?, names: Map<Int, String>) = value?.let { "${names[it] ?: "#$it"} ($it)" }
    fun time(ns: Long?) = ns?.let { "$it ns (${shutter(it)})" }
    val picture = state.pictureControlReadback
    return listOfNotNull(
        "android.control.aeMode" to readback(state.requestedExposureMode.name, state.reportedExposureMode?.name),
        "android.sensor.sensitivity" to readback(state.requestedIso?.toString() ?: "AUTO", state.sensitivityIso?.toString()),
        "android.sensor.exposureTime" to readback(time(state.requestedExposureTimeNs) ?: "AUTO", time(state.exposureTimeNs)),
        "android.control.aeAntibandingMode" to readback(state.effectiveSettings?.exposure?.antibanding?.name,
            antibandingOf(state.reportedAntibanding)?.name ?: state.reportedAntibanding?.let { "#$it" }),
        "android.control.lowLightBoostState" to readback(null, state.reportedLowLightBoost?.let { if (it) "ACTIVE" else "INACTIVE" }),
        ("org.codeaurora.qcamera3.exposure_metering.exposure_metering_mode" to readback(
            state.effectiveSettings?.exposure?.metering?.let { "${it.name} (${it.vendorValue})" },
            state.reportedMeteringMode?.let { "${ExposureMetering.fromVendor(it)?.name ?: "#$it"} ($it)" }))
            .takeIf { state.reportedMeteringMode != null || state.effectiveSettings?.exposure?.metering != null },
        "android.control.awbMode" to readback(when (wb) {
            WhiteBalanceSelection.Auto -> "AUTO"
            is WhiteBalanceSelection.Kelvin -> "OFF"
            is WhiteBalanceSelection.Preset -> code(wb.awbMode, AWB_MODES)
        }, null),
        "android.colorCorrection.colorTemperature" to readback((wb as? WhiteBalanceSelection.Kelvin)?.let { "${it.kelvin} K" },
            state.reportedColorTemperatureK?.let { "$it K" }),
        "android.colorCorrection.colorTint" to readback((wb as? WhiteBalanceSelection.Kelvin)?.tint?.toString(), state.reportedColorTint?.toString()),
        "android.control.awbLock" to readback(null, state.reportedAwbLocked?.toString()),
        "android.lens.opticalStabilizationMode" to readback(code(sent?.optical, OPTICAL_MODES), code(state.reportedOpticalStabilization, OPTICAL_MODES)),
        "android.control.videoStabilizationMode" to readback(code(sent?.video, VIDEO_MODES), code(state.reportedVideoStabilization, VIDEO_MODES)),
        "android.noiseReduction.mode" to readback(code(sent?.noise, PROCESSING_MODES), code(state.reportedNoiseReduction, PROCESSING_MODES)),
        "android.edge.mode" to readback(code(sent?.edge, PROCESSING_MODES), code(state.reportedEdgeEnhancement, PROCESSING_MODES)),
        "android.tonemap.mode" to readback(code(state.submittedToneMapMode, TONEMAP_MODES), code(state.reportedToneMapMode, TONEMAP_MODES)),
        "android.scaler.cropRegion" to readback(null, state.reportedCropRegion?.joinToString(", ", "[", "]")),
        "opencinecam.stillPhysicalRoute" to routeLine(state.hardwareTruth),
        "android.logicalMultiCamera.activePhysicalId" to readback(null, state.hardwareTruth.reported[ACTIVE_LENS_KEY]),
    ) + listOfNotNull(
        picture?.takeIf { it.reportedSaturation != null }?.let {
            "org.codeaurora.qcamera3.saturation.use_saturation" to readback(it.submittedSaturation?.toString(), it.reportedSaturation?.toString()) },
        picture?.takeIf { it.reportedSharpness != null }?.let {
            "org.codeaurora.qcamera3.sharpness.strength" to readback(it.submittedSharpness?.toString(), it.reportedSharpness?.toString()) },
    )
}

/** The still's physical route against the camera its own result names, with the verdict (the preview's active lens is a separate line). */
internal fun routeLine(hud: HardwareTruthHudState): String =
    "${readback(hud.requested[ROUTE_KEY] ?: "logical", hud.reported[ROUTE_KEY])} · ${hud.routeTruth.name}"

/** One line per recorded requested/reported mismatch, oldest first. */
internal fun discrepancyLines(hud: HardwareTruthHudState): List<Pair<String, String>> =
    hud.discrepancyHistory.map { it.key to "${readback(it.requested, it.reported)} · ${it.explanation}" }

private fun readback(requested: String?, reported: String?): String = "${requested ?: "—"} → ${reported ?: "—"}"

/**
 * The readback under a setting: one short secondary line, and only when the camera did something
 * other than what was asked. The full request-versus-report table lives in the capabilities page.
 */
@Composable
internal fun CameraReportsLine(differences: List<ReadbackDifference>, modifier: Modifier = Modifier) {
    if (differences.isEmpty()) return
    val parts = differences.map { readbackLabel(it) }
    Text(stringResource(R.string.settings_camera_reports, parts.joinToString(" · ")), color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp, lineHeight = 18.sp, modifier = modifier.testTag("camera-reports"))
}

@Composable
private fun readbackLabel(difference: ReadbackDifference): String = when (difference) {
    is ReadbackDifference.Mode -> stringResource(difference.reported.titleResource())
    is ReadbackDifference.Iso -> "ISO ${difference.reported}"
    is ReadbackDifference.ExposureTime -> shutter(difference.reportedNs)
    is ReadbackDifference.Banding -> stringResource(R.string.readback_antibanding, difference.reported?.displayLabel() ?: stringResource(R.string.pro_unknown))
    is ReadbackDifference.Metering -> stringResource(R.string.readback_metering, difference.reported?.displayLabel() ?: stringResource(R.string.pro_unknown))
    is ReadbackDifference.Temperature -> "${difference.reportedK} K"
    is ReadbackDifference.Optical -> stringResource(R.string.readback_optical, stabilizationLabel(difference.reported))
    is ReadbackDifference.Video -> stringResource(R.string.readback_video, stabilizationLabel(difference.reported))
    is ReadbackDifference.Noise -> stringResource(R.string.readback_noise, processingLabel(difference.reported))
    is ReadbackDifference.Edge -> stringResource(R.string.readback_edge, processingLabel(difference.reported))
    is ReadbackDifference.Saturation -> stringResource(R.string.readback_saturation, difference.reported)
    is ReadbackDifference.Sharpness -> stringResource(R.string.readback_sharpness, difference.reported)
}

@Composable
private fun stabilizationLabel(code: Int): String = when (code) {
    0 -> stringResource(R.string.image_mode_off)
    1 -> stringResource(R.string.readback_on)
    2 -> stringResource(R.string.image_mode_preview)
    else -> stringResource(R.string.pro_unknown)
}

@Composable
private fun processingLabel(code: Int): String = when (code) {
    0 -> stringResource(R.string.image_mode_off)
    1 -> stringResource(R.string.image_mode_fast)
    2 -> stringResource(R.string.image_mode_hq)
    else -> stringResource(R.string.pro_unknown)
}
