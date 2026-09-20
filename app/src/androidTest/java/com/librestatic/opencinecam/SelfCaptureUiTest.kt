/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class SelfCaptureUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun subjectReceivesCountdownWithoutAcquiringCaptureActions() {
        compose.setContent { MaterialTheme { SubjectDisplayScreen(CameraUiState(countdownSeconds = 3), SubjectDisplaySettings()) } }
        compose.onNodeWithContentDescription(context.getString(R.string.self_countdown, 3)).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }
    @Test fun minimalRoleKeepsReturnSettingsAndLargeCaptureAtTwoHundredPercentFont() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(420.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    MaterialTheme { SelfCaptureChrome(CameraUiState(phase = CameraUiPhase.PREVIEWING, selfRecordingActive = true), null,
                        CameraSettings(audioEnabled = false), {}, {}) }
                }
            }
        }
        compose.onNodeWithTag("self-return").assertIsDisplayed()
        compose.onNodeWithTag("self-settings").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.capture_photo)).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }
    @Test fun preparingRoleDoesNotClaimReadyOrAllowAudioReconfiguration() {
        compose.setContent { MaterialTheme { SelfCaptureChrome(CameraUiState(phase = CameraUiPhase.OPENING, selfRecordingActive = true), null,
            CameraSettings(audioEnabled = false), {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.subject_preparing)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.subject_ready)).assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.capture_photo)).assertIsNotEnabled()
    }
    @Test fun countdownCaptureButtonExplicitlyMeansCancel() {
        compose.setContent { MaterialTheme { CaptureButton(CameraUiState(phase = CameraUiPhase.PREVIEWING, selfRecordingActive = true, countdownSeconds = 3), null, CameraSettings(), 88.dp) } }
        compose.onNodeWithContentDescription(context.getString(R.string.self_cancel_timer)).assertIsDisplayed().assertHasClickAction()
    }
}
