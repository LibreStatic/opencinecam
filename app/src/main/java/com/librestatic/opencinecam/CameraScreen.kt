/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCharacteristics
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.tooling.preview.Preview
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.storage.LocalMediaItem
import com.librestatic.opencinecam.storage.LocalMediaRepository
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.max

private val Graphite = Color(0xFF0B0D0E)
private val Panel = Color(0xD914181A)
private val Amber = Color(0xFFFFB300)
private val VerifiedCyan = Color(0xFF45D6E8)
private val RecordRed = Color(0xFFE23A3A)
private val Muted = Color(0xFF9CA6AA)

private enum class AppSection { CAPTURE, MEDIA, SETTINGS }
private enum class ControlDial { RESOLUTION, FPS, ISO, SHUTTER, FOCUS, WB }

@Composable
fun CameraRootScreen() {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
    }
    val binder = rememberCaptureServiceBinder(permissionGranted)
    var audioPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        audioPermissionGranted = it
        binder?.refreshAudioCapabilities()
    }
    val fallbackState = remember { MutableStateFlow(CameraUiState()) }
    val stateFlow = binder?.cameraStates ?: fallbackState
    val state by stateFlow.collectAsStateWithLifecycle()
    val settingsStore = remember(context) { CameraSettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var section by rememberSaveable { mutableStateOf(AppSection.CAPTURE) }

    LaunchedEffect(binder, settings) {
        binder?.updateSettings(settings)
    }
    LaunchedEffect(state.audioCapabilities) {
        state.audioCapabilities?.let { capabilities ->
            val normalized = settings.normalizedFor(capabilities)
            if (normalized != settings) {
                settings = normalized
                settingsStore.save(normalized)
            }
        }
    }

    if (!permissionGranted) {
        PermissionScreen { permissionLauncher.launch(Manifest.permission.CAMERA) }
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Graphite)) {
        if (section == AppSection.CAPTURE) {
            CaptureSurface(
                state = state,
                binder = binder,
                settings = settings,
                onSettingsChanged = { updated ->
                    settings = updated
                    settingsStore.save(updated)
                },
                onOpenMedia = { section = AppSection.MEDIA },
                onOpenSettings = { section = AppSection.SETTINGS },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (section) {
                        AppSection.MEDIA -> MediaScreen()
                        AppSection.SETTINGS -> SettingsScreen(
                    state = state,
                    settings = settings,
                    audioPermissionGranted = audioPermissionGranted,
                    onRequestAudioPermission = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        ) { updated ->
                            settings = updated
                            settingsStore.save(updated)
                        }
                        AppSection.CAPTURE -> Unit
                    }
                }
                NavigationBar(section) { section = it }
            }
        }
    }
}

@Composable
private fun rememberCaptureServiceBinder(enabled: Boolean): CaptureService.LocalBinder? {
    val context = LocalContext.current
    var binder by remember { mutableStateOf<CaptureService.LocalBinder?>(null) }
    DisposableEffect(context, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as? CaptureService.LocalBinder
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }
        context.bindService(Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
        onDispose {
            binder?.detachPreview()
            runCatching { context.unbindService(connection) }
            binder = null
        }
    }
    return binder
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Graphite).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.camera_permission_title), color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.camera_permission_body), color = Muted)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black)) {
            Text(stringResource(R.string.grant_camera))
        }
    }
}

@Composable
private fun CaptureSurface(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    onOpenMedia: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var zebra by remember { mutableStateOf(false) }
    var peaking by remember { mutableStateOf(false) }
    var histogram by rememberSaveable { mutableStateOf(settings.histogramEnabled) }
    var histogramMode by rememberSaveable { mutableStateOf(settings.histogramMode) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        LaunchedEffect(binder, widthPx, heightPx) {
            if (binder != null && widthPx > 0 && heightPx > 0 && state.cameras.isEmpty()) binder.prepare(widthPx, heightPx)
        }

        val descriptor = state.descriptor
        if (descriptor != null && binder != null) {
            val landscape = maxWidth > maxHeight
            val previewStreamSize = if (state.selectedMode == CaptureMode.LOG) {
                state.activeLogProfile?.size ?: descriptor.preferredLogProfile?.size ?: descriptor.previewSize
            } else if (state.selectedMode in CameraUiState.videoProfileModes) {
                state.activeVideoProfile?.size ?: descriptor.previewSize
            } else {
                descriptor.previewSize
            }
            val ratio = previewStreamSize.width.toFloat() / previewStreamSize.height
            val displayRatio = if (landscape) ratio else 1f / ratio
            PreviewSurfaceView(
                descriptor.cameraId,
                previewStreamSize.width,
                previewStreamSize.height,
                displayRatio,
                state.selectedMode == CaptureMode.LOG,
                state.targetFps,
                descriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT && state.selectedMode != CaptureMode.LOG,
                widthPx,
                heightPx,
                binder,
                Modifier.align(Alignment.Center),
            )
            MonitoringOverlay(
                state = state,
                showZebra = zebra,
                showPeaking = peaking,
                showHistogram = histogram,
                histogramMode = histogramMode,
                landscape = landscape,
                // aspectRatio must receive the unconstrained Box bounds. Applying fillMaxWidth
                // first can force a too-wide landscape view and make Compose violate the ratio
                // when the remaining height is smaller than width / ratio.
                modifier = Modifier.align(Alignment.Center).aspectRatio(displayRatio),
            )
        }

        AdaptiveCaptureChrome(
            state = state,
            binder = binder,
            settings = settings,
            landscape = maxWidth > maxHeight,
            zebra = zebra,
            peaking = peaking,
            histogram = histogram,
            histogramMode = histogramMode,
            onToggleZebra = { zebra = !zebra },
            onTogglePeaking = { peaking = !peaking },
            onToggleHistogram = {
                val updated = !histogram
                histogram = updated
                onSettingsChanged(settings.copy(histogramEnabled = updated, histogramMode = histogramMode))
            },
            onCycleHistogramMode = {
                val updated = if (histogramMode == HistogramMode.RGB) HistogramMode.LUMA else HistogramMode.RGB
                histogramMode = updated
                onSettingsChanged(settings.copy(histogramEnabled = histogram, histogramMode = updated))
            },
            onOpenMedia = onOpenMedia,
            onOpenSettings = onOpenSettings,
        )

        if (state.phase == CameraUiPhase.PREPARING || state.phase == CameraUiPhase.OPENING || state.phase == CameraUiPhase.READY) {
            Text(
                stringResource(R.string.camera_loading),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Panel, RoundedCornerShape(8.dp)).padding(12.dp),
            )
        }
        if (state.phase == CameraUiPhase.ERROR) {
            Column(
                modifier = Modifier.align(Alignment.Center).background(Panel, RoundedCornerShape(12.dp)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.camera_error), color = RecordRed, fontWeight = FontWeight.Bold)
                Text(state.message.orEmpty(), color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { binder?.recoverPreview(widthPx, heightPx) }) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

@Composable
private fun MonitorToggle(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(5.dp)).background(if (enabled) Amber else Color(0xFF303638)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun MonitoringOverlay(
    state: CameraUiState,
    showZebra: Boolean,
    showPeaking: Boolean,
    showHistogram: Boolean,
    histogramMode: HistogramMode,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    var analysisClockMs by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            analysisClockMs = android.os.SystemClock.elapsedRealtime()
        }
    }
    BoxWithConstraints(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val sensorColumns = 16
            val sensorRows = 9
            val rotation = state.descriptor?.sensorOrientation ?: 0
            val rotated = size.height > size.width && rotation in setOf(90, 270)
            val columns = if (rotated) sensorRows else sensorColumns
            val rows = if (rotated) sensorColumns else sensorRows
            val cellWidth = size.width / columns
            val cellHeight = size.height / rows
            fun displayCell(index: Int): Pair<Int, Int> {
                val x = index % sensorColumns
                val y = index / sensorColumns
                return when {
                    rotated && rotation == 90 -> (sensorRows - 1 - y) to x
                    rotated && rotation == 270 -> y to (sensorColumns - 1 - x)
                    else -> x to y
                }
            }
            if (showZebra) state.zebraCells.forEachIndexed { index, active ->
                if (active) displayCell(index).let { (x, y) -> drawRect(Amber.copy(alpha = .22f), topLeft = androidx.compose.ui.geometry.Offset(x * cellWidth, y * cellHeight), size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight)) }
            }
            if (showPeaking) state.focusCells.forEachIndexed { index, active ->
                if (active) displayCell(index).let { (x, y) -> drawRect(VerifiedCyan.copy(alpha = .85f), topLeft = androidx.compose.ui.geometry.Offset(x * cellWidth, y * cellHeight), size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight), style = Stroke(width = 2.dp.toPx())) }
            }
        }
        val analysisFresh = state.analysisUpdatedAtMs > 0L && analysisClockMs - state.analysisUpdatedAtMs <= 1_000L
        if (showHistogram && analysisFresh && state.histogram.isNotEmpty()) {
            val graphWidth = maxWidth * .28f
            val graphHeight = maxHeight * .09f
            // The preview starts behind the chrome. Keep the scope below the top bar and
            // preview HUD instead of always drawing it at (12, 12), where it collided with
            // the microphone meter in both orientations.
            val desiredTop = if (landscape) 140.dp else 92.dp
            val graphTop = desiredTop.coerceAtMost((maxHeight - graphHeight - 12.dp).coerceAtLeast(12.dp))
            Canvas(
                Modifier
                    .offset(x = 12.dp, y = graphTop)
                    .width(graphWidth)
                    .height(graphHeight)
                    .testTag("histogram-graph"),
            ) {
                drawRect(Color.Black.copy(alpha = .55f))
                if (histogramMode == HistogramMode.LUMA) {
                    val peak = state.histogram.maxOrNull()?.coerceAtLeast(.001f) ?: 1f
                    state.histogram.forEachIndexed { index, value ->
                        val width = size.width / state.histogram.size
                        val height = size.height * value / peak
                        drawRect(Color.White.copy(alpha = .85f), androidx.compose.ui.geometry.Offset(index * width, size.height - height), androidx.compose.ui.geometry.Size((width - 1f).coerceAtLeast(.5f), height))
                    }
                } else {
                    val channels = listOf(
                        state.redHistogram to Color.Red,
                        state.greenHistogram to Color.Green,
                        state.blueHistogram to Color.Blue,
                    ).filter { it.first.isNotEmpty() }
                    val peak = channels.flatMap { it.first }.maxOrNull()?.coerceAtLeast(.001f) ?: 1f
                    channels.forEach { (values, color) ->
                        val path = Path()
                        values.forEachIndexed { index, value ->
                            val x = index * size.width / (values.size - 1).coerceAtLeast(1)
                            val y = size.height - size.height * value / peak
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color.copy(alpha = .9f), style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSurfaceView(
    cameraId: String,
    bufferWidth: Int,
    bufferHeight: Int,
    displayRatio: Float,
    layoutSizedBuffer: Boolean,
    targetFps: Int,
    mirrorViewfinder: Boolean,
    displayWidthPx: Int,
    displayHeightPx: Int,
    binder: CaptureService.LocalBinder,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = remember(context) {
        SurfaceView(context)
    }
    val currentTargetFps by rememberUpdatedState(targetFps)
    fun voteForViewfinderRate() {
        val display = view.display ?: return
        val mode = display.supportedModes
            .filter { it.refreshRate <= currentTargetFps.toFloat() + 0.5f }
            .maxByOrNull { it.refreshRate }
            ?: display.supportedModes.minByOrNull { kotlin.math.abs(it.refreshRate - currentTargetFps) }
            ?: return
        (context as? Activity)?.window?.let { window ->
            window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && view.holder.surface.isValid) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.holder.surface.setFrameRate(
                    mode.refreshRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                view.holder.surface.setFrameRate(mode.refreshRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }
    LaunchedEffect(view, bufferWidth, bufferHeight, layoutSizedBuffer) {
        if (layoutSizedBuffer) {
            // LOG is rendered by EGL, not directly by Camera2. Its output buffer must follow
            // the rotated view dimensions; a fixed landscape buffer makes SurfaceFlinger
            // stretch or retain a stale transform after an orientation change.
            view.holder.setSizeFromLayout()
        } else {
            view.holder.setFixedSize(bufferWidth, bufferHeight)
        }
    }
    LaunchedEffect(view, targetFps, displayWidthPx, displayHeightPx) {
        voteForViewfinderRate()
    }
    LaunchedEffect(view, binder, cameraId, displayWidthPx, displayHeightPx) {
        val surface = view.holder.surface
        if (displayWidthPx > 0 && displayHeightPx > 0 && surface?.isValid == true) {
            binder.attachPreview(surface, rotationDegrees(view.display?.rotation ?: Surface.ROTATION_0))
        }
    }
    DisposableEffect(view, binder) {
        var lastWidth = -1
        var lastHeight = -1
        var lastRotation = Int.MIN_VALUE

        fun attachIfChanged(holder: SurfaceHolder, width: Int, height: Int, force: Boolean = false) {
            if (!holder.surface.isValid) return
            val viewWidth = view.width.takeIf { it > 0 } ?: width
            val viewHeight = view.height.takeIf { it > 0 } ?: height
            val rotation = rotationDegrees(view.display?.rotation ?: Surface.ROTATION_0)
            if (!force && viewWidth == lastWidth && viewHeight == lastHeight && rotation == lastRotation) return
            lastWidth = viewWidth
            lastHeight = viewHeight
            lastRotation = rotation
            binder.attachPreview(holder.surface, rotation)
        }

        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                voteForViewfinderRate()
                attachIfChanged(holder, view.width, view.height, force = true)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                binder.detachPreview(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                attachIfChanged(holder, width, height)
            }
        }
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != view.display?.displayId) return
                // 0°↔180° and 90°↔270° keep the same layout dimensions, so neither Compose's
                // size keys nor SurfaceHolder.surfaceChanged are guaranteed to fire. Observe the
                // display itself to refresh Camera2/LOG orientation in those cases.
                view.post {
                    voteForViewfinderRate()
                    attachIfChanged(view.holder, view.width, view.height)
                }
            }
        }
        view.holder.addCallback(callback)
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        if (view.holder.surface?.isValid == true) callback.surfaceCreated(view.holder)
        onDispose {
            (context as? Activity)?.window?.let { window ->
                window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
            }
            displayManager.unregisterDisplayListener(displayListener)
            view.holder.removeCallback(callback)
            binder.detachPreview(view.holder.surface)
        }
    }
    AndroidView(
        factory = { view },
        update = { it.scaleX = if (mirrorViewfinder) -1f else 1f },
        // Let aspectRatio choose the largest rectangle that fits both width and height. A
        // preceding fillMaxWidth would lock the landscape width and squeeze the EGL output.
        modifier = modifier.aspectRatio(displayRatio),
    )
}

@Composable
internal fun AdaptiveCaptureChrome(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    landscape: Boolean,
    zebra: Boolean,
    peaking: Boolean,
    histogram: Boolean,
    histogramMode: HistogramMode,
    onToggleZebra: () -> Unit,
    onTogglePeaking: () -> Unit,
    onToggleHistogram: () -> Unit,
    onCycleHistogramMode: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var manualControl by remember { mutableStateOf<ControlDial?>(null) }
    var showModeGrid by remember { mutableStateOf(false) }
    var showMonitoring by remember { mutableStateOf(false) }
    val recording = state.phase == CameraUiPhase.RECORDING
    var manualReveal by remember { mutableStateOf(false) }
    // While recording, the full console auto-hides to keep a clean viewfinder. A tap reveals
    // it again; it re-hides after a short idle period unless the user keeps interacting.
    LaunchedEffect(recording, manualReveal) {
        if (recording && manualReveal) {
            delay(4_000)
            manualReveal = false
        }
    }
    val chromeVisible = !recording || manualReveal

    BackHandler(enabled = manualControl != null || showModeGrid || showMonitoring) {
        manualControl = null
        showModeGrid = false
        showMonitoring = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .pointerInput(recording) {
                detectTapGestures {
                    if (recording) manualReveal = true
                }
            },
    ) {
        // SurfaceView owns a native surface and can consume taps before a pointerInput modifier
        // on its Compose parent sees them. Keep an explicit Compose hit target above the
        // viewfinder while the console is hidden; controls composed below are later siblings and
        // therefore retain priority (notably the compact Stop button).
        if (recording && !chromeVisible) {
            Box(
                Modifier
                    .matchParentSize()
                    .testTag("recording-reveal-surface")
                    .clickable { manualReveal = true },
            )
        }

        if (chromeVisible) {
            CaptureTopBar(
                state = state,
                binder = binder,
                onOpenMedia = onOpenMedia,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            PreviewStatusHud(
                state = state,
                binder = binder,
                settings = settings,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 62.dp),
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (landscape) {
                LandscapeControlDeck(
                    state = state,
                    binder = binder,
                    selectorStyle = settings.modeSelectorStyle,
                    settings = settings,
                    onControl = { manualControl = it; showModeGrid = false; showMonitoring = false },
                    onShowModes = { showModeGrid = !showModeGrid; manualControl = null; showMonitoring = false },
                    onShowMonitoring = { showMonitoring = !showMonitoring; manualControl = null; showModeGrid = false },
                )
            } else {
                PortraitControlDeck(
                    state = state,
                    binder = binder,
                    selectorStyle = settings.modeSelectorStyle,
                    settings = settings,
                    onControl = { manualControl = it; showModeGrid = false; showMonitoring = false },
                    onShowModes = { showModeGrid = !showModeGrid; manualControl = null; showMonitoring = false },
                    onShowMonitoring = { showMonitoring = !showMonitoring; manualControl = null; showModeGrid = false },
                )
            }
        }

        if (recording) {
            val recordingHudTop = when {
                !chromeVisible -> 0.dp
                state.selectedMode == CaptureMode.LOG -> 82.dp
                else -> 56.dp
            }
            RecordingOverlay(
                state = state,
                binder = binder,
                showStop = !chromeVisible,
                zebra = zebra,
                peaking = peaking,
                histogram = histogram,
                histogramMode = histogramMode,
                onToggleZebra = onToggleZebra,
                onTogglePeaking = onTogglePeaking,
                onToggleHistogram = onToggleHistogram,
                onCycleHistogramMode = onCycleHistogramMode,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = recordingHudTop),
            )
        }

        if (chromeVisible) manualControl?.let { control ->
            ContextualPanel(landscape, onDismiss = { manualControl = null }) {
                ManualControlDial(control, state, binder) { manualControl = null }
            }
        }
        if (chromeVisible && showModeGrid) {
            ContextualPanel(landscape, onDismiss = { showModeGrid = false }) {
                ModeButtonGrid(state, binder) { showModeGrid = false }
            }
        }
        if (chromeVisible && showMonitoring) {
            ContextualPanel(landscape, onDismiss = { showMonitoring = false }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MONITOR", color = Amber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonitorToggle("Z", zebra, onToggleZebra)
                        MonitorToggle("P", peaking, onTogglePeaking)
                        MonitorToggle("H", histogram, onToggleHistogram)
                        MonitorToggle(if (histogramMode == HistogramMode.RGB) "RGB" else "Y", true, onCycleHistogramMode)
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureTopBar(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    onOpenMedia: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val switchDescription = stringResource(R.string.camera_switch)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Panel)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MediaThumbnailAction(onOpenMedia)
        Text(
            "${state.selectedCameraId ?: "—"} · ${state.phase.name}",
            color = VerifiedCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.cameras.size > 1) {
            TopAction("↻", switchDescription) {
                val index = state.cameras.indexOfFirst { it.cameraId == state.selectedCameraId }
                binder?.selectCamera(state.cameras[(index + 1).mod(state.cameras.size)].cameraId)
            }
        }
        TopAction("⚙", stringResource(R.string.settings_tab), onOpenSettings)
    }
}

@Composable
private fun TopAction(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MediaThumbnailAction(onClick: () -> Unit) {
    val context = LocalContext.current
    val description = stringResource(R.string.media_tab)
    var thumbnail by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(Unit) {
        thumbnail = withContext(Dispatchers.IO) {
            LocalMediaRepository(context.applicationContext).recent().firstOrNull()?.thumbnail
        }
    }
    Box(
        Modifier
            .size(48.dp)
            .testTag("media-action")
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF303638))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        thumbnail?.let {
            Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        } ?: Text("▣", color = Color.White, fontSize = 18.sp)
    }
}

@Composable
private fun LogSourceBadge(state: CameraUiState, settings: CameraSettings, modifier: Modifier = Modifier) {
    if (state.selectedMode != CaptureMode.LOG) return
    val sourcePath = state.activeLogProfile?.sourcePath
    val text = when {
        sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP && settings.logViewAssistEnabled ->
            "OCLOG2 HFR · ISP SDR · VIEW ASSIST"
        sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP ->
            "OCLOG2 HFR · ISP-DERIVED SDR · MAIN10"
        settings.logViewAssistEnabled -> "OCLOG2 · HLG10 · VIEW ASSIST REC.709"
        else -> "OCLOG2 · HLG-DERIVED 10-BIT · FLAT MONITOR"
    }
    Text(
        text,
        color = if (sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) Amber else VerifiedCyan,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .testTag("log-source-badge")
            .background(Panel, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun PreviewStatusHud(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    modifier: Modifier = Modifier,
) {
    val showAudio = settings.audioEnabled && state.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.LOG)
    val showLogSource = state.selectedMode == CaptureMode.LOG
    if (!showAudio && !showLogSource) return

    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        // A centered badge and a start-aligned meter overlap on phone-width portrait
        // displays. Preserve the centered landscape treatment only when both controls fit;
        // otherwise let the badge consume the measured space remaining beside the meter.
        if (showAudio && showLogSource && maxWidth < 600.dp) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                AudioMeterHud(state, binder)
                LogSourceBadge(
                    state,
                    settings,
                    Modifier.weight(1f).wrapContentWidth(Alignment.End),
                )
            }
        } else {
            if (showAudio) AudioMeterHud(state, binder, Modifier.align(Alignment.TopStart))
            if (showLogSource) LogSourceBadge(state, settings, Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun PortraitControlDeck(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    selectorStyle: ModeSelectorStyle,
    settings: CameraSettings,
    onControl: (ControlDial) -> Unit,
    onShowModes: () -> Unit,
    onShowMonitoring: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(Panel).padding(top = 6.dp, bottom = 8.dp)) {
        QuickControls(state, onControl, landscape = true)
        Spacer(Modifier.height(8.dp))
        if (selectorStyle == ModeSelectorStyle.DIAL) ModeDial(state, binder, compact = false)
        else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SelectedModeButton(state, onShowModes) }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CaptureButton(state, binder, settings, 72.dp)
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                TopAction("\u25eb", "Monitoring tools", onShowMonitoring)
            }
        }
        CaptureStatus(state)
    }
}

@Composable
private fun LandscapeControlDeck(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    selectorStyle: ModeSelectorStyle,
    settings: CameraSettings,
    onControl: (ControlDial) -> Unit,
    onShowModes: () -> Unit,
    onShowMonitoring: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 6.dp),
    ) {
        QuickControls(state, onControl, landscape = false)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (selectorStyle == ModeSelectorStyle.DIAL) ModeDial(state, binder, compact = false)
                else SelectedModeButton(state, onShowModes)
            }
            TopAction("◫", "Monitoring tools", onShowMonitoring)
            CaptureButton(state, binder, settings, 62.dp)
        }
        Spacer(Modifier.height(6.dp))
        StatusInfoBar(state, settings)
    }
}

@Composable
private fun QuickControls(state: CameraUiState, onControl: (ControlDial) -> Unit, landscape: Boolean) {
    val controls = buildList {
        if (state.selectedMode in CameraUiState.resolutionProfileModes) add(ControlDial.RESOLUTION)
        add(ControlDial.FPS)
        add(ControlDial.SHUTTER)
        add(ControlDial.ISO)
        add(ControlDial.WB)
        add(ControlDial.FOCUS)
    }
    if (landscape) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            controls.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { control -> QuickControlButton(control, state, onControl, Modifier.weight(1f)) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            controls.forEach { control -> QuickControlButton(control, state, onControl, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun QuickControlButton(
    control: ControlDial,
    state: CameraUiState,
    onControl: (ControlDial) -> Unit,
    modifier: Modifier = Modifier,
) {
    val constrained = state.activeVideoProfile?.constrainedHighSpeed == true || state.activeLogProfile?.constrainedHighSpeed == true
    val enabled = !constrained || control in setOf(ControlDial.RESOLUTION, ControlDial.FPS)
    val value = when (control) {
        ControlDial.RESOLUTION -> "${state.targetVideoWidth}×${state.targetVideoHeight}"
        ControlDial.FPS -> state.targetFps.toString()
        ControlDial.SHUTTER -> if (constrained) "AUTO·HS" else state.exposureTimeNs?.let(::formatShutter) ?: "AUTO"
        ControlDial.ISO -> if (constrained) "AUTO·HS" else state.sensitivityIso?.toString() ?: "AUTO"
        ControlDial.WB -> if (constrained) "AUTO·HS" else awbLabel(state.requestedAwbMode)
        ControlDial.FOCUS -> if (constrained) "AUTO·HS" else state.focusDistanceDiopters?.let { "%.1fD".format(it) } ?: "AUTO"
    }
    Column(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1B2023))
            .border(1.dp, Color(0xFF41494C), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onControl(control) }
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(value, color = if (enabled) Color.White else Muted, fontSize = 13.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(control.name.take(4), color = if (enabled) Muted else Color(0xFF626A6D), fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1)
    }
}

@Composable
private fun ModeDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    compact: Boolean = false,
) {
    val modes = CaptureMode.entries
    val selectedIndex = modes.indexOf(state.selectedMode).coerceAtLeast(0)
    val haptics = LocalHapticFeedback.current
    fun selectable(mode: CaptureMode): Boolean {
        val gate = state.modeGates.getValue(mode)
        return gate == ModeGateState.AVAILABLE || gate == ModeGateState.CANDIDATE
    }

    // A mode wheel: a linear carousel with a fixed center selection indicator. Several
    // neighbors stay visible on both sides, the active mode snaps to the marker, drag
    // settles on the nearest mode and tapping any visible mode selects it directly.
    if (compact) {
        // Vertical wheel for the right panel in landscape.
        val itemHeight = 28.dp
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
        val fling = rememberSnapFlingBehavior(listState, SnapPosition.Center)
        LaunchedEffect(selectedIndex) {
            if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != selectedIndex) {
                if (listState.layoutInfo.totalItemsCount == 0) return@LaunchedEffect
                listState.scrollToItem(selectedIndex)
                listState.animateScrollToItem(selectedIndex)
            }
        }
        LaunchedEffect(listState.isScrollInProgress) {
            if (!listState.isScrollInProgress && listState.layoutInfo.totalItemsCount > 0) {
                val idx = listState.firstVisibleItemIndex.coerceIn(0, modes.lastIndex)
                if (idx != selectedIndex && modes[idx] != state.selectedMode && selectable(modes[idx])) {
                    binder?.selectMode(modes[idx])
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
        Column(
            Modifier
                .width(150.dp)
                .height(itemHeight * 5)
                .semantics { contentDescription = "Mode dial: ${state.selectedMode.name}" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    flingBehavior = fling,
                    contentPadding = PaddingValues(vertical = itemHeight * 2),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(modes.size) { index ->
                        val mode = modes[index]
                        val isSelected = index == selectedIndex
                        val enabled = selectable(mode)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                                .clickable(enabled = enabled && !isSelected) { binder?.selectMode(mode) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                modeLabel(mode),
                                color = when {
                                    isSelected -> Amber
                                    enabled -> Color.White
                                    else -> Muted
                                },
                                fontSize = if (isSelected) 13.sp else 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                SelectionCursor(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(itemHeight),
                )
            }
            ModePositionDots(modes, selectedIndex)
        }
    } else {
        // Horizontal mode wheel, full width so several modes stay visible with room to breathe.
        // Item width adapts to the measured container width; the selection cursor stays fixed
        // at the exact center via SnapPosition.Center and symmetric content padding.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Mode dial: ${state.selectedMode.name}" },
        ) {
            val totalWidth = maxWidth
            val itemWidth = (totalWidth / 3.3f).coerceIn(88.dp, 150.dp)
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
            val fling = rememberSnapFlingBehavior(listState, SnapPosition.Center)
            LaunchedEffect(selectedIndex, totalWidth) {
                if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != selectedIndex) {
                    if (listState.layoutInfo.totalItemsCount == 0) return@LaunchedEffect
                    listState.scrollToItem(selectedIndex)
                    listState.animateScrollToItem(selectedIndex)
                }
            }
            LaunchedEffect(listState.isScrollInProgress) {
                if (!listState.isScrollInProgress && listState.layoutInfo.totalItemsCount > 0) {
                    val idx = listState.firstVisibleItemIndex.coerceIn(0, modes.lastIndex)
                    if (idx != selectedIndex && modes[idx] != state.selectedMode && selectable(modes[idx])) {
                        binder?.selectMode(modes[idx])
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(38.dp)) {
                    LazyRow(
                        state = listState,
                        flingBehavior = fling,
                        contentPadding = PaddingValues(horizontal = (totalWidth - itemWidth) / 2),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(modes.size) { index ->
                            val mode = modes[index]
                            val isSelected = index == selectedIndex
                            val enabled = selectable(mode)
                            Box(
                                Modifier
                                    .width(itemWidth)
                                    .height(38.dp)
                                    .clickable(enabled = enabled && !isSelected) { binder?.selectMode(mode) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    modeLabel(mode),
                                    color = when {
                                        isSelected -> Amber
                                        enabled -> Color.White
                                        else -> Muted
                                    },
                                    fontSize = if (isSelected) 16.sp else 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    SelectionCursor(
                        Modifier
                            .align(Alignment.Center)
                            .width(itemWidth)
                            .height(38.dp),
                    )
                }
                ModePositionDots(modes, selectedIndex)
            }
        }
    }
}

@Composable
private fun SelectionCursor(modifier: Modifier = Modifier) {
    Box(
        modifier.border(1.dp, Amber, RoundedCornerShape(8.dp)),
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(22.dp)
                .height(3.dp)
                .background(Amber, RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)),
        )
    }
}

@Composable
private fun ModePositionDots(modes: List<CaptureMode>, selectedIndex: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp),
    ) {
        modes.forEachIndexed { index, _ ->
            Box(
                Modifier
                    .size(if (index == selectedIndex) 5.dp else 3.dp)
                    .clip(CircleShape)
                    .background(if (index == selectedIndex) Amber else Color(0xFF4A5154)),
            )
        }
    }
}

@Composable
private fun SelectedModeButton(state: CameraUiState, onClick: () -> Unit) {
    Column(
        Modifier
            .widthIn(min = 112.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF202528))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("MODE", color = Muted, fontSize = 8.sp)
        Text(modeLabel(state.selectedMode), color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ModeButtonGrid(state: CameraUiState, binder: CaptureService.LocalBinder?, onSelected: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("MODOS", color = Amber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        CaptureMode.entries.chunked(2).forEach { rowModes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowModes.forEach { mode ->
                    val gate = state.modeGates.getValue(mode)
                    val enabled = gate == ModeGateState.AVAILABLE || gate == ModeGateState.CANDIDATE
                    Column(
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (mode == state.selectedMode) Amber else Color(0xFF303638))
                            .clickable(enabled = enabled) {
                                binder?.selectMode(mode)
                                onSelected()
                            }
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(modeLabel(mode), color = if (mode == state.selectedMode) Color.Black else if (enabled) Color.White else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (gate != ModeGateState.AVAILABLE) Text(gateLabel(gate), color = if (mode == state.selectedMode) Color.Black else gateColor(gate), fontSize = 8.sp)
                    }
                }
                if (rowModes.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ContextualPanel(landscape: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(if (landscape) Modifier.padding(end = 184.dp) else Modifier.padding(bottom = 170.dp))
                .widthIn(min = 260.dp, max = 380.dp)
                .heightIn(max = if (landscape) 280.dp else 440.dp)
                .background(Color(0xF21A1F21), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF4A5154), RoundedCornerShape(10.dp))
                .clickable(enabled = false) { }
                .padding(12.dp),
        ) { content() }
    }
}

@Composable
private fun CaptureButton(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    size: androidx.compose.ui.unit.Dp,
) {
    val context = LocalContext.current
    var showAudioChoice by remember { mutableStateOf(false) }
    val currentBinder by rememberUpdatedState(binder)
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        currentBinder?.refreshAudioCapabilities()
        if (granted) currentBinder?.capturePrimary(audioForThisTake = true)
    }
    if (showAudioChoice) {
        AlertDialog(
            onDismissRequest = { showAudioChoice = false },
            title = { Text("Audio opcional") },
            text = { Text("You can record this take without audio or grant microphone access.") },
            confirmButton = {
                TextButton(onClick = {
                    showAudioChoice = false
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Conceder permiso") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAudioChoice = false
                    binder?.capturePrimary(audioForThisTake = false)
                }) { Text("Record without audio") }
            },
        )
    }
    val recording = state.phase == CameraUiPhase.RECORDING
    Box(
        Modifier
            .size(size)
            .semantics {
                contentDescription = when {
                    state.phase == CameraUiPhase.RECORDING -> "Stop recording"
                    state.selectedMode.isStillMode() -> "Capture photo"
                    else -> "Start recording"
                }
            }
            .border(3.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = state.phase == CameraUiPhase.PREVIEWING || state.phase == CameraUiPhase.SAVED || state.phase == CameraUiPhase.RECORDING) {
                val needsAudioChoice = state.phase != CameraUiPhase.RECORDING &&
                    state.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.LOG) &&
                    settings.audioEnabled &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                if (needsAudioChoice) showAudioChoice = true else binder?.capturePrimary()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            if (recording) {
                Modifier
                    .size(size * .34f)
                    .testTag("recording-stop-glyph")
                    .clip(RoundedCornerShape(3.dp))
                    .background(RecordRed)
            } else {
                Modifier
                    .size(size - 10.dp)
                    .clip(CircleShape)
                    .background(if (state.selectedMode.isStillMode()) Amber else RecordRed)
            },
        )
    }
}

@Composable
private fun StatusInfoBar(state: CameraUiState, settings: CameraSettings) {
    val context = LocalContext.current
    var batteryPercent by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            batteryPercent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            delay(5_000)
        }
    }
    val isStill = state.selectedMode.isStillMode()
    val codec = when {
        state.selectedMode == CaptureMode.LOG -> "H.265 HEVC 10-bit LOG"
        state.selectedMode == CaptureMode.RAW_VIDEO -> "RAW 10-bit"
        isStill -> "--"
        else -> "H.264 AVC"
    }
    val audio = if (settings.audioEnabled) settings.audioOutputFormat.label + " " + (settings.audioSampleRateHz / 1000) + " kHz" else "Audio OFF"
    val bitrate = settings.videoBitrateMbps.toString() + " Mbps"
    val time = if (state.phase == CameraUiPhase.RECORDING) formatDuration(state.recordingElapsedMs)
        else state.availableStorageBytes?.takeIf { it > 0 }?.let { formatDuration(it * 8L * 1000L / (settings.videoBitrateMbps * 1_000_000L)) } ?: "--"
    val free = formatBytes(state.availableStorageBytes) + " libre"
    val battery = batteryPercent?.let { "$it%" } ?: "--"
    val lut = if (state.selectedMode == CaptureMode.LOG) (if (settings.logViewAssistEnabled) "Rec.709" else "Flat") else "--"
    val wb = awbLabel(state.requestedAwbMode)
    val focus = if (state.requestedFocusDiopters != null) "MF" else "AF-C"
    val primary = buildList {
        add(codec)
        if (!isStill) { add(bitrate); add(audio) }
        add(time)
    }.joinToString(" \u00b7 ")
    val secondary = listOf(free, battery, "LUT: $lut", "WB: $wb", "FOCUS: $focus").joinToString(" \u00b7 ")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(primary, color = Color.White, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(secondary, color = Muted, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecordingOverlay(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    showStop: Boolean,
    zebra: Boolean, peaking: Boolean, histogram: Boolean,
    histogramMode: HistogramMode,
    onToggleZebra: () -> Unit, onTogglePeaking: () -> Unit, onToggleHistogram: () -> Unit,
    onCycleHistogramMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMonitors by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.padding(top = 8.dp, start = 10.dp, end = 10.dp).fillMaxWidth()) {
        val compact = maxWidth < 500.dp
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        ) {
            Row(
                Modifier.background(Panel, RoundedCornerShape(8.dp)).padding(horizontal = if (compact) 7.dp else 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(RecordRed))
                Text("REC " + formatDuration(state.recordingElapsedMs), color = Color.White, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold)
                if (!compact && state.recordingWidth != null && state.recordingHeight != null) {
                    Text("${state.recordingWidth}\u00d7${state.recordingHeight}", color = Muted, fontSize = 10.sp)
                }
            }
            AudioMeterHud(state, binder, meterWidth = if (compact) 96.dp else 132.dp)
            Spacer(Modifier.weight(1f))
            if (!compact) TopAction("\u25eb", "Monitoring tools") { showMonitors = !showMonitors }
            if (showStop) {
                Box(
                    Modifier.size(44.dp).semantics { contentDescription = "Stop recording" }.border(2.dp, Color.White, CircleShape).padding(5.dp).clip(CircleShape).clickable { binder?.capturePrimary() },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(16.dp).testTag("recording-stop-glyph").clip(RoundedCornerShape(2.dp)).background(RecordRed)) }
            }
        }
    }
    if (showMonitors) {
        Row(modifier.padding(top = 52.dp, start = 10.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            MonitorToggle("Z", zebra, onToggleZebra)
            MonitorToggle("P", peaking, onTogglePeaking)
            MonitorToggle("H", histogram, onToggleHistogram)
            MonitorToggle(if (histogramMode == HistogramMode.RGB) "RGB" else "Y", true, onCycleHistogramMode)
        }
    }
}

@Composable
private fun AudioMeterHud(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    modifier: Modifier = Modifier,
    meterWidth: androidx.compose.ui.unit.Dp = 132.dp,
) {
    val snapshot = state.audioLevels
    val channelLevels = snapshot?.channels.orEmpty()
    Column(
        modifier
            .width(meterWidth)
            .testTag("audio-meter-hud")
            .background(Panel, RoundedCornerShape(7.dp))
            .clickable(enabled = state.audioClipLatched) { binder?.resetAudioClip() }
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.audioMonitoringActive || state.phase == CameraUiPhase.RECORDING) "MIC" else "MIC —",
                color = if (state.audioMonitoringActive) VerifiedCyan else Muted,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            if (state.audioClipLatched) Text("CLIP", color = RecordRed, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
        val rows = max(1, channelLevels.size)
        repeat(rows) { index ->
            val level = channelLevels.getOrNull(index)
            val label = when {
                channelLevels.size <= 1 -> "M"
                index == 0 -> "L"
                else -> "R"
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = Muted, fontSize = 7.sp, modifier = Modifier.width(8.dp))
                Canvas(Modifier.weight(1f).height(6.dp)) {
                    drawRect(Color(0xFF283033))
                    val peakDb = level?.peakDbfs ?: -60f
                    val rmsDb = level?.rmsDbfs ?: -60f
                    val peakFraction = ((peakDb + 60f) / 60f).coerceIn(0f, 1f)
                    val rmsFraction = ((rmsDb + 60f) / 60f).coerceIn(0f, 1f)
                    val color = when {
                        peakDb >= -3f -> RecordRed
                        peakDb >= -12f -> Amber
                        else -> Color(0xFF46C36F)
                    }
                    drawRect(color, size = androidx.compose.ui.geometry.Size(size.width * peakFraction, size.height))
                    drawLine(
                        Color.White,
                        start = androidx.compose.ui.geometry.Offset(size.width * rmsFraction, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width * rmsFraction, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
        }
    }
}


@Composable
private fun CaptureStatus(state: CameraUiState) {
    val status = when {
        state.phase == CameraUiPhase.RECORDING && state.recordingWidth != null && state.recordingHeight != null ->
            "REC ${state.recordingWidth}×${state.recordingHeight} · ${state.targetFps} fps · ${formatDuration(state.recordingElapsedMs)} · ${formatBytes(state.availableStorageBytes)} free"
        else -> state.message
    } ?: return
    Text(
        status,
        color = if (state.errorCode == null) Color.White else RecordRed,
        fontSize = 10.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}

@Composable
private fun ManualControlDial(
    control: ControlDial,
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    onClose: () -> Unit,
) {
    val descriptor = state.descriptor ?: return
    if (control == ControlDial.RESOLUTION) {
        ResolutionDial(state, binder, onClose)
        return
    }
    if (control == ControlDial.FPS) {
        FpsDial(state, binder, onClose)
        return
    }
    if (control == ControlDial.WB) {
        AwbDial(state.requestedAwbMode, binder, onClose)
        return
    }
    val value = when (control) {
        ControlDial.RESOLUTION -> 0f
        ControlDial.FPS -> 0f
        ControlDial.ISO -> logPosition((state.requestedIso ?: state.sensitivityIso ?: descriptor.sensitivityRange?.lower ?: 100).toDouble(), descriptor.sensitivityRange?.lower?.toDouble() ?: 50.0, descriptor.sensitivityRange?.upper?.toDouble() ?: 6400.0)
        ControlDial.SHUTTER -> logPosition((state.requestedExposureTimeNs ?: state.exposureTimeNs ?: 16_666_667L).toDouble(), descriptor.exposureTimeRangeNs?.lower?.toDouble() ?: 100_000.0, descriptor.exposureTimeRangeNs?.upper?.toDouble() ?: 1_000_000_000.0)
        ControlDial.FOCUS -> ((state.requestedFocusDiopters ?: state.focusDistanceDiopters ?: 0f) / (descriptor.minimumFocusDistance ?: 1f)).coerceIn(0f, 1f)
        ControlDial.WB -> 0f
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(control.name, color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Slider(
            value = value,
            onValueChange = { position ->
                when (control) {
                    ControlDial.RESOLUTION -> Unit
                    ControlDial.FPS -> Unit
                    ControlDial.ISO -> {
                        val iso = logValue(position, descriptor.sensitivityRange?.lower?.toDouble() ?: 50.0, descriptor.sensitivityRange?.upper?.toDouble() ?: 6400.0).toInt()
                        binder?.setManualExposure(iso, state.requestedExposureTimeNs ?: state.exposureTimeNs ?: 16_666_667L)
                    }
                    ControlDial.SHUTTER -> {
                        val exposure = logValue(position, descriptor.exposureTimeRangeNs?.lower?.toDouble() ?: 100_000.0, descriptor.exposureTimeRangeNs?.upper?.toDouble() ?: 1_000_000_000.0).toLong()
                        binder?.setManualExposure(state.requestedIso ?: state.sensitivityIso ?: 100, exposure)
                    }
                    ControlDial.FOCUS -> binder?.setManualFocus(position * (descriptor.minimumFocusDistance ?: 1f))
                    ControlDial.WB -> Unit
                }
            },
            modifier = Modifier.weight(1f).height(28.dp),
        )
        TextButton(onClick = {
            when (control) {
                ControlDial.RESOLUTION -> Unit
                ControlDial.FPS -> Unit
                ControlDial.ISO, ControlDial.SHUTTER -> binder?.setManualExposure(null, null)
                ControlDial.FOCUS -> binder?.setManualFocus(null)
                ControlDial.WB -> binder?.setAwbMode(null)
            }
            onClose()
        }) { Text(stringResource(R.string.auto_value), color = VerifiedCyan, fontSize = 10.sp) }
        TextButton(onClick = onClose) { Text("×", color = Color.White, fontSize = 14.sp) }
    }
}

@Composable
private fun ResolutionDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    onClose: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelHeader("RESOLUCIÓN", onClose)
        state.availableVideoSizes.chunked(2).forEach { sizes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sizes.forEach { (width, height) ->
                    val selected = width == state.targetVideoWidth && height == state.targetVideoHeight
                    val rates = if (state.selectedMode == CaptureMode.LOG) {
                        state.availableLogProfiles.filter { it.size.width == width && it.size.height == height }
                            .map { if (it.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) "${it.fps} HFR/ISP" else it.fps.toString() }
                    } else {
                        state.availableVideoProfiles.filter { it.size.width == width && it.size.height == height }.map { it.fps.toString() }
                    }.distinct()
                    ChoiceTile("${width}×$height\n${rates.joinToString("/")} fps", selected, Modifier.weight(1f)) {
                        binder?.selectVideoResolution(width, height)
                        onClose()
                    }
                }
                if (sizes.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FpsDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    onClose: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelHeader("FPS FIJO", onClose)
        state.availableTargetFps.chunked(4).forEach { rates ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rates.forEach { fps ->
                    val logProfile = state.availableLogProfiles.firstOrNull {
                        state.selectedMode == CaptureMode.LOG && it.size.width == state.targetVideoWidth && it.size.height == state.targetVideoHeight && it.fps == fps
                    }
                    val highSpeed = logProfile?.constrainedHighSpeed == true || state.availableVideoProfiles.any {
                        state.selectedMode != CaptureMode.LOG && it.size.width == state.targetVideoWidth && it.size.height == state.targetVideoHeight && it.fps == fps && it.constrainedHighSpeed
                    }
                    val label = when {
                        logProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP -> "$fps HFR"
                        highSpeed -> "$fps HS"
                        else -> fps.toString()
                    }
                    ChoiceTile(label, fps == state.targetFps, Modifier.weight(1f)) {
                        binder?.selectTargetFps(fps)
                        onClose()
                    }
                }
                repeat(4 - rates.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AwbDial(selected: Int?, binder: CaptureService.LocalBinder?, onClose: () -> Unit) {
    val choices = listOf(
        null to "AUTO",
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "DAY",
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "CLOUD",
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "TUNG",
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "FLUO",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelHeader("BALANCE DE BLANCOS", onClose)
        choices.chunked(3).forEach { rowChoices ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowChoices.forEach { (mode, label) ->
                    ChoiceTile(label, selected == mode, Modifier.weight(1f)) { binder?.setAwbMode(mode) }
                }
                repeat(3 - rowChoices.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text("×", color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
    }
}

@Composable
private fun ChoiceTile(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Amber else Color(0xFF303638))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NavigationBar(selected: AppSection, onSelect: (AppSection) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(54.dp).background(Color(0xFF111516))) {
        AppSection.entries.forEach { section ->
            val label = when (section) {
                AppSection.CAPTURE -> stringResource(R.string.capture_tab)
                AppSection.MEDIA -> stringResource(R.string.media_tab)
                AppSection.SETTINGS -> stringResource(R.string.settings_tab)
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().clickable { onSelect(section) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (selected == section) Amber else Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: CameraUiState,
    settings: CameraSettings,
    audioPermissionGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
    onSettingsChange: (CameraSettings) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().background(Graphite).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.settings_tab), color = Amber, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.settings_privacy), color = Color.White)
                Text(stringResource(R.string.settings_language), color = Muted)
                Text("Camera2 · local MediaStore · 16:9 default", color = VerifiedCyan, fontSize = 12.sp)
                Text(stringResource(R.string.settings_saved), color = Muted, fontSize = 11.sp)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.mode_selector_style), color = Color.White, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.mode_selector_summary), color = Muted, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSelectorStyle.entries.forEach { style ->
                        TextButton(onClick = { onSettingsChange(settings.copy(modeSelectorStyle = style)) }) {
                            Text(
                                if (style == ModeSelectorStyle.DIAL) stringResource(R.string.mode_selector_dial) else stringResource(R.string.mode_selector_buttons),
                                color = if (settings.modeSelectorStyle == style) Amber else Color.White,
                                fontWeight = if (settings.modeSelectorStyle == style) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsToggleRow(
                title = stringResource(R.string.audio_recording),
                summary = if (audioPermissionGranted) {
                    "${settings.audioOutputFormat.label} · visible values come from the hardware"
                } else "Microphone permission is required to meter and record audio.",
                checked = settings.audioEnabled,
                onCheckedChange = {
                    if (it && !audioPermissionGranted) onRequestAudioPermission()
                    onSettingsChange(settings.copy(audioEnabled = it))
                },
            )
        }
        if (!audioPermissionGranted) {
            item {
                Button(
                    onClick = onRequestAudioPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
                ) { Text("Grant microphone access") }
            }
        } else {
            item {
                ProfessionalAudioSettings(
                    state = state,
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text("${stringResource(R.string.burst_count)} · ${settings.burstCount}", color = Color.White, fontWeight = FontWeight.Bold)
                Slider(
                    value = settings.burstCount.toFloat(),
                    onValueChange = { onSettingsChange(settings.copy(burstCount = it.roundToInt().coerceIn(3, 10))) },
                    valueRange = 3f..10f,
                    steps = 6,
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.video_bitrate), color = Color.White, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(12, 20, 40).forEach { bitrate ->
                        TextButton(onClick = { onSettingsChange(settings.copy(videoBitrateMbps = bitrate)) }) {
                            Text(
                                "$bitrate Mbps",
                                color = if (settings.videoBitrateMbps == bitrate) Amber else Color.White,
                                fontWeight = if (settings.videoBitrateMbps == bitrate) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.recording_geometry), color = Color.White, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.recording_geometry_summary), color = Muted, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordingGeometryMode.entries.forEach { mode ->
                        TextButton(onClick = { onSettingsChange(settings.copy(recordingGeometryMode = mode)) }) {
                            Text(
                                stringResource(
                                    if (mode == RecordingGeometryMode.COMPATIBLE) {
                                        R.string.recording_geometry_compatible
                                    } else {
                                        R.string.recording_geometry_native
                                    },
                                ),
                                color = if (settings.recordingGeometryMode == mode) Amber else Color.White,
                                fontWeight = if (settings.recordingGeometryMode == mode) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsToggleRow(
                title = stringResource(R.string.flash_torch),
                summary = stringResource(R.string.flash_torch_summary),
                checked = settings.flashEnabled && state.selectedMode != CaptureMode.LOG,
                enabled = state.descriptor?.flashAvailable == true && state.selectedMode != CaptureMode.LOG,
                onCheckedChange = { onSettingsChange(settings.copy(flashEnabled = it)) },
            )
        }
        item {
            SettingsToggleRow(
                title = stringResource(R.string.histogram_default),
                summary = null,
                checked = settings.histogramEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(histogramEnabled = it)) },
            )
        }
        item {
            SettingsToggleRow(
                title = stringResource(R.string.log_view_assist),
                summary = stringResource(R.string.log_view_assist_summary),
                checked = settings.logViewAssistEnabled,
                enabled = state.descriptor?.supportsOpenCineLog == true,
                onCheckedChange = { onSettingsChange(settings.copy(logViewAssistEnabled = it)) },
            )
        }
        item {
            val descriptor = state.descriptor
            Column(Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.hardware_truth), color = VerifiedCyan, fontWeight = FontWeight.Bold)
                Text("Camera ${descriptor?.cameraId ?: "—"} · ${descriptor?.previewSize?.width ?: 0}×${descriptor?.previewSize?.height ?: 0}", color = Color.White)
                Text("JPEG ${descriptor?.jpegSize?.width ?: 0}×${descriptor?.jpegSize?.height ?: 0} · RAW ${if (descriptor?.supportsRaw == true) "YES" else "NO"}", color = Muted)
                Text(
                    if (descriptor?.supportsOpenCineLog == true) {
                        val trueLog = descriptor.logProfiles.filter { it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020 }
                        val hfrLog = descriptor.logProfiles.filter { it.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP }
                        "OCLog2 · HLG10 TRUE LOG ${trueLog.maxOfOrNull { it.size.width } ?: 0}×${trueLog.maxOfOrNull { it.size.height } ?: 0} @ ${trueLog.maxOfOrNull { it.fps } ?: 0} max" +
                            if (hfrLog.isNotEmpty()) " · HFR ISP-DERIVED ${hfrLog.maxOf { it.fps }} max" else ""
                    } else {
                        "OCLog2 UNSUPPORTED · no capability-backed graph"
                    },
                    color = if (descriptor?.supportsOpenCineLog == true) VerifiedCyan else RecordRed,
                    fontSize = 11.sp,
                )
            }
        }
        items(CaptureMode.entries) { mode ->
            val gate = state.modeGates.getValue(mode)
            Row(Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(7.dp)).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(modeLabel(mode), color = Color.White, fontSize = 12.sp)
                Text(gateLabel(gate), color = gateColor(gate), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ProfessionalAudioSettings(
    state: CameraUiState,
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
) {
    val capabilities = state.audioCapabilities
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Audio profesional", color = VerifiedCyan, fontWeight = FontWeight.Bold)
        if (capabilities == null) {
            Text("Measuring real combinations with AudioRecord…", color = Muted, fontSize = 11.sp)
            return@Column
        }
        if (capabilities.formats.isEmpty()) {
            Text("The hardware did not initialize a compatible audio route.", color = RecordRed, fontSize = 11.sp)
            return@Column
        }
        fun update(candidate: CameraSettings) = onSettingsChange(candidate.normalizedFor(capabilities))
        fun selectPcm(configuration: com.librestatic.opencinecam.media.audio.PcmAudioConfiguration) =
            onSettingsChange(settings.copy(
                audioOutputFormat = settings.audioOutputFormat,
                audioSampleRateHz = configuration.sampleRate,
                audioBitDepth = configuration.bitDepth,
                audioChannels = configuration.channels,
            ))
        AudioChoiceRow(
            title = "Formato de salida",
            choices = capabilities.formats.map { it.name to it.label },
            selected = settings.audioOutputFormat.name,
        ) { name -> update(settings.copy(audioOutputFormat = AudioOutputFormat.valueOf(name))) }

        val rates = if (settings.audioOutputFormat == AudioOutputFormat.AAC_MP4) {
            capabilities.aacSampleRates
        } else capabilities.pcmConfigurations.map { it.sampleRate }.distinct().sorted()
        AudioChoiceRow(
            title = "Frecuencia de muestreo",
            choices = rates.map { it.toString() to formatAudioRate(it) },
            selected = settings.audioSampleRateHz.toString(),
        ) { rate ->
            val selectedRate = rate.toInt()
            if (settings.audioOutputFormat != AudioOutputFormat.AAC_MP4) {
                capabilities.pcmConfigurations.filter { it.sampleRate == selectedRate }
                    .minWithOrNull(compareBy<com.librestatic.opencinecam.media.audio.PcmAudioConfiguration> {
                        kotlin.math.abs(it.bitDepth.bits - settings.audioBitDepth.bits)
                    }.thenBy { kotlin.math.abs(it.channels - settings.audioChannels) })
                    ?.let(::selectPcm)
            } else update(settings.copy(audioSampleRateHz = selectedRate))
        }

        if (settings.audioOutputFormat != AudioOutputFormat.AAC_MP4) {
            AudioChoiceRow(
                title = "Profundidad",
                choices = capabilities.bitDepths
                    .filter { settings.audioOutputFormat != AudioOutputFormat.FLAC || it == AudioBitDepth.PCM_16 }
                    .map { it.name to it.label },
                selected = settings.audioBitDepth.name,
            ) { depth ->
                val selectedDepth = AudioBitDepth.valueOf(depth)
                capabilities.pcmConfigurations.filter { it.bitDepth == selectedDepth }
                    .minWithOrNull(compareBy<com.librestatic.opencinecam.media.audio.PcmAudioConfiguration> {
                        kotlin.math.abs(it.sampleRate - settings.audioSampleRateHz)
                    }.thenBy { kotlin.math.abs(it.channels - settings.audioChannels) })
                    ?.let(::selectPcm)
            }
        }

        val channels = if (settings.audioOutputFormat == AudioOutputFormat.AAC_MP4) {
            capabilities.aacChannelCounts
        } else capabilities.channelCounts
        AudioChoiceRow(
            title = "Canales",
            choices = channels.map { it.toString() to if (it == 1) "Mono" else "Stereo" },
            selected = settings.audioChannels.toString(),
        ) { count ->
            val selectedChannels = count.toInt()
            if (settings.audioOutputFormat != AudioOutputFormat.AAC_MP4) {
                capabilities.pcmConfigurations.filter { it.channels == selectedChannels }
                    .minWithOrNull(compareBy<com.librestatic.opencinecam.media.audio.PcmAudioConfiguration> {
                        kotlin.math.abs(it.sampleRate - settings.audioSampleRateHz)
                    }.thenBy { kotlin.math.abs(it.bitDepth.bits - settings.audioBitDepth.bits) })
                    ?.let(::selectPcm)
            } else update(settings.copy(audioChannels = selectedChannels))
        }

        if (settings.audioOutputFormat == AudioOutputFormat.AAC_MP4) {
            AudioChoiceRow(
                title = "Tasa de bits AAC",
                choices = capabilities.aacBitratesKbps.map { it.toString() to "$it kbps" },
                selected = settings.audioBitrateKbps.toString(),
            ) { bitrate -> update(settings.copy(audioBitrateKbps = bitrate.toInt())) }
            Text("AAC-LC is embedded in the MP4. NS/AGC/AEC effects are not exposed because MediaRecorder does not publish its audio session.", color = Muted, fontSize = 10.sp)
        } else {
            val derived = settings.audioSampleRateHz * settings.audioBitDepth.bits * settings.audioChannels / 1_000
            val container = if (settings.audioOutputFormat == AudioOutputFormat.FLAC) "Lossless compressed FLAC" else "WAV PCM"
            Text("$container · PCM fuente: $derived kbps · archivo sincronizado junto al video", color = VerifiedCyan, fontSize = 10.sp)
        }

        AudioChoiceRow(
            title = "Public source",
            choices = capabilities.sources.map { it.name to it.label },
            selected = settings.audioSource.name,
        ) { source -> update(settings.copy(audioSource = com.librestatic.opencinecam.media.audio.AudioSourceSelection.valueOf(source))) }

        AudioChoiceRow(
            title = "Dispositivo de entrada",
            choices = listOf("auto" to "Automatic") + capabilities.inputs.map { it.id.toString() to "${it.label} · ID ${it.id}" },
            selected = settings.audioInputDeviceId?.toString() ?: "auto",
        ) { id -> update(settings.copy(audioInputDeviceId = id.takeUnless { it == "auto" }?.toInt())) }
        Text("Android lets you choose the input device; it does not guarantee selecting an individual internal capsule.", color = Muted, fontSize = 10.sp)

        if (settings.audioOutputFormat != AudioOutputFormat.AAC_MP4) {
            SettingsToggleRow(
                title = "Noise suppression (NS)",
                summary = "The effective state is saved in .audio.json.",
                checked = settings.noiseSuppressorEnabled,
                enabled = capabilities.noiseSuppressorAvailable,
                onCheckedChange = { update(settings.copy(noiseSuppressorEnabled = it)) },
            )
            SettingsToggleRow(
                title = "Automatic gain (AGC)",
                summary = "Off by default to preserve dynamics.",
                checked = settings.automaticGainControlEnabled,
                enabled = capabilities.automaticGainControlAvailable,
                onCheckedChange = { update(settings.copy(automaticGainControlEnabled = it)) },
            )
            SettingsToggleRow(
                title = "Echo cancellation (AEC)",
                summary = "Useful for speech; off by default.",
                checked = settings.acousticEchoCancelerEnabled,
                enabled = capabilities.acousticEchoCancelerAvailable,
                onCheckedChange = { update(settings.copy(acousticEchoCancelerEnabled = it)) },
            )
        }
        Text("AAC is embedded in the MP4. WAV and FLAC are saved as synchronized sidecars with capture metadata.", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun AudioChoiceRow(
    title: String,
    choices: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            choices.forEach { (value, label) ->
                TextButton(onClick = { onSelected(value) }) {
                    Text(
                        label,
                        color = if (selected == value) Amber else Color.White,
                        fontWeight = if (selected == value) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

private fun formatAudioRate(rate: Int): String = if (rate % 1_000 == 0) "${rate / 1_000} kHz" else "${rate / 1_000.0} kHz"

@Composable
private fun SettingsToggleRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(8.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) Color.White else Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            summary?.let { Text(it, color = Muted, fontSize = 10.sp) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MediaScreen() {
    val context = LocalContext.current
    var media by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        media = withContext(Dispatchers.IO) { LocalMediaRepository(context.applicationContext).recent() }
        loaded = true
    }
    if (loaded && media.isEmpty()) {
        CenterMessage(stringResource(R.string.no_media_yet))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Graphite).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(stringResource(R.string.media_tab), color = Amber, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp)) }
        items(media, key = { it.uri.toString() }) { item ->
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(8.dp)).clickable {
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(intent) }
                }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.thumbnail?.let { bitmap ->
                    Image(bitmap.asImageBitmap(), contentDescription = item.name, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)))
                } ?: Box(Modifier.size(72.dp).background(Color(0xFF303638), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            item.mimeType.contains("video") -> "VIDEO"
                            item.mimeType.contains("audio") || item.mimeType.contains("wav") -> "AUDIO"
                            else -> "RAW"
                        },
                        color = VerifiedCyan,
                        fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.widthIn(max = 600.dp)) {
                    Text(item.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.mimeType} · ${item.sizeBytes / 1024} KiB", color = Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize().background(Graphite).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Color.White)
    }
}

@Composable
private fun modeLabel(mode: CaptureMode): String = when (mode) {
    CaptureMode.PHOTO -> stringResource(R.string.photo_mode)
    CaptureMode.RAW_PHOTO -> stringResource(R.string.raw_photo_mode)
    CaptureMode.BURST -> stringResource(R.string.burst_mode)
    CaptureMode.VIDEO -> stringResource(R.string.video_mode)
    CaptureMode.SLOW_MOTION -> stringResource(R.string.slow_motion_mode)
    CaptureMode.TIME_LAPSE -> stringResource(R.string.time_lapse_mode)
    CaptureMode.BRACKET -> stringResource(R.string.bracket_mode)
    CaptureMode.LIGHT_TRAIL -> stringResource(R.string.light_trail_mode)
    CaptureMode.LOG -> stringResource(R.string.log_mode)
    CaptureMode.APV -> stringResource(R.string.apv_mode)
    CaptureMode.RAW_VIDEO -> stringResource(R.string.raw_video_mode)
}

@Composable
private fun gateLabel(gate: ModeGateState): String = when (gate) {
    ModeGateState.AVAILABLE -> stringResource(R.string.available)
    ModeGateState.CANDIDATE -> stringResource(R.string.candidate)
    ModeGateState.UNSUPPORTED -> stringResource(R.string.unsupported)
    ModeGateState.FAILED -> stringResource(R.string.failed)
}

private fun gateColor(gate: ModeGateState): Color = when (gate) {
    ModeGateState.AVAILABLE -> VerifiedCyan
    ModeGateState.CANDIDATE -> Amber
    ModeGateState.UNSUPPORTED -> Muted
    ModeGateState.FAILED -> RecordRed
}

private fun formatShutter(exposureTimeNs: Long): String {
    if (exposureTimeNs <= 0L) return "—"
    val denominator = 1_000_000_000.0 / exposureTimeNs
    return if (denominator >= 1.0) "1/${denominator.toInt()}" else "${exposureTimeNs / 1_000_000}ms"
}

private fun awbLabel(mode: Int?): String = when (mode) {
    null, CaptureRequest.CONTROL_AWB_MODE_AUTO -> "AUTO"
    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "DAY"
    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUD"
    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "TUNG"
    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "FLUO"
    else -> "WB$mode"
}

private fun logPosition(value: Double, minimum: Double, maximum: Double): Float {
    if (minimum <= 0.0 || maximum <= minimum) return 0f
    return ((ln(value.coerceIn(minimum, maximum)) - ln(minimum)) / (ln(maximum) - ln(minimum))).toFloat().coerceIn(0f, 1f)
}

private fun logValue(position: Float, minimum: Double, maximum: Double): Double =
    exp(ln(minimum) + position.coerceIn(0f, 1f) * (ln(maximum) - ln(minimum)))

private fun CaptureMode.isStillMode(): Boolean = this in setOf(
    CaptureMode.PHOTO,
    CaptureMode.RAW_PHOTO,
    CaptureMode.BURST,
    CaptureMode.BRACKET,
    CaptureMode.LIGHT_TRAIL,
)

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L)
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private fun formatBytes(bytes: Long?): String = when {
    bytes == null -> "—"
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "${bytes / 1_000L} KB"
}

@Preview(name = "Capture chrome · landscape", widthDp = 800, heightDp = 360, showBackground = true, backgroundColor = 0xFF0B0D0E)
@Composable
private fun LandscapeChromePreview() {
    Box(Modifier.fillMaxSize()) {
        LandscapeControlDeck(
            state = CameraUiState(selectedMode = CaptureMode.VIDEO, phase = CameraUiPhase.PREVIEWING),
            binder = null,
            selectorStyle = ModeSelectorStyle.DIAL,
            settings = CameraSettings(),
            onControl = {},
            onShowModes = {},
            onShowMonitoring = {},
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Preview(name = "Mode wheel · portrait", widthDp = 320, heightDp = 120, showBackground = true, backgroundColor = 0xFF0B0D0E)
@Composable
private fun ModeWheelPortraitPreview() {
    Column(Modifier.background(Panel).padding(8.dp)) {
        ModeDial(
            CameraUiState(selectedMode = CaptureMode.PHOTO),
            binder = null,
            compact = false,
        )
    }
}

private fun rotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
