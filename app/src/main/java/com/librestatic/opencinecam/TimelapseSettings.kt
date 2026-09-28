/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One canonical editor, reused in Settings and the capture interval panel. */
@Composable
internal fun TimelapseSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.timelapse_settings_title), fontSize = 20.sp)
        SettingsHelp(stringResource(R.string.timelapse_settings_help) + "\n\n" + stringResource(R.string.timelapse_pause_help), tag = "timelapse-pause-help")
        if (state.structuralSettingsFrozen) Text(stringResource(R.string.timelapse_settings_pending), fontSize = 16.sp,
            modifier = Modifier.testTag("timelapse-pending"))
        IntervalNumber("timelapse-interval", stringResource(R.string.timelapse_interval_ms), settings.timelapseIntervalMs, 100L..3_600_000L) {
            onChange(settings.copy(timelapseIntervalMs = it))
        }
        ProjectRateEditor(settings.timelapseProjectRate, "timelapse") {
            onChange(settings.copy(timelapseFps = it.numerator, timelapseFpsDenominator = it.denominator))
        }
        state.recordingProjectRate?.takeIf { state.selectedMode == CaptureMode.TIME_LAPSE }?.let {
            Text(stringResource(R.string.project_active_rate, it.projectLabel()), fontSize = 16.sp, modifier = Modifier.testTag("timelapse-active-project"))
        }
        Text(stringResource(R.string.timelapse_resolution, settings.timelapseWidth, settings.timelapseHeight), fontSize = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.descriptor?.videoProfiles.orEmpty().filter { !it.constrainedHighSpeed && it.fps == 30 }
                .distinctBy { it.size }.forEach { profile ->
                    FilterChip(selected = settings.timelapseWidth == profile.size.width && settings.timelapseHeight == profile.size.height,
                        onClick = { onChange(settings.copy(timelapseWidth = profile.size.width, timelapseHeight = profile.size.height)) },
                        label = { Text("${profile.size.width}×${profile.size.height}", fontSize = 16.sp) }, modifier = Modifier.heightIn(min = 48.dp))
                }
        }
        Text(stringResource(R.string.timelapse_limit), fontSize = 18.sp)
        TimeLapseLimitMode.entries.forEach { mode ->
            val label = when (mode) {
                TimeLapseLimitMode.UNLIMITED -> R.string.timelapse_limit_unlimited
                TimeLapseLimitMode.FRAME_COUNT -> R.string.timelapse_limit_frame_count
                TimeLapseLimitMode.DURATION -> R.string.timelapse_limit_duration
            }
            FilterChip(selected = settings.timelapseLimitMode == mode, onClick = { onChange(settings.copy(timelapseLimitMode = mode)) },
                label = { Text(stringResource(label), fontSize = 16.sp) }, modifier = Modifier.heightIn(min = 48.dp).testTag("timelapse-limit-$mode"))
        }
        if (settings.timelapseLimitMode == TimeLapseLimitMode.FRAME_COUNT) {
            IntervalNumber("timelapse-frames", stringResource(R.string.timelapse_frame_count), settings.timelapseFrameCount.toLong(), 2L..100_000L) {
                onChange(settings.copy(timelapseFrameCount = it.toInt()))
            }
        }
        if (settings.timelapseLimitMode == TimeLapseLimitMode.DURATION) {
            IntervalNumber("timelapse-duration", stringResource(R.string.timelapse_duration_ms), settings.timelapseDurationMs, 1_000L..86_400_000L) {
                onChange(settings.copy(timelapseDurationMs = it))
            }
        }
        if (state.selectedMode == CaptureMode.TIME_LAPSE) {
            Text(stringResource(R.string.timelapse_actual_sensor, state.targetFps), fontSize = 16.sp)
            Text(stringResource(R.string.timelapse_actual_frames, state.timelapseFramesCaptured, state.timelapseMissedIntervals), fontSize = 16.sp,
                modifier = Modifier.testTag("timelapse-actual"))
            state.timelapseEncoder?.let { encoder ->
                Text(stringResource(if (state.timelapseHardwareEncoder == true) R.string.timelapse_hardware_codec else R.string.timelapse_software_codec, encoder), fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun IntervalNumber(tag: String, title: String, value: Long, range: LongRange, onApply: (Long) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    val parsed = draft.toLongOrNull()?.takeIf { it in range }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(title, fontSize = 16.sp) },
            isError = parsed == null, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text(stringResource(R.string.timelapse_integer_range, range.first, range.last)) },
            modifier = Modifier.fillMaxWidth().testTag(tag))
        OutlinedButton(onClick = { parsed?.let(onApply) }, enabled = parsed != null && parsed != value,
            modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-apply")) { Text(stringResource(R.string.timelapse_apply), fontSize = 16.sp) }
    }
}
