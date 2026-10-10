/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The modes FPS LOCK applies to: regular video and the OCLog LOG and HLG graphs. */
internal fun fpsLockMode(mode: CaptureMode): Boolean = mode == CaptureMode.VIDEO || mode.usesLogGraph

/**
 * The key is offered in the video modes on a camera whose HAL honours Android 16 exposure-time
 * priority (without it the shutter cannot be held while AE still picks the ISO).
 */
internal fun fpsLockKeyVisible(state: CameraUiState): Boolean =
    fpsLockMode(state.selectedMode) && state.descriptor?.fpsLockSupported == true

/** Whether the engine should run the cadence guard on the next graph: switched on and the key applies. */
internal fun fpsLockActive(state: CameraUiState, settings: CameraSettings): Boolean =
    settings.fpsLock && fpsLockKeyVisible(state)

/** The FPS LOCK switch; the caller shows the card only on a camera that supports it. */
@Composable
internal fun FpsLockSettings(settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("fps-lock-settings"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsToggleRow(
            title = stringResource(R.string.fps_lock_title),
            summary = stringResource(R.string.fps_lock_help),
            checked = settings.fpsLock,
            onCheckedChange = { onChange(settings.copy(fpsLock = it)) },
        )
    }
}
