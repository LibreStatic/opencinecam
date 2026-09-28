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
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Presentation intent only. Accepted frames and runtime phases are supplied observations here. */
class AudioListeningSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings = mutableStateOf(CameraSettings(audioRecordingGain = DigitalRecordingGain(true, -6), audioInputDeviceId = 111))
    private val state = mutableStateOf(CameraUiState(audioListeningOutputs = listOf(
        AudioListeningDevice(212, "Fixture USB headphones", AudioListeningOutput.WIRED_USB),
        AudioListeningDevice(313, "Fixture Bluetooth headphones", AudioListeningOutput.BLUETOOTH))))
    private var connections = 0

    @Test fun preferencesIncludingAnImportedEnabledStateNeverConnectWithoutButton() {
        show()
        node("connect").performScrollTo().assertIsNotEnabled()
        click("enable")
        click("output-SPEAKER")
        compose.runOnIdle { assertEquals(0, connections) }
        node("route-help").performScrollTo().assertTextEquals(text(R.string.audio_listening_speaker_warning))
        compose.runOnIdle {
            // A restored/imported preference is not a connection authorization.
            settings.value = settings.value.copy(audioListening = AudioListeningSettings(true, 42, AudioListeningOutput.BLUETOOTH))
            state.value = state.value.copy(audioListeningStatus = AudioListeningStatus(AudioListeningPhase.NEEDS_CONNECT))
        }
        node("status").performScrollTo().assertTextEquals(text(R.string.audio_listening_needs_connect))
        compose.runOnIdle { assertEquals(0, connections) }
        click("connect")
        compose.runOnIdle { assertEquals(1, connections) }
        click("enable")
        node("connect").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, connections) }
    }
    @Test fun liveVolumeAndOutputSelectionNeverMutateRecordingGainOrInput() {
        state.value = state.value.copy(phase = CameraUiPhase.RECORDING, effectiveSettings = settings.value)
        show()
        click("enable")
        click("device-212")
        for (volume in listOf(0, 100, 73)) {
            node("volume").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(volume.toFloat())) }
            compose.runOnIdle { assertEquals(volume, settings.value.audioListening.volumePercent) }
        }
        click("output-BLUETOOTH")
        compose.runOnIdle { assertNull(settings.value.audioListeningOutputDeviceId) }
        click("device-313")
        compose.runOnIdle {
            assertEquals(313, settings.value.audioListeningOutputDeviceId)
            assertEquals(111, settings.value.audioInputDeviceId)
            assertEquals(DigitalRecordingGain(true, -6), settings.value.audioRecordingGain)
            assertEquals(0, connections)
        }
    }
    @Test fun appliedRouteAndConnectionPhaseComeOnlyFromRuntimeNotLevelMeters() {
        settings.value = settings.value.copy(audioListening = AudioListeningSettings(true))
        state.value = state.value.copy(audioMonitoringActive = true)
        show()
        node("status").performScrollTo().assertTextEquals(text(R.string.audio_listening_disabled))
        node("effective-device").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(audioListeningStatus = AudioListeningStatus(
            phase = AudioListeningPhase.ACTIVE, requestedDeviceId = 212, effectiveDeviceId = 212,
            deviceName = "Fixture USB", acceptedFrames = 48000, droppedPackets = 3)) }
        node("status").performScrollTo().assertTextEquals(text(R.string.audio_listening_active))
        node("effective-device").performScrollTo().assertTextEquals(text(R.string.audio_listening_effective_device, "Fixture USB", 212))
        node("counters").performScrollTo().assertTextEquals(text(R.string.audio_listening_counters, 48000L, 3L))
        for (phase in listOf(AudioListeningPhase.CONNECTING, AudioListeningPhase.RETIRING)) {
            compose.runOnIdle { state.value = state.value.copy(audioListeningStatus = AudioListeningStatus(phase)) }
            node("connect").performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle { state.value = state.value.copy(audioListeningStatus = AudioListeningStatus(AudioListeningPhase.DISCONNECTED)) }
        node("status").performScrollTo().assertTextEquals(text(R.string.audio_listening_disconnected))
        node("effective-device").assertDoesNotExist()
        node("connect").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, connections) }
    }
    @Test fun doubleFontControlsRemainReachableWithRealFortyEightDpTargetsAndWrappedLabels() {
        show(2f)
        listOf("enable", "volume", "output-WIRED_USB", "output-BLUETOOTH", "output-SPEAKER", "device-auto", "connect").forEach { tag ->
            val target = node(tag).performScrollTo().assertIsDisplayed()
            try { target.assertHeightIsAtLeast(48.dp) }
            catch (failure: AssertionError) { throw AssertionError("Control $tag: ${target.getUnclippedBoundsInRoot()}", failure) }
        }
        node("help-toggle").performScrollTo().performClick()
        for (tag in listOf("help", "volume-label", "output-WIRED_USB-label", "output-BLUETOOTH-label", "output-SPEAKER-label", "connect-label")) {
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
                        AudioListeningSettingsControls(state.value, settings.value, { settings.value = it }, { connections++ })
                    }
                }
            }
        }
    }
    private fun node(tag: String) = compose.onNodeWithTag("audio-listening-$tag", useUnmergedTree = tag.endsWith("-label"))
    private fun click(tag: String) { node(tag).performScrollTo().assertIsEnabled().performClick() }
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
}
