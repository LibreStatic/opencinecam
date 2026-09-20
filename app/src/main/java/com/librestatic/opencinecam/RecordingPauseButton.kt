/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A stop button always remains separate; capability comes from the live recording owner. */
@Composable
internal fun RecordingPauseButton(state: CameraUiState, onPause: (Boolean) -> Unit) {
    val status = state.recordingPauseStatus ?: return
    if (state.phase != CameraUiPhase.RECORDING || status.finished) return
    val label = stringResource(if (status.paused) R.string.recording_resume else R.string.recording_pause)
    BoxWithConstraints(Modifier.widthIn(max = 200.dp)) {
        val compact = maxWidth < 160.dp
        OutlinedButton(onClick = { onPause(!status.paused) },
            enabled = !state.recordingFinalizing && !state.recordingPausePending,
            modifier = Modifier.heightIn(min = 48.dp).testTag("recording-pause")
                .semantics { contentDescription = label }) {
            // The compact glyph retains a full spoken label instead of breaking a word in half.
            Text(if (compact) { if (status.paused) "▶" else "Ⅱ" } else label,
                modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** Captures immutable Compose intent so a queued click cannot pause another take or role. */
internal fun requestRecordingPause(state: CameraUiState,
    binder: com.librestatic.opencinecam.service.CaptureService.LocalBinder?, paused: Boolean) {
    val takeId = state.recordingPauseStatus?.takeId ?: return
    binder?.setRecordingPausedForRole(paused, takeId, CaptureActionTicket(state.selfRecordingActive, state.captureActionGeneration))
}
