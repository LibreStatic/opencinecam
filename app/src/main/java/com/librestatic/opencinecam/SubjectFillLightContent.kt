/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** OCC-PLAN-068 U3 placeholder: a uniform full-bleed light. Kelvin, tint, timeout and thermal dimming are pending. */
@Composable
internal fun SubjectFillLightContent(state: CameraUiState, settings: SubjectDisplaySettings, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.White).testTag("subject-fill-light")) {
        if (state.countdownSeconds > 0) CountdownBadge(state.countdownSeconds, Modifier.align(Alignment.Center))
    }
}

@Composable
internal fun SubjectFillLightSettings(state: CameraUiState, subject: SubjectDisplaySettings, onChange: (SubjectDisplaySettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsHelp(stringResource(R.string.subject_fill_light_help))
        FoldSlider(stringResource(R.string.subject_fill_light_kelvin, subject.fillLightKelvin), subject.fillLightKelvin.toFloat(), 2700f..6500f) {
            onChange(subject.copy(fillLightKelvin = ((it / 100).roundToInt() * 100).coerceIn(2700, 6500)))
        }
        FoldSlider(stringResource(R.string.subject_fill_light_tint, subject.fillLightTint), subject.fillLightTint.toFloat(), -50f..50f) {
            onChange(subject.copy(fillLightTint = it.roundToInt().coerceIn(-50, 50)))
        }
        SettingsChips(stringResource(R.string.subject_fill_light_timeout), listOf(0, 60, 300, 600, 1800), subject.fillLightTimeoutSeconds,
            label = { if (it == 0) stringResource(R.string.subject_fill_light_timeout_off) else stringResource(R.string.subject_fill_light_timeout_minutes, it / 60) },
            onSelect = { onChange(subject.copy(fillLightTimeoutSeconds = it)) }, tag = { "subject-fill-timeout-$it" })
    }
}
