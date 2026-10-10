/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.camera.DynamicFps
import com.librestatic.opencinecam.ui.theme.LocalCineColors

/**
 * Whether the camera may be asked for its low-light auto frame rate: the switch is on, the camera
 * offers it, the mode is regular VIDEO and the active profile is not constrained high-speed. LOG and
 * HLG (the OCLog graph), timelapse and photo modes keep a fixed cadence, and so does off-speed video:
 * its project timeline maps every sensor frame to a constant project cadence, so a 30 fps frame in a
 * 60 fps project would play back at the wrong speed.
 */
internal fun lowLightFpsActive(state: CameraUiState, settings: CameraSettings): Boolean =
    DynamicFps.enabled(
        settings.lowLightAutoFps && !settings.videoOffSpeed && state.descriptor?.lowLightAutoFps == true,
        DynamicFps.allowed(
            videoMode = state.selectedMode == CaptureMode.VIDEO,
            logGraph = state.selectedMode.usesLogGraph,
            constrainedHighSpeed = state.activeVideoProfile?.constrainedHighSpeed == true,
        ),
    )

/** The Low-light auto frame rate switch; the caller shows the card only on a camera that offers it. */
@Composable
internal fun LowLightFpsSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("low-light-fps-settings"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsToggleRow(
            title = stringResource(R.string.low_light_fps_title),
            summary = stringResource(R.string.low_light_fps_help),
            checked = settings.lowLightAutoFps,
            onCheckedChange = { onChange(settings.copy(lowLightAutoFps = it)) },
        )
        if (settings.lowLightAutoFps && state.selectedMode != CaptureMode.VIDEO) {
            Text(stringResource(R.string.low_light_fps_mode_note), color = LocalCineColors.current.pending,
                modifier = Modifier.testTag("low-light-fps-mode-note"))
        }
    }
}
