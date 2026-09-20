/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BurstUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun progressAndSeparateCancellationAreAccessibleUntilPublicationWins() {
        val state=mutableStateOf(CameraUiState(phase=CameraUiPhase.CAPTURING,selectedMode=CaptureMode.BURST,stillCapturePending=true,burstCaptured=2,burstExpected=5))
        var cancellations=0
        compose.setContent {MaterialTheme {Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
            BurstCaptureProgress(state.value) {cancellations++}
        }}}
        compose.onNodeWithTag("burst-progress").assertIsDisplayed()
        compose.onNodeWithTag("burst-cancel").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,cancellations);state.value=state.value.copy(burstSaving=true,burstCaptured=5)}
        compose.onNodeWithTag("burst-cancel").assertIsNotEnabled()
        compose.runOnIdle {state.value=state.value.copy(stillCapturePending=false,phase=CameraUiPhase.SAVED)}
        compose.onNodeWithTag("burst-progress").assertDoesNotExist()
    }
}
