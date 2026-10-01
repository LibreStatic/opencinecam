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
import com.librestatic.opencinecam.camera.AudioChannelLevel
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Presentation fixtures are not physical calibration or a substitute for DSP acceptance tests. */
class AudioMeterSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings = mutableStateOf(CameraSettings(audioRecordingGain = DigitalRecordingGain(true, -6),
        audioListening = AudioListeningSettings(true, 33), audioInputDeviceId = 123))
    private val now = mutableStateOf(1000L)
    private val state = mutableStateOf(CameraUiState(phase = CameraUiPhase.RECORDING, audioMonitoringActive = true,
        audioLevels = AudioLevelSnapshot(listOf(AudioChannelLevel(-4f, -7f, -18f, -10f)), false, 1000)))
    private var resets = 0

    @Test fun allSettingsRemainEditableDuringRecWithoutChangingInputGainOrPlayback() {
        show()
        click("settings-visible")
        node("hud").assertDoesNotExist()
        click("settings-mode-VU")
        progress("settings-reference", -6f)
        progress("settings-hold", 0f)
        click("settings-values")
        compose.runOnIdle {
            assertEquals(AudioMeterSettings(false, AudioMeterMode.VU, -6, 0, true), settings.value.audioMeter)
            assertEquals(DigitalRecordingGain(true, -6), settings.value.audioRecordingGain)
            assertEquals(AudioListeningSettings(true, 33), settings.value.audioListening)
            assertEquals(123, settings.value.audioInputDeviceId)
        }
        click("settings-visible")
        node("hud").performScrollTo().assertIsDisplayed()
        progress("settings-reference", -24f)
        progress("settings-hold", 3000f)
        compose.runOnIdle { assertEquals(-24, settings.value.audioMeter.vuReferenceDbfs); assertEquals(3000, settings.value.audioMeter.peakHoldMs) }
    }

    @Test fun vuAndPpmNumbersUseTheirOwnBallisticsAndLegacyNullDoesNotBorrowRms() {
        settings.value = settings.value.copy(audioMeter = AudioMeterSettings(showValues = true))
        show(controls = false)
        node("value-0").assertTextEquals(text(R.string.audio_meter_peak_rms_values, "-4.0", "-7.0"))
        compose.runOnIdle { settings.value = settings.value.copy(audioMeter = settings.value.audioMeter.copy(mode = AudioMeterMode.VU)) }
        node("value-0").assertTextEquals(text(R.string.audio_meter_vu_value, "0.0"))
        compose.runOnIdle { settings.value = settings.value.copy(audioMeter = settings.value.audioMeter.copy(vuReferenceDbfs = -12)) }
        node("value-0").assertTextEquals(text(R.string.audio_meter_vu_value, "-6.0"))
        compose.runOnIdle { settings.value = settings.value.copy(audioMeter = settings.value.audioMeter.copy(mode = AudioMeterMode.PPM)) }
        node("value-0").assertTextEquals(text(R.string.audio_meter_ppm_value, "-10.0"))
        compose.runOnIdle { state.value = state.value.copy(audioLevels = state.value.audioLevels!!.copy(channels = listOf(AudioChannelLevel(-4f, -7f)))) }
        node("value-0").assertTextEquals(text(R.string.audio_meter_ppm_value, "—"))
        node("hold-0").assertDoesNotExist()
    }

    @Test fun retiredExpiredFutureOrAbsentPcmDoesNotLookCurrentButClipRemainsResettable() {
        settings.value = settings.value.copy(audioMeter = AudioMeterSettings(showValues = true))
        state.value = state.value.copy(audioClipLatched = true)
        show(controls = false)
        node("current").assertTextEquals("MIC")
        compose.runOnIdle { now.value = 1501 }
        node("current").assertTextEquals(text(R.string.audio_meter_no_pcm))
        node("value-0").assertTextEquals(text(R.string.audio_meter_peak_rms_values, "—", "—"))
        node("clip").assertTextEquals("CLIP")
        node("hud").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, resets) }
        node("clip").assertDoesNotExist()
        compose.runOnIdle { now.value = 999 }
        node("current").assertTextEquals(text(R.string.audio_meter_no_pcm))
        compose.runOnIdle { now.value = 1000; state.value = state.value.copy(audioMonitoringActive = false) }
        node("current").assertTextEquals(text(R.string.audio_meter_no_pcm))
        compose.runOnIdle { state.value = state.value.copy(audioMonitoringActive = true, audioLevels = null) }
        node("current").assertTextEquals(text(R.string.audio_meter_no_pcm))
    }

    @Test fun stereoHoldExpiresPerChannelAndModeChangeResetsPresentationHistory() {
        settings.value = settings.value.copy(audioMeter = AudioMeterSettings(peakHoldMs = 300, showValues = true))
        state.value = state.value.copy(audioLevels = AudioLevelSnapshot(listOf(
            AudioChannelLevel(-3f, -9f, -18f, -12f), AudioChannelLevel(-24f, -30f, -30f, -20f)), false, 1000))
        show(controls = false)
        node("hold-0").assertTextEquals(text(R.string.audio_meter_hold_value, "-3.0"))
        compose.runOnIdle {
            now.value = 1100
            state.value = state.value.copy(audioLevels = AudioLevelSnapshot(listOf(
                AudioChannelLevel(-30f, -40f, -36f, -25f), AudioChannelLevel(-12f, -18f, -24f, -15f)), false, 1100))
        }
        node("hold-0").assertTextEquals(text(R.string.audio_meter_hold_value, "-3.0"))
        node("hold-1").assertTextEquals(text(R.string.audio_meter_hold_value, "-12.0"))
        compose.runOnIdle { now.value = 1300 }
        node("hold-0").assertTextEquals(text(R.string.audio_meter_hold_value, "-30.0"))
        compose.runOnIdle { settings.value = settings.value.copy(audioMeter = settings.value.audioMeter.copy(mode = AudioMeterMode.VU)) }
        node("hold-0").assertTextEquals(text(R.string.audio_meter_hold_value, "-18.0"))
        compose.runOnIdle { settings.value = settings.value.copy(audioMeter = settings.value.audioMeter.copy(showValues = false)) }
        node("value-0").assertDoesNotExist()
        node("hold-0").assertDoesNotExist()
    }

    @Test fun clockAgesAnUnchangedReceiptWithoutAnotherPcmCallback() {
        state.value = state.value.copy(audioLevels = state.value.audioLevels!!.copy(capturedAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()))
        show(controls = false, liveClock = true)
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(text(R.string.audio_meter_no_pcm), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        node("current").assertTextEquals(text(R.string.audio_meter_no_pcm))
    }

    @Test fun doubleFontControlsHaveRealFortyEightDpTargetsAndLabelsDoNotClip() {
        show(fontScale = 2f)
        for (tag in listOf("settings-visible", "settings-mode-PEAK_RMS", "settings-mode-VU", "settings-mode-PPM", "settings-reference", "settings-hold", "settings-values")) {
            node(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        node("settings-help-toggle").performScrollTo().performClick()
        for (tag in listOf("settings-help", "settings-mode-PEAK_RMS-label", "settings-mode-VU-label", "settings-mode-PPM-label", "settings-reference-label", "settings-hold-label")) {
            val result = mutableListOf<TextLayoutResult>()
            node(tag).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(result)) }
            assertEquals(1, result.size)
            assertFalse("Overflow: $tag ${result.single().size}", result.single().hasVisualOverflow)
        }
    }

    private fun show(fontScale: Float = 1f, controls: Boolean = true, liveClock: Boolean = false) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).height(420.dp).verticalScroll(rememberScrollState())) {
                        AudioMeterHud(state.value, null, meterSettings = settings.value.audioMeter,
                            nowElapsedRealtimeMs = if (liveClock) null else now.value,
                            onResetClip = { resets++; state.value = state.value.copy(audioClipLatched = false) })
                        if (controls) AudioMeterSettingsControls(settings.value, { settings.value = it })
                    }
                }
            }
        }
    }
    // Meter mode choices live in a dialog behind their value row.
    private fun node(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        if (tag.startsWith("settings-mode-")) compose.openChoice("audio-meter-${tag.removeSuffix("-label")}") else compose.closeChoices()
        return compose.onNodeWithTag("audio-meter-$tag", useUnmergedTree = true)
    }
    private fun click(tag: String) { node(tag).performScrollTo().assertIsEnabled().performClick() }
    private fun progress(tag: String, value: Float) { node(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) } }
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
}
