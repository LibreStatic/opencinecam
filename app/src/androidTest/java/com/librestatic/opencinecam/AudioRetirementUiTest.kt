/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Presentation only: service tests own proof of microphone retirement and late-start exclusion. */
class AudioRetirementUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun audioWaitHasAccessibleCancelWithoutImpersonatingTransferOrRecording() {
        var clicks = 0
        val state = mutableStateOf(CameraUiState(phase = CameraUiPhase.CAPTURING,
            selectedMode = CaptureMode.VIDEO, audioRetirementPending = true))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                CompositionLocalProvider(LocalOperatorActions provides OperatorActions({ clicks++ }, {}, { true })) {
                    MaterialTheme { CaptureButton(state.value, null, CameraSettings(), 88.dp) }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.audio_retirement_cancel_capture))
            .assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.runOnIdle { state.value = state.value.copy(audioRetirementPending = false) }
        compose.onNodeWithContentDescription(context.getString(R.string.audio_retirement_cancel_capture)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.start_recording)).assertIsNotEnabled()
    }
}
