/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam.capture

import kotlinx.coroutines.flow.first
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import com.librestatic.opencinecam.camera.AnalysisSuspension
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.librestatic.opencinecam.camera.ZoomAnchor
import com.librestatic.opencinecam.ui.viewfinder.ZoomAnchorBar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.service.CaptureService
import kotlin.math.abs
import kotlin.math.max
import androidx.compose.ui.unit.Dp
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.liveCameraState
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.settings.HistogramMode
import com.librestatic.opencinecam.usesLogGraph

@Composable
internal fun InstrumentStack(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    chromeVisible: Boolean,
    recording: Boolean,
    histogram: Boolean,
    histogramMode: HistogramMode,
    horizontal: Boolean,
    dedicatedPane: Boolean = false,
    /** Width (x) and height (y) in px of the lock keys' corner, which a row of instruments wraps under. */
    endObstacle: IntOffset? = null,
    obstacleAtStart: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state = liveCameraState(state)
    val analysisFresh = rememberScopeAnalysisFresh(state, settings.monitoring)
    // While recording, the recording HUD carries its own meter.
    // Off-speed (slow motion) takes are silent by design, so there is no microphone to meter.
    val showAudio = chromeVisible && !recording && settings.audioEnabled && settings.audioMeter.visible &&
        state.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG) &&
        !(state.selectedMode == CaptureMode.VIDEO && settings.videoOffSpeed)
    val showLogSource = chromeVisible && state.selectedMode.usesLogGraph
    val showZoom = chromeVisible && state.zoomSupported
    // Shown while the camera reports the boost running, whatever the chrome state: it changes the picture.
    val showLowLight = state.reportedLowLightBoost == true
    val showHistogram = histogram && analysisFresh && state.histogram.isNotEmpty()
    // A hinge split has no picture here: the preview pane's overlay says it instead.
    val showSuspension = !dedicatedPane && state.analysisSuspension != AnalysisSuspension.NONE
    // The lens lock's crop route and its lens-switch warning sit where the scopes notice does.
    val showLensNotice = lensLockNoticeText(state.lensLock) != null
    // So does what the session's graph lost (RAW, the scopes) when the mode or monitors want it.
    val graphNotice = graphFallbackNoticeText(state.selectedMode, settings, state.previewGraphLoss)
    val showNotice = showSuspension || showLensNotice || graphNotice != null
    if (!showAudio && !showLogSource && !showZoom && !showHistogram && !showNotice && !showLowLight) return
    BoxWithConstraints(modifier) {
        val histogramWidth = (maxWidth * .34f).coerceIn(112.dp, 240.dp)
        val stretchHistogram = dedicatedPane && horizontal && showHistogram
        // The notice goes under the zoom, so the lens anchors never move when it comes and goes.
        // A row has no width to spare for it: there it takes its own line under the row.
        val notice: @Composable () -> Unit = {
            val width = Modifier.widthIn(max = minOf(maxWidth, if (horizontal) 360.dp else 280.dp))
            if (showSuspension) AnalysisSuspensionNotice(state, width)
            if (showLensNotice) LensLockNotice(state.lensLock, width)
            if (graphNotice != null) GraphFallbackNotice(graphNotice, width)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FittingStack(horizontal, spacing = 8.dp, fillLast = stretchHistogram,
                obstacle = if (horizontal && !stretchHistogram) endObstacle else null, obstacleAtStart = obstacleAtStart,
                wrapCross = horizontal && showNotice, modifier = if (horizontal && showNotice) Modifier else Modifier.weight(1f)) {
                if (showLogSource) LogSourceBadge(state, settings, Modifier.widthIn(max = 260.dp))
                if (showLowLight) LowLightBoostBadge()
                if (showZoom) ZoomReadout(state, binder)
                if (showNotice && !horizontal) notice()
                if (showAudio) AudioMeterHud(state, binder, meterSettings = settings.audioMeter)
                if (showHistogram) HistogramGraph(state, settings.monitoring, histogramMode,
                    if (stretchHistogram) Modifier.fillMaxSize() else Modifier.width(histogramWidth).height(histogramWidth * .32f))
            }
            if (showNotice && horizontal) notice()
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
    // A row that reports only the height it uses, so something can follow it.
    wrapCross: Boolean = false,
    // A row only: a corner (width x, height y) at the end of the first line, or its start with
    // [obstacleAtStart], that items must not enter. An item reaching into it opens a second line under it.
    obstacle: IntOffset? = null,
    obstacleAtStart: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        // Unbounded: an instrument measures at its own size (no "1.0" over "×", no "M…" meter)
        // and is then left out below if it does not fit whole.
        val loose = Constraints()
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
        val corner = obstacle?.takeIf { horizontal }
        val lineStart = if (corner != null && obstacleAtStart) corner.x else 0
        var cursor = lineStart
        // The second line, opened under the corner once an item would reach into it.
        var lineTop = 0
        var firstLineHeight = 0
        placeables.forEach { item ->
            val main = if (horizontal) item.width else item.height
            val cross = if (horizontal) item.height else item.width
            val mainLimit = if (horizontal) constraints.maxWidth else constraints.maxHeight
            val crossLimit = if (horizontal) constraints.maxHeight else constraints.maxWidth
            val lineLimit = if (corner != null && lineTop == 0 && !obstacleAtStart) mainLimit - corner.x else mainLimit
            if (corner != null && lineTop == 0 && cursor + main > lineLimit && main <= mainLimit) {
                lineTop = maxOf(firstLineHeight, corner.y) + gap
                cursor = 0
            }
            val limit = if (corner != null && lineTop == 0 && !obstacleAtStart) mainLimit - corner.x else mainLimit
            if (cursor + main <= limit && cross <= crossLimit - lineTop) {
                positions += item to if (horizontal) IntOffset(cursor, lineTop) else IntOffset(0, cursor)
                cursor += main + gap
                if (lineTop == 0) firstLineHeight = maxOf(firstLineHeight, cross)
            }
        }
        val height = if (wrapCross) positions.maxOfOrNull { it.second.y + it.first.height } ?: 0 else constraints.maxHeight
        layout(constraints.maxWidth, height) {
            positions.forEach { (item, at) -> item.place(at) }
        }
    }
}

@Composable
private fun ZoomReadout(state: CameraUiState, binder: CaptureService.LocalBinder?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val lock = state.lensLock
        val lockedOption = lock.lockedOption
        if (lockedOption != null) {
            // Locked: the anchors are the lock options. The locked lens is highlighted; tapping it
            // returns to its native field of view, tapping another lens moves the lock there.
            ZoomAnchorBar(
                anchors = lock.options.map { ZoomAnchor(it.anchorRatio, it.focalLengthMm, it.physicalId) },
                activeRatio = lockedOption.anchorRatio,
                onSelect = { ratio ->
                    val option = lock.options.minBy { kotlin.math.abs(it.anchorRatio - ratio) }
                    if (option.physicalId == lockedOption.physicalId) binder?.setZoomRatio(option.anchorRatio)
                    else if (lensLockChangeAllowed(state)) binder?.setLensLock(option.physicalId)
                },
                locked = true,
            )
        // A single lens has nothing to switch to, so its lone anchor would only repeat the ratio.
        } else if (state.opticalAnchors.size > 1) ZoomAnchorBar(
            anchors = state.opticalAnchors,
            activeRatio = state.zoomEffectiveRatio ?: state.zoomRatio,
            onSelect = { ratio -> binder?.selectZoomAnchor(ratio) },
        )
        val ratioValue = state.zoomEffectiveRatio ?: state.zoomRatio
        // While locked only the locked lens' own anchor is optical; every other ratio is a crop.
        val opticalRatios = lockedOption?.let { listOf(it.anchorRatio) } ?: state.opticalAnchors.map { it.ratio }
        val isDigital = opticalRatios.none { (ratioValue - it).let { d -> d >= -0.05f && d <= 0.05f } }
        // On an anchor the highlighted anchor already states the ratio; the readout is for in-between.
        // It keeps its place while hidden, so a zoom passing an anchor never shifts the instruments under it.
        val readoutShown = (lockedOption == null && state.opticalAnchors.size <= 1) || isDigital
        Box(
            Modifier
                .then(if (readoutShown) Modifier.testTag("zoom-ratio") else Modifier.alpha(0f).clearAndSetSemantics {})
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
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
