/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.AudioEffectImplementation
import com.librestatic.opencinecam.camera.AudioEffectObservation
import com.librestatic.opencinecam.camera.AudioEffectState
import com.librestatic.opencinecam.camera.AudioEffectsSnapshot
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI observations are supplied fixtures, not evidence that a physical audio effect is available. */
class AudioEffectsSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings = mutableStateOf(CameraSettings(noiseSuppressorEnabled = true,
        automaticGainControlEnabled = true, acousticEchoCancelerEnabled = true))
    private val state = mutableStateOf(CameraUiState())

    @Test fun preferencesAndRetiredOrAbsentPcmNeverImplyEffectsApplied() {
        state.value = state.value.copy(audioLevels = levels(observation(AudioEffectState.ENABLED)))
        show()
        for (tag in listOf("ns", "agc", "aec")) {
            node("$tag-requested").performScrollTo().assertTextEquals(text(R.string.audio_effect_requested, text(R.string.audio_effect_yes)))
            node("$tag-state").performScrollTo().assertTextEquals(text(R.string.audio_effect_unobserved))
            node("$tag-receipt").assertDoesNotExist()
        }
        compose.runOnIdle { state.value = state.value.copy(audioMonitoringActive = true, audioLevels = null) }
        node("agc-state").performScrollTo().assertTextEquals(text(R.string.audio_effect_unobserved))
        compose.runOnIdle { state.value = state.value.copy(audioLevels = levels(observation(AudioEffectState.ENABLED))) }
        node("agc-state").performScrollTo().assertTextEquals(text(R.string.audio_effect_observed, text(R.string.audio_effect_enabled)))
        compose.runOnIdle { state.value = state.value.copy(audioMonitoringActive = false) }
        node("agc-state").performScrollTo().assertTextEquals(text(R.string.audio_effect_unobserved))
        node("agc-implementation").assertDoesNotExist()
    }

    @Test fun everyObservedStateAndPlatformControlRemainDistinctFromRequests() {
        show()
        val labels = listOf(AudioEffectState.UNKNOWN to R.string.audio_effect_unknown,
            AudioEffectState.UNAVAILABLE to R.string.audio_effect_unavailable,
            AudioEffectState.ENABLED to R.string.audio_effect_enabled,
            AudioEffectState.DISABLED to R.string.audio_effect_disabled,
            AudioEffectState.FAILED to R.string.audio_effect_failed)
        for ((observed, label) in labels) {
            compose.runOnIdle { state.value = state.value.copy(audioMonitoringActive = true,
                audioLevels = levels(AudioEffectObservation(false, observed, AudioEffectImplementation.PLATFORM, false))) }
            node("ns-state").performScrollTo().assertTextEquals(text(R.string.audio_effect_observed, text(label)))
            node("ns-requested").performScrollTo().assertTextEquals(text(R.string.audio_effect_requested, text(R.string.audio_effect_yes)))
            node("ns-receipt").performScrollTo().assertTextEquals(text(R.string.audio_effect_receipt_requested, text(R.string.audio_effect_no)))
            node("ns-control").performScrollTo().assertTextEquals(text(R.string.audio_effect_control, text(R.string.audio_effect_no)))
        }
        for (control in listOf(true, null)) {
            compose.runOnIdle { state.value = state.value.copy(audioLevels = levels(
                AudioEffectObservation(true, AudioEffectState.ENABLED, AudioEffectImplementation.PLATFORM, control))) }
            node("ns-control").performScrollTo().assertTextEquals(text(R.string.audio_effect_control,
                text(if (control == true) R.string.audio_effect_yes else R.string.audio_effect_control_unknown)))
        }
        compose.runOnIdle { state.value = state.value.copy(audioLevels = levels(
            AudioEffectObservation(true, AudioEffectState.UNAVAILABLE, AudioEffectImplementation.NONE))) }
        node("ns-implementation").performScrollTo().assertTextEquals(text(R.string.audio_effect_implementation, text(R.string.audio_effect_none)))
    }

    @Test fun manualGainAndFrozenPreferencesDoNotRewriteSoftwareReceiptOrHideSetupFailure() {
        val effective = settings.value
        settings.value = settings.value.copy(noiseSuppressorEnabled = false, acousticEchoCancelerEnabled = false,
            audioRecordingGain = DigitalRecordingGain(true, 0))
        state.value = state.value.copy(phase = CameraUiPhase.RECORDING, effectiveSettings = effective,
            audioMonitoringActive = true, audioLevels = levels(AudioEffectObservation(true,
                AudioEffectState.ENABLED, AudioEffectImplementation.SOFTWARE, null, configurationFailed = true)))
        show()
        node("agc-requested").performScrollTo().assertTextEquals(text(R.string.audio_effect_requested, text(R.string.audio_effect_yes)))
        node("agc-state").performScrollTo().assertTextEquals(text(R.string.audio_effect_observed, text(R.string.audio_effect_enabled)))
        node("agc-implementation").performScrollTo().assertTextEquals(text(R.string.audio_effect_implementation, text(R.string.audio_effect_software)))
        node("agc-configuration-failed").performScrollTo().assertTextEquals(text(R.string.audio_effect_configuration_failed))
        node("manual").performScrollTo().assertTextEquals(text(R.string.audio_effect_manual))
        for (effect in listOf("ns", "agc", "aec")) node("$effect-pending").performScrollTo().assertTextEquals(text(R.string.audio_effect_pending))
        compose.onNodeWithTag("audio-gain-agc").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(settings.value.automaticGainControlEnabled)
            state.value = state.value.copy(effectiveSettings = settings.value,
                audioLevels = levels(AudioEffectObservation(true, AudioEffectState.DISABLED, AudioEffectImplementation.PLATFORM, true))
                    .copy(appliedRecordingGain = settings.value.audioRecordingGain))
        }
        node("agc-pending").assertDoesNotExist()
        node("agc-configuration-failed").assertDoesNotExist()
        node("agc-receipt").performScrollTo().assertTextEquals(text(R.string.audio_effect_receipt_requested, text(R.string.audio_effect_yes)))
        node("agc-state").performScrollTo().assertTextEquals(text(R.string.audio_effect_observed, text(R.string.audio_effect_disabled)))
    }

    @Test fun doubleFontObservationsWrapAndExistingGainControlsKeepFortyEightDpTargets() {
        val effective = settings.value
        settings.value = settings.value.copy(audioRecordingGain = DigitalRecordingGain(true, 0))
        state.value = state.value.copy(phase = CameraUiPhase.RECORDING, effectiveSettings = effective,
            audioMonitoringActive = true, audioLevels = levels(AudioEffectObservation(true,
                AudioEffectState.ENABLED, AudioEffectImplementation.SOFTWARE, null, configurationFailed = true)))
        show(2f)
        val tags = listOf("help", "manual", "agc-pending") + listOf("ns", "agc", "aec").flatMap { effect ->
            listOf("title", "requested", "receipt", "state", "implementation", "control", "configuration-failed").map { "$effect-$it" }
        }
        for (tag in tags) {
            val results = mutableListOf<TextLayoutResult>()
            node(tag).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
            assertEquals(1, results.size)
            assertFalse("Overflow: $tag ${results.single().size}", results.single().hasVisualOverflow)
        }
        for (tag in listOf("audio-gain-manual", "audio-gain-slider", "audio-gain-agc")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun aacSettingsExposeNsAndAecRequestsWithoutClaimingAnObservedEffect() {
        val capabilities = com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities(
            permissionGranted = true,
            formats = listOf(com.librestatic.opencinecam.media.audio.AudioOutputFormat.AAC_MP4),
            sampleRates = listOf(48_000), bitDepths = emptyList(), channelCounts = listOf(1),
            pcmConfigurations = emptyList(), aacSampleRates = listOf(48_000), aacChannelCounts = listOf(1),
            aacBitratesKbps = listOf(128),
            sources = listOf(com.librestatic.opencinecam.media.audio.AudioSourceSelection.MIC), inputs = emptyList(),
            noiseSuppressorAvailable = true, automaticGainControlAvailable = false, acousticEchoCancelerAvailable = true)
        settings.value = settings.value.copy(audioOutputFormat = com.librestatic.opencinecam.media.audio.AudioOutputFormat.AAC_MP4,
            noiseSuppressorEnabled = false, acousticEchoCancelerEnabled = false)
        state.value = state.value.copy(audioCapabilities = capabilities)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).height(600.dp)) {
                    SettingsContent(state.value, settings.value, true, {}, {}, { settings.value = it }, setOf("audio-format"))
                }
            }
        }
        for (label in listOf(R.string.audio_effect_ns, R.string.audio_effect_aec)) {
            compose.onNode(hasText(text(label)) and isToggleable()).performScrollTo()
                .assertIsEnabled().assertIsOff().assertHeightIsAtLeast(48.dp).performClick().assertIsOn()
        }
        compose.runOnIdle {
            assertTrue(settings.value.noiseSuppressorEnabled)
            assertTrue(settings.value.acousticEchoCancelerEnabled)
            assertEquals(com.librestatic.opencinecam.media.audio.AudioOutputFormat.AAC_MP4, settings.value.audioOutputFormat)
        }
        node("ns-state").performScrollTo().assertTextEquals(text(R.string.audio_effect_unobserved))
        node("aec-state").performScrollTo().assertTextEquals(text(R.string.audio_effect_unobserved))
        compose.runOnIdle { state.value = state.value.copy(audioCapabilities = capabilities.copy(
            noiseSuppressorAvailable = false, acousticEchoCancelerAvailable = false)) }
        for (label in listOf(R.string.audio_effect_ns, R.string.audio_effect_aec)) {
            compose.onNode(hasText(text(label)) and isToggleable()).performScrollTo().assertIsNotEnabled()
        }
    }

    private fun observation(observed: AudioEffectState) = AudioEffectObservation(true, observed, AudioEffectImplementation.PLATFORM, true)
    private fun levels(value: AudioEffectObservation) = AudioLevelSnapshot(emptyList(), false, 1L,
        effects = AudioEffectsSnapshot(value, value, value))
    private fun show(fontScale: Float = 1f) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).height(420.dp).verticalScroll(rememberScrollState())) {
                        AudioEffectsSettingsStatus(state.value, settings.value)
                        AudioRecordingGainSettings(state.value, settings.value, { settings.value = it })
                    }
                }
            }
        }
    }
    private fun node(tag: String) = compose.onNodeWithTag("audio-effects-$tag")
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
}
