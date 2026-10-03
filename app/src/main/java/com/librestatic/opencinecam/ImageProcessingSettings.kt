/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.*

@Composable
internal fun ImageProcessingSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val caps = state.descriptor?.imageProcessingCapabilities ?: ImageProcessingCapabilities()
    val hfr = state.activeVideoProfile?.constrainedHighSpeed == true || state.activeLogProfile?.constrainedHighSpeed == true
    val selected = settings.imageProcessing
    val reported = ImageProcessingDefaults(state.reportedOpticalStabilization, state.reportedVideoStabilization,
        state.reportedNoiseReduction, state.reportedEdgeEnhancement)
    val unavailable = selected.resolve(caps, reported, hfr).unavailable
    Column(Modifier.fillMaxWidth().testTag("image-processing-settings"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSectionTitle(stringResource(R.string.image_processing_title), help = stringResource(R.string.image_processing_help))
        if (state.structuralSettingsFrozen && state.effectiveSettings?.imageProcessing != selected) {
            Text(stringResource(R.string.image_processing_deferred), color = LocalCineColors.current.pending, fontSize = 16.sp)
        }
        if (hfr) Text(stringResource(R.string.image_processing_hfr), color = LocalCineColors.current.pending, fontSize = 14.sp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsChips(stringResource(R.string.image_stabilization),
                listOf<StabilizationMode?>(null, StabilizationMode.OFF, StabilizationMode.OPTICAL, StabilizationMode.VIDEO), selected.stabilization,
                label = { mode -> stringResource(when (mode) { null -> R.string.image_template_default; StabilizationMode.OFF -> R.string.image_mode_off;
                    StabilizationMode.OPTICAL -> R.string.image_mode_optical; StabilizationMode.VIDEO -> R.string.image_mode_video }) },
                onSelect = { onChange(settings.copy(imageProcessing = selected.copy(stabilization = it))) },
                tag = { "stabilization-${it?.name ?: "DEFAULT"}" }, enabled = { it == null || (!hfr && caps.supports(it)) })
            SettingsHelp(stringResource(R.string.image_stabilization_help))
        }
        IspModeChoices(stringResource(R.string.image_noise), "image-noise", selected.noiseReduction,
            { !hfr && caps.supports(it, ImageProcessingControl.NOISE_REDUCTION) },
            { onChange(settings.copy(imageProcessing = selected.copy(noiseReduction = it))) })
        IspModeChoices(stringResource(R.string.image_edge), "image-edge", selected.edge,
            { !hfr && caps.supports(it, ImageProcessingControl.EDGE) },
            { onChange(settings.copy(imageProcessing = selected.copy(edge = it))) })
        if (unavailable.isNotEmpty()) Text(stringResource(R.string.image_processing_unavailable), color = LocalCineColors.current.pending, fontSize = 16.sp)
        if (caps.sessionControls.isNotEmpty()) Text(stringResource(R.string.image_session_keys), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        CameraReportsLine(imageProcessingReadbackDifferences(state.submittedImageProcessing, reported))
        OutlinedButton({ onChange(settings.copy(imageProcessing = ImageProcessingSelection())) }, modifier = Modifier.heightIn(min = 48.dp).testTag("image-reset")) {
            Text(stringResource(R.string.image_reset), fontSize = 16.sp)
        }
    }
}

@Composable
private fun IspModeChoices(title: String, tag: String, value: IspMode, supported: (IspMode) -> Boolean, onChange: (IspMode) -> Unit) {
    SettingsChips(title, listOf(IspMode.DEFAULT, IspMode.OFF, IspMode.FAST, IspMode.HIGH_QUALITY), value,
        label = { stringResource(it.titleResource()) }, onSelect = onChange,
        tag = { "$tag-${it.name}" }, enabled = { it == IspMode.DEFAULT || supported(it) })
}
internal fun IspMode.titleResource(): Int = when (this) {
    IspMode.DEFAULT -> R.string.image_template_default
    IspMode.OFF -> R.string.image_mode_off
    IspMode.FAST -> R.string.image_mode_fast
    IspMode.HIGH_QUALITY -> R.string.image_mode_hq
}
