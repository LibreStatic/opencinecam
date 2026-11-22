/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.ui.CineGlyph
import com.librestatic.opencinecam.ui.CineIcon
import com.librestatic.opencinecam.ui.hapticClick
import com.librestatic.opencinecam.ui.HapticEvent

/** True while the take on air can be paused: the capability comes from the live recording owner, never from the mode. */
internal fun recordingPauseOffered(state: CameraUiState): Boolean =
    state.phase == CameraUiPhase.RECORDING && state.recordingPauseStatus?.finished == false

/**
 * The pause key of a take that can be paused: a round transport key beside the record button, in the look of the other
 * transport keys. Paused, it turns into the amber resume key, so the state reads without the label. Stop stays the
 * record button's.
 */
@Composable
internal fun RecordingPauseButton(state: CameraUiState, size: Dp = 48.dp, onPause: (Boolean) -> Unit) {
    val status = state.recordingPauseStatus ?: return
    if (!recordingPauseOffered(state)) return
    val label = stringResource(if (status.paused) R.string.recording_resume else R.string.recording_pause)
    val enabled = !state.recordingFinalizing && !state.recordingPausePending
    val tint = if (status.paused) Amber else Color.White
    Box(
        Modifier
            .size(size)
            .testTag("recording-pause")
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .background(if (status.paused) Amber.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.28f))
            .border(if (status.paused) 1.5.dp else 1.dp, if (status.paused) Amber else Color(0xFF41494C), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label,
                onClick = hapticClick(HapticEvent.PauseToggle) { onPause(!status.paused) })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        CineGlyph(if (status.paused) CineIcon.PLAY else CineIcon.PAUSE, tint, Modifier.size(size * 0.42f))
    }
}

/** Captures immutable Compose intent so a queued click cannot pause another take or role. */
internal fun requestRecordingPause(state: CameraUiState,
    binder: com.librestatic.opencinecam.service.CaptureService.LocalBinder?, paused: Boolean) {
    val takeId = state.recordingPauseStatus?.takeId ?: return
    binder?.setRecordingPausedForRole(paused, takeId, CaptureActionTicket(state.selfRecordingActive, state.captureActionGeneration))
}
