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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextAlign
import com.librestatic.opencinecam.camera.MonitoringOptions
import com.librestatic.opencinecam.camera.monitoringSampleFresh
import com.librestatic.opencinecam.camera.MonitoringSignalDomain
import com.librestatic.opencinecam.camera.monitoringPreviewScale
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.drawscope.clipPath
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
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.ui.unit.Dp

private val Graphite: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
private val Panel: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f).chromePanel()
private val Amber: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
private val VerifiedCyan: Color @Composable @ReadOnlyComposable get() = LocalCineColors.current.verified
private val RecordRed: Color @Composable @ReadOnlyComposable get() = LocalCineColors.current.record
private val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val OkGreen: Color @Composable @ReadOnlyComposable get() = LocalCineColors.current.ok

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
    SubjectStateForwarder(state) { foldDisplays?.updateCameraState(it) }
    DisposableEffect(foldDisplays, binder) {
        foldDisplays?.updatePreviewPort(binder?.subjectPreview)
        foldDisplays?.updateSelfRoleObserver { binder?.setSelfRecordingActive(it) }
        foldDisplays?.updateSyncMarkerSink { report -> binder?.recordSubjectSyncMarker(report) == true }
        onDispose {
            foldDisplays?.updatePreviewPort(null)
            foldDisplays?.updateSelfRoleObserver(null)
            foldDisplays?.updateSyncMarkerSink(null)
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
            AppShell(section, foldState.hinge, onSelect = {
                settingsPage = SettingsPage.MAIN
                section = it
            }) {
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
                            liveState = state,
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
            hinge = foldState.hinge,
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
        modifier = Modifier.fillMaxSize().background(Graphite).windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(32.dp),
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
        // The chrome picks its layout family from the safe drawing size, less the part of the
        // status bar a cutout leaves inside it (see statusBarClearance). The viewfinder pane is
        // decided from the same size here, so both always agree.
        val safeInsets = WindowInsets.safeDrawing
        val layoutDirection = LocalLayoutDirection.current
        val rightToLeft = layoutDirection == LayoutDirection.Rtl
        val extraTop = statusBarClearance()
        val safeWidthDp = with(density) {
            (fullWidthPx - safeInsets.getLeft(density, layoutDirection) - safeInsets.getRight(density, layoutDirection)).toDp()
        }
        val safeHeightDp = with(density) { (fullHeightPx - safeInsets.getTop(density) - safeInsets.getBottom(density)).toDp() } - extraTop
        val fullChrome = panes == null && !(state.selfRecordingActive && settings.subjectDisplay.selfMinimalControls)
        val family = captureLayoutFamily(safeWidthDp.value, safeHeightDp.value)
        // The stacked families fit the viewfinder between the top bar and the deck the chrome
        // measures, so no part of the frame hides behind the controls.
        val stacked = fullChrome && (family == CaptureLayoutFamily.COMPACT_PORTRAIT || family == CaptureLayoutFamily.STACKED)
        val stackedTopBar = if (family == CaptureLayoutFamily.COMPACT_PORTRAIT) SLIM_TOP_BAR_HEIGHT_DP.dp else STACKED_TOP_BAR_HEIGHT_DP.dp
        var stackedDeckHeightPx by remember { mutableIntStateOf(0) }
        // What an open pane or the scopes take from the frame's end or bottom edge, reported by the chrome.
        var frameReserve by remember { mutableStateOf(CaptureFrameReserve.None) }
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
        val reserveSpec: AnimationSpec<Float> = if (LocalReducedMotion.current) snap() else tween(FRAME_RESERVE_MS, easing = FastOutSlowInEasing)
        val reserveEnd by animateFloatAsState(frameReserve.end, reserveSpec, label = "frame-reserve-end")
        val reserveBottom by animateFloatAsState(frameReserve.bottom, reserveSpec, label = "frame-reserve-bottom")
        val previewPaneModifier = when {
            overlayChrome -> paneModifier(null)
            fullChrome && family == CaptureLayoutFamily.SIDE_RAILS -> paneModifier(null)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = extraTop, start = CAPTURE_RAIL_WIDTH_DP.dp, end = SIDE_COLUMN_WIDTH_DP.dp)
            fullChrome && family == CaptureLayoutFamily.INSPECTOR -> paneModifier(null)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = extraTop, start = CAPTURE_RAIL_WIDTH_DP.dp, end = INSPECTOR_WIDTH_DP.dp)
            stacked -> paneModifier(null)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = stackedTopBar + extraTop, bottom = with(density) { stackedDeckHeightPx.toDp() })
            else -> paneModifier(panes?.preview)
        }
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
            // The frame moves and grows by a render transform, not by layout. LOG and the GPU
            // viewfinder size their buffer from the layout, and a resized surface is reattached,
            // which reconfigures a recorder session mid-take. The overlay has no surface, so it is
            // laid out at the new place instead.
            val paneWidth = constraints.maxWidth.toFloat()
            val paneHeight = constraints.maxHeight.toFloat()
            val deckSpace = if (stacked && !overlayChrome) stackedDeckHeightPx.toFloat() else 0f
            val reserve = if (fullChrome && !overlayChrome) CaptureFrameReserve(reserveEnd, reserveBottom) else CaptureFrameReserve.None
            val restingFrame = fittedPreviewViewport(paneWidth, paneHeight, displayRatio)
            val targetFrame = reservedPreviewViewport(paneWidth, paneHeight, deckSpace, viewfinderExpansion, reserve, displayRatio, rightToLeft)
            val frameMoved = targetFrame != restingFrame && restingFrame.width > 0f
            val frameScale = if (frameMoved) targetFrame.width / restingFrame.width else 1f
            val shiftX = if (frameMoved) (targetFrame.left + targetFrame.width / 2f) - (restingFrame.left + restingFrame.width / 2f) else 0f
            val shiftY = if (frameMoved) (targetFrame.top + targetFrame.height / 2f) - (restingFrame.top + restingFrame.height / 2f) else 0f
            // Absolute: a plain offset mirrors x in a right-to-left layout, the graphics layer does not.
            val overlaySizeModifier = if (frameMoved) with(density) {
                Modifier.absoluteOffset { IntOffset(shiftX.roundToInt(), shiftY.roundToInt()) }
                    .requiredSize(targetFrame.width.toDp(), targetFrame.height.toDp())
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
                        translationX = shiftX
                        translationY = shiftY
                    }
                    .then(previewSizeModifier),
            )
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
                // The full chrome hosts the scopes beside the frame (a tray, a strip or the
                // inspector); a hinge split and the self-recording chrome leave them on it.
                drawScopesPanel = !fullChrome,
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
            onFrameReserve = { frameReserve = it },
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
        InterviewOperatorControl(settings.subjectDisplay, Modifier.align(Alignment.TopCenter))
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
            // Fall back to the stock 1080p30 geometry when nothing has previewed on this install yet,
            // or when the last configuration that previewed is the one that just failed to record.
            val restoreTarget = listOfNotNull(knownGood, KnownGoodCapture.safeDefault(state.selectedMode))
                .firstOrNull { it.differsFrom(settings, state.selectedMode) }
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
 * A monitoring chip: symbol plus a short visible label, so no tool has to be memorised by letter.
 * With [cycleState] null it is an on/off switch whose ON state carries a check mark as well as the
 * amber container; otherwise it is a button that advances a mode and shows that mode as its label.
 */
@Composable
private fun MonitorToggle(icon: CineIcon, label: String, description: String, enabled: Boolean, onClick: () -> Unit,
    cycleState: String? = null, modifier: Modifier = Modifier) {
    val on = enabled && cycleState == null
    val colors = MaterialTheme.colorScheme
    val content = if (on) colors.onPrimary else colors.onSurface
    Row(
        modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = description
                if (cycleState != null) stateDescription = cycleState
            }
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) colors.primary else colors.surfaceContainerHigh)
            .border(1.dp, if (on) colors.primary else colors.outline, RoundedCornerShape(8.dp))
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
    modifier: Modifier = Modifier.widthIn(max = 312.dp),
) {
    val histogramModeTitle = stringResource(if (histogramMode == HistogramMode.RGB) R.string.histogram_mode_rgb else R.string.histogram_mode_luma)
    val gridModeTitle = stringResource(compositionGridModeTitle(gridMode))
    // Pairs share a row: each tool sits beside its mode, and the level closes the grid. The cells
    // share the row's width, so the grid fills a pane as well as the recording HUD's 312 dp popup.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonitorToggle(CineIcon.ZEBRA, stringResource(R.string.monitor_zebra_short), stringResource(R.string.monitor_zebra), zebra, onToggleZebra, modifier = Modifier.weight(1f).fillMaxHeight())
            MonitorToggle(CineIcon.PEAKING, stringResource(R.string.monitor_peaking_short), stringResource(R.string.monitor_peaking), peaking, onTogglePeaking, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonitorToggle(CineIcon.HISTOGRAM, stringResource(R.string.monitor_histogram_short), stringResource(R.string.monitor_histogram), histogram, onToggleHistogram, modifier = Modifier.weight(1f).fillMaxHeight())
            MonitorToggle(CineIcon.HISTOGRAM_MODE, histogramModeTitle, stringResource(R.string.monitor_histogram_mode), true, onCycleHistogramMode,
                cycleState = histogramModeTitle, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonitorToggle(CineIcon.GRID, stringResource(R.string.monitor_grid_short), stringResource(R.string.monitor_grid), showGrid, onToggleGrid, modifier = Modifier.weight(1f).fillMaxHeight())
            MonitorToggle(CineIcon.GRID_MODE, gridModeTitle, stringResource(R.string.monitor_grid_mode), true, onCycleGridMode,
                cycleState = gridModeTitle, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonitorToggle(CineIcon.LEVEL, stringResource(R.string.monitor_horizon_short), stringResource(R.string.monitor_horizon), showHorizon, onToggleHorizon, modifier = Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun MonitoringOverlay(
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
    // The part of the overlay the window shows; a FILL viewfinder overflows its pane.
    var visibleBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    BoxWithConstraints(modifier.onGloballyPositioned { coordinates ->
        val parent = coordinates.parentLayoutCoordinates ?: return@onGloballyPositioned
        val origin = parent.localPositionOf(coordinates, Offset.Zero)
        visibleBounds = overlayVisibleRect(origin.x, origin.y, coordinates.size.width.toFloat(), coordinates.size.height.toFloat(),
            parent.size.width.toFloat(), parent.size.height.toFloat())
    }) {
        val displayDegrees = HorizonRollMath.surfaceRotationDegrees(displayRotationProvider())
        val sensorOrientation = state.descriptor?.sensorOrientation ?: 0
        val frontFacing = state.descriptor?.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        val overlayWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.coerceAtLeast(1) else 1
        val overlayHeight = if (constraints.hasBoundedHeight) constraints.maxHeight.coerceAtLeast(1) else 1
        val gpuScale = if (state.gpuViewfinder || state.selectedMode == CaptureMode.LOG)
            monitoringPreviewScale(sourceWidth, sourceHeight, overlayWidth, overlayHeight, sensorOrientation, displayDegrees, frontFacing, squeezeFactor)
            else 1f to 1f
        // Guides belong to the recorded picture; anything placed by a corner uses the shown part of it.
        val imageRect = monitoringImageRect(overlayWidth.toFloat(), overlayHeight.toFloat(), gpuScale.first, gpuScale.second)
        val shownRect = visibleImageRect(imageRect, visibleBounds)
        val peakingMask = state.focusPeakingMask.takeIf { showPeaking && analysisFresh }
        val peakingImage = rememberFocusPeakingImage(peakingMask, options.peakingColor.composeColor())
        ProfessionalScopeImage(state, options, analysisFresh, displayDegrees, sourceWidth, sourceHeight, squeezeFactor, Modifier.matchParentSize())
        val levelColor = VerifiedCyan; val tiltColor = Amber
        val levelMarkColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
        Canvas(Modifier.matchParentSize()) {
            val domain = state.monitoringScopes?.domain ?: MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR
            /** A point of the 16 × 9 analysis grid, normalised, on screen. */
            fun gridPoint(x: Float, y: Float): Offset = monitoringOverlayPoint(x, y, domain, sensorOrientation, displayDegrees,
                frontFacing, size.width, size.height, gpuScale.first, gpuScale.second)
            fun cellRect(index: Int): androidx.compose.ui.geometry.Rect {
                val a = gridPoint((index % 16) / 16f, (index / 16) / 9f)
                val b = gridPoint((index % 16 + 1) / 16f, (index / 16 + 1) / 9f)
                return androidx.compose.ui.geometry.Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
            }
            fun cellsPath(cells: List<Boolean>) = Path().apply { cells.forEachIndexed { index, on -> if (on) addRect(cellRect(index)) } }
            val alpha = options.opacityPercent / 100f
            if (showZebra && analysisFresh && state.zebraCells.any { it }) {
                // Diagonal stripes over the flagged cells read as zebra, not as solid blocks over the picture.
                val path = cellsPath(state.zebraCells)
                val bounds = path.getBounds()
                val color = options.zebraColor.composeColor().copy(alpha = alpha)
                clipPath(path) {
                    val spacing = 7.dp.toPx()
                    var x = bounds.left - bounds.height
                    while (x < bounds.right) {
                        drawLine(color, Offset(x, bounds.bottom), Offset(x + bounds.height, bounds.top), strokeWidth = 1.5.dp.toPx())
                        x += spacing
                    }
                }
            }
            if (peakingMask != null && peakingImage != null) {
                // Without the active array, assume the analysis stream already has the sensor's shape.
                val crop = state.descriptor?.sensorActiveArray
                drawFocusPeaking(peakingImage, focusPeakingPlacement(peakingMask.width, peakingMask.height, peakingMask.domain,
                    sourceWidth, sourceHeight, crop?.width() ?: peakingMask.width, crop?.height() ?: peakingMask.height,
                    sensorOrientation, displayDegrees, frontFacing, size.width, size.height, gpuScale.first, gpuScale.second),
                    imageRect, alpha)
            }
            if (showGrid) {
                val gridColor = Color.White.copy(alpha = .45f)
                val stroke = 1.dp.toPx()
                val gridSize = imageRect.size
                if (CompositionGridGeometry.isDiagonal(gridMode)) {
                    CompositionGridGeometry.diagonals(gridSize).forEach { (a, b) ->
                        drawLine(gridColor, a + imageRect.topLeft, b + imageRect.topLeft, strokeWidth = stroke)
                    }
                } else {
                    CompositionGridGeometry.verticalDivisions(gridMode).forEach { ratio ->
                        val x = imageRect.left + gridSize.width * ratio
                        drawLine(gridColor, Offset(x, imageRect.top), Offset(x, imageRect.bottom), strokeWidth = stroke)
                    }
                    CompositionGridGeometry.horizontalDivisions(gridMode).forEach { ratio ->
                        val y = imageRect.top + gridSize.height * ratio
                        drawLine(gridColor, Offset(imageRect.left, y), Offset(imageRect.right, y), strokeWidth = stroke)
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
                    val center = shownRect.center
                    val halfLength = shownRect.width * .3f
                    // Fixed marks show where the line sits when the camera is level.
                    val gap = 6.dp.toPx(); val mark = 14.dp.toPx()
                    drawLine(levelMarkColor, Offset(center.x - halfLength - gap - mark, center.y), Offset(center.x - halfLength - gap, center.y), strokeWidth = 2.dp.toPx())
                    drawLine(levelMarkColor, Offset(center.x + halfLength + gap, center.y), Offset(center.x + halfLength + gap + mark, center.y), strokeWidth = 2.dp.toPx())
                    val (a, b) = HorizonRollMath.horizonLineEnds(center, halfLength, degrees)
                    drawLine(color, a, b, strokeWidth = 2.dp.toPx())
                    drawCircle(color, radius = 4.dp.toPx(), center = center, style = Stroke(width = 1.dp.toPx()))
                }
            }
        }
        val density = LocalDensity.current
        if (showHorizon && horizonSensorAvailable?.available == false) {
            // No gravity or accelerometer sensor: say so where the level line would be drawn.
            Text(
                stringResource(R.string.horizon_level_unavailable),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.offset { IntOffset(shownRect.left.roundToInt(), shownRect.top.roundToInt()) }
                    .size(with(density) { shownRect.width.toDp() }, with(density) { shownRect.height.toDp() })
                    .wrapContentSize().background(Panel, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp).testTag("horizon-level-unavailable"),
            )
        }
        if (drawHistogram && showHistogram && analysisFresh && state.histogram.isNotEmpty()) {
            val rect = overlayHistogramRect(shownRect, density.density)
            HistogramGraph(state, options, histogramMode, Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() }))
        }
        if (drawScopesPanel) {
            // Fallback when the host has no tray or pane for the panel: float it at the end of the
            // shown picture, sized from it so the vectorscope stays whole in landscape.
            val (panelWidth, panelHeight) = with(density) {
                overlayScopesPanelSizeDp(shownRect.width.toDp().value, shownRect.height.toDp().value, scopesPanelEndPadding.value)
            }
            ProfessionalScopesPanel(state, options, analysisFresh, Modifier.offset {
                IntOffset((shownRect.right - scopesPanelEndPadding.toPx() - panelWidth.dp.toPx()).roundToInt(),
                    (shownRect.center.y - panelHeight.dp.toPx() / 2f).roundToInt())
            }.size(panelWidth.dp, panelHeight.dp))
        }
        // The overlay spans the whole screen: clear the top bar and the AE/AF lock toggles
        // (top end, from 62 dp) and keep right of the zoom column (top start).
        AnalysisSuspensionNotice(state, Modifier.align(Alignment.TopCenter).padding(top = 116.dp, start = 88.dp, end = 12.dp))
    }
}

/** Whether the latest scope analysis is recent enough to draw; refreshed four times a second. */
@Composable
internal fun rememberScopeAnalysisFresh(state: CameraUiState, options: MonitoringOptions): Boolean {
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

/** [tag] lets a second histogram (the scopes panel's) coexist with the capture instruments' one. */
@Composable
internal fun HistogramGraph(state: CameraUiState, options: MonitoringOptions, histogramMode: HistogramMode, modifier: Modifier = Modifier,
    tag: String = "histogram-graph") {
    Canvas(modifier.testTag(tag)) {
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

/** What the context pane holds: one control's dial (opened from its slot), the modes, or the monitoring toggles. */
private sealed interface CapturePane {
    data class Control(val dial: ControlDial) : CapturePane
    data object Modes : CapturePane
    data object Monitor : CapturePane
}

/** A pane the capture chrome opens with. Only the headless renders set it; the app always starts closed. */
internal enum class CaptureInitialPane { WHITE_BALANCE, FOCUS, MONITOR, MODES, MODE_SHEET }

private fun CaptureInitialPane.pane(): CapturePane? = when (this) {
    CaptureInitialPane.WHITE_BALANCE -> CapturePane.Control(ControlDial.WB)
    CaptureInitialPane.FOCUS -> CapturePane.Control(ControlDial.FOCUS)
    CaptureInitialPane.MONITOR -> CapturePane.Monitor
    CaptureInitialPane.MODES -> CapturePane.Modes
    CaptureInitialPane.MODE_SHEET -> null
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
    // Reports the visible deck height of the stacked layouts, which the viewfinder sits above.
    onStackedDeckHeight: (Int) -> Unit = {},
    // Reports what an open pane or the scopes take from the frame's edge; the frame moves out of their way.
    onFrameReserve: (CaptureFrameReserve) -> Unit = {},
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
    initialPane: CaptureInitialPane? = null,
) {
    // One pane at a time: a control's dial, the modes or the monitoring toggles.
    var pane by remember { mutableStateOf(initialPane?.pane()) }
    // Compact portrait shows the modes in a modal sheet rather than in the docked pane.
    var modeSheet by remember { mutableStateOf(initialPane == CaptureInitialPane.MODE_SHEET) }
    var scopesExpanded by rememberSaveable { mutableStateOf(false) }
    val operatorInput = LocalOperatorActions.current
    // The scopes stay switched on in Settings; H and the panel's close key only hide them here.
    // The operator actions own the state when present, so the F-keys and volume keys agree with it.
    val localScopes = rememberSaveable(saver = CaptureScopeVisibility.Saver) { CaptureScopeVisibility() }
    val scopeVisibility = operatorInput?.scopes ?: localScopes
    val recording = state.phase == CameraUiPhase.RECORDING
    var manualReveal by remember { mutableStateOf(false) }
    var tapPoint by remember { mutableStateOf<Offset?>(null) }
    var pinchStartRatio by remember { mutableFloatStateOf(-1f) }
    var controlDeckHeightPx by remember { mutableIntStateOf(0) }
    var dockedPaneHeightPx by remember { mutableIntStateOf(0) }
    // The lock toggles (and in the stacked layouts the F-keys under them) at the frame's top end.
    var topEndClusterWidthPx by remember { mutableIntStateOf(0) }
    // While recording, the full console auto-hides to keep a clean viewfinder. A tap reveals
    // it again; it re-hides after a short idle period unless a pane is open in it.
    LaunchedEffect(recording, manualReveal, pane) {
        if (recording && manualReveal && pane == null) {
            delay(4_000)
            manualReveal = false
        }
    }
    LaunchedEffect(recording) {
        if (recording) modeSheet = false
    }
    LaunchedEffect(state.tapFocusState) {
        if (state.tapFocusState == TapFocusState.IDLE) tapPoint = null
    }
    fun togglePane(target: CapturePane) {
        pane = if (pane == target) null else target
        modeSheet = false
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
    // A cutout can leave the safe area shorter than the status bar; the top bar starts below both.
    val extraTop = statusBarClearance()
    val reducedMotion = LocalReducedMotion.current

    CompositionLocalProvider(LocalModeSelection provides modeSelection) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = extraTop),
    ) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val rightToLeft = layoutDirection == LayoutDirection.Rtl
        fun Dp.px(): Float = with(density) { toPx() }
        // A hinge split shows the picture on the other pane, so its controls pane keeps a deck and
        // has no frame to make room for.
        val hinge = !previewGesturesEnabled
        val family = if (hinge) hingePaneLayoutFamily(maxWidth.value, maxHeight.value)
            else captureLayoutFamily(maxWidth.value, maxHeight.value)
        val compact = family == CaptureLayoutFamily.COMPACT_PORTRAIT
        val stacked = family == CaptureLayoutFamily.STACKED
        val stackedFamily = compact || stacked
        val sideRails = family == CaptureLayoutFamily.SIDE_RAILS
        val inspector = family == CaptureLayoutFamily.INSPECTOR
        // The inspector has the width to stay up during a take; the other layouts hide their console.
        val chromeVisible = !recording || manualReveal || inspector
        val paneShown = pane != null && chromeVisible
        val editing = paneShown || modeSheet
        DisposableEffect(operatorInput, editing) {
            operatorInput?.setEditing?.invoke(editing)
            onDispose { operatorInput?.setEditing?.invoke(false) }
        }
        BackHandler(enabled = editing) {
            pane = null
            modeSheet = false
        }
        // Compact portrait (phones, a foldable's cover screen) spends as little height as possible
        // on chrome: a slim status bar, the F-keys over the viewfinder edge.
        val topBar = if (compact) SLIM_TOP_BAR_HEIGHT_DP.dp else STACKED_TOP_BAR_HEIGHT_DP.dp
        val topBarPx = topBar.px()
        val deckHeight = with(density) { controlDeckHeightPx.toDp() }
        // The deck slides away while recording. This height stays the deck's shown height, which
        // is where the viewfinder rests; it then grows into the freed space (viewfinderExpansion).
        // It is frozen during a take: the recording deck is taller (it adds Pause), and following it
        // would resize the viewfinder surface, which reattaches the preview mid-take.
        var stableDeckHeightPx by remember { mutableIntStateOf(0) }
        LaunchedEffect(controlDeckHeightPx, recording) {
            if (controlDeckHeightPx > 0 && !recording) stableDeckHeightPx = controlDeckHeightPx
        }
        LaunchedEffect(stackedFamily, stableDeckHeightPx, overlayViewfinderScale) {
            onStackedDeckHeight(if (stackedFamily && overlayViewfinderScale == null) stableDeckHeightPx else 0)
        }
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val ratio = previewAspectRatio
        val overlay = overlayViewfinderScale != null && !hinge
        val monitoring = settings.monitoring
        val panelScopesEnabled = monitoring.waveformEnabled || monitoring.vectorscopeEnabled || monitoring.falseColorEnabled
        // H hides and shows every scope, the histogram among them.
        val scopesEnabled = panelScopesEnabled || histogram
        // A hinge split leaves the scopes panel on its preview pane, where it cannot be hidden.
        DisposableEffect(scopeVisibility, hinge) {
            scopeVisibility.hideable = !hinge
            onDispose { scopeVisibility.hideable = false }
        }
        // With nothing switched on there is nothing to hide: the next scope switched on shows.
        LaunchedEffect(scopesEnabled) { if (!scopesEnabled) scopeVisibility.show() }
        val scopesHidden = scopeVisibility.concealed
        val scopesShown = panelScopesEnabled && !scopesHidden && !hinge
        // The histogram and its toggles follow H as well.
        val histogramShown = histogram && !scopesHidden
        val toggleHistogram: () -> Unit = { scopeVisibility.press(OperatorAction.HISTOGRAM, histogram, onToggleHistogram) }
        val stackedPaneHeight = (height - topBarPx - stableDeckHeightPx).coerceAtLeast(0f)
        val sidePaneWidth = stackedSidePaneWidth(maxWidth.value).dp
        val dockCap = minOf(
            maxHeight * DOCKED_SHEET_MAX_FRACTION,
            if (stacked) 400.dp else maxHeight,
            maxHeight - deckHeight - topBar - 8.dp,
        ).coerceAtLeast(160.dp)
        // A stacked window docks a pane, and the scopes, beside the frame or under it, by which
        // leaves the larger frame.
        val paneDock = if (stacked && !hinge) stackedPaneDock(width, stackedPaneHeight, sidePaneWidth.px(), dockCap.px(), ratio)
            else PaneDock.BOTTOM
        val scopeDock = if (stacked && !hinge) {
            stackedPaneDock(width, stackedPaneHeight, SCOPE_STRIP_WIDTH_DP.dp.px(), SCOPE_TRAY_HEIGHT_DP.dp.px(), ratio)
        } else PaneDock.BOTTOM
        val dockedBottomPane = paneShown && stackedFamily && paneDock == PaneDock.BOTTOM
        val dockedEndPane = paneShown && stacked && paneDock == PaneDock.END
        val scopeStrip = scopesShown && !paneShown && (sideRails || (stacked && scopeDock == PaneDock.END))
        val scopeTray = scopesShown && !paneShown && stackedFamily && !scopeStrip
        // ⤢ grows the scopes in place; the dock above is chosen at the resting size, so it never flips.
        val scopeStripWidth = scopeStripWidthDp(scopesExpanded, maxWidth.value).dp
        val scopeTrayHeight = scopeTrayHeightDp(scopesExpanded, with(density) { stackedPaneHeight.toDp() }.value).dp
        val targetReserve = when {
            overlay || hinge || inspector -> CaptureFrameReserve.None
            paneShown && sideRails -> CaptureFrameReserve(end = (SIDE_PANE_WIDTH_DP - SIDE_COLUMN_WIDTH_DP).dp.px())
            dockedEndPane -> CaptureFrameReserve(end = sidePaneWidth.px())
            dockedBottomPane -> CaptureFrameReserve(bottom = dockedPaneHeightPx.toFloat())
            scopeStrip -> CaptureFrameReserve(end = scopeStripWidth.px())
            scopeTray -> CaptureFrameReserve(bottom = scopeTrayHeight.px())
            else -> CaptureFrameReserve.None
        }
        // The viewfinder animates the same reserve; both follow one target, so the frame and its
        // gesture area stay in step.
        val reserveSpec: AnimationSpec<Float> = if (reducedMotion) snap() else tween(FRAME_RESERVE_MS, easing = FastOutSlowInEasing)
        val reserveEnd by animateFloatAsState(targetReserve.end, reserveSpec, label = "chrome-reserve-end")
        val reserveBottom by animateFloatAsState(targetReserve.bottom, reserveSpec, label = "chrome-reserve-bottom")
        LaunchedEffect(targetReserve) { onFrameReserve(targetReserve) }
        val reserve = CaptureFrameReserve(reserveEnd, reserveBottom)
        val startRailPx = CAPTURE_RAIL_WIDTH_DP.dp.px()
        val endColumnPx = (if (inspector) INSPECTOR_WIDTH_DP else SIDE_COLUMN_WIDTH_DP).dp.px()
        val safeInsets = WindowInsets.safeDrawing
        val previewViewport = when {
            overlayViewfinderScale != null && !hinge -> {
                // The viewfinder spans the window around this inset box; map it into chrome coordinates.
                val insetLeft = safeInsets.getLeft(density, layoutDirection).toFloat()
                val insetTop = safeInsets.getTop(density) + extraTop.px()
                val window = overlayPreviewViewport(
                    width + insetLeft + safeInsets.getRight(density, layoutDirection),
                    height + insetTop + safeInsets.getBottom(density),
                    ratio,
                    overlayViewfinderScale,
                )
                window.copy(left = window.left - insetLeft, top = window.top - insetTop)
            }
            // No picture here: the instruments get the band between the top bar and the deck.
            hinge -> PreviewViewport(0f, topBarPx, width,
                (height - topBarPx - if (chromeVisible) controlDeckHeightPx.toFloat() else 0f).coerceAtLeast(0f))
            sideRails || inspector -> {
                val inner = reservedPreviewViewport((width - startRailPx - endColumnPx).coerceAtLeast(0f), height, 0f, 0f, reserve, ratio, rightToLeft)
                inner.copy(left = inner.left + if (rightToLeft) endColumnPx else startRailPx)
            }
            else -> {
                val inner = reservedPreviewViewport(width, stackedPaneHeight, stableDeckHeightPx.toFloat(), viewfinderExpansion, reserve, ratio, rightToLeft)
                inner.copy(top = inner.top + topBarPx)
            }
        }
        val previewWidth = previewViewport.width
        val previewHeight = previewViewport.height
        val previewLeft = previewViewport.left
        val previewTop = previewViewport.top
        // SurfaceView owns a native surface, so keep an explicit Compose hit target over it.
        // This is the first child: controls composed later remain the winning hit targets.
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
                .pointerInput(previewGesturesEnabled, state.captureControlsLocked, recording, ratio, settings.tapExposureMeteringEnabled, state.phase,
                    previewLeft, previewTop, previewWidth, previewHeight) {
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

        // What the end edge carries beside the frame: the side column or pane, and the scope strip.
        val endOccupied = when {
            sideRails -> (if (paneShown) SIDE_PANE_WIDTH_DP else SIDE_COLUMN_WIDTH_DP).dp +
                if (scopeStrip) scopeStripWidth else 0.dp
            inspector -> INSPECTOR_WIDTH_DP.dp
            dockedEndPane -> sidePaneWidth
            scopeStrip -> scopeStripWidth
            else -> 0.dp
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
                    .padding(end = endOccupied + 8.dp)
                    .width(28.dp)
                    .height(120.dp),
            )
        }

        if (chromeVisible) {
            if (stackedFamily) {
                // The F-keys stand over the frame's end edge under the lock toggles. They stop above
                // the zoom rocker at mid-height and above whatever docks on the deck under them (a
                // pane that reaches the end edge, or the scope tray), and scroll rather than run into it.
                val paneEdgeGap = if (compact) 0.dp else ((maxWidth - DOCKED_SHEET_MAX_WIDTH_DP.dp) / 2).coerceAtLeast(0.dp)
                val dockedUnderKeys = when {
                    dockedBottomPane && paneEdgeGap < CAPTURE_RAIL_WIDTH_DP.dp -> with(density) { dockedPaneHeightPx.toDp() }
                    scopeTray -> scopeTrayHeight
                    else -> 0.dp
                }
                val keyColumnFloor = maxHeight - deckHeight - dockedUnderKeys - 14.dp
                val keyColumnMax = (if (state.zoomSupported) minOf(maxHeight / 2 - 60.dp - 12.dp, keyColumnFloor) else keyColumnFloor) - topBar
                // Whole 48 dp keys only (6 dp apart, under the 48 dp lock row): a key cut by the
                // column's edge would read as one hidden under the pane.
                val keyPitch = 48.dp + 6.dp
                val wholeKeys = ((keyColumnMax - 48.dp) / keyPitch).toInt().coerceAtLeast(0)
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = endOccupied + 8.dp, top = topBar + 6.dp)
                        .onSizeChanged { topEndClusterWidthPx = it.width }
                        .heightIn(max = 48.dp + keyPitch * wholeKeys)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LockToggles(state = state, binder = binder, afLockBehavior = settings.afLockBehavior)
                    // A hinge split has no picture under its chrome: its F-keys go in the deck.
                    if (!hinge) OperatorButtonColumn(state, settings)
                }
                CaptureTopBar(
                    state = state,
                    binder = binder,
                    onOpenMedia = onOpenMedia,
                    onOpenSettings = onOpenSettings,
                    settings = settings,
                    onSettingsChanged = onSettingsChanged,
                    modifier = Modifier.align(Alignment.TopCenter),
                    showThumbnail = !compact,
                    height = topBar,
                )
            } else LockToggles(
                state = state,
                binder = binder,
                afLockBehavior = settings.afLockBehavior,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = endOccupied + 8.dp, top = 8.dp)
                    .onSizeChanged { topEndClusterWidthPx = it.width },
            )
        }

        // Every viewfinder instrument lives in one measured stack on the visible frame, so the LOG
        // badge, zoom, microphone meter and histogram never land on each other, on the black bands
        // around the picture, or under the chrome.
        val recordingHudTop = when {
            !chromeVisible || sideRails || inspector -> 0.dp
            state.selectedMode == CaptureMode.LOG -> topBar + 26.dp
            else -> topBar
        }
        // The recording HUD grows with its meter and monitor toggles; measure it instead of guessing.
        var recordingHudHeightPx by remember { mutableIntStateOf(0) }
        val recordingHudHeight = with(density) { recordingHudHeightPx.toDp() }
        // The end inset clears the zoom rocker, and in the stacked layouts the F-key column too.
        val instrumentStartInset = 12.dp.px()
        val instrumentEndInset = when {
            hinge -> 12.dp
            stackedFamily -> 64.dp
            else -> 44.dp
        }.px()
        val startChrome = if (sideRails || inspector) startRailPx else 0f
        val endChrome = endOccupied.px()
        val leftBound = maxOf(previewLeft, 0f, if (rightToLeft) endChrome else startChrome)
        val rightBound = minOf(previewLeft + previewWidth, width, width - if (rightToLeft) startChrome else endChrome)
        val topBound = maxOf(previewTop, 0f, if (stackedFamily && chromeVisible) topBarPx else 0f)
        val bottomBound = minOf(
            previewTop + previewHeight,
            (if (stackedFamily && chromeVisible) height - controlDeckHeightPx else height) - when {
                dockedBottomPane -> dockedPaneHeightPx.toFloat()
                scopeTray -> scopeTrayHeight.px()
                else -> 0f
            },
        )
        val instrumentLeft = leftBound + if (rightToLeft) instrumentEndInset else instrumentStartInset
        val instrumentRight = rightBound - if (rightToLeft) instrumentStartInset else instrumentEndInset
        val instrumentTop = maxOf(
            topBound + 8.dp.px(),
            if (recording) (recordingHudTop + maxOf(recordingHudHeight, 52.dp) + 8.dp).px() else 0f,
        )
        val instrumentBottom = bottomBound - 8.dp.px()
        InstrumentStack(
            state = state,
            binder = binder,
            settings = settings,
            chromeVisible = chromeVisible,
            recording = recording,
            histogram = histogramShown,
            histogramMode = histogramMode,
            horizontal = if (hinge) landscape else rightBound - leftBound > bottomBound - topBound,
            // A hinge split gives the chrome its own pane with no picture behind it: let the
            // histogram use the band between the instruments and the deck instead of a thumbnail.
            dedicatedPane = hinge,
            // Absolute: the frame is measured left to right whatever the layout direction.
            modifier = Modifier
                .align(AbsoluteAlignment.TopLeft)
                .absoluteOffset { IntOffset(instrumentLeft.roundToInt(), instrumentTop.roundToInt()) }
                .size(
                    with(density) { (instrumentRight - instrumentLeft).coerceAtLeast(0f).toDp() },
                    with(density) { (instrumentBottom - instrumentTop).coerceAtLeast(0f).toDp() },
                ),
        )

        val displayedMode = modeSelection.displayed
        val modeName = modeLabel(displayedMode)
        val slots = captureSlotModels(state, displayedMode)
        val activeSlot = (pane as? CapturePane.Control)?.dial?.slot()
        val onSlot: (CaptureSlot) -> Unit = { slot -> togglePane(CapturePane.Control(slot.dial())) }
        val openModeSheet: () -> Unit = {
            modeSheet = true
            pane = null
        }
        val unlock: () -> Unit = { binder?.performOperatorAction(OperatorAction.CONTROL_LOCK) }
        val captureAction = operatorInput?.capture ?: rememberCaptureAction(state, binder, settings)
        val performOperator: (OperatorAction) -> Unit = { action ->
            operatorInput?.perform?.invoke(action) ?: binder?.performOperatorAction(action)
        }
        // R, F, Z, P, G, H, L and Esc with a hardware keyboard: the same actions as the controls.
        ShortcutHandler { action ->
            when (captureShortcutCommand(action, pane != null || modeSheet, operatorActionAvailable(OperatorAction.VIEW_ASSIST, state), scopesEnabled)) {
                CaptureShortcutCommand.CAPTURE -> captureControlEnabled(state).also { if (it) captureAction() }
                CaptureShortcutCommand.TOGGLE_FOCUS ->
                    slots.any { it.slot == CaptureSlot.FOCUS && it.unavailableReason == null }.also { available ->
                        if (available) {
                            togglePane(CapturePane.Control(ControlDial.FOCUS))
                            if (recording) manualReveal = true
                        }
                    }
                CaptureShortcutCommand.ZEBRA -> { onToggleZebra(); true }
                CaptureShortcutCommand.PEAKING -> { onTogglePeaking(); true }
                CaptureShortcutCommand.GRID -> { onToggleGrid(); true }
                // A hinge split shows the scopes on its preview pane, which H does not hide.
                CaptureShortcutCommand.TOGGLE_SCOPES -> !hinge && run { scopeVisibility.toggle(); true }
                CaptureShortcutCommand.ENABLE_WAVEFORM -> {
                    performOperator(OperatorAction.WAVEFORM)
                    scopeVisibility.show()
                    scopeVisibility.tab = ScopeTab.WAVEFORM
                    true
                }
                CaptureShortcutCommand.VIEW_ASSIST -> { performOperator(OperatorAction.VIEW_ASSIST); true }
                CaptureShortcutCommand.CLOSE_PANE -> {
                    pane = null
                    modeSheet = false
                    true
                }
                CaptureShortcutCommand.CONSUME -> true
                null -> false
            }
        }
        val presets: @Composable () -> Unit = {
            PresetQuickAccess(state, settings, binder?.let { owner -> { preset -> owner.applyPreset(preset) } })
        }
        val progressRows: @Composable () -> Unit = {
            BurstCaptureProgress(state) { binder?.cancelBurstCapture() }
            BracketCaptureProgress(state) { binder?.cancelBracketCapture() }
            AccumulationCaptureProgress(state, { binder?.finishAccumulationCapture() }, { binder?.cancelAccumulationCapture() })
        }
        val scopeFresh = rememberScopeAnalysisFresh(state, monitoring)
        val scopes: @Composable (Modifier) -> Unit = { modifier ->
            // No histogram tab: the histogram keeps its own place in the instrument stack.
            ProfessionalScopesPanel(state, monitoring, scopeFresh, modifier, expanded = scopesExpanded,
                onExpandedChange = { scopesExpanded = it }, onClose = { scopeVisibility.hide() },
                tab = scopeVisibility.tab, onTabChange = { scopeVisibility.tab = it })
        }
        // The modes, with the selected mode's resolution under them (RES lives here, not in the slots).
        val modesContent: @Composable (onClose: (() -> Unit)?) -> Unit = { onClose ->
            val choices = visibleCaptureModes(state.modeGates, displayedMode).map { mode ->
                val gate = state.modeGates.getValue(mode)
                CaptureModeChoice(
                    mode = mode,
                    label = modeLabel(mode),
                    gateLabel = if (gate != ModeGateState.AVAILABLE) gateLabel(gate) else null,
                    gateColor = gateColor(gate),
                    enabled = CameraUiState.isModeSelectable(gate),
                    selected = mode == displayedMode,
                )
            }
            CaptureModeContent(
                choices = choices,
                recording = recording,
                onSelect = modeSelection.select,
                onClose = onClose,
                resolution = if (state.descriptor != null && state.selectedMode in CameraUiState.resolutionProfileModes) {
                    {
                        ManualControlDial(ControlDial.RESOLUTION, state, binder, settings, onSettingsChanged, showHeader = false) {
                            pane = null
                            modeSheet = false
                        }
                    }
                } else null,
            )
        }
        val paneContent: @Composable ColumnScope.() -> Unit = {
            when (val current = pane) {
                is CapturePane.Control -> ManualControlDial(current.dial, state, binder, settings, onSettingsChanged) { pane = null }
                CapturePane.Modes -> modesContent { pane = null }
                CapturePane.Monitor -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapturePaneHeader(stringResource(R.string.monitor_title), { pane = null })
                    MonitoringToggleGrid(
                        zebra, peaking, histogramShown, histogramMode, showGrid, gridMode, showHorizon,
                        onToggleZebra, onTogglePeaking, toggleHistogram, onCycleHistogramMode,
                        onToggleGrid, onCycleGridMode, onToggleHorizon,
                        Modifier.fillMaxWidth(),
                    )
                    if (scopesEnabled && !hinge) MonitorToggle(
                        CineIcon.MONITORING,
                        stringResource(R.string.capture_scopes_short),
                        stringResource(R.string.capture_scopes_toggle),
                        !scopesHidden,
                        { scopeVisibility.toggle() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                null -> Unit
            }
        }

        if (stackedFamily) AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { controlDeckHeightPx = it.height },
        ) {
            if (compact) Column(
                Modifier.fillMaxWidth().background(Panel).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.captureControlsLocked) CaptureLockBanner(unlock, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                if (hinge) OperatorButtonRow(state, settings)
                presets()
                CaptureSlotStrip(slots, activeSlot, onSlot, Modifier.padding(horizontal = 8.dp))
                if (settings.modeSelectorStyle == ModeSelectorStyle.DIAL) ModeDial(state, binder, onOpenSheet = openModeSheet)
                else CaptureModeButton(modeName, openModeSheet)
                PortraitCaptureTransport(
                    state, binder, settings, onOpenMedia = onOpenMedia, onShowMonitoring = { togglePane(CapturePane.Monitor) },
                    // Stacked translucent panels would compound into a darker band under the shutter.
                    panelBackground = LocalChromeOpacity.current == null,
                )
            } else Column(
                Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.captureControlsLocked) CaptureLockBanner(unlock, Modifier.fillMaxWidth())
                if (hinge) OperatorButtonRow(state, settings)
                presets()
                CaptureSlotStrip(slots, activeSlot, onSlot, Modifier.align(Alignment.CenterHorizontally).widthIn(max = 560.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CaptureModeButton(modeName, { togglePane(CapturePane.Modes) }, expanded = pane == CapturePane.Modes)
                    TopAction(CineIcon.MONITORING, stringResource(R.string.monitoring_tools)) { togglePane(CapturePane.Monitor) }
                    Box(Modifier.weight(1f)) { StatusInfoBar(state, settings) }
                    RecordingPauseButton(state) { requestRecordingPause(state, binder, it) }
                    CaptureButton(state, binder, settings, 64.dp, labelBeside = true)
                }
                progressRows()
            }
        }

        if (sideRails || inspector) AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight(),
        ) {
            // The start rail: the top-bar actions, then the F-keys as one column of icon keys.
            Column(
                Modifier
                    .width(CAPTURE_RAIL_WIDTH_DP.dp)
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
                    showStatus = false,
                )
                OperatorButtonColumn(state, settings)
            }
        }

        if (sideRails) AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        ) {
            if (paneShown) Column(Modifier.width(SIDE_PANE_WIDTH_DP.dp).fillMaxHeight().background(Panel)) {
                CaptureContextPane(ContextPanePlacement.SIDE, Modifier.weight(1f).fillMaxWidth(), paneContent)
                // The shutter stays under the thumb while a pane is open.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    RecordingPauseButton(state) { requestRecordingPause(state, binder, it) }
                    CaptureButton(state, binder, settings, 56.dp, labelBeside = true)
                }
            } else Column(
                Modifier
                    .width(SIDE_COLUMN_WIDTH_DP.dp)
                    .fillMaxHeight()
                    .background(Panel)
                    .padding(6.dp)
                    .testTag("capture-end-rail"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.captureControlsLocked) CaptureLockBanner(unlock, Modifier.fillMaxWidth())
                // Two columns: the five slots, and beside them the mode, the status and the shutter,
                // which is held at the bottom so it never scrolls away.
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CaptureSlotColumn(slots, activeSlot, onSlot, Modifier.width(96.dp).fillMaxHeight())
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CaptureModeButton(modeName, { togglePane(CapturePane.Modes) }, Modifier.fillMaxWidth())
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            CaptureStatusLine(state, Modifier.fillMaxWidth(), maxLines = 3, textAlign = TextAlign.Center)
                            if (!recording) {
                                ThermalHudChip()
                                OutOfFrameHudChip(state)
                            }
                            CaptureStatus(state)
                            presets()
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            MediaThumbnailAction(state.lastSavedUri, onOpenMedia)
                            TopAction(CineIcon.MONITORING, stringResource(R.string.monitoring_tools)) { togglePane(CapturePane.Monitor) }
                        }
                        RecordingPauseButton(state) { requestRecordingPause(state, binder, it) }
                        CaptureButton(state, binder, settings, 64.dp)
                    }
                }
                StatusInfoBar(state, settings)
                progressRows()
            }
        }

        if (inspector) Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(INSPECTOR_WIDTH_DP.dp)
                .fillMaxHeight()
                .background(Panel)
                .testTag("capture-inspector"),
        ) {
            if (paneShown) CaptureContextPane(ContextPanePlacement.SIDE, Modifier.weight(1f).fillMaxWidth(), paneContent)
            else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewport = maxHeight
                var aboveScopesPx by remember { mutableIntStateOf(0) }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // The recording format and time left stay above the scopes, never scrolled away under them.
                    Column(
                        Modifier.fillMaxWidth().onSizeChanged { aboveScopesPx = it.height },
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CaptureStatusLine(state, Modifier.fillMaxWidth(), maxLines = 2)
                        if (!recording) {
                            ThermalHudChip()
                            OutOfFrameHudChip(state)
                        }
                        CaptureStatus(state)
                        if (state.captureControlsLocked) CaptureLockBanner(unlock, Modifier.fillMaxWidth())
                        CaptureModeButton(modeName, { togglePane(CapturePane.Modes) }, Modifier.fillMaxWidth())
                        CaptureSlotRows(slots, activeSlot, onSlot)
                        presets()
                        StatusInfoBar(state, settings)
                    }
                    // The panel scrolls its own scopes, so it needs a bounded height inside this column:
                    // the room the rest leaves, but never so little a trace stops reading; then the column scrolls.
                    if (scopesShown) {
                        val room = viewport - 34.dp - with(density) { aboveScopesPx.toDp() }
                        Box(
                            Modifier.fillMaxWidth().heightIn(max = inspectorScopesMaxHeightDp(scopesExpanded, room.value).dp),
                            contentAlignment = Alignment.Center,
                        ) { scopes(Modifier) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MediaThumbnailAction(state.lastSavedUri, onOpenMedia, size = 56.dp)
                TopAction(CineIcon.MONITORING, stringResource(R.string.monitoring_tools)) { togglePane(CapturePane.Monitor) }
                Spacer(Modifier.weight(1f))
                RecordingPauseButton(state) { requestRecordingPause(state, binder, it) }
                CaptureButton(state, binder, settings, 72.dp, labelBeside = true)
            }
            Column(Modifier.padding(horizontal = 12.dp)) { progressRows() }
        }

        // The scopes beside the frame (a strip) or under it (a tray), never over the picture.
        if (scopeStrip) Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(
                    end = if (sideRails) SIDE_COLUMN_WIDTH_DP.dp else 0.dp,
                    top = if (sideRails) 0.dp else topBar,
                    bottom = if (stackedFamily && chromeVisible) deckHeight else 0.dp,
                )
                .width(scopeStripWidth)
                .fillMaxHeight()
                .background(Panel)
                .testTag("capture-scope-strip"),
            contentAlignment = Alignment.Center,
        ) { scopes(Modifier) }
        if (scopeTray) Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (chromeVisible) deckHeight else 0.dp)
                .fillMaxWidth()
                .height(scopeTrayHeight)
                .background(Panel)
                .testTag("capture-scope-tray"),
            contentAlignment = Alignment.Center,
        ) { scopes(Modifier) }

        // A pane docks over the deck's top edge or beside the frame; the frame moves out of its way.
        if (dockedBottomPane) CaptureContextPane(
            ContextPanePlacement.BOTTOM_SHEET,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = deckHeight)
                .then(if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = DOCKED_SHEET_MAX_WIDTH_DP.dp).fillMaxWidth())
                .heightIn(max = dockCap)
                .onSizeChanged { dockedPaneHeightPx = it.height },
            paneContent,
        )
        if (dockedEndPane) CaptureContextPane(
            ContextPanePlacement.SIDE,
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = topBar, bottom = deckHeight)
                .width(sidePaneWidth)
                .fillMaxHeight(),
            paneContent,
        )

        if (recording) {
            RecordingOverlay(
                state = state,
                binder = binder,
                showStop = !chromeVisible,
                zebra = zebra,
                peaking = peaking,
                histogram = histogramShown,
                histogramMode = histogramMode,
                showGrid = showGrid,
                gridMode = gridMode,
                showHorizon = showHorizon,
                onToggleZebra = onToggleZebra,
                onTogglePeaking = onTogglePeaking,
                onToggleHistogram = toggleHistogram,
                onCycleHistogramMode = onCycleHistogramMode,
                onToggleGrid = onToggleGrid,
                onCycleGridMode = onCycleGridMode,
                onToggleHorizon = onToggleHorizon,
                // While the chrome is up its lock toggles share the HUD's top band: stop short of them.
                modifier = Modifier.align(Alignment.TopCenter).padding(top = recordingHudTop)
                    .padding(
                        start = if (sideRails || inspector) CAPTURE_RAIL_WIDTH_DP.dp else 0.dp,
                        end = endOccupied + if (chromeVisible) with(density) { topEndClusterWidthPx.toDp() } + 8.dp else 0.dp,
                    )
                    .onSizeChanged { recordingHudHeightPx = it.height },
            )
        }

        // The stacked decks keep a fixed height, so the viewfinder above them never reframes: their
        // notices float just above the deck (and the scope tray) instead of adding a line to it.
        if (stackedFamily && chromeVisible && !recording && !paneShown) CaptureStatus(
            state,
            Modifier.align(Alignment.BottomCenter).padding(
                bottom = deckHeight + (if (scopeTray) scopeTrayHeight else 0.dp) + 8.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            chip = true,
        )

        if (modeSheet) CaptureModeBottomSheet(onDismiss = { modeSheet = false }) { modesContent { modeSheet = false } }
    }
}
}

private fun CaptureSlot.dial(): ControlDial = when (this) {
    CaptureSlot.FPS -> ControlDial.FPS
    CaptureSlot.INTERVAL -> ControlDial.INT
    CaptureSlot.SHUTTER -> ControlDial.SHUTTER
    CaptureSlot.ISO -> ControlDial.ISO
    CaptureSlot.EV -> ControlDial.EV
    CaptureSlot.WB -> ControlDial.WB
    CaptureSlot.FOCUS -> ControlDial.FOCUS
}

private fun ControlDial.slot(): CaptureSlot? = when (this) {
    ControlDial.RESOLUTION -> null
    ControlDial.FPS -> CaptureSlot.FPS
    ControlDial.INT -> CaptureSlot.INTERVAL
    ControlDial.SHUTTER -> CaptureSlot.SHUTTER
    ControlDial.ISO -> CaptureSlot.ISO
    ControlDial.EV -> CaptureSlot.EV
    ControlDial.WB -> CaptureSlot.WB
    ControlDial.FOCUS -> CaptureSlot.FOCUS
}

/** The five slots of [mode] with their current values, and why any of them cannot be driven now. */
@Composable
private fun captureSlotModels(state: CameraUiState, mode: CaptureMode): List<CaptureSlotModel> {
    val highSpeed = state.activeVideoProfile?.constrainedHighSpeed == true || state.activeLogProfile?.constrainedHighSpeed == true
    val exposureCaps = state.descriptor?.exposureCapabilities
    val manualIso = exposureCaps?.let { it.supports(ExposureMode.MANUAL) || it.supports(ExposureMode.ISO_PRIORITY) } == true
    val manualShutter = exposureCaps?.let { it.supports(ExposureMode.MANUAL) || it.supports(ExposureMode.SHUTTER_PRIORITY) } == true
    val auto = stringResource(R.string.auto_value)
    return captureSlots(mode).map { slot ->
        val reason = captureSlotUnavailableReason(slot, state.descriptor != null, highSpeed, manualIso, manualShutter, state.aeCompensationSupported)
        // A high-speed session runs exposure itself: the slot reads AUTO, with HS beside its label.
        val autoHighSpeed = highSpeed && slot !in setOf(CaptureSlot.FPS, CaptureSlot.INTERVAL, CaptureSlot.EV)
        val value = when {
            autoHighSpeed -> auto
            else -> when (slot) {
                CaptureSlot.FPS -> state.targetFps.toString()
                CaptureSlot.INTERVAL -> formatIntervalShort(state.timelapseIntervalMs)
                CaptureSlot.SHUTTER -> state.effectiveSettings?.exposure?.takeIf {
                    it.shutterUnit == ShutterUnit.ANGLE && it.mode in setOf(ExposureMode.MANUAL, ExposureMode.SHUTTER_PRIORITY) &&
                        !state.exposureControlUnavailable
                }?.let { "${it.angleTenths / 10.0}°${if (state.exposureClamped) "*" else ""}" }
                    ?: state.exposureTimeNs?.let(::formatShutter) ?: auto
                CaptureSlot.ISO -> state.sensitivityIso?.toString() ?: auto
                CaptureSlot.EV -> if (highSpeed) "0" else formatEv(state.aeCompensationEv)
                CaptureSlot.WB -> state.requestedWhiteBalance.label()
                CaptureSlot.FOCUS -> state.focusDistanceDiopters?.let(::formatDiopters) ?: auto
            }
        }
        val detail = when {
            autoHighSpeed -> "HS"
            slot == CaptureSlot.FOCUS -> state.focusDistanceDiopters?.let(::formatFocusMetres)
            else -> null
        }
        CaptureSlotModel(
            slot = slot,
            label = stringResource(slot.labelRes()),
            name = stringResource(slot.nameRes()),
            value = value,
            detail = detail,
            unavailableReason = reason?.let { stringResource(it.textRes()) },
            shortcut = if (slot == CaptureSlot.FOCUS) ShortcutAction.FOCUS else null,
        )
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
    // The side layouts show the status lines in their end column, not beside these actions.
    showStatus: Boolean = true,
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
        // An F-key already mapped to the torch is the torch control; a second one up here was a duplicate.
        if (OperatorAction.TORCH !in settings.operation.buttons) {
            TopAction(CineIcon.TORCH, stringResource(R.string.flash_torch)) { showLight = true }
        }
        TopAction(CineIcon.SETTINGS, stringResource(R.string.settings_tab), onOpenSettings)
    }
    if (vertical) {
        Column(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showThumbnail) MediaThumbnailAction(state.lastSavedUri, onOpenMedia)
            if (showStatus) {
                CaptureStatusLine(state, Modifier.fillMaxWidth().padding(horizontal = 4.dp), maxLines = 3, textAlign = TextAlign.Center)
                ThermalHudChip()
                OutOfFrameHudChip(state)
            }
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
        if (showStatus) {
            CaptureStatusLine(state, Modifier.weight(1f))
            ThermalHudChip()
            OutOfFrameHudChip(state)
        } else Spacer(Modifier.weight(1f))
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
private fun ModeDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    // Tapping the centred mode opens the full mode sheet (with the resolution) when set.
    onOpenSheet: (() -> Unit)? = null,
) {
    val selection = LocalModeSelection.current
    val displayedMode = selection?.displayed ?: state.selectedMode
    val selectMode: (CaptureMode) -> Unit = selection?.select ?: { mode -> binder?.selectMode(mode) }
    val modes = visibleCaptureModes(state.modeGates, displayedMode)
    val selectedIndex = modes.indexOf(displayedMode).coerceAtLeast(0)
    val haptics = LocalHapticFeedback.current
    val dialDescription = stringResource(R.string.mode_dial_description, modeLabel(displayedMode))
    fun selectable(mode: CaptureMode): Boolean = CameraUiState.isModeSelectable(state.modeGates.getValue(mode))
    val labels = modes.map { modeLabel(it) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val density = LocalDensity.current
    // Every cell is as wide as the longest label, so no mode name is clipped ("Time-lapse",
    // "Ralentí" and the like in every language); the wheel shows fewer neighbours instead.
    val longestLabel = remember(labels, density) {
        with(density) { labels.maxOfOrNull { measurer.measure(it, labelStyle, softWrap = false).size.width }?.toDp() ?: 0.dp }
    }

    // A mode wheel: a linear carousel whose centred mode is the active one, marked only by amber
    // text and a dot (no frame, so it reads the same on phones, foldables and tablets). Neighbours
    // stay visible on both sides and fade out at the edges; a drag settles on the nearest mode and
    // tapping a visible mode selects it directly.
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = dialDescription },
    ) {
        val totalWidth = maxWidth
        val itemWidth = maxOf((totalWidth / 4.2f).coerceIn(76.dp, 140.dp), longestLabel + 16.dp).coerceAtMost(totalWidth)
        val rowHeight = 48.dp
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
        Box(
            Modifier
                .fillMaxWidth()
                .height(rowHeight)
                // The edge fade says "more this way" without cutting a label in half.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            .12f to Color.Black,
                            .88f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            LazyRow(
                state = listState,
                flingBehavior = fling,
                contentPadding = PaddingValues(horizontal = ((totalWidth - itemWidth) / 2).coerceAtLeast(0.dp)),
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
                            .clickable(enabled = enabled && (!isSelected || onOpenSheet != null)) {
                                if (isSelected) onOpenSheet?.invoke() else selectMode(mode)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            labels[index],
                            color = when {
                                isSelected -> Amber
                                enabled -> Color.White
                                else -> Muted
                            },
                            fontSize = if (isSelected) 15.sp else 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                        )
                        // The centred mode opens the sheet: ▾ says so, where a plain dot would not.
                        if (isSelected && onOpenSheet != null) CineGlyph(CineIcon.EXPAND, Amber, Modifier.padding(top = 2.dp).size(12.dp))
                        else Box(
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

/** Whether REC (or R) does anything now: a take can start or stop, or a pending capture can be cancelled. */
internal fun captureControlEnabled(state: CameraUiState): Boolean =
    !state.recordingFinalizing && (state.capturePreparationCancelable ||
        state.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED, CameraUiPhase.RECORDING))

@OptIn(ExperimentalMaterial3Api::class)
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
    // R does the same with a keyboard attached; the tooltip (long press, or hover) says so.
    val tooltip = keyHint(captureDescription, ShortcutAction.RECORD)
    val button: @Composable () -> Unit = { CaptureTooltip(tooltip, null) {
        Box(
            Modifier
                .size(size)
                .semantics {
                    contentDescription = captureDescription
                    if (stateLabel != null) stateDescription = stateLabel
                }
                .border(3.dp, if (recordState == RecordButtonState.UNAVAILABLE) Muted else Color.White, CircleShape)
                .clip(CircleShape)
                .clickable(enabled = captureControlEnabled(state)) {
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
    } }
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
    fun read() = (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    // Read once up front so the first frame already carries the value.
    var batteryPercent by remember { mutableStateOf(read()) }
    LaunchedEffect(Unit) {
        while (true) {
            batteryPercent = read()
            delay(5_000)
        }
    }
    return batteryPercent
}

/**
 * The top bar's one line of camera state: timecode when one runs, free space and battery. A value
 * the device does not report is left out rather than shown as a dash.
 */
@Composable
private fun CaptureStatusLine(state: CameraUiState, modifier: Modifier = Modifier, maxLines: Int = 1, textAlign: TextAlign? = null) {
    val battery = rememberBatteryPercent()
    val muted = Muted
    // Low battery is a warning, not a failure: amber, never the recording red.
    val batteryColor = if (battery != null && battery <= 15) LocalCineColors.current.pending else OkGreen
    val free = state.availableStorageBytes?.let { stringResource(R.string.capture_status_free, formatBytes(it)) }
    val text = androidx.compose.ui.text.buildAnnotatedString {
        fun separator() { if (length > 0) append("  ·  ") }
        state.timecodeDisplay?.let { tc ->
            withStyle(androidx.compose.ui.text.SpanStyle(color = muted)) { append("TC ") }
            append(tc)
        }
        free?.let { separator(); append(it) }
        battery?.let {
            separator()
            withStyle(androidx.compose.ui.text.SpanStyle(color = muted)) { append("BAT ") }
            withStyle(androidx.compose.ui.text.SpanStyle(color = batteryColor)) { append("$it%") }
        }
    }
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier.testTag("capture-status-line"),
    )
}

/**
 * One line under the capture controls with what the next take records: codec, bitrate, sound, the
 * time left on the storage and, when set, the anamorphic squeeze and the LOG view. Stills modes
 * only list what applies to them; free space and battery live in the top bar's status line.
 */
@Composable
private fun StatusInfoBar(state: CameraUiState, settings: CameraSettings) {
    val video = !state.selectedMode.isStillMode()
    // Off-speed takes are silent by design.
    val silent = !settings.audioEnabled || (state.selectedMode == CaptureMode.VIDEO && settings.videoOffSpeed)
    val parts = buildList {
        if (video) {
            add(when (state.selectedMode) {
                CaptureMode.LOG -> "HEVC 10-bit LOG"
                CaptureMode.RAW_VIDEO -> "RAW 10-bit"
                else -> "H.264"
            })
            add("${settings.videoBitrateMbps} Mbps")
            add(if (silent) stringResource(R.string.status_audio_off)
                else audioOutputLabel(settings.audioOutputFormat) + " " + formatAudioRate(settings.audioSampleRateHz))
            recordTimeLeftMs(state.availableStorageBytes, settings.videoBitrateMbps)?.let {
                add(stringResource(R.string.status_time_left, formatRecordTimeLeft(it)))
            }
        }
        anamorphicStatusLabel(settings.anamorphicSqueeze, settings.anamorphicOutputMode)?.let(::add)
        if (state.selectedMode == CaptureMode.LOG) {
            add(stringResource(R.string.status_view_assist, if (settings.logViewAssistEnabled) "Rec.709" else "LOG"))
        }
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        color = Muted,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().testTag("status-info-bar"),
    )
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
        // A phone-width strip drops the frame size and the monitoring shortcut to keep REC, the
        // meter and stop on one row.
        val compact = windowWidthClass(maxWidth.value) == WindowWidthClass.COMPACT
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
                Text((if (state.recordingPauseStatus?.paused == true) stringResource(R.string.recording_paused) else "REC") + " " + formatDuration(state.recordingElapsedMs),
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (!compact && state.recordingWidth != null && state.recordingHeight != null) {
                    Text(formatFrameSize(state.recordingWidth, state.recordingHeight), color = Muted, fontSize = 12.sp, maxLines = 1)
                }
            }
            // Full width even on a phone: a narrower meter cut its "no signal" line short.
            AudioMeterHud(state, binder)
            ThermalHudChip(recording = true)
            OutOfFrameHudChip(state)
            Spacer(Modifier.weight(1f))
            if (!compact) TopAction(CineIcon.MONITORING, stringResource(R.string.monitoring_tools)) { showMonitors = !showMonitors }
            if (showStop) {
                Box(
                    Modifier.size(48.dp).semantics { contentDescription = stopRecordingDescription }.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape).padding(5.dp).clip(CircleShape).clickable { binder?.capturePrimary() },
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
    // Every line is single and ellipsised, and the panel clips, so a narrow portrait slot never
    // lets the meter spill over the instruments next to it.
    Column(
        modifier.width(meterWidth).heightIn(min = 48.dp).testTag("audio-meter-hud")
            .background(Panel, RoundedCornerShape(7.dp))
            .clipToBounds()
            .clickable(enabled = state.audioClipLatched, onClickLabel = resetLabel, onClick = onResetClip)
            .semantics { if (state.audioClipLatched) contentDescription = resetLabel }
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(if (snapshot != null) "MIC" else stringResource(R.string.audio_meter_no_pcm),
            Modifier.testTag("audio-meter-current"), color = if (snapshot != null) VerifiedCyan else Muted,
            fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(audioMeterModeLabel(meterSettings.mode)), Modifier.testTag("audio-meter-mode"), color = Muted,
            fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (state.audioClipLatched) Text("CLIP", Modifier.testTag("audio-meter-clip"), color = RecordRed, fontSize = 12.sp,
            lineHeight = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        val meterTrack = MaterialTheme.colorScheme.surfaceContainerHighest
        val meterLine = MaterialTheme.colorScheme.onSurface
        val meterGreen = OkGreen
        repeat(max(1, levels.size)) { index ->
            val level = levels.getOrNull(index)
            val value = displayed.getOrNull(index)
            val label = if (levels.size <= 1) "M" else if (index == 0) "L" else "R"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = Muted, fontSize = 12.sp, lineHeight = 12.sp, maxLines = 1)
                val meterRed = RecordRed; val meterAmber = Amber
                Canvas(Modifier.weight(1f).height(8.dp).testTag("audio-meter-channel-$index")) {
                    drawRect(meterTrack)
                    if (value != null) {
                        val minimum = if (meterSettings.mode == AudioMeterMode.VU) -30f else -60f
                        val maximum = if (meterSettings.mode == AudioMeterMode.VU) 6f else 0f
                        fun fraction(db: Float) = ((db - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
                        val signalColor = when {
                            (level?.peakDbfs ?: -120f) >= -3f -> meterRed
                            (level?.peakDbfs ?: -120f) >= -12f -> meterAmber
                            else -> meterGreen
                        }
                        drawRect(signalColor, size = androidx.compose.ui.geometry.Size(size.width * fraction(value), size.height))
                        if (meterSettings.mode == AudioMeterMode.PEAK_RMS) level?.rmsDbfs?.takeIf { it.isFinite() }?.let { rms ->
                            drawLine(meterLine, androidx.compose.ui.geometry.Offset(size.width * fraction(rms), 0f),
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
                Text(valueText, Modifier.testTag("audio-meter-value-$index"), color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (value != null && meterSettings.peakHoldMs > 0) Text(stringResource(R.string.audio_meter_hold_value, number(held.getOrNull(index))),
                    Modifier.testTag("audio-meter-hold-$index"), color = Amber, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
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
            stringResource(R.string.recording_status, formatFrameSize(state.recordingWidth, state.recordingHeight), state.targetFps,
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
        color = if (state.errorCode == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        fontSize = 12.sp,
        lineHeight = 15.sp,
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

/**
 * A capture panel's content. It fills the width its host gives it and scrolls inside, so the host
 * only bounds its height. [showHeader] false leaves the title row and close button to a host that
 * draws its own (the capture context pane); the content then starts with the controls.
 */
@Composable
private fun ManualControlDial(
    control: ControlDial,
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    showHeader: Boolean = true,
    onClose: () -> Unit,
) {
    if (state.descriptor == null) return
    CompositionLocalProvider(LocalPanelHeaderVisible provides (showHeader && LocalPanelHeaderVisible.current)) {
        when (control) {
            ControlDial.RESOLUTION -> ResolutionDial(state, binder, onClose)
            ControlDial.FPS -> FpsDial(state, binder, onClose)
            ControlDial.WB -> WbDial(state, binder, onClose)
            ControlDial.EV -> EvDial(state, binder, onClose)
            ControlDial.FOCUS -> FocusPullDial(state, binder, settings, onSettingsChanged, onClose)
            ControlDial.INT -> IntervalometerDial(state, settings, onSettingsChanged, onClose)
            ControlDial.ISO -> ExposureDial(iso = true, state, binder, settings, onSettingsChanged, onClose)
            ControlDial.SHUTTER -> ExposureDial(iso = false, state, binder, settings, onSettingsChanged, onClose)
        }
    }
}

/** High-speed sessions leave exposure and white balance to the camera. */
private fun CameraUiState.highSpeedSession(): Boolean =
    activeVideoProfile?.constrainedHighSpeed == true || activeLogProfile?.constrainedHighSpeed == true

/** The one large value a panel is about, centred over its controls. */
@Composable
private fun PanelReadout(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth().testTag("panel-readout"))
}

/** Range ends under a slider, with an AUTO reset between them when [onAuto] is set. */
@Composable
private fun PanelRangeRow(start: String, end: String, onAuto: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(start, color = Muted, fontSize = 12.sp)
        if (onAuto != null) TextButton(onClick = onAuto, modifier = Modifier.heightIn(min = 48.dp).testTag("panel-auto")) {
            Text(stringResource(R.string.auto_value), color = VerifiedCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(end, color = Muted, fontSize = 12.sp)
    }
}

/** ISO or shutter. Where the camera cannot take either value by hand the panel says so instead. */
@Composable
private fun ExposureDial(
    iso: Boolean,
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    onClose: () -> Unit,
) {
    val descriptor = state.descriptor ?: return
    val caps = descriptor.exposureCapabilities
    val manual = caps.supports(ExposureMode.MANUAL)
    val priority = if (iso) ExposureMode.ISO_PRIORITY else ExposureMode.SHUTTER_PRIORITY
    PanelColumn(Modifier.testTag(if (iso) "iso-panel" else "shutter-panel")) {
        PanelHeader(stringResource(if (iso) R.string.panel_title_iso else R.string.panel_title_shutter), onClose)
        if (state.highSpeedSession()) {
            PanelNote(stringResource(R.string.panel_high_speed_auto), Modifier.testTag("exposure-unavailable"))
            return@PanelColumn
        }
        if (!manual && !caps.supports(priority)) {
            PanelNote(stringResource(if (iso) R.string.exposure_manual_unavailable_iso else R.string.exposure_manual_unavailable_shutter),
                Modifier.testTag("exposure-unavailable"))
            return@PanelColumn
        }
        val isoMin = descriptor.sensitivityRange?.lower?.toDouble() ?: 50.0
        val isoMax = descriptor.sensitivityRange?.upper?.toDouble() ?: 6400.0
        val timeMin = descriptor.exposureTimeRangeNs?.lower?.toDouble() ?: 100_000.0
        val timeMax = descriptor.exposureTimeRangeNs?.upper?.toDouble() ?: 1_000_000_000.0
        val mode = settings.exposure.mode
        // The camera picks this value itself in full auto and in the other value's priority mode.
        val autoHere = mode == ExposureMode.AUTO || mode == (if (iso) ExposureMode.SHUTTER_PRIORITY else ExposureMode.ISO_PRIORITY)
        val angle = !iso && settings.exposure.shutterUnit == ShutterUnit.ANGLE
        val isoValue = if (autoHere) state.sensitivityIso ?: state.requestedIso else state.requestedIso ?: state.sensitivityIso
        val timeValue = if (autoHere) state.exposureTimeNs ?: state.requestedExposureTimeNs else state.requestedExposureTimeNs ?: state.exposureTimeNs
        val value = when {
            iso -> isoValue?.let { "ISO $it" }
            angle && !autoHere -> String.format(java.util.Locale.ROOT, "%.1f°", settings.exposure.angleTenths / 10f)
            else -> timeValue?.let(::formatShutter)
        }
        val autoLabel = stringResource(R.string.auto_value)
        PanelReadout(
            when {
                value == null -> autoLabel
                autoHere -> "$value · $autoLabel"
                else -> value
            },
            color = if (autoHere) VerifiedCyan else Amber,
        )
        CineSlider(
            value = when {
                iso -> logPosition((isoValue ?: isoMin.toInt()).toDouble(), isoMin, isoMax)
                angle -> (settings.exposure.angleTenths - 1) / 3599f
                else -> logPosition((timeValue ?: 16_666_667L).toDouble(), timeMin, timeMax)
            },
            onValueChange = { position ->
                if (iso) {
                    onSettingsChanged(settings.copy(exposure = settings.exposure.copy(
                        mode = if (mode == ExposureMode.ISO_PRIORITY || !manual) ExposureMode.ISO_PRIORITY else ExposureMode.MANUAL,
                        iso = logValue(position, isoMin, isoMax).toInt(),
                        timeNs = if (mode == ExposureMode.AUTO) state.exposureTimeNs ?: settings.exposure.timeNs else settings.exposure.timeNs,
                    )))
                } else {
                    val intent = settings.exposure.copy(
                        mode = if (mode == ExposureMode.SHUTTER_PRIORITY || !manual) ExposureMode.SHUTTER_PRIORITY else ExposureMode.MANUAL,
                        iso = if (mode == ExposureMode.AUTO) state.sensitivityIso ?: settings.exposure.iso else settings.exposure.iso,
                    )
                    onSettingsChanged(settings.copy(exposure = if (angle) {
                        intent.copy(angleTenths = (position * 3599 + 1).roundToInt().coerceIn(1, 3600))
                    } else intent.copy(timeNs = logValue(position, timeMin, timeMax).toLong())))
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag(if (iso) "iso-slider" else "shutter-slider"),
        )
        PanelRangeRow(
            start = when { iso -> isoMin.toInt().toString(); angle -> "0.1°"; else -> formatShutter(timeMin.toLong()) },
            end = when { iso -> isoMax.toInt().toString(); angle -> "360°"; else -> formatShutter(timeMax.toLong()) },
            // AUTO hands the value back to the camera and keeps the panel open on the live reading.
            onAuto = { binder?.setManualExposure(null, null) },
        )
    }
}

@Composable
private fun EvDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    onClose: () -> Unit,
) {
    PanelColumn(Modifier.testTag("ev-panel")) {
        PanelHeader(stringResource(R.string.panel_title_ev), onClose)
        val range = state.aeCompensationIndexRange
        if (range == null || range.last <= range.first) {
            PanelNote(stringResource(R.string.ev_unavailable), Modifier.testTag("ev-unavailable"))
            return@PanelColumn
        }
        val step = state.descriptor?.aeCompensationStep?.takeIf { it > 0f } ?: 1f
        val current = state.requestedAeCompensationIndex.coerceIn(range.first, range.last)
        PanelReadout(formatEv(current * step), color = if (current == 0) VerifiedCyan else Amber)
        CineSlider(
            value = current.toFloat(),
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            onValueChange = { index -> binder?.setExposureCompensation(index.roundToInt()) },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("ev-slider"),
        )
        PanelRangeRow(formatEv(range.first * step), formatEv(range.last * step)) { binder?.setExposureCompensation(0) }
    }
}

@Composable
private fun ResolutionDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    onClose: () -> Unit,
) {
    PanelColumn(Modifier.testTag("resolution-panel")) {
        PanelHeader(stringResource(R.string.panel_title_resolution), onClose)
        ChoiceGrid(state.availableVideoSizes, minTileWidth = 104.dp, maxColumns = 3) { (width, height), modifier ->
            val selected = width == state.targetVideoWidth && height == state.targetVideoHeight
            ChoiceTile(formatFrameSize(width, height), selected, modifier) {
                binder?.selectVideoResolution(width, height)
                onClose()
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
    val log = state.selectedMode == CaptureMode.LOG
    val logSpecs = state.availableLogProfiles.map { it.toSpec() }
    val videoSpecs = state.availableVideoProfiles.map { it.toSpec() }
    val modeRates = when {
        log -> VideoGeometryPolicy.unionLogFps(logSpecs)
        state.selectedMode in CameraUiState.videoProfileModes -> VideoGeometryPolicy.unionFps(videoSpecs)
        else -> state.availableTargetFps
    }
    val offeredAtSize = if (log) VideoGeometryPolicy.supportedLogFps(logSpecs, state.targetVideoWidth, state.targetVideoHeight)
        else VideoGeometryPolicy.supportedFps(videoSpecs, state.targetVideoWidth, state.targetVideoHeight)
    val options = modeRates.map { fps ->
        val logProfile = state.availableLogProfiles.firstOrNull {
            log && it.size.width == state.targetVideoWidth && it.size.height == state.targetVideoHeight && it.fps == fps
        }
        FpsOption(
            fps = fps,
            offered = fps in offeredAtSize,
            highSpeed = logProfile?.constrainedHighSpeed == true || state.availableVideoProfiles.any {
                !log && it.size.width == state.targetVideoWidth && it.size.height == state.targetVideoHeight && it.fps == fps && it.constrainedHighSpeed
            },
            ispHighRate = logProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP,
        )
    }
    PanelColumn(Modifier.testTag("fps-panel")) {
        PanelHeader(stringResource(R.string.panel_title_fixed_fps), onClose)
        ChoiceGrid(options, minTileWidth = 72.dp, maxColumns = 6) { option, modifier ->
            val label = when {
                option.ispHighRate -> "${option.fps} HFR"
                option.highSpeed -> "${option.fps} HS"
                else -> option.fps.toString()
            }
            ChoiceTile(label, option.fps == state.targetFps, modifier, enabled = option.offered) {
                binder?.selectTargetFps(option.fps)
                onClose()
            }
        }
        // Notes only describe what this grid offers: the viewfinder rate when a high-speed rate
        // can be picked here, and greyed-out rates only when some are.
        if (fpsHighSpeedNoteApplies(options)) {
            // Camera2 feeds the preview one frame per high-speed batch, so the viewfinder of a 120 or
            // 240 fps session runs at 30 fps while the file keeps every frame.
            PanelNote(stringResource(R.string.fps_high_speed_preview_note), Modifier.testTag("fps-high-speed-note"))
        }
        if (options.any { !it.offered }) {
            PanelNote(stringResource(R.string.fps_unavailable_at_size, formatFrameSize(state.targetVideoWidth, state.targetVideoHeight)),
                Modifier.testTag("fps-unavailable-note"))
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
    val retainedTint = (state.effectiveSettings?.whiteBalance as? WhiteBalanceSelection.Kelvin)?.tint
        ?: (selection as? WhiteBalanceSelection.Kelvin)?.tint ?: 0
    PanelColumn(Modifier.testTag("wb-panel")) {
        PanelHeader(stringResource(R.string.panel_title_white_balance), onClose)
        if (state.highSpeedSession()) {
            PanelNote(stringResource(R.string.panel_high_speed_auto), Modifier.testTag("wb-unavailable"))
            return@PanelColumn
        }
        if (kelvinRange != null) {
            val auto = selection is WhiteBalanceSelection.Auto
            val displayK = (selection as? WhiteBalanceSelection.Kelvin)?.kelvin
                ?: KELVIN_PRESETS.firstOrNull { it in kelvinRange } ?: kelvinRange.first
            PanelReadout(if (auto) stringResource(R.string.auto_value) else "${displayK}K", color = if (auto) VerifiedCyan else Amber)
            // Kelvin slider in 100 K steps within the device range.
            if (!auto) CineSlider(
                value = displayK.toFloat(),
                valueRange = kelvinRange.first.toFloat()..kelvinRange.last.toFloat(),
                steps = ((kelvinRange.last - kelvinRange.first) / 100 - 1).coerceAtLeast(0),
                onValueChange = { raw ->
                    snapKelvinTo100(raw.toInt(), kelvinRange)?.let { binder?.setWhiteBalance(WhiteBalanceSelection.Kelvin(it, retainedTint)) }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("wb-kelvin-slider"),
            )
            ChoiceGrid(KELVIN_PRESETS, minTileWidth = 72.dp, maxColumns = 4, balanced = true) { preset, modifier ->
                val selected = selection is WhiteBalanceSelection.Kelvin && selection.kelvin == preset
                ChoiceTile("${preset}K", selected, modifier, enabled = preset in kelvinRange) {
                    binder?.setWhiteBalance(WhiteBalanceSelection.Kelvin(preset, retainedTint))
                }
            }
            PanelRangeRow("${kelvinRange.first}K", "${kelvinRange.last}K") { binder?.setWhiteBalance(WhiteBalanceSelection.Auto) }
            // Tint shifts a Kelvin value between green and magenta, where the camera offers it.
            if (descriptor.tintSupported && selection is WhiteBalanceSelection.Kelvin) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PanelSectionLabel(stringResource(R.string.wb_tint), Modifier.weight(1f))
                    Text(if (selection.tint > 0) "+${selection.tint}" else selection.tint.toString(), color = Amber,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("wb-tint-value"))
                }
                CineSlider(
                    value = selection.tint.toFloat(),
                    valueRange = -50f..50f,
                    steps = 99,
                    onValueChange = { binder?.setWhiteBalance(selection.copy(tint = it.roundToInt().coerceIn(-50, 50))) },
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("wb-tint-slider"),
                )
                PanelRangeRow(stringResource(R.string.wb_tint_green), stringResource(R.string.wb_tint_magenta))
            }
        } else {
            // Cameras without colour-temperature control offer the classic presets only.
            val offered = descriptor?.availableAwbModes.orEmpty()
            val choices = listOf(
                null to R.string.auto_value,
                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to R.string.wb_preset_daylight,
                CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to R.string.wb_preset_cloudy,
                CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to R.string.wb_preset_tungsten,
                CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to R.string.wb_preset_fluorescent,
            ).filter { (mode, _) -> mode == null || offered.isEmpty() || mode in offered }
            ChoiceGrid(choices, minTileWidth = 96.dp, maxColumns = 3) { (mode, label), modifier ->
                val selected = if (mode == null) selection is WhiteBalanceSelection.Auto
                    else selection is WhiteBalanceSelection.Preset && selection.awbMode == mode
                ChoiceTile(stringResource(label), selected, modifier) {
                    binder?.setWhiteBalance(if (mode != null) WhiteBalanceSelection.Preset(mode) else WhiteBalanceSelection.Auto)
                }
            }
        }
    }
}

@Composable
private fun IntervalometerDial(state: CameraUiState, settings: CameraSettings, onSettingsChanged: (CameraSettings) -> Unit, onClose: () -> Unit) {
    PanelColumn(Modifier.testTag("interval-panel")) {
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
    // One column of equal-width cards, capped and centred for the pane's width class; the gutter
    // is content padding so the whole pane still scrolls. Rows lay out side by side when the
    // card is wide enough for a label and a control column.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val pane = maxWidth.value
    val gutter = settingsSideGutterDp(pane)
    CompositionLocalProvider(LocalSettingsRowLayout provides settingsRowLayout(settingsRowWidthDp(pane))) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(1),
        modifier = Modifier.fillMaxSize().background(Graphite).testTag("settings-list"),
        contentPadding = PaddingValues(horizontal = gutter.dp, vertical = 16.dp),
        verticalItemSpacing = 12.dp,
    ) {
        if ("media-sharing" in visibleIds) settingsCard("media-sharing") {
            MediaSharingSettingsControls(settings.mediaSharing, onSettings = { onSettingsChange(settings.copy(mediaSharing = it)) })
        }
        if ("media-gallery" in visibleIds) settingsCard("media-gallery") {
            GallerySettingsControls(settings.gallery, onSettings = { onSettingsChange(settings.copy(gallery = it)) })
        }
        if ("proxy" in visibleIds) settingsCard("proxy") {
            ProxySettingsControls(settings.proxy) { onSettingsChange(settings.copy(proxy = it)) }
        }
        if ("playback" in visibleIds) settingsCard("playback") {
            PlaybackSettingsControls(settings.playback) { onSettingsChange(settings.copy(playback = it)) }
        }
        if ("capture-naming" in visibleIds) settingsCard("capture-naming") {
            CaptureNamingSettingsControls(settings, onSettingsChange)
        }
        if ("geotagging" in visibleIds) settingsCard("geotagging") {
            GeotaggingSettingsControls(settings, onSettingsChange)
        }
        if ("production-slate" in visibleIds) settingsCard("production-slate") {
            ProductionSlateSettingsControls(state, settings, onSettingsChange)
        }
        if ("project-timing" in visibleIds) settingsCard("project-timing") {
            ProjectTimingSettings(state, settings, onSettingsChange)
        }
        if ("timelapse" in visibleIds) settingsCard("timelapse") {
            TimelapseSettings(state, settings, onSettingsChange)
        }
        if ("operator-controls" in visibleIds) settingsCard("operator-controls") {
            OperatorSettings(state, settings, onSettingsChange)
        }
        if ("webdav-queue" in visibleIds) settingsCard("webdav-queue") { WebDavQueueSettingsSection() }
        if ("presets" in visibleIds) settingsCard("presets") {
            PresetSettings(state, settings, onApplyPreset)
        }
        if ("image-processing" in visibleIds) settingsCard("image-processing") {
            ImageProcessingSettings(state, settings, onSettingsChange)
        }
        if ("professional-exposure" in visibleIds) settingsCard("professional-exposure") {
            ProfessionalExposureSettings(state, settings, onSettingsChange)
        }
        if ("fold-displays" in visibleIds) settingsCard("fold-displays") {
            FoldDisplaySettings(state, settings, onSettingsChange, showTitle = false)
        }
        // OCC-PLAN-068 subject features: each card's controls live in that unit's own file.
        val subject = settings.subjectDisplay
        val updateSubject = { next: SubjectDisplaySettings -> onSettingsChange(settings.copy(subjectDisplay = next)) }
        if ("subject-self-monitor" in visibleIds) settingsCard("subject-self-monitor") {
            SettingsSectionTitle(stringResource(R.string.subject_self_monitor_title), help = stringResource(R.string.subject_self_monitor_help)); SubjectSelfMonitorSettings(state, subject, updateSubject)
        }
        if ("subject-tally" in visibleIds) settingsCard("subject-tally") {
            SettingsSectionTitle(stringResource(R.string.subject_tally_title), help = stringResource(R.string.subject_tally_help)); SubjectTallySettings(state, subject, updateSubject)
        }
        if ("subject-fill-light" in visibleIds) settingsCard("subject-fill-light") {
            SettingsSectionTitle(stringResource(R.string.fold_mode_fill_light), help = stringResource(R.string.subject_fill_light_help)); SubjectFillLightSettings(state, subject, updateSubject)
        }
        if ("subject-interview" in visibleIds) settingsCard("subject-interview") {
            SettingsSectionTitle(stringResource(R.string.fold_mode_interview), help = stringResource(R.string.subject_interview_help)); SubjectInterviewSettings(state, subject, updateSubject)
        }
        if ("subject-slate" in visibleIds) settingsCard("subject-slate") {
            SettingsSectionTitle(stringResource(R.string.fold_mode_slate)); SubjectSlateSettings(state, subject, updateSubject)
        }
        if ("subject-out-of-frame" in visibleIds) settingsCard("subject-out-of-frame") {
            SettingsSectionTitle(stringResource(R.string.subject_out_of_frame_title), help = stringResource(R.string.subject_out_of_frame_help)); SubjectOutOfFrameSettings(state, subject, updateSubject)
        }
        if ("appearance" in visibleIds) settingsCard("appearance") { AppearanceSettings() }
        if ("layout" in visibleIds) settingsCard("layout") {
            SettingsOptionRow(stringResource(R.string.mode_selector_style), stringResource(R.string.mode_selector_summary),
                ModeSelectorStyle.entries, settings.modeSelectorStyle,
                label = { stringResource(if (it == ModeSelectorStyle.DIAL) R.string.mode_selector_dial else R.string.mode_selector_buttons) },
                tag = { "mode-selector-$it" }, onSelect = { onSettingsChange(settings.copy(modeSelectorStyle = it)) })
        }
        if ("translucent-chrome" in visibleIds) settingsCard("translucent-chrome") {
            SettingsToggleRow(
                title = stringResource(R.string.translucent_chrome),
                summary = stringResource(R.string.translucent_chrome_summary),
                checked = settings.translucentChrome,
                onCheckedChange = { onSettingsChange(settings.copy(translucentChrome = it)) },
            )
            if (settings.translucentChrome) {
                SettingsOptionRow(stringResource(R.string.viewfinder_scale), stringResource(R.string.viewfinder_scale_summary),
                    ViewfinderScale.entries, settings.viewfinderScale,
                    label = { stringResource(if (it == ViewfinderScale.FIT) R.string.viewfinder_scale_fit else R.string.viewfinder_scale_fill) },
                    tag = { "viewfinder-scale-$it" }, onSelect = { onSettingsChange(settings.copy(viewfinderScale = it)) })
                SettingsSliderRow("${stringResource(R.string.chrome_opacity)} · ${(settings.chromeOpacity * 100).roundToInt()}%",
                    settings.chromeOpacity,
                    { onSettingsChange(settings.copy(chromeOpacity = clampChromeOpacity((it * 20f).roundToInt() / 20f))) },
                    MIN_CHROME_OPACITY..MAX_CHROME_OPACITY, steps = 10, sliderModifier = Modifier.testTag("chrome-opacity"))
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
        if ("audio-format" in visibleIds) settingsCard("audio-format") {
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
            SettingsSectionTitle(stringResource(R.string.burst_mode), help = stringResource(R.string.burst_capture_help), helpTag = "burst-help")
            SettingsSliderRow("${stringResource(R.string.burst_count)} · ${settings.burstCount}", settings.burstCount.toFloat(),
                { onSettingsChange(settings.copy(burstCount = it.roundToInt().coerceIn(3, 10))) }, 3f..10f, steps = 6,
                sliderModifier = Modifier.testTag("burst-count"))
        }
        if ("bitrate" in visibleIds) settingsCard("bitrate") {
            SettingsOptionRow(stringResource(R.string.video_bitrate), null, listOf(12, 20, 40), settings.videoBitrateMbps,
                label = { "$it Mbps" }, tag = { "video-bitrate-$it" }, onSelect = { onSettingsChange(settings.copy(videoBitrateMbps = it)) })
        }
        if ("geometry" in visibleIds) settingsCard("geometry") {
            SettingsOptionRow(stringResource(R.string.recording_geometry), stringResource(R.string.recording_geometry_summary),
                RecordingGeometryMode.entries, settings.recordingGeometryMode,
                label = { stringResource(if (it == RecordingGeometryMode.COMPATIBLE) R.string.recording_geometry_compatible else R.string.recording_geometry_native) },
                tag = { "recording-geometry-$it" }, onSelect = { onSettingsChange(settings.copy(recordingGeometryMode = it)) })
        }
        if ("anamorphic" in visibleIds) settingsCard("anamorphic") {
            SettingsOptionRow(stringResource(R.string.anamorphic), stringResource(R.string.anamorphic_summary),
                AnamorphicSqueeze.entries, settings.anamorphicSqueeze, label = { anamorphicSqueezeLabel(it) },
                tag = { "anamorphic-squeeze-$it" }, onSelect = { onSettingsChange(settings.copy(anamorphicSqueeze = it)) })
            if (settings.anamorphicSqueeze.isActive) {
                SettingsOptionRow(stringResource(R.string.anamorphic_output), stringResource(R.string.anamorphic_output_summary),
                    AnamorphicOutputMode.entries, settings.anamorphicOutputMode,
                    label = { stringResource(if (it == AnamorphicOutputMode.SQUEEZED) R.string.anamorphic_output_squeezed else R.string.anamorphic_output_desqueezed) },
                    tag = { "anamorphic-output-$it" }, onSelect = { onSettingsChange(settings.copy(anamorphicOutputMode = it)) })
            }
        }
        if ("accumulation" in visibleIds) settingsCard("accumulation") {
            AccumulationSettings(state, settings, onSettingsChange)
        }
        if ("bracket" in visibleIds) settingsCard("bracket") {
            BracketSettings(state, settings, onSettingsChange)
        }
        if ("lut-library" in visibleIds) settingsCard("lut-library") {
            LutLibrarySettings(state)
        }
        if ("monitoring-scopes" in visibleIds) settingsCard("monitoring-scopes") {
            MonitoringSettings(settings, onSettingsChange)
        }
        if ("photo-aspect" in visibleIds) settingsCard("photo-aspect") {
            PhotoAspectSettings(state, settings, onSettingsChange)
        }
        if ("photo-format" in visibleIds) settingsCard("photo-format") {
            PhotoFormatSettings(state, settings, onSettingsChange)
        }
        if ("photo-flash" in visibleIds) settingsCard("photo-flash") {
            PhotoFlashSettings(state, settings, onSettingsChange)
        }
        if ("torch" in visibleIds) settingsCard("torch") {
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
            SettingsOptionRow(stringResource(R.string.composition_grid_mode), null, CompositionGridMode.entries, settings.compositionGridMode,
                label = { stringResource(compositionGridModeTitle(it)) }, tag = { "grid-mode-$it" },
                onSelect = { onSettingsChange(settings.copy(compositionGridMode = it)) })
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
            SettingsOptionRow(stringResource(R.string.af_lock_behavior), stringResource(R.string.af_lock_behavior_summary),
                AfLockBehavior.entries, settings.afLockBehavior,
                label = { stringResource(if (it == AfLockBehavior.FREEZE_CURRENT) R.string.af_lock_freeze_current else R.string.af_lock_focus_and_lock) },
                tag = { "af-lock-$it" }, onSelect = { onSettingsChange(settings.copy(afLockBehavior = it)) })
        }
        if ("zoom-lens" in visibleIds) settingsCard("zoom-lens") {
            SettingsOptionRow(stringResource(R.string.zoom_lens_switch_mode), stringResource(R.string.zoom_lens_switch_mode_summary),
                ZoomLensSwitchMode.entries, settings.zoomLensSwitchMode,
                label = { stringResource(if (it == ZoomLensSwitchMode.MANUAL_PRESETS) R.string.zoom_lens_switch_mode_manual else R.string.zoom_lens_switch_mode_automatic) },
                tag = { "zoom-lens-switch-$it" }, onSelect = { onSettingsChange(settings.copy(zoomLensSwitchMode = it)) })
        }
        if ("timecode" in visibleIds) settingsCard("timecode") {
            TimecodeSettings(settings, onSettingsChange)
        }
        if ("camera-capabilities" in visibleIds && onOpenCapabilities != null) settingsCard("camera-capabilities") {
            SettingsLinkRow(stringResource(R.string.caps_title), stringResource(R.string.caps_settings_summary), onOpenCapabilities,
                Modifier.testTag("settings-open-capabilities"))
        }
        if ("hardware" in visibleIds) settingsCard("hardware") { HardwareSettingsSummary(state.descriptor) }
        if ("modes" in visibleIds) settingsCard("modes") {
            SettingsSectionTitle(stringResource(R.string.settings_mode_availability))
            CaptureMode.entries.forEach { mode ->
                val gate = state.modeGates.getValue(mode)
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(modeLabel(mode), Modifier.weight(1f).padding(end = 16.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Text(gateLabel(gate), color = gateColor(gate), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if ("about" in visibleIds) settingsCard("about") {
            SettingsLinkRow(stringResource(R.string.about_title), stringResource(R.string.about_settings_summary), onOpenAbout)
        }
    }
    }
    }
}

/** A row that opens a full page of its own (About, camera capabilities). */
@Composable
private fun SettingsLinkRow(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // The card is the surface; the whole row opens the page and ends in a quiet chevron.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { SettingsRowLabel(title, description) }
        CineGlyph(CineIcon.CHEVRON_RIGHT, Muted, Modifier.size(20.dp))
    }
}

@Composable
private fun ProfessionalAudioSettings(
    state: CameraUiState,
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
) {
    val capabilities = state.audioCapabilities
    // The settings card is the surface; no second box inside it.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionTitle(stringResource(R.string.professional_audio))
        AudioListeningSettingsControls(state, settings, onSettingsChange, LocalAudioListeningActions.current)
        AudioEffectsSettingsStatus(state, settings)
        AudioMeterSettingsControls(settings, onSettingsChange)
        if (capabilities == null) {
            Text(stringResource(R.string.audio_measuring), color = Muted, fontSize = 14.sp)
            return@Column
        }
        if (capabilities.formats.isEmpty()) {
            Text(stringResource(R.string.audio_no_route), color = LocalCineColors.current.pending, fontSize = 14.sp)
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
            Text(stringResource(R.string.audio_pcm_rate, derived), Modifier.testTag("audio-pcm-rate"), color = Muted, fontSize = 14.sp)
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
        SettingsSectionTitle(stringResource(R.string.production_slate_title), help = stringResource(R.string.production_slate_help), helpTag = "slate-help")
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
        SettingsSectionTitle(stringResource(R.string.audio_meter_title), help = stringResource(R.string.audio_meter_help), helpTag = "audio-meter-settings-help")
        SettingsSwitchRow(stringResource(R.string.audio_meter_visible), options.visible,
            { onSettingsChange(settings.copy(audioMeter = options.copy(visible = it))) }, tag = "audio-meter-settings-visible")
        SettingsChips(stringResource(R.string.settings_mode), AudioMeterMode.entries, options.mode,
            label = { stringResource(audioMeterModeLabel(it)) },
            onSelect = { onSettingsChange(settings.copy(audioMeter = options.copy(mode = it))) }, tag = { "audio-meter-settings-mode-$it" })
        val referenceLabel = stringResource(R.string.audio_meter_reference, options.vuReferenceDbfs)
        Text(referenceLabel, Modifier.testTag("audio-meter-settings-reference-label"), color = MaterialTheme.colorScheme.onSurface)
        val referenceInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        CineSlider(options.vuReferenceDbfs.toFloat(), { onSettingsChange(settings.copy(audioMeter = options.copy(vuReferenceDbfs = it.roundToInt()))) },
            valueRange = -24f..-6f, steps = 17, interactionSource = referenceInteraction,
            thumb = { androidx.compose.material3.SliderDefaults.Thumb(referenceInteraction, thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 52.dp)) },
            track = { androidx.compose.material3.SliderDefaults.Track(it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("audio-meter-settings-reference").semantics { contentDescription = referenceLabel })
        val holdLabel = stringResource(R.string.audio_meter_hold, options.peakHoldMs)
        Text(holdLabel, Modifier.testTag("audio-meter-settings-hold-label"), color = MaterialTheme.colorScheme.onSurface)
        val holdInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        CineSlider(options.peakHoldMs.toFloat(), { onSettingsChange(settings.copy(audioMeter = options.copy(peakHoldMs = it.roundToInt()))) },
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
        SettingsSectionTitle(stringResource(R.string.audio_effect_title), help = stringResource(R.string.audio_effect_help), helpTag = "audio-effects-help")
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
        SettingsSectionTitle(stringResource(R.string.audio_listening_title), help = stringResource(R.string.audio_listening_help), helpTag = "audio-listening-help")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(enabledLabel, Modifier.weight(1f).padding(end = 12.dp), color = MaterialTheme.colorScheme.onSurface)
            Switch(checked = request.enabled,
                onCheckedChange = { onSettingsChange(settings.copy(audioListening = request.copy(enabled = it))) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("audio-listening-enable").semantics { contentDescription = enabledLabel })
        }
        Text(volumeLabel, Modifier.testTag("audio-listening-volume-label"), color = MaterialTheme.colorScheme.onSurface)
        CineSlider(value = request.volumePercent.toFloat(), onValueChange = {
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
        SettingsSectionTitle(stringResource(R.string.audio_gain_title), help = stringResource(R.string.audio_gain_help), helpTag = "audio-gain-help")
        SettingsSwitchRow(manualLabel, gain.enabled,
            { onSettingsChange(settings.copy(audioRecordingGain = gain.copy(enabled = it))) }, tag = "audio-gain-manual")
        Text(requestedLabel,
            Modifier.testTag("audio-gain-value"), color = MaterialTheme.colorScheme.onSurface)
        CineSlider(value = gain.decibels.toFloat(), valueRange = -24f..24f, steps = 47,
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
    // The card is the surface; the row adds no second box, and the gap keeps the switch off the text.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(8.dp))
            .toggleable(value = checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else Muted, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            summary?.let { Text(it, color = Muted, fontSize = 14.sp) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
internal fun modeLabel(mode: CaptureMode): String = when (mode) {
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
    // Red belongs to REC; a failed mode is a warning, like a pending one.
    ModeGateState.AVAILABLE -> VerifiedCyan
    ModeGateState.CANDIDATE -> MaterialTheme.colorScheme.onSurface
    ModeGateState.UNSUPPORTED -> Muted
    ModeGateState.FAILED -> LocalCineColors.current.pending
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
) = FocusPanel(state, binder, settings, onSettingsChanged, onClose)

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

@Preview(name = "Capture slots · stacked deck", widthDp = 600, heightDp = 160, showBackground = true, backgroundColor = 0xFF0B0D0E)
@Composable
private fun CaptureSlotsPreview() {
    val state = CameraUiState(selectedMode = CaptureMode.VIDEO, phase = CameraUiPhase.PREVIEWING)
    Column(Modifier.background(Panel).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CaptureSlotStrip(captureSlotModels(state, CaptureMode.VIDEO), CaptureSlot.ISO, {})
        CaptureModeButton(modeLabel(CaptureMode.VIDEO), {})
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
