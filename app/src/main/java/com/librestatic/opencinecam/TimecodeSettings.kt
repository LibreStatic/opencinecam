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
import com.librestatic.opencinecam.camera.*

internal fun parseTimecodeStartInput(hours: String, minutes: String, seconds: String, frames: String, rate: TimecodeRate): SmpteTimecode? {
    val fields = listOf(hours, minutes, seconds, frames)
    if (fields.any { !it.matches(Regex("[0-9]{1,2}")) }) return null
    return runCatching { SmpteTimecode(hours.toInt(), minutes.toInt(), seconds.toInt(), frames.toInt(), rate.dropFrame)
        .also { it.toTotalFrames(rate) } }.getOrNull()
}

@Composable
internal fun TimecodeSettings(settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_timecode), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_timecode_description), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.timecode_enabled))
            Switch(settings.timecodeEnabled, { onChange(settings.copy(timecodeEnabled = it)) }, Modifier.testTag("timecode-enabled"))
        }
        if (settings.timecodeEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimecodeMode.entries.forEach { mode ->
                    FilterChip(settings.timecodeMode == mode, { onChange(settings.copy(timecodeMode = mode)) },
                        label = { Text(when (mode) { TimecodeMode.FREE_RUN -> "FREE"; TimecodeMode.RECORD_RUN -> "REC"; TimecodeMode.REGEN -> "REGEN" }) })
                }
            }
            Text(stringResource(R.string.timecode_rate))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24, 25, 30, 50, 60).forEach { fps ->
                    FilterChip(settings.timecodeNominalFps == fps && !settings.timecodeDropFrame,
                        { onChange(settings.withTimecodeRate(fps, false)) }, label = { Text("$fps NDF") })
                }
                listOf(30, 60).forEach { fps ->
                    FilterChip(settings.timecodeNominalFps == fps && settings.timecodeDropFrame,
                        { onChange(settings.withTimecodeRate(fps, true)) }, label = { Text(if (fps == 30) "29.97 DF" else "59.94 DF") })
                }
            }
            TimecodeStartEditor(settings, onChange)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.timecode_remember), Modifier.weight(1f))
                Switch(settings.timecodeRememberPosition, { onChange(settings.copy(timecodeRememberPosition = it)) }, Modifier.testTag("timecode-remember"))
            }
            Text(stringResource(R.string.timecode_remember_help), style = MaterialTheme.typography.bodySmall)
            var confirmReset by remember { mutableStateOf(false) }
            OutlinedButton({ confirmReset = true }, Modifier.testTag("timecode-reset")) { Text(stringResource(R.string.timecode_reset)) }
            if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
                title = { Text(stringResource(R.string.timecode_reset)) }, text = { Text(stringResource(R.string.timecode_reset_help)) },
                confirmButton = { TextButton({
                    onChange(settings.copy(timecodeResetRevision = if (settings.timecodeResetRevision == Int.MAX_VALUE) 0 else settings.timecodeResetRevision + 1))
                    confirmReset = false
                }, Modifier.testTag("timecode-reset-confirm")) { Text(stringResource(R.string.timecode_reset)) } },
                dismissButton = { TextButton({ confirmReset = false }) { Text(stringResource(android.R.string.cancel)) } })
        }
    }
}

@Composable
private fun TimecodeStartEditor(settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val rate = TimecodeRate(settings.timecodeNominalFps, settings.timecodeDropFrame)
    val initial = listOf(settings.timecodeStartHours, settings.timecodeStartMinutes, settings.timecodeStartSeconds, settings.timecodeStartFrames)
    var draft by remember(initial, rate) { mutableStateOf(initial.map(Int::toString)) }
    var edited by remember(initial, rate) { mutableStateOf(false) }
    val parsed = parseTimecodeStartInput(draft[0], draft[1], draft[2], draft[3], rate)
    Text(stringResource(R.string.timecode_start))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(R.string.timecode_hours, R.string.timecode_minutes, R.string.timecode_seconds, R.string.timecode_frames).forEachIndexed { index, label ->
            OutlinedTextField(draft[index], { value ->
                draft = draft.toMutableList().also { it[index] = value.take(8) }; edited = true
            }, Modifier.width(100.dp).testTag("timecode-start-$index"), label = { Text(stringResource(label)) },
                singleLine = true, isError = edited && parsed == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
    }
    if (edited && parsed == null) Text(stringResource(R.string.timecode_start_invalid), color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("timecode-start-error"))
    Button(onClick = { parsed?.let { onChange(settings.copy(timecodeStartHours = it.hours, timecodeStartMinutes = it.minutes,
        timecodeStartSeconds = it.seconds, timecodeStartFrames = it.frames)) } }, enabled = parsed != null,
        modifier = Modifier.testTag("timecode-start-apply")) { Text(stringResource(R.string.timecode_start_apply)) }
}
