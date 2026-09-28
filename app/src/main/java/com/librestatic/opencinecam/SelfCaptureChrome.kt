/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.service.CaptureService

@Composable
internal fun CountdownBadge(seconds: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.self_countdown, seconds)
    Text(seconds.toString(), color = Color.White, fontSize = 64.sp,
        modifier = modifier.background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp)).padding(20.dp)
            .testTag("capture-countdown").clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            })
}

/** Explicit operator role on the transferred screen, never used by subject-only presentation. */
@Composable
internal fun SelfCaptureChrome(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val coordinator = LocalFoldDisplayCoordinator.current
    val locked = state.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) ||
        state.countdownSeconds > 0 || state.recordingFinalizing
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp).testTag("self-capture-chrome")) {
        val optionsHeight = maxHeight * 0.38f
        val scrollWhole = maxHeight < 400.dp
        Column(Modifier.fillMaxSize().then(if (scrollWhole) Modifier.verticalScroll(rememberScrollState()) else Modifier), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { binder?.cancelSelfTimer(); coordinator?.closeSession() }, modifier = Modifier.heightIn(min = 48.dp).testTag("self-return")) {
                    Text(stringResource(R.string.fold_return))
                }
                Button(onClick = { binder?.cancelSelfTimer(); onOpenSettings() }, modifier = Modifier.heightIn(min = 48.dp).testTag("self-settings")) {
                    Text(stringResource(R.string.settings_tab))
                }
            }
            if (scrollWhole) Spacer(Modifier.height(8.dp)) else Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().heightIn(max = optionsHeight).background(Color.Black.copy(alpha = 0.8f))
                .verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.self_title), color = Color.White, fontSize = 18.sp)
                Text(stringResource(R.string.self_timer_value, settings.subjectDisplay.selfTimerSeconds), color = Color.White, fontSize = 16.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.cameras.forEach { camera ->
                        FilterChip(selected = state.selectedCameraId == camera.cameraId, enabled = !locked,
                            onClick = { binder?.selectCamera(camera.cameraId) }, modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text(stringResource(R.string.self_lens, camera.cameraId)) })
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.self_audio), color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(settings.audioEnabled, { onSettingsChanged(settings.copy(audioEnabled = it)) }, enabled = !locked)
                }
            }
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.8f)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CaptureButton(state, binder, settings, 88.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(when {
                        state.recordingFinalizing -> R.string.subject_finalizing
                        state.phase == CameraUiPhase.RECORDING && state.recordingPauseStatus?.paused == true -> R.string.recording_paused
                        state.transferRetirementPending -> R.string.webdav_transfer_cancel_capture
                        state.whiteBalancePreparing -> R.string.pro_wb_cancel_preparation
                        state.countdownSeconds > 0 -> R.string.self_cancel_timer
                        state.phase == CameraUiPhase.RECORDING -> R.string.subject_recording
                        state.phase == CameraUiPhase.CAPTURING -> R.string.subject_capturing
                        state.phase == CameraUiPhase.ERROR -> R.string.camera_error
                        state.phase == CameraUiPhase.SAVED -> R.string.subject_saved
                        state.phase == CameraUiPhase.PREVIEWING -> R.string.subject_ready
                        else -> R.string.subject_preparing
                    }), color = Color.White, fontSize = 18.sp)
                    RecordingPauseButton(state) { requestRecordingPause(state, binder, it) }
                    if (state.phase == CameraUiPhase.RECORDING) Text(stringResource(R.string.self_elapsed, state.recordingElapsedMs / 1_000), color = Color.White, fontSize = 16.sp)
                    state.message?.let { Text(it, color = Color(0xFFFFCF66), fontSize = 14.sp) }
                    AudioMeterHud(state, binder, meterWidth = 96.dp)
                }
            }
        }
    }
}
