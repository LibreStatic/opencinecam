/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Shared by settings and the viewer; the caller updates the same preference repository. */
@Composable
internal fun PlaybackSettingsControls(settings: PlaybackSettings, showTitle: Boolean = true, onSettings: (PlaybackSettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) Text(stringResource(R.string.playback_settings_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        SettingsHelp(stringResource(R.string.playback_settings_help), tag = "playback-help")
        PlaybackToggle("muted", R.string.playback_settings_muted, settings.muted) { onSettings(settings.copy(muted = it)) }
        PlaybackToggle("loop", R.string.playback_settings_loop, settings.loop) { onSettings(settings.copy(loop = it)) }
        PlaybackToggle("frame-position", R.string.playback_settings_frame_position, settings.showFramePosition) { onSettings(settings.copy(showFramePosition = it)) }
        // Off by default: the verified colour preview is the reference; direct output is for devices where it is slow.
        PlaybackToggle("native-surface", R.string.playback_settings_direct_output, settings.nativeSurfaceFrames) {
            onSettings(settings.copy(nativeSurfaceFrames = it))
        }
        SettingsHelp(stringResource(R.string.playback_settings_direct_output_help), tag = "playback-native-surface-help")
    }
}

@Composable
private fun PlaybackToggle(tag: String, label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    val title = stringResource(label)
    SettingsSwitchRow(title, checked, onChange, tag = "playback-$tag", labelTag = "playback-$tag-label")
}
