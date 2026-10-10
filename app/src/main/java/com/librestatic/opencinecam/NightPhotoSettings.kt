/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.camera.nightExtensionEngaged
import com.librestatic.opencinecam.ui.theme.LocalCineColors

/**
 * True when Night photo is selected and the selected camera offers the night extension. The
 * service applies the same rule, so the settings and the session agree on when RAW, HEIC, flash and
 * the manual slots are out.
 */
internal fun nightPhotoSelected(state: CameraUiState, settings: CameraSettings, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    nightExtensionEngaged(settings.nightPhoto, sdkInt, state.descriptor?.nightExtension)

/** The Night photo switch; the caller shows the card only on a camera that advertises the extension. */
@Composable
internal fun NightPhotoSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsToggleRow(
            title = stringResource(R.string.night_photo_title),
            summary = stringResource(R.string.night_photo_help),
            checked = settings.nightPhoto,
            onCheckedChange = { onChange(settings.copy(nightPhoto = it)) },
        )
        // The session may not start (OEM service refused the outputs); the regular photo graph then runs.
        val settled = state.phase == CameraUiPhase.PREVIEWING || state.phase == CameraUiPhase.SAVED
        if (settled && state.selectedMode == CaptureMode.PHOTO && nightPhotoSelected(state, settings) && !state.nightExtensionRunning) {
            Text(stringResource(R.string.night_photo_unavailable), color = LocalCineColors.current.pending,
                modifier = Modifier.testTag("night-photo-unavailable"))
        }
    }
}
