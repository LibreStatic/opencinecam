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
internal fun PlaybackSettingsControls(settings: PlaybackSettings, onSettings: (PlaybackSettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.playback_settings_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.playback_settings_help), Modifier.fillMaxWidth().testTag("playback-help"))
        PlaybackToggle("muted", R.string.playback_settings_muted, settings.muted) { onSettings(settings.copy(muted = it)) }
        PlaybackToggle("loop", R.string.playback_settings_loop, settings.loop) { onSettings(settings.copy(loop = it)) }
        PlaybackToggle("frame-position", R.string.playback_settings_frame_position, settings.showFramePosition) { onSettings(settings.copy(showFramePosition = it)) }
    }
}

@Composable
private fun PlaybackToggle(tag: String, label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    val title = stringResource(label)
    Text(title, Modifier.fillMaxWidth().testTag("playback-$tag-label"))
    Switch(checked, onChange, modifier = Modifier.heightIn(min = 48.dp).testTag("playback-$tag")
        .semantics { contentDescription = title })
}
