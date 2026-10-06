/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class FoldDisplayUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun unprobedDisplayCannotBeStartedByTheSettingsButtons() {
        compose.setContent { MaterialTheme { FoldDisplaySettings(CameraUiState(), CameraSettings(), {}) } }
        // The reason replaces the subject-display action; self recording stays offered but disabled.
        compose.onNodeWithTag("fold-present").assertDoesNotExist()
        compose.onNodeWithTag("fold-present-reason").assertIsDisplayed()
        compose.onNodeWithTag("fold-transfer").assertIsNotEnabled()
    }

    @Test fun subjectStatusHasNoOperatorActions() {
        compose.setContent { MaterialTheme { SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.RECORDING, recordingElapsedMs = 65_000), SubjectDisplaySettings()) } }
        compose.onNodeWithText("01:05").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.subject_recording)).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun finalizingIsNotShownAsSaved() {
        compose.setContent { MaterialTheme { SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.RECORDING, recordingFinalizing = true), SubjectDisplaySettings()) } }
        compose.onNodeWithText(context.getString(R.string.subject_finalizing)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.subject_saved)).assertDoesNotExist()
    }

    @Test fun operatorCueAndScriptAreVisibleAtDoubleFontScale() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme {
                    SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING), SubjectDisplaySettings(
                        mode = SubjectDisplayMode.TELEPROMPTER, prompterText = "Hola", operatorCue = "Look at camera", showStatus = false,
                    ))
                }
            }
        }
        compose.onNodeWithText("Look at camera").assertIsDisplayed()
        compose.onNodeWithText("Hola").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun exteriorSearchFindsCanonicalSettings() {
        compose.setContent { MaterialTheme { SettingsScreen(CameraUiState(), CameraSettings(), false, {}, {}, onSettingsChange = {}) } }
        compose.onNodeWithTag("settings-search").performTextInput("pantalla exterior")
        compose.onNodeWithTag("fold-status-card").assertIsDisplayed()
    }
}
