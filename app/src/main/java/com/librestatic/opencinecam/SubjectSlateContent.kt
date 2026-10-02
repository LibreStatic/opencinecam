/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * OCC-PLAN-068 U6 placeholder. The slate reads [ProductionSlateSettings] and never changes take
 * numbering; the running timecode and the sync marker are pending.
 */
@Composable
internal fun SubjectSlateContent(state: CameraUiState, settings: SubjectDisplaySettings, slate: ProductionSlateSettings, modifier: Modifier = Modifier) {
    Box(modifier.testTag("subject-slate")) {
        Text(stringResource(R.string.fold_mode_slate), color = Color.LightGray, fontSize = 16.sp)
    }
}

@Composable
internal fun SubjectSlateSettings(state: CameraUiState, subject: SubjectDisplaySettings, onChange: (SubjectDisplaySettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.subject_slate_fields), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
        for (field in SubjectSlateField.entries) {
            FoldToggle(stringResource(slateFieldLabel(field)), field in subject.slateFields) { shown ->
                onChange(subject.copy(slateFields = if (shown) subject.slateFields + field else subject.slateFields - field))
            }
        }
        SettingsHelp(stringResource(R.string.subject_slate_sync_help))
        FoldToggle(stringResource(R.string.subject_slate_sync_flash), subject.slateSyncFlash) { onChange(subject.copy(slateSyncFlash = it)) }
        FoldToggle(stringResource(R.string.subject_slate_sync_beep), subject.slateSyncBeep) { onChange(subject.copy(slateSyncBeep = it)) }
    }
}

internal fun slateFieldLabel(field: SubjectSlateField): Int = when (field) {
    SubjectSlateField.PROJECT -> R.string.subject_slate_field_project
    SubjectSlateField.SCENE -> R.string.subject_slate_field_scene
    SubjectSlateField.TAKE -> R.string.subject_slate_field_take
    SubjectSlateField.CAMERA -> R.string.subject_slate_field_camera
    SubjectSlateField.REEL -> R.string.subject_slate_field_reel
    SubjectSlateField.TIMECODE -> R.string.subject_slate_field_timecode
}
