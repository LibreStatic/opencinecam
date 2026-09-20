/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsHubTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchFindsTorchWithoutNavigatingCategories() {
        show()
        compose.onNodeWithTag("settings-search").performTextInput("intensidad")
        compose.onNodeWithTag("torch-toggle").assertIsDisplayed()
        compose.onNodeWithTag("torch-strength").assertDoesNotExist()
    }

    @Test fun searchExplainsEmptyResults() {
        show()
        compose.onNodeWithTag("settings-search").performTextInput("zznonexistentzz")
        compose.onNodeWithText(label(R.string.settings_no_results)).assertIsDisplayed()
    }

    @Test fun categoryAndSearchRemainUsableAtDoubleFontScale() {
        show(fontScale = 2f)
        compose.onNodeWithTag("settings-category-CAPTURE").performScrollTo().performClick()
        compose.onNodeWithTag("settings-search").performTextInput("luz")
        compose.onNodeWithTag("torch-toggle").assertIsDisplayed()
        compose.onNodeWithTag("settings-search").assertIsDisplayed()
    }

    @Test fun searchRemainsUsableAtOneAndAHalfFontScale() {
        show(fontScale = 1.5f)
        compose.onNodeWithTag("settings-search").performTextInput("peaking")
        compose.onNodeWithText(label(R.string.monitor_peaking)).assertIsDisplayed()
    }

    @Test fun recordingDisplaysDeferredChangeExplanation() {
        show(phase = CameraUiPhase.RECORDING)
        compose.onNodeWithText(label(R.string.settings_recording_pending)).assertIsDisplayed()
    }

    @Test fun torchPreferenceCanBeClearedEvenWhenCameraIsAbsent() {
        val settings = mutableStateOf(CameraSettings(flashEnabled = true, torchStrengthLevel = 3))
        compose.setContent {
            MaterialTheme { TorchSettings(CameraUiState(), settings.value) { settings.value = it } }
        }
        compose.onNodeWithTag("torch-toggle").performClick()
        compose.runOnIdle {
            assertFalse(settings.value.flashEnabled)
            assertEquals(3, settings.value.torchStrengthLevel)
        }
    }

    private fun show(fontScale: Float = 1f, phase: CameraUiPhase = CameraUiPhase.READY) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme {
                    SettingsScreen(CameraUiState(phase = phase, settingsPending = phase == CameraUiPhase.RECORDING), CameraSettings(), false, {}, {}, {})
                }
            }
        }
    }

    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
