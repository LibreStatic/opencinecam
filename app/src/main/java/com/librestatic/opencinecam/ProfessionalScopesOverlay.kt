/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.hardware.camera2.CameraCharacteristics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.ui.theme.LocalCineColors

internal fun MonitorColor.composeColor(): Color = when (this) {
    MonitorColor.CYAN -> Color.Cyan; MonitorColor.YELLOW -> Color.Yellow
    MonitorColor.RED -> Color.Red; MonitorColor.GREEN -> Color.Green; MonitorColor.WHITE -> Color.White
}
internal fun FalseColorBand.composeColor(palette: FalseColorPalette): Color = when (this) {
    FalseColorBand.BLACK -> if (palette == FalseColorPalette.CLASSIC) Color(0xff6c42c1) else Color.Black
    FalseColorBand.SHADOW -> Color(0xff168cff)
    FalseColorBand.MID -> if (palette == FalseColorPalette.CLASSIC) Color(0xff40b66a) else Color.White
    FalseColorBand.HIGHLIGHT -> Color.Yellow
    FalseColorBand.CLIP -> Color.Red
}

/** Scope samples are only drawable while the engine is still producing them; a suspended engine leaves stale ones behind. */
internal fun CameraUiState.scopeAnalysisLive(fresh: Boolean): Boolean = fresh && analysisSuspension == AnalysisSuspension.NONE

/** Says why scopes stopped while analysis is suspended; the picture and any take keep running. */
@Composable internal fun AnalysisSuspensionNotice(state: CameraUiState, modifier: Modifier = Modifier) {
    if (state.analysisSuspension != AnalysisSuspension.THERMAL) return
    Text(stringResource(R.string.analysis_suspended_thermal), color = Color.Yellow,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.background(Color.Black.copy(alpha = .75f)).padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }.testTag("analysis-suspended-thermal"))
}

/** Operator only: never mutates the encoder, subject output, crop or stored image. */
@Composable internal fun ProfessionalScopeImage(state: CameraUiState, options: MonitoringOptions,
    fresh: Boolean, displayDegrees: Int, sourceWidth: Int, sourceHeight: Int, squeezeFactor: Float, modifier: Modifier = Modifier) {
    val live = state.scopeAnalysisLive(fresh)
    Canvas(modifier.testTag("monitoring-image-guides")) {
        val scale = if (state.gpuViewfinder || state.selectedMode == CaptureMode.LOG)
            monitoringPreviewScale(sourceWidth, sourceHeight, size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1),
                state.descriptor?.sensorOrientation ?: 0, displayDegrees,
                state.descriptor?.lensFacing == CameraCharacteristics.LENS_FACING_FRONT, squeezeFactor)
            else 1f to 1f
        val frame = state.monitoringScopes
        if (options.falseColorEnabled && live && frame != null && frame.options == options) {
            fun point(x: Float, y: Float): Offset {
                val p = monitoringDisplayPoint(x, y, frame.domain, state.descriptor?.sensorOrientation ?: 0,
                    displayDegrees, state.descriptor?.lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
                return Offset((.5f + (p.first - .5f) * scale.first) * size.width, (.5f + (p.second - .5f) * scale.second) * size.height)
            }
            frame.falseColorBands.forEachIndexed { index, band ->
                val a = point((index % 64) / 64f, (index / 64) / 36f)
                val b = point((index % 64 + 1) / 64f, (index / 64 + 1) / 36f)
                drawRect(band.composeColor(options.falseColorPalette).copy(alpha = options.opacityPercent / 100f),
                    Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)))
            }
        }
        val guide = options.aspectGuide
        var w = size.width * scale.first
        var h = size.height * scale.second
        if (guide != MonitorAspectGuide.NONE) {
            val ratio = guide.width.toFloat() / guide.height
            w = minOf(w, h * ratio); h = w / ratio
            drawRect(Color.White, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h), style = Stroke(1.dp.toPx()))
        }
        if (options.safeAreaEnabled) {
            w *= options.safeAreaPercent / 100f; h *= options.safeAreaPercent / 100f
            drawRect(Color.Yellow, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h), style = Stroke(1.dp.toPx()))
        }
    }
}

/**
 * The waveform, vectorscope, false-colour legend and, when the host passes [histogramMode], the
 * histogram. The panel fills the space the host gives it: one scope at a time behind tabs, or
 * every scope stacked when a side pane is tall enough. [expanded] belongs to the host; the ⤢ key
 * only asks for the change through [onExpandedChange] and is absent without it, and the close
 * key appears only with [onClose]. The panel remembers its tab itself unless the host owns it
 * through [onTabChange], which lets a scope key bring its scope forward.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ProfessionalScopesPanel(state: CameraUiState, options: MonitoringOptions,
    fresh: Boolean, modifier: Modifier = Modifier, expanded: Boolean = false,
    onExpandedChange: ((Boolean) -> Unit)? = null, histogramMode: HistogramMode? = null,
    onClose: (() -> Unit)? = null, tab: ScopeTab? = null, onTabChange: ((ScopeTab) -> Unit)? = null) {
    val tabs = enabledScopeTabs(options.waveformEnabled, options.vectorscopeEnabled, options.falseColorEnabled, histogramMode != null)
    var savedTab by rememberSaveable { mutableStateOf<ScopeTab?>(null) }
    val selected = resolveScopeTab(if (onTabChange != null) tab else savedTab, tabs) ?: return
    val selectTab: (ScopeTab) -> Unit = onTabChange ?: { savedTab = it }
    val frame = state.monitoringScopes
    val live = state.scopeAnalysisLive(fresh)
    val current = live && frame != null && frame.options == options
    val chipRes = when {
        frame == null -> R.string.scope_domain_waiting
        !current -> R.string.scope_suspended
        else -> scopeDomainLabel(frame.domain)
    }
    val labels = tabs.map { stringResource(scopeTabLabel(it, short = false)) }
    val shortLabels = tabs.map { stringResource(scopeTabLabel(it, short = true)) }
    // Plan with the widest chip text so the header does not reflow when the status changes.
    val chipTexts = listOf(R.string.scope_domain_waiting, R.string.scope_suspended, R.string.scope_domain_isp,
        R.string.scope_domain_sdr, R.string.scope_domain_log).map { stringResource(it) }
    val keyboard = LocalAdaptiveWindow.current.hardwareKeyboard
    val hideLabel = stringResource(R.string.scope_hide).let { if (keyboard) withShortcut(it, ShortcutAction.SCOPES) else it }
    val colors = MaterialTheme.colorScheme
    val tabStyle = MaterialTheme.typography.labelLarge
    val chipStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.testTag("monitoring-panel")) {
        // A host that leaves a dimension open (a scrolling column) still gets a readable panel.
        val width = if (constraints.hasBoundedWidth) maxWidth else 280.dp
        val height = if (constraints.hasBoundedHeight) maxHeight else 280.dp
        val actionKeys = (if (onExpandedChange != null) 1 else 0) + (if (onClose != null) 1 else 0)
        val plan = remember(width, labels, shortLabels, chipTexts, actionKeys, density) {
            fun dp(text: String, style: TextStyle) = with(density) { measurer.measure(text, style, maxLines = 1).size.width.toDp().value }
            scopeHeaderPlan((width - PANEL_PADDING * 2).value, labels.map { dp(it, tabStyle) }, shortLabels.map { dp(it, tabStyle) },
                chipTexts.maxOf { dp(it, chipStyle) } + CHIP_PADDING.value * 2 + CHIP_GAP.value, actionKeys)
        }
        val contentHeight = height - PANEL_PADDING * 2 - SCOPE_TOUCH_DP.dp
        val stacked = scopePanelStacks((width - PANEL_PADDING * 2).value, contentHeight.value, tabs)
        val chipHelp = stringResource(R.string.scope_domain_help)
        val chip = @Composable {
            // The chip names the signal the scopes read; a long press says what that means.
            CaptureTooltip(stringResource(chipRes), chipHelp, Modifier.padding(start = CHIP_GAP)) {
                Text(stringResource(chipRes), style = chipStyle, maxLines = 1,
                    color = if (frame != null && !current) LocalCineColors.current.pending else colors.onSurfaceVariant,
                    modifier = Modifier.background(colors.surfaceContainerHigh, RoundedCornerShape(50))
                        .padding(horizontal = CHIP_PADDING, vertical = 3.dp).testTag("monitoring-freshness"))
            }
        }
        Column(Modifier.size(width, height).clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerLowest.copy(alpha = .88f)).padding(PANEL_PADDING)) {
            Row(Modifier.fillMaxWidth().height(SCOPE_TOUCH_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                if (stacked) { chip(); Spacer(Modifier.weight(1f)) }
                else {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        ScopeTabs(tabs, selected, labels, shortLabels, plan.tabStyle, selectTab)
                    }
                    if (plan.chipInHeader) chip()
                }
                if (onExpandedChange != null) CineIconButton("monitoring-enlarge",
                    if (expanded) CineIcon.FULLSCREEN_EXIT else CineIcon.FULLSCREEN,
                    if (expanded) R.string.scope_reduce else R.string.scope_enlarge) { onExpandedChange(!expanded) }
                if (onClose != null) ScopeCloseKey(hideLabel, onClose)
            }
            if (!stacked && !plan.chipInHeader) Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.CenterEnd) { chip() }
            val scopeWidth = (width - PANEL_PADDING * 2).value
            if (stacked) Column(Modifier.fillMaxWidth().weight(1f)) {
                tabs.forEachIndexed { index, tab ->
                    Column(Modifier.fillMaxWidth().weight(stackedScopeHeightDp(tab, scopeWidth) + SCOPE_SECTION_LABEL_DP)) {
                        Text(labels[index], style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1,
                            modifier = Modifier.height(SCOPE_SECTION_LABEL_DP.dp).padding(start = 4.dp))
                        ScopeView(tab, state, options, current, live, histogramMode, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            } else ScopeView(selected, state, options, current, live, histogramMode, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

private val PANEL_PADDING = 4.dp
private val CHIP_PADDING = 8.dp
private val CHIP_GAP = 6.dp

private fun scopeDomainLabel(domain: MonitoringSignalDomain): Int = when (domain) {
    MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR -> R.string.scope_domain_isp
    MonitoringSignalDomain.SDR_BT709_CODE -> R.string.scope_domain_sdr
    MonitoringSignalDomain.OCLOG2_CODE -> R.string.scope_domain_log
}

private fun scopeTabLabel(tab: ScopeTab, short: Boolean): Int = when (tab) {
    ScopeTab.WAVEFORM -> if (short) R.string.scope_tab_waveform_short else R.string.scope_tab_waveform
    ScopeTab.VECTORSCOPE -> if (short) R.string.scope_tab_vectorscope_short else R.string.scope_tab_vectorscope
    ScopeTab.FALSE_COLOR -> if (short) R.string.scope_tab_false_color_short else R.string.scope_tab_false_color
    ScopeTab.HISTOGRAM -> if (short) R.string.scope_tab_histogram_short else R.string.scope_tab_histogram
}

/** What each scope shows, for the tab's long-press help. */
private fun scopeTabHelp(tab: ScopeTab): Int = when (tab) {
    ScopeTab.WAVEFORM -> R.string.scope_tab_waveform_help
    ScopeTab.VECTORSCOPE -> R.string.scope_tab_vectorscope_help
    ScopeTab.FALSE_COLOR -> R.string.scope_tab_false_color_help
    ScopeTab.HISTOGRAM -> R.string.scope_tab_histogram_help
}

private fun scopeTabTag(tab: ScopeTab): String = when (tab) {
    ScopeTab.WAVEFORM -> "monitoring-tab-waveform"
    ScopeTab.VECTORSCOPE -> "monitoring-tab-vectorscope"
    ScopeTab.FALSE_COLOR -> "monitoring-tab-false-color"
    ScopeTab.HISTOGRAM -> "monitoring-tab-histogram"
}

/** Tabs, a lone title for a single scope, or one key that steps through them in the narrowest panel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ScopeTabs(tabs: List<ScopeTab>, selected: ScopeTab, labels: List<String>, shortLabels: List<String>,
    style: ScopeTabStyle, onSelect: (ScopeTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val index = tabs.indexOf(selected)
    if (tabs.size == 1) {
        Text(if (style == ScopeTabStyle.FULL) labels[0] else shortLabels[0], style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface, maxLines = 1, modifier = Modifier.padding(horizontal = (SCOPE_TAB_PADDING_DP / 2).dp)
                .semantics { contentDescription = labels[0] })
        return
    }
    if (style == ScopeTabStyle.CYCLE) {
        val next = nextScopeTab(selected, tabs) ?: selected
        Row(Modifier.heightIn(min = SCOPE_TOUCH_DP.dp).widthIn(min = SCOPE_TOUCH_DP.dp).clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = stringResource(R.string.scope_next), role = Role.Button) { onSelect(next) }
            .semantics { contentDescription = labels[index] }
            .padding(horizontal = (SCOPE_TAB_PADDING_DP / 2).dp).testTag("monitoring-tab-cycle"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(shortLabels[index], style = MaterialTheme.typography.labelLarge, color = colors.primary, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            CineGlyph(CineIcon.CHEVRON_RIGHT, colors.onSurfaceVariant, Modifier.size(14.dp))
        }
        return
    }
    Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { i, tab ->
            val on = tab == selected
            val accent = colors.primary
            // A long press names the scope in full and says what it shows.
            CaptureTooltip(labels[i], stringResource(scopeTabHelp(tab))) { Box(Modifier.heightIn(min = SCOPE_TOUCH_DP.dp).widthIn(min = SCOPE_TOUCH_DP.dp).clip(RoundedCornerShape(8.dp))
                .selectable(on, role = Role.Tab) { onSelect(tab) }
                .then(if (style == ScopeTabStyle.SHORT) Modifier.semantics { contentDescription = labels[i] } else Modifier)
                .drawBehind {
                    if (on) drawLine(accent, Offset(6.dp.toPx(), size.height - 6.dp.toPx()),
                        Offset(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
                .padding(horizontal = (SCOPE_TAB_PADDING_DP / 2).dp).testTag(scopeTabTag(tab)),
                contentAlignment = Alignment.Center) {
                Text(if (style == ScopeTabStyle.FULL) labels[i] else shortLabels[i], style = MaterialTheme.typography.labelLarge,
                    color = if (on) accent else colors.onSurfaceVariant, maxLines = 1)
            } }
        }
    }
}

/** A 48 dp close key; the icon set has no close glyph, so the cross is drawn here. */
@Composable private fun ScopeCloseKey(label: String, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(SCOPE_TOUCH_DP.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.testTag("monitoring-close"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = 1.8.dp.toPx()
            drawLine(tint, Offset.Zero, Offset(size.width, size.height), stroke, StrokeCap.Round)
            drawLine(tint, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
        }
    }
}

/** One scope filling [modifier]; stale data leaves the graticule up so the panel keeps its shape. */
@Composable private fun ScopeView(tab: ScopeTab, state: CameraUiState, options: MonitoringOptions, current: Boolean, live: Boolean,
    histogramMode: HistogramMode?, modifier: Modifier) {
    val frame = state.monitoringScopes
    when (tab) {
        ScopeTab.WAVEFORM -> WaveformScope(frame?.waveformDensity?.takeIf { current && it.isNotEmpty() }, options, modifier)
        ScopeTab.VECTORSCOPE -> Vectorscope(frame?.vectorscopeCounts?.takeIf { current && it.isNotEmpty() }, options, modifier)
        ScopeTab.FALSE_COLOR -> FalseColorLegend(options, frame?.falseColorBands?.takeIf { current && it.isNotEmpty() }, modifier)
        ScopeTab.HISTOGRAM -> if (histogramMode != null && live && (state.histogram.isNotEmpty() || state.redHistogram.isNotEmpty()))
            HistogramGraph(state, options, histogramMode, modifier.clip(RoundedCornerShape(6.dp)), tag = "monitoring-histogram-graph")
            else Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).testTag("monitoring-scope-idle"))
    }
}

/** Luma waveform: 100 % at the top, graticule every quarter and numbers at 0 / 50 / 100. */
@Composable private fun WaveformScope(counts: List<Int>?, options: MonitoringOptions, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val grid = colors.onSurfaceVariant.copy(alpha = .3f)
    val background = colors.surfaceContainerLowest
    val trace = options.lumaColor.composeColor()
    Canvas(modifier.testTag(if (counts != null) "monitoring-waveform-graph" else "monitoring-scope-idle")) {
        val levels = waveformScaleLabels(size.height / density)
        val texts = levels.map { measurer.measure(it.toString(), labelStyle) }
        val gutter = texts.maxOf { it.size.width } + 6.dp.toPx()
        val half = texts[0].size.height / 2f
        val area = Rect(gutter, half, size.width, (size.height - half).coerceAtLeast(half + 1f))
        drawRect(background, area.topLeft, area.size)
        WAVEFORM_SCALE_LINES.forEach { level ->
            val y = waveformLevelY(level, area.top, area.height)
            drawLine(grid, Offset(area.left, y), Offset(area.right, y), strokeWidth = if (level % 50 == 0) 1.dp.toPx() else Stroke.HairlineWidth)
        }
        levels.forEachIndexed { i, level ->
            val text = texts[i]
            drawText(text, topLeft = Offset(gutter - 4.dp.toPx() - text.size.width, waveformLevelY(level, area.top, area.height) - text.size.height / 2f))
        }
        if (counts != null) drawDensity(counts, area, trace, options.opacityPercent / 100f)
    }
}

/** Square vectorscope with the 75 % colour-bar targets and the skin-tone line; never stretched. */
@Composable private fun Vectorscope(counts: List<Int>?, options: MonitoringOptions, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val grid = colors.onSurfaceVariant.copy(alpha = .35f)
    val target = colors.onSurfaceVariant.copy(alpha = .7f)
    val background = colors.surfaceContainerLowest
    val trace = options.lumaColor.composeColor()
    Canvas(modifier.testTag(if (counts != null) "monitoring-vector-graph" else "monitoring-scope-idle")) {
        val square = scopeSquareFit(size.width, size.height)
        val radius = square.width / 2f
        val center = square.center
        drawCircle(background, radius, center)
        drawCircle(grid, radius, center, style = Stroke(1.dp.toPx()))
        drawLine(grid, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), Stroke.HairlineWidth)
        drawLine(grid, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), Stroke.HairlineWidth)
        val skin = Math.toRadians(VECTORSCOPE_SKIN_LINE_DEGREES.toDouble())
        drawLine(grid, center, Offset(center.x + radius * kotlin.math.cos(skin).toFloat(), center.y - radius * kotlin.math.sin(skin).toFloat()),
            strokeWidth = 1.dp.toPx())
        val box = (square.width * .045f).coerceAtLeast(4.dp.toPx())
        VECTORSCOPE_BARS.forEach { (cb, cr) ->
            val p = vectorscopePoint(cb * VECTORSCOPE_TARGET_LEVEL, cr * VECTORSCOPE_TARGET_LEVEL, square)
            drawRect(target, Offset(p.x - box / 2, p.y - box / 2), Size(box, box), style = Stroke(1.dp.toPx()))
        }
        if (counts != null) drawDensity(counts, square, trace, options.opacityPercent / 100f)
    }
}

/** The false-colour bands as a 0–100 % ramp and a legend with each band's range. */
/**
 * False colour: how much of the frame sits in each exposure zone right now ([frameBands], null
 * while there is no current analysis), above the zone thresholds the overlay paints with.
 */
@Composable private fun FalseColorLegend(options: MonitoringOptions, frameBands: List<FalseColorBand>?, modifier: Modifier) {
    val bands = listOf(FalseColorBand.BLACK, FalseColorBand.SHADOW, FalseColorBand.MID, FalseColorBand.HIGHLIGHT, FalseColorBand.CLIP)
    val names = listOf(R.string.scope_black, R.string.scope_shadow, R.string.scope_mid, R.string.scope_highlight, R.string.scope_clip)
    val cuts = listOf("≤${options.falseColorBlackPercent}", "≤${options.falseColorShadowPercent}", "<${options.falseColorHighlightPercent}",
        "<${options.falseColorClipPercent}", "≥${options.falseColorClipPercent}")
    val stops = falseColorRampStops(options.falseColorBlackPercent, options.falseColorShadowPercent,
        options.falseColorHighlightPercent, options.falseColorClipPercent)
    val shares = remember(frameBands) { frameBands?.let(::falseColorShares) }
    val colors = MaterialTheme.colorScheme
    val caption = MaterialTheme.typography.labelSmall
    val idle = colors.surfaceContainerHigh
    Column(modifier.padding(horizontal = 4.dp).testTag("monitoring-false-color-legend"),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        // The live bar takes the height the panel spares (an enlarged tray gives it more), up to 72 dp,
        // and keeps its caption just above it.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        val barHeight = (maxHeight - 20.dp).coerceIn(12.dp, 72.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.scope_fc_in_frame), style = caption, color = colors.onSurfaceVariant, maxLines = 1)
        Canvas(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(3.dp))
            .testTag(if (shares != null) "monitoring-false-color-live" else "monitoring-scope-idle")) {
            if (shares == null) drawRect(idle)
            else {
                var x = 0f
                bands.forEach { band ->
                    val w = shares[band.ordinal] * size.width
                    drawRect(band.composeColor(options.falseColorPalette), Offset(x, 0f), Size(w, size.height))
                    x += w
                }
            }
        }
        }
        }
        Text(stringResource(R.string.scope_fc_thresholds), style = caption, color = colors.onSurfaceVariant, maxLines = 1,
            modifier = Modifier.padding(top = 2.dp))
        Canvas(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
            stops.forEachIndexed { i, range ->
                drawRect(bands[i].composeColor(options.falseColorPalette), Offset(range.start * size.width, 0f),
                    Size((range.endInclusive - range.start) * size.width, size.height))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            bands.forEachIndexed { i, band ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(band.composeColor(options.falseColorPalette), RoundedCornerShape(2.dp)))
                    Text(" ${stringResource(names[i])}", color = colors.onSurface, style = caption, maxLines = 1)
                    shares?.let { Text(" ${falseColorShareText(it[band.ordinal])}", color = colors.onSurface, maxLines = 1,
                        style = caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) }
                    Text(" ${cuts[i]}", color = colors.onSurfaceVariant, style = caption, maxLines = 1)
                }
            }
        }
    }
}

/** A 64 × 64 density grid; square-root weighting with a floor keeps sparse traces visible. */
private fun DrawScope.drawDensity(counts: List<Int>, area: Rect, color: Color, opacity: Float) {
    val peak = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
    val cell = Size(area.width / 64f, area.height / 64f)
    counts.forEachIndexed { index, count ->
        if (count > 0) drawRect(color.copy(alpha = ((.2f + .8f * kotlin.math.sqrt(count.toFloat() / peak)) * opacity).coerceIn(0f, 1f)),
            Offset(area.left + (index % 64) * cell.width, area.top + (index / 64) * cell.height), cell)
    }
}
