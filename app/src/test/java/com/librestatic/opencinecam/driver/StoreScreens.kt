/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.driver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Surface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.AdaptiveCaptureChrome
import com.librestatic.opencinecam.AdaptiveWindow
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureFrameReserve
import com.librestatic.opencinecam.CaptureInitialPane
import com.librestatic.opencinecam.CaptureLayoutFamily
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.CaptureScopeVisibility
import com.librestatic.opencinecam.CompositionGridMode
import com.librestatic.opencinecam.HistogramMode
import com.librestatic.opencinecam.HorizonRollMath
import com.librestatic.opencinecam.LocalAdaptiveWindow
import com.librestatic.opencinecam.LocalOperatorActions
import com.librestatic.opencinecam.MonitoringOverlay
import com.librestatic.opencinecam.CAPTURE_RAIL_WIDTH_DP
import com.librestatic.opencinecam.INSPECTOR_WIDTH_DP
import com.librestatic.opencinecam.SIDE_COLUMN_WIDTH_DP
import com.librestatic.opencinecam.SLIM_TOP_BAR_HEIGHT_DP
import com.librestatic.opencinecam.STACKED_TOP_BAR_HEIGHT_DP
import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.camera.MonitoringOptions
import com.librestatic.opencinecam.camera.MonitoringSignalDomain
import com.librestatic.opencinecam.camera.analyzeMonitoringRgb
import com.librestatic.opencinecam.camera.detectFocusEdges
import com.librestatic.opencinecam.camera.monitoringDisplayPoint
import com.librestatic.opencinecam.fittedPreviewViewport
import com.librestatic.opencinecam.previewDisplayRatio
import com.librestatic.opencinecam.reservedPreviewViewport
import com.librestatic.opencinecam.captureLayoutFamily
import com.librestatic.opencinecam.storage.LocalMediaArtifact
import java.io.File

/*
 * Play Store captures (store/play). Same real production composables as the other driver screens,
 * but the viewfinder is a CC0 photo from store/play/raw/media (gitignored, never committed): the
 * photo is drawn where CaptureSurface puts the SurfaceView, and a downsampled copy of the same
 * photo is the fake analysis frame, so the production zebra, peaking, false colour, histogram,
 * waveform and vectorscope code computes what it shows from it. Test source set only.
 *
 * Photos come from OCC_STORE_MEDIA (a directory), else <repo>/store/play/raw/media.
 */

private fun mediaDir(): File {
    System.getenv("OCC_STORE_MEDIA")?.let { return File(it) }
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        File(dir, "store/play/raw/media").takeIf { it.isDirectory }?.let { return it }
        dir = dir.parentFile
    }
    error("Set OCC_STORE_MEDIA to the directory with the NN-scene.jpg photos")
}

private fun loadPhoto(number: Int, maxWidth: Int): Bitmap {
    val file = File(mediaDir(), "$number-scene.jpg")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }
    val options = BitmapFactory.Options().apply { inSampleSize = (bounds.outWidth / maxWidth).coerceAtLeast(1) }
    return requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "cannot decode $file" }
}

/** The photo, cropped from the centre to [ratio] (width / height) and scaled to [width] pixels. */
private fun cropped(photo: Bitmap, ratio: Float, width: Int, focusX: Float = .5f, focusY: Float = .4f): Bitmap {
    val photoRatio = photo.width.toFloat() / photo.height
    val cw = if (photoRatio > ratio) (photo.height * ratio).toInt() else photo.width
    val ch = if (photoRatio > ratio) photo.height else (photo.width / ratio).toInt()
    val piece = Bitmap.createBitmap(photo, ((photo.width - cw) * focusX).toInt(), ((photo.height - ch) * focusY).toInt(), cw, ch)
    return Bitmap.createScaledBitmap(piece, width, (width / ratio).toInt(), true)
}

/** What the camera's analysis stream would hold for the shown [frame] (a display-upright picture). */
private class PhotoAnalysis(frame: Bitmap, options: MonitoringOptions, sensorOrientation: Int, displayDegrees: Int) {
    val scopes: com.librestatic.opencinecam.camera.MonitoringScopeFrame
    val luma: List<Float>; val red: List<Float>; val green: List<Float>; val blue: List<Float>
    val zebraCells: List<Boolean>
    val peaking: com.librestatic.opencinecam.camera.FocusPeakingMask
    val peakingBytes: ByteArray

    init {
        val domain = MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR
        /** The analysis raster is the 16:9 sensor stream: each pixel looks up the display point it lands on. */
        fun raster(w: Int, h: Int): ByteArray {
            val rgb = ByteArray(w * h * 3)
            for (y in 0 until h) for (x in 0 until w) {
                val (px, py) = monitoringDisplayPoint((x + .5f) / w, (y + .5f) / h, domain, sensorOrientation, displayDegrees, false)
                val c = frame.getPixel((px * frame.width).toInt().coerceIn(0, frame.width - 1), (py * frame.height).toInt().coerceIn(0, frame.height - 1))
                val i = (y * w + x) * 3
                rgb[i] = (c shr 16).toByte(); rgb[i + 1] = (c shr 8).toByte(); rgb[i + 2] = c.toByte()
            }
            return rgb
        }
        fun lumaOf(rgb: ByteArray, i: Int) = (54 * (rgb[i].toInt() and 255) + 183 * (rgb[i + 1].toInt() and 255) + 19 * (rgb[i + 2].toInt() and 255) + 128) shr 8
        val w = 320; val h = 180
        val rgb = raster(w, h)
        scopes = analyzeMonitoringRgb(w, h, rgb, options, domain)
        val bins = 64
        val hist = Array(4) { IntArray(bins) }
        val hits = IntArray(144); val counts = IntArray(144)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            val l = lumaOf(rgb, i)
            hist[0][l * bins / 256]++; hist[1][(rgb[i].toInt() and 255) * bins / 256]++
            hist[2][(rgb[i + 1].toInt() and 255) * bins / 256]++; hist[3][(rgb[i + 2].toInt() and 255) * bins / 256]++
            val cell = (y * 9 / h).coerceIn(0, 8) * 16 + (x * 16 / w).coerceIn(0, 15)
            counts[cell]++
            if (l * 100 >= options.zebraHighPercent * 255 || options.zebraShadowEnabled && l * 100 <= options.zebraLowPercent * 255) hits[cell]++
        }
        val total = (w * h).toFloat()
        luma = hist[0].map { it / total }; red = hist[1].map { it / total }; green = hist[2].map { it / total }; blue = hist[3].map { it / total }
        zebraCells = counts.indices.map { counts[it] > 0 && hits[it].toFloat() / counts[it] >= .20f }
        val pw = 640; val ph = 360
        val prgb = raster(pw, ph)
        peakingBytes = ByteArray(pw * ph) { lumaOf(prgb, it * 3).toByte() }
        peaking = detectFocusEdges(peakingBytes, pw, ph, options.peakingThreshold, domain)
    }
}

/**
 * The capture chrome as CaptureSurface hosts it, with [photo] as the viewfinder. The frame sits
 * where production puts it: below the top bar and above the deck in the stacked layouts, between
 * the rails in the side layout and the inspector, and out of the way of an open pane or the scopes.
 */
@Composable
private fun StoreCapture(
    photo: Int,
    focusX: Float,
    state: CameraUiState,
    settings: CameraSettings,
    initialPane: CaptureInitialPane? = null,
) = DriverTheme(forceDark = true) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val window = AdaptiveWindow(maxWidth.value, maxHeight.value, false)
        val displayDegrees = HorizonRollMath.surfaceRotationDegrees(LocalView.current.display?.rotation ?: Surface.ROTATION_0)
        // Whatever rotation Robolectric reports, mount the sensor so the picture stands upright.
        val sensorOrientation = ((if (window.landscape) 0 else 90) + displayDegrees) % 360
        val camera = (state.cameras.first()).copy(sensorOrientation = sensorOrientation)
        val ratio = previewDisplayRatio(1920, 1080, 1f, sensorOrientation, displayDegrees)
        var current by remember { mutableStateOf(settings.copy(compositionGridEnabled = false)) }
        val scopes = rememberSaveable(saver = CaptureScopeVisibility.Saver) { CaptureScopeVisibility() }
        val big = remember(photo) { loadPhoto(photo, 1600) }
        val frameBitmap = remember(photo, ratio) { cropped(big, ratio, 1200, focusX) }
        val analysis = remember(photo, ratio, current.monitoring.zebraHighPercent, current.monitoring.falseColorBlackPercent, current.monitoring.waveformEnabled, current.monitoring.vectorscopeEnabled, current.monitoring.falseColorEnabled) {
            PhotoAnalysis(frameBitmap, current.monitoring, sensorOrientation, displayDegrees)
        }
        var stamp by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
        androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { stamp = android.os.SystemClock.elapsedRealtime(); kotlinx.coroutines.delay(250) } }
        val live = state.copy(audioMonitoringActive = true,
            cameras = listOf(camera), histogram = analysis.luma, redHistogram = analysis.red, greenHistogram = analysis.green,
            blueHistogram = analysis.blue, monitoringScopes = analysis.scopes, zebraCells = analysis.zebraCells,
            focusPeakingMask = analysis.peaking, analysisUpdatedAtMs = stamp,
            analysisIntervalMs = current.monitoring.periodMs,
            audioLevels = com.librestatic.opencinecam.camera.AudioLevelSnapshot(listOf(
                com.librestatic.opencinecam.camera.AudioChannelLevel(-14f, -24f), com.librestatic.opencinecam.camera.AudioChannelLevel(-17f, -27f)), false, stamp),
        )
        val actions = driverOperatorActions(live, current, scopes) { current = it }
        var deckPx by remember { mutableStateOf(0) }
        var reserve by remember { mutableStateOf(CaptureFrameReserve.None) }
        val family = captureLayoutFamily(maxWidth.value, maxHeight.value)
        val stacked = family == CaptureLayoutFamily.COMPACT_PORTRAIT || family == CaptureLayoutFamily.STACKED
        val topBar = if (family == CaptureLayoutFamily.COMPACT_PORTRAIT) SLIM_TOP_BAR_HEIGHT_DP.dp else STACKED_TOP_BAR_HEIGHT_DP.dp
        val pane = when {
            family == CaptureLayoutFamily.SIDE_RAILS -> Modifier.fillMaxSize().padding(start = CAPTURE_RAIL_WIDTH_DP.dp, end = SIDE_COLUMN_WIDTH_DP.dp)
            family == CaptureLayoutFamily.INSPECTOR -> Modifier.fillMaxSize().padding(start = CAPTURE_RAIL_WIDTH_DP.dp, end = INSPECTOR_WIDTH_DP.dp)
            stacked -> Modifier.fillMaxSize().padding(top = topBar, bottom = with(density) { deckPx.toDp() })
            else -> Modifier.fillMaxSize()
        }
        BoxWithConstraints(pane.clipToBounds()) {
            val frame = reservedPreviewViewport(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), 0f, 0f, reserve, ratio)
            with(density) {
                Box(Modifier.absoluteOffset { IntOffset(frame.left.toInt(), frame.top.toInt()) }.size(frame.width.toDp(), frame.height.toDp())) {
                    Image(frameBitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    val scopesConcealed = actions.scopes?.concealed == true
                    MonitoringOverlay(
                        state = live,
                        options = if (scopesConcealed) current.monitoring.copy(falseColorEnabled = false) else current.monitoring,
                        sourceWidth = 1920, sourceHeight = 1080, squeezeFactor = 1f,
                        showZebra = current.zebraEnabled, showPeaking = current.peakingEnabled, showHistogram = current.histogramEnabled,
                        histogramMode = current.histogramMode, showGrid = current.compositionGridEnabled, gridMode = current.compositionGridMode,
                        showHorizon = false, drawHistogram = false, modifier = Modifier.fillMaxSize(), drawScopesPanel = false,
                    )
                }
            }
        }
        CompositionLocalProvider(LocalAdaptiveWindow provides window, LocalOperatorActions provides actions) {
            AdaptiveCaptureChrome(
                state = live, binder = null, settings = current, landscape = window.landscape, previewAspectRatio = ratio,
                onStackedDeckHeight = { deckPx = it }, onFrameReserve = { reserve = it },
                zebra = current.zebraEnabled, peaking = current.peakingEnabled, histogram = current.histogramEnabled,
                histogramMode = current.histogramMode, showGrid = current.compositionGridEnabled, gridMode = current.compositionGridMode,
                showHorizon = false,
                onToggleZebra = { current = current.copy(zebraEnabled = !current.zebraEnabled) },
                onTogglePeaking = { current = current.copy(peakingEnabled = !current.peakingEnabled) },
                onToggleHistogram = { current = current.copy(histogramEnabled = !current.histogramEnabled) },
                onCycleHistogramMode = {
                    current = current.copy(histogramMode = if (current.histogramMode == HistogramMode.RGB) HistogramMode.LUMA else HistogramMode.RGB)
                },
                onToggleGrid = { current = current.copy(compositionGridEnabled = !current.compositionGridEnabled) },
                onCycleGridMode = {}, onToggleHorizon = {}, onSettingsChanged = { current = it },
                onOpenMedia = {}, onOpenSettings = {}, initialPane = initialPane,
            )
        }
    }
}

/** 1: manual ISO, shutter, white balance and focus. */
@Composable
fun StoreManual() = StoreCapture(18, .5f, driverCaptureState(CaptureMode.VIDEO), CameraSettings(histogramEnabled = false), CaptureInitialPane.WHITE_BALANCE)

/** 2: zebra and focus peaking on the picture, the monitoring toggles open. */
@Composable
fun StoreOverlays() = StoreCapture(17, .8f, driverCaptureState(CaptureMode.VIDEO),
    CameraSettings(zebraEnabled = true, peakingEnabled = true, monitoring = MonitoringOptions(zebraHighPercent = 82, peakingThreshold = 85)), CaptureInitialPane.MONITOR)

/** 2b: false colour over the picture. */
@Composable
fun StoreFalseColor() = StoreCapture(17, .8f, driverCaptureState(CaptureMode.VIDEO),
    CameraSettings(monitoring = MonitoringOptions(falseColorEnabled = true)), CaptureInitialPane.MONITOR)

/** 3: histogram, waveform and vectorscope. */
@Composable
fun StoreScopes() = StoreCapture(22, .75f, driverCaptureState(CaptureMode.VIDEO),
    CameraSettings(histogramEnabled = true, monitoring = MonitoringOptions(waveformEnabled = true, vectorscopeEnabled = true)))

/** 4: LOG mode with the LUT view assist, the modes pane open. */
@Composable
fun StoreLog() = StoreCapture(18, .5f,
    driverCaptureState(CaptureMode.LOG).let { it.copy(modeGates = it.modeGates + mapOf(CaptureMode.LOG to ModeGateState.AVAILABLE, CaptureMode.HLG to ModeGateState.AVAILABLE)) },
    CameraSettings(logViewAssistEnabled = true))

/** 7: the media catalog with the sample photos as thumbnails. */
@Composable
fun StoreGallery() = GalleryWith(storeCatalog())

@Composable
fun StoreGalleryInspector() = GalleryWith(storeCatalog(), initialSelection = "1")

private val ThumbnailPhotos = listOf(17, 22, 24, 20, 21, 18, 16)

@Composable
private fun storeCatalog() = remember {
    val cache = HashMap<Int, Bitmap>()
    FakeCatalog(SampleTakes) { artifact: LocalMediaArtifact ->
        val id = artifact.uri.substringAfterLast('/').toInt()
        cache.getOrPut(id) { cropped(loadPhoto(ThumbnailPhotos[(id - 1) % ThumbnailPhotos.size], 900), 16f / 9f, 640) }
    }
}

/** A USB-class microphone's capabilities: AAC, WAV and FLAC up to 192 kHz / 24-bit stereo. */
private fun storeAudio(): com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities {
    val depths = listOf(com.librestatic.opencinecam.media.audio.AudioBitDepth.PCM_16, com.librestatic.opencinecam.media.audio.AudioBitDepth.PCM_24)
    val rates = listOf(44_100, 48_000, 88_200, 96_000, 192_000)
    return com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities(
        permissionGranted = true,
        formats = com.librestatic.opencinecam.media.audio.AudioOutputFormat.entries,
        sampleRates = rates, bitDepths = depths, channelCounts = listOf(1, 2),
        pcmConfigurations = rates.flatMap { r -> depths.flatMap { d -> listOf(1, 2).map { c -> com.librestatic.opencinecam.media.audio.PcmAudioConfiguration(r, d, c) } } },
        aacSampleRates = listOf(44_100, 48_000), aacChannelCounts = listOf(1, 2), aacBitratesKbps = listOf(128, 192, 256, 320),
        sources = com.librestatic.opencinecam.media.audio.AudioSourceSelection.entries, inputs = emptyList(),
        noiseSuppressorAvailable = true, automaticGainControlAvailable = true, acousticEchoCancelerAvailable = true,
    )
}

/** The settings hub with the microphone permission granted and a lossless-capable microphone, so the audio page shows its controls. */
@Composable
fun StoreSettings() = DriverTheme {
    com.librestatic.opencinecam.SettingsScreen(
        CameraUiState(phase = com.librestatic.opencinecam.CameraUiPhase.PREVIEWING, audioCapabilities = storeAudio()),
        CameraSettings(audioOutputFormat = com.librestatic.opencinecam.media.audio.AudioOutputFormat.WAV_PCM, audioSampleRateHz = 48_000,
            audioBitDepth = com.librestatic.opencinecam.media.audio.AudioBitDepth.PCM_24, audioChannels = 2),
        audioPermissionGranted = true, onRequestAudioPermission = {}, onOpenAbout = {}, onSettingsChange = {})
}
