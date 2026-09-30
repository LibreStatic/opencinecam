/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.AccumulationMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccumulationSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val selection = settings.accumulation
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.accumulation_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp)
        SettingsHelp(stringResource(R.string.accumulation_help))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccumulationMode.entries.forEach { mode ->
                FilterChip(selected = selection.mode == mode,
                    onClick = { onChange(settings.copy(accumulation = selection.copy(mode = mode))) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("accumulation-mode-$mode"),
                    label = { Text(mode.name) })
            }
        }
        Text(stringResource(when (selection.mode) {
            AccumulationMode.LIGHT -> R.string.accumulation_light_help
            AccumulationMode.WATER -> R.string.accumulation_water_help
            AccumulationMode.STARS -> R.string.accumulation_stars_help
            AccumulationMode.BULB -> R.string.accumulation_bulb_help
        }), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("accumulation-algorithm"))
        Text(stringResource(R.string.accumulation_duration, selection.durationMs), color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1L, 5L, 10L, 30L, 60L, 300L).forEach { seconds ->
                FilterChip(selected = selection.durationMs == seconds * 1000,
                    enabled = selection.intervalMs <= seconds * 500,
                    onClick = { onChange(settings.copy(accumulation = selection.copy(durationMs = seconds * 1000))) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("accumulation-duration-$seconds"),
                    label = { Text("$seconds s") })
            }
        }
        Text(stringResource(R.string.accumulation_interval, selection.intervalMs), color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(100L, 250L, 500L, 1000L, 5000L, 10000L).forEach { ms ->
                FilterChip(selected = selection.intervalMs == ms, enabled = ms <= selection.durationMs / 2,
                    onClick = { onChange(settings.copy(accumulation = selection.copy(intervalMs = ms))) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("accumulation-interval-$ms"),
                    label = { Text("$ms ms") })
            }
        }
        Text(stringResource(R.string.accumulation_edge, selection.maxEdge), color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(720, 1080, 2048).forEach { edge ->
                FilterChip(selected = selection.maxEdge == edge,
                    onClick = { onChange(settings.copy(accumulation = selection.copy(maxEdge = edge))) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("accumulation-edge-$edge"),
                    label = { Text("$edge px") })
            }
        }
        if (selection.mode == AccumulationMode.STARS) {
            Text(stringResource(R.string.accumulation_threshold, selection.starsThreshold), color = MaterialTheme.colorScheme.onSurface)
            Slider(value = selection.starsThreshold.toFloat(), valueRange = 0f..255f, steps = 254,
                onValueChange = { onChange(settings.copy(accumulation = selection.copy(starsThreshold = it.toInt()))) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("accumulation-threshold"))
        }
        Text(stringResource(R.string.photo_quality, settings.photoQuality), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.accumulation_policy), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.settingsPending) Text(stringResource(R.string.photo_format_pending), color = LocalCineColors.current.pending)
    }
}

@Composable
internal fun AccumulationCaptureProgress(state: CameraUiState, onFinish: () -> Unit, onCancel: () -> Unit) {
    if (state.selectedMode != CaptureMode.LIGHT_TRAIL || !state.stillCapturePending) return
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (state.accumulationSaving) stringResource(R.string.accumulation_capture_saving)
            else stringResource(R.string.accumulation_capture_progress, state.accumulationFrames,
                state.accumulationElapsedMs / 1000, state.accumulationTargetMs / 1000),
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("accumulation-progress"))
        OutlinedButton(onClick = onFinish,
            enabled = state.accumulationFrames >= 2 && !state.accumulationSaving && !state.accumulationFinishing,
            modifier = Modifier.heightIn(min = 48.dp).testTag("accumulation-finish")) {
            Text(stringResource(R.string.accumulation_finish))
        }
        OutlinedButton(onClick = onCancel, enabled = !state.accumulationSaving,
            modifier = Modifier.heightIn(min = 48.dp).testTag("accumulation-cancel")) {
            Text(stringResource(R.string.accumulation_cancel))
        }
    }
}
