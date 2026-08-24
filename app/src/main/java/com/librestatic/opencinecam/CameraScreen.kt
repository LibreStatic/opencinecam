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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import com.librestatic.opencinecam.camera.ZoomMath
import com.librestatic.opencinecam.camera.ZoomLensSwitchMode
import com.librestatic.opencinecam.ui.viewfinder.ZoomAnchorBar
import com.librestatic.opencinecam.ui.viewfinder.ZoomRocker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
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
import com.librestatic.opencinecam.toSpec
import com.librestatic.opencinecam.storage.LocalMediaItem
import com.librestatic.opencinecam.storage.LocalMediaRepository
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.AfLockBehavior
import com.librestatic.opencinecam.camera.LockState
import com.librestatic.opencinecam.camera.TapFocusState
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import com.librestatic.opencinecam.camera.label
import com.librestatic.opencinecam.camera.snapKelvinTo100
import com.librestatic.opencinecam.camera.KELVIN_PRESETS
import com.librestatic.opencinecam.camera.adaptTo
import com.librestatic.opencinecam.camera.FocusPullEasing
import com.librestatic.opencinecam.camera.AnamorphicSqueeze
import com.librestatic.opencinecam.camera.AnamorphicOutputMode
import com.librestatic.opencinecam.camera.TimecodeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.abs
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
private enum class ControlDial { RESOLUTION, FPS, INT, ISO, SHUTTER, FOCUS, WB, EV }

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
    LaunchedEffect(state.selectedMode, state.targetVideoWidth, state.targetVideoHeight, state.targetFps) {
        if (state.phase == CameraUiPhase.RECORDING) return@LaunchedEffect
        val updated = when (state.selectedMode) {
            CaptureMode.LOG -> settings.copy(
                logWidth = state.targetVideoWidth,
                logHeight = state.targetVideoHeight,
                logFps = state.targetFps,
            )
            CaptureMode.TIME_LAPSE -> settings.copy(
                timelapseWidth = state.targetVideoWidth,
                timelapseHeight = state.targetVideoHeight,
                timelapseFps = state.targetFps,
            )
            in CameraUiState.videoProfileModes -> settings.copy(
                videoWidth = state.targetVideoWidth,
                videoHeight = state.targetVideoHeight,
                videoFps = state.targetFps,
            )
            else -> settings
        }
        if (updated != settings) {
            settings = updated
            settingsStore.save(updated)
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
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
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
        modifier = Modifier.fillMaxSize().background(Graphite).windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
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
    var showGrid by rememberSaveable { mutableStateOf(settings.compositionGridEnabled) }
    var gridMode by rememberSaveable { mutableStateOf(settings.compositionGridMode) }
    var showHorizon by rememberSaveable { mutableStateOf(settings.horizonLevelEnabled) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        LaunchedEffect(binder, widthPx, heightPx) {
            if (binder != null && widthPx > 0 && heightPx > 0 && state.cameras.isEmpty()) binder.prepare(widthPx, heightPx)
        }

        val descriptor = state.descriptor
        val landscape = maxWidth > maxHeight
        val compactPortrait = captureWindowProfile(maxWidth.value, maxHeight.value) == CaptureWindowProfile.COMPACT_PORTRAIT
        val previewStreamSize = descriptor?.let {
            if (state.selectedMode == CaptureMode.LOG) {
                state.activeLogProfile?.size ?: it.preferredLogProfile?.size ?: it.previewSize
            } else if (state.selectedMode in CameraUiState.videoProfileModes) {
                state.activeVideoProfile?.size ?: it.previewSize
            } else {
                it.previewSize
            }
        }
        val squeezeFactor = settings.anamorphicSqueeze.factor
        val previewDisplayRatio = previewStreamSize?.let { size ->
            previewDisplayRatio(size.width, size.height, squeezeFactor, landscape)
        }
        if (descriptor != null && binder != null) {
            val streamSize = requireNotNull(previewStreamSize)
            val displayRatio = requireNotNull(previewDisplayRatio)
            PreviewSurfaceView(
                descriptor.cameraId,
                streamSize.width,
                streamSize.height,
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
                showGrid = showGrid,
                gridMode = gridMode,
                showHorizon = showHorizon,
                reserveAudioMeterSpace = settings.audioEnabled &&
                    state.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.LOG),
                reserveZoomChromeSpace = state.zoomSupported,
                compactPortrait = compactPortrait,
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
            landscape = landscape,
            previewAspectRatio = previewDisplayRatio,
            zebra = zebra,
            peaking = peaking,
            histogram = histogram,
            histogramMode = histogramMode,
            showGrid = showGrid,
            gridMode = gridMode,
            showHorizon = showHorizon,
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
            onToggleGrid = {
                val updated = !showGrid
                showGrid = updated
                onSettingsChanged(settings.copy(compositionGridEnabled = updated, compositionGridMode = gridMode))
            },
            onCycleGridMode = {
                val updated = CompositionGridMode.entries[(gridMode.ordinal + 1) % CompositionGridMode.entries.size]
                gridMode = updated
                showGrid = true
                onSettingsChanged(settings.copy(compositionGridEnabled = true, compositionGridMode = updated))
            },
            onToggleHorizon = {
                val updated = !showHorizon
                showHorizon = updated
                onSettingsChanged(settings.copy(horizonLevelEnabled = updated))
            },
            onSettingsChanged = onSettingsChanged,
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
private fun MonitorToggle(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                contentDescription = description
                selected = enabled
            }
            .clip(RoundedCornerShape(7.dp))
            .background(if (enabled) Amber else Color(0xFF303638))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun MonitoringToggleGrid(
    zebra: Boolean,
    peaking: Boolean,
    histogram: Boolean,
    histogramMode: HistogramMode,
    showGrid: Boolean,
    gridMode: CompositionGridMode,
    showHorizon: Boolean,
    onToggleZebra: () -> Unit,
    onTogglePeaking: () -> Unit,
    onToggleHistogram: () -> Unit,
    onCycleHistogramMode: () -> Unit,
    onToggleGrid: () -> Unit,
    onCycleGridMode: () -> Unit,
    onToggleHorizon: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonitorToggle("Z", stringResource(R.string.monitor_zebra), zebra, onToggleZebra)
            MonitorToggle("P", stringResource(R.string.monitor_peaking), peaking, onTogglePeaking)
            MonitorToggle("H", stringResource(R.string.monitor_histogram), histogram, onToggleHistogram)
            MonitorToggle(
                if (histogramMode == HistogramMode.RGB) "RGB" else "Y",
                stringResource(R.string.monitor_histogram_mode),
                true,
                onCycleHistogramMode,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonitorToggle("G", stringResource(R.string.monitor_grid), showGrid, onToggleGrid)
            MonitorToggle(gridModeLabel(gridMode), stringResource(R.string.monitor_grid_mode), true, onCycleGridMode)
            MonitorToggle("L", stringResource(R.string.monitor_horizon), showHorizon, onToggleHorizon)
        }
    }
}

@Composable
private fun MonitoringOverlay(
    state: CameraUiState,
    showZebra: Boolean,
    showPeaking: Boolean,
    showHistogram: Boolean,
    histogramMode: HistogramMode,
    showGrid: Boolean,
    gridMode: CompositionGridMode,
    showHorizon: Boolean,
    reserveAudioMeterSpace: Boolean,
    reserveZoomChromeSpace: Boolean,
    compactPortrait: Boolean,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val displayRotationProvider = remember(context) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        { displayManager.displays.firstOrNull()?.rotation ?: 0 }
    }
    var rollSnapshot by remember { mutableStateOf<HorizonRollSnapshot?>(null) }
    val horizonSensorAvailable = remember(context, showHorizon) {
        if (!showHorizon) null
        else HorizonRollSensor(context, displayRotationProvider) { rollSnapshot = it }.also { it.start() }
    }
    DisposableEffect(horizonSensorAvailable) {
        onDispose { horizonSensorAvailable?.close() }
    }
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
            if (showGrid) {
                val gridColor = Color.White.copy(alpha = .45f)
                val gridStroke = Stroke(width = 1.dp.toPx())
                if (CompositionGridGeometry.isDiagonal(gridMode)) {
                    CompositionGridGeometry.diagonals(size).forEach { (a, b) ->
                        drawLine(gridColor, a, b, strokeWidth = 1.dp.toPx())
                    }
                } else {
                    CompositionGridGeometry.verticalDivisions(gridMode).forEach { ratio ->
                        val x = size.width * ratio
                        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    }
                    CompositionGridGeometry.horizontalDivisions(gridMode).forEach { ratio ->
                        val y = size.height * ratio
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    }
                }
            }
            if (showHorizon) {
                val snap = rollSnapshot
                if (snap != null && !snap.degrees.isNaN()) {
                    val degrees = snap.degrees.coerceIn(-90f, 90f)
                    val color = when {
                        kotlin.math.abs(degrees) <= HorizonRollColors.LEVEL_BAND -> VerifiedCyan
                        kotlin.math.abs(degrees) <= HorizonRollColors.WARNING_BAND -> Amber
                        else -> Color.White
                    }
                    val centerY = size.height / 2f
                    val halfLen = size.width * 0.35f
                    val angleRad = Math.toRadians(degrees.toDouble())
                    val cos = kotlin.math.cos(angleRad).toFloat()
                    val sin = kotlin.math.sin(angleRad).toFloat()
                    val cx = size.width / 2f
                    val cy = centerY
                    val a = Offset(cx - halfLen * cos, cy - halfLen * sin)
                    val b = Offset(cx + halfLen * cos, cy + halfLen * sin)
                    drawLine(color, a, b, strokeWidth = 2.dp.toPx())
                    drawCircle(color, radius = 4.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
                }
            }
        }
        val analysisFresh = state.analysisUpdatedAtMs > 0L && analysisClockMs - state.analysisUpdatedAtMs <= 1_000L
        if (showHistogram && analysisFresh && state.histogram.isNotEmpty()) {
            val graphWidth = maxWidth * .28f
            val graphHeight = maxHeight * .09f
            // The histogram belongs at the top of the usable preview. In landscape the
            // preview reaches behind the 56 dp top bar, so clear that bar even without audio;
            // reserve the larger HUD footprint only when the microphone meter is enabled.
            val desiredTop = maxOf(
                if (landscape) 64.dp else 12.dp,
                when {
                    reserveAudioMeterSpace && landscape -> 140.dp
                    reserveAudioMeterSpace -> 92.dp
                    else -> 0.dp
                },
                when {
                    !reserveZoomChromeSpace -> 0.dp
                    compactPortrait -> 128.dp
                    landscape -> 124.dp
                    else -> 184.dp
                },
            )
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
    var surfaceGeneration by remember { mutableIntStateOf(0) }
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
    LaunchedEffect(view, targetFps) {
        voteForViewfinderRate()
    }
    // A resize and a rotation can emit several SurfaceHolder callbacks carrying geometry from
    // the previous window. Cancelling/restarting this effect makes attachment latest-layout-wins:
    // the camera only sees the surface after Compose and AndroidView have completed two frames.
    LaunchedEffect(
        view,
        binder,
        cameraId,
        bufferWidth,
        bufferHeight,
        layoutSizedBuffer,
        displayWidthPx,
        displayHeightPx,
        surfaceGeneration,
    ) {
        withFrameNanos { }
        withFrameNanos { }
        val surface = view.holder.surface
        if (
            displayWidthPx > 0 && displayHeightPx > 0 &&
            view.width > 0 && view.height > 0 &&
            surface?.isValid == true
        ) {
            binder.attachPreview(surface, rotationDegrees(view.display?.rotation ?: Surface.ROTATION_0))
        }
    }
    DisposableEffect(view, binder) {
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                voteForViewfinderRate()
                surfaceGeneration++
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                binder.detachPreview(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceGeneration++
            }
        }
        val displayManager = context.getSystemService(DisplayManager::class.java)
        var lastObservedRotation: Int? = view.display?.rotation
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != view.display?.displayId) return
                val currentRotation = view.display?.rotation
                if (!previewDisplayRotationChanged(lastObservedRotation, currentRotation)) return
                lastObservedRotation = currentRotation
                // 0°↔180° and 90°↔270° keep the same layout dimensions, so neither Compose's
                // size keys nor SurfaceHolder.surfaceChanged are guaranteed to fire. Observe the
                // display rotation itself to refresh Camera2/LOG orientation in those cases.
                // Do not invalidate on refresh-rate callbacks: voting for the camera frame rate
                // changes the physical display mode on some foldables and used to create an attach loop.
                view.post {
                    surfaceGeneration++
                }
            }
        }
        view.holder.addCallback(callback)
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        if (view.holder.surface?.isValid == true) surfaceGeneration++
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
        modifier = modifier.aspectRatio(displayRatio).testTag("preview-surface"),
    )
}

@Composable
internal fun AdaptiveCaptureChrome(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    landscape: Boolean,
    previewAspectRatio: Float? = null,
    zebra: Boolean,
    peaking: Boolean,
    histogram: Boolean,
    histogramMode: HistogramMode,
    showGrid: Boolean,
    gridMode: CompositionGridMode,
    showHorizon: Boolean,
    onToggleZebra: () -> Unit,
    onTogglePeaking: () -> Unit,
    onToggleHistogram: () -> Unit,
    onCycleHistogramMode: () -> Unit,
    onToggleGrid: () -> Unit,
    onCycleGridMode: () -> Unit,
    onToggleHorizon: () -> Unit,
    onSettingsChanged: (CameraSettings) -> Unit,
    onOpenMedia: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var manualControl by remember { mutableStateOf<ControlDial?>(null) }
    var showModeGrid by remember { mutableStateOf(false) }
    var showMonitoring by remember { mutableStateOf(false) }
    val recording = state.phase == CameraUiPhase.RECORDING
    var manualReveal by remember { mutableStateOf(false) }
    var tapPoint by remember { mutableStateOf<Offset?>(null) }
    var pinchStartRatio by remember { mutableFloatStateOf(-1f) }
    var controlDeckHeightPx by remember { mutableIntStateOf(0) }
    // While recording, the full console auto-hides to keep a clean viewfinder. A tap reveals
    // it again; it re-hides after a short idle period unless the user keeps interacting.
    LaunchedEffect(recording, manualReveal) {
        if (recording && manualReveal) {
            delay(4_000)
            manualReveal = false
        }
    }
    val chromeVisible = !recording || manualReveal
    LaunchedEffect(state.tapFocusState) {
        if (state.tapFocusState == TapFocusState.IDLE) tapPoint = null
    }

    BackHandler(enabled = manualControl != null || showModeGrid || showMonitoring) {
        manualControl = null
        showModeGrid = false
        showMonitoring = false
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val density = LocalDensity.current
        val windowProfile = captureWindowProfile(maxWidth.value, maxHeight.value)
        val horizontalDeck = windowProfile != CaptureWindowProfile.COMPACT_PORTRAIT
        val compactPortrait = windowProfile == CaptureWindowProfile.COMPACT_PORTRAIT
        val controlDeckHeight = with(density) { controlDeckHeightPx.toDp() }
        // SurfaceView owns a native surface, so keep an explicit Compose hit target over it.
        // This is the first child: controls composed later remain the winning hit targets.
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val ratio = previewAspectRatio
        val previewViewport = fittedPreviewViewport(width, height, ratio)
        val previewWidth = previewViewport.width
        val previewHeight = previewViewport.height
        val previewLeft = previewViewport.left
        val previewTop = previewViewport.top
        Box(
            Modifier
                .matchParentSize()
                .testTag(if (recording && !chromeVisible) "recording-reveal-surface" else "viewfinder-interaction-surface")
                .semantics {
                    onClick {
                        if (recording) manualReveal = true
                        true
                    }
                }
                .pointerInput(recording, ratio, state.zoomSupported, state.zoomRatio) {
                    // Pinch-to-zoom. Consumed by this detector so it never reaches tap-focus.
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (!state.zoomSupported) return@detectTransformGestures
                        if (zoom == 1f) {
                            // Pan or idle: reset the accumulated base so the next pinch starts fresh.
                            pinchStartRatio = -1f
                            return@detectTransformGestures
                        }
                        if (pinchStartRatio <= 0f) pinchStartRatio = state.zoomRatio
                        val candidate = pinchStartRatio * zoom
                        val range = if (settings.zoomLensSwitchMode == ZoomLensSwitchMode.MANUAL_PRESETS &&
                            state.opticalAnchors.size > 1) {
                            ZoomMath.sectorBounds(
                                state.zoomRatio,
                                state.opticalAnchors,
                                settings.zoomLensSwitchMode,
                                state.zoomMinRatio,
                                state.zoomMaxRatio,
                            )
                        } else {
                            state.zoomMinRatio..state.zoomMaxRatio
                        }
                        val coerced = ZoomMath.coerce(candidate, range)
                        binder?.setZoomRatio(coerced)
                        pinchStartRatio = coerced
                    }
                }
                .pointerInput(recording, ratio, settings.tapExposureMeteringEnabled, state.phase) {
                    detectTapGestures { position ->
                        if (recording) manualReveal = true
                        if (ratio == null || position.x !in previewLeft..(previewLeft + previewWidth) ||
                            position.y !in previewTop..(previewTop + previewHeight)
                        ) return@detectTapGestures
                        val accepted = binder?.tapToFocus(
                            (position.x - previewLeft) / previewWidth,
                            (position.y - previewTop) / previewHeight,
                            settings.tapExposureMeteringEnabled,
                        ) == true
                        if (accepted) tapPoint = position
                    }
                },
        )

        tapPoint?.let { point ->
            val color = when (state.tapFocusState) {
                TapFocusState.SEARCHING -> Amber
                TapFocusState.FOCUSED -> VerifiedCyan
                TapFocusState.NOT_FOCUSED -> RecordRed
                TapFocusState.IDLE -> Color.Transparent
            }
            val reticleDescription = stringResource(
                when (state.tapFocusState) {
                    TapFocusState.SEARCHING -> R.string.tap_focus_searching
                    TapFocusState.FOCUSED -> R.string.tap_focus_locked
                    TapFocusState.NOT_FOCUSED -> R.string.tap_focus_failed
                    TapFocusState.IDLE -> R.string.tap_focus_idle
                },
            )
            Canvas(
                Modifier
                    .matchParentSize()
                    .testTag("tap-focus-reticle")
                    .semantics {
                        contentDescription = reticleDescription
                    },
            ) {
                val side = minOf(48.dp.toPx(), previewWidth, previewHeight)
                val left = (point.x - side / 2f).coerceIn(previewLeft, previewLeft + previewWidth - side)
                val top = (point.y - side / 2f).coerceIn(previewTop, previewTop + previewHeight - side)
                drawRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(side, side),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // Zoom chrome: anchor bar + ratio indicator are part of chrome; the lateral rocker stays
        // visible during recording even when the rest of the chrome hides.
        if (state.zoomSupported) {
            var rockerOffset by remember { mutableFloatStateOf(0f) }
            var lastRockerMs by remember { mutableLongStateOf(0L) }
            if (chromeVisible) {
                val anchorTop = if (compactPortrait) 112.dp else 62.dp
                ZoomAnchorBar(
                    anchors = state.opticalAnchors,
                    activeRatio = state.zoomEffectiveRatio ?: state.zoomRatio,
                    onSelect = { ratio -> binder?.selectZoomAnchor(ratio) },
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = anchorTop),
                )
                val ratioValue = state.zoomEffectiveRatio ?: state.zoomRatio
                val isDigital = state.opticalAnchors.none { (ratioValue - it.ratio).let { d -> d >= -0.05f && d <= 0.05f } }
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = anchorTop + 56.dp)
                        .testTag("zoom-ratio")
                        .clip(RoundedCornerShape(6.dp))
                        .background(Panel)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        "%.1f×".format(ratioValue) +
                            if (isDigital) " " + stringResource(R.string.zoom_digital) else "",
                        color = Amber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            ZoomRocker(
                onSpeed = { offset ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    val dt = ((now - lastRockerMs).coerceAtLeast(1L)) / 1000f
                    lastRockerMs = now
                    rockerOffset = offset
                    val speed = ZoomMath.rockerSpeedOctavesPerSecond(offset)
                    if (speed != 0f) {
 val range = if (settings.zoomLensSwitchMode == ZoomLensSwitchMode.MANUAL_PRESETS &&
                            state.opticalAnchors.size > 1) {
                            ZoomMath.sectorBounds(
                                state.zoomRatio,
                                state.opticalAnchors,
                                settings.zoomLensSwitchMode,
                                state.zoomMinRatio,
                                state.zoomMaxRatio,
                            )
                        } else {
                            state.zoomMinRatio..state.zoomMaxRatio
                        }
                        val factor = ZoomMath.rockerFactor(speed, dt)
                        val candidate = ZoomMath.multiply(state.zoomRatio, factor, range)
                        binder?.setZoomRatio(candidate)
                    }
                },
                onRelease = { rockerOffset = 0f },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .width(28.dp)
                    .height(120.dp),
            )
        }

        if (chromeVisible) {
            LockToggles(
                state = state,
                binder = binder,
                afLockBehavior = settings.afLockBehavior,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 62.dp),
            )
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
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { controlDeckHeightPx = it.height },
        ) {
            if (horizontalDeck) {
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
                showGrid = showGrid,
                gridMode = gridMode,
                showHorizon = showHorizon,
                onToggleZebra = onToggleZebra,
                onTogglePeaking = onTogglePeaking,
                onToggleHistogram = onToggleHistogram,
                onCycleHistogramMode = onCycleHistogramMode,
                onToggleGrid = onToggleGrid,
                onCycleGridMode = onCycleGridMode,
                onToggleHorizon = onToggleHorizon,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = recordingHudTop),
            )
        }

        if (chromeVisible) manualControl?.let { control ->
            ContextualPanel(horizontalDeck, controlDeckHeight, maxHeight, onDismiss = { manualControl = null }) {
                ManualControlDial(control, state, binder, settings, onSettingsChanged) { manualControl = null }
            }
        }
        if (chromeVisible && showModeGrid) {
            ContextualPanel(horizontalDeck, controlDeckHeight, maxHeight, onDismiss = { showModeGrid = false }) {
                ModeButtonGrid(state, binder) { showModeGrid = false }
            }
        }
        if (chromeVisible && showMonitoring) {
            ContextualPanel(horizontalDeck, controlDeckHeight, maxHeight, onDismiss = { showMonitoring = false }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.monitor_title), color = Amber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    MonitoringToggleGrid(
                        zebra, peaking, histogram, histogramMode, showGrid, gridMode, showHorizon,
                        onToggleZebra, onTogglePeaking, onToggleHistogram, onCycleHistogramMode,
                        onToggleGrid, onCycleGridMode, onToggleHorizon,
                    )
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
                TopAction("\u25eb", stringResource(R.string.monitoring_tools), onShowMonitoring)
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
            TopAction("◫", stringResource(R.string.monitoring_tools), onShowMonitoring)
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
        if (state.selectedMode == CaptureMode.TIME_LAPSE) add(ControlDial.INT)
        add(ControlDial.FPS)
        add(ControlDial.SHUTTER)
        add(ControlDial.ISO)
        add(ControlDial.WB)
        if (state.aeCompensationSupported) add(ControlDial.EV)
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
private fun LockToggles(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    afLockBehavior: AfLockBehavior,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val manualExposure = state.requestedIso != null || state.requestedExposureTimeNs != null
    val manualFocus = state.requestedFocusDiopters != null
    val aeAvailable = state.aeLockSupported && !manualExposure
    val afAvailable = state.afLockSupported && !manualFocus
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.aeLockSupported) {
            LockButton(
                label = stringResource(R.string.ae_lock),
                active = state.aeLockActive,
                enabled = aeAvailable,
                testTag = "ae-lock-toggle",
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    binder?.setAeLock(!state.aeLockActive)
                },
            )
        }
        val afPending = state.afLockState == LockState.PENDING
        val afLocked = state.afLockState == LockState.LOCKED
        if (state.afLockSupported) {
            LockButton(
                label = if (afPending) stringResource(R.string.af_lock_pending) else stringResource(R.string.af_lock),
                active = afLocked,
                pending = afPending,
                enabled = afAvailable,
                testTag = "af-lock-toggle",
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (afLocked || afPending) binder?.setAfLock(false, afLockBehavior)
                    else binder?.setAfLock(true, afLockBehavior)
                },
            )
        }
    }
}

@Composable
private fun LockButton(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    pending: Boolean = false,
    testTag: String,
    onClick: () -> Unit,
) {
    val bg = when {
        active -> VerifiedCyan
        pending -> Amber
        enabled -> Color(0xFF1B2023)
        else -> Color(0xFF161A1C)
    }
    val fg = if (active) Color.Black else if (enabled) Color.White else Color(0xFF626A6D)
    val border = if (active) VerifiedCyan else if (pending) Amber else if (enabled) Color(0xFF41494C) else Color(0xFF2A3033)
    Column(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
        ControlDial.WB -> if (constrained) "AUTO·HS" else state.requestedWhiteBalance.label()
        ControlDial.FOCUS -> if (constrained) "AUTO·HS" else state.focusDistanceDiopters?.let { "%.1fD".format(it) } ?: "AUTO"
        ControlDial.EV -> if (constrained) "0" else formatEv(state.aeCompensationEv)
        ControlDial.INT -> formatIntervalShort(state.timelapseIntervalMs)
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
        val focusedIndex by remember(listState, selectedIndex) {
            derivedStateOf {
                listState.layoutInfo.visibleItemsInfo
                    .minByOrNull { item ->
                        val viewportCenter =
                            (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
                        abs(item.offset + item.size / 2 - viewportCenter)
                    }
                    ?.index
                    ?: selectedIndex
            }
        }
        LaunchedEffect(selectedIndex) {
            if (!listState.isScrollInProgress && focusedIndex != selectedIndex) {
                if (listState.layoutInfo.totalItemsCount == 0) return@LaunchedEffect
                listState.animateScrollToItem(selectedIndex)
            }
        }
        LaunchedEffect(listState.isScrollInProgress) {
            if (!listState.isScrollInProgress && listState.layoutInfo.totalItemsCount > 0) {
                val idx = focusedIndex.coerceIn(0, modes.lastIndex)
                if (idx != selectedIndex && modes[idx] != state.selectedMode && selectable(modes[idx])) {
                    binder?.selectMode(modes[idx])
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                } else if (idx != selectedIndex && !selectable(modes[idx])) {
                    listState.animateScrollToItem(selectedIndex)
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
                        val isSelected = index == focusedIndex
                        val enabled = selectable(mode)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                                .semantics { selected = isSelected }
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
            ModePositionDots(modes, focusedIndex)
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
            val focusedIndex by remember(listState, selectedIndex) {
                derivedStateOf {
                    listState.layoutInfo.visibleItemsInfo
                        .minByOrNull { item ->
                            val viewportCenter =
                                (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
                            abs(item.offset + item.size / 2 - viewportCenter)
                        }
                        ?.index
                        ?: selectedIndex
                }
            }
            LaunchedEffect(selectedIndex, totalWidth) {
                if (!listState.isScrollInProgress && focusedIndex != selectedIndex) {
                    if (listState.layoutInfo.totalItemsCount == 0) return@LaunchedEffect
                    listState.animateScrollToItem(selectedIndex)
                }
            }
            LaunchedEffect(listState.isScrollInProgress) {
                if (!listState.isScrollInProgress && listState.layoutInfo.totalItemsCount > 0) {
                    val idx = focusedIndex.coerceIn(0, modes.lastIndex)
                    if (idx != selectedIndex && modes[idx] != state.selectedMode && selectable(modes[idx])) {
                        binder?.selectMode(modes[idx])
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } else if (idx != selectedIndex && !selectable(modes[idx])) {
                        listState.animateScrollToItem(selectedIndex)
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
                            val isSelected = index == focusedIndex
                            val enabled = selectable(mode)
                            Box(
                                Modifier
                                    .width(itemWidth)
                                    .height(38.dp)
                                    .semantics { selected = isSelected }
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
                ModePositionDots(modes, focusedIndex)
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
        Text(stringResource(R.string.mode_title), color = Muted, fontSize = 8.sp)
        Text(modeLabel(state.selectedMode), color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ModeButtonGrid(state: CameraUiState, binder: CaptureService.LocalBinder?, onSelected: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.modes_title), color = Amber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
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
private fun ContextualPanel(
    horizontalDeck: Boolean,
    controlDeckHeight: androidx.compose.ui.unit.Dp,
    availableHeight: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val maximumHeight = (availableHeight - controlDeckHeight - 72.dp)
        .coerceAtLeast(160.dp)
        .coerceAtMost(if (horizontalDeck) 380.dp else 440.dp)
    Box(
        Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(if (horizontalDeck) Alignment.BottomEnd else Alignment.BottomCenter)
                .padding(
                    end = if (horizontalDeck) 12.dp else 0.dp,
                    bottom = controlDeckHeight + 12.dp,
                )
                .widthIn(min = 260.dp, max = 380.dp)
                .heightIn(max = maximumHeight)
                .background(Color(0xF21A1F21), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF4A5154), RoundedCornerShape(10.dp))
                .clickable { }
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
    val captureDescription = stringResource(
        when {
            recording -> R.string.stop_recording
            state.selectedMode.isStillMode() -> R.string.capture_photo
            else -> R.string.start_recording
        },
    )
    Box(
        Modifier
            .size(size)
            .semantics {
                contentDescription = captureDescription
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
    val audio = if (settings.audioEnabled) audioOutputLabel(settings.audioOutputFormat) + " " + (settings.audioSampleRateHz / 1000) + " kHz" else "Audio OFF"
    val bitrate = settings.videoBitrateMbps.toString() + " Mbps"
    val time = if (state.phase == CameraUiPhase.RECORDING) formatDuration(state.recordingElapsedMs)
        else state.availableStorageBytes?.takeIf { it > 0 }?.let { formatDuration(it * 8L * 1000L / (settings.videoBitrateMbps * 1_000_000L)) } ?: "--"
    val free = formatBytes(state.availableStorageBytes) + " libre"
    val battery = batteryPercent?.let { "$it%" } ?: "--"
    val lut = if (state.selectedMode == CaptureMode.LOG) (if (settings.logViewAssistEnabled) "Rec.709" else "Flat") else "--"
   val wb = state.requestedWhiteBalance.label()
   val focus = if (state.requestedFocusDiopters != null) "MF" else "AF-C"
    val ana = if (settings.anamorphicSqueeze.isActive) {
        val squeezeLabel = when (settings.anamorphicSqueeze) {
            AnamorphicSqueeze.SQUEEZE_1_33X -> "1.33x"
            AnamorphicSqueeze.SQUEEZE_1_5X -> "1.5x"
            AnamorphicSqueeze.SQUEEZE_2X -> "2x"
            else -> ""
        }
        val modeLabel = if (settings.anamorphicOutputMode == AnamorphicOutputMode.DESQUEEZED) "DQ" else "SQ"
        "ANA $squeezeLabel/$modeLabel"
    } else null
    val primary = buildList {
        add(codec)
        if (!isStill) { add(bitrate); add(audio) }
           add(time)
            if (ana != null) add(ana)
            if (state.timecodeDisplay != null) add(state.timecodeDisplay!!)
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
    showGrid: Boolean, gridMode: CompositionGridMode, showHorizon: Boolean,
    onToggleZebra: () -> Unit, onTogglePeaking: () -> Unit, onToggleHistogram: () -> Unit,
    onCycleHistogramMode: () -> Unit,
    onToggleGrid: () -> Unit, onCycleGridMode: () -> Unit, onToggleHorizon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMonitors by remember { mutableStateOf(false) }
    val stopRecordingDescription = stringResource(R.string.stop_recording)
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
            if (!compact) TopAction("\u25eb", stringResource(R.string.monitoring_tools)) { showMonitors = !showMonitors }
            if (showStop) {
                Box(
                    Modifier.size(48.dp).semantics { contentDescription = stopRecordingDescription }.border(2.dp, Color.White, CircleShape).padding(5.dp).clip(CircleShape).clickable { binder?.capturePrimary() },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(16.dp).testTag("recording-stop-glyph").clip(RoundedCornerShape(2.dp)).background(RecordRed)) }
            }
        }
    }
    if (showMonitors) {
        Box(modifier.fillMaxWidth().padding(top = 60.dp, start = 10.dp, end = 10.dp), contentAlignment = Alignment.TopEnd) {
            MonitoringToggleGrid(
                zebra, peaking, histogram, histogramMode, showGrid, gridMode, showHorizon,
                onToggleZebra, onTogglePeaking, onToggleHistogram, onCycleHistogramMode,
                onToggleGrid, onCycleGridMode, onToggleHorizon,
            )
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
    var noticeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(state.message, state.messageTransient) {
        if (state.messageTransient) {
            noticeVisible = true
            delay(2_500)
            noticeVisible = false
        } else {
            noticeVisible = true
        }
    }
    val status = when {
        state.phase == CameraUiPhase.RECORDING && state.recordingWidth != null && state.recordingHeight != null ->
            "REC ${state.recordingWidth}×${state.recordingHeight} · ${state.targetFps} fps · ${formatDuration(state.recordingElapsedMs)} · ${formatBytes(state.availableStorageBytes)} free"
        else -> state.message
    }?.takeIf { !state.messageTransient || noticeVisible } ?: return
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
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
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
        WbDial(state, binder, onClose)
        return
    }
    if (control == ControlDial.EV) {
        EvDial(state, binder, onClose)
        return
    }
    if (control == ControlDial.FOCUS) {
        FocusPullDial(state, binder, settings, onSettingsChanged, onClose)
        return
    }
    if (control == ControlDial.INT) {
        IntervalometerDial(settings, onSettingsChanged, onClose)
        return
    }
    val value = when (control) {
        ControlDial.RESOLUTION -> 0f
        ControlDial.FPS -> 0f
        ControlDial.ISO -> logPosition((state.requestedIso ?: state.sensitivityIso ?: descriptor.sensitivityRange?.lower ?: 100).toDouble(), descriptor.sensitivityRange?.lower?.toDouble() ?: 50.0, descriptor.sensitivityRange?.upper?.toDouble() ?: 6400.0)
        ControlDial.SHUTTER -> logPosition((state.requestedExposureTimeNs ?: state.exposureTimeNs ?: 16_666_667L).toDouble(), descriptor.exposureTimeRangeNs?.lower?.toDouble() ?: 100_000.0, descriptor.exposureTimeRangeNs?.upper?.toDouble() ?: 1_000_000_000.0)
        ControlDial.FOCUS -> ((state.requestedFocusDiopters ?: state.focusDistanceDiopters ?: 0f) / (descriptor.minimumFocusDistance ?: 1f)).coerceIn(0f, 1f)
        ControlDial.WB -> 0f
        ControlDial.EV -> 0f
        ControlDial.INT -> 0f
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
                    ControlDial.EV -> Unit
                    ControlDial.INT -> Unit
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
                ControlDial.WB -> binder?.setWhiteBalance(com.librestatic.opencinecam.camera.WhiteBalanceSelection.Auto)
                ControlDial.EV -> binder?.setExposureCompensation(0)
                ControlDial.INT -> Unit
            }
            onClose()
        }) { Text(stringResource(R.string.auto_value), color = VerifiedCyan, fontSize = 10.sp) }
        TextButton(onClick = onClose) { Text("×", color = Color.White, fontSize = 14.sp) }
    }
}

@Composable
private fun EvDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelHeader("EV", onClose)
        val range = state.aeCompensationIndexRange ?: return@Column
        val step = state.descriptor?.aeCompensationStep?.takeIf { it > 0f } ?: 1f
        val minEv = range.first * step
        val maxEv = range.last * step
        val current = state.requestedAeCompensationIndex.coerceIn(range.first, range.last)
        Text(
            formatEv(current * step),
            color = Amber,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Slider(
            value = current.toFloat(),
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            onValueChange = { index -> binder?.setExposureCompensation(index.roundToInt()) },
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatEv(minEv), color = Muted, fontSize = 10.sp)
            TextButton(onClick = { binder?.setExposureCompensation(0); onClose() }) {
                Text(stringResource(R.string.auto_value), color = VerifiedCyan, fontSize = 10.sp)
            }
            Text(formatEv(maxEv), color = Muted, fontSize = 10.sp)
        }
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
                    ChoiceTile("${width}×$height", selected, Modifier.weight(1f)) {
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
        val modeRates = if (state.selectedMode == CaptureMode.LOG) {
            VideoGeometryPolicy.unionLogFps(state.availableLogProfiles.map { it.toSpec() })
        } else if (state.selectedMode in CameraUiState.videoProfileModes) {
            VideoGeometryPolicy.unionFps(state.availableVideoProfiles.map { it.toSpec() })
        } else {
            state.availableTargetFps
        }
        modeRates.chunked(4).forEach { rates ->
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
                    val supported = if (state.selectedMode == CaptureMode.LOG) {
                        VideoGeometryPolicy.supportedLogFps(state.availableLogProfiles.map { it.toSpec() }, state.targetVideoWidth, state.targetVideoHeight).contains(fps)
                    } else {
                        VideoGeometryPolicy.supportedFps(state.availableVideoProfiles.map { it.toSpec() }, state.targetVideoWidth, state.targetVideoHeight).contains(fps)
                    }
                    ChoiceTile(label, fps == state.targetFps, Modifier.weight(1f), enabled = supported) {
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
private fun WbDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    onClose: () -> Unit,
) {
    val descriptor = state.descriptor
    val kelvinRange = descriptor?.kelvinRange
    val selection = state.requestedWhiteBalance

    if (kelvinRange != null) {
        // Direct Kelvin (CCT) path - slider + presets
        val currentK = (selection as? WhiteBalanceSelection.Kelvin)?.kelvin
            ?: KELVIN_PRESETS.firstOrNull { it in kelvinRange }
            ?: kelvinRange.first
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelHeader("BALANCE DE BLANCOS", onClose)

            // Live Kelvin readout
            val displayK = if (selection is WhiteBalanceSelection.Kelvin) selection.kelvin else currentK
            Text(
                if (selection is WhiteBalanceSelection.Auto) "AUTO" else "${displayK}K",
                color = if (selection is WhiteBalanceSelection.Auto) VerifiedCyan else Amber,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            // Kelvin slider - 100K steps within device range
            if (selection !is WhiteBalanceSelection.Auto) {
                Slider(
                    value = displayK.toFloat(),
                    valueRange = kelvinRange.first.toFloat()..kelvinRange.last.toFloat(),
                    steps = ((kelvinRange.last - kelvinRange.first) / 100 - 1).coerceAtLeast(0),
                    onValueChange = { raw ->
                        val snapped = snapKelvinTo100(raw.toInt(), kelvinRange)
                        if (snapped != null) binder?.setWhiteBalance(WhiteBalanceSelection.Kelvin(snapped))
                    },
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                )
            }

            // Preset Kelvin chips
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KELVIN_PRESETS.forEach { preset ->
                    val inRange = preset in kelvinRange
                    val presetSel = selection is WhiteBalanceSelection.Kelvin && selection.kelvin == preset
                    ChoiceTile("${preset}K", presetSel, Modifier.weight(1f), enabled = inRange) {
                        binder?.setWhiteBalance(WhiteBalanceSelection.Kelvin(preset))
                    }
                }
            }

            // AUTO + range labels row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${kelvinRange.first}K", color = Muted, fontSize = 10.sp)
                TextButton(onClick = { binder?.setWhiteBalance(WhiteBalanceSelection.Auto) }) {
                    Text(stringResource(R.string.auto_value), color = VerifiedCyan, fontSize = 10.sp)
                }
                Text("${kelvinRange.last}K", color = Muted, fontSize = 10.sp)
            }
        }
    } else {
        // Legacy AWB preset path - devices without CCT support
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
                        val isSelected = if (mode == null) selection is WhiteBalanceSelection.Auto
                            else selection is WhiteBalanceSelection.Preset && selection.awbMode == mode
                        ChoiceTile(label, isSelected, Modifier.weight(1f)) {
                            binder?.setWhiteBalance(
                                if (mode != null) WhiteBalanceSelection.Preset(mode) else WhiteBalanceSelection.Auto,
                            )
                        }
                    }
                    repeat(3 - rowChoices.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun IntervalometerDial(
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var customText by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf(false) }
    val presets = listOf(100L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L)
    val limitModes = TimeLapseLimitMode.entries

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PanelHeader(stringResource(R.string.timelapse_interval), onClose)
        Text(stringResource(R.string.timelapse_interval_summary), color = Muted, fontSize = 10.sp)

        // Interval presets
        presets.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { ms ->
                    val selected = settings.timelapseIntervalMs == ms
                    ChoiceTile(formatIntervalShort(ms), selected, Modifier.weight(1f)) {
                        onSettingsChanged(settings.copy(timelapseIntervalMs = ms))
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        // Custom interval input
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = customText,
                onValueChange = { customText = it; customError = false },
                label = { Text(stringResource(R.string.timelapse_custom_interval), fontSize = 10.sp) },
                placeholder = { Text(stringResource(R.string.timelapse_custom_hint), fontSize = 10.sp, color = Muted) },
                isError = customError,
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            TextButton(onClick = {
                val parsed = customText.trim().replace(',', '.').toDoubleOrNull()
                if (parsed != null && parsed >= 0.1 && parsed <= 3600.0) {
                    val ms = (parsed * 1000).toLong().coerceIn(100L, 3_600_000L)
                    onSettingsChanged(settings.copy(timelapseIntervalMs = ms))
                    customText = ""
                } else {
                    customError = true
                }
            }) { Text(stringResource(R.string.timelapse_custom_interval), color = Amber, fontSize = 10.sp) }
        }
        if (customError) {
            Text(stringResource(R.string.timelapse_custom_invalid), color = RecordRed, fontSize = 10.sp)
        }

        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.timelapse_limit), color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)

        // Limit mode selector
        limitModes.forEach { mode ->
            val label = when (mode) {
                TimeLapseLimitMode.UNLIMITED -> stringResource(R.string.timelapse_limit_unlimited)
                TimeLapseLimitMode.FRAME_COUNT -> stringResource(R.string.timelapse_limit_frame_count)
                TimeLapseLimitMode.DURATION -> stringResource(R.string.timelapse_limit_duration)
            }
            ChoiceTile(label, settings.timelapseLimitMode == mode, Modifier.fillMaxWidth()) {
                onSettingsChanged(settings.copy(timelapseLimitMode = mode))
            }
        }

        // Frame count input
        if (settings.timelapseLimitMode == TimeLapseLimitMode.FRAME_COUNT) {
            var frameText by remember { mutableStateOf(settings.timelapseFrameCount.toString()) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = frameText,
                    onValueChange = { frameText = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.timelapse_frame_count), fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                TextButton(onClick = {
                    val count = frameText.toIntOrNull()?.coerceIn(2, 100_000)
                    if (count != null) onSettingsChanged(settings.copy(timelapseFrameCount = count))
                }) { Text("OK", color = Amber, fontSize = 10.sp) }
            }
            Text("2\u2013100.000", color = Muted, fontSize = 9.sp)
        }

        // Duration input
        if (settings.timelapseLimitMode == TimeLapseLimitMode.DURATION) {
            val totalSeconds = settings.timelapseDurationMs / 1000
            var hours by remember { mutableStateOf((totalSeconds / 3600).toString()) }
            var minutes by remember { mutableStateOf(((totalSeconds % 3600) / 60).toString()) }
            var seconds by remember { mutableStateOf((totalSeconds % 60).toString()) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = hours,
                    onValueChange = { hours = it.filter { c -> c.isDigit() } },
                    label = { Text("h", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter { c -> c.isDigit() } },
                    label = { Text("min", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { seconds = it.filter { c -> c.isDigit() } },
                    label = { Text("s", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                TextButton(onClick = {
                    val h = hours.toIntOrNull()?.coerceIn(0, 24) ?: 0
                    val m = minutes.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    val sec = seconds.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    val totalMs = (h * 3600 + m * 60 + sec) * 1000L
                    if (totalMs in 1_000L..86_400_000L) {
                        onSettingsChanged(settings.copy(timelapseDurationMs = totalMs))
                    }
                }) { Text("OK", color = Amber, fontSize = 10.sp) }
            }
            Text("1 s\u201324 h", color = Muted, fontSize = 9.sp)
        }
    }
}

private fun formatIntervalShort(intervalMs: Long): String {
    val totalSeconds = intervalMs / 1000.0
    return when {
        totalSeconds < 1.0 -> "%.1fs".format(totalSeconds)
        totalSeconds < 60.0 -> "%ds".format(totalSeconds.toInt())
        totalSeconds < 3600.0 -> {
            val m = totalSeconds.toInt() / 60
            val s = totalSeconds.toInt() % 60
            if (s == 0) "${m}min" else "${m}m${s}s"
        }
        else -> {
            val h = totalSeconds.toInt() / 3600
            val m = (totalSeconds.toInt() % 3600) / 60
            if (m == 0) "${h}h" else "${h}h${m}m"
        }
    }
}


@Composable
private fun PanelHeader(title: String, onClose: () -> Unit) {
    val closeDescription = stringResource(R.string.close_panel, title)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Box(
            Modifier
                .size(48.dp)
                .semantics { contentDescription = closeDescription }
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("×", color = Color.White, fontSize = 20.sp) }
    }
}

@Composable
private fun ChoiceTile(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> Amber
                    !enabled -> Color(0xFF22272A)
                    else -> Color(0xFF303638)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.Black else if (!enabled) Color(0xFF6E7A80) else Color.White, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold)
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
internal fun SettingsScreen(
    state: CameraUiState,
    settings: CameraSettings,
    audioPermissionGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
    onSettingsChange: (CameraSettings) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Graphite).testTag("settings-list"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.settings_tab), color = Amber, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.settings_privacy), color = Color.White)
                Text(stringResource(R.string.settings_language), color = Muted)
                Text(stringResource(R.string.settings_technology), color = VerifiedCyan, fontSize = 12.sp)
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
                    stringResource(R.string.audio_hardware_summary, audioOutputLabel(settings.audioOutputFormat))
                } else stringResource(R.string.audio_permission_summary),
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
                ) { Text(stringResource(R.string.grant_microphone)) }
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
            Column(Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.anamorphic), color = Color.White, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.anamorphic_summary), color = Muted, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AnamorphicSqueeze.entries.forEach { squeeze ->
                        TextButton(
                            onClick = { onSettingsChange(settings.copy(anamorphicSqueeze = squeeze)) },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                when (squeeze) {
                                    AnamorphicSqueeze.NONE -> "OFF"
                                    AnamorphicSqueeze.SQUEEZE_1_33X -> "1.33x"
                                    AnamorphicSqueeze.SQUEEZE_1_5X -> "1.5x"
                                    AnamorphicSqueeze.SQUEEZE_2X -> "2x"
                                },
                                color = if (settings.anamorphicSqueeze == squeeze) Amber else Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (settings.anamorphicSqueeze == squeeze) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                if (settings.anamorphicSqueeze.isActive) {
                    Text(stringResource(R.string.anamorphic_output), color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AnamorphicOutputMode.entries.forEach { mode ->
                            TextButton(
                                onClick = { onSettingsChange(settings.copy(anamorphicOutputMode = mode)) },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    when (mode) {
                                        AnamorphicOutputMode.SQUEEZED -> "SQUEEZE+SAR"
                                        AnamorphicOutputMode.DESQUEEZED -> "DESQUEEZE"
                                    },
                                    color = if (settings.anamorphicOutputMode == mode) Amber else Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = if (settings.anamorphicOutputMode == mode) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
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
                title = stringResource(R.string.composition_grid),
                summary = stringResource(R.string.composition_grid_summary),
                checked = settings.compositionGridEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(compositionGridEnabled = it)) },
            )
        }
        item {
            Column(Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.composition_grid_mode), color = Color.White, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompositionGridMode.entries.forEach { mode ->
                        TextButton(onClick = { onSettingsChange(settings.copy(compositionGridMode = mode)) }) {
                            Text(
                                stringResource(
                                    when (mode) {
                                        CompositionGridMode.THIRDS -> R.string.composition_grid_thirds
                                        CompositionGridMode.FOUR_BY_FOUR -> R.string.composition_grid_quarters
                                        CompositionGridMode.DIAGONAL -> R.string.composition_grid_diagonal
                                        CompositionGridMode.GOLDEN_RATIO -> R.string.composition_grid_golden
                                    }
                                ),
                                color = if (settings.compositionGridMode == mode) Amber else Color.White,
                                fontWeight = if (settings.compositionGridMode == mode) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsToggleRow(
                title = stringResource(R.string.horizon_level),
                summary = stringResource(R.string.horizon_level_summary),
                checked = settings.horizonLevelEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(horizonLevelEnabled = it)) },
            )
        }
        item {
            SettingsToggleRow(
                title = stringResource(R.string.tap_exposure_metering),
                summary = stringResource(R.string.tap_exposure_metering_summary),
                checked = settings.tapExposureMeteringEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(tapExposureMeteringEnabled = it)) },
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
            Column(Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.af_lock_behavior), color = Color.White, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.af_lock_behavior_summary), color = Muted, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AfLockBehavior.entries.forEach { behavior ->
                        TextButton(onClick = { onSettingsChange(settings.copy(afLockBehavior = behavior)) }) {
                            Text(
                                if (behavior == AfLockBehavior.FREEZE_CURRENT) stringResource(R.string.af_lock_freeze_current)
                                else stringResource(R.string.af_lock_focus_and_lock),
                                color = if (settings.afLockBehavior == behavior) Amber else Color.White,
                                fontWeight = if (settings.afLockBehavior == behavior) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Color(0xFF1A1F21), RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text("Timecode SMPTE", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Generates timecode for VIDEO and LOG. Saved in the sidecar.", color = Muted, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onSettingsChange(settings.copy(timecodeEnabled = !settings.timecodeEnabled)) }) {
                        Text(if (settings.timecodeEnabled) "ON" else "OFF", color = if (settings.timecodeEnabled) Amber else Color.White, fontWeight = if (settings.timecodeEnabled) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                if (settings.timecodeEnabled) {
                    Text("Modo", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TimecodeMode.entries.forEach { mode ->
                            TextButton(onClick = { onSettingsChange(settings.copy(timecodeMode = mode)) }, contentPadding = PaddingValues(0.dp)) {
                                Text(when (mode) {
                                    TimecodeMode.FREE_RUN -> "FREE"
                                    TimecodeMode.RECORD_RUN -> "REC"
                                    TimecodeMode.REGEN -> "REGEN"
                                }, color = if (settings.timecodeMode == mode) Amber else Color.White, fontSize = 9.sp, fontWeight = if (settings.timecodeMode == mode) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    Text("Tasa", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(24, 25, 30, 50, 60).forEach { fps ->
                            TextButton(onClick = { onSettingsChange(settings.copy(timecodeNominalFps = fps, timecodeDropFrame = false)) }, contentPadding = PaddingValues(0.dp)) {
                                Text("", color = if (settings.timecodeNominalFps == fps && !settings.timecodeDropFrame) Amber else Color.White, fontSize = 9.sp)
                            }
                        }
                    }
                    if (settings.timecodeNominalFps in setOf(30, 60)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onSettingsChange(settings.copy(timecodeDropFrame = !settings.timecodeDropFrame)) }, contentPadding = PaddingValues(0.dp)) {
                                Text(if (settings.timecodeDropFrame) "DF" else "NDF", color = if (settings.timecodeDropFrame) Amber else Color.White, fontSize = 9.sp, fontWeight = if (settings.timecodeDropFrame) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    Text("Start TC: %02d:%02d:%02d%s%02d".format(settings.timecodeStartHours, settings.timecodeStartMinutes, settings.timecodeStartSeconds, if (settings.timecodeDropFrame) ";" else ":", settings.timecodeStartFrames), color = VerifiedCyan, fontSize = 11.sp)
                }
            }
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
        Text(stringResource(R.string.professional_audio), color = VerifiedCyan, fontWeight = FontWeight.Bold)
        if (capabilities == null) {
            Text(stringResource(R.string.audio_measuring), color = Muted, fontSize = 11.sp)
            return@Column
        }
        if (capabilities.formats.isEmpty()) {
            Text(stringResource(R.string.audio_no_route), color = RecordRed, fontSize = 11.sp)
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
            title = stringResource(R.string.audio_output_format),
            choices = capabilities.formats.map { it.name to audioOutputLabel(it) },
            selected = settings.audioOutputFormat.name,
        ) { name -> update(settings.copy(audioOutputFormat = AudioOutputFormat.valueOf(name))) }

        val rates = if (settings.audioOutputFormat == AudioOutputFormat.AAC_MP4) {
            capabilities.aacSampleRates
        } else capabilities.pcmConfigurations.map { it.sampleRate }.distinct().sorted()
        AudioChoiceRow(
            title = stringResource(R.string.audio_sample_rate),
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
                title = stringResource(R.string.audio_bit_depth),
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
            title = stringResource(R.string.audio_channels),
            choices = channels.map { it.toString() to stringResource(if (it == 1) R.string.audio_mono else R.string.audio_stereo) },
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
                title = stringResource(R.string.audio_aac_bitrate),
                choices = capabilities.aacBitratesKbps.map { it.toString() to "$it kbps" },
                selected = settings.audioBitrateKbps.toString(),
            ) { bitrate -> update(settings.copy(audioBitrateKbps = bitrate.toInt())) }
            Text("AAC-LC is embedded in the MP4. AGC applies to LOG recording; it does not apply to standard video (MediaRecorder).", color = Muted, fontSize = 10.sp)
        } else {
            val derived = settings.audioSampleRateHz * settings.audioBitDepth.bits * settings.audioChannels / 1_000
            val container = if (settings.audioOutputFormat == AudioOutputFormat.FLAC) "Lossless compressed FLAC" else "WAV PCM"
            Text("$container · PCM fuente: $derived kbps · archivo sincronizado junto al video", color = VerifiedCyan, fontSize = 10.sp)
        }

        AudioChoiceRow(
            title = stringResource(R.string.audio_public_source),
            choices = capabilities.sources.map { it.name to audioSourceLabel(it) },
            selected = settings.audioSource.name,
        ) { source -> update(settings.copy(audioSource = com.librestatic.opencinecam.media.audio.AudioSourceSelection.valueOf(source))) }

        AudioChoiceRow(
            title = stringResource(R.string.audio_input_device),
            choices = listOf("auto" to stringResource(R.string.audio_automatic)) + capabilities.inputs.map { it.id.toString() to "${it.label} · ID ${it.id}" },
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
                title = "Echo cancellation (AEC)",
                summary = "Useful for speech; off by default.",
                checked = settings.acousticEchoCancelerEnabled,
                enabled = capabilities.acousticEchoCancelerAvailable,
                onCheckedChange = { update(settings.copy(acousticEchoCancelerEnabled = it)) },
            )
        }
        SettingsToggleRow(
                title = "Automatic gain (AGC)",
                summary = "On by default for a consistent level; turn it off to preserve dynamics.",
                checked = settings.automaticGainControlEnabled,
                // Always interactive: hardware AGC when the HAL exposes it, in-process
                // SoftAgc otherwise.
                enabled = true,
                onCheckedChange = { update(settings.copy(automaticGainControlEnabled = it)) },
        )
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 600.dp) 2 else 3
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                choices.chunked(columns).forEach { rowChoices ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowChoices.forEach { (value, label) ->
                            TextButton(
                                onClick = { onSelected(value) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                Text(
                                    label,
                                    color = if (selected == value) Amber else Color.White,
                                    fontWeight = if (selected == value) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                )
                            }
                        }
                        repeat(columns - rowChoices.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private fun formatAudioRate(rate: Int): String = if (rate % 1_000 == 0) "${rate / 1_000} kHz" else "${rate / 1_000.0} kHz"

@Composable
private fun audioOutputLabel(format: AudioOutputFormat): String = stringResource(
    when (format) {
        AudioOutputFormat.AAC_MP4 -> R.string.audio_aac_mp4
        AudioOutputFormat.WAV_PCM -> R.string.audio_wav_pcm
        AudioOutputFormat.FLAC -> R.string.audio_flac
    },
)

@Composable
private fun audioSourceLabel(source: com.librestatic.opencinecam.media.audio.AudioSourceSelection): String = stringResource(
    when (source) {
        com.librestatic.opencinecam.media.audio.AudioSourceSelection.UNPROCESSED -> R.string.audio_source_unprocessed
        com.librestatic.opencinecam.media.audio.AudioSourceSelection.VOICE_RECOGNITION -> R.string.audio_source_voice_recognition
        com.librestatic.opencinecam.media.audio.AudioSourceSelection.MIC -> R.string.audio_source_microphone
    },
)

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

/** Formats an EV value (e.g. -1.33, +0.5, 0). Returns "0" for null/zero. */
private fun formatEv(ev: Float?): String {
    val value = ev ?: 0f
    if (value == 0f) return "0"
    val sign = if (value > 0f) "+" else "−"
    val rounded = (value * 100).roundToInt() / 100f
    val formatted = if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else "%.2f".format(rounded)
    return "$sign$formatted"
}

@Composable
private fun FocusPullDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    onClose: () -> Unit,
) {
    val descriptor = state.descriptor ?: return
    val minDistance = descriptor.minimumFocusDistance ?: 0f
    val supportsManualFocus = minDistance > 0f
    val currentDiopters = state.requestedFocusDiopters ?: state.focusDistanceDiopters ?: 0f
    val focusSliderPos = if (supportsManualFocus) (currentDiopters / minDistance).coerceIn(0f, 1f) else 0f
    val marks = state.focusMarks
    val markLabels = listOf("A", "B", "C", "D")
    val pullActive = state.focusPullActive

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PanelHeader(if (pullActive) "FOCUS PULL" else "FOCO", onClose)

        Text(
            if (supportsManualFocus) {
                val effective = state.focusDistanceDiopters
                val requested = state.requestedFocusDiopters
                if (pullActive) {
                    "-> %.1fD".format(state.focusPullTargetDiopters ?: 0f)
                } else if (requested != null) {
                    "%.1fD".format(requested) + (effective?.let { " (%.1fD)".format(it) } ?: "")
                } else if (effective != null) {
                    "%.1fD".format(effective)
                } else "AUTO"
            } else "AUTO (lente fija)",
            color = if (pullActive) RecordRed else Amber,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        if (supportsManualFocus) {
            Slider(
                value = focusSliderPos,
                onValueChange = { pos ->
                    binder?.setManualFocus(pos * minDistance)
                },
                modifier = Modifier.fillMaxWidth().height(28.dp),
                enabled = !pullActive,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0D", color = Muted, fontSize = 9.sp)
                TextButton(
                    onClick = { binder?.setManualFocus(null) },
                    contentPadding = PaddingValues(0.dp),
                ) { Text(stringResource(R.string.auto_value), color = VerifiedCyan, fontSize = 10.sp) }
                Text("%.1fD".format(minDistance), color = Muted, fontSize = 9.sp)
            }
        }

        if (supportsManualFocus && !pullActive) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))

            Text("MARCAS", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                markLabels.forEach { label ->
                    val savedDiopters = marks[label]
                    val hasMark = savedDiopters != null
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(
                                if (hasMark) Color(0xFF1A3A1A) else Color(0xFF1A1F21),
                                RoundedCornerShape(6.dp),
                            )
                            .pointerInput(label, hasMark, currentDiopters) {
                                detectTapGestures(
                                    onTap = {
                                        if (hasMark) {
                                            binder?.startFocusPull(
                                                savedDiopters!!,
                                                settings.focusPullDurationMs,
                                                settings.focusPullEasing,
                                            )
                                        }
                                    },
                                    onLongPress = {
                                        if (hasMark) {
                                            binder?.clearFocusMark(label)
                                        } else {
                                            binder?.setFocusMark(label, currentDiopters)
                                        }
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                label,
                                color = if (hasMark) VerifiedCyan else Muted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (hasMark) {
                                Text("%.1fD".format(savedDiopters), color = Muted, fontSize = 7.sp)
                            } else {
                                Text("SET", color = Color(0xFF444444), fontSize = 7.sp)
                            }
                        }
                    }
                }
            }
            Text(
                "Tap: pull - Long: guardar/borrar",
                color = Color(0xFF555555),
                fontSize = 8.sp,
            )

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))

            Text("DURACION %.1fs".format(settings.focusPullDurationMs / 1000.0), color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = settings.focusPullDurationMs.toFloat(),
                onValueChange = { v ->
                    onSettingsChanged(settings.copy(focusPullDurationMs = (v / 500).toInt() * 500L))
                },
                valueRange = 500f..10_000f,
                steps = 18,
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )

            Text("CURVA", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FocusPullEasing.entries.forEach { easing ->
                    TextButton(
                        onClick = { onSettingsChanged(settings.copy(focusPullEasing = easing)) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            when (easing) {
                                FocusPullEasing.LINEAR -> "LIN"
                                FocusPullEasing.EASE_IN -> "IN"
                                FocusPullEasing.EASE_OUT -> "OUT"
                                FocusPullEasing.EASE_IN_OUT -> "S-CURVE"
                            },
                            color = if (settings.focusPullEasing == easing) Amber else Color.White,
                            fontSize = 9.sp,
                            fontWeight = if (settings.focusPullEasing == easing) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        if (pullActive) {
            Button(
                onClick = { binder?.cancelFocusPull() },
                colors = ButtonDefaults.buttonColors(containerColor = RecordRed, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("CANCELAR PULL", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
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

private fun gridModeLabel(mode: CompositionGridMode): String = when (mode) {
    CompositionGridMode.THIRDS -> "T"
    CompositionGridMode.FOUR_BY_FOUR -> "4"
    CompositionGridMode.DIAGONAL -> "D"
    CompositionGridMode.GOLDEN_RATIO -> "G"
}

private fun rotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
