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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import com.librestatic.opencinecam.AboutScreen
import com.librestatic.opencinecam.AdaptiveCaptureChrome
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.CompositionGridMode
import com.librestatic.opencinecam.GallerySettings
import com.librestatic.opencinecam.HistogramMode
import com.librestatic.opencinecam.HorizonRollMath
import com.librestatic.opencinecam.MediaCatalogContent
import com.librestatic.opencinecam.MediaCatalogSource
import com.librestatic.opencinecam.MonitoringOverlay
import com.librestatic.opencinecam.OnboardingScreen
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.SettingsScreen
import com.librestatic.opencinecam.SplashHandoff
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

/*
 * Zero-argument Compose Driver entry points (AGENTS.md). Each one feeds fake state to the real
 * production composable; none of this is on the app's runtime classpath. Select one with
 * `tools/compose-driver.sh start <Name>`, which expands to
 * `com.librestatic.opencinecam.driver.DriverScreensKt.<Name>`.
 *
 * The theme follows `--theme cine|you` (default cine); `--device` and `--night` set the viewport.
 */

/** Portrait capture chrome over a black stand-in for the viewfinder, photo mode, previewing. */
@Composable
fun CapturePortrait() = CaptureChrome(landscape = false, state = CameraUiState(phase = CameraUiPhase.PREVIEWING))

/** Portrait capture chrome while a video take is recording. */
@Composable
fun CaptureRecording() = CaptureChrome(
    landscape = false,
    state = CameraUiState(phase = CameraUiPhase.RECORDING, selectedMode = CaptureMode.VIDEO, recordingElapsedMs = 83_000L),
)

/** Landscape capture chrome in video mode; start with `--device landscape`. */
@Composable
fun CaptureLandscape() = CaptureChrome(
    landscape = true,
    state = CameraUiState(phase = CameraUiPhase.PREVIEWING, selectedMode = CaptureMode.VIDEO),
)

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

/** The media catalog with a mix of video, photo and audio takes. */
@Composable
fun Gallery() = GalleryWith(FakeCatalog(SampleTakes))

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
private fun DriverTheme(forceDark: Boolean = false, content: @Composable () -> Unit) =
    OpenCineCamTheme(driverTheme(), forceDark = forceDark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
    }

@Composable
private fun CaptureChrome(landscape: Boolean, state: CameraUiState) = DriverTheme(forceDark = true) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AdaptiveCaptureChrome(
            state = state,
            binder = null,
            settings = CameraSettings(),
            landscape = landscape,
            zebra = false,
            peaking = false,
            histogram = true,
            histogramMode = HistogramMode.RGB,
            showGrid = false,
            gridMode = CompositionGridMode.THIRDS,
            showHorizon = false,
            onToggleZebra = {},
            onTogglePeaking = {},
            onToggleHistogram = {},
            onCycleHistogramMode = {},
            onToggleGrid = {},
            onCycleGridMode = {},
            onToggleHorizon = {},
            onSettingsChanged = {},
            onOpenMedia = {},
            onOpenSettings = {},
        )
    }
}

@Composable
private fun GalleryWith(source: MediaCatalogSource) = DriverTheme {
    MediaCatalogContent(GallerySettings(), onSettings = {}, source = source, onShare = {}, onDelete = {},
        onRename = {}, onReview = {}, onProxy = {}, onProxyCatalog = {}, onOpen = {})
}

private class FakeCatalog(private val takes: List<LocalMediaTake>) : MediaCatalogSource {
    override suspend fun page(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int) =
        LocalMediaPage(takes.filter { query.isBlank() || it.primary.name.contains(query, ignoreCase = true) }, next = null)

    override suspend fun thumbnail(artifact: LocalMediaArtifact): Bitmap? =
        if (artifact.mimeType.startsWith("audio/")) null else gradientThumbnail(artifact.name.hashCode())
}

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

private fun take(id: Int, name: String, mime: String, kind: LocalMediaKind, sizeBytes: Long, slate: ProductionSlateSettings?,
    status: LocalMediaRelationStatus = LocalMediaRelationStatus.DECLARED): LocalMediaTake {
    val primary = artifact(id, name, mime, sizeBytes, 1_790_000_000L - id * 3_600L)
    val sidecar = artifact(id + 1_000, name.substringBeforeLast('.') + ".json", "application/json", 4_096L, primary.modifiedSeconds)
    return LocalMediaTake(id.toString(), primary, listOf(primary), listOf(sidecar), kind, slate, status)
}

private val SampleTakes = listOf(
    take(1, "OCC_TAKE_A001_S12_T03.mp4", "video/mp4", LocalMediaKind.VIDEO, 1_480_000_000L,
        ProductionSlateSettings(project = "Night Market", camera = "A", scene = "12", reel = "A001", takeNumber = 3, goodTake = true)),
    take(2, "OCC_TAKE_A001_S12_T02.mp4", "video/mp4", LocalMediaKind.VIDEO, 912_000_000L,
        ProductionSlateSettings(project = "Night Market", camera = "A", scene = "12", reel = "A001", takeNumber = 2)),
    take(3, "OCC_20260930_181522.dng", "image/x-adobe-dng", LocalMediaKind.PHOTO, 24_600_000L, slate = null),
    take(4, "OCC_20260930_181410.jpg", "image/jpeg", LocalMediaKind.PHOTO, 6_200_000L, slate = null,
        status = LocalMediaRelationStatus.LEGACY),
    take(5, "OCC_TAKE_A001_S11_T01_room-tone.m4a", "audio/mp4", LocalMediaKind.AUDIO, 3_100_000L,
        ProductionSlateSettings(project = "Night Market", scene = "11", takeNumber = 1)),
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
