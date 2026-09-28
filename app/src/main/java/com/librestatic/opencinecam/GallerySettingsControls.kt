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
internal fun GallerySettingsControls(settings: GallerySettings, onSettings: (GallerySettings) -> Unit,
    // The media screen shows the type pills above its list, so its filter panel leaves them out.
    showKinds: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.gallery_settings_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        SettingsHelp(stringResource(R.string.gallery_settings_help), tag = "gallery-settings-help")
        if (showKinds) SettingsPillRow { for (kind in GalleryMediaKind.entries) {
            SettingsPill(stringResource(galleryKindLabel(kind)), "gallery-kind-$kind", settings.kind == kind) {
                onSettings(settings.copy(kind = kind))
            }
        } }
        GalleryToggle("newest", R.string.gallery_newest_first, settings.newestFirst) { onSettings(settings.copy(newestFirst = it)) }
        GalleryToggle("good", R.string.gallery_good_only, settings.goodTakesOnly) { onSettings(settings.copy(goodTakesOnly = it)) }
        GalleryToggle("slate", R.string.gallery_show_slate, settings.showSlate) { onSettings(settings.copy(showSlate = it)) }
        GalleryToggle("technical", R.string.gallery_show_technical, settings.showTechnical) { onSettings(settings.copy(showTechnical = it)) }
        GalleryToggle("thumbnails", R.string.gallery_auto_thumbnails, settings.autoThumbnails) { onSettings(settings.copy(autoThumbnails = it)) }
    }
}

@Composable
private fun GalleryToggle(tag: String, label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    val title = stringResource(label)
    SettingsSwitchRow(title, checked, onChange, tag = "gallery-$tag", labelTag = "gallery-$tag-label")
}

internal fun galleryKindLabel(kind: GalleryMediaKind): Int = when (kind) {
    GalleryMediaKind.ALL -> R.string.gallery_all
    GalleryMediaKind.PHOTO -> R.string.gallery_photo
    GalleryMediaKind.VIDEO -> R.string.gallery_video
    GalleryMediaKind.AUDIO -> R.string.gallery_audio
}
