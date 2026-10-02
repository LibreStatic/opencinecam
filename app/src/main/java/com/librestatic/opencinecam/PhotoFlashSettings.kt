/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.librestatic.opencinecam.camera.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoFlashSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val caps = state.descriptor?.photoFlashCapabilities ?: PhotoFlashCapabilities()
    val selection = settings.photoFlash
    fun choice(mode: PhotoFlashMode): PhotoFlashSelection {
        val automatic = PhotoFlashSelection(mode)
        return if (mode == PhotoFlashMode.ON && caps.singleMax > 1 &&
            automatic.resolve(caps, settings.exposure.mode) is PhotoFlashResolution.Rejected)
            automatic.copy(strength = caps.singleDefault) else automatic
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.photo_flash_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp)
        SettingsHelp(stringResource(R.string.photo_flash_help))
        SettingsChips(stringResource(R.string.settings_mode), PhotoFlashMode.entries, selection.mode,
            label = { mode -> stringResource(when (mode) {
                PhotoFlashMode.OFF -> R.string.settings_off
                PhotoFlashMode.AUTO -> R.string.photo_flash_auto
                PhotoFlashMode.ON -> R.string.photo_flash_on
            }) },
            onSelect = { onChange(settings.copy(photoFlash = choice(it))) },
            tag = { "photo-flash-${it.name}" },
            enabled = { choice(it).resolve(caps, settings.exposure.mode) is PhotoFlashResolution.Plan })
        val resolved = selection.resolve(caps, settings.exposure.mode)
        if (resolved is PhotoFlashResolution.Rejected) Text(
            stringResource(R.string.photo_flash_rejected, stringResource(when (resolved.reason) {
                PhotoFlashRejection.FLASH_UNAVAILABLE -> R.string.photo_flash_reason_unavailable
                PhotoFlashRejection.EXPOSURE_UNSUPPORTED -> R.string.photo_flash_reason_exposure
                PhotoFlashRejection.AE_MODE_UNSUPPORTED -> R.string.photo_flash_reason_ae
                PhotoFlashRejection.STRENGTH_UNSUPPORTED -> R.string.photo_flash_reason_strength
                PhotoFlashRejection.STRENGTH_OUT_OF_RANGE -> R.string.photo_flash_reason_range
                PhotoFlashRejection.STRENGTH_WITH_AUTO -> R.string.photo_flash_reason_auto
            })),
            color = LocalCineColors.current.pending, modifier = Modifier.testTag("photo-flash-rejected"))
        if (selection.mode == PhotoFlashMode.ON && caps.singleMax > 1) {
            var level by remember(selection.strength, caps) { mutableFloatStateOf((selection.strength ?: caps.singleDefault).coerceIn(1, caps.singleMax).toFloat()) }
            val label = stringResource(R.string.photo_flash_strength, level.roundToInt(), caps.singleMax)
            Text(if (selection.strength == null) stringResource(R.string.photo_flash_default_strength) else label, color = MaterialTheme.colorScheme.onSurface)
            val interaction = remember { MutableInteractionSource() }
            CineSlider(value = level, interactionSource = interaction,
                thumb = { Box(Modifier.heightIn(min = 48.dp)) { SliderDefaults.Thumb(interactionSource = interaction) } }, onValueChange = { level = it },
                onValueChangeFinished = { onChange(settings.copy(photoFlash = selection.copy(strength = level.roundToInt()))) },
                enabled = selection.copy(strength = level.roundToInt()).resolve(caps, settings.exposure.mode) is PhotoFlashResolution.Plan,
                valueRange = 1f..caps.singleMax.toFloat(), steps = caps.singleMax - 2,
                modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth().semantics { contentDescription = label }.testTag("photo-flash-strength"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (delta in listOf(-1, 1)) {
                    val next = level.roundToInt() + delta
                    val description = stringResource(if (delta < 0) R.string.photo_flash_decrease else R.string.photo_flash_increase)
                    OutlinedButton(onClick = { onChange(settings.copy(photoFlash = selection.copy(strength = next))) },
                        enabled = next in 1..caps.singleMax && selection.copy(strength = next).resolve(caps, settings.exposure.mode) is PhotoFlashResolution.Plan,
                        modifier = Modifier.sizeIn(minWidth = 64.dp, minHeight = 48.dp).semantics { contentDescription = description }
                            .testTag(if (delta < 0) "photo-flash-decrease" else "photo-flash-increase")) { Text(if (delta < 0) "−" else "+") }
                }
            }
            OutlinedButton(onClick = { onChange(settings.copy(photoFlash = selection.copy(strength = null))) },
                enabled = selection.copy(strength = null).resolve(caps, settings.exposure.mode) is PhotoFlashResolution.Plan,
                modifier = Modifier.heightIn(min = 48.dp).testTag("photo-flash-default-strength")) {
                Text(stringResource(R.string.photo_flash_default_strength))
            }
        } else Text(stringResource(R.string.photo_flash_strength_help), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        val report = state.photoFlashReport
        val unknown = stringResource(R.string.settings_unknown)
        val flash = when (report?.reportedFlashState) {
            0 -> R.string.photo_flash_unavailable
            1 -> R.string.photo_flash_charging
            2 -> R.string.photo_flash_ready
            3 -> R.string.photo_flash_fired
            4 -> R.string.photo_flash_partial
            else -> R.string.settings_unknown
        }
        Text(stringResource(R.string.photo_flash_report, (if (report?.submittedFlashMode == 2) stringResource(R.string.flash_torch) + " / " else "") + stringResource(flash), report?.reportedStrength?.toString() ?: unknown),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("photo-flash-report"))
    }
}
