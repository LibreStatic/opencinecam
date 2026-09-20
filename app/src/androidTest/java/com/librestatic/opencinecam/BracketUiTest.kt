/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Range
import android.util.Size
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.camera.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BracketUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun countAndStepRemainEditableAndReportExactUnsupportedCombination() {
        val settings=mutableStateOf(CameraSettings(bracket=BracketSelection(9,BracketStep.TWO_EV),photoQuality=73))
        val descriptor=Camera2CameraDescriptor("bracket-ui",0,listOf(4f),Size(640,480),Size(640,480),null,null,90,null,null,Range(-3,3),1f/3f,null,false,false,
            emptyList(),listOf(30),emptyList(),emptyList(),photoFlashCapabilities=PhotoFlashCapabilities(aeModes=setOf(1)),
            aeCompensationStepNumerator=1,aeCompensationStepDenominator=3)
        val state=CameraUiState(cameras=listOf(descriptor),selectedCameraId=descriptor.cameraId,selectedMode=CaptureMode.BRACKET)
        compose.setContent {MaterialTheme {Column(Modifier.widthIn(max=280.dp).heightIn(max=480.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
            BracketSettings(state,settings.value) {settings.value=it}
        }}}
        compose.onNodeWithTag("bracket-rejected").assertExists()
        compose.onNodeWithTag("bracket-count-3").performScrollTo().performClick()
        compose.onNodeWithTag("bracket-step-HALF_EV").performScrollTo().performClick()
        compose.onNodeWithTag("bracket-rejected").assertExists() // 1/2 EV cannot map exactly to thirds.
        compose.onNodeWithTag("bracket-step-THIRD_EV").performScrollTo().performClick()
        compose.onNodeWithTag("bracket-rejected").assertDoesNotExist()
        compose.onNodeWithTag("bracket-exposures").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {assertEquals(BracketSelection(3,BracketStep.THIRD_EV),settings.value.bracket);assertEquals(73,settings.value.photoQuality)}
    }
    @Test fun progressAndSeparateCancellationAreAccessibleUntilPublicationWins() {
        val state=mutableStateOf(CameraUiState(phase=CameraUiPhase.CAPTURING,selectedMode=CaptureMode.BRACKET,stillCapturePending=true,bracketCaptured=2,bracketExpected=5))
        var cancellations=0
        compose.setContent {MaterialTheme {Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
            BracketCaptureProgress(state.value) {cancellations++}
        }}}
        compose.onNodeWithTag("bracket-progress").assertIsDisplayed()
        compose.onNodeWithTag("bracket-cancel").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,cancellations);state.value=state.value.copy(bracketSaving=true,bracketCaptured=5)}
        compose.onNodeWithTag("bracket-cancel").assertIsNotEnabled()
        compose.runOnIdle {state.value=state.value.copy(stillCapturePending=false,phase=CameraUiPhase.SAVED)}
        compose.onNodeWithTag("bracket-progress").assertDoesNotExist()
    }
}
