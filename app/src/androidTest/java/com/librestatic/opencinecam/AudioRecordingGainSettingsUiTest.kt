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
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Intent and receipt rendering only; these tests do not manufacture native audio acceptance. */
class AudioRecordingGainSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings = mutableStateOf(CameraSettings(photoQuality = 71, audioInputDeviceId = 101))
    private val state = mutableStateOf(CameraUiState())

    @Test fun manualZeroIsExplicitAndSuspendsRatherThanErasesAgc() {
        show()
        node("slider").performScrollTo().assertIsNotEnabled()
        node("manual").performScrollTo().assertIsOff().performClick().assertIsOn()
        node("agc").performScrollTo().assertIsOn().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(DigitalRecordingGain(true, 0), settings.value.audioRecordingGain)
            assertTrue(settings.value.automaticGainControlEnabled)
        }
        for (db in listOf(-24, 24, 0)) {
            node("slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(db.toFloat())) }
            compose.runOnIdle { assertEquals(db, settings.value.audioRecordingGain.decibels) }
        }
        node("manual").performScrollTo().performClick()
        node("agc").performScrollTo().assertIsEnabled().assertIsOn().performClick()
        compose.runOnIdle {
            assertFalse(settings.value.automaticGainControlEnabled)
            assertFalse(settings.value.audioRecordingGain.enabled)
            assertEquals(71, settings.value.photoQuality); assertEquals(101, settings.value.audioInputDeviceId)
        }
    }
    @Test fun requestedAndAppliedRemainDistinctWhileChangeIsPendingForNextTake() {
        val admitted = DigitalRecordingGain(true, -6)
        settings.value = settings.value.copy(audioRecordingGain = admitted)
        state.value = CameraUiState(phase = CameraUiPhase.RECORDING, effectiveSettings = settings.value,
            audioMonitoringActive = true, audioLevels = AudioLevelSnapshot(emptyList(), false, 1L, appliedRecordingGain = admitted))
        show()
        node("slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(12f)) }
        node("pending").performScrollTo().assertIsDisplayed()
        node("applied").performScrollTo().assertTextEquals(text(R.string.audio_gain_applied, -6))
        compose.runOnIdle {
            assertEquals(12, settings.value.audioRecordingGain.decibels)
            assertEquals(admitted, state.value.audioLevels?.appliedRecordingGain)
            state.value = state.value.copy(audioMonitoringActive = false)
        }
        // Retained last-frame metadata cannot impersonate an active producer's receipt.
        node("applied").assertTextEquals(text(R.string.audio_gain_no_receipt))
        compose.runOnIdle { state.value = state.value.copy(audioMonitoringActive = true, audioLevels = null) }
        node("applied").assertTextEquals(text(R.string.audio_gain_no_receipt))
        compose.runOnIdle { state.value = state.value.copy(audioLevels = AudioLevelSnapshot(emptyList(), false, 2L,
            appliedRecordingGain = DigitalRecordingGain())) }
        node("applied").assertTextEquals(text(R.string.audio_gain_manual_not_applied))
    }
    @Test fun doubleFontLabelsReflowAndEveryControlHasFortyEightDpTarget() {
        show(fontScale = 2f)
        listOf("manual", "slider", "agc").forEach { tag ->
            val target = node(tag).performScrollTo().assertIsDisplayed()
            try { target.assertHeightIsAtLeast(48.dp) }
            catch (failure: AssertionError) { throw AssertionError("Control $tag: ${target.getUnclippedBoundsInRoot()}", failure) }
        }
        for (tag in listOf("help", "value", "agc-help", "applied")) {
            val results = mutableListOf<TextLayoutResult>()
            node(tag).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
            assertEquals(1, results.size)
            assertFalse("Overflow: $tag ${results.single().size}", results.single().hasVisualOverflow)
        }
    }
    private fun show(fontScale: Float = 1f) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).height(420.dp).verticalScroll(rememberScrollState())) {
                        AudioRecordingGainSettings(state.value, settings.value) { settings.value = it }
                    }
                }
            }
        }
    }
    private fun node(tag: String) = compose.onNodeWithTag("audio-gain-$tag")
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
}
