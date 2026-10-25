/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                listOf<StabilizationMode?>(null, StabilizationMode.OFF, StabilizationMode.OPTICAL, StabilizationMode.VIDEO,
                    StabilizationMode.PREVIEW).filter { it != StabilizationMode.PREVIEW || caps.previewStabilization }, selected.stabilization,
                label = { mode -> stringResource(when (mode) { null -> R.string.image_template_default; StabilizationMode.OFF -> R.string.image_mode_off;
                    StabilizationMode.OPTICAL -> R.string.image_mode_optical; StabilizationMode.VIDEO -> R.string.image_mode_video;
                    StabilizationMode.PREVIEW -> R.string.image_mode_preview }) },
                onSelect = { onChange(settings.copy(imageProcessing = selected.copy(stabilization = it))) },
                tag = { "stabilization-${it?.name ?: "DEFAULT"}" }, enabled = { it == null || (!hfr && caps.supports(it)) })
            SettingsHelp(stringResource(R.string.image_stabilization_help_summary), stringResource(R.string.image_stabilization_help))
        }
        IspModeChoices(stringResource(R.string.image_noise), "image-noise", selected.noiseReduction,
            { !hfr && caps.supports(it, ImageProcessingControl.NOISE_REDUCTION) },
            { onChange(settings.copy(imageProcessing = selected.copy(noiseReduction = it))) })
        IspModeChoices(stringResource(R.string.image_edge), "image-edge", selected.edge,
            { !hfr && caps.supports(it, ImageProcessingControl.EDGE) },
            { onChange(settings.copy(imageProcessing = selected.copy(edge = it))) })
        if (caps.supportsPictureProfiles) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsChips(stringResource(R.string.image_picture_profile), PictureProfile.entries, selected.pictureProfile,
                    label = { stringResource(it.titleResource()) },
                    onSelect = { onChange(settings.copy(imageProcessing = selected.copy(pictureProfile = it))) },
                    tag = { "image-profile-${it.name}" }, enabled = { it == PictureProfile.STANDARD || !hfr })
                SettingsHelp(stringResource(R.string.image_picture_profile_help_summary), stringResource(R.string.image_picture_profile_help))
            }
        }
        PictureControls(state, caps.pictureControls, selected, hfr, selected.resolve(caps, reported, hfr).values.edge) { onChange(settings.copy(imageProcessing = it)) }
        if (unavailable.isNotEmpty()) Text(stringResource(R.string.image_processing_unavailable), color = LocalCineColors.current.pending, fontSize = 16.sp)
        if (caps.sessionControls.isNotEmpty()) Text(stringResource(R.string.image_session_keys), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        CameraReportsLine(imageProcessingReadbackDifferences(state.submittedImageProcessing, reported) +
            pictureControlReadbackDifferences(state.pictureControlReadback))
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
internal fun PictureProfile.titleResource(): Int = when (this) {
    PictureProfile.STANDARD -> R.string.image_profile_standard
    PictureProfile.FLAT -> R.string.image_profile_flat
    PictureProfile.CINE -> R.string.image_profile_cine
}
internal fun IspMode.titleResource(): Int = when (this) {
    IspMode.DEFAULT -> R.string.image_template_default
    IspMode.OFF -> R.string.image_mode_off
    IspMode.FAST -> R.string.image_mode_fast
    IspMode.HIGH_QUALITY -> R.string.image_mode_hq
}

/** Saturation and sharpness, offered only for the vendor controls this camera advertises. */
@Composable
private fun PictureControls(state: CameraUiState, caps: PictureControlCapabilities, selected: ImageProcessingSelection, hfr: Boolean,
    edgeMode: Int?, onChange: (ImageProcessingSelection) -> Unit) {
    if (!caps.any) return
    Column(Modifier.testTag("picture-controls"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.picture_controls_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
        caps.saturation?.let { range ->
            PictureControlSlider(stringResource(R.string.picture_saturation), "picture-saturation", range, selected.saturation,
                pictureControlBlock(false, state.selectedMode, hfr, edgeMode)) { onChange(selected.copy(saturation = it)) }
        }
        caps.sharpness?.let { range ->
            PictureControlSlider(stringResource(R.string.picture_sharpness), "picture-sharpness", range, selected.sharpness,
                pictureControlBlock(true, state.selectedMode, hfr, edgeMode),
                reportedDefault = templateSharpness(state.pictureControlReadback)) { onChange(selected.copy(sharpness = it)) }
        }
        SettingsHelp(stringResource(R.string.picture_help_summary), stringResource(R.string.picture_help))
    }
}

@Composable
private fun PictureControlSlider(title: String, tag: String, range: PictureControlRange, value: Int?, block: PictureControlBlock,
    reportedDefault: Int? = null, onChange: (Int?) -> Unit) {
    val deviceValue = pictureControlDeviceValue(range, reportedDefault)
    val shown = range.snap(value ?: deviceValue)
    var draft by remember(shown, range) { mutableFloatStateOf(shown.toFloat()) }
    val current = range.snap(draft.toInt())
    val label = when {
        value == null && current == deviceValue -> stringResource(
            if (range.defaultAdvertised) R.string.picture_value_default else R.string.picture_value_device, title, current)
        else -> stringResource(R.string.picture_value, title, current)
    }
    SettingsSliderRow(label, draft, { draft = it }, range.min.toFloat()..range.top.toFloat(), steps = range.sliderSteps,
        enabled = block == PictureControlBlock.NONE, sliderModifier = Modifier.testTag(tag),
        onValueChangeFinished = { onChange(pictureControlSelection(range, range.snap(draft.toInt()))) })
    when (block) {
        PictureControlBlock.NONE -> Unit
        PictureControlBlock.NEUTRAL -> SettingsHelp(stringResource(R.string.picture_blocked_neutral_summary), stringResource(R.string.picture_blocked_neutral))
        PictureControlBlock.EDGE_OFF -> SettingsHelp(stringResource(R.string.picture_blocked_edge_off_summary), stringResource(R.string.picture_blocked_edge_off))
    }
}

/** Why a picture control cannot act right now; the stored value is preserved either way. */
internal enum class PictureControlBlock { NONE, NEUTRAL, EDGE_OFF }

/** LOG, HLG, RAW and constrained HFR need neutral processing; sharpness also needs an edge stage. */
internal fun pictureControlBlock(sharpness: Boolean, mode: CaptureMode, hfr: Boolean, edgeMode: Int?): PictureControlBlock = when {
    hfr || mode == CaptureMode.LOG || mode == CaptureMode.HLG || mode == CaptureMode.RAW_PHOTO -> PictureControlBlock.NEUTRAL
    sharpness && !sharpnessEffective(edgeMode) -> PictureControlBlock.EDGE_OFF
    else -> PictureControlBlock.NONE
}

/**
 * The value the slider rests on with no selection: the advertised default, else what the camera
 * reports while nothing is submitted (the Razr's template sharpness is 2, not the range midpoint).
 */
internal fun pictureControlDeviceValue(range: PictureControlRange, reported: Int?): Int =
    if (range.defaultAdvertised) range.default else reported?.takeIf { it in range.min..range.max }?.let(range::snap) ?: range.default

/** Sharpness the template applies: the reported value of a frame whose request carried none. */
internal fun templateSharpness(readback: PictureControlReadback?): Int? =
    readback?.takeIf { it.submittedSharpness == null }?.reportedSharpness

/** The stored value for a slider position: the advertised device default clears the override. */
internal fun pictureControlSelection(range: PictureControlRange, position: Int): Int? =
    range.snap(position).takeUnless { range.defaultAdvertised && it == range.snap(range.default) }
