/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam

import kotlinx.coroutines.flow.first
import androidx.compose.material3.ExperimentalMaterial3Api
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextAlign
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.camera.label
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun ProfessionalAudioSettings(
    state: CameraUiState,
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
) {
    val capabilities = state.audioCapabilities
    // The settings card is the surface; no second box inside it.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionTitle(stringResource(R.string.professional_audio))
        AudioListeningSettingsControls(state, settings, onSettingsChange, LocalAudioListeningActions.current)
        AudioEffectsSettingsStatus(state, settings)
        AudioMeterSettingsControls(settings, onSettingsChange)
        if (capabilities == null) {
            Text(stringResource(R.string.audio_measuring), color = Muted, fontSize = 14.sp)
            return@Column
        }
        if (capabilities.formats.isEmpty()) {
            Text(stringResource(R.string.audio_no_route), color = LocalCineColors.current.pending, fontSize = 14.sp)
            return@Column
        }
        fun update(candidate: CameraSettings) = onSettingsChange(candidate.normalizedFor(capabilities))
        fun selectPcm(configuration: com.librestatic.opencinecam.media.audio.PcmAudioConfiguration) =
            onSettingsChange(settings.copy(
                audioOutputFormat = settings.audioOutputFormat,
                audioSampleRateHz = configuration.sampleRate,
                audioBitDepth = configuration.bitDepth,
                audioChannels = configuration.channels,
            ))
        AudioChoiceRow(
            title = stringResource(R.string.audio_output_format),
            choices = capabilities.formats.map { it.name to audioOutputLabel(it) },
            selected = settings.audioOutputFormat.name,
        ) { name -> update(settings.copy(audioOutputFormat = AudioOutputFormat.valueOf(name))) }

        val rates = if (settings.audioOutputFormat == AudioOutputFormat.AAC_MP4) {
            capabilities.aacSampleRates
        } else capabilities.pcmConfigurations.map { it.sampleRate }.distinct().sorted()
        AudioChoiceRow(
            title = stringResource(R.string.audio_sample_rate),
            choices = rates.map { it.toString() to formatAudioRate(it) },
            selected = settings.audioSampleRateHz.toString(),
        ) { rate ->
            val selectedRate = rate.toInt()
            if (settings.audioOutputFormat != AudioOutputFormat.AAC_MP4) {
                capabilities.pcmConfigurations.filter { it.sampleRate == selectedRate }
                    .minWithOrNull(compareBy<com.librestatic.opencinecam.media.audio.PcmAudioConfiguration> {
                        kotlin.math.abs(it.bitDepth.bits - settings.audioBitDepth.bits)
                    }.thenBy { kotlin.math.abs(it.channels - settings.audioChannels) })
                    ?.let(::selectPcm)
            } else update(settings.copy(audioSampleRateHz = selectedRate))
        }

        if (settings.audioOutputFormat != AudioOutputFormat.AAC_MP4) {
            AudioChoiceRow(
                title = stringResource(R.string.audio_bit_depth),
                choices = capabilities.bitDepths
                    .filter { settings.audioOutputFormat != AudioOutputFormat.FLAC || it == AudioBitDepth.PCM_16 }
                    .map { it.name to it.label },
                selected = settings.audioBitDepth.name,
            ) { depth ->
                val selectedDepth = AudioBitDepth.valueOf(depth)
                capabilities.pcmConfigurations.filter { it.bitDepth == selectedDepth }
                    .minWithOrNull(compareBy<com.librestatic.opencinecam.media.audio.PcmAudioConfiguration> {
                        kotlin.math.abs(it.sampleRate - settings.audioSampleRateHz)
                    }.thenBy { kotlin.math.abs(it.channels - settings.audioChannels) })
                    ?.let(::selectPcm)
            }
        }

        val channels = if (settings.audioOutputFormat == AudioOutputFormat.AAC_MP4) {
            capabilities.aacChannelCounts
        } else capabilities.channelCounts
        AudioChoiceRow(
            title = stringResource(R.string.audio_channels),
            choices = channels.map { it.toString() to stringResource(if (it == 1) R.string.audio_mono else R.string.audio_stereo) },
            selected = settings.audioChannels.toString(),
        ) { count ->
            val selectedChannels = count.toInt()
            if (settings.audioOutputFormat != AudioOutputFormat.AAC_MP4) {
                capabilities.pcmConfigurations.filter { it.channels == selectedChannels }
                    .minWithOrNull(compareBy<com.librestatic.opencinecam.media.audio.PcmAudioConfiguration> {
                        kotlin.math.abs(it.sampleRate - settings.audioSampleRateHz)
                    }.thenBy { kotlin.math.abs(it.bitDepth.bits - settings.audioBitDepth.bits) })
                    ?.let(::selectPcm)
            } else update(settings.copy(audioChannels = selectedChannels))
        }

        if (settings.audioOutputFormat == AudioOutputFormat.AAC_MP4) {
            AudioChoiceRow(
                title = stringResource(R.string.audio_aac_bitrate),
                choices = capabilities.aacBitratesKbps.map { it.toString() to "$it kbps" },
                selected = settings.audioBitrateKbps.toString(),
            ) { bitrate -> update(settings.copy(audioBitrateKbps = bitrate.toInt())) }
            Text(stringResource(R.string.audio_gain_aac_backend), color = Muted, fontSize = 14.sp)
        } else {
            val derived = settings.audioSampleRateHz * settings.audioBitDepth.bits * settings.audioChannels / 1_000
            Text(stringResource(R.string.audio_pcm_rate, derived), Modifier.testTag("audio-pcm-rate"), color = Muted, fontSize = 14.sp)
        }

        AudioChoiceRow(
            title = stringResource(R.string.audio_public_source),
            choices = capabilities.sources.map { it.name to audioSourceLabel(it) },
            selected = settings.audioSource.name,
        ) { source -> update(settings.copy(audioSource = com.librestatic.opencinecam.media.audio.AudioSourceSelection.valueOf(source))) }

        val inputResolution = settings.resolveAudioInputSelection(capabilities.inputs)
        val autoLabel = inputResolution.autoChoice?.let { choice ->
            stringResource(R.string.audio_input_auto_with, stringResource(audioInputTypeLabel(choice.kind)))
        } ?: stringResource(R.string.audio_automatic)
        val missingKey = settings.audioInputKey?.takeIf { inputResolution.unavailable }
        AudioChoiceRow(
            title = stringResource(R.string.audio_input_device),
            choices = listOf("auto" to autoLabel) +
                capabilities.inputs.map { encodeAudioInputKey(it.key) }
                    .zip(uniqueAudioInputLabels(capabilities.inputs.map { audioInputChoiceLabel(it.kind, it.label, it.address) })) +
                listOfNotNull(missingKey?.let { key ->
                    encodeAudioInputKey(key) to stringResource(R.string.audio_input_disconnected, audioInputChoiceLabel(key.kind, key.productName, key.address))
                }),
            selected = when {
                settings.audioInputKey == null -> "auto"
                else -> encodeAudioInputKey(inputResolution.input?.key ?: requireNotNull(settings.audioInputKey))
            },
        ) { encoded ->
            val key = encoded.takeUnless { it == "auto" }?.let(::decodeAudioInputKey)
            update(settings.copy(audioInputKey = key, legacyAudioInputDeviceId = null,
                audioInputDeviceId = key?.let { chosen -> capabilities.inputs.firstOrNull { it.key == chosen }?.id }))
        }
        Text(stringResource(R.string.audio_input_device_hint), color = Muted, fontSize = 14.sp)
        AudioChoiceRow(
            title = stringResource(R.string.audio_input_loss_policy),
            choices = com.librestatic.opencinecam.media.audio.AudioInputLossPolicy.entries.map { it.name to audioInputLossPolicyLabel(it) },
            selected = settings.audioInputLossPolicy.name,
        ) { policy -> update(settings.copy(audioInputLossPolicy = com.librestatic.opencinecam.media.audio.AudioInputLossPolicy.valueOf(policy))) }
        Text(
            stringResource(if (settings.audioInputLossPolicy == com.librestatic.opencinecam.media.audio.AudioInputLossPolicy.CONTINUE_SILENT)
                R.string.audio_input_loss_silent_hint else R.string.audio_input_loss_hint),
            Modifier.testTag("audio-input-loss-hint"), color = Muted, fontSize = 14.sp,
        )

        SettingsToggleRow(
            title = stringResource(R.string.audio_effect_ns),
            summary = stringResource(R.string.audio_effect_request_help),
            checked = settings.noiseSuppressorEnabled,
            enabled = capabilities.noiseSuppressorAvailable,
            onCheckedChange = { update(settings.copy(noiseSuppressorEnabled = it)) },
        )
        SettingsToggleRow(
            title = stringResource(R.string.audio_effect_aec),
            summary = stringResource(R.string.audio_effect_request_help),
            checked = settings.acousticEchoCancelerEnabled,
            enabled = capabilities.acousticEchoCancelerAvailable,
            onCheckedChange = { update(settings.copy(acousticEchoCancelerEnabled = it)) },
        )
        AudioRecordingGainSettings(state, settings, onSettingsChange)
        Text(stringResource(R.string.audio_format_container_hint), color = Muted, fontSize = 14.sp)
    }
}

/** All controls below are live presentation preferences, not microphone or encoder settings. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AudioMeterSettingsControls(settings: CameraSettings, onSettingsChange: (CameraSettings) -> Unit) {
    val options = settings.audioMeter
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle(stringResource(R.string.audio_meter_title), help = stringResource(R.string.audio_meter_help), helpTag = "audio-meter-settings-help")
        SettingsSwitchRow(stringResource(R.string.audio_meter_visible), options.visible,
            { onSettingsChange(settings.copy(audioMeter = options.copy(visible = it))) }, tag = "audio-meter-settings-visible")
        SettingsChips(stringResource(R.string.settings_mode), AudioMeterMode.entries, options.mode,
            label = { stringResource(audioMeterModeLabel(it)) },
            onSelect = { onSettingsChange(settings.copy(audioMeter = options.copy(mode = it))) }, tag = { "audio-meter-settings-mode-$it" })
        val referenceLabel = stringResource(R.string.audio_meter_reference, options.vuReferenceDbfs)
        Text(referenceLabel, Modifier.testTag("audio-meter-settings-reference-label"), color = MaterialTheme.colorScheme.onSurface)
        val referenceInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        CineSlider(options.vuReferenceDbfs.toFloat(), { onSettingsChange(settings.copy(audioMeter = options.copy(vuReferenceDbfs = it.roundToInt()))) },
            valueRange = -24f..-6f, steps = 17, interactionSource = referenceInteraction,
            thumb = { androidx.compose.material3.SliderDefaults.Thumb(referenceInteraction, thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 52.dp)) },
            track = { androidx.compose.material3.SliderDefaults.Track(it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("audio-meter-settings-reference").semantics { contentDescription = referenceLabel })
        val holdLabel = stringResource(R.string.audio_meter_hold, options.peakHoldMs)
        Text(holdLabel, Modifier.testTag("audio-meter-settings-hold-label"), color = MaterialTheme.colorScheme.onSurface)
        val holdInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        CineSlider(options.peakHoldMs.toFloat(), { onSettingsChange(settings.copy(audioMeter = options.copy(peakHoldMs = it.roundToInt()))) },
            valueRange = 0f..3000f, interactionSource = holdInteraction,
            thumb = { androidx.compose.material3.SliderDefaults.Thumb(holdInteraction, thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 52.dp)) },
            track = { androidx.compose.material3.SliderDefaults.Track(it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("audio-meter-settings-hold").semantics { contentDescription = holdLabel })
        val valuesLabel = stringResource(R.string.audio_meter_numbers)
        SettingsSwitchRow(valuesLabel, options.showValues,
            { onSettingsChange(settings.copy(audioMeter = options.copy(showValues = it))) }, tag = "audio-meter-settings-values")
    }
}

/** Observations belong to the current PCM producer, never to a capability or preference. */
@Composable
internal fun AudioEffectsSettingsStatus(state: CameraUiState, settings: CameraSettings) {
    val state = liveCameraState(state)
    val receipt = state.audioLevels?.effects.takeIf { state.audioMonitoringActive }
    val effective = state.effectiveSettings
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle(stringResource(R.string.audio_effect_title), help = stringResource(R.string.audio_effect_help), helpTag = "audio-effects-help")
        if (settings.audioRecordingGain.enabled) {
            Text(stringResource(R.string.audio_effect_manual), Modifier.testTag("audio-effects-manual"), color = Amber, fontSize = 14.sp)
        }
        AudioEffectStatusRow("ns", R.string.audio_effect_ns, settings.noiseSuppressorEnabled,
            receipt?.noiseSuppressor, state.structuralSettingsFrozen && effective != null &&
                effective.noiseSuppressorEnabled != settings.noiseSuppressorEnabled)
        AudioEffectStatusRow("agc", R.string.audio_effect_agc, settings.automaticGainControlEnabled,
            receipt?.automaticGainControl, state.structuralSettingsFrozen && effective != null &&
                (effective.automaticGainControlEnabled != settings.automaticGainControlEnabled ||
                    effective.audioRecordingGain.enabled != settings.audioRecordingGain.enabled))
        AudioEffectStatusRow("aec", R.string.audio_effect_aec, settings.acousticEchoCancelerEnabled,
            receipt?.acousticEchoCanceler, state.structuralSettingsFrozen && effective != null &&
                effective.acousticEchoCancelerEnabled != settings.acousticEchoCancelerEnabled)
    }
}

@Composable
private fun AudioEffectStatusRow(tag: String, title: Int, requested: Boolean,
    observation: com.librestatic.opencinecam.camera.AudioEffectObservation?, pending: Boolean) {
    fun field(name: String) = Modifier.testTag("audio-effects-$tag-$name")
    val yes = stringResource(R.string.audio_effect_yes)
    val no = stringResource(R.string.audio_effect_no)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), field("title"), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.audio_effect_requested, if (requested) yes else no), field("requested"), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        if (observation == null) {
            Text(stringResource(R.string.audio_effect_unobserved), field("state"), color = Muted, fontSize = 14.sp)
        } else {
            Text(stringResource(R.string.audio_effect_receipt_requested, if (observation.requested) yes else no), field("receipt"), color = Muted, fontSize = 14.sp)
            val stateLabel = stringResource(when (observation.state) {
                com.librestatic.opencinecam.camera.AudioEffectState.UNKNOWN -> R.string.audio_effect_unknown
                com.librestatic.opencinecam.camera.AudioEffectState.UNAVAILABLE -> R.string.audio_effect_unavailable
                com.librestatic.opencinecam.camera.AudioEffectState.ENABLED -> R.string.audio_effect_enabled
                com.librestatic.opencinecam.camera.AudioEffectState.DISABLED -> R.string.audio_effect_disabled
                com.librestatic.opencinecam.camera.AudioEffectState.FAILED -> R.string.audio_effect_failed
            })
            Text(stringResource(R.string.audio_effect_observed, stateLabel), field("state"), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            val implementation = stringResource(when (observation.implementation) {
                com.librestatic.opencinecam.camera.AudioEffectImplementation.NONE -> R.string.audio_effect_none
                com.librestatic.opencinecam.camera.AudioEffectImplementation.PLATFORM -> R.string.audio_effect_platform
                com.librestatic.opencinecam.camera.AudioEffectImplementation.SOFTWARE -> R.string.audio_effect_software
            })
            Text(stringResource(R.string.audio_effect_implementation, implementation), field("implementation"), color = Muted, fontSize = 14.sp)
            val control = when (observation.hasControl) { true -> yes; false -> no; null -> stringResource(R.string.audio_effect_control_unknown) }
            Text(stringResource(R.string.audio_effect_control, control), field("control"), color = Muted, fontSize = 14.sp)
            if (observation.configurationFailed) Text(stringResource(R.string.audio_effect_configuration_failed), field("configuration-failed"), color = Amber, fontSize = 14.sp)
        }
        if (pending) Text(stringResource(R.string.audio_effect_pending), field("pending"), color = Amber, fontSize = 14.sp)
    }
}

/** Playback request is separate from both microphone ownership and recorded-sample gain. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AudioListeningSettingsControls(
    state: CameraUiState,
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
    onReconnect: () -> Unit,
) {
    val request = settings.audioListening
    val status = state.audioListeningStatus
    val enabledLabel = stringResource(R.string.audio_listening_enable)
    val volumeLabel = stringResource(R.string.audio_listening_volume, request.volumePercent)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle(stringResource(R.string.audio_listening_title), help = stringResource(R.string.audio_listening_help), helpTag = "audio-listening-help")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(enabledLabel, Modifier.weight(1f).padding(end = 12.dp), color = MaterialTheme.colorScheme.onSurface)
            Switch(checked = request.enabled,
                onCheckedChange = { onSettingsChange(settings.copy(audioListening = request.copy(enabled = it))) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("audio-listening-enable").semantics { contentDescription = enabledLabel })
        }
        Text(volumeLabel, Modifier.testTag("audio-listening-volume-label"), color = MaterialTheme.colorScheme.onSurface)
        CineSlider(value = request.volumePercent.toFloat(), onValueChange = {
            onSettingsChange(settings.copy(audioListening = request.copy(volumePercent = it.roundToInt())))
        }, valueRange = 0f..100f, steps = 99, interactionSource = interaction,
            thumb = { androidx.compose.material3.SliderDefaults.Thumb(interactionSource = interaction,
                thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 52.dp)) },
            track = { androidx.compose.material3.SliderDefaults.Track(sliderState = it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("audio-listening-volume").semantics { contentDescription = volumeLabel })
        SettingsChips(stringResource(R.string.audio_listening_output_title), AudioListeningOutput.entries, request.output,
            label = { stringResource(when (it) {
                AudioListeningOutput.WIRED_USB -> R.string.audio_listening_wired
                AudioListeningOutput.BLUETOOTH -> R.string.audio_listening_bluetooth
                AudioListeningOutput.SPEAKER -> R.string.audio_listening_speaker
            }) },
            onSelect = { onSettingsChange(settings.copy(audioListening = request.copy(output = it), audioListeningOutputDeviceId = null)) },
            tag = { "audio-listening-output-${it.name}" })
        Text(stringResource(if (request.output == AudioListeningOutput.SPEAKER) R.string.audio_listening_speaker_warning else R.string.audio_listening_latency),
            Modifier.testTag("audio-listening-route-help"), color = Muted, fontSize = 14.sp)
        val devices = state.audioListeningOutputs.filter { it.output == request.output }
        val autoDevice = stringResource(R.string.audio_listening_auto)
        SettingsChips(stringResource(R.string.audio_listening_device_title), listOf<Int?>(null) + devices.map { it.id },
            settings.audioListeningOutputDeviceId,
            label = { id -> if (id == null) autoDevice else devices.firstOrNull { it.id == id }?.name ?: id.toString() },
            onSelect = { onSettingsChange(settings.copy(audioListeningOutputDeviceId = it)) },
            tag = { "audio-listening-device-${it ?: "auto"}" })
        Text(stringResource(R.string.audio_listening_requested_device,
            settings.audioListeningOutputDeviceId?.toString() ?: stringResource(R.string.audio_listening_auto)),
            Modifier.testTag("audio-listening-requested-device"), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        val phaseLabel = stringResource(when (status.phase) {
            AudioListeningPhase.DISABLED -> R.string.audio_listening_disabled
            AudioListeningPhase.NEEDS_CONNECT -> R.string.audio_listening_needs_connect
            AudioListeningPhase.WAITING_PCM -> R.string.audio_listening_waiting_pcm
            AudioListeningPhase.NO_OUTPUT -> R.string.audio_listening_no_output
            AudioListeningPhase.CONNECTING -> R.string.audio_listening_connecting
            AudioListeningPhase.ACTIVE -> R.string.audio_listening_active
            AudioListeningPhase.DISCONNECTED -> R.string.audio_listening_disconnected
            AudioListeningPhase.FAILED -> R.string.audio_listening_failed
            AudioListeningPhase.RETIRING -> R.string.audio_listening_retiring
        })
        Text(phaseLabel, Modifier.testTag("audio-listening-status"), color = if (status.phase == AudioListeningPhase.ACTIVE) VerifiedCyan else Muted)
        status.effectiveDeviceId?.let { id ->
            Text(stringResource(R.string.audio_listening_effective_device, status.deviceName ?: id.toString(), id),
                Modifier.testTag("audio-listening-effective-device"), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        }
        Text(stringResource(R.string.audio_listening_counters, status.acceptedFrames, status.droppedPackets),
            Modifier.testTag("audio-listening-counters"), color = Muted, fontSize = 14.sp)
        status.message?.let { Text(it, Modifier.testTag("audio-listening-message"), color = Muted, fontSize = 14.sp) }
        OutlinedButton(onClick = onReconnect,
            enabled = request.enabled && status.phase !in setOf(AudioListeningPhase.CONNECTING, AudioListeningPhase.RETIRING),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("audio-listening-connect")) {
            Text(stringResource(R.string.audio_listening_connect), Modifier.weight(1f).testTag("audio-listening-connect-label"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Intent controls only: meters are not proof of headphone playback or native gain application. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AudioRecordingGainSettings(
    state: CameraUiState,
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
) {
    val state = liveCameraState(state)
    val gain = settings.audioRecordingGain
    val manualLabel = stringResource(R.string.audio_gain_manual)
    val agcLabel = stringResource(R.string.audio_gain_agc_requested)
    val requestedLabel = stringResource(R.string.audio_gain_requested, gain.decibels)
    val gainInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle(stringResource(R.string.audio_gain_title), help = stringResource(R.string.audio_gain_help), helpTag = "audio-gain-help")
        SettingsSwitchRow(manualLabel, gain.enabled,
            { onSettingsChange(settings.copy(audioRecordingGain = gain.copy(enabled = it))) }, tag = "audio-gain-manual")
        Text(requestedLabel,
            Modifier.testTag("audio-gain-value"), color = MaterialTheme.colorScheme.onSurface)
        CineSlider(value = gain.decibels.toFloat(), valueRange = -24f..24f, steps = 47,
            enabled = gain.enabled,
            onValueChange = { onSettingsChange(settings.copy(audioRecordingGain = gain.copy(decibels = it.roundToInt()))) },
            interactionSource = gainInteraction,
            // Material3's inner slider measures from the thumb (44 dp by default), not the
            // outer minimum. Enlarge the actual thumb/layout, rather than tagging empty padding.
            thumb = { androidx.compose.material3.SliderDefaults.Thumb(
                interactionSource = gainInteraction, enabled = gain.enabled,
                thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 52.dp)) },
            track = { androidx.compose.material3.SliderDefaults.Track(sliderState = it, enabled = gain.enabled) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("audio-gain-slider").semantics { contentDescription = requestedLabel })
        SettingsSwitchRow(agcLabel, settings.automaticGainControlEnabled,
            { onSettingsChange(settings.copy(automaticGainControlEnabled = it)) }, tag = "audio-gain-agc", enabled = !gain.enabled)
        Text(stringResource(if (gain.enabled) R.string.audio_gain_agc_suspended else R.string.audio_gain_agc_backend),
            Modifier.testTag("audio-gain-agc-help"), color = Muted, fontSize = 14.sp)
        val applied = state.audioLevels?.appliedRecordingGain.takeIf { state.audioMonitoringActive }
        Text(when {
            applied == null -> stringResource(R.string.audio_gain_no_receipt)
            applied.enabled -> stringResource(R.string.audio_gain_applied, applied.decibels)
            else -> stringResource(R.string.audio_gain_manual_not_applied)
        }, Modifier.testTag("audio-gain-applied"), color = if (applied == null) Muted else VerifiedCyan, fontSize = 14.sp)
        if (state.structuralSettingsFrozen && (state.effectiveSettings?.audioRecordingGain != gain ||
                state.effectiveSettings?.automaticGainControlEnabled != settings.automaticGainControlEnabled)) {
            Text(stringResource(R.string.audio_gain_pending), Modifier.testTag("audio-gain-pending"), color = Amber, fontSize = 14.sp)
        }
    }
}

@Composable
private fun AudioChoiceRow(
    title: String,
    choices: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    val labels = choices.toMap()
    SettingsChips(title, choices.map { it.first }, selected, label = { labels.getValue(it) }, onSelect = onSelected)
}

/** "USB microphone · USB-C Lavalier"; the built-in microphone is named by its type alone. */
@Composable
private fun audioInputChoiceLabel(kind: com.librestatic.opencinecam.media.audio.AudioInputKind, productName: String, address: String): String {
    val type = stringResource(audioInputTypeLabel(kind))
    if (kind == com.librestatic.opencinecam.media.audio.AudioInputKind.BUILT_IN) {
        return builtInMicPositionLabel(address)?.let { "$type · ${stringResource(it)}" } ?: type
    }
    return if (productName.isBlank()) type else "$type · $productName"
}

@Composable
private fun audioInputLossPolicyLabel(policy: com.librestatic.opencinecam.media.audio.AudioInputLossPolicy): String = stringResource(
    when (policy) {
        com.librestatic.opencinecam.media.audio.AudioInputLossPolicy.STOP_TAKE -> R.string.audio_input_loss_stop
        com.librestatic.opencinecam.media.audio.AudioInputLossPolicy.FALLBACK_BUILTIN -> R.string.audio_input_loss_fallback
        com.librestatic.opencinecam.media.audio.AudioInputLossPolicy.CONTINUE_SILENT -> R.string.audio_input_loss_silent
    },
)

internal fun formatAudioRate(rate: Int): String = if (rate % 1_000 == 0) "${rate / 1_000} kHz" else "${rate / 1_000.0} kHz"

@Composable
internal fun audioOutputLabel(format: AudioOutputFormat): String = stringResource(
    when (format) {
        AudioOutputFormat.AAC_MP4 -> R.string.audio_aac_mp4
        AudioOutputFormat.WAV_PCM -> R.string.audio_wav_pcm
        AudioOutputFormat.FLAC -> R.string.audio_flac
    },
)

@Composable
private fun audioSourceLabel(source: com.librestatic.opencinecam.media.audio.AudioSourceSelection): String = stringResource(
    when (source) {
        com.librestatic.opencinecam.media.audio.AudioSourceSelection.UNPROCESSED -> R.string.audio_source_unprocessed
        com.librestatic.opencinecam.media.audio.AudioSourceSelection.VOICE_RECOGNITION -> R.string.audio_source_voice_recognition
        com.librestatic.opencinecam.media.audio.AudioSourceSelection.MIC -> R.string.audio_source_microphone
    },
)
