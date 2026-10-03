/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.hardware.camera2.CameraMetadata
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.FocusPullEasing
import com.librestatic.opencinecam.service.CaptureService
import java.util.Locale
import kotlin.math.roundToInt

private val FOCUS_MARK_LABELS = listOf("A", "B", "C", "D")

/**
 * Focus panel in two levels. The quick view is what a focus puller reaches for: AF or MF, the
 * distance, a slider on a true diopter scale and the A–D marks. Pull time and curve live one tap
 * away under Advanced, so the quick view fits a phone sheet without cutting anything off.
 */
@Composable
internal fun FocusPanel(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    onClose: () -> Unit,
) {
    val descriptor = state.descriptor ?: return
    val declaredNear = descriptor.minimumFocusDistance ?: 0f
    val pullActive = state.focusPullActive
    var advanced by rememberSaveable(descriptor.cameraId) { mutableStateOf(false) }
    // Back and Esc leave Advanced first; the host closes the panel on the next press.
    BackHandler(enabled = advanced) { advanced = false }
    ShortcutHandler(enabled = advanced) { action -> if (action == ShortcutAction.DISMISS) { advanced = false; true } else false }
    PanelColumn(Modifier.testTag("focus-panel")) {
        PanelHeader(stringResource(if (pullActive) R.string.panel_title_focus_pull else R.string.panel_title_focus), onClose)
        if (declaredNear <= 0f) {
            PanelNote(stringResource(R.string.focus_auto_fixed_lens))
            return@PanelColumn
        }
        if (advanced) FocusAdvanced(settings, onSettingsChanged) { advanced = false }
        else FocusQuick(state, binder, settings, declaredNear, descriptor.cameraId,
            afAvailable = descriptor.availableAfModes.any { it != CameraMetadata.CONTROL_AF_MODE_OFF }) { advanced = true }
        if (pullActive) {
            Button(
                onClick = { binder?.cancelFocusPull() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("focus-pull-cancel"),
            ) { Text(stringResource(R.string.focus_pull_cancel), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun FocusQuick(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    declaredNear: Float,
    cameraId: String,
    afAvailable: Boolean,
    onAdvanced: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val requested = state.requestedFocusDiopters
    val reported = state.focusDistanceDiopters
    val pullActive = state.focusPullActive
    val pullTarget = state.focusPullTargetDiopters
    val marks = state.focusMarks
    // The scale only ever widens while the panel is open, so it never jumps under the thumb.
    val wanted = focusScaleSpan(declaredNear, listOf(requested, reported, pullTarget) + marks.values)
    var widest by remember(cameraId) { mutableFloatStateOf(wanted) }
    LaunchedEffect(wanted) { if (wanted > widest) widest = wanted }
    val span = maxOf(widest, wanted)
    val current = requested ?: reported ?: 0f

    if (afAvailable) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceTile(stringResource(R.string.focus_mode_af), requested == null, Modifier.weight(1f).testTag("focus-mode-af"),
            enabled = !pullActive) { binder?.setManualFocus(null) }
        ChoiceTile(stringResource(R.string.focus_mode_mf), requested != null, Modifier.weight(1f).testTag("focus-mode-mf"),
            enabled = !pullActive) { binder?.setManualFocus((reported ?: 0f).coerceIn(0f, declaredNear)) }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                pullActive && pullTarget != null -> "→ " + formatFocusReading(pullTarget)
                requested != null -> formatFocusReading(requested)
                reported != null -> formatFocusReading(reported)
                else -> stringResource(R.string.auto_value)
            },
            color = colors.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("focus-reading"),
        )
        // Requested against reported: one short line, only when the lens is somewhere else.
        if (!pullActive && reported != null && focusReadbackDiffers(requested, reported)) {
            Text(stringResource(R.string.focus_lens_reports, formatFocusReading(reported)), color = colors.onSurfaceVariant,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("focus-readback"))
        }
    }

    CineSlider(
        value = focusSliderPosition(if (pullActive) reported ?: current else current, span),
        onValueChange = { binder?.setManualFocus(focusFromSliderPosition(it, span, declaredNear)) },
        enabled = !pullActive,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("focus-slider"),
    )
    FocusScale(span, declaredNear)

    PanelSectionLabel(stringResource(R.string.focus_marks_title), Modifier.padding(top = 4.dp))
    ChoiceGrid(FOCUS_MARK_LABELS, minTileWidth = 64.dp, maxColumns = 4, balanced = true) { label, modifier ->
        FocusMarkTile(label, marks[label], current, settings, binder, enabled = !pullActive,
            target = pullActive && pullTarget != null && marks[label] == pullTarget, modifier = modifier)
    }
    PanelNote(stringResource(R.string.focus_marks_hint))

    val easing = stringResource(easingLabel(settings.focusPullEasing))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceContainerHighest)
            .clickable(onClick = onAdvanced)
            .padding(horizontal = 12.dp)
            .testTag("focus-advanced"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.focus_advanced), color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text("${formatPullSeconds(settings.focusPullDurationMs)} · $easing", color = colors.onSurfaceVariant, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        CineGlyph(CineIcon.CHEVRON, colors.onSurfaceVariant, Modifier.size(16.dp))
    }
}

/**
 * Tick marks and labels under the slider: diopters on top, the distance in metres below, ∞ at the
 * far end. Ticks past the lens's declared near limit are dimmed, since the lens will not go there.
 */
@Composable
private fun FocusScale(span: Float, declaredNear: Float) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().testTag("focus-scale")) {
        val ticks = focusScaleTicks(span, focusTickCount(maxWidth.value))
        val tickColor = colors.outline
        Column(Modifier.fillMaxWidth()) {
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                ticks.forEach { tick ->
                    val x = (tick / span).coerceIn(0f, 1f) * size.width
                    drawLine(tickColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                }
            }
            Layout(
                content = {
                    ticks.forEach { tick ->
                        val reachable = tick <= declaredNear + 1e-4f
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${formatTickDiopters(tick)} D", fontSize = 12.sp, maxLines = 1,
                                color = if (reachable) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.6f))
                            Text(formatFocusMetres(tick), fontSize = 12.sp, maxLines = 1,
                                color = colors.onSurfaceVariant.copy(alpha = if (reachable) 1f else 0.6f))
                        }
                    }
                },
            ) { measurables, constraints ->
                val width = constraints.maxWidth
                val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
                    placeables.forEachIndexed { i, placeable ->
                        val centre = (ticks[i] / span).coerceIn(0f, 1f) * width
                        val x = (centre - placeable.width / 2f).roundToInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                        placeable.place(x, 0)
                    }
                }
            }
        }
    }
}

/** A saved mark pulls focus to it on tap; an empty one saves the current focus. Holding clears or saves. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FocusMarkTile(
    label: String,
    saved: Float?,
    current: Float,
    settings: CameraSettings,
    binder: CaptureService.LocalBinder?,
    enabled: Boolean,
    target: Boolean,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val pull = stringResource(R.string.focus_mark_pull_action, label)
    val save = stringResource(R.string.focus_mark_save_action, label)
    val clear = stringResource(R.string.focus_mark_clear_action, label)
    val tap = { if (saved != null) binder?.startFocusPull(saved, settings.focusPullDurationMs, settings.focusPullEasing)
        else binder?.setFocusMark(label, current) }
    Column(
        modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (target) colors.primary else colors.surfaceContainerHighest)
            .border(1.dp, if (saved != null) colors.primary else colors.outlineVariant, RoundedCornerShape(8.dp))
            .semantics { selected = target }
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = if (saved != null) pull else save,
                onLongClickLabel = if (saved != null) clear else save,
                onLongClick = { if (saved != null) binder?.clearFocusMark(label) else binder?.setFocusMark(label, current) },
                onClick = { tap() },
            )
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .testTag("focus-mark-$label"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val main = if (target) colors.onPrimary else colors.onSurface
        val sub = if (target) colors.onPrimary else colors.onSurfaceVariant
        Text(label, color = if (saved != null && !target) colors.primary else main, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (saved != null) {
            Text(formatDiopters(saved), color = main, fontSize = 12.sp, maxLines = 1)
            Text(formatFocusMetres(saved), color = sub, fontSize = 12.sp, maxLines = 1)
        } else {
            Text(stringResource(R.string.focus_mark_empty), color = sub, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun FocusAdvanced(settings: CameraSettings, onSettingsChanged: (CameraSettings) -> Unit, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onBack)
            .testTag("focus-advanced-back"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CineGlyph(CineIcon.BACK, colors.onSurface, Modifier.size(20.dp))
        Text(stringResource(R.string.focus_advanced), color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PanelSectionLabel(stringResource(R.string.focus_pull_duration), Modifier.weight(1f))
        Text(formatPullSeconds(settings.focusPullDurationMs), color = colors.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("focus-pull-duration-value"))
    }
    CineSlider(
        value = settings.focusPullDurationMs.toFloat(),
        onValueChange = { onSettingsChanged(settings.copy(focusPullDurationMs = ((it / 500f).roundToInt() * 500L).coerceIn(500L, 10_000L))) },
        valueRange = 500f..10_000f,
        steps = 18,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("focus-pull-duration"),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatPullSeconds(500L), color = colors.onSurfaceVariant, fontSize = 12.sp)
        Text(formatPullSeconds(10_000L), color = colors.onSurfaceVariant, fontSize = 12.sp)
    }

    PanelSectionLabel(stringResource(R.string.focus_pull_curve), Modifier.padding(top = 4.dp))
    ChoiceGrid(FocusPullEasing.entries, minTileWidth = 96.dp, maxColumns = 4, balanced = true,
        modifier = Modifier.testTag("focus-pull-curves")) { easing, modifier ->
        ChoiceTile(stringResource(easingLabel(easing)), settings.focusPullEasing == easing, modifier) {
            onSettingsChanged(settings.copy(focusPullEasing = easing))
        }
    }
}

private fun easingLabel(easing: FocusPullEasing): Int = when (easing) {
    FocusPullEasing.LINEAR -> R.string.focus_curve_linear
    FocusPullEasing.EASE_IN -> R.string.focus_curve_ease_in
    FocusPullEasing.EASE_OUT -> R.string.focus_curve_ease_out
    FocusPullEasing.EASE_IN_OUT -> R.string.focus_curve_s
}

/** "2.0 s", "0.5 s", "10 s". */
fun formatPullSeconds(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1000.0
    return if (seconds >= 10.0) String.format(Locale.ROOT, "%.0f s", seconds) else String.format(Locale.ROOT, "%.1f s", seconds)
}
