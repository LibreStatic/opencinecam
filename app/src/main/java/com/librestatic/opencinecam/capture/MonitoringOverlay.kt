/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam.capture

import kotlinx.coroutines.flow.first
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.IntOffset
import com.librestatic.opencinecam.camera.HistogramSource
import com.librestatic.opencinecam.camera.MonitoringOptions
import com.librestatic.opencinecam.camera.monitoringSampleFresh
import com.librestatic.opencinecam.camera.MonitoringSignalDomain
import com.librestatic.opencinecam.camera.monitoringPreviewScale
import android.view.Surface
import android.os.SystemClock
import android.hardware.camera2.CameraCharacteristics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.label
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.max
import androidx.compose.animation.core.snap
import androidx.compose.ui.unit.Dp
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.liveCameraState
import com.librestatic.opencinecam.settings.HistogramMode
import com.librestatic.opencinecam.settings.compositionGridModeTitle
import com.librestatic.opencinecam.ui.CineGlyph
import com.librestatic.opencinecam.ui.CineIcon
import com.librestatic.opencinecam.ui.HapticEvent
import com.librestatic.opencinecam.ui.LocalAppHaptics
import com.librestatic.opencinecam.usesLogGraph

/**
 * A monitoring chip: symbol plus a short visible label, so no tool has to be memorised by letter.
 * With [cycleState] null it is an on/off switch whose ON state carries a check mark as well as the
 * amber container; otherwise it is a button that advances a mode and shows that mode as its label.
 */
@Composable
internal fun MonitorToggle(icon: CineIcon, label: String, description: String, enabled: Boolean, onClick: () -> Unit,
    cycleState: String? = null, modifier: Modifier = Modifier) {
    val on = enabled && cycleState == null
    val haptics = LocalAppHaptics.current
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
                if (cycleState != null) Modifier.clickable(role = Role.Button, onClick = { haptics.perform(HapticEvent.Select); onClick() })
                else Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = { haptics.perform(HapticEvent.Toggle); onClick() })
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
internal fun MonitoringToggleGrid(
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
    val state = liveCameraState(state)
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
        val gpuScale = if (state.gpuViewfinder || state.selectedMode.usesLogGraph)
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
        // The full chrome stacks the notice with its instruments, clear of its controls; a hinge
        // split's preview pane and the self-recording chrome have no such stack (72 dp clears the
        // latter's top row).
        if (drawScopesPanel) AnalysisSuspensionNotice(state, Modifier.align(Alignment.TopCenter).padding(top = 72.dp, start = 16.dp, end = 16.dp))
    }
}

/**
 * Whether the latest scope analysis is recent enough to draw. A new sample arrives as a new
 * [state]; the only other change is the current sample going stale, so this waits for exactly
 * that moment rather than ticking. A ticking clock recomposed the whole caller several times a second.
 */
@Composable
internal fun rememberScopeAnalysisFresh(state: CameraUiState, options: MonitoringOptions): Boolean {
    val state = liveCameraState(state)
    val sampleAtMs = state.analysisUpdatedAtMs
    val staleAfterMs = options.staleAfterMs
    var staleSampleAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sampleAtMs, staleAfterMs) {
        if (sampleAtMs <= 0) return@LaunchedEffect
        val remainingMs = sampleAtMs + staleAfterMs - SystemClock.elapsedRealtime()
        if (remainingMs >= 0) delay(remainingMs + 1)
        staleSampleAtMs = sampleAtMs
    }
    val fresh = staleSampleAtMs != sampleAtMs && monitoringSampleFresh(sampleAtMs, SystemClock.elapsedRealtime(), options)
    return state.scopeAnalysisLive(fresh) &&
        (state.monitoringScopes == null || state.monitoringScopes.options == options)
}

/** [tag] lets a second histogram (the scopes panel's) coexist with the capture instruments' one. */
@Composable
internal fun HistogramGraph(state: CameraUiState, options: MonitoringOptions, histogramMode: HistogramMode, modifier: Modifier = Modifier,
    tag: String = "histogram-graph") {
    val state = liveCameraState(state)
    Box(modifier.testTag(tag)) {
    Canvas(Modifier.matchParentSize()) {
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
    // Only the ISP sources are labelled: the frame histogram is the default and needs no badge.
    // The RGB graph is always the frame's.
    val sourceLabel = when {
        histogramMode != HistogramMode.LUMA -> null
        state.histogramSource == HistogramSource.ISP -> R.string.histogram_source_isp
        state.histogramSource == HistogramSource.ISP_BAYER -> R.string.histogram_source_isp_raw
        else -> null
    }
    if (sourceLabel != null) Text(stringResource(sourceLabel), color = Color.White.copy(alpha = .7f), fontSize = 8.sp,
        modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 3.dp, vertical = 1.dp).testTag("$tag-source"))
    }
}
