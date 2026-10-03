/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.*

/** Edits monitoring intent only; signal sampling and output eligibility belong to the backend. */
@Composable
internal fun MonitoringSettings(settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val options = settings.monitoring
    fun update(value: MonitoringOptions) = onChange(settings.copy(monitoring = value))
    // One group per tool. Wide panes lay the groups out in two columns instead of one long list.
    val instruments: @Composable () -> Unit = {
        MonitorGroup(R.string.monitoring_group_instruments, CineIcon.MONITORING,
            stringResource(R.string.monitoring_signal_help) + "\n\n" + stringResource(R.string.monitoring_source_help)) {
            MonitorSwitch(R.string.monitoring_waveform, "waveform", options.waveformEnabled) { update(options.copy(waveformEnabled = it)) }
            MonitorSwitch(R.string.monitoring_vectorscope, "vectorscope", options.vectorscopeEnabled) { update(options.copy(vectorscopeEnabled = it)) }
            MonitorSwitch(R.string.monitoring_false_color, "false-color", options.falseColorEnabled) { update(options.copy(falseColorEnabled = it)) }
        }
    }
    val zebra: @Composable () -> Unit = {
        MonitorGroup(R.string.monitoring_group_zebra, CineIcon.ZEBRA) {
            MonitorSwitch(R.string.monitoring_zebra, "zebra-enabled", settings.zebraEnabled) { onChange(settings.copy(zebraEnabled = it)) }
            MonitorSwitch(R.string.monitoring_zebra_shadow, "zebra-shadow", options.zebraShadowEnabled) { update(options.copy(zebraShadowEnabled = it)) }
            ZebraThresholds(options, ::update)
            MonitorColors(R.string.monitoring_zebra_color, "zebra-color", options.zebraColor) { update(options.copy(zebraColor = it)) }
        }
    }
    val peaking: @Composable () -> Unit = {
        MonitorGroup(R.string.monitoring_group_peaking, CineIcon.PEAKING) {
            MonitorSwitch(R.string.monitoring_peaking, "peaking-enabled", settings.peakingEnabled) { onChange(settings.copy(peakingEnabled = it)) }
            MonitorInteger(R.string.monitoring_peaking_threshold, "peaking-threshold", options.peakingThreshold, 1..255) {
                update(options.copy(peakingThreshold = it))
            }
            MonitorColors(R.string.monitoring_peaking_color, "peaking-color", options.peakingColor) { update(options.copy(peakingColor = it)) }
        }
    }
    val overlays: @Composable () -> Unit = {
        MonitorGroup(R.string.monitoring_group_overlays, CineIcon.HISTOGRAM) {
            MonitorInteger(R.string.monitoring_opacity, "opacity", options.opacityPercent, 10..100) { update(options.copy(opacityPercent = it)) }
            MonitorColors(R.string.monitoring_luma_color, "luma-color", options.lumaColor) { update(options.copy(lumaColor = it)) }
        }
    }
    val falseColor: @Composable () -> Unit = {
        MonitorGroup(R.string.monitoring_group_false, CineIcon.HISTOGRAM_MODE) {
            val classic = stringResource(R.string.monitoring_palette_classic)
            val contrast = stringResource(R.string.monitoring_palette_high_contrast)
            SettingsChips(stringResource(R.string.monitoring_false_palette), FalseColorPalette.entries, options.falseColorPalette,
                label = { if (it == FalseColorPalette.CLASSIC) classic else contrast },
                onSelect = { update(options.copy(falseColorPalette = it)) },
                tag = { "monitoring-palette-${it.name}" })
            FalseColorThresholds(options, ::update)
        }
    }
    val guides: @Composable () -> Unit = {
        MonitorGroup(R.string.monitoring_group_guides, CineIcon.GRID, stringResource(R.string.monitoring_guides_help)) {
            SettingsChips(stringResource(R.string.monitoring_aspect), MonitorAspectGuide.entries, options.aspectGuide,
                label = { guide -> stringResource(when (guide) {
                    MonitorAspectGuide.NONE -> R.string.monitoring_aspect_none
                    MonitorAspectGuide.WIDE -> R.string.monitoring_aspect_wide
                    MonitorAspectGuide.PHOTO -> R.string.monitoring_aspect_photo
                    MonitorAspectGuide.SQUARE -> R.string.monitoring_aspect_square
                    MonitorAspectGuide.CINEMA -> R.string.monitoring_aspect_cinema
                }) },
                onSelect = { update(options.copy(aspectGuide = it)) },
                tag = { "monitoring-aspect-${it.name}" })
            MonitorSwitch(R.string.monitoring_safe_area, "safe-enabled", options.safeAreaEnabled) { update(options.copy(safeAreaEnabled = it)) }
            MonitorInteger(R.string.monitoring_safe_percent, "safe-percent", options.safeAreaPercent, 50..100) {
                update(options.copy(safeAreaPercent = it))
            }
        }
    }
    val analysis: @Composable () -> Unit = {
        MonitorGroup(R.string.monitoring_group_analysis, CineIcon.MONITORING, stringResource(R.string.monitoring_frequency_help)) {
            MonitorInteger(R.string.monitoring_refresh, "refresh-hz", options.refreshHz, 1..10) { update(options.copy(refreshHz = it)) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 560.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { instruments(); zebra(); peaking() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { falseColor(); overlays(); guides(); analysis() }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            instruments(); zebra(); peaking(); overlays(); falseColor(); guides(); analysis()
        }
    }
}

@Composable
private fun MonitorGroup(titleId: Int, icon: CineIcon, help: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(SettingsSurfaceRaised, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsHeading(stringResource(titleId), icon = icon, help = help)
        content()
    }
}

@Composable
private fun MonitorSwitch(labelId: Int, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val label = stringResource(labelId)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }.testTag("monitoring-$tag"))
    }
}

@Composable
private fun MonitorField(labelId: Int, tag: String, value: String, invalid: Boolean, onChange: (String) -> Unit) {
    val label = stringResource(labelId)
    Text(label, color = MaterialTheme.colorScheme.onSurface)
    OutlinedTextField(value = value, onValueChange = { onChange(it.take(4)) }, singleLine = true, isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label }.testTag("monitoring-$tag"))
}

@Composable
private fun MonitorApply(tag: String, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).testTag("monitoring-$tag-apply")) {
        Text(stringResource(R.string.monitoring_apply), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun MonitorInteger(labelId: Int, tag: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val number = text.toIntOrNull()?.takeIf { it in range }
    val label = stringResource(labelId)
    Text(label, color = MaterialTheme.colorScheme.onSurface)
    // The draft and its apply action share a line; the range sits under the field.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it.take(4) }, singleLine = true, isError = number == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text(stringResource(R.string.monitoring_range, range.first, range.last)) },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = label }.testTag("monitoring-$tag"))
        MonitorApply(tag, number != null, Modifier.width(120.dp)) { number?.let(onChange) }
    }
}

@Composable
private fun MonitorColors(labelId: Int, tag: String, value: MonitorColor, onChange: (MonitorColor) -> Unit) {
    Text(stringResource(labelId), color = MaterialTheme.colorScheme.onSurface)
    SettingsSwatches(MonitorColor.entries, value, color = { it.composeColor() },
        label = { color -> stringResource(when (color) {
            MonitorColor.CYAN -> R.string.monitoring_color_cyan
            MonitorColor.YELLOW -> R.string.monitoring_color_yellow
            MonitorColor.RED -> R.string.monitoring_color_red
            MonitorColor.GREEN -> R.string.monitoring_color_green
            MonitorColor.WHITE -> R.string.monitoring_color_white
        }) },
        onSelect = onChange, tag = { "monitoring-$tag-${it.name}" })
}

@Composable
private fun ZebraThresholds(options: MonitoringOptions, onChange: (MonitoringOptions) -> Unit) {
    var low by remember(options.zebraLowPercent) { mutableStateOf(options.zebraLowPercent.toString()) }
    var high by remember(options.zebraHighPercent) { mutableStateOf(options.zebraHighPercent.toString()) }
    val lo = low.toIntOrNull(); val hi = high.toIntOrNull()
    val valid = lo != null && hi != null && lo in 0..99 && hi in 1..100 && lo < hi
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) { MonitorField(R.string.monitoring_zebra_low, "zebra-low", low, !valid) { low = it } }
        Column(Modifier.weight(1f)) { MonitorField(R.string.monitoring_zebra_high, "zebra-high", high, !valid) { high = it } }
    }
    Text(stringResource(R.string.monitoring_zebra_order), color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else LocalCineColors.current.pending)
    MonitorApply("zebra", valid) { if (valid) onChange(options.copy(zebraLowPercent = requireNotNull(lo), zebraHighPercent = requireNotNull(hi))) }
}

@Composable
private fun FalseColorThresholds(options: MonitoringOptions, onChange: (MonitoringOptions) -> Unit) {
    var black by remember(options.falseColorBlackPercent) { mutableStateOf(options.falseColorBlackPercent.toString()) }
    var shadow by remember(options.falseColorShadowPercent) { mutableStateOf(options.falseColorShadowPercent.toString()) }
    var highlight by remember(options.falseColorHighlightPercent) { mutableStateOf(options.falseColorHighlightPercent.toString()) }
    var clip by remember(options.falseColorClipPercent) { mutableStateOf(options.falseColorClipPercent.toString()) }
    val values = listOf(black, shadow, highlight, clip).map { it.toIntOrNull() }
    val valid = values.all { it != null && it in 0..100 } && values.zipWithNext().all { (a, b) -> requireNotNull(a) < requireNotNull(b) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) { MonitorField(R.string.monitoring_false_black, "false-black", black, !valid) { black = it } }
        Column(Modifier.weight(1f)) { MonitorField(R.string.monitoring_false_shadow, "false-shadow", shadow, !valid) { shadow = it } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) { MonitorField(R.string.monitoring_false_highlight, "false-highlight", highlight, !valid) { highlight = it } }
        Column(Modifier.weight(1f)) { MonitorField(R.string.monitoring_false_clip, "false-clip", clip, !valid) { clip = it } }
    }
    Text(stringResource(R.string.monitoring_false_order), color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else LocalCineColors.current.pending,
        modifier = Modifier.testTag("monitoring-false-order"))
    MonitorApply("false", valid) {
        if (valid) onChange(options.copy(falseColorBlackPercent = requireNotNull(values[0]), falseColorShadowPercent = requireNotNull(values[1]),
            falseColorHighlightPercent = requireNotNull(values[2]), falseColorClipPercent = requireNotNull(values[3])))
    }
}
