/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.CaptureFrameRate

@Composable
internal fun ProjectRateEditor(rate: CaptureFrameRate, tag: String, onChange: (CaptureFrameRate) -> Unit) {
    Text(stringResource(R.string.project_selected_rate, rate.projectLabel()), fontSize = 18.sp, modifier = Modifier.testTag("$tag-selected"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf(24, 25, 30, 50, 60, 120).map { CaptureFrameRate(it) } + fractionalProjectRates).forEach { choice ->
            FilterChip(selected = rate == choice, onClick = { onChange(choice) }, label = { Text(choice.projectLabel(), fontSize = 16.sp) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-fps-${choice.projectLabel()}"))
        }
    }
    var draft by remember(rate) { mutableStateOf("") }
    val integer = draft.toIntOrNull()?.takeIf { it in 1..120 }
    OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(stringResource(R.string.project_integer_rate)) },
        isError = draft.isNotEmpty() && integer == null, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("$tag-project"))
    OutlinedButton(onClick = { integer?.let { onChange(CaptureFrameRate(it)) } }, enabled = integer != null && rate != CaptureFrameRate(integer),
        modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-project-apply")) { Text(stringResource(R.string.timelapse_apply), fontSize = 16.sp) }
}

@Composable
internal fun ProjectTimingSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.project_timing_title), fontSize = 20.sp)
        SettingsHelp(stringResource(R.string.project_timing_help) + "\n\n" + stringResource(R.string.shared_capture_pause_help), tag = "shared-capture-pause-help")
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("video-off-speed")
            .toggleable(settings.videoOffSpeed, role = Role.Switch) { onChange(settings.copy(videoOffSpeed = it)) },
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.project_off_speed), modifier = Modifier.weight(1f), fontSize = 16.sp)
            Switch(checked = settings.videoOffSpeed, onCheckedChange = null)
        }
        if (state.structuralSettingsFrozen) Text(stringResource(R.string.timelapse_settings_pending), fontSize = 16.sp)
        if (settings.videoOffSpeed) ProjectRateEditor(settings.videoProjectRate, "video") {
            onChange(settings.copy(videoProjectNumerator = it.numerator, videoProjectDenominator = it.denominator))
        }
        Text(stringResource(R.string.timelapse_actual_sensor, state.targetFps), fontSize = 16.sp)
        state.recordingProjectRate?.let { Text(stringResource(R.string.project_active_rate, it.projectLabel()), fontSize = 16.sp, modifier = Modifier.testTag("project-active")) }
    }
}
