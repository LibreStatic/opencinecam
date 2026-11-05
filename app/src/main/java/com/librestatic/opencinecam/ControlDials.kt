/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam

import kotlinx.coroutines.flow.first
import androidx.compose.ui.text.style.TextAlign
import android.hardware.camera2.CaptureRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.toSpec
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.CaptureFrameRate
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ShutterUnit
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import com.librestatic.opencinecam.camera.label
import com.librestatic.opencinecam.camera.snapKelvinTo100
import com.librestatic.opencinecam.camera.KELVIN_PRESETS
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A capture panel's content. It fills the width its host gives it and scrolls inside, so the host
 * only bounds its height. [showHeader] false leaves the title row and close button to a host that
 * draws its own (the capture context pane); the content then starts with the controls.
 */
@Composable
internal fun ManualControlDial(
    control: ControlDial,
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    showHeader: Boolean = true,
    onClose: () -> Unit,
) {
    if (state.descriptor == null) return
    CompositionLocalProvider(LocalPanelHeaderVisible provides (showHeader && LocalPanelHeaderVisible.current)) {
        when (control) {
            ControlDial.RESOLUTION -> ResolutionDial(state, binder, settings.videoProjectRate, onClose)
            ControlDial.FPS -> FpsDial(state, binder, settings.videoProjectRate, onClose)
            ControlDial.WB -> WbDial(state, binder, onClose)
            ControlDial.EV -> EvDial(state, binder, onClose)
            ControlDial.FOCUS -> FocusPullDial(state, binder, settings, onSettingsChanged, onClose)
            ControlDial.INT -> IntervalometerDial(state, settings, onSettingsChanged, onClose)
            ControlDial.ISO -> ExposureDial(iso = true, state, binder, settings, onSettingsChanged, onClose)
            ControlDial.SHUTTER -> ExposureDial(iso = false, state, binder, settings, onSettingsChanged, onClose)
        }
    }
}

/** High-speed sessions leave exposure and white balance to the camera. */
private fun CameraUiState.highSpeedSession(): Boolean =
    activeVideoProfile?.constrainedHighSpeed == true || activeLogProfile?.constrainedHighSpeed == true

/** The one large value a panel is about, centred over its controls. */
@Composable
private fun PanelReadout(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth().testTag("panel-readout"))
}

/** Range ends under a slider, with an AUTO reset between them when [onAuto] is set. */
@Composable
private fun PanelRangeRow(start: String, end: String, onAuto: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(start, color = Muted, fontSize = 12.sp)
        if (onAuto != null) TextButton(onClick = onAuto, modifier = Modifier.heightIn(min = 48.dp).testTag("panel-auto")) {
            Text(stringResource(R.string.auto_value), color = VerifiedCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(end, color = Muted, fontSize = 12.sp)
    }
}

/** ISO or shutter. Where the camera cannot take either value by hand the panel says so instead. */
@Composable
private fun ExposureDial(
    iso: Boolean,
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    onClose: () -> Unit,
) {
    val descriptor = state.descriptor ?: return
    val caps = descriptor.exposureCapabilities
    val manual = caps.supports(ExposureMode.MANUAL)
    val priority = if (iso) ExposureMode.ISO_PRIORITY else ExposureMode.SHUTTER_PRIORITY
    PanelColumn(Modifier.testTag(if (iso) "iso-panel" else "shutter-panel")) {
        PanelHeader(stringResource(if (iso) R.string.panel_title_iso else R.string.panel_title_shutter), onClose)
        if (state.highSpeedSession()) {
            PanelNote(stringResource(R.string.panel_high_speed_auto), Modifier.testTag("exposure-unavailable"))
            return@PanelColumn
        }
        if (!manual && !caps.supports(priority)) {
            PanelNote(stringResource(if (iso) R.string.exposure_manual_unavailable_iso else R.string.exposure_manual_unavailable_shutter),
                Modifier.testTag("exposure-unavailable"))
            return@PanelColumn
        }
        val isoMin = descriptor.sensitivityRange?.lower?.toDouble() ?: 50.0
        val isoMax = descriptor.sensitivityRange?.upper?.toDouble() ?: 6400.0
        val timeMin = descriptor.exposureTimeRangeNs?.lower?.toDouble() ?: 100_000.0
        val timeMax = descriptor.exposureTimeRangeNs?.upper?.toDouble() ?: 1_000_000_000.0
        val mode = settings.exposure.mode
        // The camera picks this value itself in full auto and in the other value's priority mode.
        val autoHere = mode == ExposureMode.AUTO || mode == (if (iso) ExposureMode.SHUTTER_PRIORITY else ExposureMode.ISO_PRIORITY)
        val angle = !iso && settings.exposure.shutterUnit == ShutterUnit.ANGLE
        val isoValue = if (autoHere) state.sensitivityIso ?: state.requestedIso else state.requestedIso ?: state.sensitivityIso
        val timeValue = if (autoHere) state.exposureTimeNs ?: state.requestedExposureTimeNs else state.requestedExposureTimeNs ?: state.exposureTimeNs
        val value = when {
            iso -> isoValue?.let { "ISO $it" }
            angle && !autoHere -> String.format(java.util.Locale.ROOT, "%.1f°", settings.exposure.angleTenths / 10f)
            else -> timeValue?.let(::formatShutter)
        }
        val autoLabel = stringResource(R.string.auto_value)
        PanelReadout(
            when {
                value == null -> autoLabel
                autoHere -> "$value · $autoLabel"
                else -> value
            },
            color = if (autoHere) VerifiedCyan else Amber,
        )
        CineSlider(
            value = when {
                iso -> logPosition((isoValue ?: isoMin.toInt()).toDouble(), isoMin, isoMax)
                angle -> (settings.exposure.angleTenths - 1) / 3599f
                else -> logPosition((timeValue ?: 16_666_667L).toDouble(), timeMin, timeMax)
            },
            onValueChange = { position ->
                if (iso) {
                    onSettingsChanged(settings.copy(exposure = settings.exposure.copy(
                        mode = if (mode == ExposureMode.ISO_PRIORITY || !manual) ExposureMode.ISO_PRIORITY else ExposureMode.MANUAL,
                        iso = logValue(position, isoMin, isoMax).toInt(),
                        timeNs = if (mode == ExposureMode.AUTO) state.exposureTimeNs ?: settings.exposure.timeNs else settings.exposure.timeNs,
                    )))
                } else {
                    val intent = settings.exposure.copy(
                        mode = if (mode == ExposureMode.SHUTTER_PRIORITY || !manual) ExposureMode.SHUTTER_PRIORITY else ExposureMode.MANUAL,
                        iso = if (mode == ExposureMode.AUTO) state.sensitivityIso ?: settings.exposure.iso else settings.exposure.iso,
                    )
                    onSettingsChanged(settings.copy(exposure = if (angle) {
                        intent.copy(angleTenths = (position * 3599 + 1).roundToInt().coerceIn(1, 3600))
                    } else intent.copy(timeNs = logValue(position, timeMin, timeMax).toLong())))
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag(if (iso) "iso-slider" else "shutter-slider"),
        )
        PanelRangeRow(
            start = when { iso -> isoMin.toInt().toString(); angle -> "0.1°"; else -> formatShutter(timeMin.toLong()) },
            end = when { iso -> isoMax.toInt().toString(); angle -> "360°"; else -> formatShutter(timeMax.toLong()) },
            // AUTO hands the value back to the camera and keeps the panel open on the live reading.
            onAuto = { binder?.setManualExposure(null, null) },
        )
    }
}

@Composable
private fun EvDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    onClose: () -> Unit,
) {
    PanelColumn(Modifier.testTag("ev-panel")) {
        PanelHeader(stringResource(R.string.panel_title_ev), onClose)
        val range = state.aeCompensationIndexRange
        if (range == null || range.last <= range.first) {
            PanelNote(stringResource(R.string.ev_unavailable), Modifier.testTag("ev-unavailable"))
            return@PanelColumn
        }
        val step = state.descriptor?.aeCompensationStep?.takeIf { it > 0f } ?: 1f
        val current = state.requestedAeCompensationIndex.coerceIn(range.first, range.last)
        PanelReadout(formatEv(current * step), color = if (current == 0) VerifiedCyan else Amber)
        CineSlider(
            value = current.toFloat(),
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            onValueChange = { index -> binder?.setExposureCompensation(index.roundToInt()) },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("ev-slider"),
        )
        PanelRangeRow(formatEv(range.first * step), formatEv(range.last * step)) { binder?.setExposureCompensation(0) }
    }
}

/**
 * RES: sizes grouped by aspect, each tile its short name over its pixels. Photo modes pick the
 * still size; framing stays the photo aspect crop, so stills list the sensor's own aspect.
 */
@Composable
private fun ResolutionDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    projectRate: CaptureFrameRate,
    onClose: () -> Unit,
) {
    // Slow motion runs on VIDEO, so only the displayed mode tells it apart.
    val slowMotion = LocalModeSelection.current?.displayed == CaptureMode.SLOW_MOTION
    val mode = if (slowMotion) CaptureMode.SLOW_MOTION else state.selectedMode
    val photo = mode in CameraUiState.photoResolutionModes
    val sizes = state.resolutionChoices(mode, projectRate)
    val active = if (photo) state.activeStillSize else state.targetVideoWidth to state.targetVideoHeight
    val enabled = !state.structuralSettingsFrozen && state.phase != CameraUiPhase.CAPTURING && mode != CaptureMode.RAW_PHOTO
    PanelColumn(Modifier.testTag("resolution-panel")) {
        PanelHeader(stringResource(R.string.panel_title_resolution), onClose)
        groupResolutions(sizes).forEach { group ->
            PanelSectionLabel(group.aspect.label ?: stringResource(R.string.resolution_group_open),
                Modifier.testTag("resolution-group-${group.aspect.name.lowercase()}"))
            ChoiceGrid(group.sizes, minTileWidth = 96.dp, maxColumns = 3) { (width, height), modifier ->
                val name = if (photo) megapixelName(width, height) else resolutionShortName(width, height)
                ChoiceTile(name, (width to height) == active, modifier, enabled = enabled, detail = formatFrameSize(width, height)) {
                    when {
                        photo -> binder?.selectPhotoResolution(width, height)
                        slowMotion -> slowMotionRateAt(state.availableVideoProfiles.map { it.toSpec() }, width, height, state.targetFps, projectRate)
                            ?.let { fps -> binder?.selectVideoResolution(width, height, fps) }
                        else -> binder?.selectVideoResolution(width, height)
                    }
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun FpsDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    projectRate: CaptureFrameRate,
    onClose: () -> Unit,
) {
    // Slow motion conforms the capture rate to the project rate: a rate at or below it records in real time.
    val slowMotion = LocalModeSelection.current?.displayed == CaptureMode.SLOW_MOTION
    val log = state.selectedMode.usesLogGraph
    val logSpecs = state.availableLogProfiles.map { it.toSpec() }
    val videoSpecs = state.availableVideoProfiles.map { it.toSpec() }
    val modeRates = when {
        log -> VideoGeometryPolicy.unionLogFps(logSpecs)
        state.selectedMode in CameraUiState.videoProfileModes -> VideoGeometryPolicy.unionFps(videoSpecs)
        else -> state.availableTargetFps
    }
    val offeredAtSize = if (log) VideoGeometryPolicy.supportedLogFps(logSpecs, state.targetVideoWidth, state.targetVideoHeight)
        else VideoGeometryPolicy.supportedFps(videoSpecs, state.targetVideoWidth, state.targetVideoHeight)
    val options = modeRates.map { fps ->
        val logProfile = state.availableLogProfiles.firstOrNull {
            log && it.size.width == state.targetVideoWidth && it.size.height == state.targetVideoHeight && it.fps == fps
        }
        FpsOption(
            fps = fps,
            offered = fps in offeredAtSize && (!slowMotion || slowsDown(fps, projectRate)),
            highSpeed = logProfile?.constrainedHighSpeed == true || state.availableVideoProfiles.any {
                !log && it.size.width == state.targetVideoWidth && it.size.height == state.targetVideoHeight && it.fps == fps && it.constrainedHighSpeed
            },
            ispHighRate = logProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP,
        )
    }
    PanelColumn(Modifier.testTag("fps-panel")) {
        PanelHeader(stringResource(R.string.panel_title_fixed_fps), onClose)
        ChoiceGrid(options, minTileWidth = 72.dp, maxColumns = 6) { option, modifier ->
            val label = when {
                option.ispHighRate -> "${option.fps} HFR"
                option.highSpeed -> "${option.fps} HS"
                else -> option.fps.toString()
            }
            ChoiceTile(label, option.fps == state.targetFps, modifier, enabled = option.offered) {
                binder?.selectTargetFps(option.fps)
                onClose()
            }
        }
        // Notes only describe what this grid offers: the viewfinder rate when a high-speed rate
        // can be picked here, and greyed-out rates only when some are.
        if (fpsHighSpeedNoteApplies(options)) {
            // Camera2 feeds the preview one frame per high-speed batch, so the viewfinder of a 120 or
            // 240 fps session runs at 30 fps while the file keeps every frame.
            PanelNote(stringResource(R.string.fps_high_speed_preview_note), Modifier.testTag("fps-high-speed-note"))
        }
        if (slowMotion && options.any { !slowsDown(it.fps, projectRate) }) {
            PanelNote(stringResource(R.string.fps_slow_motion_needs_faster_rate, projectRate.projectLabel()),
                Modifier.testTag("fps-slow-motion-note"))
        }
        if (options.any { it.fps !in offeredAtSize }) {
            PanelNote(stringResource(R.string.fps_unavailable_at_size, formatFrameSize(state.targetVideoWidth, state.targetVideoHeight)),
                Modifier.testTag("fps-unavailable-note"))
        }
    }
}

@Composable
private fun WbDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    onClose: () -> Unit,
) {
    val descriptor = state.descriptor
    val kelvinRange = descriptor?.kelvinRange
    val selection = state.requestedWhiteBalance
    val retainedTint = (state.effectiveSettings?.whiteBalance as? WhiteBalanceSelection.Kelvin)?.tint
        ?: (selection as? WhiteBalanceSelection.Kelvin)?.tint ?: 0
    PanelColumn(Modifier.testTag("wb-panel")) {
        PanelHeader(stringResource(R.string.panel_title_white_balance), onClose)
        if (state.highSpeedSession()) {
            PanelNote(stringResource(R.string.panel_high_speed_auto), Modifier.testTag("wb-unavailable"))
            return@PanelColumn
        }
        if (kelvinRange != null) {
            val auto = selection is WhiteBalanceSelection.Auto
            val displayK = (selection as? WhiteBalanceSelection.Kelvin)?.kelvin
                ?: KELVIN_PRESETS.firstOrNull { it in kelvinRange } ?: kelvinRange.first
            PanelReadout(if (auto) stringResource(R.string.auto_value) else "${displayK}K", color = if (auto) VerifiedCyan else Amber)
            // Kelvin slider in 100 K steps within the device range.
            if (!auto) CineSlider(
                value = displayK.toFloat(),
                valueRange = kelvinRange.first.toFloat()..kelvinRange.last.toFloat(),
                steps = ((kelvinRange.last - kelvinRange.first) / 100 - 1).coerceAtLeast(0),
                onValueChange = { raw ->
                    snapKelvinTo100(raw.toInt(), kelvinRange)?.let { binder?.setWhiteBalance(WhiteBalanceSelection.Kelvin(it, retainedTint)) }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("wb-kelvin-slider"),
            )
            ChoiceGrid(KELVIN_PRESETS, minTileWidth = 72.dp, maxColumns = 4, balanced = true) { preset, modifier ->
                val selected = selection is WhiteBalanceSelection.Kelvin && selection.kelvin == preset
                ChoiceTile("${preset}K", selected, modifier, enabled = preset in kelvinRange) {
                    binder?.setWhiteBalance(WhiteBalanceSelection.Kelvin(preset, retainedTint))
                }
            }
            PanelRangeRow("${kelvinRange.first}K", "${kelvinRange.last}K") { binder?.setWhiteBalance(WhiteBalanceSelection.Auto) }
            // Tint shifts a Kelvin value between green and magenta, where the camera offers it.
            if (descriptor.tintSupported && selection is WhiteBalanceSelection.Kelvin) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PanelSectionLabel(stringResource(R.string.wb_tint), Modifier.weight(1f))
                    Text(if (selection.tint > 0) "+${selection.tint}" else selection.tint.toString(), color = Amber,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("wb-tint-value"))
                }
                CineSlider(
                    value = selection.tint.toFloat(),
                    valueRange = -50f..50f,
                    steps = 99,
                    onValueChange = { binder?.setWhiteBalance(selection.copy(tint = it.roundToInt().coerceIn(-50, 50))) },
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("wb-tint-slider"),
                )
                PanelRangeRow(stringResource(R.string.wb_tint_green), stringResource(R.string.wb_tint_magenta))
            }
        } else {
            // Cameras without colour-temperature control offer the classic presets only.
            val offered = descriptor?.availableAwbModes.orEmpty()
            val choices = listOf(
                null to R.string.auto_value,
                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to R.string.wb_preset_daylight,
                CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to R.string.wb_preset_cloudy,
                CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to R.string.wb_preset_tungsten,
                CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to R.string.wb_preset_fluorescent,
            ).filter { (mode, _) -> mode == null || offered.isEmpty() || mode in offered }
            ChoiceGrid(choices, minTileWidth = 96.dp, maxColumns = 3) { (mode, label), modifier ->
                val selected = if (mode == null) selection is WhiteBalanceSelection.Auto
                    else selection is WhiteBalanceSelection.Preset && selection.awbMode == mode
                ChoiceTile(stringResource(label), selected, modifier) {
                    binder?.setWhiteBalance(if (mode != null) WhiteBalanceSelection.Preset(mode) else WhiteBalanceSelection.Auto)
                }
            }
        }
    }
}

@Composable
private fun IntervalometerDial(state: CameraUiState, settings: CameraSettings, onSettingsChanged: (CameraSettings) -> Unit, onClose: () -> Unit) {
    PanelColumn(Modifier.testTag("interval-panel")) {
        PanelHeader(stringResource(R.string.timelapse_interval), onClose)
        TimelapseSettings(state, settings, onSettingsChanged)
    }
}

internal fun formatIntervalShort(intervalMs: Long): String {
    val totalSeconds = intervalMs / 1000.0
    return when {
        totalSeconds < 1.0 -> "%.1fs".format(totalSeconds)
        totalSeconds < 60.0 -> "%ds".format(totalSeconds.toInt())
        totalSeconds < 3600.0 -> {
            val m = totalSeconds.toInt() / 60
            val s = totalSeconds.toInt() % 60
            if (s == 0) "${m}min" else "${m}m${s}s"
        }
        else -> {
            val h = totalSeconds.toInt() / 3600
            val m = (totalSeconds.toInt() % 3600) / 60
            if (m == 0) "${h}h" else "${h}h${m}m"
        }
    }
}

internal fun formatShutter(exposureTimeNs: Long): String {
    if (exposureTimeNs <= 0L) return "—"
    val denominator = 1_000_000_000.0 / exposureTimeNs
    return if (denominator >= 1.0) "1/${denominator.toInt()}" else "${exposureTimeNs / 1_000_000}ms"
}

/** Formats an EV value (e.g. −1.33, +0.5, 0). Returns "0" for null/zero. */
internal fun formatEv(ev: Float?): String {
    val value = ev ?: 0f
    if (value == 0f) return "0"
    val sign = if (value > 0f) "+" else "−"
    val rounded = (abs(value) * 100).roundToInt() / 100f
    val formatted = if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else "%.2f".format(rounded)
    return "$sign$formatted"
}

@Composable
private fun FocusPullDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    onClose: () -> Unit,
) = FocusPanel(state, binder, settings, onSettingsChanged, onClose)

private fun logPosition(value: Double, minimum: Double, maximum: Double): Float {
    if (minimum <= 0.0 || maximum <= minimum) return 0f
    return ((ln(value.coerceIn(minimum, maximum)) - ln(minimum)) / (ln(maximum) - ln(minimum))).toFloat().coerceIn(0f, 1f)
}

private fun logValue(position: Float, minimum: Double, maximum: Double): Double =
    exp(ln(minimum) + position.coerceIn(0f, 1f) * (ln(maximum) - ln(minimum)))
