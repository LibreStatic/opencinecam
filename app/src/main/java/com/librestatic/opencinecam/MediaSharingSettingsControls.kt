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
        SettingsHelp(stringResource(R.string.media_share_help), tag = "media-share-help")
        SettingsChips(stringResource(R.string.media_share_content_title), MediaShareContent.entries, settings.content,
            label = { stringResource(when (it) {
                MediaShareContent.ORIGINALS_AND_METADATA -> R.string.media_share_content_both
                MediaShareContent.ORIGINALS_ONLY -> R.string.media_share_content_originals
                MediaShareContent.METADATA_ONLY -> R.string.media_share_content_metadata
            }) },
            onSelect = { onSettings(settings.copy(content = it)) }, tag = { "media-share-content-$it" }, rowEnabled = enabled)
        SettingsChips(stringResource(R.string.media_share_metadata_title), MediaShareMetadata.entries, settings.metadata,
            label = { stringResource(when (it) {
                MediaShareMetadata.PRODUCTION -> R.string.media_share_production
                MediaShareMetadata.TECHNICAL -> R.string.media_share_technical
                MediaShareMetadata.BOTH -> R.string.media_share_both
            }) },
            onSelect = { onSettings(settings.copy(metadata = it)) }, tag = { "media-share-metadata-$it" },
            rowEnabled = enabled && settings.content != MediaShareContent.ORIGINALS_ONLY)
        val lutLabel = stringResource(R.string.media_share_lut)
        SettingsSwitchRow(lutLabel, settings.includeReferencedLut, { onSettings(settings.copy(includeReferencedLut = it)) },
            tag = "media-share-lut", labelTag = "media-share-lut-label",
            enabled = enabled && settings.content != MediaShareContent.ORIGINALS_ONLY && settings.metadata != MediaShareMetadata.PRODUCTION)
        SettingsHelp(stringResource(R.string.media_share_lut_help), tag = "media-share-lut-help")
    }
}

