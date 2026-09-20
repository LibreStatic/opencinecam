/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TorchSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val capabilities = state.descriptor?.torchCapabilities
    val highSpeed = when (state.selectedMode) {
        CaptureMode.LOG -> state.activeLogProfile?.constrainedHighSpeed == true
        in CameraUiState.videoProfileModes -> state.activeVideoProfile?.constrainedHighSpeed == true
        else -> false
    }
    val available = capabilities?.available == true && !highSpeed
    val max = capabilities?.maxLevel ?: 1
    val requested = (settings.torchStrengthLevel ?: capabilities?.defaultLevel ?: 1).coerceIn(1, max)
    var level by remember(requested, max, state.selectedCameraId) { mutableFloatStateOf(requested.toFloat()) }
    val title = stringResource(R.string.flash_torch)
    Column(
        Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(settings.flashEnabled, enabled = available || settings.flashEnabled, role = Role.Switch) {
                    onChange(settings.copy(flashEnabled = it))
                }.testTag("torch-toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(checked = settings.flashEnabled, onCheckedChange = null, enabled = available || settings.flashEnabled)
        }
        if (!available) Text(stringResource(R.string.settings_torch_unavailable), color = Color.LightGray, fontSize = 14.sp)
        if (available && capabilities?.adjustable == true) {
            val levelDescription = stringResource(R.string.settings_torch_level, level.roundToInt(), max)
            val strengthLabel = stringResource(R.string.settings_torch_strength_label)
            val sliderInteraction = remember { MutableInteractionSource() }
            Text(levelDescription, color = Color.White, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().testTag("torch-level-label"))
            Slider(
                value = level,
                onValueChange = { level = it },
                onValueChangeFinished = { onChange(settings.copy(torchStrengthLevel = level.roundToInt())) },
                valueRange = 1f..max.toFloat(),
                steps = max - 2,
                interactionSource = sliderInteraction,
                thumb = {
                    // Material3 resets its internal minimum constraints to the track size.
                    // Size the measured thumb slot too, so the native progress semantics and
                    // pointer target retain 48 dp rather than only reserving outer spacing.
                    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                        SliderDefaults.Thumb(interactionSource = sliderInteraction)
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = strengthLabel
                    stateDescription = levelDescription
                }.testTag("torch-strength"),
            )
            // Separate discrete actions remain reachable by keyboard and switch access. The
            // slider retains Material3's native range/progress actions instead of replacing them.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TorchLevelButton(
                    symbol = "−",
                    description = stringResource(R.string.settings_torch_decrease),
                    tag = "torch-decrease",
                    enabled = level.roundToInt() > 1,
                ) {
                    level = (level.roundToInt() - 1).coerceAtLeast(1).toFloat()
                    onChange(settings.copy(torchStrengthLevel = level.roundToInt()))
                }
                TorchLevelButton(
                    symbol = "+",
                    description = stringResource(R.string.settings_torch_increase),
                    tag = "torch-increase",
                    enabled = level.roundToInt() < max,
                ) {
                    level = (level.roundToInt() + 1).coerceAtMost(max).toFloat()
                    onChange(settings.copy(torchStrengthLevel = level.roundToInt()))
                }
            }
        } else if (available) Text(stringResource(R.string.settings_torch_fixed), color = Color.LightGray, fontSize = 14.sp)
        val unknown = stringResource(R.string.settings_unknown)
        val reported = when (state.torchReported) {
            true -> stringResource(R.string.settings_on)
            false -> stringResource(R.string.settings_off)
            null -> unknown
        }
        Text(
            stringResource(R.string.settings_torch_reported, reported, state.torchStrengthReported?.toString() ?: unknown),
            color = Color.LightGray,
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth().testTag("torch-reported"),
        )
    }
}

@Composable
private fun TorchLevelButton(symbol: String, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.sizeIn(minWidth = 64.dp, minHeight = 48.dp)
            .semantics { contentDescription = description }.testTag(tag),
    ) {
        Text(symbol, fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics { })
    }
}
