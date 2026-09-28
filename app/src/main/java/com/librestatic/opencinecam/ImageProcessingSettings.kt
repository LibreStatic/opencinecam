/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

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
    val unknown = stringResource(R.string.pro_unknown)
    Column(Modifier.fillMaxWidth().testTag("image-processing-settings"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.image_processing_title), color = Color.White, fontSize = 20.sp)
        SettingsHelp(stringResource(R.string.image_processing_help))
        if (state.structuralSettingsFrozen && state.effectiveSettings?.imageProcessing != selected) {
            Text(stringResource(R.string.image_processing_deferred), color = Color(0xFFFFCF66), fontSize = 16.sp)
        }
        if (hfr) Text(stringResource(R.string.image_processing_hfr), color = Color(0xFFFFCF66), fontSize = 14.sp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.image_stabilization), color = Color.White, fontSize = 18.sp)
        SettingsHelp(stringResource(R.string.image_stabilization_help))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf<StabilizationMode?>(null, StabilizationMode.OFF, StabilizationMode.OPTICAL, StabilizationMode.VIDEO).forEach { mode ->
                FilterChip(selected.stabilization == mode, { onChange(settings.copy(imageProcessing = selected.copy(stabilization = mode))) },
                    enabled = mode == null || (!hfr && caps.supports(mode)), modifier = Modifier.heightIn(min = 48.dp).testTag("stabilization-${mode?.name ?: "DEFAULT"}"),
                    label = { Text(stringResource(when (mode) { null -> R.string.image_template_default; StabilizationMode.OFF -> R.string.image_mode_off;
                        StabilizationMode.OPTICAL -> R.string.image_mode_optical; StabilizationMode.VIDEO -> R.string.image_mode_video }), fontSize = 16.sp) })
            }
        }
        }
        IspModeChoices(stringResource(R.string.image_noise), "image-noise", selected.noiseReduction,
            { !hfr && caps.supports(it, ImageProcessingControl.NOISE_REDUCTION) },
            { onChange(settings.copy(imageProcessing = selected.copy(noiseReduction = it))) })
        IspModeChoices(stringResource(R.string.image_edge), "image-edge", selected.edge,
            { !hfr && caps.supports(it, ImageProcessingControl.EDGE) },
            { onChange(settings.copy(imageProcessing = selected.copy(edge = it))) })
        if (unavailable.isNotEmpty()) Text(stringResource(R.string.image_processing_unavailable), color = Color(0xFFFFCF66), fontSize = 16.sp)
        if (caps.sessionControls.isNotEmpty()) Text(stringResource(R.string.image_session_keys), color = Color.LightGray, fontSize = 14.sp)
        state.submittedImageProcessing?.let { sent ->
            Text(stringResource(R.string.image_submitted, sent.optical?.toString() ?: unknown, sent.video?.toString() ?: unknown,
                reportedIspLabel(sent.noise), reportedIspLabel(sent.edge)), color = Color.LightGray, fontSize = 14.sp)
        }
        Text(stringResource(R.string.image_reported_stabilization,
            state.reportedOpticalStabilization?.let { if (it == 0) "OFF" else if (it == 1) "ON" else "#$it" } ?: unknown,
            state.reportedVideoStabilization?.let { if (it == 0) "OFF" else if (it == 1) "ON" else if (it == 2) "PREVIEW" else "#$it" } ?: unknown), color = Color.White, fontSize = 16.sp)
        Text(stringResource(R.string.image_reported_isp, reportedIspLabel(state.reportedNoiseReduction), reportedIspLabel(state.reportedEdgeEnhancement)), color = Color.White, fontSize = 16.sp)
        Text(stringResource(R.string.image_crop_region, state.reportedCropRegion?.joinToString(", ") ?: unknown), color = Color.LightGray, fontSize = 14.sp)
        OutlinedButton({ onChange(settings.copy(imageProcessing = ImageProcessingSelection())) }, modifier = Modifier.heightIn(min = 48.dp).testTag("image-reset")) {
            Text(stringResource(R.string.image_reset), fontSize = 16.sp)
        }
    }
}

@Composable
private fun IspModeChoices(title: String, tag: String, value: IspMode, supported: (IspMode) -> Boolean, onChange: (IspMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, color = Color.White, fontSize = 18.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(IspMode.DEFAULT, IspMode.OFF, IspMode.FAST, IspMode.HIGH_QUALITY).forEach { mode ->
            FilterChip(value == mode, { onChange(mode) }, enabled = mode == IspMode.DEFAULT || supported(mode),
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-${mode.name}"), label = { Text(stringResource(mode.titleResource()), fontSize = 16.sp) })
        }
    }
    }
}
internal fun IspMode.titleResource(): Int = when (this) {
    IspMode.DEFAULT -> R.string.image_template_default
    IspMode.OFF -> R.string.image_mode_off
    IspMode.FAST -> R.string.image_mode_fast
    IspMode.HIGH_QUALITY -> R.string.image_mode_hq
}
@Composable
private fun reportedIspLabel(code: Int?): String = when (code) {
    null -> stringResource(R.string.pro_unknown)
    0 -> stringResource(R.string.image_mode_off)
    1 -> stringResource(R.string.image_mode_fast)
    2 -> stringResource(R.string.image_mode_hq)
    else -> "#$code"
}
