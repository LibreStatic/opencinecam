/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.PhotoAspectSelection
import com.librestatic.opencinecam.camera.StillPhotoFormat

internal fun photoAspectHeicProcessing(mode: CaptureMode, settings: CameraSettings): Boolean =
    settings.photoAspect.enabled && mode == CaptureMode.PHOTO && settings.photoFormat == StillPhotoFormat.HEIC

@Composable
internal fun PhotoAspectSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val selection = settings.photoAspect
    val toggleLabel = stringResource(R.string.photo_aspect_enabled)
    var width by remember(selection.width) { mutableStateOf(selection.width.toString()) }
    var height by remember(selection.height) { mutableStateOf(selection.height.toString()) }
    val candidate = runCatching {
        PhotoAspectSelection.normalized(selection.enabled, requireNotNull(width.toIntOrNull()), requireNotNull(height.toIntOrNull()))
    }.getOrNull()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.photo_aspect_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp)
        SettingsHelp(stringResource(R.string.photo_aspect_help))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(toggleLabel, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Switch(checked = selection.enabled, onCheckedChange = { onChange(settings.copy(photoAspect = selection.copy(enabled = it))) },
                modifier = Modifier.semantics { contentDescription = toggleLabel }.testTag("photo-aspect-enabled"))
        }
        SettingsChips(stringResource(R.string.photo_aspect_selected, selection.width, selection.height),
            listOf(1 to 1, 4 to 3, 3 to 2, 16 to 9, 239 to 100), selection.width to selection.height,
            label = { (w, h) -> "$w:$h" }, onSelect = { (w, h) -> onChange(settings.copy(photoAspect = PhotoAspectSelection(true, w, h))) },
            tag = { (w, h) -> "photo-aspect-$w-$h" }, rowTag = "photo-aspect-selected")
        OutlinedTextField(value = width, onValueChange = { width = it.take(5) }, singleLine = true,
            label = { Text(stringResource(R.string.photo_aspect_width)) }, isError = candidate == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("photo-aspect-width"))
        OutlinedTextField(value = height, onValueChange = { height = it.take(5) }, singleLine = true,
            label = { Text(stringResource(R.string.photo_aspect_height)) }, isError = candidate == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("photo-aspect-height"))
        if (candidate == null) Text(stringResource(R.string.photo_aspect_invalid), color = LocalCineColors.current.pending,
            modifier = Modifier.testTag("photo-aspect-invalid"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { candidate?.let { onChange(settings.copy(photoAspect = it.copy(enabled = true))) } },
                enabled = candidate != null, modifier = Modifier.heightIn(min = 48.dp).testTag("photo-aspect-apply")) {
                Text(stringResource(R.string.photo_aspect_apply))
            }
            OutlinedButton(onClick = { onChange(settings.copy(photoAspect = selection.copy(width = selection.height, height = selection.width))) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("photo-aspect-swap")) { Text(stringResource(R.string.photo_aspect_swap)) }
        }
        Text(stringResource(R.string.photo_aspect_raw), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (photoAspectHeicProcessing(state.selectedMode, settings)) Text(stringResource(R.string.photo_aspect_heic),
            color = LocalCineColors.current.pending, modifier = Modifier.testTag("photo-aspect-heic"))
        if (state.settingsPending) Text(stringResource(R.string.photo_format_pending), color = LocalCineColors.current.pending)
    }
}
