/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam.capture

import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.LoadingIndicator
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import androidx.compose.ui.unit.IntOffset
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.hardware.camera2.CameraCharacteristics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.librestatic.opencinecam.ui.viewfinder.LocalChromeOpacity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.camera.label
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.calibration.LensCalibrationUiState
import com.librestatic.opencinecam.fold.FoldDisplayState
import com.librestatic.opencinecam.fold.FoldPane
import com.librestatic.opencinecam.fold.foldPanes
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.settings.HistogramMode
import com.librestatic.opencinecam.settings.ViewfinderScale
import com.librestatic.opencinecam.subject.InterviewOperatorControl
import com.librestatic.opencinecam.usesLogGraph

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
    val lensCalibrationFallback = remember { MutableStateFlow(LensCalibrationUiState()) }
    val lensCalibration by (binder?.lensCalibrationStates ?: lensCalibrationFallback).collectAsStateWithLifecycle()
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
            if (state.selectedMode.usesLogGraph) {
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
        val sideRails = fullChrome && family == CaptureLayoutFamily.SIDE_RAILS
        // The deck (or the side rails) slides away during a take, and the viewfinder grows into its space.
        val viewfinderExpansion by animateFloatAsState(
            if ((stacked || sideRails) && !overlayChrome && state.phase == CameraUiPhase.RECORDING) 1f else 0f,
            tween(RECORDING_VIEWFINDER_EXPANSION_MS, easing = FastOutSlowInEasing),
            label = "recording-viewfinder-expansion",
        )
        val reserveSpec: AnimationSpec<Float> = if (LocalReducedMotion.current) snap() else tween(FRAME_RESERVE_MS, easing = FastOutSlowInEasing)
        val reserveEnd by animateFloatAsState(frameReserve.end, reserveSpec, label = "frame-reserve-end")
        val reserveBottom by animateFloatAsState(frameReserve.bottom, reserveSpec, label = "frame-reserve-bottom")
        val previewPaneModifier = when {
            overlayChrome -> paneModifier(null)
            sideRails -> paneModifier(null)
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
            val targetFrame = if (sideRails && !overlayChrome) with(density) {
                // The pane's start padding is the rail and its end padding the column, mirrored right to left.
                val railPx = CAPTURE_RAIL_WIDTH_DP.dp.toPx()
                val columnPx = SIDE_COLUMN_WIDTH_DP.dp.toPx()
                railsPreviewViewport(paneWidth, paneHeight, if (rightToLeft) columnPx else railPx, if (rightToLeft) railPx else columnPx,
                    viewfinderExpansion, reserve, displayRatio, rightToLeft)
            } else reservedPreviewViewport(paneWidth, paneHeight, deckSpace, viewfinderExpansion, reserve, displayRatio, rightToLeft)
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
                state.gpuViewfinder || state.selectedMode.usesLogGraph,
                state.targetFps,
                descriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT && !state.gpuViewfinder && !state.selectedMode.usesLogGraph,
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
            // H hides the false-colour paint with the scopes; the setting stays on for when they return.
            val scopesConcealed = LocalOperatorActions.current?.scopes?.concealed == true
            MonitoringOverlay(
                state = state,
                options = if (scopesConcealed) settings.monitoring.copy(falseColorEnabled = false) else settings.monitoring,
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
            lensCalibration = lensCalibration,
            onLensCalibrationCancel = { binder?.cancelLensCalibration() },
            onLensCalibrationSave = { binder?.saveLensCalibration() },
            onLensCalibrationDiscard = { binder?.discardLensCalibration() },
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
    // Vote on the viewfinder layer only. Pinning the window's preferredDisplayModeId to the
    // camera rate held the whole UI at 30 Hz in photo and 24/30 fps video, so the chrome
    // animations dropped frames. A FIXED_SOURCE vote lets SurfaceFlinger pick a multiple of the
    // camera rate (120 Hz for 24 and 30 fps) and still raise the rate for touch and animation.
    fun voteForViewfinderRate() {
        val fps = currentTargetFps.toFloat()
        if (fps <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !view.holder.surface.isValid) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.holder.surface.setFrameRate(
                    fps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                view.holder.surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
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
