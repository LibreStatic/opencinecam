/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.driver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.librestatic.opencinecam.AboutScreen
import com.librestatic.opencinecam.AdaptiveCaptureChrome
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.CompositionGridMode
import com.librestatic.opencinecam.GalleryFacts
import com.librestatic.opencinecam.GallerySettings
import com.librestatic.opencinecam.HistogramMode
import com.librestatic.opencinecam.MediaCatalogContent
import com.librestatic.opencinecam.MediaCatalogSource
import com.librestatic.opencinecam.OnboardingScreen
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.SettingsScreen
import com.librestatic.opencinecam.SplashHandoff
import com.librestatic.opencinecam.TakeProxyState
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

/** The media catalog with a mix of video, photo and audio takes; take 1's proxy is ready, take 2's is being made. */
@Composable
fun Gallery() = GalleryWith(FakeCatalog(SampleTakes))

/** The media catalog with take 1's details open: beside the grid, or as a sheet where there is no room. */
@Composable
fun GalleryInspector() = GalleryWith(FakeCatalog(SampleTakes), initialSelection = "1")

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
private fun GalleryWith(source: MediaCatalogSource, initialSelection: String? = null) = DriverTheme {
    MediaCatalogContent(GallerySettings(), onSettings = {}, source = source, onShare = {}, onDelete = {},
        onRename = {}, onReview = {}, onProxy = {}, onProxyCatalog = {}, initialSelection = initialSelection, onOpen = {})
}

private class FakeCatalog(private val takes: List<SampleTake>) : MediaCatalogSource {
    override suspend fun page(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int): LocalMediaPage {
        val shown = takes.filter { query.isBlank() || it.take.primary.name.contains(query, ignoreCase = true) }
        return LocalMediaPage(shown.map { it.take }, next = null,
            encodings = shown.mapNotNull { sample -> sample.encoding?.let { sample.take.id to it } }.toMap())
    }

    override suspend fun thumbnail(artifact: LocalMediaArtifact): Bitmap? =
        if (artifact.mimeType.startsWith("audio/")) null else gradientThumbnail(artifact.name.hashCode())

    override suspend fun facts(artifact: LocalMediaArtifact): GalleryFacts? = takes.firstOrNull { it.take.primary == artifact }?.facts

    override fun proxyStates(ids: Set<String>): Flow<Map<String, TakeProxyState>> =
        flowOf(takes.filter { it.take.id in ids && it.proxy != TakeProxyState.NONE }.associate { it.take.id to it.proxy })
}

/** A take with what MediaStore, its sidecars and the proxy queue would say about it. */
private class SampleTake(val take: LocalMediaTake, val facts: GalleryFacts? = null, val encoding: LocalMediaEncoding? = null,
    val proxy: TakeProxyState = TakeProxyState.NONE)

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

private val SampleTakes = listOf(
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
)
