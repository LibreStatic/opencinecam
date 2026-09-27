/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun MediaSharingSettingsControls(settings: MediaSharingSettings, onSettings: (MediaSharingSettings) -> Unit,
    enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.media_sharing_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.media_share_help), Modifier.fillMaxWidth().testTag("media-share-help"))
        SettingsPillRow { for (content in MediaShareContent.entries) {
            ShareChoice("content-$content", when (content) {
                MediaShareContent.ORIGINALS_AND_METADATA -> R.string.media_share_content_both
                MediaShareContent.ORIGINALS_ONLY -> R.string.media_share_content_originals
                MediaShareContent.METADATA_ONLY -> R.string.media_share_content_metadata
            }, settings.content == content, enabled) { onSettings(settings.copy(content = content)) }
        } }
        SettingsPillRow { for (metadata in MediaShareMetadata.entries) {
            ShareChoice("metadata-$metadata", when (metadata) {
                MediaShareMetadata.PRODUCTION -> R.string.media_share_production
                MediaShareMetadata.TECHNICAL -> R.string.media_share_technical
                MediaShareMetadata.BOTH -> R.string.media_share_both
            }, settings.metadata == metadata, enabled && settings.content != MediaShareContent.ORIGINALS_ONLY) {
                onSettings(settings.copy(metadata = metadata))
            }
        } }
        val lutLabel = stringResource(R.string.media_share_lut)
        Text(lutLabel, Modifier.fillMaxWidth().testTag("media-share-lut-label"))
        Switch(settings.includeReferencedLut, { onSettings(settings.copy(includeReferencedLut = it)) },
            enabled = enabled && settings.content != MediaShareContent.ORIGINALS_ONLY && settings.metadata != MediaShareMetadata.PRODUCTION,
            modifier = Modifier.heightIn(min = 48.dp).testTag("media-share-lut").semantics { contentDescription = lutLabel })
        Text(stringResource(R.string.media_share_lut_help), Modifier.fillMaxWidth().testTag("media-share-lut-help"))
    }
}

@Composable
private fun ShareChoice(tag: String, label: Int, selected: Boolean, enabled: Boolean, action: () -> Unit) {
    SettingsPill(stringResource(label), "media-share-$tag", selected, enabled, onClick = action)
}
