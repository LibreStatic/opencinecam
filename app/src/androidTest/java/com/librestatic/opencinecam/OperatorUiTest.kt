/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OperatorUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun transferRetirementPreparationHasAnEnabledDistinctCancelAction() {
        var clicks = 0
        val state = mutableStateOf(CameraUiState(phase = CameraUiPhase.CAPTURING,
            selectedMode = CaptureMode.TIME_LAPSE, transferRetirementPending = true))
        compose.setContent {
            CompositionLocalProvider(LocalOperatorActions provides OperatorActions({ clicks++ }, {}, { true })) {
                MaterialTheme { CaptureButton(state.value, null, CameraSettings(), 88.dp) }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.webdav_transfer_cancel_capture))
            .assertIsEnabled().performClick()
        assertEquals(1, clicks)
        compose.runOnUiThread { state.value = state.value.copy(transferRetirementPending = false) }
        compose.onNodeWithContentDescription(context.getString(R.string.start_recording)).assertIsNotEnabled()
    }
    @Test fun threeButtonsDispatchOnlyTheirAssignedAction() {
        val seen = mutableListOf<OperatorAction>()
        compose.setContent { MaterialTheme { OperatorButtonRow(CameraUiState(), CameraSettings(), OperatorActions({}, { seen.add(it) }, { true })) } }
        for (i in 1..3) compose.onNodeWithTag("operator-button-$i").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(OperatorAction.TORCH, OperatorAction.PEAKING, OperatorAction.VIEW_ASSIST), seen)
    }
    @Test fun unsupportedActionIsDisabledRatherThanPretendingToExecute() {
        var invoked = false
        compose.setContent { MaterialTheme { OperatorButtonRow(CameraUiState(), CameraSettings(), OperatorActions({}, { invoked = true }, { it == OperatorAction.PEAKING })) } }
        compose.onNodeWithTag("operator-button-1").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("operator-button-2").assertIsEnabled()
        assertFalse(invoked)
        // The long-press help explains why the action is unavailable, so it stays reachable.
        compose.onNodeWithTag("operator-button-1").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        compose.onNodeWithTag("operator-button-1").performTouchInput { longClick() }
        assertFalse(invoked)
    }
    @Test fun mappingDialogAtDoubleFontUpdatesOnlyTheSelectedButton() {
        var actual = CameraSettings()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(420.dp, 740.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    var settings by remember { mutableStateOf(actual) }
                    MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        OperatorSettings(CameraUiState(), settings) { settings = it; actual = it }
                    } }
                }
            }
        }
        compose.onNodeWithTag("operator-map-2").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("operator-choice-PRESET_C2").performScrollTo().performClick()
        assertEquals(OperatorAction.PRESET_C2, actual.operation.button2)
        assertEquals(OperatorAction.TORCH, actual.operation.button1); assertEquals(OperatorAction.SYSTEM_VOLUME, actual.operation.volumeUp)
    }
    @Test fun takeLockCanBeExplicitlyDisabledInSettingsAndStartupPolicyPersists() {
        val memory = PresetPreferences(); val repository = SettingsRepository(CameraSettingsStore(memory))
        repository.update { it.copy(operation = it.operation.copy(lockDuringTake = true)) }
        compose.setContent {
            val settings by repository.states.collectAsState()
            MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                OperatorSettings(CameraUiState(phase = CameraUiPhase.RECORDING, effectiveSettings = settings), settings, repository::set)
            } }
        }
        compose.onNodeWithTag("operator-lock").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("operator-lock").assertIsOff()
        compose.onNodeWithTag("operator-startup-LAST").performScrollTo().performClick()
        assertFalse(CameraSettingsStore(memory).load().operation.lockDuringTake)
        assertEquals(StartupMode.LAST, CameraSettingsStore(memory).load().operation.startupMode)
    }
    @Test fun toggleButtonsShowTheirLatchedStateWithoutBeingPressed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = CameraSettings(flashEnabled = false, peakingEnabled = true, logViewAssistEnabled = true)
        // View assist only exists in LOG, so that is where its latched state reads ON.
        compose.setContent { MaterialTheme { OperatorButtonRow(CameraUiState(selectedMode = CaptureMode.LOG), settings, OperatorActions({}, {}, { true })) } }
        // F1 torch off, F2 peaking on, F3 view assist on: readable before any press.
        compose.onNodeWithTag("operator-button-1-state", useUnmergedTree = true).assertTextEquals(context.getString(R.string.operator_state_off))
        compose.onNodeWithTag("operator-button-2-state", useUnmergedTree = true).assertTextEquals(context.getString(R.string.operator_state_on))
        compose.onNodeWithTag("operator-button-3-state", useUnmergedTree = true).assertTextEquals(context.getString(R.string.operator_state_on))
        // TalkBack must hear the same state the pill shows.
        compose.onNodeWithTag("operator-button-1").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, context.getString(R.string.operator_state_off_description)))
        compose.onNodeWithTag("operator-button-2").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, context.getString(R.string.operator_state_on_description)))
        for (index in 1..3) compose.onNodeWithTag("operator-button-$index").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
    }
    @Test fun momentaryActionsClaimNoOnOffStateAndEveryActionExplainsItself() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = CameraSettings(operation = OperatorPreferences(
            button1 = OperatorAction.FOCUS_A, button2 = OperatorAction.AUTO_FOCUS, button3 = OperatorAction.ZEBRA))
        compose.setContent { MaterialTheme { OperatorButtonRow(CameraUiState(), settings, OperatorActions({}, {}, { true })) } }
        for (index in 1..2) compose.onNodeWithTag("operator-button-$index-state", useUnmergedTree = true).assertDoesNotExist()
        for (index in 1..2) compose.onNodeWithTag("operator-button-$index").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNodeWithTag("operator-button-3-state", useUnmergedTree = true).assertExists()
        // A button the operator cannot interpret is the bug being fixed; every action carries its own help.
        val help = OperatorAction.entries.map { context.getString(it.helpResource()) }
        assertEquals(help.size, help.distinct().size)
        assertTrue(help.none { it.isBlank() })
    }
    @Test fun volumeStartupAndLockSearchReachTheCanonicalCategory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (query in listOf("volumen", "botones programables", "startup", "bloqueo toma")) {
            assertTrue(SettingsCatalog.search(query, SettingsCategory.CONTROLS) { context.getString(it) }.contains("operator-controls"))
        }
    }
}
