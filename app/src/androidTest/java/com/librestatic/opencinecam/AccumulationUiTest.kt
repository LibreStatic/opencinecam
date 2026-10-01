/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.camera.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccumulationUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun allModesAndParametersRemainReachableInNarrowScrolledSettings() {
        val settings=mutableStateOf(CameraSettings(photoQuality=73))
        compose.setContent {MaterialTheme {Column(Modifier.width(280.dp).height(480.dp).verticalScroll(rememberScrollState())) {
            AccumulationSettings(CameraUiState(),settings.value) {settings.value=it}
        }}}
        for(mode in AccumulationMode.entries) {
            compose.pickChoice("accumulation-mode-$mode")
            compose.runOnIdle {assertEquals(mode,settings.value.accumulation.mode)}
            compose.onNodeWithTag("accumulation-algorithm").assertExists()
        }
        compose.pickChoice("accumulation-duration-30")
        compose.pickChoice("accumulation-interval-10000")
        compose.openChoice("accumulation-duration-1").assertIsNotEnabled()
        compose.pickChoice("accumulation-interval-100")
        compose.pickChoice("accumulation-duration-1")
        compose.pickChoice("accumulation-edge-720")
        compose.pickChoice("accumulation-mode-STARS")
        compose.onNodeWithTag("accumulation-threshold").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) {it(32f)}
        compose.runOnIdle {assertEquals(AccumulationSelection(AccumulationMode.STARS,1000,100,720,32),settings.value.accumulation);assertEquals(73,settings.value.photoQuality)}
    }
    @Test fun finishRequiresTwoFramesAndIsDistinctFromCancelUntilPublicationWins() {
        val state=mutableStateOf(CameraUiState(selectedMode=CaptureMode.LIGHT_TRAIL,phase=CameraUiPhase.CAPTURING,
            stillCapturePending=true,accumulationFrames=1,accumulationTargetMs=30000))
        var finished=0;var cancelled=0
        compose.setContent {MaterialTheme {Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
            AccumulationCaptureProgress(state.value,{finished++},{cancelled++})
        }}}
        compose.onNodeWithTag("accumulation-progress").assertIsDisplayed()
        compose.onNodeWithTag("accumulation-finish").assertIsNotEnabled()
        compose.runOnIdle {state.value=state.value.copy(accumulationFrames=2)}
        compose.onNodeWithTag("accumulation-finish").performScrollTo().performClick()
        compose.onNodeWithTag("accumulation-cancel").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,finished);assertEquals(1,cancelled);state.value=state.value.copy(accumulationSaving=true)}
        compose.onNodeWithTag("accumulation-finish").assertIsNotEnabled();compose.onNodeWithTag("accumulation-cancel").assertIsNotEnabled()
        compose.runOnIdle {state.value=state.value.copy(stillCapturePending=false)}
        compose.onNodeWithTag("accumulation-progress").assertDoesNotExist()
    }
}
