/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam.capture

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextAlign
import android.content.Context
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import com.librestatic.opencinecam.camera.ZoomMath
import com.librestatic.opencinecam.camera.ZoomLensSwitchMode
import com.librestatic.opencinecam.ui.viewfinder.ZoomRocker
import com.librestatic.opencinecam.ui.viewfinder.LocalChromeOpacity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.camera.CaptureFrameRate
import com.librestatic.opencinecam.camera.TapFocusState
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ShutterUnit
import com.librestatic.opencinecam.camera.label
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.max
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.ui.unit.Dp
import com.librestatic.opencinecam.CALIBRATION_CARD_TOP_CLEARANCE
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.calibration.LensCalibrationCard
import com.librestatic.opencinecam.calibration.LensCalibrationPhase
import com.librestatic.opencinecam.calibration.LensCalibrationUiState
import com.librestatic.opencinecam.calibration.labelRes
import com.librestatic.opencinecam.fold.FoldDisplaySettings
import com.librestatic.opencinecam.fold.labelRes
import com.librestatic.opencinecam.settings.AccumulationCaptureProgress
import com.librestatic.opencinecam.settings.BracketCaptureProgress
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.settings.HistogramMode
import com.librestatic.opencinecam.settings.ModeSelectorStyle
import com.librestatic.opencinecam.settings.PresetQuickAccess
import com.librestatic.opencinecam.settings.ViewfinderScale
import com.librestatic.opencinecam.settings.videoProjectRate
import com.librestatic.opencinecam.subject.OutOfFrameHudChip
import com.librestatic.opencinecam.ui.CineIcon
import com.librestatic.opencinecam.ui.ContextPanePlacement
import com.librestatic.opencinecam.ui.HapticEvent
import com.librestatic.opencinecam.ui.LocalAppHaptics
import com.librestatic.opencinecam.ui.zoomHapticFor
import com.librestatic.opencinecam.usesLogGraph

internal enum class ControlDial { RESOLUTION, FPS, INT, ISO, SHUTTER, FOCUS, WB, EV }

/** What the context pane holds: one control's dial (opened from its slot), the modes, or the monitoring toggles. */
private sealed interface CapturePane {
    data class Control(val dial: ControlDial) : CapturePane
    data object Modes : CapturePane
    data object Monitor : CapturePane
    data object Displays : CapturePane
}

/** A pane the capture chrome opens with. Only the headless renders set it; the app always starts closed. */
internal enum class CaptureInitialPane { WHITE_BALANCE, FOCUS, MONITOR, MODES, MODE_SHEET, DISPLAYS }

private fun CaptureInitialPane.pane(): CapturePane? = when (this) {
    CaptureInitialPane.WHITE_BALANCE -> CapturePane.Control(ControlDial.WB)
    CaptureInitialPane.FOCUS -> CapturePane.Control(ControlDial.FOCUS)
    CaptureInitialPane.MONITOR -> CapturePane.Monitor
    CaptureInitialPane.MODES -> CapturePane.Modes
    CaptureInitialPane.MODE_SHEET -> null
    CaptureInitialPane.DISPLAYS -> CapturePane.Displays
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
    // The lens calibration run (PLAN-070): its card shows while the phase is not IDLE.
    lensCalibration: LensCalibrationUiState = LensCalibrationUiState(),
    onLensCalibrationCancel: () -> Unit = {},
    onLensCalibrationSave: () -> Unit = {},
    onLensCalibrationDiscard: () -> Unit = {},
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
    var controlDeckHeightPx by remember { mutableIntStateOf(0) }
    var dockedPaneHeightPx by remember { mutableIntStateOf(0) }
    // The lock toggles (and in the stacked layouts the F-keys under them) at the frame's top end.
    var topEndClusterWidthPx by remember { mutableIntStateOf(0) }
    var keyColumnShownPx by remember { mutableIntStateOf(0) }
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
    val zoomHaptics = LocalAppHaptics.current
    val zoomState by rememberUpdatedState(state)
    LaunchedEffect(Unit) {
        // Only the transition into a result; the state already held on entry plays nothing.
        snapshotFlow { zoomState.tapFocusState }.drop(1).collect {
            when (it) {
                TapFocusState.FOCUSED -> zoomHaptics.perform(HapticEvent.FocusConfirmed)
                TapFocusState.NOT_FOCUSED -> zoomHaptics.perform(HapticEvent.FocusFailed)
                else -> Unit
            }
        }
    }
    LaunchedEffect(Unit) {
        var previous = zoomState.zoomRatio
        snapshotFlow { zoomState.zoomRatio }.collect { now ->
            val s = zoomState
            zoomHapticFor(previous, now, zoomSectorAnchors(s).map { it.ratio }, s.zoomMinRatio, s.zoomMaxRatio)?.let(zoomHaptics::perform)
            previous = now
        }
    }
    fun togglePane(target: CapturePane) {
        pane = if (pane == target) null else target
        modeSheet = false
    }

    // The real-time rate Video had before slow motion raised it, restored when Video is chosen again.
    var normalVideoFps by rememberSaveable { mutableIntStateOf(30) }
    val modeSelection = ModeSelection(displayedCaptureMode(state.selectedMode, settings.videoOffSpeed)) { mode ->
        val plan = planModeSwitch(mode, state, settings, normalVideoFps)
        normalVideoFps = plan.normalVideoFps
        plan.settings?.let { updated ->
            onSettingsChanged(updated)
            binder?.applySettings(updated)
        }
        plan.pipelineMode?.let { binder?.selectMode(it) } ?: plan.targetFps?.let { binder?.selectTargetFps(it) }
    }
    // A cutout can leave the safe area shorter than the status bar; the top bar starts below both.
    val extraTop = statusBarClearance()
    val reducedMotion = LocalReducedMotion.current

    // The modes alone; resolution is the RES slot's.
    val modesContent: @Composable (onClose: (() -> Unit)?) -> Unit = { onClose ->
        val displayedMode = modeSelection.displayed
        val choices = visibleCaptureModes(state.modeGates, displayedMode).map { mode ->
            val gate = state.modeGates.getValue(mode)
            val experimental = showsExperimentalGate(mode, gate, state.descriptor?.hasVerifiedOpenCineLog == true)
            CaptureModeChoice(
                mode = mode,
                label = modeLabel(mode),
                gateLabel = if (gate != ModeGateState.AVAILABLE || experimental) gateLabel(gate, experimental) else null,
                gateColor = gateColor(gate, experimental),
                enabled = CameraUiState.isModeSelectable(gate),
                selected = mode == displayedMode,
            )
        }
        CaptureModeContent(
            choices = choices,
            recording = recording,
            onSelect = modeSelection.select,
            onClose = onClose,
        )
    }
    val currentPinchState by rememberUpdatedState(state)
    val currentPinchSettings by rememberUpdatedState(settings)
    val currentPinchBinder by rememberUpdatedState(binder)
    // The running ratio of the pinch under the fingers; not state, nothing draws it.
    val pinchRatio = remember { mutableFloatStateOf(1f) }
    CompositionLocalProvider(LocalModeSelection provides modeSelection) {
    Box(
        Modifier
            .fillMaxSize()
            // Each pinch starts from the ratio shown at its first step and then follows the fingers
            // from its own running value, never from the camera's lagging report. It sits on the chrome's
            // root rather than the viewfinder surface: Compose hit-tests each finger on its own, and a
            // second finger that landed on a control above the surface never reached a detector there,
            // so the pinch read as a tap and refocused.
            .pinchToZoom(previewGesturesEnabled && !state.captureControlsLocked && state.zoomSupported) { first, zoom ->
                val shown = currentPinchState
                if (first) pinchRatio.floatValue = shown.zoomRatio
                val base = pinchRatio.floatValue
                val candidate = base * zoom
                val range = if (currentPinchSettings.zoomLensSwitchMode == ZoomLensSwitchMode.MANUAL_PRESETS &&
                    zoomSectorAnchors(shown).size > 1) {
                    ZoomMath.sectorBounds(
                        base,
                        zoomSectorAnchors(shown),
                        currentPinchSettings.zoomLensSwitchMode,
                        shown.zoomMinRatio,
                        shown.zoomMaxRatio,
                        direction = candidate - base,
                    )
                } else {
                    shown.zoomMinRatio..shown.zoomMaxRatio
                }
                pinchRatio.floatValue = ZoomMath.coerce(candidate, range)
                currentPinchBinder?.setZoomRatio(pinchRatio.floatValue)
            },
    ) {
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
        // Compact portrait overlays its sheets on the frame instead of shrinking it, so they stop
        // lower there: the frame above stays large enough to aim by.
        val dockCap = minOf(
            maxHeight * if (compact) COMPACT_SHEET_MAX_FRACTION else DOCKED_SHEET_MAX_FRACTION,
            if (stacked) 400.dp else maxHeight,
            maxHeight - deckHeight - topBar - 8.dp,
        ).coerceAtLeast(160.dp)
        // Context panes (modes, ISO, EV, WB, focus...) dock under the frame, as on a phone. A
        // stacked window docks the scopes beside the frame or under it, by which leaves the larger frame.
        val paneDock = PaneDock.BOTTOM
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
            .let { if (compact) minOf(it, maxHeight * COMPACT_SHEET_MAX_FRACTION) else it }
        val targetReserve = when {
            overlay || hinge || inspector -> CaptureFrameReserve.None
            paneShown && sideRails -> CaptureFrameReserve(end = (SIDE_PANE_WIDTH_DP - SIDE_COLUMN_WIDTH_DP).dp.px())
            dockedEndPane -> CaptureFrameReserve(end = sidePaneWidth.px())
            // A phone in portrait has no height to give: its sheets and scope tray overlay the frame.
            compact -> CaptureFrameReserve.None
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
                val leftPx = if (rightToLeft) endColumnPx else startRailPx
                val rightPx = if (rightToLeft) startRailPx else endColumnPx
                // The rails slide away during a take and the frame grows into their room; the inspector stays.
                val inner = railsPreviewViewport((width - startRailPx - endColumnPx).coerceAtLeast(0f), height, leftPx, rightPx,
                    if (sideRails) viewfinderExpansion else 0f, reserve, ratio, rightToLeft)
                inner.copy(left = inner.left + leftPx)
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
                .pointerInput(previewGesturesEnabled, state.captureControlsLocked, recording, chromeVisible, ratio, settings.tapExposureMeteringEnabled, state.phase,
                    previewLeft, previewTop, previewWidth, previewHeight) {
                    detectTapGestures { position ->
                        if (recording) manualReveal = true
                        // During a take the tap that brings the chrome back must not refocus the shot.
                        if (recording && !chromeVisible) return@detectTapGestures
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

        // The stacked F-key column stands over the frame's end edge and stops above whatever docks
        // on the deck under it (a pane that reaches the end edge, or the scope tray). The zoom rocker
        // sits at mid-height under it; when the keys need more room than that leaves, the rocker
        // drops to the bottom of the frame so every key shows without scrolling.
        val paneEdgeGap = if (compact) 0.dp else ((maxWidth - DOCKED_SHEET_MAX_WIDTH_DP.dp) / 2).coerceAtLeast(0.dp)
        val dockedUnderKeys = when {
            dockedBottomPane && paneEdgeGap < CAPTURE_RAIL_WIDTH_DP.dp -> with(density) { dockedPaneHeightPx.toDp() }
            scopeTray -> scopeTrayHeight
            else -> 0.dp
        }
        val keyColumnFloor = maxHeight - deckHeight - dockedUnderKeys - 14.dp
        val rockerHeight = 120.dp
        val keysAboveMidRocker = maxHeight / 2 - rockerHeight / 2 - 12.dp
        val keysAboveLowRocker = keyColumnFloor - rockerHeight - 12.dp
        var keyColumnNaturalPx by remember { mutableIntStateOf(0) }
        // Compact portrait has no room for both the rocker and the F-keys above a docked sheet or
        // tray: the rocker steps aside (pinch still zooms) and the keys take its height.
        val rockerShown = state.zoomSupported && !(compact && dockedUnderKeys > 0.dp)
        val zoomRockerLow = stackedFamily && !hinge && state.zoomSupported &&
            with(density) { keyColumnNaturalPx.toDp() } > keysAboveMidRocker - topBar - 6.dp &&
            keysAboveLowRocker > keysAboveMidRocker

        // Zoom chrome: anchor bar + ratio indicator are part of chrome; the lateral rocker stays
        // visible during recording even when the rest of the chrome hides.
        if (rockerShown) {
            ZoomRocker(
                onStep = { offset, deltaSeconds ->
                    val speed = ZoomMath.rockerSpeedOctavesPerSecond(offset)
                    if (speed != 0f) {
                        val range = if (settings.zoomLensSwitchMode == ZoomLensSwitchMode.MANUAL_PRESETS &&
                            zoomSectorAnchors(state).size > 1) {
                            ZoomMath.sectorBounds(
                                state.zoomRatio,
                                zoomSectorAnchors(state),
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
                modifier = (if (zoomRockerLow) Modifier.align(Alignment.BottomEnd).padding(bottom = maxHeight - keyColumnFloor)
                    else Modifier.align(Alignment.CenterEnd))
                    .padding(end = endOccupied + 8.dp)
                    .width(28.dp)
                    .height(rockerHeight),
            )
        }

        if (chromeVisible) {
            if (stackedFamily) {
                // The F-keys stand over the frame's end edge under the lock toggles, stop above the
                // zoom rocker and the docked deck, and scroll rather than run into either.
                val keyColumnMax = (if (rockerShown) minOf(if (zoomRockerLow) keysAboveLowRocker else keysAboveMidRocker, keyColumnFloor)
                    else keyColumnFloor) - topBar
                // Whole 48 dp keys only (6 dp apart, under the 48 dp lock row): a key cut by the
                // column's edge would read as one hidden under the pane.
                val keyPitch = 48.dp + 6.dp
                val keyColumnCap = keyColumnCapDp(
                    naturalDp = with(density) { keyColumnNaturalPx.toDp() }.value,
                    availableDp = (keyColumnMax - 6.dp).value,
                    pitchDp = keyPitch.value,
                    lockRowDp = 48f,
                )
                val keyScroll = rememberScrollState()
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = endOccupied + 8.dp, top = topBar + 6.dp)
                        .onSizeChanged { topEndClusterWidthPx = it.width; keyColumnShownPx = it.height }
                        .then(if (keyColumnCap != null) Modifier.heightIn(max = keyColumnCap.dp) else Modifier)
                        .verticalScroll(keyScroll),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Measured at its natural height (the scroll leaves it unbounded) to place the rocker.
                    Column(
                        Modifier.onSizeChanged { keyColumnNaturalPx = it.height },
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LockToggles(state = state, binder = binder, afLockBehavior = settings.afLockBehavior, settings = settings, onSettingsChanged = onSettingsChanged)
                        // A hinge split has no picture under its chrome: its F-keys go in the deck.
                        if (!hinge) OperatorButtonColumn(state, settings)
                    }
                }
                // Keys cut off by a docked sheet scroll; ▾ under the column says more are below
                // and steps the column one key down.
                if (keyScroll.canScrollForward) {
                    val scope = rememberCoroutineScope()
                    val stepPx = keyPitch.px()
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = endOccupied + 8.dp, top = topBar + 6.dp + with(density) { keyColumnShownPx.toDp() })
                            .size(48.dp, 20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Panel)
                            .clickable { scope.launch { keyScroll.animateScrollBy(stepPx) } }
                            .testTag("capture-keys-more"),
                        contentAlignment = Alignment.Center,
                    ) { Text("▾", color = Amber, fontSize = 14.sp, maxLines = 1) }
                }
                CaptureTopBar(
                    state = state,
                    binder = binder,
                    onOpenMedia = onOpenMedia,
                    onOpenSettings = onOpenSettings,
                    settings = settings,
                    onSettingsChanged = onSettingsChanged,
                    onOpenDisplays = { togglePane(CapturePane.Displays) },
                    modifier = Modifier.align(Alignment.TopCenter),
                    showThumbnail = !compact,
                    height = topBar,
                )
            } else LockToggles(
                state = state,
                binder = binder,
                afLockBehavior = settings.afLockBehavior,
                settings = settings,
                onSettingsChanged = onSettingsChanged,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = endOccupied + 8.dp, top = 8.dp)
                    .onSizeChanged { topEndClusterWidthPx = it.width },
            )
        }

        // Every viewfinder instrument lives in one measured stack on the visible frame, so the LOG
        // badge, zoom, microphone meter and histogram never land on each other, on the black bands
        // around the picture, or under the chrome.
        val recordingHudTop = when {
            !chromeVisible || sideRails || inspector -> 0.dp
            state.selectedMode.usesLogGraph -> topBar + 26.dp
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
        val instrumentsHorizontal = if (hinge) landscape else rightBound - leftBound > bottomBound - topBound
        // The lock keys stand at the top end of the frame. A row of instruments that would run under
        // them wraps to a line below instead (anchors, meter and histogram beside four lock keys).
        val lockRowObstacle = if (chromeVisible && instrumentsHorizontal && topEndClusterWidthPx > 0) {
            val clusterTop = (if (stackedFamily) topBar + 6.dp else 8.dp).px()
            val clusterBottom = clusterTop + 48.dp.px()
            val clusterInner = endChrome + 8.dp.px() + topEndClusterWidthPx + 8.dp.px()
            val main = if (rightToLeft) clusterInner - instrumentLeft else instrumentRight - (width - clusterInner)
            val cross = clusterBottom - instrumentTop
            if (main > 0f && cross > 0f) IntOffset(main.roundToInt(), cross.roundToInt()) else null
        } else null
        InstrumentStack(
            state = state,
            binder = binder,
            settings = settings,
            chromeVisible = chromeVisible,
            recording = recording,
            histogram = histogramShown,
            histogramMode = histogramMode,
            horizontal = instrumentsHorizontal,
            endObstacle = lockRowObstacle,
            obstacleAtStart = rightToLeft,
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
        val slots = captureSlotModels(state, displayedMode, settings.videoProjectRate)
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
        val scopes: @Composable (Modifier) -> Unit = { modifier ->
            val scopeFresh = rememberScopeAnalysisFresh(state, monitoring)
            // No histogram tab: the histogram keeps its own place in the instrument stack.
            ProfessionalScopesPanel(state, monitoring, scopeFresh, modifier, expanded = scopesExpanded,
                onExpandedChange = { scopesExpanded = it }, onClose = { scopeVisibility.hide() },
                tab = scopeVisibility.tab, onTabChange = { scopeVisibility.tab = it })
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
                CapturePane.Displays -> {
                    val displaysScroll = rememberScrollState()
                    Column(Modifier.verticalScroll(displaysScroll).testTag("fold-displays-pane"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CapturePaneHeader(stringResource(R.string.fold_settings_title), { pane = null })
                        FoldDisplaySettings(state, settings, onSettingsChanged, inCapturePane = true, scroll = displaysScroll)
                    }
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
                if (hinge) OperatorButtonRow(state, settings)
                presets()
                CaptureHeldSlots(state, unlock, Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { CaptureSlotStrip(slots, activeSlot, onSlot) }
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
                if (hinge) OperatorButtonRow(state, settings)
                presets()
                CaptureHeldSlots(state, unlock, Modifier.align(Alignment.CenterHorizontally).widthIn(max = 560.dp)) {
                    CaptureSlotStrip(slots, activeSlot, onSlot)
                }
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
                    onOpenDisplays = { togglePane(CapturePane.Displays) },
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
                // Two columns: the five slots, and beside them the mode, the status and the shutter,
                // which is held at the bottom so it never scrolls away.
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CaptureDimmedWhileHeld(state, Modifier.width(96.dp).fillMaxHeight()) {
                        CaptureSlotColumn(slots, activeSlot, onSlot, Modifier.fillMaxSize())
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CaptureHeldSlots(state, unlock, Modifier.fillMaxWidth(), HoldStyle.PILL) {
                            CaptureModeButton(modeName, { togglePane(CapturePane.Modes) }, Modifier.fillMaxWidth())
                        }
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
                        CaptureModeButton(modeName, { togglePane(CapturePane.Modes) }, Modifier.fillMaxWidth())
                        CaptureHeldSlots(state, unlock, Modifier.fillMaxWidth()) { CaptureSlotRows(slots, activeSlot, onSlot) }
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

        // Over the viewfinder, above the deck: a run needs the scene in view, and the card is inert to touches.
        // Its end edge keeps the instruments' end inset, so it stops short of the zoom rocker and, in the
        // stacked layouts, the F-key column standing over the frame's end edge.
        if (lensCalibration.phase != LensCalibrationPhase.IDLE) LensCalibrationCard(
            state = lensCalibration,
            onCancel = onLensCalibrationCancel,
            onSave = onLensCalibrationSave,
            onDiscard = onLensCalibrationDiscard,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(
                    bottom = deckHeight + (if (scopeTray) scopeTrayHeight else 0.dp) + 8.dp,
                    start = (if (sideRails || inspector) CAPTURE_RAIL_WIDTH_DP.dp else 0.dp) + 12.dp,
                    end = endOccupied + with(density) { instrumentEndInset.toDp() },
                )
                .widthIn(max = 420.dp).fillMaxWidth(),
            // Its top stops under the instruments' first row (the zoom anchors and the lock keys
            // beside them), so a tall result scrolls instead of running over them.
            maxHeight = minOf(
                maxHeight - deckHeight - topBar - 24.dp,
                with(density) { (height - instrumentTop).toDp() } - CALIBRATION_CARD_TOP_CLEARANCE -
                    deckHeight - (if (scopeTray) scopeTrayHeight else 0.dp) - 8.dp,
            ).coerceAtLeast(160.dp),
        )
    }
    CaptureModeSheet(visible = modeSheet, onDismiss = { modeSheet = false }) { modesContent { modeSheet = false } }
    }
}
}

private fun CaptureSlot.dial(): ControlDial = when (this) {
    CaptureSlot.RESOLUTION -> ControlDial.RESOLUTION
    CaptureSlot.FPS -> ControlDial.FPS
    CaptureSlot.INTERVAL -> ControlDial.INT
    CaptureSlot.SHUTTER -> ControlDial.SHUTTER
    CaptureSlot.ISO -> ControlDial.ISO
    CaptureSlot.EV -> ControlDial.EV
    CaptureSlot.WB -> ControlDial.WB
    CaptureSlot.FOCUS -> ControlDial.FOCUS
}

private fun ControlDial.slot(): CaptureSlot = when (this) {
    ControlDial.RESOLUTION -> CaptureSlot.RESOLUTION
    ControlDial.FPS -> CaptureSlot.FPS
    ControlDial.INT -> CaptureSlot.INTERVAL
    ControlDial.SHUTTER -> CaptureSlot.SHUTTER
    ControlDial.ISO -> CaptureSlot.ISO
    ControlDial.EV -> CaptureSlot.EV
    ControlDial.WB -> CaptureSlot.WB
    ControlDial.FOCUS -> CaptureSlot.FOCUS
}

/**
 * What the RES slot offers in [mode]: video sizes where the size picks the profile, the sizes with a
 * rate faster than the project rate in slow motion, still sizes in photo modes. RAW photo and the experimental modes
 * offer at most their one size.
 */
internal fun CameraUiState.resolutionChoices(mode: CaptureMode, projectRate: CaptureFrameRate): List<Pair<Int, Int>> = when (mode) {
    in CameraUiState.resolutionProfileModes -> availableVideoSizes
    CaptureMode.SLOW_MOTION -> slowMotionSizes(availableVideoProfiles.map { it.toSpec() }, projectRate).let { fast ->
        availableVideoSizes.filter { it in fast }
    }
    in CameraUiState.photoResolutionModes -> availableStillSizes
    else -> emptyList()
}

/** The RES slot value: "4K", "1080p" or "3.4K" for video, "12 MP" for stills. */
private fun CameraUiState.resolutionName(mode: CaptureMode): String =
    if (mode in CameraUiState.photoResolutionModes) activeStillSize?.let { (w, h) -> megapixelName(w, h) } ?: "—"
    else resolutionShortName(targetVideoWidth, targetVideoHeight)

/** The six slots of [mode] with their current values, and why any of them cannot be driven now. */
@Composable
private fun captureSlotModels(state: CameraUiState, mode: CaptureMode, projectRate: CaptureFrameRate): List<CaptureSlotModel> {
    val highSpeed = state.activeVideoProfile?.constrainedHighSpeed == true || state.activeLogProfile?.constrainedHighSpeed == true
    val exposureCaps = state.descriptor?.exposureCapabilities
    val manualIso = exposureCaps?.let { it.supports(ExposureMode.MANUAL) || it.supports(ExposureMode.ISO_PRIORITY) } == true
    val manualShutter = exposureCaps?.let { it.supports(ExposureMode.MANUAL) || it.supports(ExposureMode.SHUTTER_PRIORITY) } == true
    val auto = stringResource(R.string.auto_value)
    return captureSlots(mode).map { slot ->
        val reason = captureSlotUnavailableReason(slot, state.descriptor != null, highSpeed, manualIso, manualShutter,
            state.aeCompensationSupported, resolutionChoices = state.resolutionChoices(mode, projectRate).size,
            nightPhoto = mode == CaptureMode.PHOTO && state.nightExtensionRunning)
        // A high-speed session runs exposure itself: the slot reads AUTO, with HS beside its label.
        val autoHighSpeed = highSpeed && slot !in setOf(CaptureSlot.RESOLUTION, CaptureSlot.FPS, CaptureSlot.INTERVAL, CaptureSlot.EV)
        val value = when {
            autoHighSpeed -> auto
            else -> when (slot) {
                CaptureSlot.RESOLUTION -> state.resolutionName(mode)
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

@Preview(name = "Capture slots · stacked deck", widthDp = 600, heightDp = 160, showBackground = true, backgroundColor = 0xFF0B0D0E)
@Composable
private fun CaptureSlotsPreview() {
    val state = CameraUiState(selectedMode = CaptureMode.VIDEO, phase = CameraUiPhase.PREVIEWING)
    Column(Modifier.background(Panel).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CaptureSlotStrip(captureSlotModels(state, CaptureMode.VIDEO, CameraSettings().videoProjectRate), CaptureSlot.ISO, {})
        CaptureModeButton(modeLabel(CaptureMode.VIDEO), {})
    }
}
