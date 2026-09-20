/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.monitoring_title), color = Color.White, fontSize = 20.sp)
        Text(stringResource(R.string.monitoring_signal_help), color = Color.LightGray)
        Text(stringResource(R.string.monitoring_source_help), color = Color.LightGray)
        MonitorSwitch(R.string.monitoring_waveform, "waveform", options.waveformEnabled) { update(options.copy(waveformEnabled = it)) }
        MonitorSwitch(R.string.monitoring_vectorscope, "vectorscope", options.vectorscopeEnabled) { update(options.copy(vectorscopeEnabled = it)) }
        MonitorSwitch(R.string.monitoring_false_color, "false-color", options.falseColorEnabled) { update(options.copy(falseColorEnabled = it)) }
        MonitorSwitch(R.string.monitoring_zebra, "zebra-enabled", settings.zebraEnabled) { onChange(settings.copy(zebraEnabled = it)) }
        MonitorSwitch(R.string.monitoring_zebra_shadow, "zebra-shadow", options.zebraShadowEnabled) { update(options.copy(zebraShadowEnabled = it)) }
        ZebraThresholds(options, ::update)
        MonitorSwitch(R.string.monitoring_peaking, "peaking-enabled", settings.peakingEnabled) { onChange(settings.copy(peakingEnabled = it)) }
        MonitorInteger(R.string.monitoring_peaking_threshold, "peaking-threshold", options.peakingThreshold, 1..255) {
            update(options.copy(peakingThreshold = it))
        }
        MonitorInteger(R.string.monitoring_opacity, "opacity", options.opacityPercent, 10..100) { update(options.copy(opacityPercent = it)) }
        MonitorColors(R.string.monitoring_zebra_color, "zebra-color", options.zebraColor) { update(options.copy(zebraColor = it)) }
        MonitorColors(R.string.monitoring_peaking_color, "peaking-color", options.peakingColor) { update(options.copy(peakingColor = it)) }
        MonitorColors(R.string.monitoring_luma_color, "luma-color", options.lumaColor) { update(options.copy(lumaColor = it)) }
        Text(stringResource(R.string.monitoring_false_palette), color = Color.White)
        FalseColorPalette.entries.forEach { palette ->
            MonitorChoice(stringResource(if (palette == FalseColorPalette.CLASSIC) R.string.monitoring_palette_classic
                else R.string.monitoring_palette_high_contrast), "palette-${palette.name}", options.falseColorPalette == palette) {
                update(options.copy(falseColorPalette = palette))
            }
        }
        FalseColorThresholds(options, ::update)
        Text(stringResource(R.string.monitoring_frequency_help), color = Color.LightGray)
        MonitorInteger(R.string.monitoring_refresh, "refresh-hz", options.refreshHz, 1..10) { update(options.copy(refreshHz = it)) }
        Text(stringResource(R.string.monitoring_aspect), color = Color.White)
        MonitorAspectGuide.entries.forEach { guide ->
            val label = stringResource(when (guide) {
                MonitorAspectGuide.NONE -> R.string.monitoring_aspect_none
                MonitorAspectGuide.WIDE -> R.string.monitoring_aspect_wide
                MonitorAspectGuide.PHOTO -> R.string.monitoring_aspect_photo
                MonitorAspectGuide.SQUARE -> R.string.monitoring_aspect_square
                MonitorAspectGuide.CINEMA -> R.string.monitoring_aspect_cinema
            })
            MonitorChoice(label, "aspect-${guide.name}", options.aspectGuide == guide) { update(options.copy(aspectGuide = guide)) }
        }
        MonitorSwitch(R.string.monitoring_safe_area, "safe-enabled", options.safeAreaEnabled) { update(options.copy(safeAreaEnabled = it)) }
        MonitorInteger(R.string.monitoring_safe_percent, "safe-percent", options.safeAreaPercent, 50..100) {
            update(options.copy(safeAreaPercent = it))
        }
        Text(stringResource(R.string.monitoring_guides_help), color = Color.LightGray)
    }
}

@Composable
private fun MonitorSwitch(labelId: Int, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val label = stringResource(labelId)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }.testTag("monitoring-$tag"))
    }
}

@Composable
private fun MonitorField(labelId: Int, tag: String, value: String, invalid: Boolean, onChange: (String) -> Unit) {
    val label = stringResource(labelId)
    Text(label, color = Color.White)
    OutlinedTextField(value = value, onValueChange = { onChange(it.take(4)) }, singleLine = true, isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label }.testTag("monitoring-$tag"))
}

@Composable
private fun MonitorApply(tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("monitoring-$tag-apply")) {
        Text(stringResource(R.string.monitoring_apply), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun MonitorInteger(labelId: Int, tag: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val number = text.toIntOrNull()?.takeIf { it in range }
    MonitorField(labelId, tag, text, number == null) { text = it }
    Text(stringResource(R.string.monitoring_range, range.first, range.last), color = Color.LightGray)
    MonitorApply(tag, number != null) { number?.let(onChange) }
}

@Composable
private fun MonitorChoice(label: String, tag: String, checked: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, colors = ButtonDefaults.outlinedButtonColors(
        containerColor = if (checked) Color(0xFF294657) else Color.Transparent,
        contentColor = Color.White), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .semantics { selected = checked }.testTag("monitoring-$tag")) {
        Text(label, modifier = Modifier.weight(1f).testTag("monitoring-$tag-label"), textAlign = TextAlign.Center)
    }
}

@Composable
private fun MonitorColors(labelId: Int, tag: String, value: MonitorColor, onChange: (MonitorColor) -> Unit) {
    Text(stringResource(labelId), color = Color.White)
    MonitorColor.entries.forEach { color ->
        val label = stringResource(when (color) {
            MonitorColor.CYAN -> R.string.monitoring_color_cyan
            MonitorColor.YELLOW -> R.string.monitoring_color_yellow
            MonitorColor.RED -> R.string.monitoring_color_red
            MonitorColor.GREEN -> R.string.monitoring_color_green
            MonitorColor.WHITE -> R.string.monitoring_color_white
        })
        MonitorChoice(label, "$tag-${color.name}", value == color) { onChange(color) }
    }
}

@Composable
private fun ZebraThresholds(options: MonitoringOptions, onChange: (MonitoringOptions) -> Unit) {
    var low by remember(options.zebraLowPercent) { mutableStateOf(options.zebraLowPercent.toString()) }
    var high by remember(options.zebraHighPercent) { mutableStateOf(options.zebraHighPercent.toString()) }
    val lo = low.toIntOrNull(); val hi = high.toIntOrNull()
    val valid = lo != null && hi != null && lo in 0..99 && hi in 1..100 && lo < hi
    MonitorField(R.string.monitoring_zebra_low, "zebra-low", low, !valid) { low = it }
    MonitorField(R.string.monitoring_zebra_high, "zebra-high", high, !valid) { high = it }
    Text(stringResource(R.string.monitoring_zebra_order), color = if (valid) Color.LightGray else Color(0xFFFFCF66))
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
    MonitorField(R.string.monitoring_false_black, "false-black", black, !valid) { black = it }
    MonitorField(R.string.monitoring_false_shadow, "false-shadow", shadow, !valid) { shadow = it }
    MonitorField(R.string.monitoring_false_highlight, "false-highlight", highlight, !valid) { highlight = it }
    MonitorField(R.string.monitoring_false_clip, "false-clip", clip, !valid) { clip = it }
    Text(stringResource(R.string.monitoring_false_order), color = if (valid) Color.LightGray else Color(0xFFFFCF66),
        modifier = Modifier.testTag("monitoring-false-order"))
    MonitorApply("false", valid) {
        if (valid) onChange(options.copy(falseColorBlackPercent = requireNotNull(values[0]), falseColorShadowPercent = requireNotNull(values[1]),
            falseColorHighlightPercent = requireNotNull(values[2]), falseColorClipPercent = requireNotNull(values[3])))
    }
}
