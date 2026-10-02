/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.TimecodeRate
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import java.util.Locale

private val SlateLabel = Color(0xFFBDBDBD)
private const val TIMECODE_TEMPLATE = "88:88:88:88"

/**
 * OCC-PLAN-068 U6: a clapperboard-style slate for other cameras to film. Values come from the
 * operator's [ProductionSlateSettings] and the service-published take timecode; the slate never
 * changes take numbering and has no actions (ADR-0031). Text is sized from the cover's own pixels,
 * so it fills the panel and stays legible from a few metres at any system font scale.
 */
@Composable
internal fun SubjectSlateContent(
    state: CameraUiState,
    settings: SubjectDisplaySettings,
    slate: ProductionSlateSettings,
    modifier: Modifier = Modifier,
    timecodeRate: TimecodeRate? = null,
) {
    val timecode = if (SubjectSlateField.TIMECODE in settings.slateFields) rememberSlateTimecode(state, timecodeRate) else SLATE_TIMECODE_IDLE
    val rows = subjectSlateRows(subjectSlateLines(slate, settings.slateFields, timecode))
    Column(modifier.fillMaxSize().background(Color.Black).testTag("subject-slate")) {
        ClapperStripes(Modifier.fillMaxWidth().height(28.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).border(2.dp, Color.White)) {
            if (rows.isEmpty()) return@BoxWithConstraints
            // The timecode row gets more height: it is the value that changes and must be read on camera.
            val weights = rows.map { row -> if (row.size == 1 && row[0].field == SubjectSlateField.TIMECODE) 1.4f else 1f }
            val unit = maxHeight / weights.sum()
            Column(Modifier.fillMaxSize()) {
                rows.forEachIndexed { index, row ->
                    Row(Modifier.fillMaxWidth().height(unit * weights[index])) {
                        row.forEach { line ->
                            SlateCell(line, Modifier.weight(1f).fillMaxHeight().border(1.dp, Color.White))
                        }
                    }
                }
            }
        }
    }
}

/** Ticks with the display frame clock while recording; the published label anchors each update. */
@Composable
private fun rememberSlateTimecode(state: CameraUiState, rate: TimecodeRate?): String {
    val recording = state.phase == CameraUiPhase.RECORDING
    val running = recording && !state.recordingFinalizing && state.recordingPauseStatus?.paused != true
    val published = state.timecodeDisplay
    var shown by remember { mutableStateOf(slateTimecodeLabel(published, rate, recording, false, 0L)) }
    LaunchedEffect(published, rate, recording, running) {
        shown = slateTimecodeLabel(published, rate, recording, false, 0L)
        if (!running || published == null || rate == null) return@LaunchedEffect
        val anchor = withFrameNanos { it }
        while (true) {
            val elapsed = withFrameNanos { it } - anchor
            shown = slateTimecodeLabel(published, rate, true, true, elapsed)
            if (elapsed >= SLATE_TIMECODE_EXTRAPOLATION_CAP_NS) break
        }
    }
    return shown
}

@Composable
private fun SlateCell(line: SubjectSlateLine, modifier: Modifier) {
    val timecode = line.field == SubjectSlateField.TIMECODE
    BoxWithConstraints(modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("subject-slate-${line.field.name.lowercase(Locale.ROOT)}")) {
        val density = LocalDensity.current
        val labelPx = with(density) { (maxHeight * 0.18f).coerceIn(10.dp, 40.dp).toPx() }
        val valueWidth = with(density) { maxWidth.roundToPx() }
        val valueHeight = with(density) { (maxHeight.toPx() - labelPx * 1.3f).coerceAtLeast(1f).toInt() }
        val family = if (timecode) FontFamily.Monospace else FontFamily.SansSerif
        // Monospace timecode is fitted once against its widest label, so the size never jumps while it ticks.
        val fitted = fittedFontSize(if (timecode) TIMECODE_TEMPLATE else line.value, family, valueWidth, valueHeight)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(slateFieldLabel(line.field)).uppercase(LocalConfiguration.current.locales[0]), color = SlateLabel,
                fontSize = with(density) { labelPx.toSp() }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = if (timecode) Alignment.Center else Alignment.CenterStart) {
                Text(line.value, color = Color.White, fontSize = fitted, fontFamily = family, fontWeight = FontWeight.Bold,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            }
        }
    }
}

/** Largest single-line size that fits; expressed through toSp so the system font scale cannot overflow it. */
@Composable
private fun fittedFontSize(text: String, family: FontFamily, widthPx: Int, heightPx: Int): TextUnit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, family, widthPx, heightPx, density) {
        var low = 4f
        var high = heightPx.toFloat().coerceAtLeast(low)
        repeat(12) {
            val middle = (low + high) / 2
            val size = measurer.measure(text.ifEmpty { " " }, TextStyle(fontSize = with(density) { middle.toSp() }, fontFamily = family,
                fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false, constraints = Constraints(), density = density).size
            if (size.width <= widthPx && size.height <= heightPx) low = middle else high = middle
        }
        with(density) { low.toSp() }
    }
}

/** Diagonal black and white clapper bars; decorative, so they carry no semantics. */
@Composable
private fun ClapperStripes(modifier: Modifier) {
    Canvas(modifier.background(Color.Black)) {
        val band = size.height * 1.6f
        var x = -size.height
        var white = true
        while (x < size.width + size.height) {
            if (white) drawPath(Path().apply {
                moveTo(x, size.height)
                lineTo(x + size.height, 0f)
                lineTo(x + size.height + band, 0f)
                lineTo(x + band, size.height)
                close()
            }, Color.White)
            white = !white
            x += band
        }
        drawLine(Color.White, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2.dp.toPx())
    }
}

@Composable
internal fun SubjectSlateSettings(state: CameraUiState, subject: SubjectDisplaySettings, onChange: (SubjectDisplaySettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.subject_slate_fields), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
        for (field in SubjectSlateField.entries) {
            FoldToggle(stringResource(slateFieldLabel(field)), field in subject.slateFields) { shown ->
                onChange(subject.copy(slateFields = if (shown) subject.slateFields + field else subject.slateFields - field))
            }
        }
        SettingsHelp(stringResource(R.string.subject_slate_sync_scope))
        FoldToggle(stringResource(R.string.subject_slate_sync_flash), subject.slateSyncFlash) { onChange(subject.copy(slateSyncFlash = it)) }
        FoldToggle(stringResource(R.string.subject_slate_sync_beep), subject.slateSyncBeep) { onChange(subject.copy(slateSyncBeep = it)) }
        // Always visible, not behind a help toggle: the beep is recorded into this phone's own audio.
        Text(stringResource(R.string.subject_slate_sync_help), color = LocalCineColors.current.pending, fontSize = 14.sp,
            modifier = Modifier.testTag("subject-slate-beep-warning"))
    }
}

internal fun slateFieldLabel(field: SubjectSlateField): Int = when (field) {
    SubjectSlateField.PROJECT -> R.string.subject_slate_field_project
    SubjectSlateField.SCENE -> R.string.subject_slate_field_scene
    SubjectSlateField.TAKE -> R.string.subject_slate_field_take
    SubjectSlateField.CAMERA -> R.string.subject_slate_field_camera
    SubjectSlateField.REEL -> R.string.subject_slate_field_reel
    SubjectSlateField.TIMECODE -> R.string.subject_slate_field_timecode
}
