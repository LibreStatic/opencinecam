/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** OCC-PLAN-068 U1: what the subject sees over its own preview. Never reaches the encoder. */
@Composable
internal fun SubjectSelfMonitorSettings(state: CameraUiState, subject: SubjectDisplaySettings, onChange: (SubjectDisplaySettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsHelp(stringResource(R.string.subject_self_monitor_help))
        FoldToggle(stringResource(R.string.fold_preview_mirror), subject.previewMirror) { onChange(subject.copy(previewMirror = it)) }
        FoldToggle(stringResource(R.string.subject_preview_bands), subject.previewRecordedAreaBands) { onChange(subject.copy(previewRecordedAreaBands = it)) }
        SettingsChips(stringResource(R.string.subject_preview_guide), SubjectPreviewGuide.entries, subject.previewGuide,
            label = { guide -> stringResource(when (guide) {
                SubjectPreviewGuide.NONE -> R.string.subject_preview_guide_none
                SubjectPreviewGuide.THIRDS -> R.string.subject_preview_guide_thirds
                SubjectPreviewGuide.SAFE_AREA -> R.string.subject_preview_guide_safe
            }) },
            onSelect = { onChange(subject.copy(previewGuide = it)) }, tag = { "subject-guide-${it.name}" })
        FoldToggle(stringResource(R.string.subject_preview_audio_meter), subject.previewAudioMeter) { onChange(subject.copy(previewAudioMeter = it)) }
    }
}

/** OCC-PLAN-068 U2: tally border and full-screen countdown toggles. */
@Composable
internal fun SubjectTallySettings(state: CameraUiState, subject: SubjectDisplaySettings, onChange: (SubjectDisplaySettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsHelp(stringResource(R.string.subject_tally_help))
        FoldToggle(stringResource(R.string.subject_tally_border), subject.tallyBorder) { onChange(subject.copy(tallyBorder = it)) }
        FoldToggle(stringResource(R.string.subject_giant_countdown), subject.giantCountdown) { onChange(subject.copy(giantCountdown = it)) }
    }
}
