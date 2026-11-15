/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import kotlin.math.roundToInt

/**
 * Live listening's controls, shared by the places that offer it: the status line, the hint when no headphones are
 * connected, a start button per headphone output, the volume and Stop while listening, and the Bluetooth delay note.
 * Every tag starts with [tagPrefix] (`<prefix>-status`, `<prefix>-start` for a single device or `<prefix>-start-<id>`,
 * `<prefix>-volume`, `<prefix>-stop`, `<prefix>-no-output`, `<prefix>-delay`). The caller adds its own title, and may
 * word the no-headphones hint for its place with [noOutputText].
 */
@Composable
internal fun ListenControls(
    state: ListenUiState,
    tagPrefix: String,
    onStart: (AudioListeningDevice) -> Unit,
    onStop: () -> Unit,
    onVolume: (Int) -> Unit,
    modifier: Modifier = Modifier,
    noOutputText: String? = null,
) {
    val view = listenView(state)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val name = state.deviceName ?: stringResource(R.string.input_eq_listen_headphones)
        listenStatus(state)?.let { status ->
            val active = status == ListenStatus.ACTIVE
            Text(when (status) {
                ListenStatus.CONNECTING -> stringResource(R.string.input_eq_listen_connecting)
                ListenStatus.ACTIVE -> stringResource(R.string.input_eq_listen_active, name)
                ListenStatus.PAUSED -> stringResource(R.string.input_eq_listen_paused)
                ListenStatus.DISCONNECTED -> stringResource(R.string.input_eq_listen_disconnected)
                ListenStatus.FAILED -> stringResource(R.string.input_eq_listen_failed)
                ListenStatus.NOT_FOUND -> stringResource(R.string.input_eq_listen_not_found)
            }, Modifier.testTag("$tagPrefix-status").semantics { liveRegion = LiveRegionMode.Polite },
                color = if (active) MaterialTheme.colorScheme.primary else if (view == ListenView.RETRY) LocalCineColors.current.pending else SettingsMuted,
                fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        }
        when (view) {
            ListenView.NO_OUTPUT -> Text(noOutputText ?: stringResource(R.string.input_eq_listen_no_output), Modifier.testTag("$tagPrefix-no-output"),
                color = SettingsMuted, fontSize = 13.sp)
            ListenView.LIVE -> {
                EqSliderRow(stringResource(R.string.input_eq_listen_volume), stringResource(R.string.input_eq_listen_volume_value, state.volumePercent),
                    state.volumePercent.toFloat(), 0f..100f, { onVolume(it.roundToInt()) }, "$tagPrefix-volume")
                SettingsPillRow {
                    SettingsPill(stringResource(R.string.input_eq_listen_stop), "$tagPrefix-stop", role = Role.Button, onClick = onStop)
                }
            }
            ListenView.IDLE, ListenView.RETRY -> {
                val single = state.devices.size == 1
                val labels = listenDeviceLabels(state.devices)
                state.devices.forEach { device ->
                    OutlinedButton({ onStart(device) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag(if (single) "$tagPrefix-start" else "$tagPrefix-start-${device.id}"), enabled = listenStartEnabled(state)) {
                        Text(stringResource(R.string.input_eq_listen_start, labels[device.id] ?: device.name), Modifier.weight(1f), textAlign = TextAlign.Center)
                    }
                }
            }
        }
        if (view != ListenView.NO_OUTPUT) Text(stringResource(R.string.input_eq_listen_delay), Modifier.testTag("$tagPrefix-delay"),
            color = SettingsMuted, fontSize = 13.sp)
    }
}

/**
 * Moves between the app's sections through [go], asking first when that leaves Settings for the capture screen while
 * listening is armed ([listenExitPrompt]). Keep listening leaves as it is; Stop switches listening off through
 * [onSettingsChange] and leaves; dismissing the dialog stays in Settings. Returns the navigation to use instead of [go].
 */
@Composable
internal fun rememberListenExitGuard(
    section: AppSection,
    state: CameraUiState,
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
    go: (AppSection) -> Unit,
): (AppSection) -> Unit {
    var asking by remember { mutableStateOf<AppSection?>(null) }
    val latestSection by rememberUpdatedState(section)
    val latestStatus by rememberUpdatedState(state.audioListeningStatus)
    val latestSettings by rememberUpdatedState(settings)
    val latestChange by rememberUpdatedState(onSettingsChange)
    val latestGo by rememberUpdatedState(go)
    asking?.let { target ->
        val name = listenUiState(settings, state.audioListeningStatus, state.audioListeningOutputs).deviceName
            ?: stringResource(R.string.input_eq_listen_headphones)
        ListenExitDialog(
            deviceName = name,
            onKeep = { asking = null; latestGo(target) },
            onStop = { asking = null; latestChange(latestSettings.withListenStop()); latestGo(target) },
            onDismiss = { asking = null },
        )
    }
    return remember {
        { target -> if (listenExitPrompt(latestSection, target, latestStatus)) asking = target else latestGo(target) }
    }
}

@Composable
private fun ListenExitDialog(deviceName: String, onKeep: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("listen-exit-dialog"),
        title = { Text(stringResource(R.string.listen_exit_title)) },
        text = { Text(stringResource(R.string.listen_exit_body, deviceName)) },
        confirmButton = {
            TextButton(onKeep, Modifier.testTag("listen-exit-keep")) { Text(stringResource(R.string.listen_exit_keep)) }
        },
        dismissButton = {
            TextButton(onStop, Modifier.testTag("listen-exit-stop")) { Text(stringResource(R.string.listen_exit_stop)) }
        },
    )
}

/**
 * The capture screen's listen panel, opened from the listen key: the headphone outputs, the volume and Stop, with the
 * reminder that what is heard is what is recorded (after the equaliser and the gain), never a separate feed.
 */
@Composable
internal fun CaptureListenPanel(state: CameraUiState, settings: CameraSettings, onSettingsChanged: (CameraSettings) -> Unit, onClose: () -> Unit) {
    val starter = currentListenStarter(settings, state.audioListeningStatus, onSettingsChanged)
    PanelColumn(Modifier.testTag("listen-panel")) {
        PanelHeader(stringResource(R.string.listen_panel_title), onClose)
        PanelNote(stringResource(R.string.listen_panel_hint), Modifier.testTag("listen-panel-hint"))
        ListenControls(listenUiState(settings, state.audioListeningStatus, state.audioListeningOutputs), "listen-panel",
            starter.start, starter.stop, starter.volume, noOutputText = stringResource(R.string.listen_panel_no_output))
        // RAW video runs no preview monitor, so an armed route stays silent (the key amber) until a take feeds it.
        if (state.selectedMode == CaptureMode.RAW_VIDEO && state.phase != CameraUiPhase.RECORDING) {
            PanelNote(stringResource(R.string.listen_panel_raw_video), Modifier.testTag("listen-panel-raw-video"))
        }
    }
}
