/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.driver

import android.hardware.camera2.CameraMetadata
import android.os.SystemClock
import android.util.Range
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.CaptureScopeVisibility
import com.librestatic.opencinecam.OperatorAction
import com.librestatic.opencinecam.OperatorActions
import com.librestatic.opencinecam.OperatorUnavailableReason
import com.librestatic.opencinecam.operatorActionUnavailableReason
import com.librestatic.opencinecam.operatorActionToggleState
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2VideoProfile
import com.librestatic.opencinecam.camera.ExposureCapabilities
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.FalseColorBand
import com.librestatic.opencinecam.camera.MonitoringOptions
import com.librestatic.opencinecam.camera.MonitoringScopeFrame
import com.librestatic.opencinecam.camera.MonitoringSignalDomain
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/*
 * Fake camera and analysis for the capture chrome renders. None of it reaches the app: it lives in
 * the test source set and only feeds the real production composables.
 */

/** A rear camera with manual exposure, white balance in kelvin, manual focus, Razr-like video sizes and 12/8/5 MP stills. */
internal val DriverCamera = Camera2CameraDescriptor(
    cameraId = "0", lensFacing = CameraMetadata.LENS_FACING_BACK, focalLengthsMm = listOf(6.9f),
    previewSize = Size(1920, 1080), jpegSize = Size(4000, 3000), rawSize = Size(4000, 3000), analysisSize = Size(640, 360),
    sensorOrientation = 90, sensitivityRange = Range(50, 6400), exposureTimeRangeNs = Range(100_000L, 500_000_000L),
    aeCompensationRange = Range(-6, 6), aeCompensationStep = 1f / 3f, minimumFocusDistance = 10f,
    supportsRaw = true, flashAvailable = true,
    targetFpsRanges = listOf(Range(15, 30), Range(30, 30), Range(60, 60)), availableFixedFps = listOf(24, 25, 30, 60),
    // The Razr Fold back camera's sizes, so the RES panel shows every aspect group.
    videoProfiles = listOf(
        3840 to 2160, 4000 to 2250, 3376 to 1898, 3264 to 1836, 1920 to 1080,
        4000 to 3000, 3840 to 2880, 3264 to 2448, 4000 to 1714, 3840 to 1644, 3000 to 3000, 2880 to 2880, 2376 to 2160,
    ).flatMap { (w, h) ->
        listOf(24, 30, 60).map { Camera2VideoProfile(Size(w, h), it, constrainedHighSpeed = false) }
    },
    logProfiles = emptyList(),
    availableAfModes = listOf(CameraMetadata.CONTROL_AF_MODE_OFF, CameraMetadata.CONTROL_AF_MODE_AUTO,
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
    maxAfRegions = 1, maxAeRegions = 1, aeLockSupported = true, afLockSupported = true,
    kelvinRange = 2000..10000, tintSupported = true,
    availableAwbModes = setOf(CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CameraMetadata.CONTROL_AWB_MODE_SHADE),
    exposureCapabilities = ExposureCapabilities(manual = true, isoRange = 50..6400, timeRangeNs = 100_000L..500_000_000L,
        priorities = setOf(ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY)),
    awbLockSupported = true, aeCompensationStepNumerator = 1, aeCompensationStepDenominator = 3,
    jpegSizes = listOf(Size(4000, 3000), Size(4000, 2250), Size(3264, 2448), Size(2592, 1944), Size(1920, 1080)),
)

/** The fake camera previewing in [mode], with the readings a live preview reports. */
internal fun driverCaptureState(
    mode: CaptureMode = CaptureMode.PHOTO,
    phase: CameraUiPhase = CameraUiPhase.PREVIEWING,
): CameraUiState = CameraUiState(
    phase = phase, cameras = listOf(DriverCamera), selectedCameraId = DriverCamera.cameraId, selectedMode = mode,
    sensitivityIso = 400, exposureTimeNs = 10_000_000L, focusDistanceDiopters = 0.8f, reportedColorTemperatureK = 5200,
    aeLockSupported = true, afLockSupported = true, focusMarks = mapOf("A" to 0.5f, "B" to 2f),
    targetFps = if (mode == CaptureMode.PHOTO) 30 else 24, targetVideoWidth = 3840, targetVideoHeight = 2160,
    recordingWidth = 3840, recordingHeight = 2160, availableStorageBytes = 96_000_000_000L,
    zoomSupported = true, zoomMinRatio = 1f, zoomMaxRatio = 8f,
)

/** Settings with the waveform, vectorscope and false colour switched on, and the histogram. */
internal fun driverScopeSettings(): CameraSettings = CameraSettings(
    histogramEnabled = true,
    monitoring = MonitoringOptions(waveformEnabled = true, vectorscopeEnabled = true, falseColorEnabled = true),
)

/** The F-keys and quick toggles against [settings]: scope keys go through [scopes] like the app's. */
internal fun driverOperatorActions(
    state: CameraUiState,
    settings: CameraSettings,
    scopes: CaptureScopeVisibility,
    onSettings: (CameraSettings) -> Unit,
): OperatorActions = OperatorActions(
    capture = {},
    perform = { action ->
        scopes.press(action, operatorActionToggleState(action, settings, state) == true) {
            toggled(action, settings)?.let(onSettings)
        }
    },
    available = { driverReason(it, state) == null },
    latched = { scopes.latched(it, operatorActionToggleState(it, settings, state)) },
    scopes = scopes,
    reason = { driverReason(it, state) },
)

/** No fold coordinator and no presets in the driver, so those keys read as unavailable. */
private fun driverReason(action: OperatorAction, state: CameraUiState): OperatorUnavailableReason? =
    operatorActionUnavailableReason(action, state) ?: when (action) {
        OperatorAction.EXTERIOR -> OperatorUnavailableReason.NO_EXTERIOR
        OperatorAction.PRESET_C1, OperatorAction.PRESET_C2 -> OperatorUnavailableReason.NO_PRESET
        else -> null
    }

/** What the capture service writes to the settings for a toggle action. */
private fun toggled(action: OperatorAction, s: CameraSettings): CameraSettings? = when (action) {
    OperatorAction.TORCH -> s.copy(flashEnabled = !s.flashEnabled)
    OperatorAction.PEAKING -> s.copy(peakingEnabled = !s.peakingEnabled)
    OperatorAction.ZEBRA -> s.copy(zebraEnabled = !s.zebraEnabled)
    OperatorAction.HISTOGRAM -> s.copy(histogramEnabled = !s.histogramEnabled)
    OperatorAction.WAVEFORM -> s.copy(monitoring = s.monitoring.copy(waveformEnabled = !s.monitoring.waveformEnabled))
    OperatorAction.VECTORSCOPE -> s.copy(monitoring = s.monitoring.copy(vectorscopeEnabled = !s.monitoring.vectorscopeEnabled))
    OperatorAction.FALSE_COLOR -> s.copy(monitoring = s.monitoring.copy(falseColorEnabled = !s.monitoring.falseColorEnabled))
    OperatorAction.VIEW_ASSIST -> s.copy(logViewAssistEnabled = !s.logViewAssistEnabled)
    else -> null
}

/**
 * [state] with live analysis of a stand-in scene: histograms and scopes for [options], stamped
 * fresh every quarter second the way the engine reports them, so the scopes read as live.
 */
@Composable
internal fun withDriverAnalysis(state: CameraUiState, options: MonitoringOptions): CameraUiState {
    var stamp by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            stamp = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val analysis = remember(options) { DriverAnalysis(options) }
    return state.copy(
        histogram = analysis.luma, redHistogram = analysis.red, greenHistogram = analysis.green, blueHistogram = analysis.blue,
        monitoringScopes = analysis.frame, analysisUpdatedAtMs = stamp, analysisIntervalMs = options.periodMs,
    )
}

/** A 64 × 36 sample scene: a bright sky, a skin-toned subject in the middle and darker foliage below. */
private class DriverAnalysis(options: MonitoringOptions) {
    val luma: List<Float>
    val red: List<Float>
    val green: List<Float>
    val blue: List<Float>
    val frame: MonitoringScopeFrame

    init {
        val width = MonitoringScopeFrame.FALSE_COLOR_WIDTH
        val height = MonitoringScopeFrame.FALSE_COLOR_HEIGHT
        val grid = MonitoringScopeFrame.GRID_SIZE
        val waveform = IntArray(grid * grid)
        val vectorscope = IntArray(grid * grid)
        val bands = ArrayList<FalseColorBand>(width * height)
        val bins = 64
        val histograms = Array(4) { IntArray(bins) }
        fun bin(value: Float) = (value.coerceIn(0f, 1f) * (bins - 1)).roundToInt()
        fun cell(value: Float) = (value.coerceIn(0f, 1f) * (grid - 1)).roundToInt()
        for (y in 0 until height) for (x in 0 until width) {
            val u = x / (width - 1f)
            val v = y / (height - 1f)
            val subject = u in 0.36f..0.64f && v > 0.3f
            val (l, cb, cr) = when {
                subject -> Triple(.52f + .12f * sin(v * 7f + u * 3f), -.07f + .02f * sin(u * 11f), .11f + .03f * sin(v * 5f))
                v < .38f -> Triple(.9f - .35f * v + .06f * sin(u * 6f), .16f - .12f * v, -.06f + .02f * sin(u * 4f))
                else -> Triple(.12f + .22f * (1f - v) + .08f * sin(u * 13f + v * 9f), -.09f + .03f * sin(u * 5f), -.07f + .04f * sin(u * 7f))
            }
            val y709 = l.coerceIn(0f, 1f)
            waveform[cell(1f - y709) * grid + x] += 1
            vectorscope[cell(.5f - cr) * grid + cell(cb + .5f)] += 1
            bands += when {
                y709 * 100 < options.falseColorBlackPercent -> FalseColorBand.BLACK
                y709 * 100 < options.falseColorShadowPercent -> FalseColorBand.SHADOW
                y709 * 100 < options.falseColorHighlightPercent -> FalseColorBand.MID
                y709 * 100 < options.falseColorClipPercent -> FalseColorBand.HIGHLIGHT
                else -> FalseColorBand.CLIP
            }
            histograms[0][bin(y709)]++
            histograms[1][bin(y709 + 1.5748f * cr)]++
            histograms[2][bin(y709 - .1873f * cb - .4681f * cr)]++
            histograms[3][bin(y709 + 1.8556f * cb)]++
        }
        val total = (width * height).toFloat()
        val normalized = histograms.map { counts -> counts.map { it / total } }
        luma = normalized[0]; red = normalized[1]; green = normalized[2]; blue = normalized[3]
        frame = MonitoringScopeFrame(waveform.toList(), vectorscope.toList(), bands, width * height,
            MonitoringSignalDomain.SDR_BT709_CODE, width, height, options)
    }
}
