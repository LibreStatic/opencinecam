/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import com.librestatic.opencinecam.media.video.VideoCodecPreference
import com.librestatic.opencinecam.media.video.hevcSdrSupported
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.librestatic.opencinecam.camera.ZoomLensSwitchMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.AfLockBehavior
import com.librestatic.opencinecam.camera.label
import com.librestatic.opencinecam.camera.AnamorphicSqueeze
import com.librestatic.opencinecam.camera.AnamorphicOutputMode
import kotlin.math.roundToInt

@Composable
internal fun SettingsContent(
    state: CameraUiState,
    settings: CameraSettings,
    audioPermissionGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
    onOpenAbout: () -> Unit,
    onSettingsChange: (CameraSettings) -> Unit,
    visibleIds: Set<String>,
    onApplyPreset: ((CameraPreset) -> Unit)? = null,
    onOpenCapabilities: (() -> Unit)? = null,
    audioPermissionBlocked: Boolean = false,
    onOpenStorageBenchmark: (() -> Unit)? = null,
    onOpenLensCalibration: (() -> Unit)? = null,
    onSetLensLock: ((String?) -> Unit)? = null,
) {
    // One column of equal-width cards, capped and centred for the pane's width class; the gutter
    // is content padding so the whole pane still scrolls. Rows lay out side by side when the
    // card is wide enough for a label and a control column.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val pane = maxWidth.value
    val gutter = settingsSideGutterDp(pane)
    CompositionLocalProvider(LocalSettingsRowLayout provides settingsRowLayout(settingsRowWidthDp(pane))) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(1),
        modifier = Modifier.fillMaxSize().background(Graphite).testTag("settings-list"),
        contentPadding = PaddingValues(horizontal = gutter.dp, vertical = 16.dp),
        verticalItemSpacing = 12.dp,
    ) {
        if ("media-sharing" in visibleIds) settingsCard("media-sharing") {
            MediaSharingSettingsControls(settings.mediaSharing, onSettings = { onSettingsChange(settings.copy(mediaSharing = it)) })
        }
        if ("media-gallery" in visibleIds) settingsCard("media-gallery") {
            GallerySettingsControls(settings.gallery, onSettings = { onSettingsChange(settings.copy(gallery = it)) })
        }
        if ("proxy" in visibleIds) settingsCard("proxy") {
            ProxySettingsControls(settings.proxy) { onSettingsChange(settings.copy(proxy = it)) }
        }
        if ("playback" in visibleIds) settingsCard("playback") {
            PlaybackSettingsControls(settings.playback) { onSettingsChange(settings.copy(playback = it)) }
        }
        if ("capture-naming" in visibleIds) settingsCard("capture-naming") {
            CaptureNamingSettingsControls(settings, onSettingsChange)
        }
        if ("geotagging" in visibleIds) settingsCard("geotagging") {
            GeotaggingSettingsControls(settings, onSettingsChange)
        }
        if ("production-slate" in visibleIds) settingsCard("production-slate") {
            ProductionSlateSettingsControls(state, settings, onSettingsChange)
        }
        if ("project-timing" in visibleIds) settingsCard("project-timing") {
            ProjectTimingSettings(state, settings, onSettingsChange)
        }
        if ("timelapse" in visibleIds) settingsCard("timelapse") {
            TimelapseSettings(state, settings, onSettingsChange)
        }
        if ("operator-controls" in visibleIds) settingsCard("operator-controls") {
            OperatorSettings(state, settings, onSettingsChange)
        }
        if ("webdav-queue" in visibleIds) settingsCard("webdav-queue") { WebDavQueueSettingsSection() }
        if ("presets" in visibleIds) settingsCard("presets") {
            PresetSettings(state, settings, onApplyPreset)
        }
        if ("image-processing" in visibleIds) settingsCard("image-processing") {
            ImageProcessingSettings(state, settings, onSettingsChange)
        }
        if ("professional-exposure" in visibleIds) settingsCard("professional-exposure") {
            ProfessionalExposureSettings(state, settings, onSettingsChange)
        }
        if ("fold-displays" in visibleIds) settingsCard("fold-displays") {
            FoldDisplaySettings(state, settings, onSettingsChange)
        }
        // OCC-PLAN-068 subject features: each card's controls live in that unit's own file.
        val subject = settings.subjectDisplay
        val updateSubject = { next: SubjectDisplaySettings -> onSettingsChange(settings.copy(subjectDisplay = next)) }
        if ("subject-self-monitor" in visibleIds) settingsCard("subject-self-monitor") {
            SettingsSectionTitle(stringResource(R.string.subject_self_monitor_title), help = stringResource(R.string.subject_self_monitor_help)); SubjectSelfMonitorSettings(state, subject, updateSubject)
        }
        if ("subject-tally" in visibleIds) settingsCard("subject-tally") {
            SettingsSectionTitle(stringResource(R.string.subject_tally_title), help = stringResource(R.string.subject_tally_help)); SubjectTallySettings(state, subject, updateSubject)
        }
        if ("subject-fill-light" in visibleIds) settingsCard("subject-fill-light") {
            SettingsSectionTitle(stringResource(R.string.fold_mode_fill_light), help = stringResource(R.string.subject_fill_light_help)); SubjectFillLightSettings(state, subject, updateSubject)
        }
        if ("subject-interview" in visibleIds) settingsCard("subject-interview") {
            SettingsSectionTitle(stringResource(R.string.fold_mode_interview), help = stringResource(R.string.subject_interview_help)); SubjectInterviewSettings(state, subject, updateSubject)
        }
        if ("subject-slate" in visibleIds) settingsCard("subject-slate") {
            SettingsSectionTitle(stringResource(R.string.fold_mode_slate)); SubjectSlateSettings(state, subject, updateSubject)
        }
        if ("subject-out-of-frame" in visibleIds) settingsCard("subject-out-of-frame") {
            SettingsSectionTitle(stringResource(R.string.subject_out_of_frame_title), help = stringResource(R.string.subject_out_of_frame_help)); SubjectOutOfFrameSettings(state, subject, updateSubject)
        }
        if ("appearance" in visibleIds) settingsCard("appearance") { AppearanceSettings() }
        if ("layout" in visibleIds) settingsCard("layout") {
            SettingsOptionRow(stringResource(R.string.mode_selector_style), stringResource(R.string.mode_selector_summary),
                ModeSelectorStyle.entries, settings.modeSelectorStyle,
                label = { stringResource(if (it == ModeSelectorStyle.DIAL) R.string.mode_selector_dial else R.string.mode_selector_buttons) },
                tag = { "mode-selector-$it" }, onSelect = { onSettingsChange(settings.copy(modeSelectorStyle = it)) })
        }
        if ("haptics" in visibleIds) settingsCard("haptics") {
            val haptics = settings.haptics
            val appHaptics = LocalAppHaptics.current
            SettingsOptionRow(stringResource(R.string.haptics_title), stringResource(R.string.haptics_summary),
                HapticLevel.entries, haptics.level,
                label = { stringResource(when (it) { HapticLevel.OFF -> R.string.haptics_level_off; HapticLevel.SUBTLE -> R.string.haptics_level_subtle; HapticLevel.FULL -> R.string.haptics_level_full }) },
                tag = { "haptic-level-$it" },
                onSelect = { onSettingsChange(settings.copy(haptics = haptics.copy(level = it))); appHaptics.preview(it) })
            val on = haptics.level != HapticLevel.OFF
            SettingsToggleRow(stringResource(R.string.haptics_capture), stringResource(R.string.haptics_capture_summary), haptics.capture, on,
                Modifier.testTag("haptic-capture")) { onSettingsChange(settings.copy(haptics = haptics.copy(capture = it))) }
            SettingsToggleRow(stringResource(R.string.haptics_controls), stringResource(R.string.haptics_controls_summary), haptics.controls, on,
                Modifier.testTag("haptic-controls")) { onSettingsChange(settings.copy(haptics = haptics.copy(controls = it))) }
            SettingsToggleRow(stringResource(R.string.haptics_focus_exposure), stringResource(R.string.haptics_focus_exposure_summary), haptics.focusExposure, on,
                Modifier.testTag("haptic-focus-exposure")) { onSettingsChange(settings.copy(haptics = haptics.copy(focusExposure = it))) }
            SettingsToggleRow(stringResource(R.string.haptics_navigation), stringResource(R.string.haptics_navigation_summary), haptics.navigation, on,
                Modifier.testTag("haptic-navigation")) { onSettingsChange(settings.copy(haptics = haptics.copy(navigation = it))) }
            SettingsToggleRow(stringResource(R.string.haptics_mute_during_take), stringResource(R.string.haptics_mute_during_take_summary), haptics.muteDuringTake, on,
                Modifier.testTag("haptic-mute-during-take")) { onSettingsChange(settings.copy(haptics = haptics.copy(muteDuringTake = it))) }
        }
        if ("translucent-chrome" in visibleIds) settingsCard("translucent-chrome") {
            SettingsToggleRow(
                title = stringResource(R.string.translucent_chrome),
                summary = stringResource(R.string.translucent_chrome_summary),
                checked = settings.translucentChrome,
                onCheckedChange = { onSettingsChange(settings.copy(translucentChrome = it)) },
            )
            if (settings.translucentChrome) {
                SettingsOptionRow(stringResource(R.string.viewfinder_scale), stringResource(R.string.viewfinder_scale_summary),
                    ViewfinderScale.entries, settings.viewfinderScale,
                    label = { stringResource(if (it == ViewfinderScale.FIT) R.string.viewfinder_scale_fit else R.string.viewfinder_scale_fill) },
                    tag = { "viewfinder-scale-$it" }, onSelect = { onSettingsChange(settings.copy(viewfinderScale = it)) })
                SettingsSliderRow("${stringResource(R.string.chrome_opacity)} · ${(settings.chromeOpacity * 100).roundToInt()}%",
                    settings.chromeOpacity,
                    { onSettingsChange(settings.copy(chromeOpacity = clampChromeOpacity((it * 20f).roundToInt() / 20f))) },
                    MIN_CHROME_OPACITY..MAX_CHROME_OPACITY, steps = 10, sliderModifier = Modifier.testTag("chrome-opacity"))
            }
        }
        if ("audio" in visibleIds) settingsCard("audio") {
            SettingsToggleRow(
                title = stringResource(R.string.audio_recording),
                summary = if (audioPermissionGranted) {
                    stringResource(R.string.audio_hardware_summary, audioOutputLabel(settings.audioOutputFormat))
                } else stringResource(R.string.audio_permission_summary),
                checked = settings.audioEnabled,
                onCheckedChange = {
                    if (it && !audioPermissionGranted) onRequestAudioPermission()
                    onSettingsChange(settings.copy(audioEnabled = it))
                },
            )
        }
        if (!audioPermissionGranted) {
            if (("audio-permission" in visibleIds || "audio-format" in visibleIds)) settingsCard("audio-permission") {
                if (audioPermissionBlocked) {
                    Text(stringResource(R.string.permission_blocked_explanation), color = Muted, fontSize = 14.sp)
                }
                Button(
                    onClick = onRequestAudioPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Text(stringResource(if (audioPermissionBlocked) R.string.permission_open_settings else R.string.grant_microphone)) }
            }
        } else if ("audio-format" in visibleIds) settingsCard("audio-format") {
            ProfessionalAudioSettings(
                state = state,
                settings = settings,
                onSettingsChange = onSettingsChange,
            )
        }
        if ("burst" in visibleIds) settingsCard("burst") {
            SettingsSectionTitle(stringResource(R.string.burst_mode), help = stringResource(R.string.burst_capture_help), helpTag = "burst-help")
            SettingsSliderRow("${stringResource(R.string.burst_count)} · ${settings.burstCount}", settings.burstCount.toFloat(),
                { onSettingsChange(settings.copy(burstCount = it.roundToInt().coerceIn(3, 10))) }, 3f..10f, steps = 6,
                sliderModifier = Modifier.testTag("burst-count"))
        }
        if ("bitrate" in visibleIds) settingsCard("bitrate") {
            SettingsOptionRow(stringResource(R.string.video_bitrate), null, listOf(12, 20, 40), settings.videoBitrateMbps,
                label = { "$it Mbps" }, tag = { "video-bitrate-$it" }, onSelect = { onSettingsChange(settings.copy(videoBitrateMbps = it)) })
        }
        if ("video-codec" in visibleIds) settingsCard("video-codec") {
            val profile = state.activeVideoProfile
            val hevcAvailable = remember(profile?.size, profile?.fps) {
                profile == null || hevcSdrSupported(profile.size.width, profile.size.height, profile.fps)
            }
            SettingsOptionRow(stringResource(R.string.video_codec),
                stringResource(if (hevcAvailable) R.string.video_codec_summary else R.string.video_codec_hevc_unavailable),
                VideoCodecPreference.entries, settings.videoCodec,
                label = { stringResource(when (it) { VideoCodecPreference.AUTO -> R.string.video_codec_auto; VideoCodecPreference.H264 -> R.string.video_codec_h264; VideoCodecPreference.HEVC -> R.string.video_codec_hevc }) },
                tag = { "video-codec-$it" }, onSelect = { onSettingsChange(settings.copy(videoCodec = it)) },
                choiceEnabled = { it != VideoCodecPreference.HEVC || hevcAvailable || settings.videoCodec == VideoCodecPreference.HEVC })
        }
        if ("geometry" in visibleIds) settingsCard("geometry") {
            SettingsOptionRow(stringResource(R.string.recording_geometry), stringResource(R.string.recording_geometry_summary),
                RecordingGeometryMode.entries, settings.recordingGeometryMode,
                label = { stringResource(if (it == RecordingGeometryMode.COMPATIBLE) R.string.recording_geometry_compatible else R.string.recording_geometry_native) },
                tag = { "recording-geometry-$it" }, onSelect = { onSettingsChange(settings.copy(recordingGeometryMode = it)) })
        }
        if ("anamorphic" in visibleIds) settingsCard("anamorphic") {
            SettingsOptionRow(stringResource(R.string.anamorphic), stringResource(R.string.anamorphic_summary),
                AnamorphicSqueeze.entries, settings.anamorphicSqueeze, label = { anamorphicSqueezeLabel(it) },
                tag = { "anamorphic-squeeze-$it" }, onSelect = { onSettingsChange(settings.copy(anamorphicSqueeze = it)) })
            if (settings.anamorphicSqueeze.isActive) {
                SettingsOptionRow(stringResource(R.string.anamorphic_output), stringResource(R.string.anamorphic_output_summary),
                    AnamorphicOutputMode.entries, settings.anamorphicOutputMode,
                    label = { stringResource(if (it == AnamorphicOutputMode.SQUEEZED) R.string.anamorphic_output_squeezed else R.string.anamorphic_output_desqueezed) },
                    tag = { "anamorphic-output-$it" }, onSelect = { onSettingsChange(settings.copy(anamorphicOutputMode = it)) })
            }
        }
        if ("accumulation" in visibleIds) settingsCard("accumulation") {
            AccumulationSettings(state, settings, onSettingsChange)
        }
        if ("bracket" in visibleIds) settingsCard("bracket") {
            BracketSettings(state, settings, onSettingsChange)
        }
        if ("lut-library" in visibleIds) settingsCard("lut-library") {
            LutLibrarySettings(state)
        }
        if ("monitoring-scopes" in visibleIds) settingsCard("monitoring-scopes") {
            MonitoringSettings(settings, onSettingsChange)
        }
        if ("photo-aspect" in visibleIds) settingsCard("photo-aspect") {
            PhotoAspectSettings(state, settings, onSettingsChange)
        }
        if ("photo-format" in visibleIds) settingsCard("photo-format") {
            PhotoFormatSettings(state, settings, onSettingsChange)
        }
        if ("zero-shutter-lag" in visibleIds) settingsCard("zero-shutter-lag") {
            ZeroShutterLagSettings(state, settings, onSettingsChange)
        }
        if (RECORDING_POLICY_SETTING_ID in visibleIds) settingsCard(RECORDING_POLICY_SETTING_ID) {
            RecordingPolicySettings(settings, onSettingsChange)
        }
        if ("low-light-fps" in visibleIds && state.descriptor?.lowLightAutoFps == true) settingsCard("low-light-fps") {
            LowLightFpsSettings(state, settings, onSettingsChange)
        }
        if ("fps-lock" in visibleIds && state.descriptor?.fpsLockSupported == true) settingsCard("fps-lock") {
            FpsLockSettings(settings, onSettingsChange)
        }
        if ("night-photo" in visibleIds && state.descriptor?.nightExtension != null) settingsCard("night-photo") {
            NightPhotoSettings(state, settings, onSettingsChange)
        }
        if ("photo-flash" in visibleIds) settingsCard("photo-flash") {
            PhotoFlashSettings(state, settings, onSettingsChange)
        }
        if ("torch" in visibleIds) settingsCard("torch") {
            TorchSettings(state, settings, onSettingsChange)
        }
        if ("zebra" in visibleIds) settingsCard("zebra") {
            SettingsToggleRow(title = stringResource(R.string.monitor_zebra), summary = null, checked = settings.zebraEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(zebraEnabled = it)) })
        }
        if ("peaking" in visibleIds) settingsCard("peaking") {
            SettingsToggleRow(title = stringResource(R.string.monitor_peaking), summary = null, checked = settings.peakingEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(peakingEnabled = it)) })
        }
        if ("histogram" in visibleIds) settingsCard("histogram") {
            SettingsToggleRow(
                title = stringResource(R.string.histogram_default),
                summary = null,
                checked = settings.histogramEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(histogramEnabled = it)) },
            )
        }
        if ("grid" in visibleIds) settingsCard("grid") {
            SettingsToggleRow(
                title = stringResource(R.string.composition_grid),
                summary = stringResource(R.string.composition_grid_summary),
                checked = settings.compositionGridEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(compositionGridEnabled = it)) },
            )
        }
        if ("grid-mode" in visibleIds) settingsCard("grid-mode") {
            SettingsOptionRow(stringResource(R.string.composition_grid_mode), null, CompositionGridMode.entries, settings.compositionGridMode,
                label = { stringResource(compositionGridModeTitle(it)) }, tag = { "grid-mode-$it" },
                onSelect = { onSettingsChange(settings.copy(compositionGridMode = it)) })
        }
        if ("horizon" in visibleIds) settingsCard("horizon") {
            SettingsToggleRow(
                title = stringResource(R.string.horizon_level),
                summary = stringResource(R.string.horizon_level_summary),
                checked = settings.horizonLevelEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(horizonLevelEnabled = it)) },
            )
        }
        if ("metering" in visibleIds) settingsCard("metering") {
            SettingsToggleRow(
                title = stringResource(R.string.tap_exposure_metering),
                summary = stringResource(R.string.tap_exposure_metering_summary),
                checked = settings.tapExposureMeteringEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(tapExposureMeteringEnabled = it)) },
            )
        }
        if ("assist" in visibleIds) settingsCard("assist") {
            SettingsToggleRow(
                title = stringResource(R.string.log_view_assist),
                summary = stringResource(R.string.log_view_assist_summary),
                checked = settings.logViewAssistEnabled,
                enabled = state.descriptor?.supportsOpenCineLog == true,
                onCheckedChange = { onSettingsChange(settings.copy(logViewAssistEnabled = it)) },
            )
        }
        if ("log-grey-reference" in visibleIds) settingsCard("log-grey-reference") {
            LogGreyReferenceSettings(state, settings, onSettingsChange)
        }
        if (HDR_FORMAT_SETTING_ID in visibleIds && state.descriptor.offersHdr10Video) settingsCard(HDR_FORMAT_SETTING_ID) {
            HdrFormatSettings(settings, onSettingsChange)
        }
        if ("focus-lock" in visibleIds) settingsCard("focus-lock") {
            SettingsOptionRow(stringResource(R.string.af_lock_behavior), stringResource(R.string.af_lock_behavior_summary),
                AfLockBehavior.entries, settings.afLockBehavior,
                label = { stringResource(if (it == AfLockBehavior.FREEZE_CURRENT) R.string.af_lock_freeze_current else R.string.af_lock_focus_and_lock) },
                tag = { "af-lock-$it" }, onSelect = { onSettingsChange(settings.copy(afLockBehavior = it)) })
        }
        if ("zoom-lens" in visibleIds) settingsCard("zoom-lens") {
            SettingsOptionRow(stringResource(R.string.zoom_lens_switch_mode), stringResource(R.string.zoom_lens_switch_mode_summary),
                ZoomLensSwitchMode.entries, settings.zoomLensSwitchMode,
                label = { stringResource(if (it == ZoomLensSwitchMode.MANUAL_PRESETS) R.string.zoom_lens_switch_mode_manual else R.string.zoom_lens_switch_mode_automatic) },
                tag = { "zoom-lens-switch-$it" }, onSelect = { onSettingsChange(settings.copy(zoomLensSwitchMode = it)) })
        }
        if ("smooth-lens-switch" in visibleIds) settingsCard("smooth-lens-switch") {
            SettingsToggleRow(stringResource(R.string.smooth_lens_switch), stringResource(R.string.smooth_lens_switch_summary),
                settings.smoothLensSwitch, modifier = Modifier.testTag("settings-smooth-lens-switch"),
                onCheckedChange = { onSettingsChange(settings.copy(smoothLensSwitch = it)) })
        }
        if ("lens-calibration" in visibleIds && onOpenLensCalibration != null) settingsCard("lens-calibration") {
            SettingsLinkRow(stringResource(R.string.lens_cal_title), stringResource(R.string.lens_cal_settings_summary), onOpenLensCalibration,
                Modifier.testTag("settings-lens-calibration"))
        }
        if ("lens-lock" in visibleIds && state.lensLock.available && onSetLensLock != null) settingsCard("lens-lock") {
            LensLockSettings(state, onSetLensLock)
        }
        if ("timecode" in visibleIds) settingsCard("timecode") {
            TimecodeSettings(settings, onSettingsChange)
        }
        if ("gyroflow-log" in visibleIds) settingsCard("gyroflow-log") {
            GyroflowSettingsControls(settings, onSettingsChange)
        }
        if ("camera-capabilities" in visibleIds && onOpenCapabilities != null) settingsCard("camera-capabilities") {
            SettingsLinkRow(stringResource(R.string.caps_title), stringResource(R.string.caps_settings_summary), onOpenCapabilities,
                Modifier.testTag("settings-open-capabilities"))
        }
        if ("storage-benchmark" in visibleIds && onOpenStorageBenchmark != null) settingsCard("storage-benchmark") {
            SettingsLinkRow(stringResource(R.string.bench_title), stringResource(R.string.bench_settings_summary), onOpenStorageBenchmark,
                Modifier.testTag("settings-open-storage-benchmark"))
        }
        if ("hardware" in visibleIds) settingsCard("hardware") { HardwareSettingsSummary(state.descriptor) }
        if ("modes" in visibleIds) settingsCard("modes") {
            SettingsSectionTitle(stringResource(R.string.settings_mode_availability))
            CaptureMode.entries.forEach { mode ->
                val gate = state.modeGates.getValue(mode)
                val experimental = showsExperimentalGate(mode, gate, state.descriptor?.hasVerifiedOpenCineLog == true)
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(modeLabel(mode), Modifier.weight(1f).padding(end = 16.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Text(gateLabel(gate, experimental), color = gateColor(gate, experimental), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if ("about" in visibleIds) settingsCard("about") {
            SettingsLinkRow(stringResource(R.string.about_title), stringResource(R.string.about_settings_summary), onOpenAbout)
        }
    }
    }
    }
}

/** A row that opens a full page of its own (About, camera capabilities). */
@Composable
private fun SettingsLinkRow(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // The card is the surface; the whole row opens the page and ends in a quiet chevron.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { SettingsRowLabel(title, description) }
        CineGlyph(CineIcon.CHEVRON_RIGHT, Muted, Modifier.size(20.dp))
    }
}

/** Edits target the next admitted capture; the service owns saved-take increment and publication. */
@Composable
internal fun ProductionSlateSettingsControls(state: CameraUiState, settings: CameraSettings, onSettingsChange: (CameraSettings) -> Unit) {
    val slate = settings.productionSlate
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionTitle(stringResource(R.string.production_slate_title), help = stringResource(R.string.production_slate_help), helpTag = "slate-help")
        if (state.structuralSettingsFrozen && state.effectiveSettings?.productionSlate?.let { it != slate } == true) {
            Text(stringResource(R.string.production_slate_pending), Modifier.testTag("slate-pending"), color = Amber, fontSize = 14.sp)
        }
        ProductionSlateTextField("project", R.string.production_slate_project, slate.project) { onSettingsChange(settings.copy(productionSlate = slate.copy(project = it))) }
        ProductionSlateTextField("camera", R.string.production_slate_camera, slate.camera) { onSettingsChange(settings.copy(productionSlate = slate.copy(camera = it))) }
        ProductionSlateTextField("scene", R.string.production_slate_scene, slate.scene) { onSettingsChange(settings.copy(productionSlate = slate.copy(scene = it))) }
        ProductionSlateTextField("reel", R.string.production_slate_reel, slate.reel) { onSettingsChange(settings.copy(productionSlate = slate.copy(reel = it))) }
        ProductionSlateTextField("lens", R.string.production_slate_lens, slate.lens) { onSettingsChange(settings.copy(productionSlate = slate.copy(lens = it))) }
        var takeText by rememberSaveable(slate.takeNumber) { mutableStateOf(slate.takeNumber.toString()) }
        val take = takeText.toIntOrNull()?.takeIf { it in 1..999999 && it.toString() == takeText }
        OutlinedTextField(takeText, { value ->
            takeText = value
            value.toIntOrNull()?.takeIf { it in 1..999999 && it.toString() == value }?.let { onSettingsChange(settings.copy(productionSlate = slate.copy(takeNumber = it))) }
        }, label = { Text(stringResource(R.string.production_slate_take), Modifier.fillMaxWidth().testTag("slate-take-label")) }, isError = take == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("slate-take"))
        if (take == null) Text(stringResource(R.string.production_slate_invalid_take), Modifier.testTag("slate-take-invalid"), color = Amber, fontSize = 14.sp)
        SettingsChips(stringResource(R.string.production_slate_location), ProductionSlateLocation.entries, slate.location,
            label = { stringResource(when (it) {
                ProductionSlateLocation.UNSPECIFIED -> R.string.production_slate_unspecified
                ProductionSlateLocation.INTERIOR -> R.string.production_slate_interior
                ProductionSlateLocation.EXTERIOR -> R.string.production_slate_exterior
            }) },
            onSelect = { onSettingsChange(settings.copy(productionSlate = slate.copy(location = it))) }, tag = { "slate-location-$it" })
        SettingsChips(stringResource(R.string.production_slate_time), ProductionSlateTimeOfDay.entries, slate.timeOfDay,
            label = { stringResource(when (it) {
                ProductionSlateTimeOfDay.UNSPECIFIED -> R.string.production_slate_unspecified
                ProductionSlateTimeOfDay.DAY -> R.string.production_slate_day
                ProductionSlateTimeOfDay.NIGHT -> R.string.production_slate_night
            }) },
            onSelect = { onSettingsChange(settings.copy(productionSlate = slate.copy(timeOfDay = it))) }, tag = { "slate-time-$it" })
        val goodLabel = stringResource(R.string.production_slate_good)
        SettingsSwitchRow(goodLabel, slate.goodTake, { onSettingsChange(settings.copy(productionSlate = slate.copy(goodTake = it))) },
            tag = "slate-good", labelTag = "slate-good-label")
        val incrementLabel = stringResource(R.string.production_slate_increment)
        SettingsSwitchRow(incrementLabel, slate.autoIncrementTake,
            { onSettingsChange(settings.copy(productionSlate = slate.copy(autoIncrementTake = it))) },
            tag = "slate-increment", labelTag = "slate-increment-label")
    }
}

@Composable
private fun ProductionSlateTextField(tag: String, label: Int, value: String, onChange: (String) -> Unit) {
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val valid = validProductionSlateText(draft)
    OutlinedTextField(draft, { draft = it; if (validProductionSlateText(it)) onChange(it) },
        label = { Text(stringResource(label), Modifier.fillMaxWidth().testTag("slate-$tag-label")) }, isError = !valid,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("slate-$tag"))
    if (!valid) Text(stringResource(R.string.production_slate_invalid_text), Modifier.testTag("slate-$tag-invalid"), color = Amber, fontSize = 14.sp)
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = LocalAppHaptics.current
    // The card is the surface; the row adds no second box, and the gap keeps the switch off the text.
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(8.dp))
            .toggleable(value = checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = { haptics.perform(HapticEvent.Toggle); onCheckedChange(it) }),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else Muted, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            summary?.let { Text(it, color = Muted, fontSize = 14.sp) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

internal fun compositionGridModeTitle(mode: CompositionGridMode): Int = when (mode) {
    CompositionGridMode.THIRDS -> R.string.composition_grid_thirds
    CompositionGridMode.FOUR_BY_FOUR -> R.string.composition_grid_quarters
    CompositionGridMode.DIAGONAL -> R.string.composition_grid_diagonal
    CompositionGridMode.GOLDEN_RATIO -> R.string.composition_grid_golden
}
