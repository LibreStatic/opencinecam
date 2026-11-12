/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.driver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.librestatic.opencinecam.export.CinemaDngDestinationChoice
import com.librestatic.opencinecam.export.CinemaDngExportControl
import com.librestatic.opencinecam.export.CinemaDngExportForm
import com.librestatic.opencinecam.export.CinemaDngExportFormState
import com.librestatic.opencinecam.export.CinemaDngExportOutcome
import com.librestatic.opencinecam.export.CinemaDngExportPlan
import com.librestatic.opencinecam.export.CinemaDngExportProgress
import com.librestatic.opencinecam.export.CinemaDngExportState
import com.librestatic.opencinecam.export.CinemaDngExporter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.librestatic.opencinecam.normalizedFor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.librestatic.opencinecam.DisplayCapability
import com.librestatic.opencinecam.FoldDisplaySettings
import com.librestatic.opencinecam.FoldDisplayState
import com.librestatic.opencinecam.FoldPosture
import com.librestatic.opencinecam.LocalFoldDisplayStateWithoutCoordinator
import com.librestatic.opencinecam.SelfCaptureChrome
import com.librestatic.opencinecam.SubjectDisplayMode
import com.librestatic.opencinecam.SubjectDisplayScreen
import com.librestatic.opencinecam.SubjectDisplaySettings
import com.librestatic.opencinecam.SubjectSessionCues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.AboutScreen
import com.librestatic.opencinecam.CameraCapabilitiesScreen
import com.librestatic.opencinecam.StorageBenchmarkContent
import com.librestatic.opencinecam.LensCalibrationContent
import com.librestatic.opencinecam.LensCalibrationFailure
import com.librestatic.opencinecam.LensCalibrationLensRow
import com.librestatic.opencinecam.LensCalibrationLensStatus
import com.librestatic.opencinecam.LensCalibrationPhase
import com.librestatic.opencinecam.LensCalibrationUiState
import com.librestatic.opencinecam.core.model.CalibrationFlag
import com.librestatic.opencinecam.camera.CameraInventory
import com.librestatic.opencinecam.camera.CharacteristicEntry
import com.librestatic.opencinecam.core.model.BenchmarkResultDocument
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.core.model.StorageBenchmarkOutcome
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.librestatic.opencinecam.AdaptiveCaptureChrome
import com.librestatic.opencinecam.AdaptiveWindow
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.OperatorAction
import com.librestatic.opencinecam.OperatorPreferences
import com.librestatic.opencinecam.VolumeKeyPair
import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.OnboardingRawStage
import com.librestatic.opencinecam.RawQualificationConditions
import com.librestatic.opencinecam.RawQualificationFrame
import com.librestatic.opencinecam.RawQualificationItem
import com.librestatic.opencinecam.RawQualificationItemStatus
import com.librestatic.opencinecam.RawQualificationOutcomeKind
import com.librestatic.opencinecam.RawQualificationStep
import com.librestatic.opencinecam.RawVideoConfigOption
import com.librestatic.opencinecam.rawQualificationPlanItems
import com.librestatic.opencinecam.RawVideoQualificationInfo
import com.librestatic.opencinecam.RawVideoQualificationUi
import com.librestatic.opencinecam.rawvideo.RawQualificationEstimate
import com.librestatic.opencinecam.rawvideo.RawQualificationPhase
import com.librestatic.opencinecam.rawvideo.RawQualificationPlannedConfig
import com.librestatic.opencinecam.rawvideo.RawQualificationSpecs
import com.librestatic.opencinecam.rawvideo.RawQualificationSpaceEstimate
import com.librestatic.opencinecam.rawvideo.RawVideoQualifier
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureInitialPane
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.CaptureScopeVisibility
import com.librestatic.opencinecam.CodecBadge
import com.librestatic.opencinecam.CompositionGridMode
import com.librestatic.opencinecam.GalleryFacts
import com.librestatic.opencinecam.rawCodecBadge
import com.librestatic.opencinecam.TakeDetailInputs
import com.librestatic.opencinecam.TakeTechnicalDetails
import com.librestatic.opencinecam.buildTakeDetails
import com.librestatic.opencinecam.detailText
import org.robolectric.RuntimeEnvironment
import com.librestatic.opencinecam.GallerySettings
import com.librestatic.opencinecam.HistogramMode
import com.librestatic.opencinecam.HorizonRollMath
import com.librestatic.opencinecam.LocalAdaptiveWindow
import com.librestatic.opencinecam.LocalOperatorActions
import com.librestatic.opencinecam.MediaCatalogContent
import com.librestatic.opencinecam.MediaCatalogSource
import com.librestatic.opencinecam.MonitoringOverlay
import com.librestatic.opencinecam.OnboardingScreen
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.SettingsScreen
import com.librestatic.opencinecam.SplashHandoff
import com.librestatic.opencinecam.TakeProxyState
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.MonitoringOptions
import com.librestatic.opencinecam.camera.MonitoringSignalDomain
import com.librestatic.opencinecam.camera.detectFocusEdges
import com.librestatic.opencinecam.camera.monitoringDisplayPoint
import com.librestatic.opencinecam.camera.outputFrameInStream
import com.librestatic.opencinecam.drawFocusPeaking
import com.librestatic.opencinecam.focusPeakingPlacement
import com.librestatic.opencinecam.previewDisplayRatio
import com.librestatic.opencinecam.storage.LocalMediaArtifact
import com.librestatic.opencinecam.storage.LocalMediaCursor
import com.librestatic.opencinecam.storage.LocalMediaEncoding
import com.librestatic.opencinecam.storage.LocalMediaKind
import com.librestatic.opencinecam.storage.LocalMediaPage
import com.librestatic.opencinecam.storage.LocalMediaRelationStatus
import com.librestatic.opencinecam.storage.LocalMediaTake
import com.librestatic.opencinecam.storage.rawVideoTake
import com.librestatic.opencinecam.ui.theme.AppTheme
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import com.librestatic.opencinecam.ui.theme.OpenCineCamTheme
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import com.librestatic.opencinecam.MediaZoomState
import com.librestatic.opencinecam.PlaybackObservation
import com.librestatic.opencinecam.PlaybackPhase
import com.librestatic.opencinecam.PlaybackStageArea

/*
 * Zero-argument Compose Driver entry points (AGENTS.md). Each one feeds fake state to the real
 * production composable; none of this is on the app's runtime classpath. Select one with
 * `tools/compose-driver.sh start <Name>`, which expands to
 * `com.librestatic.opencinecam.driver.DriverScreensKt.<Name>`.
 *
 * The theme follows `--theme cine|you` (default cine); `--device` and `--night` set the viewport.
 */

/*
 * Capture chrome: every screen runs a fake camera (manual exposure, kelvin white balance, manual
 * focus) over a black stand-in for the viewfinder, and picks its layout from the device it is
 * started on: phone (compact portrait), landscape (side rails), inner or tablet (stacked) and a
 * 1280 × 800 dp window (the inspector). The F-keys and scope keys act on the screen's own settings.
 */

/** Capture chrome, photo mode, previewing, with the histogram. */
@Composable
fun CapturePortrait() = CaptureChrome(driverCaptureState())

/** A back camera that offers the OEM night extension, so the Night quick toggle can exist. */
private fun driverNightState(dark: Boolean, engaged: Boolean): CameraUiState {
    val night = DriverCamera.copy(nightExtension = com.librestatic.opencinecam.camera.NightExtensionCapability(listOf(1920 to 1080), listOf(4000 to 3000)))
    return driverCaptureState().copy(
        cameras = listOf(night), nightSuggested = dark, nightTransientEngaged = engaged, nightExtensionRunning = engaged,
        // A dim room: the metering asked for ISO 6400 at 1/30 s.
        sensitivityIso = if (dark) 6400 else 400, exposureTimeNs = if (dark) 33_333_333L else 10_000_000L,
    )
}

/** Photo mode in Auto, the scene dark and Night not yet on: the quick toggle is offered beside the lock keys. */
@Composable
fun CaptureNightSuggested() = CaptureChrome(driverNightState(dark = true, engaged = false))

/** Night engaged from the quick toggle: highlighted, and the way back out. */
@Composable
fun CaptureNightOn() = CaptureChrome(driverNightState(dark = true, engaged = true))

/** A back camera whose HAL honours exposure-time priority (Android 16), so the FPS LOCK key can exist. */
private fun driverFpsLockState(holding: Boolean): CameraUiState {
    val camera = DriverCamera.copy(exposureCapabilities = DriverCamera.exposureCapabilities.copy(
        priorities = setOf(com.librestatic.opencinecam.camera.ExposureMode.ISO_PRIORITY, com.librestatic.opencinecam.camera.ExposureMode.SHUTTER_PRIORITY)))
    return driverCaptureState(CaptureMode.VIDEO).copy(cameras = listOf(camera), fpsLockHolding = holding)
}

/** Video with FPS LOCK on and the frame rate healthy: the key (`capture-fps-lock`) sits beside AE/AF. */
@Composable
fun CaptureFpsLock() = CaptureChrome(driverFpsLockState(holding = false))

/** Video with FPS LOCK holding the shutter at 1/fps in the dark: the key is amber. */
@Composable
fun CaptureFpsLockHolding() = CaptureChrome(driverFpsLockState(holding = true))

/** The fake camera with 1080p constrained high-speed profiles at 120 and 240 fps, so slow motion can run. */
private fun driverSlowMotionState(phase: CameraUiPhase = CameraUiPhase.PREVIEWING): CameraUiState {
    val camera = DriverCamera.copy(videoProfiles = DriverCamera.videoProfiles + listOf(120, 240).map {
        com.librestatic.opencinecam.camera.Camera2VideoProfile(Size(1920, 1080), it, constrainedHighSpeed = true)
    })
    return driverCaptureState(CaptureMode.VIDEO, phase).copy(cameras = listOf(camera), targetFps = 240,
        targetVideoWidth = 1920, targetVideoHeight = 1080, recordingWidth = 1920, recordingHeight = 1080)
}

/** Slow motion at 240 fps on a 30 fps project, bounded to 10 s of capture. */
private val DriverSlowMotionSettings = CameraSettings(histogramEnabled = true, videoOffSpeed = true, videoWidth = 1920,
    videoHeight = 1080, videoFps = 240, slowMotionTakeLengthSeconds = 10)

/** Slow motion previewing: the strip reads RES · FPS · LENGTH (10 s) · SHUTTER · ISO · WB. */
@Composable
fun CaptureSlowMotion() = CaptureChrome(driverSlowMotionState(), DriverSlowMotionSettings)

/** The take length panel (`take-length-panel`) open; the slider sets the screen's own settings. */
@Composable
fun CaptureSlowMotionLength() = CaptureChrome(driverSlowMotionState(), DriverSlowMotionSettings, CaptureInitialPane.TAKE_LENGTH)

/** A bounded slow-motion take at 00:07 of 00:10. */
@Composable
fun CaptureSlowMotionRecording() = CaptureChrome(
    driverSlowMotionState(CameraUiPhase.RECORDING).copy(recordingElapsedMs = 7_000L, takeLengthLimitMs = 10_000L),
    DriverSlowMotionSettings,
)

/** The Razr Fold's three lenses as lock options: ultra-wide "3", wide "2" and tele "5" (anchors 0.55, 1 and 3). */
private val DriverLensLockOptions = listOf(
    com.librestatic.opencinecam.LensLockOption("3", 0.55f, 2.2f),
    com.librestatic.opencinecam.LensLockOption("2", 1.0f, 6.9f),
    com.librestatic.opencinecam.LensLockOption("5", 3.0f, 20.6f),
)

private fun driverLensLockState(
    lockedId: String?,
    route: com.librestatic.opencinecam.LensLockRoute? = null,
    switched: Boolean = false,
): CameraUiState {
    val lock = com.librestatic.opencinecam.LensLockUiState(DriverLensLockOptions, lockedId, route, switched)
    val base = driverCaptureState(CaptureMode.VIDEO)
    val anchors = DriverLensLockOptions.map { com.librestatic.opencinecam.camera.ZoomAnchor(it.anchorRatio, it.focalLengthMm, it.physicalId) }
    // Locked to the tele: its 1-8x digital range times its 3x anchor; unlocked: the logical 0.55-24x scale at 1x.
    return if (lockedId != null) base.copy(lensLock = lock, opticalAnchors = anchors, zoomMinRatio = 3f, zoomMaxRatio = 24f, zoomRatio = 4.5f,
        hardwareTruth = base.hardwareTruth.withActiveLens(lockedId))
    else base.copy(lensLock = lock, opticalAnchors = anchors, zoomMinRatio = 0.55f, zoomMaxRatio = 24f, zoomRatio = 1f,
        hardwareTruth = base.hardwareTruth.withActiveLens("2"))
}

/** Video locked to the tele ("5", served directly): the anchor bar is the lock bar and LENS LOCK is on. */
@Composable
fun CaptureLensLocked() = CaptureChrome(driverLensLockState("5", com.librestatic.opencinecam.LensLockRoute.DIRECT_PHYSICAL))

/** Video with the lens lock offered but off: the LENS LOCK key beside the lock keys, the normal anchors. */
@Composable
fun CaptureLensLockAvailable() = CaptureChrome(driverLensLockState(null))

/** Locked to the tele on the fallback route: the "Lens lock: digital crop" notice under the zoom. */
@Composable
fun CaptureLensLockCrop() = CaptureChrome(driverLensLockState("5", com.librestatic.opencinecam.LensLockRoute.GPU_CROP))

/** RAW photo on a camera that rejected the full graph: RAW and the analysis stream dropped, the notice under the zoom. */
@Composable
fun CaptureGraphFallback() = CaptureChrome(
    driverCaptureState(CaptureMode.RAW_PHOTO).copy(previewGraphLoss = com.librestatic.opencinecam.camera.PreviewGraphLoss(analysis = true, raw = true)),
)

/** A take that asked for HEVC records H.264: the fallback is the recording warning. */
@Composable
fun CaptureCodecFallback() = CaptureChrome(
    driverCaptureState(CaptureMode.VIDEO, CameraUiPhase.RECORDING).copy(recordingElapsedMs = 83_000L,
        recordingWarning = stringResource(R.string.video_codec_fallback_warning)),
)

/** Capture chrome while a video take is recording at 01:23. */
@Composable
fun CaptureRecording() = CaptureChrome(
    driverCaptureState(CaptureMode.VIDEO, CameraUiPhase.RECORDING).copy(recordingElapsedMs = 83_000L),
)

/** Recording under the Adaptive policy with a live warning: it must show in every layout while the take runs. */
@Composable
fun CaptureRecordingWarning() = CaptureChrome(
    driverCaptureState(CaptureMode.VIDEO, CameraUiPhase.RECORDING).copy(recordingElapsedMs = 83_000L,
        recordingWarning = "Storage is running low · Audio is drifting from the picture"),
)

/** Recording through a confirmed USB lavalier: the meter shows a USB badge. */
@Composable
fun CaptureRecordingUsb() = CaptureChrome(
    driverCaptureState(CaptureMode.VIDEO, CameraUiPhase.RECORDING).copy(recordingElapsedMs = 83_000L,
        activeAudioInput = driverAudioInput(confirmed = true)),
)

/** The lavalier was unplugged mid-take and the take fell back to the built-in microphone: amber INT. */
@Composable
fun CaptureRecordingFallback() = CaptureChrome(
    driverCaptureState(CaptureMode.VIDEO, CameraUiPhase.RECORDING).copy(recordingElapsedMs = 83_000L,
        activeAudioInput = com.librestatic.opencinecam.media.audio.ActiveAudioInput(7, null, "", 15, confirmed = false, fallback = true)),
)

private fun driverAudioInput(confirmed: Boolean) = com.librestatic.opencinecam.media.audio.ActiveAudioInput(
    deviceId = 42, key = com.librestatic.opencinecam.media.audio.AudioInputKey(22, "USB-C Lavalier", "card=2;device=0"),
    label = "USB-C Lavalier", type = 22, confirmed = confirmed,
)

/** Capture chrome in video mode; start with `--device landscape` for the side rails. */
@Composable
fun CaptureLandscape() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO))

// RAW video (OCC-PLAN-071)

/** An S24 Ultra-like main camera's qualified RAW10 configurations: the 4080×3060 sensor at 1.25 B/px, and two centre crops. */
private val DriverRawVideoOptions: List<com.librestatic.opencinecam.RawVideoConfigOption> = buildList {
    fun option(width: Int, height: Int, fps: Int, crop: Boolean) = com.librestatic.opencinecam.RawVideoConfigOption(
        key = (if (crop) "${width}x$height" else "full") + "@$fps", label = "$width×$height · $fps fps",
        bytesPerSecond = width.toLong() * height * 5 / 4 * fps, isCrop = crop)
    listOf(15, 24, 30).forEach { add(option(4080, 3060, it, crop = false)) }
    listOf(30, 60).forEach { add(option(3840, 2160, it, crop = true)) }
    listOf(30, 60).forEach { add(option(1920, 1080, it, crop = true)) }
}

/**
 * [DriverRawVideoOptions] with block-pack twins of 4080×3060 · 30 fps and 3840×2160 · 30 fps, each right
 * after its uncompressed configuration (the gate's order); they read "· Near-lossless" while near-lossless is on.
 */
private val DriverRawVideoOptionsCompressed: List<com.librestatic.opencinecam.RawVideoConfigOption> = DriverRawVideoOptions.flatMap { option ->
    val twin = when (option.key) {
        "full@30" -> com.librestatic.opencinecam.camera.RawVideoConfig("0", 4080, 3060, 4080, 3060, 30, "bp")
        "3840x2160@30" -> com.librestatic.opencinecam.camera.RawVideoConfig("0", 4080, 3060, 3840, 2160, 30, "bp")
        else -> null
    }
    listOfNotNull(option, twin?.let { com.librestatic.opencinecam.RawVideoConfigOption.of(it) })
}

/**
 * RAW video qualified on this camera, running full sensor at 30 fps (picked, not Automatic). With
 * [compressed] the block-pack twins are offered too and the camera has a noise profile, so they can
 * record near-lossless.
 */
private fun driverRawVideoState(phase: CameraUiPhase = CameraUiPhase.PREVIEWING, compressed: Boolean = false): CameraUiState =
    driverCaptureState(CaptureMode.RAW_VIDEO, phase).let {
        it.copy(modeGates = it.modeGates + (CaptureMode.RAW_VIDEO to ModeGateState.AVAILABLE),
            rawVideoOptions = if (compressed) DriverRawVideoOptionsCompressed else DriverRawVideoOptions,
            rawVideoNearLosslessAvailable = compressed,
            rawVideoSelectedKey = "full@30", rawVideoAutoKey = "full@30",
            targetFps = 30, targetVideoWidth = 4080, targetVideoHeight = 3060, recordingWidth = 4080, recordingHeight = 3060)
    }

private val DriverRawVideoSettings = CameraSettings(histogramEnabled = true, rawVideoConfig = "full@30")

/** RAW video previewing: RES and FPS read the configuration, the status line its rate and the time left. */
@Composable
fun CaptureRawVideo() = CaptureChrome(driverRawVideoState(), DriverRawVideoSettings)

/**
 * The same with every status fact: timecode, free space, time left and a 72 % battery (a
 * Robolectric shadow; the device reports none headless). On a narrow phone the status line splits
 * into two rows (`capture-status-line-top` / `-bottom`) instead of ending in an ellipsis.
 */
@Composable
fun CaptureRawVideoStatus(): Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    remember {
        val battery = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
        org.robolectric.Shadows.shadowOf(battery).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 72)
    }
    CaptureChrome(driverRawVideoState().copy(timecodeDisplay = "01:02:03:04"), DriverRawVideoSettings)
}

/**
 * The RAW configuration chooser open (what RES or FPS opens in RAW video): Automatic, full sensor, crops,
 * with the block-pack twins of 4080×3060 and 3840×2160 at 30 fps labelled "· Near-lossless".
 */
@Composable
fun CaptureRawVideoConfig() = CaptureChrome(driverRawVideoState(compressed = true), DriverRawVideoSettings, CaptureInitialPane.RESOLUTION)

/**
 * RAW video with 4080×3060 at 60 fps qualified as risky and chosen by hand: the chooser open, its
 * tile carrying the warning and the risk hint (`raw-video-config-risk-hint`), and the risky-rate
 * notice (`raw-video-risky-notice`) under the zoom, with the Razr-like burst estimate (75 s: "about 1 min").
 */
@Composable
fun CaptureRawVideoRisky() = CaptureChrome(
    driverRawVideoState().let { state ->
        val full60 = com.librestatic.opencinecam.RawVideoConfigOption("full@60", "4080×3060 · 60 fps", 936_360_000L, isCrop = false, isRisky = true,
            burstSeconds = 75L)
        state.copy(rawVideoOptions = state.rawVideoOptions.take(3) + full60 + state.rawVideoOptions.drop(3),
            rawVideoSelectedKey = "full@60", targetFps = 60)
    },
    DriverRawVideoSettings.copy(rawVideoConfig = "full@60"),
    CaptureInitialPane.RESOLUTION,
)

/**
 * Reduced-rate configurations in the chooser (PLAN-071): 4080×3060 at 60 fps risky and reaching
 * 51.9 fps, 3840×2160 at 60 fps qualified but reaching 49.1 fps. Their tiles lead with "Reaches … fps";
 * Automatic still resolves to 4080×3060 at 30 fps, which reaches its target.
 */
@Composable
fun CaptureRawVideoReducedRate() = CaptureChrome(
    driverRawVideoState().let { state ->
        val full60 = com.librestatic.opencinecam.RawVideoConfigOption("full@60", "4080×3060 · 60 fps", 936_360_000L, isCrop = false, isRisky = true,
            burstSeconds = 75L, achievedFps = 51.9)
        val options = (state.rawVideoOptions.take(3) + full60 + state.rawVideoOptions.drop(3))
            .map { if (it.key == "3840x2160@60") it.copy(achievedFps = 49.1) else it }
        state.copy(rawVideoOptions = options, rawVideoSelectedKey = "full@30")
    },
    DriverRawVideoSettings.copy(rawVideoConfig = ""),
    CaptureInitialPane.RESOLUTION,
)

/** A RAW take at 00:42: the ring meter beside the REC timer, 37 % full and 1260 frames written. */
@Composable
fun CaptureRawVideoRecording() = CaptureChrome(
    driverRawVideoState(CameraUiPhase.RECORDING).copy(recordingElapsedMs = 42_000L, rawVideoRingPercent = 37, rawVideoFramesWritten = 1_260L),
    DriverRawVideoSettings,
)

/** The same take with the ring at 84 %: the meter turns red (amber from 50 %). */
@Composable
fun CaptureRawVideoRingHigh() = CaptureChrome(
    driverRawVideoState(CameraUiPhase.RECORDING).copy(recordingElapsedMs = 42_000L, rawVideoRingPercent = 84, rawVideoFramesWritten = 1_260L),
    DriverRawVideoSettings,
)

private fun driverRawCandidateState(): CameraUiState =
    driverCaptureState(CaptureMode.VIDEO).let { it.copy(modeGates = it.modeGates + (CaptureMode.RAW_VIDEO to ModeGateState.CANDIDATE)) }

/**
 * The default plan on a 4080×3060 sensor with what each run needs: with 26 GB free the two 60 fps
 * configurations that need 54 and 81 GB (80 s runs) are skipped, the other six (40 s runs) are tested.
 */
private val DriverRawPlanNeeds = listOf(
    "full@15" to 11_600_000_000L, "full@24" to 18_000_000_000L, "full@30" to 22_100_000_000L, "full@60" to 80_700_000_000L,
    "3840x2160@30" to 15_100_000_000L, "3840x2160@60" to 53_900_000_000L, "1920x1080@30" to 4_600_000_000L, "1920x1080@60" to 8_100_000_000L,
)

private fun driverRawPlannedConfigs(freeBytes: Long): List<RawQualificationPlannedConfig> = DriverRawPlanNeeds.map { (spec, needed) ->
    val run = requireNotNull(RawQualificationSpecs.parse(spec, "0", 4080, 3060))
    RawQualificationPlannedConfig(spec, run.config, needed, fits = needed <= freeBytes)
}

/** A qualification estimate: about 12 min, [freeBytes] free, the configurations that do not fit skipped for space. */
private fun driverRawQualificationInfo(
    freeBytes: Long = 26_000_000_000L,
    minimumFreeBytes: Long = 3L shl 30,
    previousFailure: Boolean = false,
): RawVideoQualificationInfo {
    val configs = driverRawPlannedConfigs(freeBytes)
    return RawVideoQualificationInfo(
        cameraId = "0",
        gate = ModeGateState.CANDIDATE,
        space = RawQualificationSpaceEstimate(
            estimate = RawQualificationEstimate(durationMs = 12 * 60_000L + 10_000L, freeBytesNeeded = 22_100_000_000L,
                sweepFreeBytesNeeded = 3L shl 30, runCount = 15),
            freeBytes = freeBytes,
            skippedRuns = configs.count { !it.fits },
            minimumFreeBytes = minimumFreeBytes,
            configs = configs,
            fullPlanFreeBytes = 80_700_000_000L,
        ),
        previousFailure = previousFailure,
    )
}

/** RAW10 present but nothing qualified yet: the modes pane lists RAW video as choosable, with its gate label. */
@Composable
fun CaptureRawVideoCandidate() = CaptureChrome(driverRawCandidateState(), initialPane = CaptureInitialPane.MODES,
    rawQualificationInfo = { driverRawQualificationInfo() })

/** The phone's mode sheet with the RAW video candidate choosable. */
@Composable
fun CaptureRawVideoCandidateSheet() = CaptureChrome(driverRawCandidateState(), initialPane = CaptureInitialPane.MODE_SHEET,
    rawQualificationInfo = { driverRawQualificationInfo() })

/** RAW video chosen on a candidate: the prompt (a dialog, so use `tree`), with a partial-space line. */
@Composable
fun CaptureRawVideoQualifyPrompt() = CaptureChrome(driverRawCandidateState(), initialPane = CaptureInitialPane.MODES,
    rawQualificationInfo = { driverRawQualificationInfo(freeBytes = 9_000_000_000L) }, initialRawQualifyPrompt = true)

/** The prompt without enough free space for even the sweep: only a dismiss button. */
@Composable
fun CaptureRawVideoQualifyNoSpace() = CaptureChrome(driverRawCandidateState(), initialPane = CaptureInitialPane.MODES,
    rawQualificationInfo = { driverRawQualificationInfo(freeBytes = 1_000_000_000L) }, initialRawQualifyPrompt = true)

/** The onboarding's last question: qualify RAW video now or later. */
@Composable
fun OnboardingRawQuestion() = DriverTheme {
    CompositionLocalProvider(LocalReducedMotion provides true) {
        OnboardingScreen(splash = SplashHandoff(onScreen = false), onPermissionsChanged = {}, onFinished = {},
            rawQualificationStep = { _ -> }, initialRawStage = OnboardingRawStage.QUESTION, initialRawInfo = driverRawQualificationInfo())
    }
}

/** The qualification step as the mode selector opens it, over the app in the wizard's look. */
@Composable
private fun RawQualificationDriver(
    conditions: RawQualificationConditions,
    state: RawVideoQualificationUi?,
    info: RawVideoQualificationInfo = driverRawQualificationInfo(),
) = DriverTheme {
    CompositionLocalProvider(LocalReducedMotion provides true) {
        var shown by remember { mutableStateOf(state) }
        RawQualificationFrame(hinge = null) {
            RawQualificationStep(
                info = info,
                conditions = conditions,
                state = shown,
                onStart = { shown = RawVideoQualificationUi(phase = RawQualificationPhase.RING, remainingMs = 12 * 60_000L) },
                onCancel = { shown = RawVideoQualificationUi(outcome = RawQualificationOutcomeKind.CANCELLED, reason = it) },
                onLeave = { shown = null },
            )
        }
    }
}

private val DriverRawReady = RawQualificationConditions(batteryPercent = 82, thermalStatus = 0)

/** The preconditions all pass with 26 GB free: Start is enabled and two 60 fps configurations are skipped for space. */
@Composable
fun RawQualificationReady() = RawQualificationDriver(DriverRawReady, null)

/** The preconditions all pass with room for every configuration: only a compact free-space line. */
@Composable
fun RawQualificationReadyAllFit() = RawQualificationDriver(DriverRawReady, null, driverRawQualificationInfo(freeBytes = 128_000_000_000L))

/** Battery at 3 % and moderately warm: each check says what to do and Start is disabled. */
@Composable
fun RawQualificationBlocked() = RawQualificationDriver(RawQualificationConditions(batteryPercent = 3, thermalStatus = 2), null)

/** Battery at 35 % and cool: the battery row warns that the run may drain it, and Start stays enabled. */
@Composable
fun RawQualificationBatteryLow() = RawQualificationDriver(RawQualificationConditions(batteryPercent = 35, thermalStatus = 0), null)

/** The plan's items with [statuses] set by spec; the rest pending, or skipped when they do not fit. */
private fun driverRawItems(vararg statuses: Pair<String, RawQualificationItemStatus>): List<RawQualificationItem> =
    rawQualificationPlanItems(driverRawQualificationInfo().space).map { item ->
        statuses.firstOrNull { it.first == item.spec }?.let { item.copy(status = it.second) } ?: item
    }

/** The camera's rate each fitting configuration's check measured, as the driver's screens show it. */
private val DriverRawChecked = linkedMapOf(
    "full@30" to 29.9, "full@24" to 24.0, "3840x2160@30" to 30.0, "full@15" to 15.0, "1920x1080@60" to 59.8, "1920x1080@30" to 30.0,
)

/** The plan's items with the camera checks of [checked] done (MEASURED, with their rates), then [more] statuses. */
private fun driverRawCheckedItems(checked: Collection<String>, vararg more: Pair<String, RawQualificationItemStatus>): List<RawQualificationItem> =
    driverRawItems(*(checked.map { it to RawQualificationItemStatus.MEASURED } + more).toTypedArray())
        .map { item -> DriverRawChecked[item.spec]?.takeIf { item.spec in checked }?.let { item.copy(achievedFps = it) } ?: item }

/**
 * Camera check 4 of 15, heaviest first while the phone is cool: the two 60 fps configurations that do
 * not fit stay "No space", 4080×3060 · 30 fps is measured, 24 fps is being checked at 23.4 fps live.
 */
@Composable
fun RawQualificationRunning() = RawQualificationDriver(DriverRawReady, RawVideoQualificationUi(
    phase = RawQualificationPhase.CAMERA_CHECK, fraction = 0.09f, runIndex = 4, runCount = 15, specLabel = "4080×3060 · 24 fps",
    remainingMs = 11 * 60_000L,
    items = driverRawCheckedItems(listOf("full@30"), "full@24" to RawQualificationItemStatus.TESTING)
        .map { if (it.spec == "full@24") it.copy(liveFps = 23.4) else it },
))

/** Camera check 7 of 15 on 1920×1080 · 60 fps, the camera delivering 49.8 fps (under the tolerance: amber). */
@Composable
fun RawQualificationRunningSlow() = RawQualificationDriver(DriverRawReady, run {
    val items = driverRawCheckedItems(listOf("full@30", "full@24", "3840x2160@30", "full@15"),
        "1920x1080@60" to RawQualificationItemStatus.TESTING)
        .map { if (it.spec == "1920x1080@60") it.copy(liveFps = 49.8) else it }
    RawVideoQualificationUi(
        phase = RawQualificationPhase.CAMERA_CHECK, fraction = 0.15f, runIndex = 7, runCount = 15,
        specLabel = items.first { it.spec == "1920x1080@60" }.label, remainingMs = 10 * 60_000L, items = items,
    )
})

/**
 * The compression benchmark, step 9 of 15, after every camera check: it streams 4080×3060 · 30 fps,
 * the heaviest the camera delivered, at 29.7 fps; that row leads, highlighted and still Measured.
 */
@Composable
fun RawQualificationRunningBench() = RawQualificationDriver(DriverRawReady, run {
    val items = driverRawCheckedItems(DriverRawChecked.keys)
        .map { if (it.spec == "full@30") it.copy(benchmarking = true, benchLiveFps = 29.7) else it }
    RawVideoQualificationUi(
        phase = RawQualificationPhase.ENCODE_BENCH, fraction = 0.22f, runIndex = 9, runCount = 15,
        specLabel = items.first { it.spec == "full@30" }.label, remainingMs = 9 * 60_000L, items = items,
    )
})

/** The storage rest after the camera work: the ring keeps 9 / 15, every fitting configuration Measured. */
@Composable
fun RawQualificationRunningRest() = RawQualificationDriver(DriverRawReady, RawVideoQualificationUi(
    phase = RawQualificationPhase.STORAGE_REST, fraction = 0.31f, runIndex = 9, runCount = 15, remainingMs = 8 * 60_000L,
    items = driverRawCheckedItems(DriverRawChecked.keys),
))

/** The storage sweep after the rest, at 45 % overall: the ring keeps 9 / 15, every fitting configuration Measured. */
@Composable
fun RawQualificationRunningSweep() = RawQualificationDriver(DriverRawReady, RawVideoQualificationUi(
    phase = RawQualificationPhase.SWEEP, fraction = 0.45f, runIndex = 9, runCount = 15, remainingMs = 6 * 60_000L,
    items = driverRawCheckedItems(DriverRawChecked.keys),
))

/**
 * Late in the run, step 11 of 13: the descent stopped at 4080×3060 · 30 fps (Ready), and its compressed (block-pack)
 * twin runs its own long run, the longest header label, which may break only before "· Compressed".
 */
@Composable
fun RawQualificationRunningCompressed() = RawQualificationDriver(DriverRawReady, run {
    val compressed = requireNotNull(RawQualificationSpecs.parse("full@30+bp", "0", 4080, 3060))
    val label = RawVideoConfigOption.of(compressed.config).label
    RawVideoQualificationUi(
        phase = RawQualificationPhase.RUN, fraction = 0.8f, runIndex = 11, runCount = 13, specLabel = label, remainingMs = 2 * 60_000L,
        items = driverRawCheckedItems(DriverRawChecked.keys - "full@30", "full@30" to RawQualificationItemStatus.PASSED)
            .map { if (it.spec == "full@30") it.copy(achievedFps = 29.9) else it } +
            RawQualificationItem("full@30+bp", label, 22_100_000_000L, RawQualificationItemStatus.TESTING, liveFps = 29.8),
    )
})

/** Six configurations qualified, two skipped for space. */
@Composable
fun RawQualificationDone() = RawQualificationDriver(DriverRawReady, run {
    val items = driverRawItems(*DriverRawPlanNeeds.filter { it.second <= 26_000_000_000L }
        .map { it.first to RawQualificationItemStatus.PASSED }.toTypedArray())
    RawVideoQualificationUi(
        phase = RawQualificationPhase.DONE, fraction = 1f, outcome = RawQualificationOutcomeKind.PASSED,
        passedLabels = items.filter { it.status == RawQualificationItemStatus.PASSED }.map { it.label },
        items = items,
    )
})

/**
 * A run the heat cut short, as on the device: 4080×3060 and 3840×2160 at 60 fps failed, then the phone
 * reached SEVERE and the qualifier skipped 3840×2160 and 1920×1080 at 30 fps ("Too hot", the heat hint).
 */
@Composable
fun RawQualificationDoneHot() = RawQualificationDriver(DriverRawReady, run {
    val items = driverRawItems(
        "full@15" to RawQualificationItemStatus.PASSED, "full@24" to RawQualificationItemStatus.PASSED,
        "full@30" to RawQualificationItemStatus.PASSED, "full@60" to RawQualificationItemStatus.FAILED,
        "3840x2160@30" to RawQualificationItemStatus.SKIPPED_HEAT, "3840x2160@60" to RawQualificationItemStatus.FAILED,
        "1920x1080@30" to RawQualificationItemStatus.SKIPPED_HEAT, "1920x1080@60" to RawQualificationItemStatus.PASSED,
    )
    RawVideoQualificationUi(
        phase = RawQualificationPhase.DONE, fraction = 1f, outcome = RawQualificationOutcomeKind.PASSED,
        passedLabels = items.filter { it.status == RawQualificationItemStatus.PASSED }.map { it.label },
        items = items,
    )
})

/**
 * The Razr's result: its HAL delivers RAW at 30 fps at most, so the 60 fps configurations delivered
 * no frames and are left out as "Camera limit"; everything at 15 to 30 fps qualified.
 */
@Composable
fun RawQualificationDoneCameraLimit() = RawQualificationDriver(DriverRawReady, run {
    val items = driverRawItems(
        "full@15" to RawQualificationItemStatus.PASSED, "full@24" to RawQualificationItemStatus.PASSED,
        "full@30" to RawQualificationItemStatus.PASSED, "full@60" to RawQualificationItemStatus.CAMERA_LIMIT,
        "3840x2160@30" to RawQualificationItemStatus.PASSED, "3840x2160@60" to RawQualificationItemStatus.CAMERA_LIMIT,
        "1920x1080@30" to RawQualificationItemStatus.PASSED, "1920x1080@60" to RawQualificationItemStatus.CAMERA_LIMIT,
    )
    RawVideoQualificationUi(
        phase = RawQualificationPhase.DONE, fraction = 1f, outcome = RawQualificationOutcomeKind.PASSED,
        passedLabels = items.filter { it.status == RawQualificationItemStatus.PASSED }.map { it.label },
        items = items,
    )
})

/**
 * A second qualification soon after a full one: the sweep found the write cache still flushing, so
 * the run stopped after it (`storage-not-rested`). "Storage needs to rest" with the rest hint
 * (`raw-qualification-rest-hint`) and, the camera having been qualified before, the line saying
 * that qualification is kept (`raw-qualification-rest-kept`).
 */
@Composable
fun RawQualificationStorageBusy() = RawQualificationDriver(DriverRawReady, RawVideoQualificationUi(
    phase = RawQualificationPhase.DONE, fraction = 1f, outcome = RawQualificationOutcomeKind.FAILED_TO_RUN,
    reason = RawVideoQualifier.REASON_STORAGE_NOT_RESTED, previousKept = true,
    items = driverRawItems("full@15" to RawQualificationItemStatus.NOT_QUALIFIED, "full@60" to RawQualificationItemStatus.NOT_QUALIFIED),
))

/**
 * The Razr-like result under the 1.1225x margin: 4080×3060 at 60 fps kept up with zero loss but its
 * storage median sits under the margin, so it is "Risky rate" in its own card, with the burst
 * estimate the sweep's cliff gives it (75 s: "frames may drop after about 1 min"); 3840×2160 and
 * 1920×1080 at 60 fps and everything at 15 to 30 fps qualified.
 */
@Composable
fun RawQualificationDoneRisky() = RawQualificationDriver(DriverRawReady, run {
    val items = driverRawItems(
        "full@15" to RawQualificationItemStatus.PASSED, "full@24" to RawQualificationItemStatus.PASSED,
        "full@30" to RawQualificationItemStatus.PASSED, "full@60" to RawQualificationItemStatus.RISKY,
        "3840x2160@30" to RawQualificationItemStatus.PASSED, "3840x2160@60" to RawQualificationItemStatus.PASSED,
        "1920x1080@30" to RawQualificationItemStatus.PASSED, "1920x1080@60" to RawQualificationItemStatus.PASSED,
    ).map { if (it.spec == "full@60") it.copy(burstSeconds = 75L) else it }
    RawVideoQualificationUi(
        phase = RawQualificationPhase.DONE, fraction = 1f, outcome = RawQualificationOutcomeKind.PASSED,
        passedLabels = items.filter { it.status == RawQualificationItemStatus.PASSED }.map { it.label },
        items = items,
    )
})

/**
 * The Razr-like result with reduced-rate passes (PLAN-071): 4080×3060 at 60 fps risky and reaching
 * 51.9 fps, 3840×2160 at 60 fps qualified but reaching 49.1 fps ("Reaches … fps"); every other
 * configuration shows the rate it achieved at its target ("29.9 fps"). Rows are `raw-qualification-item-<spec>`.
 */
@Composable
fun RawQualificationDoneReducedRate() = RawQualificationDriver(DriverRawReady, run {
    val achieved = mapOf("full@15" to 15.0, "full@24" to 24.0, "full@30" to 29.9, "full@60" to 51.9, "3840x2160@30" to 30.0,
        "3840x2160@60" to 49.1, "1920x1080@30" to 30.0, "1920x1080@60" to 59.9)
    val items = driverRawItems(
        "full@15" to RawQualificationItemStatus.PASSED, "full@24" to RawQualificationItemStatus.PASSED,
        "full@30" to RawQualificationItemStatus.PASSED, "full@60" to RawQualificationItemStatus.RISKY,
        "3840x2160@30" to RawQualificationItemStatus.PASSED, "3840x2160@60" to RawQualificationItemStatus.PASSED,
        "1920x1080@30" to RawQualificationItemStatus.PASSED, "1920x1080@60" to RawQualificationItemStatus.PASSED,
    ).map { it.copy(achievedFps = achieved[it.spec], burstSeconds = if (it.spec == "full@60") 75L else null) }
    RawVideoQualificationUi(
        phase = RawQualificationPhase.DONE, fraction = 1f, outcome = RawQualificationOutcomeKind.PASSED,
        passedLabels = items.filter { it.status == RawQualificationItemStatus.PASSED }.map { it.label },
        items = items,
    )
})

/** The run finished with no configuration sustained. */
@Composable
fun RawQualificationNone() = RawQualificationDriver(DriverRawReady, RawVideoQualificationUi(
    phase = RawQualificationPhase.DONE, fraction = 1f, outcome = RawQualificationOutcomeKind.NO_CONFIG,
))

/**
 * Settings with RAW video qualified: Capture holds the configuration row (`settings-raw-video`), with
 * block-pack twins, the RAW compression row (`settings-raw-compression`: `raw-compression-lossless`,
 * `raw-compression-near-lossless`) and "Qualify again" (`settings-raw-video-requalify`).
 */
@Composable
fun SettingsRawVideo() = DriverTheme {
    var settings by remember { mutableStateOf(DriverRawVideoSettings) }
    SettingsScreen(driverRawVideoState(compressed = true), settings, audioPermissionGranted = true, onRequestAudioPermission = {}, onOpenAbout = {},
        onSettingsChange = { settings = it }, onSelectRawVideoConfig = { key -> settings = settings.copy(rawVideoConfig = key) },
        onRequalifyRawVideo = {})
}

/** Settings on a RAW video candidate: the row says nothing is qualified yet and points to the mode selector. */
@Composable
fun SettingsRawVideoCandidate() = DriverTheme {
    val state = driverCaptureState(CaptureMode.VIDEO).let { it.copy(modeGates = it.modeGates + (CaptureMode.RAW_VIDEO to ModeGateState.CANDIDATE)) }
    SettingsScreen(state, CameraSettings(), audioPermissionGranted = true, onRequestAudioPermission = {}, onOpenAbout = {},
        onSettingsChange = {})
}

/** The white balance dial open: a bottom sheet on a phone, a side pane where there is width. */
@Composable
fun CaptureSheetWb() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO), initialPane = CaptureInitialPane.WHITE_BALANCE)

/** The focus panel open, with marks A and B. */
@Composable
fun CaptureSheetFocus() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO), initialPane = CaptureInitialPane.FOCUS)

/** The modal mode sheet a phone opens from the mode dial. */
@Composable
fun CaptureModeSheet() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO), initialPane = CaptureInitialPane.MODE_SHEET)

/** The modes in the docked pane, as the wider layouts open them. */
@Composable
fun CaptureModes() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO), initialPane = CaptureInitialPane.MODES)

/** HLG selected, with LOG and HLG both offered in the docked modes pane. */
@Composable
fun CaptureHlg() = CaptureChrome(
    driverCaptureState(CaptureMode.HLG).let {
        it.copy(modeGates = it.modeGates + mapOf(CaptureMode.LOG to ModeGateState.AVAILABLE, CaptureMode.HLG to ModeGateState.AVAILABLE))
    },
    initialPane = CaptureInitialPane.MODES,
)

/** HLG with no pane open over a portrait 9:16 frame: the instruments stack in a column beside the lock keys. */
@Composable
fun CaptureHlgPortrait() = CaptureChrome(driverCaptureState(CaptureMode.HLG), previewAspectRatio = 9f / 16f)

/** The monitoring toggles pane, with every scope switched on. */
@Composable
fun CaptureMonitor() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO), driverScopeSettings(), CaptureInitialPane.MONITOR)

/** Waveform, vectorscope and false colour switched on, with live analysis, and the histogram. */
@Composable
fun CaptureScopes() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO), driverScopeSettings())

/** The same scopes hidden with H: their keys read off, and pressing one shows them again. */
@Composable
fun CaptureScopesHidden() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO), driverScopeSettings(), scopesHidden = true)

/** The device ran too hot: analysis is suspended and the scope keys read unavailable; the notice stacks under the zoom. */
@Composable
fun CaptureThermal() = CaptureChrome(
    driverCaptureState(CaptureMode.VIDEO).copy(analysisSuspension = com.librestatic.opencinecam.camera.AnalysisSuspension.THERMAL),
    driverScopeSettings(),
)

/** The same while recording, with the recording HUD above the instruments. */
@Composable
fun CaptureThermalRecording() = CaptureChrome(
    driverCaptureState(CaptureMode.VIDEO, CameraUiPhase.RECORDING).copy(recordingElapsedMs = 83_000L,
        analysisSuspension = com.librestatic.opencinecam.camera.AnalysisSuspension.THERMAL),
    driverScopeSettings(),
)

/** Controls locked while a photo is saved, with the saved notice: the state review item 21 covers. */
@Composable
fun CaptureLocked() = CaptureChrome(
    driverCaptureState().copy(
        stillCapturePending = true,
        message = stringResource(R.string.photo_capture_saved, "OCC_20261003_101500.jpg"),
    ),
)

/** A video take starting with "lock capture controls during a take" on: the operator's own lock, with Unlock. */
@Composable
fun CaptureOperatorLocked() = CaptureChrome(
    driverCaptureState(CaptureMode.VIDEO, CameraUiPhase.CAPTURING).copy(
        effectiveSettings = CameraSettings(operation = OperatorPreferences(lockDuringTake = true)),
    ),
)

/** A desktop-like window with a hardware keyboard: tooltips and labels carry the shortcuts. */
@Composable
fun CaptureDesktop() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO), driverScopeSettings(), hardwareKeyboard = true)

/** The settings hub, home page. 840 dp or wider (`--device inner`) shows the two-pane layout. */
@Composable
fun Settings() = DriverTheme {
    // Keeps its own settings so toggles and pills reflect clicks; the lens lock row answers like the service.
    var settings by remember { mutableStateOf(CameraSettings()) }
    var lockedLens by remember { mutableStateOf<String?>(null) }
    SettingsScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING,
        lensLock = com.librestatic.opencinecam.LensLockUiState(DriverLensLockOptions, lockedLens)), settings, audioPermissionGranted = false,
        onRequestAudioPermission = {}, onOpenAbout = {}, onSettingsChange = { settings = it }, onSetLensLock = { lockedLens = it })
}

/** The settings hub on a camera that offers the low-light auto frame rate switch (Qualcomm/Motorola IQdynamicFPS). */
@Composable
fun SettingsLowLightFps() = DriverTheme {
    var settings by remember { mutableStateOf(CameraSettings()) }
    val state = driverCaptureState(CaptureMode.VIDEO).copy(cameras = listOf(DriverCamera.copy(lowLightAutoFps = true)))
    SettingsScreen(state, settings, audioPermissionGranted = false,
        onRequestAudioPermission = {}, onOpenAbout = {}, onSettingsChange = { settings = it })
}

/** The settings hub while recording: changes that need a camera restart are locked. */
@Composable
fun SettingsRecording() = DriverTheme {
    SettingsScreen(CameraUiState(phase = CameraUiPhase.RECORDING, settingsPending = true), CameraSettings(),
        audioPermissionGranted = true, onRequestAudioPermission = {}, onOpenAbout = {}, onSettingsChange = {})
}

/** The settings hub on an Adaptive recording policy; `click tag=settings-category-RECORDING` shows the policy row. */
@Composable
fun SettingsRecordingPolicy() = DriverTheme {
    var settings by remember { mutableStateOf(CameraSettings(recordingPolicy = PolicyMode.ADAPTIVE)) }
    SettingsScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING), settings, audioPermissionGranted = true,
        onRequestAudioPermission = {}, onOpenAbout = {}, onSettingsChange = { settings = it })
}

/** The settings hub with the volume keys as a zoom pair; Controls › Operator controls shows the pair rows. */
@Composable
fun SettingsVolumeKeys() = DriverTheme {
    var settings by remember { mutableStateOf(CameraSettings(operation = OperatorPreferences(volumePair = VolumeKeyPair.ZOOM, volumeBoth = OperatorAction.CAPTURE))) }
    SettingsScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING), settings, audioPermissionGranted = true,
        onRequestAudioPermission = {}, onOpenAbout = {}, onSettingsChange = { settings = it })
}

private val FakeInventory = listOf(
    CameraInventory("0", null, listOf(CharacteristicEntry("android.lens.facing", "BACK"),
        CharacteristicEntry("android.sensor.info.sensitivityRange", "[50, 6400]")), lensFacing = 1, focalLengthsMm = listOf(6.9f), sensorSizeMm = 9.6f to 7.2f),
)

/** Capabilities with the export dialog open: redacted by default, consent not yet given, so the actions are off. */
@Composable
fun CapabilitiesExport() = DriverTheme {
    CameraCapabilitiesScreen(listOf(DriverCamera), "0", onBack = {}, loadInventory = { FakeInventory },
        initialExportOpen = true, loadCodecAudio = { _, _ -> null })
}

/** Capabilities with the latest Main10 file check: HLG signalling passed, effective precision needs a controlled capture. */
@Composable
fun CapabilitiesMain10() = DriverTheme {
    CameraCapabilitiesScreen(listOf(DriverCamera), "0", onBack = {}, loadInventory = { FakeInventory }, loadCodecAudio = { _, _ -> null },
        loadMain10Check = { com.librestatic.opencinecam.camera.Main10FileChecks.evaluate(com.librestatic.opencinecam.camera.Main10TakeKind.HLG,
            com.librestatic.opencinecam.camera.Main10FileSignal("video/hevc", "Main10", "BT.2020", "HLG", 10), "OCC_20261019_120000.mp4", 1_792_000_000_000L) })
}

/**
 * Capabilities with the Razr's bundled device profile on a build other than the one it was recorded
 * on: active, the two high-speed quirks listed, and the "not verified on this build" notice.
 */
@Composable
fun CapabilitiesDeviceProfile() = DriverTheme {
    val documents = java.io.File("src/main/profiles/device-profiles").listFiles { file -> file.extension == "json" }
        .orEmpty().sortedBy { it.name }.associate { it.name to it.readText() }
    val quirks = com.librestatic.opencinecam.BundledDeviceQuirks.fromDocuments(documents, "blanc", "motorola/blanc_g/blanc:16/NEXT.1/1:user/release-keys")
    CameraCapabilitiesScreen(listOf(DriverCamera), "0", onBack = {}, loadInventory = { FakeInventory }, loadCodecAudio = { _, _ -> null },
        loadMain10Check = { null }, loadDeviceProfile = { _, cameraId -> quirks.resolve(cameraId) })
}

/** The storage benchmark page with a finished, qualified result (three windows). */
@Composable
fun StorageBenchmark() = DriverTheme {
    val window = { index: Int, rate: Long -> buildJsonObject { put("index", index); put("bytesPerSecond", rate) } }
    val result = BenchmarkResultDocument(
        schemaVersion = "1.0.0", benchmarkId = "driver-benchmark", createdAt = "2026-10-19T12:00:00Z",
        destination = buildJsonObject { put("kind", "saf-destination") }, bytesWritten = 3L * 1024 * 1024 * 1024,
        windows = listOf(window(0, 410_000_000L), window(1, 395_000_000L), window(2, 402_000_000L)),
        requiredBytesPerSecond = 37_500_000L, qualificationMarginBytesPerSecond = 357_500_000L,
        outcome = "pass", outcomeDetail = StorageBenchmarkOutcome.QUALIFIED.name,
    )
    StorageBenchmarkContent(37_500_000L, running = false, last = result, onBack = {}, onChoose = {}, onCancel = {})
}

private val DriverCalibratedLenses = listOf(
    LensCalibrationLensRow("3", 0.5f, LensCalibrationLensStatus.DONE, 1f, 6.1f, 1.2f, 9.4f, 0.8f, 0.72f, setOf(CalibrationFlag.PARALLAX_SUSPECT)),
    LensCalibrationLensRow("2", 1f, LensCalibrationLensStatus.REFERENCE),
    LensCalibrationLensRow("5", 2f, LensCalibrationLensStatus.DONE, 1f, 4.3f, 0.9f, 5.2f, 0.6f, 0.91f),
)

private val DriverCalibrationSaved = LensCalibrationUiState(
    logicalCameraId = "0", referencePhysicalId = "2", savedCreatedAtMs = 1_792_000_000_000L, savedLenses = DriverCalibratedLenses,
)

/** The lens calibration page with a saved three-lens calibration; the switches keep their own state. */
@Composable
fun LensCalibration() = LensCalibrationPage(DriverCalibrationSaved)

/** The same page before any calibration is saved. */
@Composable
fun LensCalibrationEmpty() = LensCalibrationPage(LensCalibrationUiState())

@Composable
private fun LensCalibrationPage(state: LensCalibrationUiState) = DriverTheme {
    var settings by remember { mutableStateOf(CameraSettings(lensCorrectionVideo = true, lensCorrectionPhotos = false, lensCorrectionAlignment = true)) }
    LensCalibrationContent(state, settings, onSettingsChange = { settings = it }, onBack = {}, onStart = {}, onDelete = {})
}

/** A run in progress: the ultra-wide is done, the tele is measuring at 40 %. */
@Composable
fun LensCalibrationRunning() = CaptureChrome(
    driverCaptureState(CaptureMode.PHOTO),
    lensCalibration = LensCalibrationUiState(
        phase = LensCalibrationPhase.MEASURING, logicalCameraId = "0", referencePhysicalId = "2",
        lenses = listOf(
            LensCalibrationLensRow("3", 0.5f, LensCalibrationLensStatus.DONE, 1f),
            LensCalibrationLensRow("2", 1f, LensCalibrationLensStatus.REFERENCE),
            LensCalibrationLensRow("5", 2f, LensCalibrationLensStatus.MEASURING, 0.4f),
        ),
    ),
)

/** A finished run waiting to be saved or discarded. */
@Composable
fun LensCalibrationResult() = CaptureChrome(
    driverCaptureState(CaptureMode.PHOTO),
    lensCalibration = LensCalibrationUiState(
        phase = LensCalibrationPhase.RESULT, logicalCameraId = "0", referencePhysicalId = "2", lenses = DriverCalibratedLenses,
    ),
)

/** A run that kept nothing: the ultra-wide failed on parallax and AE, the tele never found its switch. */
@Composable
fun LensCalibrationFailed() = CaptureChrome(
    driverCaptureState(CaptureMode.PHOTO),
    lensCalibration = LensCalibrationUiState(
        phase = LensCalibrationPhase.FAILED, logicalCameraId = "0", referencePhysicalId = "2",
        failure = LensCalibrationFailure.LOW_QUALITY,
        lenses = listOf(
            LensCalibrationLensRow("3", 0.5f, LensCalibrationLensStatus.FAILED, 1f, colorDeltaEBefore = 6.8f, colorDeltaEAfter = 4.9f,
                alignPxBefore = 9.2f, alignPxAfter = 3.4f, score = 0.31f,
                flags = setOf(CalibrationFlag.PARALLAX_SUSPECT, CalibrationFlag.UNSTABLE_AE)),
            LensCalibrationLensRow("2", 1f, LensCalibrationLensStatus.REFERENCE),
            LensCalibrationLensRow("5", 3f, LensCalibrationLensStatus.FAILED, 0.25f),
        ),
    ),
)

/** The media catalog with a mix of video, photo and audio takes; take 1's proxy is ready, take 2's is being made. */
@Composable
fun Gallery() = GalleryWith(FakeCatalog(SampleTakes))

/** The media catalog with take 1's details open: beside the grid, or as a sheet where there is no room. */
@Composable
fun GalleryInspector() = GalleryWith(FakeCatalog(SampleTakes), initialSelection = "1")

/** Take 7's details: a plain recording whose codec comes from probing the file, not from its sidecar. */
@Composable
fun GalleryInspectorProbed() = GalleryWith(FakeCatalog(SampleTakes), initialSelection = "7")

/** The DNG photo's details: its EXIF in the Image and Camera and exposure sections, and the RAW section's colour filter. */
@Composable
fun GalleryPhotoInspector() = GalleryWith(FakeCatalog(SampleTakes), initialSelection = "3")

/** The WAV recorder track's details: the File and Audio sections only. */
@Composable
fun GalleryAudioInspector() = GalleryWith(FakeCatalog(SampleTakes), initialSelection = "6")

/** Take 1's details while its files are still being read: the loading line in place of the sections. */
@Composable
fun GalleryInspectorLoading() = GalleryWith(PendingDetailsCatalog(FakeCatalog(SampleTakes)), initialSelection = "1")

/** A catalog whose details never arrive. */
private class PendingDetailsCatalog(source: MediaCatalogSource) : MediaCatalogSource by source {
    override suspend fun details(take: LocalMediaTake): TakeTechnicalDetails? = awaitCancellation()
}

/** The media catalog with no takes yet. */
/**
 * The sample takes with two RAW video takes (`.ocraw`): RAW10 and near-lossless badges, size in place of the length;
 * the first with its developed first frame (a fake gradient), the second whose frame gave no picture, so the placeholder.
 */
@Composable
fun GalleryRaw() = GalleryWith(FakeCatalog(SampleRawTakes + SampleTakes) { artifact ->
    if (artifact == SampleRawTakes[1].take.primary) null else gradientThumbnail(artifact.name.hashCode())
})

/** A RAW take's details: Play and Share (no rename or delete), and the note that the player shows a developed preview. */
@Composable
fun GalleryRawInspector() = GalleryWith(FakeCatalog(SampleRawTakes + SampleTakes), initialSelection = SampleRawTakes.first().take.id)

/** The CinemaDNG export form for the first RAW take: Documents, LJ92 offered, two damaged frames, the size against the free space. */
@Composable
fun GalleryCinemaDngExport() = CinemaDngExportFormScreen(CinemaDngExportFormState(SampleExportTakeName, SampleExportPlan,
    loading = false, choice = CinemaDngDestinationChoice.Documents, folder = SampleExportFolder, lj92Available = true,
    compress = false, freeBytes = 26_400_000_000L, recording = false, busy = false))

/** The same form exporting to a picked USB folder with LJ92 on: the size is an estimate and the free space unknown. */
@Composable
fun GalleryCinemaDngExportFolder() = CinemaDngExportFormScreen(CinemaDngExportFormState(SampleExportTakeName, SampleExportPlan,
    loading = false, choice = SampleExportFolder, folder = SampleExportFolder, lj92Available = true,
    compress = true, freeBytes = null, recording = false, busy = false))

/** The form when the take does not fit: Start disabled, the shortfall spelled out. */
@Composable
fun GalleryCinemaDngExportNoSpace() = CinemaDngExportFormScreen(CinemaDngExportFormState(SampleExportTakeName, SampleExportPlan,
    loading = false, choice = CinemaDngDestinationChoice.Documents, folder = null, lj92Available = false,
    compress = false, freeBytes = 8_200_000_000L, recording = false, busy = false))

/** The gallery while the first RAW take exports: frame count, percent and Cancel on its card. */
@Composable
fun GalleryRawExporting() = GalleryWith(FakeCatalog(SampleRawTakes + SampleTakes), exports = FakeExports(SampleExportRunning))

/** The exporting take's details: the progress and Cancel above the integrity section. */
@Composable
fun GalleryRawExportingInspector() = GalleryWith(FakeCatalog(SampleRawTakes + SampleTakes),
    initialSelection = SampleRawTakes.first().take.id, exports = FakeExports(SampleExportRunning))

/** The in-app notice after an export: where it went, the frames and the damaged ones skipped. */
@Composable
fun GalleryRawExported() = GalleryWith(FakeCatalog(SampleRawTakes + SampleTakes), exports = FakeExports(
    CinemaDngExportState.Finished(SampleRawTakes.first().take.primary.uri, SampleExportTakeName,
        CinemaDngExportOutcome.Exported("Documents/OpenCineCam/CinemaDNG/$SampleExportTakeName", frames = 1_438, skipped = 2,
            audio = true, audioOmitted = false, bytes = 19_520_000_000L, noMedia = true))))

private val SampleExportTakeName by lazy { CinemaDngExporter.takeName(SampleRawTakes.first().take.primary.name) }

/** A 60 s 24 fps 4080x3060 RAW10 take with sound, two frames damaged. */
private val SampleExportPlan by lazy { CinemaDngExportPlan(SampleExportTakeName, frames = 1_440, convertible = 1_438, fixedBytes = 0L,
    reencodedBytes = 1_438L * 15_640_000L, reencodedFrames = 1_438, wavBytes = 17_280_000L) }

private val SampleExportFolder = CinemaDngDestinationChoice.Folder("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AFootage",
    "Footage")

private val SampleExportRunning by lazy { CinemaDngExportState.Running(SampleRawTakes.first().take.primary.uri, SampleExportTakeName,
    CinemaDngExportProgress(done = 517, total = 1_440, skipped = 0, bytes = 517L * 15_640_000L)) }

/** The form inside the dialog card's look; screenshots leave dialogs out, so the driver draws the card in the window. */
@Composable
private fun CinemaDngExportFormScreen(state: CinemaDngExportFormState) = DriverTheme {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Surface(Modifier.padding(24.dp).widthIn(max = 560.dp).heightIn(max = 680.dp).testTag("cinemadng-export-dialog"),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CinemaDngExportForm(SampleRawTakes.first().take, state, onChoice = {}, onChooseFolder = {}, onCompress = {},
                    onStart = {}, onCancel = {})
            }
        }
    }
}

/** An export state that never changes; cancel and acknowledge do nothing. */
internal class FakeExports(state: CinemaDngExportState) : CinemaDngExportControl {
    override val states: StateFlow<CinemaDngExportState> = MutableStateFlow(state)
    override fun cancel() {}
    override fun acknowledge() {}
}

@Composable
fun GalleryEmpty() = GalleryWith(FakeCatalog(emptyList()))

/** The media catalog while the first page is still loading. */
@Composable
fun GalleryLoading() = GalleryWith(object : MediaCatalogSource {
    override suspend fun page(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int): LocalMediaPage =
        awaitCancellation()
    override suspend fun thumbnail(artifact: LocalMediaArtifact): Bitmap? = null
})

/** The media catalog after MediaStore failed to answer. */
@Composable
fun GalleryError() = GalleryWith(object : MediaCatalogSource {
    override suspend fun page(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int): LocalMediaPage =
        throw IOException("MediaStore unavailable")
    override suspend fun thumbnail(artifact: LocalMediaArtifact): Bitmap? = null
})

/** About, with the bundled third-party license catalog. */
@Composable
fun About() = DriverTheme { AboutScreen(onBack = {}, onOpenUri = {}, onReplayTour = {}) }

/**
 * First-run tour, first page; Robolectric reports every runtime permission as not granted. Rendered
 * with reduced motion, the path the app takes when animations are off: the animated backdrop's
 * per-frame loop would otherwise keep the test clock from ever going idle.
 */
@Composable
fun Onboarding() = DriverTheme {
    CompositionLocalProvider(LocalReducedMotion provides true) {
        OnboardingScreen(splash = SplashHandoff(onScreen = false), onPermissionsChanged = {}, onFinished = {})
    }
}

private fun driverTheme(): AppTheme =
    AppTheme.entries.firstOrNull { it.name.equals(System.getProperty("composeDriver.theme"), ignoreCase = true) } ?: AppTheme.CINE

/** The app theme over the same full-window background CameraRootScreen gives every section. */
@Composable
internal fun DriverTheme(forceDark: Boolean = false, content: @Composable () -> Unit) =
    OpenCineCamTheme(driverTheme(), forceDark = forceDark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
    }

/**
 * The capture chrome as CaptureSurface hosts it, over its own copy of [settings] so the monitoring
 * toggles, F-keys and scope keys work. The window and its keyboard are provided the way
 * ProvideAdaptiveWindow measures them; [hardwareKeyboard] stands in for an attached keyboard.
 */
@Composable
private fun CaptureChrome(
    state: CameraUiState,
    settings: CameraSettings = CameraSettings(histogramEnabled = true),
    initialPane: CaptureInitialPane? = null,
    scopesHidden: Boolean = false,
    hardwareKeyboard: Boolean = false,
    foldState: FoldDisplayState = FoldDisplayState(),
    lensCalibration: LensCalibrationUiState = LensCalibrationUiState(),
    previewAspectRatio: Float? = null,
    rawQualificationInfo: (() -> RawVideoQualificationInfo?)? = null,
    initialRawQualifyPrompt: Boolean = false,
) = DriverTheme(forceDark = true) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val window = AdaptiveWindow(maxWidth.value, maxHeight.value, hardwareKeyboard)
        var current by remember { mutableStateOf(settings) }
        val scopes = rememberSaveable(saver = CaptureScopeVisibility.Saver) { CaptureScopeVisibility(scopesHidden) }
        val live = withDriverAnalysis(state, current.monitoring)
        val actions = driverOperatorActions(live, current, scopes) { current = it }
        CompositionLocalProvider(LocalAdaptiveWindow provides window, LocalOperatorActions provides actions,
            LocalFoldDisplayStateWithoutCoordinator provides foldState) {
            AdaptiveCaptureChrome(
                state = live,
                binder = null,
                settings = current,
                landscape = window.landscape,
                previewAspectRatio = previewAspectRatio,
                zebra = current.zebraEnabled,
                peaking = current.peakingEnabled,
                histogram = current.histogramEnabled,
                histogramMode = current.histogramMode,
                showGrid = current.compositionGridEnabled,
                gridMode = current.compositionGridMode,
                showHorizon = current.horizonLevelEnabled,
                onToggleZebra = { current = current.copy(zebraEnabled = !current.zebraEnabled) },
                onTogglePeaking = { current = current.copy(peakingEnabled = !current.peakingEnabled) },
                onToggleHistogram = { current = current.copy(histogramEnabled = !current.histogramEnabled) },
                onCycleHistogramMode = {
                    current = current.copy(histogramMode = if (current.histogramMode == HistogramMode.RGB) HistogramMode.LUMA else HistogramMode.RGB)
                },
                onToggleGrid = { current = current.copy(compositionGridEnabled = !current.compositionGridEnabled) },
                onCycleGridMode = {
                    val modes = CompositionGridMode.entries
                    current = current.copy(compositionGridEnabled = true, compositionGridMode = modes[(current.compositionGridMode.ordinal + 1) % modes.size])
                },
                onToggleHorizon = { current = current.copy(horizonLevelEnabled = !current.horizonLevelEnabled) },
                onSettingsChanged = { current = it },
                onOpenMedia = {},
                onOpenSettings = {},
                initialPane = initialPane,
                lensCalibration = lensCalibration,
                rawQualificationInfo = rawQualificationInfo,
                initialRawQualifyPrompt = initialRawQualifyPrompt,
            )
        }
    }
}

@Composable
internal fun GalleryWith(source: MediaCatalogSource, initialSelection: String? = null,
    exports: CinemaDngExportControl? = null) = DriverTheme {
    MediaCatalogContent(GallerySettings(), onSettings = {}, source = source, onShare = {}, onDelete = {},
        onRename = {}, onReview = {}, onProxy = {}, onProxyCatalog = {}, initialSelection = initialSelection,
        exports = exports, onExport = {}, onOpen = {})
}

internal class FakeCatalog(private val takes: List<SampleTake>,
    private val thumbnails: (LocalMediaArtifact) -> Bitmap? = { gradientThumbnail(it.name.hashCode()) }) : MediaCatalogSource {
    override suspend fun page(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int): LocalMediaPage {
        val shown = takes.filter { query.isBlank() || it.take.primary.name.contains(query, ignoreCase = true) }
        return LocalMediaPage(shown.map { it.take }, next = null,
            encodings = shown.mapNotNull { sample -> sample.encoding?.let { sample.take.id to it } }.toMap())
    }

    override suspend fun thumbnail(artifact: LocalMediaArtifact): Bitmap? =
        if (artifact.mimeType.startsWith("audio/")) null else thumbnails(artifact)

    override suspend fun facts(artifact: LocalMediaArtifact): GalleryFacts? = takes.firstOrNull { it.take.primary == artifact }?.facts

    override fun proxyStates(ids: Set<String>): Flow<Map<String, TakeProxyState>> =
        flowOf(takes.filter { it.take.id in ids && it.proxy != TakeProxyState.NONE }.associate { it.take.id to it.proxy })

    override suspend fun probedCodec(artifact: LocalMediaArtifact): CodecBadge? = takes.firstOrNull { it.take.primary == artifact }?.probed

    override suspend fun details(take: LocalMediaTake): TakeTechnicalDetails? = takes.firstOrNull { it.take == take }?.details
        ?.let { buildTakeDetails(it(take), detailText(RuntimeEnvironment.getApplication())) }
}

/** A take with what MediaStore, its sidecars, the proxy queue, a probe of its file and the inspector's readers would say about it. */
internal class SampleTake(val take: LocalMediaTake, val facts: GalleryFacts? = null, val encoding: LocalMediaEncoding? = null,
    val proxy: TakeProxyState = TakeProxyState.NONE, val probed: CodecBadge? = null, val details: ((LocalMediaTake) -> TakeDetailInputs)? = null)

/** A stand-in frame: a two-colour gradient seeded by the file name, so takes are told apart. */
private fun gradientThumbnail(seed: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
    val hue = (seed.toUInt() % 360u).toFloat()
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, 320f, 180f,
            android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.55f)),
            android.graphics.Color.HSVToColor(floatArrayOf((hue + 60f) % 360f, 0.7f, 0.18f)), Shader.TileMode.CLAMP)
    }
    Canvas(bitmap).drawRect(0f, 0f, 320f, 180f, paint)
    return bitmap
}

private fun artifact(id: Int, name: String, mime: String, sizeBytes: Long, modified: Long) =
    LocalMediaArtifact("content://media/external_primary/file/$id", name, mime, sizeBytes, modified)

/** [log] names the sidecar like an OCLog recording's, which is what marks the take LOG. */
private fun take(id: Int, name: String, mime: String, kind: LocalMediaKind, sizeBytes: Long, slate: ProductionSlateSettings?,
    status: LocalMediaRelationStatus = LocalMediaRelationStatus.DECLARED, log: Boolean = false): LocalMediaTake {
    val primary = artifact(id, name, mime, sizeBytes, 1_790_000_000L - id * 3_600L)
    val sidecar = artifact(id + 1_000, name.substringBeforeLast('.') + if (log) ".oclog.json" else ".json", "application/json",
        4_096L, primary.modifiedSeconds)
    return LocalMediaTake(id.toString(), primary, listOf(primary), listOf(sidecar), kind, slate, status)
}

/**
 * RAW takes as the repository lists them: one `.ocraw` in Documents/OpenCineCam/RAW, no sidecar. Their
 * only facts are the codec the file's first chunk declares: uncompressed RAW10, and near-lossless.
 */
internal val SampleRawTakes = listOf(
    SampleTake(rawVideoTake(artifact(21, "OCC_TAKE_A001_S13_T01.ocraw", "application/octet-stream", 19_660_000_000L, 1_790_001_800L)),
        facts = GalleryFacts(codec = rawCodecBadge("raw10")), details = { rawTakeInputs(it, compressed = false) }),
    SampleTake(rawVideoTake(artifact(22, "OCC_TAKE_A001_S13_T02.ocraw", "application/octet-stream", 4_120_000_000L, 1_790_000_900L)),
        facts = GalleryFacts(codec = rawCodecBadge("bpq")), details = { rawTakeInputs(it, compressed = true) }),
)

internal val SampleTakes = listOf(
    SampleTake(take(1, "OCC_TAKE_A001_S12_T03.mp4", "video/mp4", LocalMediaKind.VIDEO, 1_480_000_000L,
        ProductionSlateSettings(project = "Night Market", camera = "A", scene = "12", reel = "A001", takeNumber = 3, goodTake = true), log = true),
        GalleryFacts(durationMs = 83_000L, width = 3840, height = 2160), LocalMediaEncoding("video/hevc", "Main10"), TakeProxyState.READY,
        details = ::logTakeInputs),
    SampleTake(take(2, "OCC_TAKE_A001_S12_T02.mp4", "video/mp4", LocalMediaKind.VIDEO, 912_000_000L,
        ProductionSlateSettings(project = "Night Market", camera = "A", scene = "12", reel = "A001", takeNumber = 2), log = true),
        GalleryFacts(durationMs = 51_000L, width = 1920, height = 1080), LocalMediaEncoding("video/avc", "AVC_8"), TakeProxyState.MAKING),
    SampleTake(take(3, "OCC_20260930_181522.dng", "image/x-adobe-dng", LocalMediaKind.PHOTO, 24_600_000L, slate = null),
        details = { photoTakeInputs(it, dng = true) }),
    SampleTake(take(4, "OCC_20260930_181410.jpg", "image/jpeg", LocalMediaKind.PHOTO, 6_200_000L, slate = null,
        status = LocalMediaRelationStatus.LEGACY), details = { photoTakeInputs(it, dng = false) }),
    SampleTake(take(5, "OCC_TAKE_A001_S11_T01_room-tone.m4a", "audio/mp4", LocalMediaKind.AUDIO, 3_100_000L,
        ProductionSlateSettings(project = "Night Market", scene = "11", takeNumber = 1)), GalleryFacts(durationMs = 62_000L),
        details = { audioTakeInputs(it, wav = false) }),
    SampleTake(take(6, "OCC_TAKE_A001_S11_T02_wild.wav", "audio/wav", LocalMediaKind.AUDIO, 21_800_000L,
        ProductionSlateSettings(project = "Night Market", scene = "11", takeNumber = 2)), GalleryFacts(durationMs = 75_000L),
        LocalMediaEncoding(audioContainer = "WAV", audioSamples = "PCM_24"), details = { audioTakeInputs(it, wav = true) }),
    SampleTake(take(7, "OCC_TAKE_A001_S10_T04.mp4", "video/mp4", LocalMediaKind.VIDEO, 640_000_000L,
        ProductionSlateSettings(project = "Night Market", camera = "A", scene = "10", reel = "A001", takeNumber = 4)),
        GalleryFacts(durationMs = 42_000L, width = 1920, height = 1080), probed = CodecBadge("H.264", 8),
        details = ::probedTakeInputs),
)

// Focus peaking

/**
 * Capture chrome over a synthetic analysis frame with focus peaking on: the sharp ring, the sharp
 * disc and the block of glyphs peak, the soft disc in the middle does not. Portrait or landscape
 * follows the window. The frame is centred in the window, because the real pane layout needs a
 * service binder; the overlay and its mapping are the production ones.
 */
@Composable
fun CapturePeaking() = DriverTheme(forceDark = true) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        val displayDegrees = HorizonRollMath.surfaceRotationDegrees(LocalView.current.display?.rotation ?: Surface.ROTATION_0)
        // Whatever rotation Robolectric reports, mount the sensor so the picture stands upright.
        val sensorOrientation = ((if (landscape) 0 else 90) + displayDegrees) % 360
        val ratio = previewDisplayRatio(PeakingStream.width, PeakingStream.height, 1f, sensorOrientation, displayDegrees)
        val luma = remember(sensorOrientation, displayDegrees) { peakingSceneLuma(sensorOrientation, displayDegrees, ratio) }
        val mask = remember(luma) {
            detectFocusEdges(luma, PeakingAnalysis.width, PeakingAnalysis.height, MonitoringOptions().peakingThreshold, MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR)
        }
        val picture = remember(luma) {
            val pixels = IntArray(luma.size) { val g = (luma[it].toInt() and 0xff) * 3 / 4; (0xff shl 24) or (g shl 16) or (g shl 8) or g }
            Bitmap.createBitmap(pixels, PeakingAnalysis.width, PeakingAnalysis.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
        // The overlay hides analysis older than a second, as it does on a stalled camera.
        var analysisAtMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(Unit) { while (true) { delay(250); analysisAtMs = SystemClock.elapsedRealtime() } }
        val settings = CameraSettings(peakingEnabled = true)
        val camera = peakingCamera(sensorOrientation)
        val state = CameraUiState(phase = CameraUiPhase.PREVIEWING, selectedMode = CaptureMode.VIDEO, cameras = listOf(camera),
            selectedCameraId = camera.cameraId, focusPeakingMask = mask, analysisUpdatedAtMs = analysisAtMs)
        Box(Modifier.align(Alignment.Center).aspectRatio(ratio)) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val placement = focusPeakingPlacement(PeakingAnalysis.width, PeakingAnalysis.height, MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR,
                    PeakingStream.width, PeakingStream.height, PeakingSensor.width(), PeakingSensor.height(), sensorOrientation,
                    displayDegrees, false, size.width, size.height, 1f, 1f, edgeShift = 0f)
                drawFocusPeaking(picture, placement, Rect(Offset.Zero, size), alpha = 1f)
            }
            MonitoringOverlay(state, settings.monitoring, PeakingStream.width, PeakingStream.height, 1f, showZebra = false,
                showPeaking = true, showHistogram = false, histogramMode = HistogramMode.RGB, showGrid = false,
                gridMode = CompositionGridMode.THIRDS, showHorizon = false, drawHistogram = false,
                modifier = Modifier.matchParentSize(), drawScopesPanel = false)
        }
        AdaptiveCaptureChrome(state = state, binder = null, settings = settings, landscape = landscape, zebra = false,
            peaking = true, histogram = false, histogramMode = HistogramMode.RGB, showGrid = false, gridMode = CompositionGridMode.THIRDS,
            showHorizon = false, onToggleZebra = {}, onTogglePeaking = {}, onToggleHistogram = {}, onCycleHistogramMode = {},
            onToggleGrid = {}, onCycleGridMode = {}, onToggleHorizon = {}, onSettingsChanged = {}, onOpenMedia = {}, onOpenSettings = {})
    }
}

private val PeakingStream = Size(1920, 1080)
private val PeakingAnalysis = Size(320, 240)
private val PeakingSensor = android.graphics.Rect(0, 0, 4000, 3000)

private fun peakingCamera(sensorOrientation: Int) = Camera2CameraDescriptor(
    cameraId = "0", lensFacing = CameraCharacteristics.LENS_FACING_BACK, focalLengthsMm = listOf(5f), previewSize = PeakingStream,
    jpegSize = Size(4000, 3000), rawSize = null, analysisSize = PeakingAnalysis, sensorOrientation = sensorOrientation,
    sensitivityRange = null, exposureTimeRangeNs = null, aeCompensationRange = null, aeCompensationStep = 0f,
    minimumFocusDistance = null, supportsRaw = false, flashAvailable = false, targetFpsRanges = emptyList(),
    availableFixedFps = listOf(30), videoProfiles = emptyList(), logProfiles = emptyList(), sensorActiveArray = PeakingSensor,
)

/**
 * The 4:3 analysis raster of an upright scene. Each sensor pixel is placed on screen the way the
 * overlay places it, so the scene is drawn in screen space (units of 1/1000 of the frame height)
 * and stays round and upright in either orientation; the part of the 4:3 raster outside the 16:9
 * preview carries scene too, as on a real sensor.
 */
private fun peakingSceneLuma(sensorOrientation: Int, displayDegrees: Int, ratio: Float): ByteArray {
    val width = PeakingAnalysis.width
    val height = PeakingAnalysis.height
    val frame = outputFrameInStream(width, height, PeakingStream.width, PeakingStream.height, PeakingSensor.width(), PeakingSensor.height())
    val w = ratio * 1000f
    val h = 1000f
    val s = minOf(w, h)
    return ByteArray(width * height) { index ->
        val u = frame.left + (index % width + .5f) * frame.width / width
        val v = frame.top + (index / width + .5f) * frame.height / height
        val (px, py) = monitoringDisplayPoint(u, v, MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR, sensorOrientation, displayDegrees, false)
        peakingScene(px * w, py * h, w, h, s).toByte()
    }
}

/** Luma of the test scene at ([x], [y]) in a [w] × [h] frame whose short side is [s]. */
private fun peakingScene(x: Float, y: Float, w: Float, h: Float, s: Float): Int {
    fun distance(cx: Float, cy: Float) = kotlin.math.hypot(x - cx, y - cy)
    var value = 92 + (36 * y / h).toInt().coerceIn(-20, 56)
    if (distance(.3f * w, .27f * h) in .09f * s..(.15f * s)) value = 226
    if (distance(.7f * w, .27f * h) < .12f * s) value = 26
    // Out of focus: the same contrast as the discs, spread over about fifteen analysis pixels.
    val blur = ((distance(.5f * w, .5f * h) - .07f * s) / (.1f * s)).coerceIn(0f, 1f)
    value += ((214 - value) * (1f - blur * blur * (3f - 2f * blur))).toInt()
    // A page of text: rows of glyphs in words, each glyph one of four letter-like shapes.
    val left = .2f * w; val right = .8f * w; val top = .64f * h; val bottom = .88f * h
    if (x in left..right && y in top..bottom) {
        value = 232
        val line = .075f * s; val advance = .05f * s
        val row = ((y - top - .02f * s) / line).toInt()
        val column = ((x - left - .03f * s) / advance).toInt()
        val gx = (x - left - .03f * s) / advance - column
        val gy = (y - top - .02f * s) / line - row
        val inside = x < right - .03f * s && y < bottom - .02f * s && row >= 0 && column >= 0 && gx < .64f && gy < .66f
        if (inside && (column + row * 3) % 6 != 5) {
            val cx = gx / .64f; val cy = gy / .66f
            val ink = when ((column * 7 + row * 5) % 4) {
                0 -> cx in .35f..(.7f)
                1 -> kotlin.math.hypot(cx - .5f, cy - .5f) in .25f..(.5f)
                2 -> cx < .3f || cx > .7f || cy < .25f
                else -> cy < .2f || cy in .42f..(.6f) || cy > .8f || cx < .3f
            }
            if (ink) value = 34
        }
    }
    return value.coerceIn(0, 255)
}

// ---- Exterior (subject) display and the fold menu. Subject screens are meant for `--device cover`. ----

private const val SUBJECT_SCRIPT = "Hola, soy Ana. Hoy les muestro cómo grabamos este corto con un solo teléfono.\n\n" +
    "Primero, la luz: usamos la pantalla externa como relleno suave.\n\nDespués, el encuadre y el sonido."

private fun subjectRecording(): CameraUiState =
    driverCaptureState(CaptureMode.VIDEO, CameraUiPhase.RECORDING).copy(recordingElapsedMs = 83_000L)

/** The exterior display as FoldDisplayCoordinator hosts it: always the Cine palette. */
@Composable
private fun Subject(state: CameraUiState, settings: SubjectDisplaySettings, cues: SubjectSessionCues = SubjectSessionCues()) =
    OpenCineCamTheme(AppTheme.CINE, forceDark = true) {
        SubjectDisplayScreen(state, settings, previewPort = null, cues = cues,
            productionSlate = ProductionSlateSettings(project = "CORTO", scene = "12A", reel = "A001", camera = "A", lens = "24mm", takeNumber = 3))
    }

/** Exterior display, Status: Ready at rest. */
@Composable
fun SubjectStatus() = Subject(driverCaptureState(CaptureMode.VIDEO), SubjectDisplaySettings())

/** Exterior display, Status: REC 01:23 with an operator cue. */
@Composable
fun SubjectStatusRecording() = Subject(subjectRecording(), SubjectDisplaySettings(operatorCue = "Mira a cámara"))

/** Exterior display, teleprompter at rest with a cue. */
@Composable
fun SubjectPrompter() = Subject(driverCaptureState(CaptureMode.VIDEO),
    SubjectDisplaySettings(mode = SubjectDisplayMode.TELEPROMPTER, prompterText = SUBJECT_SCRIPT, operatorCue = "Más despacio"))

/** Exterior display, teleprompter while recording: the REC header shows. */
@Composable
fun SubjectPrompterRecording() = Subject(subjectRecording(),
    SubjectDisplaySettings(mode = SubjectDisplayMode.TELEPROMPTER, prompterText = SUBJECT_SCRIPT))

/** Exterior display, camera preview waiting for frames (no viewfinder headless). */
@Composable
fun SubjectPreview() = Subject(driverCaptureState(CaptureMode.VIDEO), SubjectDisplaySettings(mode = SubjectDisplayMode.PREVIEW))

/** Exterior display, fill light at 4300 K. */
@Composable
fun SubjectFillLight() = Subject(driverCaptureState(CaptureMode.VIDEO),
    SubjectDisplaySettings(mode = SubjectDisplayMode.FILL_LIGHT, fillLightKelvin = 4300))

/** Exterior display, take review before the operator picks a take. */
@Composable
fun SubjectReview() = Subject(driverCaptureState(CaptureMode.VIDEO), SubjectDisplaySettings(mode = SubjectDisplayMode.REVIEW))

/** Exterior display, interview question 2 of 3. */
@Composable
fun SubjectInterview() = Subject(driverCaptureState(CaptureMode.VIDEO),
    SubjectDisplaySettings(mode = SubjectDisplayMode.INTERVIEW,
        interviewQuestions = listOf("¿Cómo empezaste a filmar?", "¿Qué equipo usas?", "¿Qué consejo darías?")),
    SubjectSessionCues(interviewIndex = 1))

/** Exterior display, digital slate. */
@Composable
fun SubjectSlate() = Subject(driverCaptureState(CaptureMode.VIDEO), SubjectDisplaySettings(mode = SubjectDisplayMode.SLATE))

/** Exterior display, split screen: teleprompter on top, the rear camera below (no viewfinder headless). */
@Composable
fun SubjectPrompterSplit() = Subject(driverCaptureState(CaptureMode.VIDEO),
    SubjectDisplaySettings(mode = SubjectDisplayMode.TELEPROMPTER, prompterText = SUBJECT_SCRIPT, operatorCue = "Más despacio",
        splitPreviewModes = setOf(SubjectDisplayMode.TELEPROMPTER)))

/** Exterior display, split screen: the status hero on top. */
@Composable
fun SubjectStatusSplit() = Subject(driverCaptureState(CaptureMode.VIDEO),
    SubjectDisplaySettings(splitPreviewModes = setOf(SubjectDisplayMode.STATUS)))

/** Exterior display, split screen: fill light on top, the camera below dimmed by the 50% dither mask. */
@Composable
fun SubjectFillLightSplit() = Subject(driverCaptureState(CaptureMode.VIDEO),
    SubjectDisplaySettings(mode = SubjectDisplayMode.FILL_LIGHT, fillLightKelvin = 4300, fillLightPreviewLevel = 50,
        splitPreviewModes = setOf(SubjectDisplayMode.FILL_LIGHT)))

/** Exterior display, split screen: the interview question on top. */
@Composable
fun SubjectInterviewSplit() = Subject(driverCaptureState(CaptureMode.VIDEO),
    SubjectDisplaySettings(mode = SubjectDisplayMode.INTERVIEW, splitPreviewModes = setOf(SubjectDisplayMode.INTERVIEW),
        interviewQuestions = listOf("¿Cómo empezaste a filmar?", "¿Qué equipo usas?", "¿Qué consejo darías?")),
    SubjectSessionCues(interviewIndex = 1))

/** Exterior display, split screen: the slate on top. */
@Composable
fun SubjectSlateSplit() = Subject(driverCaptureState(CaptureMode.VIDEO),
    SubjectDisplaySettings(mode = SubjectDisplayMode.SLATE, splitPreviewModes = setOf(SubjectDisplayMode.SLATE)))

/** Exterior display, split screen: take review on top. */
@Composable
fun SubjectReviewSplit() = Subject(driverCaptureState(CaptureMode.VIDEO),
    SubjectDisplaySettings(mode = SubjectDisplayMode.REVIEW, splitPreviewModes = setOf(SubjectDisplayMode.REVIEW)))

private val FoldUnfolded = FoldDisplayState(DisplayCapability.AVAILABLE, DisplayCapability.AVAILABLE, posture = FoldPosture.FLAT)
private val FoldFolded = FoldDisplayState(DisplayCapability.UNAVAILABLE, DisplayCapability.AVAILABLE)

/** The Displays pane on the capture screen, unfolded, teleprompter chosen (use `--device inner`). */
@Composable
fun FoldMenuPane() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO),
    CameraSettings(histogramEnabled = true, subjectDisplay = SubjectDisplaySettings(mode = SubjectDisplayMode.TELEPROMPTER, prompterText = SUBJECT_SCRIPT)),
    CaptureInitialPane.DISPLAYS, foldState = FoldUnfolded)

/** The Displays pane with the teleprompter chosen and its split screen with the camera on (use `--device inner`). */
@Composable
fun FoldMenuPaneSplit() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO),
    CameraSettings(histogramEnabled = true, subjectDisplay = SubjectDisplaySettings(mode = SubjectDisplayMode.TELEPROMPTER, prompterText = SUBJECT_SCRIPT,
        splitPreviewModes = setOf(SubjectDisplayMode.TELEPROMPTER))),
    CaptureInitialPane.DISPLAYS, foldState = FoldUnfolded)

/** The Displays pane folded: the reason replaces the start action, self recording stays offered (a sheet on `phone`). */
@Composable
fun FoldMenuFolded() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO), initialPane = CaptureInitialPane.DISPLAYS, foldState = FoldFolded)

/** The fold menu as the settings hub shows it, with the inner-screen options, unfolded. */
@Composable
fun FoldMenu() = DriverTheme(forceDark = true) {
    var settings by remember { mutableStateOf(CameraSettings()) }
    CompositionLocalProvider(LocalFoldDisplayStateWithoutCoordinator provides FoldUnfolded) {
        Box(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            FoldDisplaySettings(driverCaptureState(CaptureMode.VIDEO), settings, { settings = it })
        }
    }
}

/** Rear-screen self recording: the minimal camera interface on the cover (use `--device cover`). */
@Composable
fun SelfCapture() = DriverTheme(forceDark = true) {
    var settings by remember { mutableStateOf(CameraSettings(subjectDisplay = SubjectDisplaySettings(selfTimerSeconds = 3))) }
    SelfCaptureChrome(driverCaptureState(CaptureMode.VIDEO), binder = null, settings = settings, onSettingsChanged = { settings = it }, onOpenSettings = {})
}

/** Self recording while a take runs at 01:23. */
@Composable
fun SelfCaptureRecording() = DriverTheme(forceDark = true) {
    var settings by remember { mutableStateOf(CameraSettings()) }
    SelfCaptureChrome(subjectRecording(), binder = null, settings = settings, onSettingsChanged = { settings = it }, onOpenSettings = {})
}

/** The audio settings with the permission granted, the built-in microphone and a USB lavalier offered as inputs. */
@Composable
fun SettingsAudioInputs() = AudioInputSettings(
    CameraSettings(audioInputKey = com.librestatic.opencinecam.media.audio.AudioInputKey(22, "USB-C Lavalier", "card=2;device=0")),
)

/** The remembered USB microphone is unplugged: the chip reads "(disconnected)". */
@Composable
fun SettingsAudioInputMissing() = AudioInputSettings(
    CameraSettings(audioInputKey = com.librestatic.opencinecam.media.audio.AudioInputKey(22, "RØDE Wireless GO II RX", "card=3;device=0")),
)

@Composable
private fun AudioInputSettings(initial: CameraSettings) = DriverTheme {
    val depths = listOf(com.librestatic.opencinecam.media.audio.AudioBitDepth.PCM_16, com.librestatic.opencinecam.media.audio.AudioBitDepth.PCM_24)
    val rates = listOf(44_100, 48_000)
    val capabilities = com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities(
        permissionGranted = true,
        formats = com.librestatic.opencinecam.media.audio.AudioOutputFormat.entries,
        sampleRates = rates, bitDepths = depths, channelCounts = listOf(1, 2),
        pcmConfigurations = rates.flatMap { r -> depths.flatMap { d -> listOf(1, 2).map { c -> com.librestatic.opencinecam.media.audio.PcmAudioConfiguration(r, d, c) } } },
        aacSampleRates = rates, aacChannelCounts = listOf(1, 2), aacBitratesKbps = listOf(128, 192, 256),
        sources = com.librestatic.opencinecam.media.audio.AudioSourceSelection.entries,
        inputs = listOf(
            com.librestatic.opencinecam.media.audio.SelectableAudioInput(7, "", 15, rates, listOf(1, 2), emptyList(), address = "bottom"),
            com.librestatic.opencinecam.media.audio.SelectableAudioInput(42, "USB-C Lavalier", 22, listOf(48_000, 96_000), listOf(1), emptyList(),
                address = "card=2;device=0"),
        ),
        noiseSuppressorAvailable = true, automaticGainControlAvailable = true, acousticEchoCancelerAvailable = true,
    )
    var settings by remember { mutableStateOf(initial.normalizedFor(capabilities)) }
    SettingsScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING, audioCapabilities = capabilities),
        settings, audioPermissionGranted = true,
        onRequestAudioPermission = {}, onOpenAbout = {}, onSettingsChange = { settings = it.normalizedFor(capabilities) })
}

/** The network drive page (PLAN-071 §8 route 3), stopped: the switch off, the originals hidden and the instructions below. */
@Composable
fun NetworkDrive() = NetworkDriveStoppedScreen(showOriginals = false)

/** The same with the original `.ocraw` files shown (`network-drive-show-originals` on). */
@Composable
fun NetworkDriveOriginals() = NetworkDriveStoppedScreen(showOriginals = true)

@Composable
private fun NetworkDriveStoppedScreen(showOriginals: Boolean) = DriverTheme {
    var originals by remember { mutableStateOf(showOriginals) }
    com.librestatic.opencinecam.share.NetworkDriveContent(
        state = com.librestatic.opencinecam.share.NetworkShareState.Stopped(),
        takeActive = false,
        showOriginals = originals,
        onEnabledChange = {},
        onShowOriginalsChange = { originals = it },
        onCopy = { _, _, _ -> },
        onBack = {},
    )
}

/** The network drive running on Wi-Fi and the hotspot, with fake addresses; the password hidden, as it opens. */
@Composable
fun NetworkDriveRunning() = NetworkDriveRunningScreen(revealed = false)

/** The same with the fake password revealed, grouped in fours. */
@Composable
fun NetworkDriveRunningRevealed() = NetworkDriveRunningScreen(revealed = true)

@Composable
private fun NetworkDriveRunningScreen(revealed: Boolean) = DriverTheme {
    com.librestatic.opencinecam.share.NetworkDriveContent(
        state = com.librestatic.opencinecam.share.NetworkShareState.Running(
            urls = listOf("http://192.168.1.23:8642/", "http://192.168.43.1:8642/"),
            user = "occ",
            password = "k7pq2m9xwe4hjtna",
        ),
        takeActive = false,
        showOriginals = false,
        onEnabledChange = {},
        onShowOriginalsChange = {},
        onCopy = { _, _, _ -> },
        onBack = {},
        initialPasswordRevealed = revealed,
    )
}

/** A 4:3 test chart: a gradient under a 12x9 grid with a ring in the middle, so zoom and pan read at a glance. */
private fun zoomChart(): Bitmap {
    val width = 1200
    val height = 900
    val bitmap = gradientThumbnail(7).let { Bitmap.createScaledBitmap(it, width, height, true) }
    val canvas = Canvas(bitmap)
    val line = Paint().apply { color = android.graphics.Color.WHITE; strokeWidth = 3f; isAntiAlias = true }
    for (i in 0..12) canvas.drawLine(i * 100f, 0f, i * 100f, height.toFloat(), line)
    for (j in 0..9) canvas.drawLine(0f, j * 100f, width.toFloat(), j * 100f, line)
    val ring = Paint().apply { color = android.graphics.Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 12f; isAntiAlias = true }
    canvas.drawCircle(width / 2f, height / 2f, 160f, ring)
    val dot = Paint().apply { color = android.graphics.Color.RED; isAntiAlias = true }
    canvas.drawCircle(300f, 200f, 40f, dot)
    return bitmap
}

/**
 * The review's photo stage in full screen ([PlaybackStageArea], which the dialog draws; the dialog itself is
 * a window screenshots leave out and opens real sessions). Pinch with two `pointerInput` pointers on
 * `media-playback-zoom-area`, drag with one while zoomed, double tap to toggle 2.5x; a single tap counts in
 * the readout (`media-playback-zoom-readout`), as it would show or hide the full-screen chrome.
 */
@Composable
fun PlaybackPhotoZoom() = DriverTheme(forceDark = true) {
    val chart = remember { zoomChart() }
    val density = LocalDensity.current
    val zoom = remember { MediaZoomState(density) }
    val scope = rememberCoroutineScope()
    var taps by remember { mutableIntStateOf(0) }
    PlaybackStageArea(Modifier.fillMaxSize().testTag("media-playback-zoom-area"),
        artifact(9001, "IMG_20261104_101500.jpg", "image/jpeg", 2_400_000, 1_793_000_000), video = false, photo = true,
        aspect = chart.width.toFloat() / chart.height, state = PlaybackObservation(phase = PlaybackPhase.PAUSED, bitmap = chart),
        shown = chart, poster = null, fullscreen = true, zoom = zoom, zoomScope = scope, zoomEnabled = true, surfaceZoom = false,
        onTap = { taps++ }, onSurfaceView = {}, onSurfaceCreated = {}, onSurfaceChanged = { _, _, _ -> }, onSurfaceDestroyed = {}) {
        Text("scale=%.2f x=%.0f y=%.0f taps=%d".format(zoom.pan.scale.value, zoom.pan.offsetX.value, zoom.pan.offsetY.value, taps),
            Modifier.align(Alignment.TopStart).padding(8.dp).testTag("media-playback-zoom-readout"), color = Color.White)
    }
}
