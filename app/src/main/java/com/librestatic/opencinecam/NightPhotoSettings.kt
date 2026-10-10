/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.camera.NIGHT_EXTENSION_MIN_SDK
import com.librestatic.opencinecam.camera.NightPhotoMode
import com.librestatic.opencinecam.camera.nightExtensionEngaged
import com.librestatic.opencinecam.camera.nightPhotoRequested
import com.librestatic.opencinecam.ui.theme.LocalCineColors

/**
 * True when Night photo is engaged: the operator's choice asks for it (Always, or Auto with the
 * quick toggle on) and the selected camera offers the night extension. The service applies the
 * same rule, so the settings and the session agree on when RAW, HEIC, flash and the manual slots
 * are out.
 */
internal fun nightPhotoSelected(state: CameraUiState, settings: CameraSettings, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    nightExtensionEngaged(nightPhotoRequested(settings.nightPhotoMode, state.nightTransientEngaged), sdkInt, state.descriptor?.nightExtension)

/** The quick toggle can exist on this camera and mode at all: PHOTO, the extension offered, Night not Off. */
internal fun nightToggleAvailable(state: CameraUiState, settings: CameraSettings, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    state.selectedMode == CaptureMode.PHOTO && sdkInt >= NIGHT_EXTENSION_MIN_SDK &&
        state.descriptor?.nightExtension != null && settings.nightPhotoMode != NightPhotoMode.OFF

/**
 * The quick toggle shows when Night is forced on, or in Auto while the scene is dark or Night is
 * switched on (so it can be switched off in a scene that turned bright).
 */
internal fun nightToggleVisible(state: CameraUiState, settings: CameraSettings, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    nightToggleAvailable(state, settings, sdkInt) &&
        (settings.nightPhotoMode == NightPhotoMode.ON || state.nightSuggested || state.nightTransientEngaged)

/**
 * What a tap on the quick toggle does. In Auto it flips the session-only state; in Always it hands
 * the choice back to Auto with Night off, because the toggle is the way out of a forced mode.
 */
internal sealed interface NightToggleAction {
    data class SetEngaged(val engaged: Boolean) : NightToggleAction
    data object ReleaseForced : NightToggleAction
}

internal fun nightToggleAction(state: CameraUiState, settings: CameraSettings): NightToggleAction =
    if (settings.nightPhotoMode == NightPhotoMode.ON) NightToggleAction.ReleaseForced
    else NightToggleAction.SetEngaged(!state.nightTransientEngaged)

/** The Night photo choice (Off, Auto, Always); the caller shows the card only on a camera that advertises the extension. */
@Composable
internal fun NightPhotoSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionTitle(stringResource(R.string.night_photo_title), help = stringResource(R.string.night_photo_help))
        SettingsChips(stringResource(R.string.settings_mode), NightPhotoMode.entries, settings.nightPhotoMode,
            label = { mode -> stringResource(when (mode) {
                NightPhotoMode.OFF -> R.string.night_photo_off
                NightPhotoMode.AUTO -> R.string.night_photo_auto
                NightPhotoMode.ON -> R.string.night_photo_always
            }) },
            onSelect = { onChange(settings.copy(nightPhotoMode = it)) },
            tag = { "night-photo-${it.name}" })
        Text(stringResource(R.string.night_photo_mode_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The session may not start (OEM service refused the outputs); the regular photo graph then runs.
        val settled = state.phase == CameraUiPhase.PREVIEWING || state.phase == CameraUiPhase.SAVED
        if (settled && state.selectedMode == CaptureMode.PHOTO && nightPhotoSelected(state, settings) && !state.nightExtensionRunning) {
            Text(stringResource(R.string.night_photo_unavailable), color = LocalCineColors.current.pending,
                modifier = Modifier.testTag("night-photo-unavailable"))
        }
    }
}
