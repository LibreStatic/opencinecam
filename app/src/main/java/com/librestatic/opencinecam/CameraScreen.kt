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
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material3.MaterialTheme
import com.librestatic.opencinecam.ui.theme.CaptureTheme
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librestatic.opencinecam.service.CaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import com.librestatic.opencinecam.calibration.LensCalibrationContent
import com.librestatic.opencinecam.calibration.LensCalibrationUiState
import com.librestatic.opencinecam.capture.Amber
import com.librestatic.opencinecam.capture.CaptureSurface
import com.librestatic.opencinecam.capture.Graphite
import com.librestatic.opencinecam.capture.LocalOperatorActions
import com.librestatic.opencinecam.capture.Muted
import com.librestatic.opencinecam.capture.color
import com.librestatic.opencinecam.capture.rememberOperatorActions
import com.librestatic.opencinecam.diagnostics.CameraCapabilitiesScreen
import com.librestatic.opencinecam.diagnostics.StorageBenchmarkScreen
import com.librestatic.opencinecam.diagnostics.benchmarkRequiredBytesPerSecond
import com.librestatic.opencinecam.fold.FoldDisplayState
import com.librestatic.opencinecam.fold.LocalFoldDisplayCoordinator
import com.librestatic.opencinecam.gallery.MediaCatalogScreen
import com.librestatic.opencinecam.onboarding.OnboardingScreen
import com.librestatic.opencinecam.onboarding.OnboardingStore
import com.librestatic.opencinecam.onboarding.SplashHandoff
import com.librestatic.opencinecam.settings.AboutScreen
import com.librestatic.opencinecam.settings.LocalAudioListeningActions
import com.librestatic.opencinecam.settings.SettingsRepositories
import com.librestatic.opencinecam.settings.SettingsScreen
import com.librestatic.opencinecam.settings.normalizedFor
import com.librestatic.opencinecam.ui.LocalAppHaptics
import com.librestatic.opencinecam.ui.predictiveBackPreview
import com.librestatic.opencinecam.ui.rememberAppHaptics
import com.librestatic.opencinecam.ui.rememberPermissionAccess
import com.librestatic.opencinecam.ui.rememberPredictiveBack

@Composable
fun CameraRootScreen(splash: SplashHandoff = SplashHandoff(onScreen = false), onReady: () -> Unit = {}) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraAccess = rememberPermissionAccess(Manifest.permission.CAMERA) { permissionGranted = it }
    val binder = rememberCaptureServiceBinder(permissionGranted)
    var audioPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val audioAccess = rememberPermissionAccess(Manifest.permission.RECORD_AUDIO) {
        audioPermissionGranted = it
        binder?.refreshAudioCapabilities()
    }
    val onboardingStore = remember(context) { OnboardingStore(context) }
    val navigation = rememberCameraRootNavigation(onboardingStore)
    val onboardingDone = navigation.onboardingDone
    val fallbackState = remember { MutableStateFlow(CameraUiState()) }
    val stateFlow = binder?.cameraStates ?: fallbackState
    val liveState = stateFlow.collectAsStateWithLifecycle()
    // Without the per-sample fields, so a sample alone recomposes nothing here; leaves read them through LocalLiveCameraState.
    val state by remember(liveState) { derivedStateOf(structuralEqualityPolicy()) { liveState.value.withoutLiveSamples() } }
    val settingsRepository = remember(context) { SettingsRepositories.get(context) }
    val settings by settingsRepository.states.collectAsStateWithLifecycle()
    val lensCalibrationFallback = remember { MutableStateFlow(LensCalibrationUiState()) }
    val lensCalibration by (binder?.lensCalibrationStates ?: lensCalibrationFallback).collectAsStateWithLifecycle()
    // A normal start keeps the splash until this: the viewfinder shows its first frame (or the
    // camera failed), or there is a screen other than the viewfinder to show first.
    val readyForSplash = !permissionGranted || !onboardingDone ||
        state.phase !in setOf(CameraUiPhase.PREPARING, CameraUiPhase.OPENING, CameraUiPhase.READY)
    LaunchedEffect(readyForSplash) { if (readyForSplash) onReady() }
    val foldDisplays = LocalFoldDisplayCoordinator.current
    val foldFallback = remember { MutableStateFlow(FoldDisplayState()) }
    val foldState by (foldDisplays?.states ?: foldFallback).collectAsStateWithLifecycle()
    // The cover display draws the audio meter, so it gets every sample, collected outside composition.
    LaunchedEffect(foldDisplays, liveState) { snapshotFlow { liveState.value }.collect { foldDisplays?.updateCameraState(it) } }
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
    val section = navigation.section
    val settingsPage = navigation.settingsPage
    val settingsStateHolder = rememberSaveableStateHolder()

    val sectionBack = rememberPredictiveBack(enabled = navigation.sectionBackEnabled) { navigation.backToCapture() }

    val pageBack = rememberPredictiveBack(enabled = navigation.pageBackEnabled) { navigation.backToSettingsHome() }

    LaunchedEffect(state.audioCapabilities) {
        state.audioCapabilities?.let { capabilities ->
            val normalized = settings.normalizedFor(capabilities)
            if (normalized != settings) {
                settingsRepository.set(normalized)
            }
        }
    }
    LaunchedEffect(state.selectedMode, state.targetVideoWidth, state.targetVideoHeight, state.targetFps) {
        val updated = settings.followingCameraSelection(state)
        if (updated != settings) {
            settingsRepository.set(updated)
        }
    }

    @Composable
    fun MainContent() {

    if (!permissionGranted) {
        PermissionScreen(blocked = cameraAccess.blocked, onGrant = cameraAccess.request)
        return
    }

    val operatorActions = rememberOperatorActions(state, settings, binder, foldDisplays, foldState, section == AppSection.CAPTURE)
    val appHaptics = rememberAppHaptics(settings.haptics, state.phase == CameraUiPhase.RECORDING)
    CompositionLocalProvider(LocalLiveCameraState provides liveState, LocalOperatorActions provides operatorActions,
        LocalAppHaptics provides appHaptics,
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
                onOpenMedia = { binder?.cancelSelfTimer(); navigation.openMedia() },
                onOpenSettings = {
                    binder?.cancelSelfTimer()
                    navigation.openSettings()
                },
            )
            }
        } else {
            // The capture surface binds the camera, so it is not composed under the gesture: the shrinking shell reveals the background.
            Box(Modifier.fillMaxSize().predictiveBackPreview(sectionBack).background(MaterialTheme.colorScheme.background)) {
            AppShell(section, foldState.hinge, onSelect = navigation::select) {
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
                        Box(Modifier.fillMaxSize().predictiveBackPreview(pageBack).background(MaterialTheme.colorScheme.surface)) {
                        AboutScreen(
                            onBack = navigation::backToSettingsHome,
                            onReplayTour = navigation::replayTour,
                        )
                        }
                    } else if (settingsPage == SettingsPage.CAPABILITIES) {
                        Box(Modifier.fillMaxSize().predictiveBackPreview(pageBack).background(MaterialTheme.colorScheme.surface)) {
                        CameraCapabilitiesScreen(
                            cameras = state.cameras,
                            activeCameraId = state.selectedCameraId,
                            onBack = navigation::backToSettingsHome,
                            liveState = state,
                        )
                        }
                    } else if (settingsPage == SettingsPage.STORAGE_BENCHMARK) {
                        Box(Modifier.fillMaxSize().predictiveBackPreview(pageBack).background(MaterialTheme.colorScheme.surface)) {
                        StorageBenchmarkScreen(
                            requiredBytesPerSecond = benchmarkRequiredBytesPerSecond(state.selectedMode, settings, state.targetFps,
                                state.activeVideoProfile?.constrainedHighSpeed == true),
                            onBack = navigation::backToSettingsHome,
                        )
                        }
                    } else if (settingsPage == SettingsPage.LENS_CALIBRATION) {
                        Box(Modifier.fillMaxSize().predictiveBackPreview(pageBack).background(MaterialTheme.colorScheme.surface)) {
                        LensCalibrationContent(
                            state = lensCalibration,
                            settings = settings,
                            onSettingsChange = settingsRepository::set,
                            onBack = navigation::backToSettingsHome,
                            onStart = {
                                // The run happens on the viewfinder: start it, then show the capture screen.
                                binder?.startLensCalibration()
                                navigation.showCaptureForCalibration()
                            },
                            onDelete = { binder?.deleteLensCalibration() },
                        )
                        }
                    } else {
                        settingsStateHolder.SaveableStateProvider("settings") {
                        SettingsScreen(
                            state = state,
                            settings = settings,
                            audioPermissionGranted = audioPermissionGranted,
                            audioPermissionBlocked = audioAccess.blocked,
                            onRequestAudioPermission = audioAccess.request,
                            onOpenAbout = { navigation.openSettingsPage(SettingsPage.ABOUT) },
                            onSettingsChange = settingsRepository::set,
                            onApplyPreset = binder?.let { owner -> { preset -> owner.applyPreset(preset) } },
                            onOpenCapabilities = { navigation.openSettingsPage(SettingsPage.CAPABILITIES) },
                            onOpenStorageBenchmark = { navigation.openSettingsPage(SettingsPage.STORAGE_BENCHMARK) },
                            onOpenLensCalibration = { navigation.openSettingsPage(SettingsPage.LENS_CALIBRATION) },
                            onSetLensLock = binder?.let { owner -> { id -> owner.setLensLock(id) } },
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
            onFinished = navigation::finishTour,
            hinge = foldState.hinge,
            replay = navigation.tourReplay,
        )
    }
    }

}

private const val HandoffWaitMillis = 1_500L

/** The instruments' first row (the 48 dp zoom anchors) plus a gap of about 8 dp: the calibration card stays below it. */
internal val CALIBRATION_CARD_TOP_CLEARANCE = 64.dp

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
private fun PermissionScreen(blocked: Boolean, onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Graphite).windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.camera_permission_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.camera_permission_body), color = Muted)
        if (blocked) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.permission_blocked_explanation), color = Muted)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = MaterialTheme.colorScheme.onPrimary)) {
            Text(stringResource(if (blocked) R.string.permission_open_settings else R.string.grant_camera))
        }
    }
}
