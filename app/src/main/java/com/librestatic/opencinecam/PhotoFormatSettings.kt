/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.StillPhotoFormat
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoFormatSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val descriptor = state.descriptor
    fun supported(format: StillPhotoFormat) = when (format) {
        StillPhotoFormat.JPEG -> descriptor?.jpegSize != null
        StillPhotoFormat.RAW_JPEG -> descriptor?.supportsRaw == true && descriptor.rawSize != null && descriptor.jpegSize != null
        StillPhotoFormat.HEIC -> descriptor?.heicSize != null
        StillPhotoFormat.DNG -> false // The existing RAW capture mode owns DNG-only.
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionTitle(stringResource(R.string.photo_format_title), help = stringResource(R.string.photo_format_help))
        SettingsChips(stringResource(R.string.photo_format_label), listOf(StillPhotoFormat.JPEG, StillPhotoFormat.RAW_JPEG, StillPhotoFormat.HEIC),
            settings.photoFormat, label = { if (it == StillPhotoFormat.RAW_JPEG) "RAW + JPEG" else it.name },
            onSelect = { onChange(settings.copy(photoFormat = it)) }, tag = { "photo-format-${it.name}" }, enabled = ::supported)
        if (!supported(settings.photoFormat)) Text(stringResource(R.string.photo_format_unavailable),
            color = LocalCineColors.current.pending, modifier = Modifier.testTag("photo-format-unavailable"))
        var quality by remember(settings.photoQuality) { mutableFloatStateOf(settings.photoQuality.toFloat()) }
        val label = stringResource(R.string.photo_quality, quality.roundToInt())
        Text(label, color = MaterialTheme.colorScheme.onSurface)
        CineSlider(value = quality, onValueChange = { quality = it },
            onValueChangeFinished = { onChange(settings.copy(photoQuality = quality.roundToInt())) },
            valueRange = 1f..100f, steps = 98,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label }.testTag("photo-quality"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (delta in listOf(-1, 1)) {
                val next = settings.photoQuality + delta
                val description = stringResource(if (delta < 0) R.string.photo_quality_decrease else R.string.photo_quality_increase)
                OutlinedButton(onClick = { onChange(settings.copy(photoQuality = next)) }, enabled = next in 1..100,
                    modifier = Modifier.sizeIn(minWidth = 64.dp, minHeight = 48.dp).semantics { contentDescription = description }
                        .testTag(if (delta < 0) "photo-quality-decrease" else "photo-quality-increase")) { Text(if (delta < 0) "−" else "+") }
            }
        }
        SettingsHelp(stringResource(R.string.photo_quality_help))
        if (state.settingsPending) Text(stringResource(R.string.photo_format_pending), color = LocalCineColors.current.pending)
        state.lastStillPublication?.let { publication ->
            Text(stringResource(R.string.photo_capture_saved, publication.images.joinToString(" + ") { it.kind.name }),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("photo-format-publication"))
        }
    }
}
