/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * OCC-PLAN-068 U7 hook inside [SubjectOverlayLayer]; it draws nothing yet. Face rectangles stay
 * in memory and are never persisted or written to metadata.
 */
@Composable
internal fun SubjectOutOfFrameWarning(state: CameraUiState, settings: SubjectDisplaySettings, modifier: Modifier = Modifier) = Unit

/** Capability gating (hide without face detection on the active graph) is pending in U7. */
@Composable
internal fun SubjectOutOfFrameSettings(state: CameraUiState, subject: SubjectDisplaySettings, onChange: (SubjectDisplaySettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsHelp(stringResource(R.string.subject_out_of_frame_help))
        FoldToggle(stringResource(R.string.subject_out_of_frame), subject.outOfFrameWarning) { onChange(subject.copy(outOfFrameWarning = it)) }
        if (subject.outOfFrameWarning) {
            FoldSlider(stringResource(R.string.subject_out_of_frame_delay, subject.outOfFrameDelaySeconds), subject.outOfFrameDelaySeconds.toFloat(), 1f..10f) {
                onChange(subject.copy(outOfFrameDelaySeconds = it.roundToInt().coerceIn(1, 10)))
            }
        }
    }
}
