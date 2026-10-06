/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import java.util.Locale

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
    val recording = state.phase == CameraUiPhase.RECORDING
    val status = stringResource(when {
        state.recordingFinalizing -> R.string.subject_finalizing
        recording && state.recordingPauseStatus?.paused == true -> R.string.recording_paused
        state.transferRetirementPending -> R.string.webdav_transfer_cancel_capture
        state.whiteBalancePreparing -> R.string.pro_wb_cancel_preparation
        state.countdownSeconds > 0 -> R.string.self_cancel_timer
        recording -> R.string.subject_recording
        state.phase == CameraUiPhase.CAPTURING -> R.string.subject_capturing
        state.phase == CameraUiPhase.ERROR -> R.string.camera_error
        state.phase == CameraUiPhase.SAVED -> R.string.subject_saved
        state.phase == CameraUiPhase.PREVIEWING -> R.string.subject_ready
        else -> R.string.subject_preparing
    })
    // The viewfinder runs full screen behind this chrome: a slim bar on top, the controls at the bottom
    // and nothing in between, so the operator frames themself on the whole cover.
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("self-capture-chrome")) {
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CineIconButton("self-return", CineIcon.RETURN, R.string.fold_return, Modifier.size(48.dp).background(SelfScrim, CircleShape)) {
                binder?.cancelSelfTimer(); coordinator?.closeSession()
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SelfStatusPill(state, status) }
            CineIconButton("self-settings", CineIcon.SETTINGS, R.string.settings_tab, Modifier.size(48.dp).background(SelfScrim, CircleShape)) {
                binder?.cancelSelfTimer(); onOpenSettings()
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(SelfScrim).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.message?.let { Text(it, color = Color(0xFFFFCF66), fontSize = 14.sp, textAlign = TextAlign.Center) }
            if (recording) AudioMeterHud(state, binder, meterWidth = 160.dp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SelfCameraKey(state, binder, enabled = !locked, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CaptureButton(state, binder, settings, 88.dp)
                    RecordingPauseButton(state) { requestRecordingPause(state, binder, it) }
                }
                SelfAudioKey(settings.audioEnabled, enabled = !locked, modifier = Modifier.weight(1f)) {
                    onSettingsChanged(settings.copy(audioEnabled = it))
                }
            }
            val timer = settings.subjectDisplay.selfTimerSeconds
            if (timer > 0 && !recording) Text(stringResource(R.string.self_timer_value, timer), color = Color(0xFFAAB4BA), fontSize = 14.sp)
        }
    }
}

private val SelfScrim = Color.Black.copy(alpha = 0.6f)

/** Ready in white at rest; a red dot, REC and the take time while recording. */
@Composable
private fun SelfStatusPill(state: CameraUiState, status: String) {
    val recording = state.phase == CameraUiPhase.RECORDING && state.recordingPauseStatus?.paused != true && !state.recordingFinalizing
    val record = LocalCineColors.current.record
    Row(Modifier.background(SelfScrim, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (recording) Box(Modifier.size(10.dp).background(record, CircleShape))
        Text(status, color = if (recording) record else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, textAlign = TextAlign.Center)
        if (state.phase == CameraUiPhase.RECORDING) {
            val seconds = state.recordingElapsedMs.coerceAtLeast(0) / 1000
            Text(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60), color = Color.White, fontSize = 16.sp,
                fontFamily = FontFamily.Monospace)
        }
    }
}

/** The lens by facing and focal length; a tap moves to the next camera. */
@Composable
private fun SelfCameraKey(state: CameraUiState, binder: CaptureService.LocalBinder?, enabled: Boolean, modifier: Modifier) {
    val cameras = state.cameras
    val current = cameras.firstOrNull { it.cameraId == state.selectedCameraId }
    val name = current?.let { camera ->
        val facing = facingLabel(camera.lensFacing)
        camera.focalLengthsMm.firstOrNull()?.let { String.format(Locale.ROOT, "%s · %.1f mm", facing, it) } ?: facing
    } ?: "—"
    val switchable = enabled && cameras.size > 1
    // Dimmed only while locked: a single camera is not "disabled", there is just nothing to switch to.
    SelfKey(stringResource(R.string.caps_camera), name, CineIcon.SWITCH_CAMERA, enabled,
        modifier.testTag("self-lens-${state.selectedCameraId}").then(
            if (switchable) Modifier.clickable(role = Role.Button) {
                val index = cameras.indexOfFirst { it.cameraId == state.selectedCameraId }
                binder?.selectCamera(cameras[(index + 1).mod(cameras.size)].cameraId)
            } else Modifier))
}

@Composable
private fun SelfAudioKey(on: Boolean, enabled: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val value = stringResource(if (on) R.string.self_audio_on else R.string.self_audio_off)
    SelfKey(stringResource(R.string.self_audio_short), value, CineIcon.AUDIO, enabled,
        modifier.toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .testTag("self-audio"), highlighted = on)
}

/** A labelled key beside the shutter: an icon, a small caption and the current value. */
@Composable
private fun SelfKey(label: String, value: String, icon: CineIcon, enabled: Boolean, modifier: Modifier, highlighted: Boolean = false) {
    val alpha = if (enabled) 1f else 0.4f
    Column(modifier.heightIn(min = 64.dp).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CineGlyph(icon, (if (highlighted) MaterialTheme.colorScheme.primary else Color.White).copy(alpha = alpha), Modifier.size(24.dp))
        Text(label, color = Color(0xFFAAB4BA).copy(alpha = alpha), fontSize = 12.sp, maxLines = 1)
        Text(value, color = Color.White.copy(alpha = alpha), fontSize = 15.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, textAlign = TextAlign.Center)
    }
}
