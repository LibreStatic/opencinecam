/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.ReadOnlyComposable
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material3.MaterialTheme
import com.librestatic.opencinecam.ui.theme.CaptureTheme
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.librestatic.opencinecam.camera.MonitoringOptions
import com.librestatic.opencinecam.camera.monitoringSampleFresh
import com.librestatic.opencinecam.camera.MonitoringSignalDomain
import com.librestatic.opencinecam.camera.monitoringPreviewScale
import com.librestatic.opencinecam.camera.monitoringDisplayPoint
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.librestatic.opencinecam.ui.viewfinder.LocalChromeOpacity
import com.librestatic.opencinecam.ui.viewfinder.chromePanel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.tooling.preview.Preview
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.toSpec
import com.librestatic.opencinecam.storage.LocalMediaRepository
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.Camera2LogProfile
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.AfLockBehavior
import com.librestatic.opencinecam.camera.LockState
import com.librestatic.opencinecam.camera.TapFocusState
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ShutterUnit
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

private val Graphite: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
private val Panel: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f).chromePanel()
private val Amber: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
private val VerifiedCyan: Color @Composable @ReadOnlyComposable get() = LocalCineColors.current.verified
private val RecordRed: Color @Composable @ReadOnlyComposable get() = LocalCineColors.current.record
private val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val OkGreen: Color @Composable @ReadOnlyComposable get() = LocalCineColors.current.ok

private enum class AppSection { CAPTURE, MEDIA, SETTINGS }
private enum class SettingsPage { MAIN, ABOUT, CAPABILITIES }
private enum class ControlDial { RESOLUTION, FPS, INT, ISO, SHUTTER, FOCUS, WB, EV }

@Composable
fun CameraRootScreen(splash: SplashHandoff = SplashHandoff(onScreen = false), onReady: () -> Unit = {}) {
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
    val onboardingStore = remember(context) { OnboardingStore(context) }
    var onboardingDone by remember { mutableStateOf(onboardingStore.isCompleted()) }
    val fallbackState = remember { MutableStateFlow(CameraUiState()) }
    val stateFlow = binder?.cameraStates ?: fallbackState
    val state by stateFlow.collectAsStateWithLifecycle()
    val settingsRepository = remember(context) { SettingsRepositories.get(context) }
    val settings by settingsRepository.states.collectAsStateWithLifecycle()
    // A normal start keeps the splash until this: the camera has opened (or failed), or there is a
    // screen other than the viewfinder to show first.
    val readyForSplash = !permissionGranted || !onboardingDone || state.phase != CameraUiPhase.PREPARING
    LaunchedEffect(readyForSplash) { if (readyForSplash) onReady() }
    val foldDisplays = LocalFoldDisplayCoordinator.current
    val foldFallback = remember { MutableStateFlow(FoldDisplayState()) }
    val foldState by (foldDisplays?.states ?: foldFallback).collectAsStateWithLifecycle()
    LaunchedEffect(foldDisplays, state.phase, state.recordingFinalizing, state.recordingPauseStatus, state.recordingElapsedMs, state.errorCode, state.selectedMode, state.countdownSeconds) {
        foldDisplays?.updateCameraState(state)
    }
    DisposableEffect(foldDisplays, binder) {
        foldDisplays?.updatePreviewPort(binder?.subjectPreview)
        foldDisplays?.updateSelfRoleObserver { binder?.setSelfRecordingActive(it) }
        onDispose {
            foldDisplays?.updatePreviewPort(null)
            foldDisplays?.updateSelfRoleObserver(null)
        }
    }
    LaunchedEffect(foldDisplays, settings.subjectDisplay.brightness) { foldDisplays?.applyBrightness() }
    var section by rememberSaveable { mutableStateOf(AppSection.CAPTURE) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    val settingsStateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = section != AppSection.CAPTURE && settingsPage == SettingsPage.MAIN) {
        section = AppSection.CAPTURE
    }

    BackHandler(enabled = section == AppSection.SETTINGS && settingsPage != SettingsPage.MAIN) {
        settingsPage = SettingsPage.MAIN
    }

    LaunchedEffect(state.audioCapabilities) {
        state.audioCapabilities?.let { capabilities ->
            val normalized = settings.normalizedFor(capabilities)
            if (normalized != settings) {
                settingsRepository.set(normalized)
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
            )
            in CameraUiState.videoProfileModes -> settings.copy(
                videoWidth = state.targetVideoWidth,
                videoHeight = state.targetVideoHeight,
                videoFps = state.targetFps,
            )
            else -> settings
        }
        if (updated != settings) {
            settingsRepository.set(updated)
        }
    }

    @Composable
    fun MainContent() {

    if (!permissionGranted) {
        PermissionScreen { permissionLauncher.launch(Manifest.permission.CAMERA) }
        return
    }

    val operatorActions = rememberOperatorActions(state, settings, binder, foldDisplays, foldState, section == AppSection.CAPTURE)
    CompositionLocalProvider(LocalOperatorActions provides operatorActions,
        LocalAudioListeningActions provides { binder?.reconnectAudioListening() }) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (section == AppSection.CAPTURE) {
            // The chrome over the viewfinder stays dark in every theme.
            CaptureTheme {
            CaptureSurface(
                state = state,
                fold = foldState,
                binder = binder,
                settings = settings,
                onSettingsChanged = { updated ->
                    settingsRepository.set(updated)
                },
                onOpenMedia = { binder?.cancelSelfTimer(); section = AppSection.MEDIA },
                onOpenSettings = {
                    binder?.cancelSelfTimer()
                    settingsPage = SettingsPage.MAIN
                    section = AppSection.SETTINGS
                },
            )
            }
        } else {
            HingeSafeSettingsPane(foldState.hinge) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Box(Modifier.weight(1f)) {
                    when (section) {
                        AppSection.MEDIA -> MediaCatalogScreen(settings.gallery,
                            onSettings = { gallery -> settingsRepository.update { it.copy(gallery = gallery) } },
                            sharingSettings = settings.mediaSharing,
                            onSharingSettings = { sharing -> settingsRepository.update { it.copy(mediaSharing = sharing) } },
                            proxySettings = settings.proxy,
                            onProxySettings = { proxy -> settingsRepository.update { it.copy(proxy = proxy) } },
                            playbackSettings = settings.playback,
                            onPlaybackSettings = { playback -> settingsRepository.update { it.copy(playback = playback) } })
                        AppSection.SETTINGS -> if (settingsPage == SettingsPage.ABOUT) {
                            AboutScreen(
                                onBack = { settingsPage = SettingsPage.MAIN },
                                onReplayTour = {
                                    onboardingStore.reset()
                                    settingsPage = SettingsPage.MAIN
                                    onboardingDone = false
                                },
                            )
                        } else if (settingsPage == SettingsPage.CAPABILITIES) {
                            CameraCapabilitiesScreen(
                                cameras = state.cameras,
                                activeCameraId = state.selectedCameraId,
                                onBack = { settingsPage = SettingsPage.MAIN },
                            )
                        } else {
                            settingsStateHolder.SaveableStateProvider("settings") {
                            SettingsScreen(
                                state = state,
                                settings = settings,
                                audioPermissionGranted = audioPermissionGranted,
                                onRequestAudioPermission = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                onOpenAbout = { settingsPage = SettingsPage.ABOUT },
                                onSettingsChange = settingsRepository::set,
                                onApplyPreset = binder?.let { owner -> { preset -> owner.applyPreset(preset) } },
                                onOpenCapabilities = { settingsPage = SettingsPage.CAPABILITIES },
                            )
                            }
                        }
                        AppSection.CAPTURE -> Unit
                    }
                }
                NavigationBar(section) {
                    settingsPage = SettingsPage.MAIN
                    section = it
                }
            }
            }
        }
    }
}
    }

    val reducedMotion = LocalReducedMotion.current
    // 0 shows the wizard, 1 the app. Finishing the wizard runs it up once the app has drawn its first
    // frame: composing the capture screen is slow the first time, and an animation started in the
    // same frame would be over before anything reached the screen.
    val handoff = remember { Animatable(if (onboardingDone) 1f else 0f) }
    // Derived, so the running handoff only redraws the layers instead of recomposing this screen.
    val handoffRunning by remember { derivedStateOf { handoff.value < 1f } }
    val wizardShown = !onboardingDone || handoffRunning
    val finished = rememberUpdatedState(onboardingDone)
    val cameraSettled = rememberUpdatedState(
        !permissionGranted || (state.phase != CameraUiPhase.PREPARING && state.phase != CameraUiPhase.OPENING && state.phase != CameraUiPhase.READY),
    )
    // Replaying the tour from About brings the wizard back at full strength.
    LaunchedEffect(onboardingDone) { if (!onboardingDone) handoff.snapTo(0f) }
    // An opaque themed floor under both layers: a fading frame never reveals the window.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    if (onboardingDone) Box(
        Modifier.fillMaxSize().graphicsLayer {
            val enter = FastOutSlowInEasing.transform(((handoff.value * 720f - 120f) / 600f).coerceIn(0f, 1f))
            alpha = ((handoff.value * 720f - 120f) / 420f).coerceIn(0f, 1f)
            scaleX = 0.94f + 0.06f * enter; scaleY = scaleX
        },
    ) {
        LaunchedEffect(Unit) {
            if (handoff.value >= 1f) return@LaunchedEffect
            if (reducedMotion) {
                handoff.snapTo(1f)
                return@LaunchedEffect
            }
            // Opening the camera and attaching its preview also block the main thread for a moment;
            // the wizard stays up (the app is already composed under it) until that has passed.
            withTimeoutOrNull(HandoffWaitMillis) { snapshotFlow { cameraSettled.value }.first { it } }
            awaitSmoothFrames()
            handoff.animateTo(1f, tween(720, easing = LinearEasing))
        }
        MainContent()
    }
    if (wizardShown) Box(
        Modifier.fillMaxSize().graphicsLayer {
            val t = if (finished.value) handoff.value * 720f else 0f
            alpha = 1f - (t / 300f).coerceIn(0f, 1f)
            val grow = FastOutSlowInEasing.transform((t / 450f).coerceIn(0f, 1f))
            scaleX = 1f + 0.06f * grow; scaleY = scaleX
        },
    ) {
        OnboardingScreen(
            splash = splash,
            onPermissionsChanged = {
                permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                audioPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                binder?.refreshAudioCapabilities()
            },
            onFinished = {
                onboardingStore.markCompleted()
                onboardingDone = true
            },
        )
    }
    }

}

private const val HandoffWaitMillis = 1_500L

/** Returns after three frames in a row arrive on time, or after a second whatever happens. */
private suspend fun awaitSmoothFrames() {
    val start = withFrameNanos { it }
    var last = start
    var smooth = 0
    while (smooth < 3 && last - start < 1_000_000_000L) {
        val now = withFrameNanos { it }
        smooth = if (now - last < 34_000_000L) smooth + 1 else 0
        last = now
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
        Text(stringResource(R.string.camera_permission_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.camera_permission_body), color = Muted)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = MaterialTheme.colorScheme.onPrimary)) {
            Text(stringResource(R.string.grant_camera))
        }
    }
}

@Composable
internal fun CaptureSurface(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    onOpenMedia: () -> Unit,
    onOpenSettings: () -> Unit,
    fold: FoldDisplayState = FoldDisplayState(),
) {
    val zebra = settings.zebraEnabled
    val peaking = settings.peakingEnabled
    val histogram = settings.histogramEnabled
    val histogramMode = settings.histogramMode
    val showGrid = settings.compositionGridEnabled
    val gridMode = settings.compositionGridMode
    val showHorizon = settings.horizonLevelEnabled
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    val knownGoodStore = remember(context) { KnownGoodCaptureStore(context) }
    val currentSettings by rememberUpdatedState(settings)
    // Record the configuration only on the transition into a live preview: a change requested
    // while previewing must prove itself by reopening before it becomes the way back.
    LaunchedEffect(state.phase) {
        if (state.phase == CameraUiPhase.PREVIEWING) {
            knownGoodStore.save(KnownGoodCapture.of(currentSettings, state.selectedMode, state.selectedCameraId))
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black).onGloballyPositioned { windowOrigin = it.positionInWindow() }) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.roundToPx() }
        val fullHeightPx = with(density) { maxHeight.roundToPx() }
        val panes = if (settings.subjectDisplay.adaptToHinge) foldPanes(
            fullWidthPx, fullHeightPx, windowOrigin.x.roundToInt(), windowOrigin.y.roundToInt(), fold.hinge,
            with(density) { 16.dp.roundToPx() }, with(density) { 180.dp.roundToPx() }, settings.subjectDisplay.swapPanes,
        ) else null
        val widthPx = panes?.preview?.width ?: fullWidthPx
        val heightPx = panes?.preview?.height ?: fullHeightPx
        fun paneModifier(pane: FoldPane?): Modifier = if (pane == null) Modifier.fillMaxSize() else with(density) {
            Modifier.offset(pane.left.toDp(), pane.top.toDp()).size(pane.width.toDp(), pane.height.toDp()).clipToBounds()
        }
        LaunchedEffect(binder, widthPx, heightPx) {
            if (binder != null && widthPx > 0 && heightPx > 0 && state.cameras.isEmpty()) binder.prepare(widthPx, heightPx)
        }

        val descriptor = state.descriptor
        val landscape = widthPx > heightPx
        val previewStreamSize = descriptor?.let {
            if (state.selectedMode == CaptureMode.LOG) {
                state.activeLogProfile?.size ?: it.preferredLogProfile?.size ?: it.previewSize
            } else if (state.selectedMode in CameraUiState.videoProfileModes) {
                state.activeVideoProfile?.size ?: it.previewSize
            } else {
                it.previewSize
            }
        }
        val squeezeFactor = (state.effectiveSettings?.takeIf { state.phase == CameraUiPhase.RECORDING }
            ?: settings).anamorphicSqueeze.factor
        // Read on every constraint change: a quarter rotation always swaps widthPx/heightPx, and a
        // half rotation keeps the sensor-to-display quarter-turn parity (and so the ratio).
        val displayRotationDegrees = rotationDegrees(LocalView.current.display?.rotation ?: Surface.ROTATION_0)
        val previewDisplayRatio = previewStreamSize?.let { size ->
            previewDisplayRatio(size.width, size.height, squeezeFactor, descriptor?.sensorOrientation ?: 90, displayRotationDegrees)
        }
        // A short landscape window moves the capture controls into side rails (see
        // AdaptiveCaptureChrome); the viewfinder is then fitted between them instead of under them.
        // The decision uses the same safe-drawing size the chrome measures, so both always agree.
        val safeInsets = WindowInsets.safeDrawing
        val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
        val safeWidthDp = with(density) {
            (fullWidthPx - safeInsets.getLeft(density, layoutDirection) - safeInsets.getRight(density, layoutDirection)).toDp()
        }
        val safeHeightDp = with(density) { (fullHeightPx - safeInsets.getTop(density) - safeInsets.getBottom(density)).toDp() }
        val fullChrome = panes == null && !(state.selfRecordingActive && settings.subjectDisplay.selfMinimalControls)
        val sideRails = fullChrome &&
            captureChromeLayout(safeWidthDp.value, safeHeightDp.value) == CaptureChromeLayout.SIDE_RAILS
        // The stacked layout fits the viewfinder between the top bar and the deck the chrome
        // measures, so no part of the frame hides behind the controls.
        val stacked = fullChrome && !sideRails
        val stackedTopBar = if (captureWindowProfile(safeWidthDp.value, safeHeightDp.value) == CaptureWindowProfile.COMPACT_PORTRAIT)
            SLIM_TOP_BAR_HEIGHT_DP.dp else STACKED_TOP_BAR_HEIGHT_DP.dp
        var stackedDeckHeightPx by remember { mutableIntStateOf(0) }
        // Translucent chrome floats over a viewfinder that spans the whole window. The pane is not
        // inset, so a filled (cropped) frame overflows the physical screen rather than relying on
        // Compose clipping, which a SurfaceView does not reliably honour.
        val overlayChrome = fullChrome && settings.translucentChrome
        // The deck slides away during a take, and the stacked viewfinder grows into its space.
        val viewfinderExpansion by animateFloatAsState(
            if (stacked && !overlayChrome && state.phase == CameraUiPhase.RECORDING) 1f else 0f,
            tween(RECORDING_VIEWFINDER_EXPANSION_MS, easing = FastOutSlowInEasing),
            label = "recording-viewfinder-expansion",
        )
        val previewPaneModifier = if (overlayChrome) {
            paneModifier(null)
        } else if (sideRails) {
            paneModifier(null)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = SIDE_RAIL_START_WIDTH_DP.dp, end = SIDE_RAIL_END_WIDTH_DP.dp)
        } else if (stacked) {
            paneModifier(null)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = stackedTopBar, bottom = with(density) { stackedDeckHeightPx.toDp() })
        } else paneModifier(panes?.preview)
        BoxWithConstraints(previewPaneModifier.testTag("fold-preview-pane")) {
        if (descriptor != null && binder != null) {
            val streamSize = requireNotNull(previewStreamSize)
            val displayRatio = requireNotNull(previewDisplayRatio)
            // aspectRatio must receive the unconstrained Box bounds. Applying fillMaxWidth
            // first can force a too-wide landscape view and make Compose violate the ratio
            // when the remaining height is smaller than width / ratio.
            val previewSizeModifier = if (overlayChrome && settings.viewfinderScale == ViewfinderScale.FILL) {
                val filled = filledPreviewViewport(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), displayRatio)
                with(density) {
                    Modifier.wrapContentSize(unbounded = true).requiredSize(filled.width.toDp(), filled.height.toDp())
                }
            } else Modifier.aspectRatio(displayRatio)
            // The frame grows by a render transform, not by layout. LOG and the GPU viewfinder size
            // their buffer from the layout, and a resized surface is reattached, which reconfigures
            // a recorder session mid-take. The overlay has no surface, so it is laid out instead.
            val paneWidth = constraints.maxWidth.toFloat()
            val paneHeight = constraints.maxHeight.toFloat()
            val deckSpace = if (stacked && !overlayChrome) stackedDeckHeightPx.toFloat() else 0f
            val restingFrame = fittedPreviewViewport(paneWidth, paneHeight, displayRatio)
            val recordingFrame = recordingPreviewViewport(paneWidth, paneHeight + deckSpace, 0f, deckSpace, displayRatio, viewfinderExpansion)
            val frameGrown = recordingFrame != restingFrame && restingFrame.width > 0f
            val frameScale = if (frameGrown) recordingFrame.width / restingFrame.width else 1f
            val frameShift = (recordingFrame.top + recordingFrame.height / 2f) - (restingFrame.top + restingFrame.height / 2f)
            val overlaySizeModifier = if (frameGrown) with(density) {
                Modifier.offset { IntOffset(0, frameShift.roundToInt()) }
                    .requiredSize(recordingFrame.width.toDp(), recordingFrame.height.toDp())
            } else previewSizeModifier
            PreviewSurfaceView(
                descriptor.cameraId,
                streamSize.width,
                streamSize.height,
                state.gpuViewfinder || state.selectedMode == CaptureMode.LOG,
                state.targetFps,
                descriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT && !state.gpuViewfinder && state.selectedMode != CaptureMode.LOG,
                widthPx,
                heightPx,
                binder,
                Modifier.align(Alignment.Center)
                    .graphicsLayer {
                        scaleX = frameScale
                        scaleY = frameScale
                        translationY = frameShift
                    }
                    .then(previewSizeModifier),
            )
            // A frame narrower than its pane leaves a strip at each side (a tablet or an unfolded
            // screen in portrait). The scopes panel then sits in the end strip, clear of the zoom
            // rocker, instead of covering the picture. Floating chrome may occupy that strip.
            val framedWidthPx = if (overlayChrome && settings.viewfinderScale == ViewfinderScale.FILL) constraints.maxWidth.toFloat()
                else minOf(constraints.maxWidth.toFloat(), recordingFrame.width)
            val sideStrip = with(density) { ((constraints.maxWidth - framedWidthPx) / 2f).toDp() }
            val scopesBeside = !overlayChrome && sideStrip - SCOPES_BESIDE_END_CLEARANCE - SCOPES_BESIDE_START_GAP >= SCOPES_BESIDE_MIN_WIDTH
            MonitoringOverlay(
                state = state,
                options = settings.monitoring,
                sourceWidth = streamSize.width,
                sourceHeight = streamSize.height,
                squeezeFactor = squeezeFactor,
                showZebra = zebra,
                showPeaking = peaking,
                showHistogram = histogram,
                histogramMode = histogramMode,
                showGrid = showGrid,
                gridMode = gridMode,
                showHorizon = showHorizon,
                // The capture chrome stacks the histogram with the zoom and audio instruments;
                // only the minimal self-recording chrome leaves it to the overlay.
                drawHistogram = state.selfRecordingActive && settings.subjectDisplay.selfMinimalControls,
                modifier = Modifier.align(Alignment.Center).then(overlaySizeModifier),
                drawScopesPanel = !scopesBeside,
                // 52 dp keys 8 dp from the window edge, plus a gap.
                scopesPanelEndPadding = if (stacked && captureWindowProfile(safeWidthDp.value, safeHeightDp.value) == CaptureWindowProfile.COMPACT_PORTRAIT) 72.dp else 12.dp,
            )
            if (scopesBeside) ProfessionalScopesPanel(
                state, settings.monitoring, rememberScopeAnalysisFresh(state, settings.monitoring),
                Modifier.align(Alignment.CenterEnd).padding(end = SCOPES_BESIDE_END_CLEARANCE)
                    .width(sideStrip - SCOPES_BESIDE_END_CLEARANCE - SCOPES_BESIDE_START_GAP).testTag("monitoring-panel-beside"),
            )
        }

        }
        Box(paneModifier(panes?.controls).testTag("fold-controls-pane")) {
        if (state.selfRecordingActive && settings.subjectDisplay.selfMinimalControls) {
            SelfCaptureChrome(state, binder, settings, onSettingsChanged, onOpenSettings)
        } else CompositionLocalProvider(LocalChromeOpacity provides settings.chromeOpacity.takeIf { overlayChrome }) {
        AdaptiveCaptureChrome(
            state = state,
            binder = binder,
            settings = settings,
            landscape = landscape,
            previewAspectRatio = previewDisplayRatio,
            previewGesturesEnabled = panes == null,
            overlayViewfinderScale = settings.viewfinderScale.takeIf { overlayChrome },
            onStackedDeckHeight = { stackedDeckHeightPx = it },
            viewfinderExpansion = viewfinderExpansion,
            zebra = zebra,
            peaking = peaking,
            histogram = histogram,
            histogramMode = histogramMode,
            showGrid = showGrid,
            gridMode = gridMode,
            showHorizon = showHorizon,
            onToggleZebra = { onSettingsChanged(settings.copy(zebraEnabled = !zebra)) },
            onTogglePeaking = { onSettingsChanged(settings.copy(peakingEnabled = !peaking)) },
            onToggleHistogram = {
                val updated = !histogram
                onSettingsChanged(settings.copy(histogramEnabled = updated, histogramMode = histogramMode))
            },
            onCycleHistogramMode = {
                val updated = if (histogramMode == HistogramMode.RGB) HistogramMode.LUMA else HistogramMode.RGB
                onSettingsChanged(settings.copy(histogramEnabled = histogram, histogramMode = updated))
            },
            onToggleGrid = {
                val updated = !showGrid
                onSettingsChanged(settings.copy(compositionGridEnabled = updated, compositionGridMode = gridMode))
            },
            onCycleGridMode = {
                val updated = CompositionGridMode.entries[(gridMode.ordinal + 1) % CompositionGridMode.entries.size]
                onSettingsChanged(settings.copy(compositionGridEnabled = true, compositionGridMode = updated))
            },
            onToggleHorizon = {
                val updated = !showHorizon
                onSettingsChanged(settings.copy(horizonLevelEnabled = updated))
            },
            onSettingsChanged = onSettingsChanged,
            onOpenMedia = onOpenMedia,
            onOpenSettings = onOpenSettings,
        )
        }

        }
        Box(paneModifier(panes?.preview)) {
        if (state.countdownSeconds > 0) CountdownBadge(state.countdownSeconds, Modifier.align(Alignment.Center))
        val cameraLoading = state.phase == CameraUiPhase.PREPARING || state.phase == CameraUiPhase.OPENING || state.phase == CameraUiPhase.READY
        val reducedMotion = LocalReducedMotion.current
        AnimatedVisibility(
            visible = cameraLoading,
            modifier = Modifier.align(Alignment.Center),
            enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
            exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(400)) + shrinkVertically(tween(500, delayMillis = 150)),
        ) {
            Column(
                Modifier.background(Panel, RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LoadingIndicator(color = Amber)
                Text(stringResource(R.string.camera_loading), color = Color.White)
            }
        }
        if (state.phase == CameraUiPhase.ERROR) {
            val knownGood = remember(state.errorCode, state.message) { knownGoodStore.load() }
            // Fall back to the stock 1080p30 geometry when nothing has previewed on this install yet.
            val restoreTarget = (knownGood ?: KnownGoodCapture.safeDefault(state.selectedMode))
                .takeIf { it.differsFrom(settings, state.selectedMode) }
            CameraErrorSheet(
                state = state,
                failed = KnownGoodCapture.of(settings, state.selectedMode, state.selectedCameraId),
                restoreTarget = restoreTarget,
                onRetry = { binder?.recoverPreview(widthPx, heightPx) },
                onRestore = restoreTarget?.let { target -> {
                    val restored = target.applyTo(settings)
                    onSettingsChanged(restored)
                    binder?.applySettings(restored)
                    if (target.mode != state.selectedMode) binder?.selectMode(target.mode, reopen = false)
                    binder?.recoverPreview(widthPx, heightPx)
                } },
                onOpenSettings = onOpenSettings,
            )
        }
        }
    }
}

/**
 * Operational error sheet. The scrim swallows input so nothing behind it, REC included, reads as
 * actionable; the operator sees the cause in plain words and can open the raw technical text,
 * which is never shown by default on the shooting surface.
 */
@Composable
private fun BoxScope.CameraErrorSheet(
    state: CameraUiState,
    failed: KnownGoodCapture,
    restoreTarget: KnownGoodCapture?,
    onRetry: () -> Unit,
    onRestore: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    var showDetails by remember(state.message, state.errorCode) { mutableStateOf(false) }
    val raw = state.message.orEmpty()
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = .72f))
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("camera-error-scrim"),
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .background(Color(0xFF12171A), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF344047), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .testTag("camera-error-sheet"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CineGlyph(CineIcon.WARNING, RecordRed, Modifier.size(32.dp))
        Text(stringResource(R.string.camera_error), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(operatorErrorText(raw), color = Color(0xFFAAB4BA), fontSize = 14.sp, textAlign = TextAlign.Center)
        // Name the configuration that failed, so the operator can tell what "restore" moves away from.
        Text(
            stringResource(R.string.error_failed_configuration, captureSummary(failed)),
            color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
            modifier = Modifier.testTag("camera-error-failed-config"),
        )
        // Retrying replays the configuration that failed, so restoring the last one that
        // previewed leads, alone on its row; the lighter actions share the row below.
        val primary = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black)
        val outline = BorderStroke(1.dp, Color(0xFF344047))
        if (onRestore != null) {
            Button(onClick = onRestore, colors = primary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("camera-error-restore")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.error_restore_previous), fontWeight = FontWeight.Bold)
                    restoreTarget?.let { Text(captureSummary(it), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().testTag("camera-error-actions"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val action = Modifier.weight(1f).heightIn(min = 48.dp)
            if (onRestore != null) OutlinedButton(onClick = onRetry, border = outline, modifier = action) {
                Text(stringResource(R.string.retry), color = Color.White, textAlign = TextAlign.Center)
            } else Button(onClick = onRetry, colors = primary, modifier = action) {
                Text(stringResource(R.string.retry), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onOpenSettings, border = outline, modifier = action.testTag("camera-error-settings")) {
                Text(stringResource(R.string.error_open_settings), color = Color.White, textAlign = TextAlign.Center)
            }
        }
        if (raw.isNotBlank() || state.errorCode != null) {
            TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (showDetails) R.string.error_details_hide else R.string.error_details_show), color = VerifiedCyan)
            }
            if (showDetails) SelectionContainer {
                Text(
                    listOfNotNull(state.errorCode, raw.takeIf { it.isNotBlank() }).joinToString("\n"),
                    color = Muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().testTag("camera-error-details"),
                )
            }
        }
    }
}

@Composable
private fun captureSummary(capture: KnownGoodCapture): String =
    listOfNotNull(
        capture.geometry()?.let { (w, h, _) -> "$w×$h" },
        capture.geometry()?.third?.let { "$it fps" },
        modeLabel(capture.mode),
    ).joinToString(" · ")

/** Drops the exception class a failure message may carry, keeping the sentence the operator can act on. */
internal fun operatorErrorText(message: String): String =
    message.replace(Regex("""^(?:[a-z][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error)\s*:\s*"""), "").trim()
        .ifEmpty { message.trim() }

/**
 * A monitoring chip: symbol plus a short visible label, so no tool has to be memorised by letter.
 * With [cycleState] null it is an on/off switch whose ON state carries a check mark as well as the
 * amber container; otherwise it is a button that advances a mode and shows that mode as its label.
 */
@Composable
private fun MonitorToggle(icon: CineIcon, label: String, description: String, enabled: Boolean, onClick: () -> Unit,
    cycleState: String? = null, modifier: Modifier = Modifier) {
    val on = enabled && cycleState == null
    val content = if (on) Color.Black else Color.White
    Row(
        modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = description
                if (cycleState != null) stateDescription = cycleState
            }
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Amber else Color(0xFF1B2226))
            .border(1.dp, if (on) Amber else Color(0xFF344047), RoundedCornerShape(8.dp))
            .then(
                if (cycleState != null) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = { onClick() })
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CineGlyph(icon, content, Modifier.size(20.dp))
        Text(label, color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (on) CineGlyph(CineIcon.CHECK, content, Modifier.size(16.dp))
    }
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
    val histogramModeTitle = stringResource(if (histogramMode == HistogramMode.RGB) R.string.histogram_mode_rgb else R.string.histogram_mode_luma)
    val gridModeTitle = stringResource(compositionGridModeTitle(gridMode))
    // Pairs share a row: each tool sits beside its mode, and the level closes the grid.
    val cell = Modifier.width(148.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonitorToggle(CineIcon.ZEBRA, stringResource(R.string.monitor_zebra_short), stringResource(R.string.monitor_zebra), zebra, onToggleZebra, modifier = cell)
            MonitorToggle(CineIcon.PEAKING, stringResource(R.string.monitor_peaking_short), stringResource(R.string.monitor_peaking), peaking, onTogglePeaking, modifier = cell)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonitorToggle(CineIcon.HISTOGRAM, stringResource(R.string.monitor_histogram_short), stringResource(R.string.monitor_histogram), histogram, onToggleHistogram, modifier = cell)
            MonitorToggle(CineIcon.HISTOGRAM_MODE, histogramModeTitle, stringResource(R.string.monitor_histogram_mode), true, onCycleHistogramMode,
                cycleState = histogramModeTitle, modifier = cell)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonitorToggle(CineIcon.GRID, stringResource(R.string.monitor_grid_short), stringResource(R.string.monitor_grid), showGrid, onToggleGrid, modifier = cell)
            MonitorToggle(CineIcon.GRID_MODE, gridModeTitle, stringResource(R.string.monitor_grid_mode), true, onCycleGridMode,
                cycleState = gridModeTitle, modifier = cell)
        }
        MonitorToggle(CineIcon.LEVEL, stringResource(R.string.monitor_horizon_short), stringResource(R.string.monitor_horizon), showHorizon, onToggleHorizon, modifier = cell)
    }
}

@Composable
private fun MonitoringOverlay(
    state: CameraUiState,
    options: MonitoringOptions,
    sourceWidth: Int,
    sourceHeight: Int,
    squeezeFactor: Float,
    showZebra: Boolean,
    showPeaking: Boolean,
    showHistogram: Boolean,
    histogramMode: HistogramMode,
    showGrid: Boolean,
    gridMode: CompositionGridMode,
    showHorizon: Boolean,
    drawHistogram: Boolean,
    modifier: Modifier = Modifier,
    /** False when the caller lays the scopes panel out beside the frame instead of over it. */
    drawScopesPanel: Boolean = true,
    /** Clears the F-key column that compact portrait chrome lays over the frame's end edge. */
    scopesPanelEndPadding: androidx.compose.ui.unit.Dp = 12.dp,
) {
    val context = LocalContext.current
    val displayView = LocalView.current
    val displayRotationProvider = remember(displayView) {
        { displayView.display?.rotation ?: Surface.ROTATION_0 }
    }
    var rollSnapshot by remember { mutableStateOf<HorizonRollSnapshot?>(null) }
    val horizonSensorAvailable = remember(context, displayRotationProvider, showHorizon) {
        if (!showHorizon) null
        else HorizonRollSensor(context, displayRotationProvider) { rollSnapshot = it }.also { it.start() }
    }
    DisposableEffect(horizonSensorAvailable) {
        onDispose { horizonSensorAvailable?.close() }
    }
    val analysisFresh = rememberScopeAnalysisFresh(state, options)
    BoxWithConstraints(modifier) {
        ProfessionalScopeImage(state, options, analysisFresh, displayRotationProvider() * 90, sourceWidth, sourceHeight, squeezeFactor, Modifier.matchParentSize())
        val levelColor = VerifiedCyan; val tiltColor = Amber
        Canvas(Modifier.matchParentSize()) {
            val gpuScale = if (state.gpuViewfinder || state.selectedMode == CaptureMode.LOG)
                monitoringPreviewScale(sourceWidth, sourceHeight, size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1),
                    state.descriptor?.sensorOrientation ?: 0, displayRotationProvider() * 90,
                    state.descriptor?.lensFacing == CameraCharacteristics.LENS_FACING_FRONT, squeezeFactor)
                else 1f to 1f
            fun cellRect(index: Int): androidx.compose.ui.geometry.Rect {
                val domain = state.monitoringScopes?.domain ?: MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR
                fun point(x: Float, y: Float): Offset {
                    val p = monitoringDisplayPoint(x, y, domain, state.descriptor?.sensorOrientation ?: 0,
                        displayRotationProvider() * 90, state.descriptor?.lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
                    return Offset((.5f + (p.first - .5f) * gpuScale.first) * size.width, (.5f + (p.second - .5f) * gpuScale.second) * size.height)
                }
                val a = point((index % 16) / 16f, (index / 16) / 9f)
                val b = point((index % 16 + 1) / 16f, (index / 16 + 1) / 9f)
                return androidx.compose.ui.geometry.Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
            }
            if (showZebra && analysisFresh) state.zebraCells.forEachIndexed { index, active ->
                if (active) cellRect(index).let { drawRect(options.zebraColor.composeColor().copy(alpha = options.opacityPercent / 100f), it.topLeft, it.size) }
            }
            if (showPeaking && analysisFresh) state.focusCells.forEachIndexed { index, active ->
                if (active) cellRect(index).let { drawRect(options.peakingColor.composeColor().copy(alpha = options.opacityPercent / 100f), it.topLeft, it.size, style = Stroke(width = 2.dp.toPx())) }
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
                        kotlin.math.abs(degrees) <= HorizonRollColors.LEVEL_BAND -> levelColor
                        kotlin.math.abs(degrees) <= HorizonRollColors.WARNING_BAND -> tiltColor
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
        if (showHorizon && horizonSensorAvailable?.available == false) {
            // No gravity or accelerometer sensor: say so where the level line would be drawn.
            Text(
                stringResource(R.string.horizon_level_unavailable),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center).background(Panel, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp).testTag("horizon-level-unavailable"),
            )
        }
        if (drawHistogram && showHistogram && analysisFresh && state.histogram.isNotEmpty()) {
            HistogramGraph(state, options, histogramMode, Modifier.padding(start = 12.dp, top = 12.dp).width(maxWidth * .28f).height(maxHeight * .09f))
        }
        if (drawScopesPanel) ProfessionalScopesPanel(state, options, analysisFresh, Modifier.align(Alignment.CenterEnd).padding(end = scopesPanelEndPadding))
        // The overlay spans the whole screen: clear the top bar and the AE/AF lock toggles
        // (top end, from 62 dp) and keep right of the zoom column (top start).
        AnalysisSuspensionNotice(state, Modifier.align(Alignment.TopCenter).padding(top = 116.dp, start = 88.dp, end = 12.dp))
    }
}

/** The zoom rocker: 28 dp wide, 8 dp from the pane end, plus a gap before the panel. */
private val SCOPES_BESIDE_END_CLEARANCE = 44.dp
/** Gap between the frame edge and a panel laid out beside it. */
private val SCOPES_BESIDE_START_GAP = 12.dp
/** Narrowest strip worth moving the panel into; below it the panel stays over the frame. */
private val SCOPES_BESIDE_MIN_WIDTH = 140.dp

/** Whether the latest scope analysis is recent enough to draw; refreshed four times a second. */
@Composable
private fun rememberScopeAnalysisFresh(state: CameraUiState, options: MonitoringOptions): Boolean {
    var analysisClockMs by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            analysisClockMs = android.os.SystemClock.elapsedRealtime()
        }
    }
    return state.scopeAnalysisLive(monitoringSampleFresh(state.analysisUpdatedAtMs, maxOf(analysisClockMs, SystemClock.elapsedRealtime()), options)) &&
        (state.monitoringScopes == null || state.monitoringScopes.options == options)
}

@Composable
private fun HistogramGraph(state: CameraUiState, options: MonitoringOptions, histogramMode: HistogramMode, modifier: Modifier = Modifier) {
    Canvas(modifier.testTag("histogram-graph")) {
        drawRect(Color.Black.copy(alpha = .55f))
        if (histogramMode == HistogramMode.LUMA) {
            val peak = state.histogram.maxOrNull()?.coerceAtLeast(.001f) ?: 1f
            state.histogram.forEachIndexed { index, value ->
                val width = size.width / state.histogram.size
                val height = size.height * value / peak
                drawRect(options.lumaColor.composeColor().copy(alpha = options.opacityPercent / 100f), androidx.compose.ui.geometry.Offset(index * width, size.height - height), androidx.compose.ui.geometry.Size((width - 1f).coerceAtLeast(.5f), height))
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
                drawPath(path, color.copy(alpha = options.opacityPercent / 100f), style = Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}

@Composable
private fun PreviewSurfaceView(
    cameraId: String,
    bufferWidth: Int,
    bufferHeight: Int,
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
        // The caller sizes the view: aspectRatio picks the largest rectangle that fits both width
        // and height, and a filled viewfinder uses an oversized box with the same ratio. A
        // preceding fillMaxWidth would lock the landscape width and squeeze the EGL output.
        modifier = modifier.testTag("preview-surface"),
    )
}

@Composable
internal fun AdaptiveCaptureChrome(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    landscape: Boolean,
    previewAspectRatio: Float? = null,
    previewGesturesEnabled: Boolean = true,
    // Non-null when the viewfinder spans the whole window under translucent chrome.
    overlayViewfinderScale: ViewfinderScale? = null,
    // Reports the visible deck height of the compact portrait layout, which the viewfinder sits above.
    onStackedDeckHeight: (Int) -> Unit = {},
    // 0 at rest, 1 once the stacked viewfinder has grown into the space of the hidden deck.
    viewfinderExpansion: Float = 0f,
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
    val operatorInput = LocalOperatorActions.current
    DisposableEffect(manualControl, showModeGrid, showMonitoring) {
        operatorInput?.setEditing?.invoke(manualControl != null || showModeGrid || showMonitoring)
        onDispose { operatorInput?.setEditing?.invoke(false) }
    }
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

    // The real-time rate Video had before slow motion raised it, restored when Video is chosen again.
    var normalVideoFps by rememberSaveable { mutableIntStateOf(30) }
    val modeSelection = ModeSelection(displayedCaptureMode(state.selectedMode, settings.videoOffSpeed)) { mode ->
        // Slow motion is VIDEO recording off-speed; Video is the same path at normal speed.
        val slow = mode == CaptureMode.SLOW_MOTION
        val leavingSlowMotion = mode == CaptureMode.VIDEO && settings.videoOffSpeed
        if (slow && !settings.videoOffSpeed && state.targetFps < SLOW_MOTION_MIN_FPS) normalVideoFps = state.targetFps
        val profile = if (slow) state.descriptor?.let { descriptor ->
            slowMotionProfile(descriptor.videoProfiles.map { it.toSpec() }, state.targetVideoWidth, state.targetVideoHeight)
        } else null
        val offSpeed = when (mode) {
            CaptureMode.SLOW_MOTION -> true
            CaptureMode.VIDEO -> false
            else -> settings.videoOffSpeed
        }
        // Size and rate are settled before the mode changes, so a switch from another mode opens
        // the camera once, directly in the high-speed session, instead of reopening it per step.
        val updated = settings.copy(videoOffSpeed = offSpeed).let { base ->
            profile?.let { base.copy(videoWidth = it.width, videoHeight = it.height, videoFps = it.fps) }
                ?: if (leavingSlowMotion) base.copy(videoFps = normalVideoFps) else base
        }
        if (updated != settings) {
            onSettingsChanged(updated)
            binder?.applySettings(updated)
        }
        val pipelineMode = if (slow) CaptureMode.VIDEO else mode
        if (pipelineMode != state.selectedMode) binder?.selectMode(pipelineMode)
        else if (profile != null) binder?.selectTargetFps(profile.fps)
        else if (leavingSlowMotion) binder?.selectTargetFps(normalVideoFps)
    }

    CompositionLocalProvider(LocalModeSelection provides modeSelection) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val density = LocalDensity.current
        val windowProfile = captureWindowProfile(maxWidth.value, maxHeight.value)
        val horizontalDeck = windowProfile != CaptureWindowProfile.COMPACT_PORTRAIT
        val compactPortrait = windowProfile == CaptureWindowProfile.COMPACT_PORTRAIT
        // Short landscape windows put the controls in side rails and keep the viewfinder at full
        // height between them. A hinge split owns the preview elsewhere, so it keeps the deck.
        val sideRails = previewGesturesEnabled &&
            captureChromeLayout(maxWidth.value, maxHeight.value) == CaptureChromeLayout.SIDE_RAILS
        val startRail = SIDE_RAIL_START_WIDTH_DP.dp
        val endRail = SIDE_RAIL_END_WIDTH_DP.dp
        val controlDeckHeight = if (sideRails) 0.dp else with(density) { controlDeckHeightPx.toDp() }
        // Compact portrait (phones, a foldable's cover screen) spends as little height as possible
        // on chrome: a slim status bar, the F-keys over the viewfinder edge, a one-line deck.
        val slimChrome = compactPortrait && !sideRails
        val topBarHeight = if (slimChrome) SLIM_TOP_BAR_HEIGHT_DP.dp else STACKED_TOP_BAR_HEIGHT_DP.dp
        // The deck slides away while recording. This height stays the deck's shown height, which
        // is where the viewfinder rests; it then grows into the freed space (viewfinderExpansion).
        // It is frozen during a take: the recording deck is taller (it adds Pause), and following it
        // would resize the viewfinder surface, which reattaches the preview mid-take.
        var stableDeckHeightPx by remember { mutableIntStateOf(0) }
        LaunchedEffect(controlDeckHeightPx, recording) {
            if (controlDeckHeightPx > 0 && !recording) stableDeckHeightPx = controlDeckHeightPx
        }
        LaunchedEffect(sideRails, stableDeckHeightPx, overlayViewfinderScale) {
            onStackedDeckHeight(if (sideRails || overlayViewfinderScale != null) 0 else stableDeckHeightPx)
        }
        // SurfaceView owns a native surface, so keep an explicit Compose hit target over it.
        // This is the first child: controls composed later remain the winning hit targets.
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val ratio = previewAspectRatio
        val safeInsets = WindowInsets.safeDrawing
        val chromeLayoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
        val previewViewport = if (overlayViewfinderScale != null && previewGesturesEnabled) {
            // The viewfinder spans the window around this inset box; map it into chrome coordinates.
            val insetLeft = safeInsets.getLeft(density, chromeLayoutDirection).toFloat()
            val insetTop = safeInsets.getTop(density).toFloat()
            val window = overlayPreviewViewport(
                width + insetLeft + safeInsets.getRight(density, chromeLayoutDirection),
                height + insetTop + safeInsets.getBottom(density),
                ratio,
                overlayViewfinderScale,
            )
            window.copy(left = window.left - insetLeft, top = window.top - insetTop)
        } else if (sideRails) {
            sideRailPreviewViewport(
                width, height,
                with(density) { startRail.toPx() }, with(density) { endRail.toPx() },
                ratio,
                rightToLeft = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl,
            )
        } else if (previewGesturesEnabled) {
            recordingPreviewViewport(width, height, with(density) { topBarHeight.toPx() }, stableDeckHeightPx.toFloat(), ratio, viewfinderExpansion)
        } else fittedPreviewViewport(width, height, ratio)
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
                .pointerInput(previewGesturesEnabled, state.captureControlsLocked, recording, ratio, state.zoomSupported, state.zoomRatio) {
                    // Pinch-to-zoom. Consumed by this detector so it never reaches tap-focus.
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (!previewGesturesEnabled || state.captureControlsLocked || !state.zoomSupported) return@detectTransformGestures
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
                                direction = candidate - state.zoomRatio,
                            )
                        } else {
                            state.zoomMinRatio..state.zoomMaxRatio
                        }
                        val coerced = ZoomMath.coerce(candidate, range)
                        binder?.setZoomRatio(coerced)
                        pinchStartRatio = coerced
                    }
                }
                .pointerInput(previewGesturesEnabled, state.captureControlsLocked, recording, ratio, settings.tapExposureMeteringEnabled, state.phase) {
                    detectTapGestures { position ->
                        if (recording) manualReveal = true
                        if (!previewGesturesEnabled || state.captureControlsLocked || ratio == null || position.x !in previewLeft..(previewLeft + previewWidth) ||
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
                // A filled viewfinder overflows the screen; keep the reticle on its visible part.
                val minLeft = maxOf(previewLeft, 0f)
                val minTop = maxOf(previewTop, 0f)
                val left = (point.x - side / 2f).coerceIn(minLeft, maxOf(minLeft, minOf(previewLeft + previewWidth, size.width) - side))
                val top = (point.y - side / 2f).coerceIn(minTop, maxOf(minTop, minOf(previewTop + previewHeight, size.height) - side))
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
            ZoomRocker(
                onStep = { offset, deltaSeconds ->
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
                                direction = speed,
                            )
                        } else {
                            state.zoomMinRatio..state.zoomMaxRatio
                        }
                        val factor = ZoomMath.rockerFactor(speed, deltaSeconds)
                        val candidate = ZoomMath.multiply(state.zoomRatio, factor, range)
                        binder?.setZoomRatio(candidate)
                    }
                },
                onRelease = {},
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = if (sideRails) endRail + 8.dp else 8.dp)
                    .width(28.dp)
                    .height(120.dp),
            )
        }

        if (chromeVisible) {
            if (slimChrome) Column(
                Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = topBarHeight + 6.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LockToggles(state = state, binder = binder, afLockBehavior = settings.afLockBehavior)
                OperatorButtonColumn(state, settings)
            } else LockToggles(
                state = state,
                binder = binder,
                afLockBehavior = settings.afLockBehavior,
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(end = if (sideRails) endRail + 8.dp else 8.dp, top = if (sideRails) 8.dp else topBarHeight + 6.dp),
            )
            // In side-rail layout the top-bar actions live in the start rail instead.
            if (!sideRails) CaptureTopBar(
                state = state,
                binder = binder,
                onOpenMedia = onOpenMedia,
                onOpenSettings = onOpenSettings,
                settings = settings,
                onSettingsChanged = onSettingsChanged,
                modifier = Modifier.align(Alignment.TopCenter),
                showThumbnail = !slimChrome,
                height = topBarHeight,
            )
        }

        // Every viewfinder instrument lives in one measured stack, so the LOG badge, zoom,
        // microphone meter and histogram can never land on top of each other. It starts below
        // the top bar (or the recording HUD) and stops above the measured control deck.
        val recordingHudTop = when {
            !chromeVisible -> 0.dp
            sideRails -> 0.dp
            state.selectedMode == CaptureMode.LOG -> topBarHeight + 26.dp
            else -> topBarHeight
        }
        // With side rails the instruments sit on the viewfinder between the rails, and there is no
        // top bar above them; the end inset also clears the zoom rocker at the viewfinder edge.
        val instrumentStart = if (sideRails) startRail + 12.dp else 12.dp
        val instrumentEnd = if (sideRails) endRail + 44.dp else 64.dp
        val instrumentTop = if (sideRails) 12.dp else topBarHeight + 6.dp
        // The recording HUD grows with its meter and monitor toggles; measure it instead of guessing.
        var recordingHudHeightPx by remember { mutableIntStateOf(0) }
        val recordingHudHeight = with(LocalDensity.current) { recordingHudHeightPx.toDp() }
        InstrumentStack(
            state = state,
            binder = binder,
            settings = settings,
            chromeVisible = chromeVisible,
            recording = recording,
            histogram = histogram,
            histogramMode = histogramMode,
            horizontal = landscape,
            // A hinge split gives the chrome its own pane with no picture behind it: let the
            // histogram use the band between the instruments and the deck instead of a thumbnail.
            dedicatedPane = !previewGesturesEnabled,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = instrumentStart,
                    end = instrumentEnd,
                    top = if (recording) recordingHudTop + maxOf(recordingHudHeight, 52.dp) + 8.dp else instrumentTop,
                    bottom = if (chromeVisible) controlDeckHeight + 8.dp else 8.dp,
                ),
        )

        if (sideRails) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight(),
            ) {
                CaptureStartRail(
                    state = state,
                    binder = binder,
                    settings = settings,
                    onSettingsChanged = onSettingsChanged,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.width(startRail),
                )
            }
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            ) {
                CaptureEndRail(
                    state = state,
                    binder = binder,
                    selectorStyle = settings.modeSelectorStyle,
                    settings = settings,
                    onControl = { manualControl = it; showModeGrid = false; showMonitoring = false },
                    onShowModes = { showModeGrid = !showModeGrid; manualControl = null; showMonitoring = false },
                    onShowMonitoring = { showMonitoring = !showMonitoring; manualControl = null; showModeGrid = false },
                    onOpenMedia = onOpenMedia,
                    modifier = Modifier.width(endRail),
                )
            }
        } else AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { controlDeckHeightPx = it.height },
        ) {
            Column {
            if (!slimChrome) OperatorButtonRow(state, settings)
            PresetQuickAccess(state, settings, binder?.let { owner -> { preset -> owner.applyPreset(preset) } })
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
                    onOpenMedia = onOpenMedia,
                    onControl = { manualControl = it; showModeGrid = false; showMonitoring = false },
                    onShowModes = { showModeGrid = !showModeGrid; manualControl = null; showMonitoring = false },
                    onShowMonitoring = { showMonitoring = !showMonitoring; manualControl = null; showModeGrid = false },
                )
            }
            }
        }

        if (recording) {
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
                modifier = Modifier.align(Alignment.TopCenter).padding(top = recordingHudTop)
                    .padding(start = if (sideRails) startRail else 0.dp, end = if (sideRails) endRail else 0.dp)
                    .onSizeChanged { recordingHudHeightPx = it.height },
            )
        }

        // The slim deck keeps a fixed height, so the viewfinder above it never reframes: its
        // notices float just above it instead of adding a line to it.
        if (slimChrome && chromeVisible && !recording) CaptureStatus(
            state,
            Modifier.align(Alignment.BottomCenter).padding(bottom = controlDeckHeight + 8.dp, start = 16.dp, end = 16.dp),
            chip = true,
        )

        if (state.captureControlsLocked) {
            OutlinedButton({ binder?.performOperatorAction(OperatorAction.CONTROL_LOCK) },
                Modifier.align(Alignment.BottomStart).padding(start = if (sideRails) startRail else 0.dp).padding(8.dp)
                    .heightIn(min = 48.dp).testTag("operator-unlock")) {
                Text(stringResource(R.string.operator_unlock), color = Color.White)
            }
        }

        if (chromeVisible) manualControl?.let { control ->
            ContextualPanel(horizontalDeck || sideRails, controlDeckHeight, maxHeight, endInset = if (sideRails) endRail else 0.dp, onDismiss = { manualControl = null }) {
                ManualControlDial(control, state, binder, settings, onSettingsChanged) { manualControl = null }
            }
        }
        if (chromeVisible && showModeGrid) {
            ContextualPanel(horizontalDeck || sideRails, controlDeckHeight, maxHeight, endInset = if (sideRails) endRail else 0.dp, onDismiss = { showModeGrid = false }) {
                ModeButtonGrid(state, binder) { showModeGrid = false }
            }
        }
        if (chromeVisible && showMonitoring) {
            ContextualPanel(horizontalDeck || sideRails, controlDeckHeight, maxHeight, endInset = if (sideRails) endRail else 0.dp, onDismiss = { showMonitoring = false }) {
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
}

@Composable
private fun CaptureTopBar(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    onOpenMedia: () -> Unit,
    onOpenSettings: () -> Unit,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    modifier: Modifier = Modifier,
    // The side-rail layout stacks the same actions in the start rail instead of across the top.
    vertical: Boolean = false,
    // Compact portrait and the side rails keep the gallery thumbnail beside the shutter, under the thumb.
    showThumbnail: Boolean = true,
    height: androidx.compose.ui.unit.Dp = STACKED_TOP_BAR_HEIGHT_DP.dp,
) {
    val foldCoordinator = LocalFoldDisplayCoordinator.current
    val foldFallback = remember { MutableStateFlow(FoldDisplayState()) }
    val fold by (foldCoordinator?.states ?: foldFallback).collectAsStateWithLifecycle()
    var showDisplays by remember { mutableStateOf(false) }
    if (showDisplays) AlertDialog(
        onDismissRequest = { showDisplays = false },
        confirmButton = { TextButton(onClick = { showDisplays = false }) { Text(stringResource(android.R.string.ok)) } },
        text = { FoldDisplaySettings(state, settings, onSettingsChanged) },
        containerColor = Panel,
    )
    var showLight by remember { mutableStateOf(false) }
    if (showLight) AlertDialog(
        onDismissRequest = { showLight = false },
        confirmButton = { TextButton(onClick = { showLight = false }) { Text(stringResource(android.R.string.ok)) } },
        text = { TorchSettings(state, settings, onSettingsChanged) },
        containerColor = Panel,
    )
    val switchDescription = stringResource(R.string.camera_switch)
    val actions: @Composable () -> Unit = {
        if (state.cameras.size > 1) {
            TopAction(CineIcon.SWITCH_CAMERA, switchDescription) {
                val index = state.cameras.indexOfFirst { it.cameraId == state.selectedCameraId }
                binder?.selectCamera(state.cameras[(index + 1).mod(state.cameras.size)].cameraId)
            }
        }
        if (fold.operation == DisplayOperation.TRANSFER && fold.phase == DisplaySessionPhase.ACTIVE) {
            TopAction(CineIcon.RETURN, stringResource(R.string.fold_return)) { foldCoordinator?.closeSession() }
        } else {
            TopAction(CineIcon.DISPLAYS, stringResource(R.string.fold_settings_title)) { showDisplays = true }
        }
        TopAction(CineIcon.TORCH, stringResource(R.string.flash_torch)) { showLight = true }
        TopAction(CineIcon.SETTINGS, stringResource(R.string.settings_tab), onOpenSettings)
    }
    if (vertical) {
        Column(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showThumbnail) MediaThumbnailAction(state.lastSavedUri, onOpenMedia)
            CaptureStatusLine(state, Modifier.fillMaxWidth().padding(horizontal = 4.dp), maxLines = 3, textAlign = TextAlign.Center)
            ThermalHudChip()
            OutOfFrameHudChip(state)
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { actions() }
        }
    } else Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Panel)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (showThumbnail) 6.dp else 2.dp),
    ) {
        if (showThumbnail) MediaThumbnailAction(state.lastSavedUri, onOpenMedia)
        else Spacer(Modifier.width(4.dp))
        CaptureStatusLine(state, Modifier.weight(1f))
        ThermalHudChip()
        OutOfFrameHudChip(state)
        actions()
    }
}

/** A round secondary action beside the shutter, sized to balance the gallery thumbnail. */
@Composable
private fun TransportSideAction(icon: CineIcon, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .border(1.dp, Color(0xFF41494C), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CineGlyph(icon, Color.White, Modifier.size(24.dp))
    }
}

@Composable
private fun TopAction(icon: CineIcon, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CineGlyph(icon, Color.White, Modifier.size(24.dp))
    }
}

@Composable
private fun MediaThumbnailAction(lastSavedUri: String?, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val context = LocalContext.current
    val description = stringResource(R.string.media_tab)
    var thumbnail by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Reload after every published capture, not only once: the newest take belongs here.
    LaunchedEffect(lastSavedUri) {
        thumbnail = withContext(Dispatchers.IO) {
            runCatching { LocalMediaRepository(context.applicationContext).recent(1).firstOrNull()?.thumbnail }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(size)
            .testTag("media-action")
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(if (size > 48.dp) 12.dp else 8.dp))
            .background(Color(0xFF303638))
            .border(1.dp, Color(0xFF41494C), RoundedCornerShape(if (size > 48.dp) 12.dp else 8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        thumbnail?.let {
            Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        } ?: CineGlyph(CineIcon.MEDIA, Color.White, Modifier.size(24.dp))
    }
}

@Composable
private fun LogSourceBadge(state: CameraUiState, settings: CameraSettings, modifier: Modifier = Modifier) {
    if (state.selectedMode != CaptureMode.LOG) return
    val profile = state.activeLogProfile
    val sourcePath = profile?.sourcePath
    val qualification = ocLogQualificationLabel(profile)?.let { " · $it" }.orEmpty()
    val text = when {
        sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP && settings.logViewAssistEnabled ->
            "OCLOG2 HFR · ISP SDR · VIEW ASSIST$qualification"
        sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP ->
            "OCLOG2 HFR · ISP-DERIVED SDR · MAIN10$qualification"
        settings.logViewAssistEnabled -> "OCLOG2 · HLG10 · VIEW ASSIST REC.709$qualification"
        else -> "OCLOG2 · HLG-DERIVED 10-BIT · FLAT$qualification"
    }
    Text(
        text,
        color = if (profile?.isVerified == true) VerifiedCyan else Color.White,
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

/** VERIFIED only for a profile tied to exact evidence; an unqualified profile makes no claim either way. */
internal fun ocLogQualificationLabel(profile: Camera2LogProfile?): String? =
    if (profile?.isVerified == true) "VERIFIED" else null

@Composable
private fun InstrumentStack(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    chromeVisible: Boolean,
    recording: Boolean,
    histogram: Boolean,
    histogramMode: HistogramMode,
    horizontal: Boolean,
    dedicatedPane: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val analysisFresh = rememberScopeAnalysisFresh(state, settings.monitoring)
    // While recording, the recording HUD carries its own meter.
    // Off-speed (slow motion) takes are silent by design, so there is no microphone to meter.
    val showAudio = chromeVisible && !recording && settings.audioEnabled && settings.audioMeter.visible &&
        state.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.LOG) &&
        !(state.selectedMode == CaptureMode.VIDEO && settings.videoOffSpeed)
    val showLogSource = chromeVisible && state.selectedMode == CaptureMode.LOG
    val showZoom = chromeVisible && state.zoomSupported
    val showHistogram = histogram && analysisFresh && state.histogram.isNotEmpty()
    if (!showAudio && !showLogSource && !showZoom && !showHistogram) return
    BoxWithConstraints(modifier) {
        val histogramWidth = (maxWidth * .34f).coerceIn(112.dp, 240.dp)
        val stretchHistogram = dedicatedPane && horizontal && showHistogram
        FittingStack(horizontal, spacing = 8.dp, fillLast = stretchHistogram) {
            if (showLogSource) LogSourceBadge(state, settings, Modifier.widthIn(max = 260.dp))
            if (showZoom) ZoomReadout(state, binder)
            if (showAudio) AudioMeterHud(state, binder, meterSettings = settings.audioMeter)
            if (showHistogram) HistogramGraph(state, settings.monitoring, histogramMode,
                if (stretchHistogram) Modifier.fillMaxSize() else Modifier.width(histogramWidth).height(histogramWidth * .32f))
        }
    }
}

/**
 * Lays instruments out in a row or a column and leaves out any that would not fit whole in the
 * space left above the deck. A half-drawn meter reads as a real level, so a clipped instrument is
 * worse than an absent one. With [fillLast] the last instrument takes the rest of the main axis
 * and the full cross axis (capped at [maxFillCross]) instead of its own size.
 */
@Composable
private fun FittingStack(
    horizontal: Boolean,
    spacing: androidx.compose.ui.unit.Dp,
    fillLast: Boolean = false,
    maxFillCross: androidx.compose.ui.unit.Dp = 160.dp,
    content: @Composable () -> Unit,
) {
    Layout(content) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = if (fillLast && measurables.isNotEmpty()) {
            val leading = measurables.dropLast(1).map { it.measure(loose) }
            val used = leading.sumOf { (if (horizontal) it.width else it.height) + gap }
            val mainLimit = if (horizontal) constraints.maxWidth else constraints.maxHeight
            val crossLimit = if (horizontal) constraints.maxHeight else constraints.maxWidth
            val main = (mainLimit - used).coerceAtLeast(0)
            val cross = minOf(crossLimit, maxFillCross.roundToPx())
            // Too little room left to be worth stretching into: keep the instrument's own size.
            val last = if (main < 112.dp.roundToPx()) measurables.last().measure(loose) else measurables.last().measure(
                if (horizontal) Constraints.fixed(main, cross) else Constraints.fixed(cross, main),
            )
            leading + last
        } else measurables.map { it.measure(loose) }
        val positions = ArrayList<Pair<androidx.compose.ui.layout.Placeable, IntOffset>>()
        var cursor = 0
        placeables.forEach { item ->
            val main = if (horizontal) item.width else item.height
            val cross = if (horizontal) item.height else item.width
            val mainLimit = if (horizontal) constraints.maxWidth else constraints.maxHeight
            val crossLimit = if (horizontal) constraints.maxHeight else constraints.maxWidth
            if (cursor + main <= mainLimit && cross <= crossLimit) {
                positions += item to if (horizontal) IntOffset(cursor, 0) else IntOffset(0, cursor)
                cursor += main + gap
            }
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            positions.forEach { (item, at) -> item.place(at) }
        }
    }
}

@Composable
private fun ZoomReadout(state: CameraUiState, binder: CaptureService.LocalBinder?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A single lens has nothing to switch to, so its lone anchor would only repeat the ratio.
        if (state.opticalAnchors.size > 1) ZoomAnchorBar(
            anchors = state.opticalAnchors,
            activeRatio = state.zoomEffectiveRatio ?: state.zoomRatio,
            onSelect = { ratio -> binder?.selectZoomAnchor(ratio) },
        )
        val ratioValue = state.zoomEffectiveRatio ?: state.zoomRatio
        val isDigital = state.opticalAnchors.none { (ratioValue - it.ratio).let { d -> d >= -0.05f && d <= 0.05f } }
        // On an anchor the highlighted anchor already states the ratio; the readout is for in-between.
        if (state.opticalAnchors.size <= 1 || isDigital) Box(
            Modifier
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
    onOpenMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(Panel).padding(top = 8.dp, bottom = 8.dp)) {
        QuickControls(state, onControl, landscape = false)
        Spacer(Modifier.height(6.dp))
        if (selectorStyle == ModeSelectorStyle.DIAL) ModeDial(state, binder)
        else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SelectedModeButton(state, onShowModes) }
        Spacer(Modifier.height(4.dp))
        PortraitCaptureTransport(
            state, binder, settings, onOpenMedia = onOpenMedia, onShowMonitoring = onShowMonitoring,
            // Stacked translucent panels would compound into a darker band under the shutter.
            panelBackground = LocalChromeOpacity.current == null,
        )
    }
}

/** Pause uses its own row so it never overlaps Stop on narrow/large-font displays. */
@Composable
internal fun PortraitCaptureTransport(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    // The gallery sits under the thumb, opposite monitoring, when the top bar does not carry it.
    onOpenMedia: (() -> Unit)? = null,
    panelBackground: Boolean = true,
    onShowMonitoring: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().then(if (panelBackground) Modifier.background(Panel) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            onOpenMedia?.let { open ->
                Box(Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) {
                    MediaThumbnailAction(state.lastSavedUri, open, size = 52.dp)
                }
            }
            CaptureButton(state, binder, settings, 72.dp)
            Box(Modifier.align(Alignment.CenterEnd).padding(end = if (onOpenMedia != null) 24.dp else 12.dp)) {
                if (onOpenMedia != null) TransportSideAction(CineIcon.MONITORING, stringResource(R.string.monitoring_tools), onShowMonitoring)
                else TopAction(CineIcon.MONITORING, stringResource(R.string.monitoring_tools), onShowMonitoring)
            }
        }
        RecordingPauseButton(state) { requestRecordingPause(state, binder, it) }
        BurstCaptureProgress(state) { binder?.cancelBurstCapture() }
        BracketCaptureProgress(state) { binder?.cancelBracketCapture() }
        AccumulationCaptureProgress(state, { binder?.finishAccumulationCapture() }, { binder?.cancelAccumulationCapture() })
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
                if (selectorStyle == ModeSelectorStyle.DIAL) ModeDial(state, binder)
                else SelectedModeButton(state, onShowModes)
            }
            TopAction(CineIcon.MONITORING, stringResource(R.string.monitoring_tools), onShowMonitoring)
            RecordingPauseButton(state) { requestRecordingPause(state, binder, it) }
            CaptureButton(state, binder, settings, 62.dp, labelBeside = true)
        }
        Spacer(Modifier.height(6.dp))
        BurstCaptureProgress(state) { binder?.cancelBurstCapture() }
        BracketCaptureProgress(state) { binder?.cancelBracketCapture() }
        AccumulationCaptureProgress(state, { binder?.finishAccumulationCapture() }, { binder?.cancelAccumulationCapture() })
        StatusInfoBar(state, settings)
    }
}

/**
 * Start rail of the side-rail layout: the top-bar actions, then the F-keys as a two-column grid
 * and the preset slots. It still scrolls rather than clipping if preset slots push it past the
 * window height.
 */
@Composable
private fun CaptureStartRail(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .background(Panel)
            .verticalScroll(rememberScrollState())
            .testTag("capture-start-rail"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        CaptureTopBar(
            state = state,
            binder = binder,
            onOpenMedia = {},
            onOpenSettings = onOpenSettings,
            settings = settings,
            onSettingsChanged = onSettingsChanged,
            vertical = true,
            showThumbnail = false,
        )
        OperatorButtonGrid(state, settings, Modifier.fillMaxWidth())
        PresetQuickAccess(state, settings, binder?.let { owner -> { preset -> owner.applyPreset(preset) } })
    }
}

/**
 * End rail of the side-rail layout, in two columns: the exposure controls as one vertical strip
 * that scrolls on its own, and beside it the mode selector over the shutter, which is held at the
 * bottom so it never scrolls away, with monitoring above it as the secondary action.
 */
@Composable
private fun CaptureEndRail(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    selectorStyle: ModeSelectorStyle,
    settings: CameraSettings,
    onControl: (ControlDial) -> Unit,
    onShowModes: () -> Unit,
    onShowMonitoring: () -> Unit,
    onOpenMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .background(Panel)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .testTag("capture-end-rail"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuickControls(state, onControl, landscape = true, modifier = Modifier.width(SIDE_RAIL_EXPOSURE_WIDTH_DP.dp).fillMaxHeight())
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (selectorStyle == ModeSelectorStyle.DIAL) ModeDial(state, binder, compact = true)
                    else SelectedModeButton(state, onShowModes)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MediaThumbnailAction(state.lastSavedUri, onOpenMedia)
                    TopAction(CineIcon.MONITORING, stringResource(R.string.monitoring_tools), onShowMonitoring)
                }
                Spacer(Modifier.height(6.dp))
                CaptureButton(state, binder, settings, 64.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
        StatusInfoBar(state, settings)
        RecordingPauseButton(state) { requestRecordingPause(state, binder, it) }
        BurstCaptureProgress(state) { binder?.cancelBurstCapture() }
        BracketCaptureProgress(state) { binder?.cancelBracketCapture() }
        AccumulationCaptureProgress(state, { binder?.finishAccumulationCapture() }, { binder?.cancelAccumulationCapture() })
        CaptureStatus(state)
    }
}

@Composable
private fun QuickControls(state: CameraUiState, onControl: (ControlDial) -> Unit, landscape: Boolean, modifier: Modifier = Modifier) {
    val controls = buildList {
        if (state.selectedMode in CameraUiState.resolutionProfileModes) add(ControlDial.RESOLUTION)
        if (state.selectedMode == CaptureMode.TIME_LAPSE) add(ControlDial.INT)
        // A recording rate only exists where frames are recorded as motion; stills and the
        // fixed-output time-lapse have none to choose.
        if (state.selectedMode in CameraUiState.frameRateModes) add(ControlDial.FPS)
        add(ControlDial.SHUTTER)
        add(ControlDial.ISO)
        add(ControlDial.WB)
        if (state.aeCompensationSupported) add(ControlDial.EV)
        add(ControlDial.FOCUS)
    }
    if (landscape) ExposureColumn(controls, state, onControl, modifier) else ExposureStrip(controls, state, onControl)
}

/** The exposure strip turned upright for the side rail: one column that scrolls when it does not fit. */
@Composable
private fun ExposureColumn(controls: List<ControlDial>, state: CameraUiState, onControl: (ControlDial) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color(0xFF12171A).chromePanel())
                .border(1.dp, Color(0xFF263036), shape)
                .verticalScroll(rememberScrollState())
                .testTag("exposure-column"),
        ) {
            controls.forEachIndexed { index, control ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 10.dp).background(Color(0xFF2E383E)))
                QuickControlButton(control, state, onControl, Modifier.fillMaxWidth(), inStrip = true, stripValueSize = 14.sp)
            }
        }
    }
}

/**
 * Exposure as one strip of value-over-label cells split by hairlines, instead of rows of tiles:
 * one line of deck for every control. Cells never shrink below a readable width; when a mode has
 * more controls than fit, the strip scrolls sideways rather than truncating values.
 */
@Composable
private fun ExposureStrip(controls: List<ControlDial>, state: CameraUiState, onControl: (ControlDial) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val minimumCell = 52.dp
        val cell = maxWidth / controls.size.coerceAtLeast(1)
        val fits = cell >= minimumCell
        // Seven video controls on a 400 dp phone leave ~56 dp a cell: a size smaller keeps 1/8000 whole.
        val valueSize = if (cell < 62.dp) 14.sp else 15.sp
        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier
                .then(if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()))
                .height(IntrinsicSize.Min)
                .clip(shape)
                .background(Color(0xFF12171A).chromePanel())
                .border(1.dp, Color(0xFF263036), shape)
                .testTag("exposure-strip"),
        ) {
            controls.forEachIndexed { index, control ->
                if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(Color(0xFF2E383E)))
                QuickControlButton(control, state, onControl, if (fits) Modifier.weight(1f) else Modifier.width(minimumCell), inStrip = true,
                    stripValueSize = valueSize)
            }
        }
    }
}

/** 3840×2160 reads as 4K and 1920×1080 as 1080p: the short edge names the format. */
internal fun shortResolutionLabel(width: Int, height: Int): String {
    val short = minOf(width, height)
    val long = maxOf(width, height)
    return when {
        short <= 0 -> "—"
        long >= 3840 && short >= 2160 -> "4K"
        else -> "${short}p"
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
    val aeAvailable = state.aeLockSupported && !manualExposure && !state.captureControlsLocked
    val afAvailable = state.afLockSupported && !manualFocus && !state.captureControlsLocked
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
        enabled -> Color(0xFF1B2023).chromePanel()
        else -> Color(0xFF161A1C).chromePanel()
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
    inStrip: Boolean = false,
    stripValueSize: androidx.compose.ui.unit.TextUnit = 15.sp,
) {
    val constrained = state.activeVideoProfile?.constrainedHighSpeed == true || state.activeLogProfile?.constrainedHighSpeed == true
    val exposureCaps = state.descriptor?.exposureCapabilities
    val enabled = (!constrained || control in setOf(ControlDial.RESOLUTION, ControlDial.FPS)) && when (control) {
        ControlDial.ISO -> exposureCaps?.let { it.supports(ExposureMode.MANUAL) || it.supports(ExposureMode.ISO_PRIORITY) } == true
        ControlDial.SHUTTER -> exposureCaps?.let { it.supports(ExposureMode.MANUAL) || it.supports(ExposureMode.SHUTTER_PRIORITY) } == true
        else -> true
    }
    // A high-speed session runs exposure automatically. The strip has no width for "AUTO·HS", so
    // there the HS moves under the value, next to the control name.
    val highSpeedAuto = constrained && control !in setOf(ControlDial.RESOLUTION, ControlDial.FPS, ControlDial.EV, ControlDial.INT)
    val value = when {
        control == ControlDial.RESOLUTION -> shortResolutionLabel(state.targetVideoWidth, state.targetVideoHeight)
        inStrip && highSpeedAuto -> "AUTO"
        else -> null
    } ?: when (control) {
        ControlDial.RESOLUTION -> "${state.targetVideoWidth}×${state.targetVideoHeight}"
        ControlDial.FPS -> state.targetFps.toString()
        ControlDial.SHUTTER -> if (constrained) "AUTO·HS" else state.effectiveSettings?.exposure?.takeIf {
            it.shutterUnit == ShutterUnit.ANGLE && it.mode in setOf(ExposureMode.MANUAL, ExposureMode.SHUTTER_PRIORITY) && !state.exposureControlUnavailable
        }?.let { "${it.angleTenths / 10.0}°${if (state.exposureClamped) "*" else ""}" } ?: state.exposureTimeNs?.let(::formatShutter) ?: "AUTO"
        ControlDial.ISO -> if (constrained) "AUTO·HS" else state.sensitivityIso?.toString() ?: "AUTO"
        ControlDial.WB -> if (constrained) "AUTO·HS" else state.requestedWhiteBalance.label()
        ControlDial.FOCUS -> if (constrained) "AUTO·HS" else state.focusDistanceDiopters?.let { "%.1fD".format(it) } ?: "AUTO"
        ControlDial.EV -> if (constrained) "0" else formatEv(state.aeCompensationEv)
        ControlDial.INT -> formatIntervalShort(state.timelapseIntervalMs)
    }
    Column(
        modifier
            .then(
                if (inStrip) Modifier.height(52.dp)
                else Modifier
                    .height(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1B2023).chromePanel())
                    .border(1.dp, Color(0xFF41494C), RoundedCornerShape(8.dp)),
            )
            .clickable(enabled = enabled) { onControl(control) }
            .padding(horizontal = if (inStrip) 2.dp else 4.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val label = when (control) {
            ControlDial.RESOLUTION -> "RES"
            ControlDial.SHUTTER -> "SHUTTER"
            else -> control.name
        }
        if (inStrip) {
            Text(label + if (highSpeedAuto) " · HS" else "", color = if (enabled) Muted else Color(0xFF626A6D), fontSize = 9.sp, lineHeight = 10.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, maxLines = 1)
            Text(value, color = if (enabled) Color.White else Muted, fontSize = stripValueSize, lineHeight = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        } else {
            Text(value, color = if (enabled) Color.White else Muted, fontSize = 13.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, color = if (enabled) Muted else Color(0xFF626A6D), fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ModeDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    compact: Boolean = false,
) {
    val selection = LocalModeSelection.current
    val displayedMode = selection?.displayed ?: state.selectedMode
    val selectMode: (CaptureMode) -> Unit = selection?.select ?: { mode -> binder?.selectMode(mode) }
    val modes = visibleCaptureModes(state.modeGates, displayedMode)
    val selectedIndex = modes.indexOf(displayedMode).coerceAtLeast(0)
    val haptics = LocalHapticFeedback.current
    val dialDescription = stringResource(R.string.mode_dial_description, modeLabel(displayedMode))
    fun selectable(mode: CaptureMode): Boolean = CameraUiState.isModeSelectable(state.modeGates.getValue(mode))

    // A mode wheel: a linear carousel whose centered mode is the active one, marked only by amber
    // text and a dot (no frame, so it reads the same on phones, foldables and tablets). Several
    // neighbors stay visible on both sides, drag settles on the nearest mode and tapping any
    // visible mode selects it directly.
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
                if (idx != selectedIndex && modes[idx] != displayedMode && selectable(modes[idx])) {
                    selectMode(modes[idx])
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                } else if (idx != selectedIndex && !selectable(modes[idx])) {
                    listState.animateScrollToItem(selectedIndex)
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .height(itemHeight * 5)
                .semantics { contentDescription = dialDescription },
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
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                                .semantics { selected = isSelected }
                                .clickable(enabled = enabled && !isSelected) { selectMode(mode) },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isSelected) Box(
                                Modifier
                                    .padding(end = 6.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(Amber),
                            )
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
            }
        }
    } else {
        // Horizontal mode wheel, full width so several modes stay visible with room to breathe.
        // Item width adapts to the measured container width; the selection cursor stays fixed
        // at the exact center via SnapPosition.Center and symmetric content padding.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = dialDescription },
        ) {
            val totalWidth = maxWidth
            val itemWidth = (totalWidth / 4.2f).coerceIn(76.dp, 140.dp)
            val rowHeight = 40.dp
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
                    if (idx != selectedIndex && modes[idx] != displayedMode && selectable(modes[idx])) {
                        selectMode(modes[idx])
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } else if (idx != selectedIndex && !selectable(modes[idx])) {
                        listState.animateScrollToItem(selectedIndex)
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(rowHeight)) {
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
                            Column(
                                Modifier
                                    .width(itemWidth)
                                    .height(rowHeight)
                                    .semantics { selected = isSelected }
                                    .clickable(enabled = enabled && !isSelected) { selectMode(mode) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    modeLabel(mode),
                                    color = when {
                                        isSelected -> Amber
                                        enabled -> Color.White
                                        else -> Muted
                                    },
                                    fontSize = if (isSelected) 15.sp else 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Box(
                                    Modifier
                                        .padding(top = 3.dp)
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) Amber else Color.Transparent),
                                )
                            }
                        }
                    }
                }
            }
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
            .background(Color(0xFF202528).chromePanel())
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
    val selection = LocalModeSelection.current
    val displayedMode = selection?.displayed ?: state.selectedMode
    val selectMode: (CaptureMode) -> Unit = selection?.select ?: { mode -> binder?.selectMode(mode) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.modes_title), color = Amber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        visibleCaptureModes(state.modeGates, displayedMode).chunked(2).forEach { rowModes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowModes.forEach { mode ->
                    val gate = state.modeGates.getValue(mode)
                    val enabled = CameraUiState.isModeSelectable(gate)
                    Column(
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (mode == displayedMode) Amber else Color(0xFF303638))
                            .clickable(enabled = enabled) {
                                selectMode(mode)
                                onSelected()
                            }
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(modeLabel(mode), color = if (mode == displayedMode) Color.Black else if (enabled) Color.White else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (gate != ModeGateState.AVAILABLE) Text(gateLabel(gate), color = if (mode == displayedMode) Color.Black else gateColor(gate), fontSize = 8.sp)
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
    // Side rails occupy the end edge; the panel opens beside them, over the viewfinder.
    endInset: androidx.compose.ui.unit.Dp = 0.dp,
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
                    end = endInset + if (horizontalDeck) 12.dp else 0.dp,
                    bottom = controlDeckHeight + 12.dp,
                )
                .widthIn(min = 260.dp, max = 380.dp)
                .heightIn(max = maximumHeight)
                .background(Color(0xF21A1F21).chromePanel(), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF4A5154), RoundedCornerShape(10.dp))
                .clickable { }
                .padding(12.dp),
        ) { content() }
    }
}

@Composable
internal fun rememberCaptureAction(state: CameraUiState, binder: CaptureService.LocalBinder?, settings: CameraSettings, enabled: Boolean = true): () -> Unit {
    val currentEnabled by rememberUpdatedState(enabled)
    val context = LocalContext.current
    var showAudioChoice by remember { mutableStateOf(false) }
    val currentBinder by rememberUpdatedState(binder)
    val actionTicket = CaptureActionTicket(state.selfRecordingActive, state.captureActionGeneration)
    val currentActionTicket by rememberUpdatedState(actionTicket)
    var audioChoiceTicket by remember { mutableStateOf<CaptureActionTicket?>(null) }
    var audioChoiceOwner by remember { mutableStateOf<CaptureService.LocalBinder?>(null) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        currentBinder?.refreshAudioCapabilities()
        val requestedTicket = audioChoiceTicket
        val owner = audioChoiceOwner
        audioChoiceTicket = null
        audioChoiceOwner = null
        if (granted && currentEnabled && requestedTicket != null && currentActionTicket == requestedTicket && owner != null && owner === currentBinder) {
            owner.captureForRole(requestedTicket, audioForThisTake = true)
        }
    }
    LaunchedEffect(enabled) {
        if (!enabled) { showAudioChoice = false; audioChoiceTicket = null; audioChoiceOwner = null }
    }
    if (showAudioChoice) {
        AlertDialog(
            onDismissRequest = { showAudioChoice = false; audioChoiceTicket = null; audioChoiceOwner = null },
            title = { Text(stringResource(R.string.audio_choice_title)) },
            text = { Text(stringResource(R.string.audio_choice_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showAudioChoice = false
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(stringResource(R.string.audio_choice_grant)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAudioChoice = false
                    val requestedTicket = audioChoiceTicket
                    val owner = audioChoiceOwner
                    audioChoiceTicket = null
                    audioChoiceOwner = null
                    if (currentEnabled && requestedTicket != null && owner != null && owner === binder) owner.captureForRole(requestedTicket, audioForThisTake = false)
                }) { Text(stringResource(R.string.audio_choice_without_audio)) }
            },
        )
    }
    return {
        if (enabled) {
            val needsAudioChoice = !state.capturePreparationCancelable && state.countdownSeconds == 0 && state.phase != CameraUiPhase.RECORDING &&
                settings.captureWantsAudio(state.selectedMode) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            if (needsAudioChoice) {
                audioChoiceTicket = actionTicket
                audioChoiceOwner = binder
                showAudioChoice = true
            } else binder?.captureForRole(actionTicket)
        }
    }
}

@Composable
internal fun CaptureButton(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    size: androidx.compose.ui.unit.Dp,
    // The landscape deck has width to spare but no height: there the label sits beside the button.
    labelBeside: Boolean = false,
) {
    val onCapture = LocalOperatorActions.current?.capture ?: rememberCaptureAction(state, binder, settings)
    val recording = state.phase == CameraUiPhase.RECORDING
    val recordState = recordButtonState(state)
    val captureDescription = stringResource(
        when {
            state.phase == CameraUiPhase.CAPTURING && state.audioRetirementPending -> R.string.audio_retirement_cancel_capture
            state.transferRetirementPending -> R.string.webdav_transfer_cancel_capture
            state.whiteBalancePreparing -> R.string.pro_wb_cancel_preparation
            state.countdownSeconds > 0 -> R.string.self_cancel_timer
            recording -> R.string.stop_recording
            state.selectedMode.isStillMode() -> R.string.capture_photo
            else -> R.string.start_recording
        },
    )
    val stateLabel = recordState?.let { stringResource(it.label) }
    val busy = recordState == RecordButtonState.PREPARING || recordState == RecordButtonState.FINALIZING
    val label: @Composable () -> Unit = {
        if (stateLabel != null) {
            Text(
                stateLabel,
                color = recordState!!.color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(top = if (labelBeside) 0.dp else 3.dp).testTag("record-state-label"),
            )
        }
    }
    val button: @Composable () -> Unit = {
        Box(
            Modifier
                .size(size)
                .semantics {
                    contentDescription = captureDescription
                    if (stateLabel != null) stateDescription = stateLabel
                }
                .border(3.dp, if (recordState == RecordButtonState.UNAVAILABLE) Muted else Color.White, CircleShape)
                .clip(CircleShape)
                .clickable(enabled = !state.recordingFinalizing && (state.capturePreparationCancelable || state.phase == CameraUiPhase.PREVIEWING ||
                    state.phase == CameraUiPhase.SAVED || state.phase == CameraUiPhase.RECORDING)) {
                    onCapture()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                when {
                    recordState == RecordButtonState.FINALIZING -> Modifier
                        .size(size * .34f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Muted)
                    recording -> Modifier
                        .size(size * .34f)
                        .testTag("recording-stop-glyph")
                        .clip(RoundedCornerShape(3.dp))
                        .background(RecordRed)
                    else -> Modifier
                        .size(size - 10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                recordState == RecordButtonState.UNAVAILABLE || recordState == RecordButtonState.PREPARING -> Color(0xFF3A4247)
                                state.selectedMode.isStillMode() -> Amber
                                else -> RecordRed
                            },
                        )
                },
            )
            // Starting and closing a take both spin; the colour says which one is in progress.
            val reducedMotion = LocalReducedMotion.current
            AnimatedVisibility(
                visible = busy,
                modifier = Modifier.matchParentSize(),
                enter = if (reducedMotion) EnterTransition.None else fadeIn(),
                exit = if (reducedMotion) ExitTransition.None else fadeOut(),
            ) {
                CircularWavyProgressIndicator(
                    color = if (recordState == RecordButtonState.PREPARING) RecordRed else Amber,
                    trackColor = Color.Transparent,
                    modifier = Modifier.fillMaxSize().padding(2.dp).testTag("record-busy-indicator"),
                )
            }
        }
    }
    if (labelBeside) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { label(); button() }
    else Column(horizontalAlignment = Alignment.CenterHorizontally) { button(); label() }
}

/**
 * What the record control can honestly claim, derived from the service's confirmed phase: a take
 * reads REC only once the service reports RECORDING, and a closing file is never shown as saved.
 * Still modes return null because their button has no recording lifecycle to explain.
 */
internal enum class RecordButtonState(val label: Int) {
    READY(R.string.rec_state_ready),
    PREPARING(R.string.rec_state_preparing),
    RECORDING(R.string.rec_state_recording),
    FINALIZING(R.string.rec_state_finalizing),
    SAVED(R.string.rec_state_saved),
    UNAVAILABLE(R.string.rec_state_unavailable),
}

internal val RecordButtonState.color: Color
    @Composable @ReadOnlyComposable get() = when (this) {
        RecordButtonState.READY -> Color.White
        RecordButtonState.PREPARING, RecordButtonState.FINALIZING -> Amber
        RecordButtonState.RECORDING -> RecordRed
        RecordButtonState.SAVED -> OkGreen
        RecordButtonState.UNAVAILABLE -> Muted
    }

internal fun recordButtonState(state: CameraUiState): RecordButtonState? {
    if (state.selectedMode.isStillMode()) return null
    return when {
        state.recordingFinalizing -> RecordButtonState.FINALIZING
        state.phase == CameraUiPhase.RECORDING -> RecordButtonState.RECORDING
        state.phase == CameraUiPhase.CAPTURING -> RecordButtonState.PREPARING
        state.phase == CameraUiPhase.SAVED -> RecordButtonState.SAVED
        state.phase == CameraUiPhase.PREVIEWING -> RecordButtonState.READY
        else -> RecordButtonState.UNAVAILABLE
    }
}

@Composable
private fun rememberBatteryPercent(): Int? {
    val context = LocalContext.current
    var batteryPercent by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            batteryPercent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            delay(5_000)
        }
    }
    return batteryPercent
}

/**
 * The top bar's one line of camera state: timecode when one runs, free space and battery. It
 * replaces the camera id and pipeline phase, which told the operator nothing they could act on.
 */
@Composable
private fun CaptureStatusLine(state: CameraUiState, modifier: Modifier = Modifier, maxLines: Int = 1, textAlign: TextAlign? = null) {
    val battery = rememberBatteryPercent()
    val low = battery != null && battery <= 15
    val text = androidx.compose.ui.text.buildAnnotatedString {
        state.timecodeDisplay?.let { tc ->
            withStyle(androidx.compose.ui.text.SpanStyle(color = Muted)) { append("TC ") }
            append(tc)
            append("  ·  ")
        }
        state.availableStorageBytes?.let { bytes ->
            append(stringResource(R.string.capture_status_free, formatBytes(bytes)))
            append("  ·  ")
        }
        withStyle(androidx.compose.ui.text.SpanStyle(color = Muted)) { append("BAT ") }
        withStyle(androidx.compose.ui.text.SpanStyle(color = if (low) RecordRed else OkGreen)) { append(battery?.let { "$it%" } ?: "—") }
    }
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier.testTag("capture-status-line"),
    )
}

@Composable
private fun StatusInfoBar(state: CameraUiState, settings: CameraSettings) {
    val batteryPercent = rememberBatteryPercent()
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
                Text((if (state.recordingPauseStatus?.paused == true) stringResource(R.string.recording_paused) else "REC") + " " + formatDuration(state.recordingElapsedMs), color = Color.White, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold)
                if (!compact && state.recordingWidth != null && state.recordingHeight != null) {
                    Text("${state.recordingWidth}\u00d7${state.recordingHeight}", color = Muted, fontSize = 10.sp)
                }
            }
            AudioMeterHud(state, binder, meterWidth = if (compact) 96.dp else 132.dp)
            ThermalHudChip()
            OutOfFrameHudChip(state)
            Spacer(Modifier.weight(1f))
            if (!compact) TopAction(CineIcon.MONITORING, stringResource(R.string.monitoring_tools)) { showMonitors = !showMonitors }
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
internal fun AudioMeterHud(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    modifier: Modifier = Modifier,
    meterWidth: androidx.compose.ui.unit.Dp = 132.dp,
    meterSettings: AudioMeterSettings = state.effectiveSettings?.audioMeter ?: AudioMeterSettings(),
    nowElapsedRealtimeMs: Long? = null,
    onResetClip: () -> Unit = { binder?.resetAudioClip() },
) {
    if (!meterSettings.visible) return
    var clockMs by remember { androidx.compose.runtime.mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(nowElapsedRealtimeMs) {
        if (nowElapsedRealtimeMs == null) while (true) { clockMs = SystemClock.elapsedRealtime(); delay(100) }
    }
    val now = nowElapsedRealtimeMs ?: maxOf(clockMs, SystemClock.elapsedRealtime())
    val snapshot = currentAudioMeterSnapshot(state.audioLevels, state.audioMonitoringActive, now)
    val levels = snapshot?.channels.orEmpty()
    val holder = remember(meterSettings, state.audioMonitoringActive, levels.size) { AudioMeterPeakHold() }
    val displayed = levels.map { meterSettings.displayDb(it) }
    val held = if (snapshot != null) holder.observe(snapshot.capturedAtElapsedRealtimeMs, now, displayed, meterSettings.peakHoldMs)
        else { holder.clear(); emptyList() }
    val resetLabel = stringResource(R.string.audio_meter_reset_clip)
    Column(
        modifier.width(meterWidth).heightIn(min = 48.dp).testTag("audio-meter-hud")
            .background(Panel, RoundedCornerShape(7.dp))
            .clickable(enabled = state.audioClipLatched, onClickLabel = resetLabel, onClick = onResetClip)
            .semantics { if (state.audioClipLatched) contentDescription = resetLabel }
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(if (snapshot != null) "MIC" else stringResource(R.string.audio_meter_no_pcm),
            Modifier.testTag("audio-meter-current"), color = if (snapshot != null) VerifiedCyan else Muted,
            fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(audioMeterModeLabel(meterSettings.mode)), Modifier.testTag("audio-meter-mode"), color = Muted, fontSize = 10.sp)
        if (state.audioClipLatched) Text("CLIP", Modifier.testTag("audio-meter-clip"), color = RecordRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        repeat(max(1, levels.size)) { index ->
            val level = levels.getOrNull(index)
            val value = displayed.getOrNull(index)
            val label = if (levels.size <= 1) "M" else if (index == 0) "L" else "R"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = Muted, fontSize = 9.sp)
                val meterRed = RecordRed; val meterAmber = Amber
                Canvas(Modifier.weight(1f).height(8.dp).testTag("audio-meter-channel-$index")) {
                    drawRect(Color(0xFF283033))
                    if (value != null) {
                        val minimum = if (meterSettings.mode == AudioMeterMode.VU) -30f else -60f
                        val maximum = if (meterSettings.mode == AudioMeterMode.VU) 6f else 0f
                        fun fraction(db: Float) = ((db - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
                        val signalColor = when {
                            (level?.peakDbfs ?: -120f) >= -3f -> meterRed
                            (level?.peakDbfs ?: -120f) >= -12f -> meterAmber
                            else -> Color(0xFF46C36F)
                        }
                        drawRect(signalColor, size = androidx.compose.ui.geometry.Size(size.width * fraction(value), size.height))
                        if (meterSettings.mode == AudioMeterMode.PEAK_RMS) level?.rmsDbfs?.takeIf { it.isFinite() }?.let { rms ->
                            drawLine(Color.White, androidx.compose.ui.geometry.Offset(size.width * fraction(rms), 0f),
                                androidx.compose.ui.geometry.Offset(size.width * fraction(rms), size.height), strokeWidth = 1.dp.toPx())
                        }
                        held.getOrNull(index)?.let { peak ->
                            drawLine(meterAmber, androidx.compose.ui.geometry.Offset(size.width * fraction(peak), 0f),
                                androidx.compose.ui.geometry.Offset(size.width * fraction(peak), size.height), strokeWidth = 2.dp.toPx())
                        }
                    }
                }
            }
            if (meterSettings.showValues) {
                fun number(value: Float?) = value?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "—"
                val valueText = when (meterSettings.mode) {
                    AudioMeterMode.PEAK_RMS -> stringResource(R.string.audio_meter_peak_rms_values, number(value), number(level?.rmsDbfs?.takeIf { it.isFinite() }))
                    AudioMeterMode.VU -> stringResource(R.string.audio_meter_vu_value, number(value))
                    AudioMeterMode.PPM -> stringResource(R.string.audio_meter_ppm_value, number(value))
                }
                Text(valueText, Modifier.testTag("audio-meter-value-$index"), color = Color.White, fontSize = 10.sp)
                if (value != null && meterSettings.peakHoldMs > 0) Text(stringResource(R.string.audio_meter_hold_value, number(held.getOrNull(index))),
                    Modifier.testTag("audio-meter-hold-$index"), color = Amber, fontSize = 10.sp)
            }
        }
    }
}

private fun audioMeterModeLabel(mode: AudioMeterMode): Int = when (mode) {
    AudioMeterMode.PEAK_RMS -> R.string.audio_meter_peak_rms
    AudioMeterMode.VU -> R.string.audio_meter_vu
    AudioMeterMode.PPM -> R.string.audio_meter_ppm
}


@Composable
private fun CaptureStatus(state: CameraUiState, modifier: Modifier = Modifier, chip: Boolean = false) {
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
    val controlNotice = if (state.exposureControlUnavailable || state.effectiveSettings?.whiteBalance?.let { it != state.requestedWhiteBalance } == true)
        stringResource(R.string.pro_capture_notice) else null
    val baseStatus = when {
        state.phase == CameraUiPhase.RECORDING && state.recordingWidth != null && state.recordingHeight != null ->
            stringResource(R.string.recording_status, state.recordingWidth, state.recordingHeight, state.targetFps,
                formatDuration(state.recordingElapsedMs), formatBytes(state.availableStorageBytes))
        // The error sheet already states a failure; repeating it here would show it twice.
        state.phase == CameraUiPhase.ERROR -> null
        else -> state.message
    }?.takeIf { !state.messageTransient || noticeVisible }
    val recoveryNotice = state.stillRecovery?.takeIf { it.discardedGroups > 0 || it.unresolvedGroups > 0 }?.let {
        stringResource(R.string.still_recovery_notice, it.discardedGroups, it.unresolvedGroups)
    }
    val recordingRecoveryNotice = state.recordingRecovery?.takeIf { it.discardedGroups > 0 || it.unresolvedGroups > 0 }?.let {
        stringResource(R.string.recording_recovery_notice, it.discardedGroups, it.unresolvedGroups)
    }
    val status = listOfNotNull(recoveryNotice, recordingRecoveryNotice, baseStatus, controlNotice).joinToString(" · ").takeIf { it.isNotBlank() } ?: return
    Text(
        status,
        color = if (state.errorCode == null) Color.White else RecordRed,
        fontSize = if (chip) 11.sp else 10.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (chip) TextAlign.Center else null,
        modifier = if (chip) modifier
            .testTag("capture-status-chip")
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f).chromePanel(), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
        else modifier.fillMaxWidth().padding(horizontal = 8.dp),
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
        IntervalometerDial(state, settings, onSettingsChanged, onClose)
        return
    }
    val value = when (control) {
        ControlDial.RESOLUTION -> 0f
        ControlDial.FPS -> 0f
        ControlDial.ISO -> logPosition((state.requestedIso ?: state.sensitivityIso ?: descriptor.sensitivityRange?.lower ?: 100).toDouble(), descriptor.sensitivityRange?.lower?.toDouble() ?: 50.0, descriptor.sensitivityRange?.upper?.toDouble() ?: 6400.0)
        ControlDial.SHUTTER -> if (settings.exposure.shutterUnit == ShutterUnit.ANGLE) ((settings.exposure.angleTenths - 1) / 3599f) else logPosition((state.requestedExposureTimeNs ?: state.exposureTimeNs ?: 16_666_667L).toDouble(), descriptor.exposureTimeRangeNs?.lower?.toDouble() ?: 100_000.0, descriptor.exposureTimeRangeNs?.upper?.toDouble() ?: 1_000_000_000.0)
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
                        onSettingsChanged(settings.copy(exposure = settings.exposure.copy(
                            mode = if (settings.exposure.mode == ExposureMode.ISO_PRIORITY || !descriptor.exposureCapabilities.supports(ExposureMode.MANUAL)) ExposureMode.ISO_PRIORITY else ExposureMode.MANUAL,
                            iso = iso,
                            timeNs = if (settings.exposure.mode == ExposureMode.AUTO) state.exposureTimeNs ?: settings.exposure.timeNs else settings.exposure.timeNs,
                        )))
                    }
                    ControlDial.SHUTTER -> {
                        val intent = settings.exposure.copy(
                            mode = if (settings.exposure.mode == ExposureMode.SHUTTER_PRIORITY || !descriptor.exposureCapabilities.supports(ExposureMode.MANUAL)) ExposureMode.SHUTTER_PRIORITY else ExposureMode.MANUAL,
                            iso = if (settings.exposure.mode == ExposureMode.AUTO) state.sensitivityIso ?: settings.exposure.iso else settings.exposure.iso,
                        )
                        onSettingsChanged(settings.copy(exposure = if (intent.shutterUnit == ShutterUnit.ANGLE) {
                            intent.copy(angleTenths = (position * 3599 + 1).roundToInt().coerceIn(1, 3600))
                        } else intent.copy(timeNs = logValue(position, descriptor.exposureTimeRangeNs?.lower?.toDouble() ?: 100_000.0,
                            descriptor.exposureTimeRangeNs?.upper?.toDouble() ?: 1_000_000_000.0).toLong())))
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
        PanelHeader(stringResource(R.string.panel_title_ev), onClose)
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
        PanelHeader(stringResource(R.string.panel_title_resolution), onClose)
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
        PanelHeader(stringResource(R.string.panel_title_fixed_fps), onClose)
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
        // Camera2 feeds the preview one frame per high-speed batch, so the viewfinder of a 120 or
        // 240 fps session runs at 30 fps while the file keeps every frame. Say so here.
        Text(stringResource(R.string.fps_high_speed_preview_note), color = Muted, fontSize = 12.sp,
            modifier = Modifier.testTag("fps-high-speed-note"))
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
    val retainedTint = (state.effectiveSettings?.whiteBalance as? WhiteBalanceSelection.Kelvin)?.tint
        ?: (selection as? WhiteBalanceSelection.Kelvin)?.tint ?: 0

    if (kelvinRange != null) {
        // Direct Kelvin (CCT) path - slider + presets
        val currentK = (selection as? WhiteBalanceSelection.Kelvin)?.kelvin
            ?: KELVIN_PRESETS.firstOrNull { it in kelvinRange }
            ?: kelvinRange.first
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelHeader(stringResource(R.string.panel_title_white_balance), onClose)

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
                        if (snapped != null) binder?.setWhiteBalance(WhiteBalanceSelection.Kelvin(snapped, retainedTint))
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
                        binder?.setWhiteBalance(WhiteBalanceSelection.Kelvin(preset, retainedTint))
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
            PanelHeader(stringResource(R.string.panel_title_white_balance), onClose)
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
private fun IntervalometerDial(state: CameraUiState, settings: CameraSettings, onSettingsChanged: (CameraSettings) -> Unit, onClose: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PanelHeader(stringResource(R.string.timelapse_interval), onClose)
        TimelapseSettings(state, settings, onSettingsChanged)
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
        ) { Text("×", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp) }
    }
}

@Composable
internal fun ChoiceTile(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> Amber
                    !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NavigationBar(selected: AppSection, onSelect: (AppSection) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(54.dp).background(MaterialTheme.colorScheme.surfaceContainer)) {
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
internal fun SettingsContent(
    state: CameraUiState,
    settings: CameraSettings,
    audioPermissionGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
    onOpenAbout: () -> Unit,
    onSettingsChange: (CameraSettings) -> Unit,
    visibleIds: Set<String>,
    onApplyPreset: ((CameraPreset) -> Unit)? = null,
    onOpenCapabilities: (() -> Unit)? = null,
) {
    // Cards flow into two columns wherever each keeps a usable width (an unfolded foldable
    // already qualifies); long forms and libraries always take the whole row.
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(260.dp),
        modifier = Modifier.fillMaxSize().background(Graphite).testTag("settings-list"),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
    ) {
        if ("media-sharing" in visibleIds) settingsCard("media-sharing", fullLine = true) {
            MediaSharingSettingsControls(settings.mediaSharing, onSettings = { onSettingsChange(settings.copy(mediaSharing = it)) })
        }
        if ("media-gallery" in visibleIds) settingsCard("media-gallery") {
            GallerySettingsControls(settings.gallery, onSettings = { onSettingsChange(settings.copy(gallery = it)) })
        }
        if ("proxy" in visibleIds) settingsCard("proxy", fullLine = true) {
            ProxySettingsControls(settings.proxy) { onSettingsChange(settings.copy(proxy = it)) }
        }
        if ("playback" in visibleIds) settingsCard("playback") {
            PlaybackSettingsControls(settings.playback) { onSettingsChange(settings.copy(playback = it)) }
        }
        if ("capture-naming" in visibleIds) settingsCard("capture-naming", fullLine = true) {
            CaptureNamingSettingsControls(settings, onSettingsChange)
        }
        if ("geotagging" in visibleIds) settingsCard("geotagging") {
            GeotaggingSettingsControls(settings, onSettingsChange)
        }
        if ("production-slate" in visibleIds) settingsCard("production-slate", fullLine = true) {
            ProductionSlateSettingsControls(state, settings, onSettingsChange)
        }
        if ("project-timing" in visibleIds) settingsCard("project-timing", fullLine = true) {
            ProjectTimingSettings(state, settings, onSettingsChange)
        }
        if ("timelapse" in visibleIds) settingsCard("timelapse", fullLine = true) {
            TimelapseSettings(state, settings, onSettingsChange)
        }
        if ("operator-controls" in visibleIds) settingsCard("operator-controls", fullLine = true) {
            OperatorSettings(state, settings, onSettingsChange)
        }
        if ("webdav-queue" in visibleIds) settingsCard("webdav-queue", fullLine = true) { WebDavQueueSettingsSection() }
        if ("presets" in visibleIds) settingsCard("presets", fullLine = true) {
            PresetSettings(state, settings, onApplyPreset)
        }
        if ("image-processing" in visibleIds) settingsCard("image-processing", fullLine = true) {
            ImageProcessingSettings(state, settings, onSettingsChange)
        }
        if ("professional-exposure" in visibleIds) settingsCard("professional-exposure", fullLine = true) {
            ProfessionalExposureSettings(state, settings, onSettingsChange)
        }
        if ("fold-displays" in visibleIds) settingsCard("fold-displays", fullLine = true) {
            FoldDisplaySettings(state, settings, onSettingsChange, showTitle = false)
        }
        // OCC-PLAN-068 subject features: each card's controls live in that unit's own file.
        val subject = settings.subjectDisplay
        val updateSubject = { next: SubjectDisplaySettings -> onSettingsChange(settings.copy(subjectDisplay = next)) }
        if ("subject-self-monitor" in visibleIds) settingsCard("subject-self-monitor") {
            SettingsHeading(stringResource(R.string.subject_self_monitor_title)); SubjectSelfMonitorSettings(state, subject, updateSubject)
        }
        if ("subject-tally" in visibleIds) settingsCard("subject-tally") {
            SettingsHeading(stringResource(R.string.subject_tally_title)); SubjectTallySettings(state, subject, updateSubject)
        }
        if ("subject-fill-light" in visibleIds) settingsCard("subject-fill-light") {
            SettingsHeading(stringResource(R.string.fold_mode_fill_light)); SubjectFillLightSettings(state, subject, updateSubject)
        }
        if ("subject-interview" in visibleIds) settingsCard("subject-interview", fullLine = true) {
            SettingsHeading(stringResource(R.string.fold_mode_interview)); SubjectInterviewSettings(state, subject, updateSubject)
        }
        if ("subject-slate" in visibleIds) settingsCard("subject-slate") {
            SettingsHeading(stringResource(R.string.fold_mode_slate)); SubjectSlateSettings(state, subject, updateSubject)
        }
        if ("subject-out-of-frame" in visibleIds) settingsCard("subject-out-of-frame") {
            SettingsHeading(stringResource(R.string.subject_out_of_frame_title)); SubjectOutOfFrameSettings(state, subject, updateSubject)
        }
        if ("appearance" in visibleIds) settingsCard("appearance") { AppearanceSettings() }
        if ("layout" in visibleIds) settingsCard("layout") {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.mode_selector_style), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.mode_selector_summary), color = Muted, fontSize = 14.sp)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSelectorStyle.entries.forEach { style ->
                        TextButton(onClick = { onSettingsChange(settings.copy(modeSelectorStyle = style)) }) {
                            Text(
                                if (style == ModeSelectorStyle.DIAL) stringResource(R.string.mode_selector_dial) else stringResource(R.string.mode_selector_buttons),
                                color = if (settings.modeSelectorStyle == style) Amber else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (settings.modeSelectorStyle == style) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        if ("translucent-chrome" in visibleIds) settingsCard("translucent-chrome") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsToggleRow(
                    title = stringResource(R.string.translucent_chrome),
                    summary = stringResource(R.string.translucent_chrome_summary),
                    checked = settings.translucentChrome,
                    onCheckedChange = { onSettingsChange(settings.copy(translucentChrome = it)) },
                )
                if (settings.translucentChrome) {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp)) {
                        Text(stringResource(R.string.viewfinder_scale), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.viewfinder_scale_summary), color = Muted, fontSize = 14.sp)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ViewfinderScale.entries.forEach { scale ->
                                TextButton(onClick = { onSettingsChange(settings.copy(viewfinderScale = scale)) }) {
                                    Text(
                                        if (scale == ViewfinderScale.FIT) stringResource(R.string.viewfinder_scale_fit) else stringResource(R.string.viewfinder_scale_fill),
                                        color = if (settings.viewfinderScale == scale) Amber else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (settings.viewfinderScale == scale) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                        Text(
                            "${stringResource(R.string.chrome_opacity)} · ${(settings.chromeOpacity * 100).roundToInt()}%",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Slider(
                            value = settings.chromeOpacity,
                            onValueChange = { onSettingsChange(settings.copy(chromeOpacity = clampChromeOpacity((it * 20f).roundToInt() / 20f))) },
                            valueRange = MIN_CHROME_OPACITY..MAX_CHROME_OPACITY,
                            steps = 10,
                        )
                    }
                }
            }
        }
        if ("audio" in visibleIds) settingsCard("audio") {
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
            if ("audio-permission" in visibleIds) settingsCard("audio-permission") {
                Button(
                    onClick = onRequestAudioPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Text(stringResource(R.string.grant_microphone)) }
            }
        }
        if ("audio-format" in visibleIds) settingsCard("audio-format", fullLine = true) {
            if (!audioPermissionGranted) {
                Text(stringResource(R.string.audio_permission_summary), color = Muted, fontSize = 14.sp)
                Button(onClick = onRequestAudioPermission) { Text(stringResource(R.string.grant_microphone)) }
            } else {
                ProfessionalAudioSettings(
                    state = state,
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                )
            }
        }
        if ("burst" in visibleIds) settingsCard("burst") {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp)) {
                SettingsHelp(stringResource(R.string.burst_capture_help))
                Text("${stringResource(R.string.burst_count)} · ${settings.burstCount}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Slider(
                    value = settings.burstCount.toFloat(),
                    onValueChange = { onSettingsChange(settings.copy(burstCount = it.roundToInt().coerceIn(3, 10))) },
                    valueRange = 3f..10f,
                    steps = 6,
                )
            }
        }
        if ("bitrate" in visibleIds) settingsCard("bitrate") {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.video_bitrate), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(12, 20, 40).forEach { bitrate ->
                        TextButton(onClick = { onSettingsChange(settings.copy(videoBitrateMbps = bitrate)) }) {
                            Text(
                                "$bitrate Mbps",
                                color = if (settings.videoBitrateMbps == bitrate) Amber else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (settings.videoBitrateMbps == bitrate) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        if ("geometry" in visibleIds) settingsCard("geometry") {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.recording_geometry), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.recording_geometry_summary), color = Muted, fontSize = 14.sp)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                color = if (settings.recordingGeometryMode == mode) Amber else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (settings.recordingGeometryMode == mode) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        if ("anamorphic" in visibleIds) settingsCard("anamorphic") {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.anamorphic), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.anamorphic_summary), color = Muted, fontSize = 14.sp)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                color = if (settings.anamorphicSqueeze == squeeze) Amber else MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = if (settings.anamorphicSqueeze == squeeze) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                if (settings.anamorphicSqueeze.isActive) {
                    Text(stringResource(R.string.anamorphic_output), color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                    color = if (settings.anamorphicOutputMode == mode) Amber else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = if (settings.anamorphicOutputMode == mode) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
        if ("accumulation" in visibleIds) settingsCard("accumulation", fullLine = true) {
            AccumulationSettings(state, settings, onSettingsChange)
        }
        if ("bracket" in visibleIds) settingsCard("bracket", fullLine = true) {
            BracketSettings(state, settings, onSettingsChange)
        }
        if ("lut-library" in visibleIds) settingsCard("lut-library", fullLine = true) {
            LutLibrarySettings(state)
        }
        if ("monitoring-scopes" in visibleIds) settingsCard("monitoring-scopes", fullLine = true) {
            MonitoringSettings(settings, onSettingsChange)
        }
        if ("photo-aspect" in visibleIds) settingsCard("photo-aspect", fullLine = true) {
            PhotoAspectSettings(state, settings, onSettingsChange)
        }
        if ("photo-format" in visibleIds) settingsCard("photo-format", fullLine = true) {
            PhotoFormatSettings(state, settings, onSettingsChange)
        }
        if ("photo-flash" in visibleIds) settingsCard("photo-flash", fullLine = true) {
            PhotoFlashSettings(state, settings, onSettingsChange)
        }
        if ("torch" in visibleIds) settingsCard("torch", fullLine = true) {
            TorchSettings(state, settings, onSettingsChange)
        }
        if ("zebra" in visibleIds) settingsCard("zebra") {
            SettingsToggleRow(title = stringResource(R.string.monitor_zebra), summary = null, checked = settings.zebraEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(zebraEnabled = it)) })
        }
        if ("peaking" in visibleIds) settingsCard("peaking") {
            SettingsToggleRow(title = stringResource(R.string.monitor_peaking), summary = null, checked = settings.peakingEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(peakingEnabled = it)) })
        }
        if ("histogram" in visibleIds) settingsCard("histogram") {
            SettingsToggleRow(
                title = stringResource(R.string.histogram_default),
                summary = null,
                checked = settings.histogramEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(histogramEnabled = it)) },
            )
        }
        if ("grid" in visibleIds) settingsCard("grid") {
            SettingsToggleRow(
                title = stringResource(R.string.composition_grid),
                summary = stringResource(R.string.composition_grid_summary),
                checked = settings.compositionGridEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(compositionGridEnabled = it)) },
            )
        }
        if ("grid-mode" in visibleIds) settingsCard("grid-mode") {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.composition_grid_mode), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompositionGridMode.entries.forEach { mode ->
                        TextButton(onClick = { onSettingsChange(settings.copy(compositionGridMode = mode)) }) {
                            Text(
                                stringResource(compositionGridModeTitle(mode)),
                                color = if (settings.compositionGridMode == mode) Amber else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (settings.compositionGridMode == mode) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        if ("horizon" in visibleIds) settingsCard("horizon") {
            SettingsToggleRow(
                title = stringResource(R.string.horizon_level),
                summary = stringResource(R.string.horizon_level_summary),
                checked = settings.horizonLevelEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(horizonLevelEnabled = it)) },
            )
        }
        if ("metering" in visibleIds) settingsCard("metering") {
            SettingsToggleRow(
                title = stringResource(R.string.tap_exposure_metering),
                summary = stringResource(R.string.tap_exposure_metering_summary),
                checked = settings.tapExposureMeteringEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(tapExposureMeteringEnabled = it)) },
            )
        }
        if ("assist" in visibleIds) settingsCard("assist") {
            SettingsToggleRow(
                title = stringResource(R.string.log_view_assist),
                summary = stringResource(R.string.log_view_assist_summary),
                checked = settings.logViewAssistEnabled,
                enabled = state.descriptor?.supportsOpenCineLog == true,
                onCheckedChange = { onSettingsChange(settings.copy(logViewAssistEnabled = it)) },
            )
        }
        if ("log-grey-reference" in visibleIds) settingsCard("log-grey-reference") {
            LogGreyReferenceSettings(state, settings, onSettingsChange)
        }
        if ("focus-lock" in visibleIds) settingsCard("focus-lock") {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.af_lock_behavior), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.af_lock_behavior_summary), color = Muted, fontSize = 14.sp)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AfLockBehavior.entries.forEach { behavior ->
                        TextButton(onClick = { onSettingsChange(settings.copy(afLockBehavior = behavior)) }) {
                            Text(
                                if (behavior == AfLockBehavior.FREEZE_CURRENT) stringResource(R.string.af_lock_freeze_current)
                                else stringResource(R.string.af_lock_focus_and_lock),
                                color = if (settings.afLockBehavior == behavior) Amber else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (settings.afLockBehavior == behavior) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        if ("zoom-lens" in visibleIds) settingsCard("zoom-lens", fullLine = true) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.zoom_lens_switch_mode), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.zoom_lens_switch_mode_summary), color = Muted, fontSize = 14.sp)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZoomLensSwitchMode.entries.forEach { mode ->
                        val chosen = settings.zoomLensSwitchMode == mode
                        TextButton(onClick = { onSettingsChange(settings.copy(zoomLensSwitchMode = mode)) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("zoom-lens-switch-$mode").semantics { selected = chosen }) {
                            Text(
                                stringResource(if (mode == ZoomLensSwitchMode.MANUAL_PRESETS) R.string.zoom_lens_switch_mode_manual else R.string.zoom_lens_switch_mode_automatic),
                                color = if (chosen) Amber else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        if ("timecode" in visibleIds) settingsCard("timecode") {
            TimecodeSettings(settings, onSettingsChange)
        }
        if ("camera-capabilities" in visibleIds && onOpenCapabilities != null) settingsCard("camera-capabilities", fullLine = true) {
            SettingsLinkRow(stringResource(R.string.caps_title), stringResource(R.string.caps_settings_summary), onOpenCapabilities,
                Modifier.testTag("settings-open-capabilities"))
        }
        if ("hardware" in visibleIds) settingsCard("hardware", fullLine = true) {
            val descriptor = state.descriptor
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(stringResource(R.string.hardware_truth), color = VerifiedCyan, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.hardware_camera_line, descriptor?.cameraId ?: "—", descriptor?.previewSize?.width ?: 0, descriptor?.previewSize?.height ?: 0), color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.hardware_still_line, descriptor?.jpegSize?.width ?: 0, descriptor?.jpegSize?.height ?: 0,
                    stringResource(if (descriptor?.supportsRaw == true) R.string.hardware_supported else R.string.hardware_not_supported)), color = Muted)
                val profilesVerifiedTemplate = stringResource(R.string.hardware_log_profiles_verified)
                val logUnsupported = stringResource(R.string.hardware_log_unsupported)
                Text(
                    if (descriptor?.supportsOpenCineLog == true) {
                        val trueLog = descriptor.logProfiles.filter { it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020 }
                        val hfrLog = descriptor.logProfiles.filter { it.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP }
                        val verifiedCount = descriptor.logProfiles.count { it.isVerified }
                        val qualification = if (descriptor.allOpenCineLogProfilesVerified) {
                            "VERIFIED"
                        } else {
                            profilesVerifiedTemplate.format(verifiedCount, descriptor.logProfiles.size)
                        }
                        "OCLog2 $qualification · HLG10-DERIVED ${trueLog.maxOfOrNull { it.size.width } ?: 0}×${trueLog.maxOfOrNull { it.size.height } ?: 0} @ ${trueLog.maxOfOrNull { it.fps } ?: 0} max" +
                            if (hfrLog.isNotEmpty()) " · HFR ISP-DERIVED ${hfrLog.maxOf { it.fps }} max" else ""
                    } else {
                        logUnsupported
                    },
                    color = when {
                        descriptor?.allOpenCineLogProfilesVerified == true -> VerifiedCyan
                        descriptor?.supportsOpenCineLog == true -> Amber
                        else -> RecordRed
                    },
                    fontSize = 14.sp,
                )
            }
        }
        if ("modes" in visibleIds) items(CaptureMode.entries) { mode ->
            val gate = state.modeGates.getValue(mode)
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(7.dp)).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(modeLabel(mode), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Text(gateLabel(gate), color = gateColor(gate), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        if ("about" in visibleIds) settingsCard("about") {
            SettingsLinkRow(stringResource(R.string.about_title), stringResource(R.string.about_settings_summary), onOpenAbout)
        }
    }
}

/** A row that opens a full page of its own (About, camera capabilities). */
@Composable
private fun SettingsLinkRow(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            Text(description, color = Muted, fontSize = 14.sp)
        }
        Text("›", color = Amber, fontSize = 22.sp)
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
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.professional_audio), color = VerifiedCyan, fontWeight = FontWeight.Bold)
        AudioListeningSettingsControls(state, settings, onSettingsChange, LocalAudioListeningActions.current)
        AudioEffectsSettingsStatus(state, settings)
        AudioMeterSettingsControls(settings, onSettingsChange)
        if (capabilities == null) {
            Text(stringResource(R.string.audio_measuring), color = Muted, fontSize = 14.sp)
            return@Column
        }
        if (capabilities.formats.isEmpty()) {
            Text(stringResource(R.string.audio_no_route), color = RecordRed, fontSize = 14.sp)
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
            Text(stringResource(R.string.audio_gain_aac_backend), color = Muted, fontSize = 14.sp)
        } else {
            val derived = settings.audioSampleRateHz * settings.audioBitDepth.bits * settings.audioChannels / 1_000
            val container = if (settings.audioOutputFormat == AudioOutputFormat.FLAC) "Lossless compressed FLAC" else "WAV PCM"
            Text("$container · PCM fuente: $derived kbps · archivo sincronizado junto al video", color = VerifiedCyan, fontSize = 14.sp)
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
        Text(stringResource(R.string.audio_input_device_hint), color = Muted, fontSize = 14.sp)

        SettingsToggleRow(
            title = stringResource(R.string.audio_effect_ns),
            summary = stringResource(R.string.audio_effect_request_help),
            checked = settings.noiseSuppressorEnabled,
            enabled = capabilities.noiseSuppressorAvailable,
            onCheckedChange = { update(settings.copy(noiseSuppressorEnabled = it)) },
        )
        SettingsToggleRow(
            title = stringResource(R.string.audio_effect_aec),
            summary = stringResource(R.string.audio_effect_request_help),
            checked = settings.acousticEchoCancelerEnabled,
            enabled = capabilities.acousticEchoCancelerAvailable,
            onCheckedChange = { update(settings.copy(acousticEchoCancelerEnabled = it)) },
        )
        AudioRecordingGainSettings(state, settings, onSettingsChange)
        Text(stringResource(R.string.audio_format_container_hint), color = Muted, fontSize = 14.sp)
    }
}

/** Edits target the next admitted capture; the service owns saved-take increment and publication. */
@Composable
internal fun ProductionSlateSettingsControls(state: CameraUiState, settings: CameraSettings, onSettingsChange: (CameraSettings) -> Unit) {
    val slate = settings.productionSlate
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.production_slate_title), color = VerifiedCyan, fontWeight = FontWeight.Bold)
        SettingsHelp(stringResource(R.string.production_slate_help), tag = "slate-help")
        if (state.structuralSettingsFrozen && state.effectiveSettings?.productionSlate?.let { it != slate } == true) {
            Text(stringResource(R.string.production_slate_pending), Modifier.testTag("slate-pending"), color = Amber, fontSize = 14.sp)
        }
        ProductionSlateTextField("project", R.string.production_slate_project, slate.project) { onSettingsChange(settings.copy(productionSlate = slate.copy(project = it))) }
        ProductionSlateTextField("camera", R.string.production_slate_camera, slate.camera) { onSettingsChange(settings.copy(productionSlate = slate.copy(camera = it))) }
        ProductionSlateTextField("scene", R.string.production_slate_scene, slate.scene) { onSettingsChange(settings.copy(productionSlate = slate.copy(scene = it))) }
        ProductionSlateTextField("reel", R.string.production_slate_reel, slate.reel) { onSettingsChange(settings.copy(productionSlate = slate.copy(reel = it))) }
        ProductionSlateTextField("lens", R.string.production_slate_lens, slate.lens) { onSettingsChange(settings.copy(productionSlate = slate.copy(lens = it))) }
        var takeText by rememberSaveable(slate.takeNumber) { mutableStateOf(slate.takeNumber.toString()) }
        val take = takeText.toIntOrNull()?.takeIf { it in 1..999999 && it.toString() == takeText }
        OutlinedTextField(takeText, { value ->
            takeText = value
            value.toIntOrNull()?.takeIf { it in 1..999999 && it.toString() == value }?.let { onSettingsChange(settings.copy(productionSlate = slate.copy(takeNumber = it))) }
        }, label = { Text(stringResource(R.string.production_slate_take), Modifier.fillMaxWidth().testTag("slate-take-label")) }, isError = take == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("slate-take"))
        if (take == null) Text(stringResource(R.string.production_slate_invalid_take), Modifier.testTag("slate-take-invalid"), color = Amber, fontSize = 14.sp)
        SettingsChips(stringResource(R.string.production_slate_location), ProductionSlateLocation.entries, slate.location,
            label = { stringResource(when (it) {
                ProductionSlateLocation.UNSPECIFIED -> R.string.production_slate_unspecified
                ProductionSlateLocation.INTERIOR -> R.string.production_slate_interior
                ProductionSlateLocation.EXTERIOR -> R.string.production_slate_exterior
            }) },
            onSelect = { onSettingsChange(settings.copy(productionSlate = slate.copy(location = it))) }, tag = { "slate-location-$it" })
        SettingsChips(stringResource(R.string.production_slate_time), ProductionSlateTimeOfDay.entries, slate.timeOfDay,
            label = { stringResource(when (it) {
                ProductionSlateTimeOfDay.UNSPECIFIED -> R.string.production_slate_unspecified
                ProductionSlateTimeOfDay.DAY -> R.string.production_slate_day
                ProductionSlateTimeOfDay.NIGHT -> R.string.production_slate_night
            }) },
            onSelect = { onSettingsChange(settings.copy(productionSlate = slate.copy(timeOfDay = it))) }, tag = { "slate-time-$it" })
        val goodLabel = stringResource(R.string.production_slate_good)
        SettingsSwitchRow(goodLabel, slate.goodTake, { onSettingsChange(settings.copy(productionSlate = slate.copy(goodTake = it))) },
            tag = "slate-good", labelTag = "slate-good-label")
        val incrementLabel = stringResource(R.string.production_slate_increment)
        SettingsSwitchRow(incrementLabel, slate.autoIncrementTake,
            { onSettingsChange(settings.copy(productionSlate = slate.copy(autoIncrementTake = it))) },
            tag = "slate-increment", labelTag = "slate-increment-label")
    }
}

@Composable
private fun ProductionSlateTextField(tag: String, label: Int, value: String, onChange: (String) -> Unit) {
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val valid = validProductionSlateText(draft)
    OutlinedTextField(draft, { draft = it; if (validProductionSlateText(it)) onChange(it) },
        label = { Text(stringResource(label), Modifier.fillMaxWidth().testTag("slate-$tag-label")) }, isError = !valid,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("slate-$tag"))
    if (!valid) Text(stringResource(R.string.production_slate_invalid_text), Modifier.testTag("slate-$tag-invalid"), color = Amber, fontSize = 14.sp)
}

/** All controls below are live presentation preferences, not microphone or encoder settings. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AudioMeterSettingsControls(settings: CameraSettings, onSettingsChange: (CameraSettings) -> Unit) {
    val options = settings.audioMeter
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.audio_meter_title), color = VerifiedCyan, fontWeight = FontWeight.Bold)
        SettingsHelp(stringResource(R.string.audio_meter_help), tag = "audio-meter-settings-help")
        SettingsSwitchRow(stringResource(R.string.audio_meter_visible), options.visible,
            { onSettingsChange(settings.copy(audioMeter = options.copy(visible = it))) }, tag = "audio-meter-settings-visible")
        SettingsChips(stringResource(R.string.settings_mode), AudioMeterMode.entries, options.mode,
            label = { stringResource(audioMeterModeLabel(it)) },
            onSelect = { onSettingsChange(settings.copy(audioMeter = options.copy(mode = it))) }, tag = { "audio-meter-settings-mode-$it" })
        val referenceLabel = stringResource(R.string.audio_meter_reference, options.vuReferenceDbfs)
        Text(referenceLabel, Modifier.testTag("audio-meter-settings-reference-label"), color = MaterialTheme.colorScheme.onSurface)
        val referenceInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Slider(options.vuReferenceDbfs.toFloat(), { onSettingsChange(settings.copy(audioMeter = options.copy(vuReferenceDbfs = it.roundToInt()))) },
            valueRange = -24f..-6f, steps = 17, interactionSource = referenceInteraction,
            thumb = { androidx.compose.material3.SliderDefaults.Thumb(referenceInteraction, thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 52.dp)) },
            track = { androidx.compose.material3.SliderDefaults.Track(it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("audio-meter-settings-reference").semantics { contentDescription = referenceLabel })
        val holdLabel = stringResource(R.string.audio_meter_hold, options.peakHoldMs)
        Text(holdLabel, Modifier.testTag("audio-meter-settings-hold-label"), color = MaterialTheme.colorScheme.onSurface)
        val holdInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Slider(options.peakHoldMs.toFloat(), { onSettingsChange(settings.copy(audioMeter = options.copy(peakHoldMs = it.roundToInt()))) },
            valueRange = 0f..3000f, interactionSource = holdInteraction,
            thumb = { androidx.compose.material3.SliderDefaults.Thumb(holdInteraction, thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 52.dp)) },
            track = { androidx.compose.material3.SliderDefaults.Track(it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("audio-meter-settings-hold").semantics { contentDescription = holdLabel })
        val valuesLabel = stringResource(R.string.audio_meter_numbers)
        SettingsSwitchRow(valuesLabel, options.showValues,
            { onSettingsChange(settings.copy(audioMeter = options.copy(showValues = it))) }, tag = "audio-meter-settings-values")
    }
}

/** Observations belong to the current PCM producer, never to a capability or preference. */
@Composable
internal fun AudioEffectsSettingsStatus(state: CameraUiState, settings: CameraSettings) {
    val receipt = state.audioLevels?.effects.takeIf { state.audioMonitoringActive }
    val effective = state.effectiveSettings
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.audio_effect_title), color = VerifiedCyan, fontWeight = FontWeight.Bold)
        SettingsHelp(stringResource(R.string.audio_effect_help), tag = "audio-effects-help")
        if (settings.audioRecordingGain.enabled) {
            Text(stringResource(R.string.audio_effect_manual), Modifier.testTag("audio-effects-manual"), color = Amber, fontSize = 14.sp)
        }
        AudioEffectStatusRow("ns", R.string.audio_effect_ns, settings.noiseSuppressorEnabled,
            receipt?.noiseSuppressor, state.structuralSettingsFrozen && effective != null &&
                effective.noiseSuppressorEnabled != settings.noiseSuppressorEnabled)
        AudioEffectStatusRow("agc", R.string.audio_effect_agc, settings.automaticGainControlEnabled,
            receipt?.automaticGainControl, state.structuralSettingsFrozen && effective != null &&
                (effective.automaticGainControlEnabled != settings.automaticGainControlEnabled ||
                    effective.audioRecordingGain.enabled != settings.audioRecordingGain.enabled))
        AudioEffectStatusRow("aec", R.string.audio_effect_aec, settings.acousticEchoCancelerEnabled,
            receipt?.acousticEchoCanceler, state.structuralSettingsFrozen && effective != null &&
                effective.acousticEchoCancelerEnabled != settings.acousticEchoCancelerEnabled)
    }
}

@Composable
private fun AudioEffectStatusRow(tag: String, title: Int, requested: Boolean,
    observation: com.librestatic.opencinecam.camera.AudioEffectObservation?, pending: Boolean) {
    fun field(name: String) = Modifier.testTag("audio-effects-$tag-$name")
    val yes = stringResource(R.string.audio_effect_yes)
    val no = stringResource(R.string.audio_effect_no)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), field("title"), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.audio_effect_requested, if (requested) yes else no), field("requested"), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        if (observation == null) {
            Text(stringResource(R.string.audio_effect_unobserved), field("state"), color = Muted, fontSize = 14.sp)
        } else {
            Text(stringResource(R.string.audio_effect_receipt_requested, if (observation.requested) yes else no), field("receipt"), color = Muted, fontSize = 14.sp)
            val stateLabel = stringResource(when (observation.state) {
                com.librestatic.opencinecam.camera.AudioEffectState.UNKNOWN -> R.string.audio_effect_unknown
                com.librestatic.opencinecam.camera.AudioEffectState.UNAVAILABLE -> R.string.audio_effect_unavailable
                com.librestatic.opencinecam.camera.AudioEffectState.ENABLED -> R.string.audio_effect_enabled
                com.librestatic.opencinecam.camera.AudioEffectState.DISABLED -> R.string.audio_effect_disabled
                com.librestatic.opencinecam.camera.AudioEffectState.FAILED -> R.string.audio_effect_failed
            })
            Text(stringResource(R.string.audio_effect_observed, stateLabel), field("state"), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            val implementation = stringResource(when (observation.implementation) {
                com.librestatic.opencinecam.camera.AudioEffectImplementation.NONE -> R.string.audio_effect_none
                com.librestatic.opencinecam.camera.AudioEffectImplementation.PLATFORM -> R.string.audio_effect_platform
                com.librestatic.opencinecam.camera.AudioEffectImplementation.SOFTWARE -> R.string.audio_effect_software
            })
            Text(stringResource(R.string.audio_effect_implementation, implementation), field("implementation"), color = Muted, fontSize = 14.sp)
            val control = when (observation.hasControl) { true -> yes; false -> no; null -> stringResource(R.string.audio_effect_control_unknown) }
            Text(stringResource(R.string.audio_effect_control, control), field("control"), color = Muted, fontSize = 14.sp)
            if (observation.configurationFailed) Text(stringResource(R.string.audio_effect_configuration_failed), field("configuration-failed"), color = Amber, fontSize = 14.sp)
        }
        if (pending) Text(stringResource(R.string.audio_effect_pending), field("pending"), color = Amber, fontSize = 14.sp)
    }
}

/** Playback request is separate from both microphone ownership and recorded-sample gain. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AudioListeningSettingsControls(
    state: CameraUiState,
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
    onReconnect: () -> Unit,
) {
    val request = settings.audioListening
    val status = state.audioListeningStatus
    val enabledLabel = stringResource(R.string.audio_listening_enable)
    val volumeLabel = stringResource(R.string.audio_listening_volume, request.volumePercent)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.audio_listening_title), color = VerifiedCyan, fontWeight = FontWeight.Bold)
        SettingsHelp(stringResource(R.string.audio_listening_help), tag = "audio-listening-help")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(enabledLabel, Modifier.weight(1f).padding(end = 12.dp), color = MaterialTheme.colorScheme.onSurface)
            Switch(checked = request.enabled,
                onCheckedChange = { onSettingsChange(settings.copy(audioListening = request.copy(enabled = it))) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("audio-listening-enable").semantics { contentDescription = enabledLabel })
        }
        Text(volumeLabel, Modifier.testTag("audio-listening-volume-label"), color = MaterialTheme.colorScheme.onSurface)
        Slider(value = request.volumePercent.toFloat(), onValueChange = {
            onSettingsChange(settings.copy(audioListening = request.copy(volumePercent = it.roundToInt())))
        }, valueRange = 0f..100f, steps = 99, interactionSource = interaction,
            thumb = { androidx.compose.material3.SliderDefaults.Thumb(interactionSource = interaction,
                thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 52.dp)) },
            track = { androidx.compose.material3.SliderDefaults.Track(sliderState = it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("audio-listening-volume").semantics { contentDescription = volumeLabel })
        SettingsChips(stringResource(R.string.audio_listening_output_title), AudioListeningOutput.entries, request.output,
            label = { stringResource(when (it) {
                AudioListeningOutput.WIRED_USB -> R.string.audio_listening_wired
                AudioListeningOutput.BLUETOOTH -> R.string.audio_listening_bluetooth
                AudioListeningOutput.SPEAKER -> R.string.audio_listening_speaker
            }) },
            onSelect = { onSettingsChange(settings.copy(audioListening = request.copy(output = it), audioListeningOutputDeviceId = null)) },
            tag = { "audio-listening-output-${it.name}" })
        Text(stringResource(if (request.output == AudioListeningOutput.SPEAKER) R.string.audio_listening_speaker_warning else R.string.audio_listening_latency),
            Modifier.testTag("audio-listening-route-help"), color = Muted, fontSize = 14.sp)
        val devices = state.audioListeningOutputs.filter { it.output == request.output }
        val autoDevice = stringResource(R.string.audio_listening_auto)
        SettingsChips(stringResource(R.string.audio_listening_device_title), listOf<Int?>(null) + devices.map { it.id },
            settings.audioListeningOutputDeviceId,
            label = { id -> if (id == null) autoDevice else devices.firstOrNull { it.id == id }?.name ?: id.toString() },
            onSelect = { onSettingsChange(settings.copy(audioListeningOutputDeviceId = it)) },
            tag = { "audio-listening-device-${it ?: "auto"}" })
        Text(stringResource(R.string.audio_listening_requested_device,
            settings.audioListeningOutputDeviceId?.toString() ?: stringResource(R.string.audio_listening_auto)),
            Modifier.testTag("audio-listening-requested-device"), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        val phaseLabel = stringResource(when (status.phase) {
            AudioListeningPhase.DISABLED -> R.string.audio_listening_disabled
            AudioListeningPhase.NEEDS_CONNECT -> R.string.audio_listening_needs_connect
            AudioListeningPhase.WAITING_PCM -> R.string.audio_listening_waiting_pcm
            AudioListeningPhase.NO_OUTPUT -> R.string.audio_listening_no_output
            AudioListeningPhase.CONNECTING -> R.string.audio_listening_connecting
            AudioListeningPhase.ACTIVE -> R.string.audio_listening_active
            AudioListeningPhase.DISCONNECTED -> R.string.audio_listening_disconnected
            AudioListeningPhase.FAILED -> R.string.audio_listening_failed
            AudioListeningPhase.RETIRING -> R.string.audio_listening_retiring
        })
        Text(phaseLabel, Modifier.testTag("audio-listening-status"), color = if (status.phase == AudioListeningPhase.ACTIVE) VerifiedCyan else Muted)
        status.effectiveDeviceId?.let { id ->
            Text(stringResource(R.string.audio_listening_effective_device, status.deviceName ?: id.toString(), id),
                Modifier.testTag("audio-listening-effective-device"), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        }
        Text(stringResource(R.string.audio_listening_counters, status.acceptedFrames, status.droppedPackets),
            Modifier.testTag("audio-listening-counters"), color = Muted, fontSize = 14.sp)
        status.message?.let { Text(it, Modifier.testTag("audio-listening-message"), color = Muted, fontSize = 14.sp) }
        OutlinedButton(onClick = onReconnect,
            enabled = request.enabled && status.phase !in setOf(AudioListeningPhase.CONNECTING, AudioListeningPhase.RETIRING),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("audio-listening-connect")) {
            Text(stringResource(R.string.audio_listening_connect), Modifier.weight(1f).testTag("audio-listening-connect-label"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Intent controls only: meters are not proof of headphone playback or native gain application. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AudioRecordingGainSettings(
    state: CameraUiState,
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
) {
    val gain = settings.audioRecordingGain
    val manualLabel = stringResource(R.string.audio_gain_manual)
    val agcLabel = stringResource(R.string.audio_gain_agc_requested)
    val requestedLabel = stringResource(R.string.audio_gain_requested, gain.decibels)
    val gainInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.audio_gain_title), color = VerifiedCyan, fontWeight = FontWeight.Bold)
        SettingsHelp(stringResource(R.string.audio_gain_help), tag = "audio-gain-help")
        SettingsSwitchRow(manualLabel, gain.enabled,
            { onSettingsChange(settings.copy(audioRecordingGain = gain.copy(enabled = it))) }, tag = "audio-gain-manual")
        Text(requestedLabel,
            Modifier.testTag("audio-gain-value"), color = MaterialTheme.colorScheme.onSurface)
        Slider(value = gain.decibels.toFloat(), valueRange = -24f..24f, steps = 47,
            enabled = gain.enabled,
            onValueChange = { onSettingsChange(settings.copy(audioRecordingGain = gain.copy(decibels = it.roundToInt()))) },
            interactionSource = gainInteraction,
            // Material3's inner slider measures from the thumb (44 dp by default), not the
            // outer minimum. Enlarge the actual thumb/layout, rather than tagging empty padding.
            thumb = { androidx.compose.material3.SliderDefaults.Thumb(
                interactionSource = gainInteraction, enabled = gain.enabled,
                thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 52.dp)) },
            track = { androidx.compose.material3.SliderDefaults.Track(sliderState = it, enabled = gain.enabled) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("audio-gain-slider").semantics { contentDescription = requestedLabel })
        SettingsSwitchRow(agcLabel, settings.automaticGainControlEnabled,
            { onSettingsChange(settings.copy(automaticGainControlEnabled = it)) }, tag = "audio-gain-agc", enabled = !gain.enabled)
        Text(stringResource(if (gain.enabled) R.string.audio_gain_agc_suspended else R.string.audio_gain_agc_backend),
            Modifier.testTag("audio-gain-agc-help"), color = Muted, fontSize = 14.sp)
        val applied = state.audioLevels?.appliedRecordingGain.takeIf { state.audioMonitoringActive }
        Text(when {
            applied == null -> stringResource(R.string.audio_gain_no_receipt)
            applied.enabled -> stringResource(R.string.audio_gain_applied, applied.decibels)
            else -> stringResource(R.string.audio_gain_manual_not_applied)
        }, Modifier.testTag("audio-gain-applied"), color = if (applied == null) Muted else VerifiedCyan, fontSize = 14.sp)
        if (state.structuralSettingsFrozen && (state.effectiveSettings?.audioRecordingGain != gain ||
                state.effectiveSettings?.automaticGainControlEnabled != settings.automaticGainControlEnabled)) {
            Text(stringResource(R.string.audio_gain_pending), Modifier.testTag("audio-gain-pending"), color = Amber, fontSize = 14.sp)
        }
    }
}

@Composable
private fun AudioChoiceRow(
    title: String,
    choices: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    val labels = choices.toMap()
    SettingsChips(title, choices.map { it.first }, selected, label = { labels.getValue(it) }, onSelect = onSelected)
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
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onCheckedChange).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else Muted, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            summary?.let { Text(it, color = Muted, fontSize = 14.sp) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
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

@Composable
@ReadOnlyComposable
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

/** Formats an EV value (e.g. −1.33, +0.5, 0). Returns "0" for null/zero. */
internal fun formatEv(ev: Float?): String {
    val value = ev ?: 0f
    if (value == 0f) return "0"
    val sign = if (value > 0f) "+" else "−"
    val rounded = (abs(value) * 100).roundToInt() / 100f
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
        PanelHeader(stringResource(if (pullActive) R.string.panel_title_focus_pull else R.string.panel_title_focus), onClose)

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
            } else stringResource(R.string.focus_auto_fixed_lens),
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

            Text(stringResource(R.string.focus_marks_title), color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
                                Text(stringResource(R.string.focus_mark_empty), color = Color(0xFF444444), fontSize = 7.sp)
                            }
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.focus_marks_hint),
                color = Color(0xFF555555),
                fontSize = 8.sp,
            )

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))

            Text(stringResource(R.string.focus_pull_duration, settings.focusPullDurationMs / 1000.0), color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = settings.focusPullDurationMs.toFloat(),
                onValueChange = { v ->
                    onSettingsChanged(settings.copy(focusPullDurationMs = (v / 500).toInt() * 500L))
                },
                valueRange = 500f..10_000f,
                steps = 18,
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )

            Text(stringResource(R.string.focus_pull_curve), color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
            ) { Text(stringResource(R.string.focus_pull_cancel), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
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
        )
    }
}

private fun compositionGridModeTitle(mode: CompositionGridMode): Int = when (mode) {
    CompositionGridMode.THIRDS -> R.string.composition_grid_thirds
    CompositionGridMode.FOUR_BY_FOUR -> R.string.composition_grid_quarters
    CompositionGridMode.DIAGONAL -> R.string.composition_grid_diagonal
    CompositionGridMode.GOLDEN_RATIO -> R.string.composition_grid_golden
}

private fun rotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
