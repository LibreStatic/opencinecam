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
        SettingsSectionTitle(stringResource(R.string.accumulation_title), help = stringResource(R.string.accumulation_help))
        SettingsChips(stringResource(R.string.settings_mode), AccumulationMode.entries, selection.mode,
            label = { it.name }, onSelect = { onChange(settings.copy(accumulation = selection.copy(mode = it))) },
            tag = { "accumulation-mode-$it" })
        Text(stringResource(when (selection.mode) {
            AccumulationMode.LIGHT -> R.string.accumulation_light_help
            AccumulationMode.WATER -> R.string.accumulation_water_help
            AccumulationMode.STARS -> R.string.accumulation_stars_help
            AccumulationMode.BULB -> R.string.accumulation_bulb_help
        }), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("accumulation-algorithm"))
        // The long labels keep their caveats (early finish, processing time, no upscaling) as the
        // row title; the chosen value is the row's supporting text.
        SettingsChips(stringResource(R.string.accumulation_duration, selection.durationMs), listOf(1L, 5L, 10L, 30L, 60L, 300L),
            selection.durationMs / 1000, label = { "$it s" },
            onSelect = { onChange(settings.copy(accumulation = selection.copy(durationMs = it * 1000))) },
            tag = { "accumulation-duration-$it" }, enabled = { selection.intervalMs <= it * 500 })
        SettingsChips(stringResource(R.string.accumulation_interval, selection.intervalMs), listOf(100L, 250L, 500L, 1000L, 5000L, 10000L),
            selection.intervalMs, label = { "$it ms" },
            onSelect = { onChange(settings.copy(accumulation = selection.copy(intervalMs = it))) },
            tag = { "accumulation-interval-$it" }, enabled = { it <= selection.durationMs / 2 })
        SettingsChips(stringResource(R.string.accumulation_edge, selection.maxEdge), listOf(720, 1080, 2048), selection.maxEdge,
            label = { "$it px" }, onSelect = { onChange(settings.copy(accumulation = selection.copy(maxEdge = it))) },
            tag = { "accumulation-edge-$it" })
        if (selection.mode == AccumulationMode.STARS) {
            Text(stringResource(R.string.accumulation_threshold, selection.starsThreshold), color = MaterialTheme.colorScheme.onSurface)
            CineSlider(value = selection.starsThreshold.toFloat(), valueRange = 0f..255f, steps = 254,
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
