/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.hardware.camera2.CaptureRequest
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    val exposure = settings.exposure
    val resolved = exposure.resolve(caps, CaptureFrameRate(state.targetFps))
    val supportsTime = caps.supports(ExposureMode.MANUAL) || caps.supports(ExposureMode.SHUTTER_PRIORITY)
    fun updateExposure(value: ExposureSelection) = onChange(settings.copy(exposure = value))
    fun timed(value: ExposureSelection): ExposureSelection = value.copy(mode = when {
        value.mode in setOf(ExposureMode.MANUAL, ExposureMode.SHUTTER_PRIORITY) -> value.mode
        caps.supports(ExposureMode.MANUAL) -> ExposureMode.MANUAL
        else -> ExposureMode.SHUTTER_PRIORITY
    })
    val unknown = stringResource(R.string.pro_unknown)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.pro_exposure_title), color = Color.White, fontSize = 20.sp)
        SettingsHelp(stringResource(R.string.pro_exposure_help))
        if (hfr) Text(stringResource(R.string.pro_hfr_unavailable), color = Color(0xFFFFCF66), fontSize = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposureMode.entries.forEach { mode ->
                FilterChip(selected = exposure.mode == mode, onClick = { updateExposure(exposure.copy(mode = mode)) },
                    enabled = caps.supports(mode), modifier = Modifier.heightIn(min = 48.dp), label = { Text(stringResource(mode.titleResource())) })
            }
        }
        if (resolved.unavailable) Text(stringResource(R.string.pro_exposure_unavailable), color = Color(0xFFFFCF66), fontSize = 16.sp)
        caps.isoRange?.takeIf { it.first < it.last }?.let { range ->
            ProSlider(stringResource(R.string.pro_iso), exposure.iso.toFloat(), range.first.toFloat()..range.last.toFloat(),
                enabled = caps.supports(exposure.mode) && exposure.mode in setOf(ExposureMode.MANUAL, ExposureMode.ISO_PRIORITY),
                format = { it.roundToInt().toString() }, onChange = { updateExposure(exposure.copy(iso = it.roundToInt())) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShutterUnit.entries.forEach { unit ->
                FilterChip(exposure.shutterUnit == unit, { updateExposure(exposure.copy(shutterUnit = unit)) }, modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(stringResource(if (unit == ShutterUnit.TIME) R.string.pro_time else R.string.pro_angle)) })
            }
        }
        if (exposure.shutterUnit == ShutterUnit.ANGLE) {
            ProSlider(stringResource(R.string.pro_angle), exposure.angleTenths.toFloat(), 1f..3600f, supportsTime,
                format = { String.format(Locale.ROOT, "%.1f°", it / 10) }, onChange = { updateExposure(timed(exposure.copy(angleTenths = it.roundToInt()))) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(900, 1440, 1728, 1800, 2160, 2700, 3600).forEach { angle ->
                    FilterChip(exposure.angleTenths == angle, { updateExposure(timed(exposure.copy(angleTenths = angle))) }, enabled = supportsTime,
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text("${angle / 10.0}°") })
                }
            }
        } else {
            caps.timeRangeNs?.let { range ->
                val upper = range.last.coerceAtMost(CaptureFrameRate(state.targetFps).frameDurationNs - 100_000L)
                if (range.first > 0 && upper > range.first) ProSlider(stringResource(R.string.pro_time), log10(exposure.timeNs.toDouble()).toFloat(),
                    log10(range.first.toDouble()).toFloat()..log10(upper.toDouble()).toFloat(), supportsTime,
                    format = { String.format(Locale.ROOT, "%.3f ms", 10.0.pow(it.toDouble()) / 1_000_000) },
                    onChange = { updateExposure(timed(exposure.copy(timeNs = 10.0.pow(it.toDouble()).toLong().coerceIn(range.first, upper)))) })
            }
        }
        Text(stringResource(R.string.pro_exposure_values, exposure.requestedTimeNs(CaptureFrameRate(state.targetFps)).toString(),
            resolved.timeNs?.toString() ?: unknown, state.exposureTimeNs?.toString() ?: unknown, state.sensitivityIso?.toString() ?: unknown), color = Color.White, fontSize = 14.sp)
        if (resolved.clamped) Text(stringResource(R.string.pro_clamped), color = Color(0xFFFFCF66), fontSize = 14.sp)
        Text(stringResource(R.string.pro_antibanding), color = Color.White, fontSize = 18.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Antibanding.entries.forEach { band ->
                FilterChip(exposure.antibanding == band, { updateExposure(exposure.copy(antibanding = band)) }, enabled = band in caps.antibanding,
                    modifier = Modifier.heightIn(min = 48.dp), label = { Text(band.displayLabel()) })
            }
        }
        SettingsHelp(stringResource(R.string.pro_flicker_help))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(50 to 10_000_000L, 60 to 8_333_333L).forEach { (hz, time) ->
                OutlinedButton({ updateExposure(timed(exposure.copy(shutterUnit = ShutterUnit.TIME, timeNs = time))) }, enabled = supportsTime,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pro_shutter_suggestion, hz)) }
            }
        }
        Text(stringResource(R.string.pro_reported_modes, state.reportedExposureMode?.let { stringResource(it.titleResource()) } ?: unknown, when (state.reportedAntibanding) {
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO -> "AUTO"
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF -> "OFF"
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ -> "50 Hz"
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ -> "60 Hz"
            else -> unknown
        }), color = Color.LightGray, fontSize = 14.sp)
        Text(stringResource(R.string.pro_white_balance), color = Color.White, fontSize = 18.sp)
        RecordingWhiteBalanceSettings(state, settings, onChange)
        val presets = if (hfr) emptySet() else descriptor?.availableAwbModes.orEmpty()
        val kelvinRange = descriptor?.kelvinRange.takeUnless { hfr }
        val wb = settings.whiteBalance
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(wb == WhiteBalanceSelection.Auto, { onChange(settings.copy(whiteBalance = WhiteBalanceSelection.Auto)) },
                modifier = Modifier.heightIn(min = 48.dp), label = { Text(stringResource(R.string.pro_wb_auto)) })
            listOf(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
                CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT).forEach { mode ->
                val selection = WhiteBalanceSelection.Preset(mode)
                FilterChip(wb == selection, { onChange(settings.copy(whiteBalance = selection)) }, enabled = mode in presets,
                    modifier = Modifier.heightIn(min = 48.dp), label = { Text(selection.label()) })
            }
            FilterChip(wb is WhiteBalanceSelection.Kelvin, { onChange(settings.copy(whiteBalance = (wb as? WhiteBalanceSelection.Kelvin) ?: WhiteBalanceSelection.Kelvin(5600.coerceIn(requireNotNull(kelvinRange))))) },
                enabled = kelvinRange != null, modifier = Modifier.heightIn(min = 48.dp), label = { Text("Kelvin / CCT") })
        }
        val adapted = wb.adaptTo(kelvinRange, descriptor?.tintSupported == true, presets)
        if (adapted != wb) Text(stringResource(R.string.pro_wb_unavailable), color = Color(0xFFFFCF66), fontSize = 14.sp)
        if (wb is WhiteBalanceSelection.Kelvin && kelvinRange != null) {
            ProSlider("Kelvin", wb.kelvin.toFloat(), kelvinRange.first.toFloat()..kelvinRange.last.toFloat(),
                format = { "${it.roundToInt()} K" }, onChange = { onChange(settings.copy(whiteBalance = wb.copy(kelvin = snapKelvinTo100(it.roundToInt(), kelvinRange) ?: wb.kelvin))) })
            ProSlider(stringResource(R.string.pro_tint), wb.tint.toFloat(), -50f..50f, descriptor?.tintSupported == true,
                format = { it.roundToInt().toString() }, onChange = { onChange(settings.copy(whiteBalance = wb.copy(tint = it.roundToInt()))) })
        }
        Text(stringResource(R.string.pro_wb_reported, state.reportedColorTemperatureK?.toString() ?: unknown, state.reportedColorTint?.toString() ?: unknown), color = Color.LightGray, fontSize = 14.sp)
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
internal fun Antibanding.displayLabel(): String = when (this) { Antibanding.AUTO -> "AUTO"; Antibanding.OFF -> "OFF"; Antibanding.HZ50 -> "50 Hz"; Antibanding.HZ60 -> "60 Hz" }

/** Commit on release rather than enqueueing an unbounded stream of Camera2 updates while dragging. */
@Composable
private fun ProSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true,
    format: (Float) -> String, onChange: (Float) -> Unit) {
    var draft by remember(value, range) { mutableFloatStateOf(value.coerceIn(range)) }
    Column {
        Text("$label: ${format(draft)}", color = Color.White, fontSize = 16.sp)
        Slider(value = draft, onValueChange = { draft = it }, onValueChangeFinished = { onChange(draft) }, enabled = enabled,
            valueRange = range, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label })
    }
}

@Composable
internal fun RecordingWhiteBalanceSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pro_wb_record_policy), color = Color.White, fontSize = 18.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordingWhiteBalancePolicy.entries.forEach { policy ->
                FilterChip(settings.recordingWhiteBalance == policy, { onChange(settings.copy(recordingWhiteBalance = policy)) },
                    modifier = Modifier.heightIn(min = 48.dp), label = {
                        Text(stringResource(if (policy == RecordingWhiteBalancePolicy.CONTINUOUS) R.string.pro_wb_continuous else R.string.pro_wb_lock_record), fontSize = 16.sp)
                    })
            }
        }
        SettingsHelp(stringResource(R.string.pro_wb_record_help))
        if (state.descriptor?.awbLockSupported != true || state.activeVideoProfile?.constrainedHighSpeed == true || state.activeLogProfile?.constrainedHighSpeed == true) {
            Text(stringResource(R.string.pro_wb_lock_unavailable), color = Color(0xFFFFCF66), fontSize = 14.sp)
        }
        if (state.structuralSettingsFrozen) Text(stringResource(R.string.pro_wb_record_pending), color = Color(0xFFFFCF66), fontSize = 14.sp)
        Text(stringResource(R.string.pro_wb_lock_reported, stringResource(when (state.reportedAwbLocked) {
            true -> R.string.pro_wb_locked
            false -> R.string.pro_wb_unlocked
            null -> R.string.pro_unknown
        })), color = Color.LightGray, fontSize = 14.sp)
        if (state.recordingWhiteBalanceStatus != RecordingWhiteBalanceStatus.IDLE) Text(stringResource(when (state.recordingWhiteBalanceStatus) {
            RecordingWhiteBalanceStatus.CONVERGING, RecordingWhiteBalanceStatus.LOCKING -> R.string.pro_wb_preparing
            RecordingWhiteBalanceStatus.LOCKED -> R.string.pro_wb_locked
            RecordingWhiteBalanceStatus.FIXED -> R.string.pro_wb_fixed
            else -> R.string.pro_wb_prepare_failed
        }), color = Color.White, fontSize = 16.sp)
    }
}
