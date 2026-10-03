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
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ImageProcessingDefaults
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
    data class Temperature(val reportedK: Int) : ReadbackDifference
    data class Optical(val reported: Int) : ReadbackDifference
    data class Video(val reported: Int) : ReadbackDifference
    data class Noise(val reported: Int) : ReadbackDifference
    data class Edge(val reported: Int) : ReadbackDifference
}

/** Sensors round exposure to whole lines, so a report this close to the request is the same shutter. */
private const val TIME_TOLERANCE = 0.01
/** The reported colour temperature is an estimate; this close it matches the request. */
private const val TEMPERATURE_TOLERANCE_K = 100

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

private val OPTICAL_MODES = mapOf(0 to "OFF", 1 to "ON")
private val VIDEO_MODES = mapOf(0 to "OFF", 1 to "ON", 2 to "PREVIEW_STABILIZATION")
private val PROCESSING_MODES = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "MINIMAL", 4 to "ZERO_SHUTTER_LAG")
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
    return listOf(
        "android.control.aeMode" to readback(state.requestedExposureMode.name, state.reportedExposureMode?.name),
        "android.sensor.sensitivity" to readback(state.requestedIso?.toString() ?: "AUTO", state.sensitivityIso?.toString()),
        "android.sensor.exposureTime" to readback(time(state.requestedExposureTimeNs) ?: "AUTO", time(state.exposureTimeNs)),
        "android.control.aeAntibandingMode" to readback(state.effectiveSettings?.exposure?.antibanding?.name,
            antibandingOf(state.reportedAntibanding)?.name ?: state.reportedAntibanding?.let { "#$it" }),
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
        "android.scaler.cropRegion" to readback(null, state.reportedCropRegion?.joinToString(", ", "[", "]")),
    )
}

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
    is ReadbackDifference.Temperature -> "${difference.reportedK} K"
    is ReadbackDifference.Optical -> stringResource(R.string.readback_optical, stabilizationLabel(difference.reported))
    is ReadbackDifference.Video -> stringResource(R.string.readback_video, stabilizationLabel(difference.reported))
    is ReadbackDifference.Noise -> stringResource(R.string.readback_noise, processingLabel(difference.reported))
    is ReadbackDifference.Edge -> stringResource(R.string.readback_edge, processingLabel(difference.reported))
}

@Composable
private fun stabilizationLabel(code: Int): String = when (code) {
    0 -> stringResource(R.string.image_mode_off)
    1 -> stringResource(R.string.readback_on)
    2 -> stringResource(R.string.readback_preview_only)
    else -> stringResource(R.string.pro_unknown)
}

@Composable
private fun processingLabel(code: Int): String = when (code) {
    0 -> stringResource(R.string.image_mode_off)
    1 -> stringResource(R.string.image_mode_fast)
    2 -> stringResource(R.string.image_mode_hq)
    else -> stringResource(R.string.pro_unknown)
}
