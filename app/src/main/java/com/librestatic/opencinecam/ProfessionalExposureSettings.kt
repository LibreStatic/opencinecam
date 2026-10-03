/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import android.hardware.camera2.CaptureRequest
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.*
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
internal fun ProfessionalExposureSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val descriptor = state.descriptor
    val hfr = state.activeVideoProfile?.constrainedHighSpeed == true || state.activeLogProfile?.constrainedHighSpeed == true
    val caps = if (hfr) ExposureCapabilities() else descriptor?.exposureCapabilities ?: ExposureCapabilities()
    // One answer for Settings and the capabilities page: controls the camera cannot apply are not shown.
    val available = exposureAvailability(caps)
    val exposure = settings.exposure
    val rate = CaptureFrameRate(state.targetFps)
    val resolved = exposure.resolve(caps, rate)
    fun updateExposure(value: ExposureSelection) = onChange(settings.copy(exposure = value))
    fun timed(value: ExposureSelection): ExposureSelection = value.copy(mode = when {
        available.appliesTime(value.mode) -> value.mode
        available.manual -> ExposureMode.MANUAL
        else -> ExposureMode.SHUTTER_PRIORITY
    })
    val pending = LocalCineColors.current.pending
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSectionTitle(stringResource(R.string.pro_exposure_title), help = stringResource(R.string.pro_exposure_help))
        if (hfr) Text(stringResource(R.string.pro_hfr_unavailable), color = pending, fontSize = 16.sp)
        SettingsChips(stringResource(R.string.settings_mode), ExposureMode.entries, exposure.mode,
            label = { stringResource(it.titleResource()) }, onSelect = { updateExposure(exposure.copy(mode = it)) },
            tag = { "pro-mode-${it.name}" }, enabled = { available.supports(it) })
        when {
            resolved.unavailable -> Text(stringResource(R.string.pro_exposure_unavailable), color = pending, fontSize = 16.sp)
            !available.any && !hfr -> Text(stringResource(R.string.pro_exposure_auto_only), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        available.isoRange?.takeIf { it.first < it.last }?.let { range ->
            val reading = isoReading(available, exposure.mode, exposure.iso, state.sensitivityIso)
            ProSlider(stringResource(R.string.pro_iso), (reading.value ?: exposure.iso).toFloat(), range.first.toFloat()..range.last.toFloat(),
                enabled = !reading.automatic, format = { it.roundToInt().toString() },
                automatic = automaticText(reading) { it.toString() }, onChange = { updateExposure(exposure.copy(iso = it.roundToInt())) })
        }
        if (available.timeAdjustable) {
            val reading = timeReading(available, exposure.mode, exposure.requestedTimeNs(rate), state.exposureTimeNs)
            SettingsChips(stringResource(R.string.pro_shutter_unit), ShutterUnit.entries, exposure.shutterUnit,
                label = { stringResource(if (it == ShutterUnit.TIME) R.string.pro_time else R.string.pro_angle) },
                onSelect = { updateExposure(exposure.copy(shutterUnit = it)) }, tag = { "pro-shutter-unit-${it.name}" })
            if (exposure.shutterUnit == ShutterUnit.ANGLE) {
                fun angleTenths(ns: Long) = (3600.0 * ns / rate.frameDurationNs).roundToInt().coerceIn(1, 3600)
                ProSlider(stringResource(R.string.pro_angle), (reading.value?.takeIf { reading.automatic }?.let(::angleTenths) ?: exposure.angleTenths).toFloat(), 1f..3600f,
                    format = { String.format(Locale.ROOT, "%.1f°", it / 10) },
                    automatic = automaticText(reading) { String.format(Locale.ROOT, "%.1f°", angleTenths(it) / 10.0) },
                    onChange = { updateExposure(timed(exposure.copy(angleTenths = it.roundToInt()))) })
                SettingsChips(stringResource(R.string.pro_angle_presets), listOf(900, 1440, 1728, 1800, 2160, 2700, 3600), exposure.angleTenths,
                    label = { "${it / 10.0}°" }, onSelect = { updateExposure(timed(exposure.copy(angleTenths = it))) }, tag = { "pro-angle-$it" })
            } else {
                available.timeRangeNs?.let { range ->
                    val upper = range.last.coerceAtMost(rate.frameDurationNs - 100_000L)
                    if (range.first > 0 && upper > range.first) ProSlider(stringResource(R.string.pro_time),
                        log10((reading.value ?: exposure.timeNs).toDouble()).toFloat(),
                        log10(range.first.toDouble()).toFloat()..log10(upper.toDouble()).toFloat(),
                        format = { exposureTimeText(10.0.pow(it.toDouble()).toLong()) }, automatic = automaticText(reading) { shutter(it) },
                        onChange = { updateExposure(timed(exposure.copy(timeNs = 10.0.pow(it.toDouble()).toLong().coerceIn(range.first, upper)))) })
                }
            }
            var flickerActions by remember { mutableStateOf(false) }
            val flickerTitle = stringResource(R.string.pro_flicker_suggestions)
            SettingsValueRow(flickerTitle, null, Modifier.testTag("pro-flicker-row")) { flickerActions = true }
            val flickerSuggestions = listOf(50 to 10_000_000L, 60 to 8_333_333L).map { (hz, time) ->
                SettingsAction(stringResource(R.string.pro_shutter_suggestion, hz), "pro-flicker-$hz") {
                    updateExposure(timed(exposure.copy(shutterUnit = ShutterUnit.TIME, timeNs = time)))
                }
            }
            if (flickerActions) SettingsActionsDialog(flickerTitle, flickerSuggestions) { flickerActions = false }
        }
        if (resolved.clamped) Text(stringResource(R.string.pro_clamped), color = pending, fontSize = 14.sp)
        CameraReportsLine(exposureReadbackDifferences(resolved, state.reportedExposureMode, state.sensitivityIso, state.exposureTimeNs, state.reportedAntibanding))
        SettingsChips(stringResource(R.string.pro_antibanding), Antibanding.entries, exposure.antibanding,
            label = { it.displayLabel() }, onSelect = { updateExposure(exposure.copy(antibanding = it)) },
            tag = { "pro-antibanding-${it.name}" }, enabled = { it in caps.antibanding })
        SettingsHelp(stringResource(R.string.pro_flicker_help))
        RecordingWhiteBalanceSettings(state, settings, onChange)
        val presets = if (hfr) emptySet() else descriptor?.availableAwbModes.orEmpty()
        val kelvinRange = descriptor?.kelvinRange.takeUnless { hfr }
        val wb = settings.whiteBalance
        // The Kelvin entry is the current Kelvin selection when there is one, so it stays selected
        // and keeps its temperature and tint when picked again.
        val kelvinChoice = (wb as? WhiteBalanceSelection.Kelvin) ?: WhiteBalanceSelection.Kelvin(kelvinRange?.let { 5600.coerceIn(it) } ?: 5600)
        val wbChoices = listOf<WhiteBalanceSelection>(WhiteBalanceSelection.Auto) +
            listOf(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
                CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT).map { WhiteBalanceSelection.Preset(it) } +
            kelvinChoice
        val wbAuto = stringResource(R.string.pro_wb_auto)
        val temperature = stringResource(R.string.pro_wb_temperature)
        SettingsChips(stringResource(R.string.pro_white_balance), wbChoices, wb,
            label = { when (it) { WhiteBalanceSelection.Auto -> wbAuto; is WhiteBalanceSelection.Preset -> it.label(); is WhiteBalanceSelection.Kelvin -> temperature } },
            onSelect = { onChange(settings.copy(whiteBalance = it)) },
            tag = { when (it) { WhiteBalanceSelection.Auto -> "pro-wb-auto"; is WhiteBalanceSelection.Preset -> "pro-wb-preset${it.awbMode}"; is WhiteBalanceSelection.Kelvin -> "pro-wb-kelvin" } },
            enabled = { when (it) { WhiteBalanceSelection.Auto -> true; is WhiteBalanceSelection.Preset -> it.awbMode in presets; is WhiteBalanceSelection.Kelvin -> kelvinRange != null } })
        val adapted = wb.adaptTo(kelvinRange, descriptor?.tintSupported == true, presets)
        if (adapted != wb) Text(stringResource(R.string.pro_wb_unavailable), color = pending, fontSize = 14.sp)
        if (wb is WhiteBalanceSelection.Kelvin && kelvinRange != null) {
            ProSlider(temperature, wb.kelvin.toFloat(), kelvinRange.first.toFloat()..kelvinRange.last.toFloat(),
                format = { "${it.roundToInt()} K" }, onChange = { onChange(settings.copy(whiteBalance = wb.copy(kelvin = snapKelvinTo100(it.roundToInt(), kelvinRange) ?: wb.kelvin))) })
            if (descriptor?.tintSupported == true) ProSlider(stringResource(R.string.pro_tint), wb.tint.toFloat(), -50f..50f,
                format = { it.roundToInt().toString() }, onChange = { onChange(settings.copy(whiteBalance = wb.copy(tint = it.roundToInt()))) })
        }
        CameraReportsLine(whiteBalanceReadbackDifferences(adapted, state.reportedColorTemperatureK))
        OutlinedButton({ onChange(settings.copy(exposure = exposure.copy(mode = ExposureMode.AUTO), whiteBalance = WhiteBalanceSelection.Auto)) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.pro_restore_auto))
        }
    }
}

internal fun ExposureMode.titleResource(): Int = when (this) {
    ExposureMode.AUTO -> R.string.pro_mode_auto
    ExposureMode.MANUAL -> R.string.pro_mode_manual
    ExposureMode.ISO_PRIORITY -> R.string.pro_mode_iso_priority
    ExposureMode.SHUTTER_PRIORITY -> R.string.pro_mode_shutter_priority
}

@Composable
internal fun Antibanding.displayLabel(): String = when (this) {
    Antibanding.AUTO -> stringResource(R.string.pro_auto)
    Antibanding.OFF -> stringResource(R.string.image_mode_off)
    Antibanding.HZ50 -> "50 Hz"
    Antibanding.HZ60 -> "60 Hz"
}

/** "1/60 · 16.667 ms": the fraction operators read, with the exact time beside it. */
internal fun exposureTimeText(ns: Long): String = "${shutter(ns)} · ${String.format(Locale.ROOT, "%.3f ms", ns / 1_000_000.0)}"

/** The label an automatic reading shows instead of the stored request; null when the mode applies the request. */
@Composable
private fun <T> automaticText(reading: ExposureReading<T>, format: (T) -> String): String? = when {
    !reading.automatic -> null
    reading.value == null -> stringResource(R.string.pro_auto)
    else -> stringResource(R.string.pro_auto_reported, format(reading.value))
}

/**
 * Commits on release rather than enqueueing an unbounded stream of Camera2 updates while dragging.
 * [automatic] replaces the value text until the operator moves the slider.
 */
@Composable
private fun ProSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true,
    format: (Float) -> String, automatic: String? = null, onChange: (Float) -> Unit) {
    var draft by remember(value, range) { mutableFloatStateOf(value.coerceIn(range)) }
    var moved by remember(value, range) { mutableStateOf(false) }
    val text = if (automatic != null && !moved) automatic else format(draft)
    SettingsSliderRow("$label: $text", draft, { draft = it; moved = true }, range, enabled = enabled, onValueChangeFinished = { onChange(draft) })
}

@Composable
internal fun RecordingWhiteBalanceSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsChips(stringResource(R.string.pro_wb_record_policy), RecordingWhiteBalancePolicy.entries, settings.recordingWhiteBalance,
            label = { stringResource(if (it == RecordingWhiteBalancePolicy.CONTINUOUS) R.string.pro_wb_continuous else R.string.pro_wb_lock_record) },
            onSelect = { onChange(settings.copy(recordingWhiteBalance = it)) }, tag = { "pro-wb-policy-${it.name}" })
        SettingsHelp(stringResource(R.string.pro_wb_record_help))
        if (state.descriptor?.awbLockSupported != true || state.activeVideoProfile?.constrainedHighSpeed == true || state.activeLogProfile?.constrainedHighSpeed == true) {
            Text(stringResource(R.string.pro_wb_lock_unavailable), color = LocalCineColors.current.pending, fontSize = 14.sp)
        }
        if (state.structuralSettingsFrozen) Text(stringResource(R.string.pro_wb_record_pending), color = LocalCineColors.current.pending, fontSize = 14.sp)
        Text(stringResource(R.string.pro_wb_lock_reported, stringResource(when (state.reportedAwbLocked) {
            true -> R.string.pro_wb_locked
            false -> R.string.pro_wb_unlocked
            null -> R.string.pro_unknown
        })), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        if (state.recordingWhiteBalanceStatus != RecordingWhiteBalanceStatus.IDLE) Text(stringResource(when (state.recordingWhiteBalanceStatus) {
            RecordingWhiteBalanceStatus.CONVERGING, RecordingWhiteBalanceStatus.LOCKING -> R.string.pro_wb_preparing
            RecordingWhiteBalanceStatus.LOCKED -> R.string.pro_wb_locked
            RecordingWhiteBalanceStatus.FIXED -> R.string.pro_wb_fixed
            else -> R.string.pro_wb_prepare_failed
        }), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
    }
}
