/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BracketSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val descriptor = state.descriptor
    val plan = settings.bracket.resolve(descriptor?.aeCompensationRange?.lower, descriptor?.aeCompensationRange?.upper,
        descriptor?.aeCompensationStepNumerator ?: 0, descriptor?.aeCompensationStepDenominator ?: 1,
        settings.exposure.mode == ExposureMode.AUTO && descriptor?.photoFlashCapabilities?.aeModes?.contains(1) == true)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.bracket_settings_title), color = Color.White, fontSize = 20.sp)
        SettingsHelp(stringResource(R.string.bracket_settings_help))
        Text(stringResource(R.string.bracket_count_title), color = Color.White)
        // Preferences remain editable even when this lens rejects the current combination:
        // lowering count and then step must not leave both controls mutually disabled.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (count in listOf(3, 5, 7, 9)) FilterChip(selected = settings.bracket.count == count,
                onClick = { onChange(settings.copy(bracket = settings.bracket.copy(count = count))) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("bracket-count-$count"), label = { Text(count.toString()) })
        }
        Text(stringResource(R.string.bracket_step_title), color = Color.White)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BracketStep.entries.forEach { step ->
                val label = when (step) { BracketStep.THIRD_EV -> "1/3"; BracketStep.HALF_EV -> "1/2"; BracketStep.ONE_EV -> "1"; BracketStep.TWO_EV -> "2" }
                FilterChip(selected = settings.bracket.step == step,
                    onClick = { onChange(settings.copy(bracket = settings.bracket.copy(step = step))) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("bracket-step-${step.name}"), label = { Text("$label EV") })
            }
        }
        when (plan) {
            is BracketResolution.Plan -> Text(stringResource(R.string.bracket_exposures,
                plan.exposures.joinToString(" / ") { "%.2f".format(it.ev) }), color = Color.LightGray,
                modifier = Modifier.testTag("bracket-exposures"))
            is BracketResolution.Rejected -> Text(stringResource(when (plan.reason) {
                BracketRejection.AE_UNAVAILABLE -> R.string.bracket_requires_auto
                BracketRejection.COMPENSATION_UNAVAILABLE -> R.string.bracket_no_compensation
                BracketRejection.INEXACT_STEP -> R.string.bracket_inexact_step
                BracketRejection.OUT_OF_RANGE -> R.string.bracket_out_of_range
            }), color = Color(0xFFFFCF66), modifier = Modifier.testTag("bracket-rejected"))
        }
        Text(stringResource(R.string.bracket_partial_policy), color = Color.LightGray, fontSize = 14.sp)
        Text(stringResource(R.string.photo_quality, settings.photoQuality), color = Color.LightGray)
        if (state.settingsPending) Text(stringResource(R.string.photo_format_pending), color = Color(0xFFFFCF66))
    }
}

@Composable
internal fun BracketCaptureProgress(state: CameraUiState, onCancel: () -> Unit) {
    if (state.selectedMode != CaptureMode.BRACKET || !state.stillCapturePending) return
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (state.bracketSaving) stringResource(R.string.bracket_capture_saving)
            else stringResource(R.string.bracket_capture_progress, state.bracketCaptured, state.bracketExpected),
            color = Color.White, modifier = Modifier.testTag("bracket-progress"))
        OutlinedButton(onClick = onCancel, enabled = !state.bracketSaving,
            modifier = Modifier.heightIn(min = 48.dp).testTag("bracket-cancel")) { Text(stringResource(R.string.bracket_cancel)) }
    }
}
