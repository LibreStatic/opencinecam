/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun FoldDisplaySettings(camera: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit,
    // The settings hub already titles the page; the capture-screen dialog does not.
    showTitle: Boolean = true) {
    val coordinator = LocalFoldDisplayCoordinator.current
    val fallback = remember { kotlinx.coroutines.flow.MutableStateFlow(FoldDisplayState()) }
    val display by (coordinator?.states ?: fallback).collectAsState()
    val subject = settings.subjectDisplay
    fun update(next: SubjectDisplaySettings) = onChange(settings.copy(subjectDisplay = next))
    Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showTitle) Text(stringResource(R.string.fold_settings_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
        Text(stringResource(R.string.fold_capability, stringResource(R.string.fold_dual), capabilityLabel(display.presentation)), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        Text(stringResource(R.string.fold_capability, stringResource(R.string.fold_transfer), capabilityLabel(display.transfer)), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        Text(stringResource(R.string.fold_posture, postureLabel(display.posture)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        display.failure?.let { Text(stringResource(R.string.fold_failure, it), color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
        if (display.phase != DisplaySessionPhase.IDLE) {
            Text(stringResource(if (display.phase == DisplaySessionPhase.STARTING) R.string.fold_starting else if (display.visible) R.string.fold_visible else R.string.fold_hidden), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Button(onClick = { coordinator?.closeSession() }, modifier = Modifier.heightIn(min = 48.dp).testTag("fold-close")) { Text(stringResource(R.string.fold_close)) }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { coordinator?.start(DisplayOperation.PRESENT) }, enabled = display.presentation == DisplayCapability.AVAILABLE,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fold-present")) { Text(stringResource(R.string.fold_start_subject)) }
            Button(onClick = { coordinator?.start(DisplayOperation.TRANSFER) }, enabled = display.transfer == DisplayCapability.AVAILABLE && camera.phase != CameraUiPhase.RECORDING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fold-transfer")) { Text(stringResource(R.string.fold_start_self)) }
        }
        SettingsHelp(stringResource(R.string.fold_transfer_help))
        Text(stringResource(R.string.self_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
        SettingsHelp(stringResource(R.string.self_timer_help))
        SettingsChips(stringResource(R.string.self_timer_label), listOf(0, 3, 5, 10), subject.selfTimerSeconds,
            label = { stringResource(R.string.self_timer_short, it) },
            onSelect = { update(subject.copy(selfTimerSeconds = it)) }, tag = { "self-timer-$it" })
        FoldToggle(stringResource(R.string.self_minimal), subject.selfMinimalControls) { update(subject.copy(selfMinimalControls = it)) }
        SettingsChips(stringResource(R.string.fold_subject_mode), SubjectDisplayMode.entries, subject.mode,
            label = { mode -> stringResource(when (mode) {
                SubjectDisplayMode.STATUS -> R.string.fold_mode_status
                SubjectDisplayMode.TELEPROMPTER -> R.string.fold_mode_prompter
                SubjectDisplayMode.PREVIEW -> R.string.fold_mode_preview
                SubjectDisplayMode.FILL_LIGHT -> R.string.fold_mode_fill_light
                SubjectDisplayMode.REVIEW -> R.string.fold_mode_review
                SubjectDisplayMode.INTERVIEW -> R.string.fold_mode_interview
                SubjectDisplayMode.SLATE -> R.string.fold_mode_slate
            }) },
            onSelect = { update(subject.copy(mode = it)) }, tag = { "fold-mode-${it.name}" })
        SubjectReviewOperatorBar(help = subject.mode == SubjectDisplayMode.REVIEW)
        FoldSlider(stringResource(R.string.fold_brightness, (subject.brightness * 100).roundToInt()), subject.brightness * 100, 0f..100f) { update(subject.copy(brightness = it / 100)) }
        FoldToggle(stringResource(R.string.fold_lock_touch), subject.touchLocked) { update(subject.copy(touchLocked = it)) }
        if (subject.mode != SubjectDisplayMode.STATUS) {
            FoldToggle(stringResource(R.string.fold_show_status), subject.showStatus) { update(subject.copy(showStatus = it)) }
        }
        if (subject.mode == SubjectDisplayMode.PREVIEW) {
            SettingsHelp(stringResource(R.string.fold_preview_help))
            FoldToggle(stringResource(R.string.fold_preview_mirror), subject.previewMirror) { update(subject.copy(previewMirror = it)) }
            FoldToggle(stringResource(R.string.fold_preview_assist), subject.previewViewAssist) { update(subject.copy(previewViewAssist = it)) }
        }
        OutlinedTextField(subject.operatorCue, { update(subject.copy(operatorCue = it.take(200))) }, label = { Text(stringResource(R.string.fold_cue)) },
            modifier = Modifier.fillMaxWidth(), colors = readableFieldColors())
        if (subject.mode == SubjectDisplayMode.TELEPROMPTER) {
            OutlinedTextField(subject.prompterText, { update(subject.copy(prompterText = it.take(20_000))) },
                label = { Text(stringResource(R.string.fold_script)) }, minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth().testTag("fold-script-editor"), colors = readableFieldColors())
            FoldSlider(stringResource(R.string.fold_font, subject.prompterFontSp), subject.prompterFontSp.toFloat(), 16f..72f) { update(subject.copy(prompterFontSp = it.roundToInt())) }
            FoldSlider(stringResource(R.string.fold_speed, subject.prompterSpeedDpPerSecond), subject.prompterSpeedDpPerSecond.toFloat(), 5f..120f) { update(subject.copy(prompterSpeedDpPerSecond = it.roundToInt())) }
            FoldToggle(stringResource(R.string.fold_pause), subject.prompterPaused) { update(subject.copy(prompterPaused = it)) }
        }
        FoldToggle(stringResource(R.string.fold_continue), subject.continueRecordingOnFold) { update(subject.copy(continueRecordingOnFold = it)) }
        Text(stringResource(when (camera.foldClosureSensorAvailable) {
            true -> R.string.fold_close_policy_help
            false -> R.string.fold_close_sensor_missing
            null -> R.string.fold_close_sensor_unknown
        }), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        FoldToggle(stringResource(R.string.fold_adapt), subject.adaptToHinge) { update(subject.copy(adaptToHinge = it)) }
        FoldToggle(stringResource(R.string.fold_swap), subject.swapPanes) { update(subject.copy(swapPanes = it)) }
    }
}

@Composable
private fun readableFieldColors() = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface)

@Composable
internal fun FoldSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
    Slider(draft, { draft = it }, valueRange = range, onValueChangeFinished = { onChange(draft) },
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label })
}

@Composable
internal fun FoldToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(value, onCheckedChange = null)
    }
}

@Composable
private fun capabilityLabel(capability: DisplayCapability): String = stringResource(when (capability) {
    DisplayCapability.UNKNOWN -> R.string.fold_unknown
    DisplayCapability.UNSUPPORTED -> R.string.fold_unsupported
    DisplayCapability.UNAVAILABLE -> R.string.fold_unavailable
    DisplayCapability.AVAILABLE -> R.string.fold_available
    DisplayCapability.ACTIVE -> R.string.fold_active
})

@Composable
private fun postureLabel(posture: FoldPosture): String = stringResource(when (posture) {
    FoldPosture.NONE_REPORTED -> R.string.fold_no_posture
    FoldPosture.FLAT -> R.string.fold_flat
    FoldPosture.TABLETOP -> R.string.fold_tabletop
    FoldPosture.BOOK -> R.string.fold_book
    FoldPosture.SEPARATING -> R.string.fold_separating
})
