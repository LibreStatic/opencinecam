/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecordingWhiteBalanceUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun content(state: CameraUiState = CameraUiState(), onChange: (CameraSettings) -> Unit = {}) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(420.dp, 740.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            RecordingWhiteBalanceSettings(state, CameraSettings(), onChange)
                        }
                    }
                }
            }
        }
    }
    @Test fun policyIsConfigurableAtDoubleFontWithoutDiscardingOtherPreferences() {
        var changed: CameraSettings? = null
        content(onChange = { changed = it })
        compose.openChoice("pro-wb-policy-LOCK_ON_RECORD")
        compose.onNodeWithTag("pro-wb-policy-LOCK_ON_RECORD").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(RecordingWhiteBalancePolicy.LOCK_ON_RECORD, changed?.recordingWhiteBalance)
        assertEquals(CameraSettings().whiteBalance, changed?.whiteBalance)
    }
    @Test fun unknownCapabilityAndReportedUnknownAreExplicit() {
        content()
        compose.onNodeWithText(context.getString(R.string.pro_wb_lock_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.pro_wb_lock_reported, context.getString(R.string.pro_unknown))).performScrollTo().assertIsDisplayed()
    }
    @Test fun preparationDisclosesDeferredChangesAndConfirmationWait() {
        content(CameraUiState(phase = CameraUiPhase.CAPTURING, selectedMode = CaptureMode.VIDEO,
            recordingWhiteBalanceStatus = RecordingWhiteBalanceStatus.CONVERGING))
        compose.onNodeWithText(context.getString(R.string.pro_wb_record_pending)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.pro_wb_preparing)).performScrollTo().assertIsDisplayed()
    }
    @Test fun captureButtonOffersCancelWithoutAnotherMicrophonePrompt() {
        compose.setContent {
            MaterialTheme { CaptureButton(CameraUiState(phase = CameraUiPhase.CAPTURING, selectedMode = CaptureMode.VIDEO,
                recordingWhiteBalanceStatus = RecordingWhiteBalanceStatus.CONVERGING), null, CameraSettings(audioEnabled = true), 88.dp) }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.pro_wb_cancel_preparation)).assertIsEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.audio_choice_title)).assertDoesNotExist()
    }

    @Test fun lockWhiteBalanceSearchFindsCanonicalSettings() {
        assertEquals(setOf("professional-exposure"), SettingsCatalog.search("bloqueo blancos", null) { context.getString(it) })
    }
}
