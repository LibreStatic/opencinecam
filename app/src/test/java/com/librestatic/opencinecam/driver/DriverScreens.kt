/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.driver

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
import com.librestatic.opencinecam.AdaptiveCaptureChrome
import com.librestatic.opencinecam.AdaptiveWindow
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.OperatorPreferences
import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureInitialPane
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.CaptureScopeVisibility
import com.librestatic.opencinecam.CodecBadge
import com.librestatic.opencinecam.CompositionGridMode
import com.librestatic.opencinecam.GalleryFacts
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
import com.librestatic.opencinecam.ui.theme.AppTheme
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import com.librestatic.opencinecam.ui.theme.OpenCineCamTheme
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

/** Capture chrome while a video take is recording at 01:23. */
@Composable
fun CaptureRecording() = CaptureChrome(
    driverCaptureState(CaptureMode.VIDEO, CameraUiPhase.RECORDING).copy(recordingElapsedMs = 83_000L),
)

/** Capture chrome in video mode; start with `--device landscape` for the side rails. */
@Composable
fun CaptureLandscape() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO))

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

/** The monitoring toggles pane, with every scope switched on. */
@Composable
fun CaptureMonitor() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO), driverScopeSettings(), CaptureInitialPane.MONITOR)

/** Waveform, vectorscope and false colour switched on, with live analysis, and the histogram. */
@Composable
fun CaptureScopes() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO), driverScopeSettings())

/** The same scopes hidden with H: their keys read off, and pressing one shows them again. */
@Composable
fun CaptureScopesHidden() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO), driverScopeSettings(), scopesHidden = true)

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
    SettingsScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING), CameraSettings(), audioPermissionGranted = false,
        onRequestAudioPermission = {}, onOpenAbout = {}, onSettingsChange = {})
}

/** The settings hub while recording: changes that need a camera restart are locked. */
@Composable
fun SettingsRecording() = DriverTheme {
    SettingsScreen(CameraUiState(phase = CameraUiPhase.RECORDING, settingsPending = true), CameraSettings(),
        audioPermissionGranted = true, onRequestAudioPermission = {}, onOpenAbout = {}, onSettingsChange = {})
}

/** The media catalog with a mix of video, photo and audio takes; take 1's proxy is ready, take 2's is being made. */
@Composable
fun Gallery() = GalleryWith(FakeCatalog(SampleTakes))

/** The media catalog with take 1's details open: beside the grid, or as a sheet where there is no room. */
@Composable
fun GalleryInspector() = GalleryWith(FakeCatalog(SampleTakes), initialSelection = "1")

/** Take 7's details: a plain recording whose codec comes from probing the file, not from its sidecar. */
@Composable
fun GalleryInspectorProbed() = GalleryWith(FakeCatalog(SampleTakes), initialSelection = "7")

/** The media catalog with no takes yet. */
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
            )
        }
    }
}

@Composable
internal fun GalleryWith(source: MediaCatalogSource, initialSelection: String? = null) = DriverTheme {
    MediaCatalogContent(GallerySettings(), onSettings = {}, source = source, onShare = {}, onDelete = {},
        onRename = {}, onReview = {}, onProxy = {}, onProxyCatalog = {}, initialSelection = initialSelection, onOpen = {})
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
}

/** A take with what MediaStore, its sidecars, the proxy queue and a probe of its file would say about it. */
internal class SampleTake(val take: LocalMediaTake, val facts: GalleryFacts? = null, val encoding: LocalMediaEncoding? = null,
    val proxy: TakeProxyState = TakeProxyState.NONE, val probed: CodecBadge? = null)

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

internal val SampleTakes = listOf(
    SampleTake(take(1, "OCC_TAKE_A001_S12_T03.mp4", "video/mp4", LocalMediaKind.VIDEO, 1_480_000_000L,
        ProductionSlateSettings(project = "Night Market", camera = "A", scene = "12", reel = "A001", takeNumber = 3, goodTake = true), log = true),
        GalleryFacts(durationMs = 83_000L, width = 3840, height = 2160), LocalMediaEncoding("video/hevc", "Main10"), TakeProxyState.READY),
    SampleTake(take(2, "OCC_TAKE_A001_S12_T02.mp4", "video/mp4", LocalMediaKind.VIDEO, 912_000_000L,
        ProductionSlateSettings(project = "Night Market", camera = "A", scene = "12", reel = "A001", takeNumber = 2), log = true),
        GalleryFacts(durationMs = 51_000L, width = 1920, height = 1080), LocalMediaEncoding("video/avc", "AVC_8"), TakeProxyState.MAKING),
    SampleTake(take(3, "OCC_20260930_181522.dng", "image/x-adobe-dng", LocalMediaKind.PHOTO, 24_600_000L, slate = null)),
    SampleTake(take(4, "OCC_20260930_181410.jpg", "image/jpeg", LocalMediaKind.PHOTO, 6_200_000L, slate = null,
        status = LocalMediaRelationStatus.LEGACY)),
    SampleTake(take(5, "OCC_TAKE_A001_S11_T01_room-tone.m4a", "audio/mp4", LocalMediaKind.AUDIO, 3_100_000L,
        ProductionSlateSettings(project = "Night Market", scene = "11", takeNumber = 1)), GalleryFacts(durationMs = 62_000L)),
    SampleTake(take(6, "OCC_TAKE_A001_S11_T02_wild.wav", "audio/wav", LocalMediaKind.AUDIO, 21_800_000L,
        ProductionSlateSettings(project = "Night Market", scene = "11", takeNumber = 2)), GalleryFacts(durationMs = 75_000L),
        LocalMediaEncoding(audioContainer = "WAV", audioSamples = "PCM_24")),
    SampleTake(take(7, "OCC_TAKE_A001_S10_T04.mp4", "video/mp4", LocalMediaKind.VIDEO, 640_000_000L,
        ProductionSlateSettings(project = "Night Market", camera = "A", scene = "10", reel = "A001", takeNumber = 4)),
        GalleryFacts(durationMs = 42_000L, width = 1920, height = 1080), probed = CodecBadge("H.264", 8)),
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
fun SubjectStatusRecording() = Subject(subjectRecording(), SubjectDisplaySettings(operatorCue = "Mirá a cámara"))

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
        interviewQuestions = listOf("¿Cómo empezaste a filmar?", "¿Qué equipo usás?", "¿Qué consejo darías?")),
    SubjectSessionCues(interviewIndex = 1))

/** Exterior display, digital slate. */
@Composable
fun SubjectSlate() = Subject(driverCaptureState(CaptureMode.VIDEO), SubjectDisplaySettings(mode = SubjectDisplayMode.SLATE))

private val FoldUnfolded = FoldDisplayState(DisplayCapability.AVAILABLE, DisplayCapability.AVAILABLE, posture = FoldPosture.FLAT)
private val FoldFolded = FoldDisplayState(DisplayCapability.UNAVAILABLE, DisplayCapability.AVAILABLE)

/** The Displays pane on the capture screen, unfolded, teleprompter chosen (use `--device inner`). */
@Composable
fun FoldMenuPane() = CaptureChrome(driverCaptureState(CaptureMode.VIDEO),
    CameraSettings(histogramEnabled = true, subjectDisplay = SubjectDisplaySettings(mode = SubjectDisplayMode.TELEPROMPTER, prompterText = SUBJECT_SCRIPT)),
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
fun SettingsAudioInputs() = DriverTheme {
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
            com.librestatic.opencinecam.media.audio.SelectableAudioInput(7, "Built-in microphone", 15, rates, listOf(1, 2), emptyList()),
            com.librestatic.opencinecam.media.audio.SelectableAudioInput(42, "USB-C Lavalier", 22, listOf(48_000, 96_000), listOf(1), emptyList()),
        ),
        noiseSuppressorAvailable = true, automaticGainControlAvailable = true, acousticEchoCancelerAvailable = true,
    )
    SettingsScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING, audioCapabilities = capabilities),
        CameraSettings(audioInputDeviceId = 42), audioPermissionGranted = true,
        onRequestAudioPermission = {}, onOpenAbout = {}, onSettingsChange = {})
}
