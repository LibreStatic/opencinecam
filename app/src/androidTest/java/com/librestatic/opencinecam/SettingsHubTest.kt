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
        // "luz" also matches the tall accumulation section, so the torch result sits below the fold.
        // The results are a lazy list, which composes only what is near the viewport: scroll the
        // list to the result rather than the node, then require it visible and the field still there.
        // Scrolling by the card's key settles the whole card in view; scrolling to the node can stop
        // with the toggle on the list's last pixels, which the test host draws below the screen.
        compose.onNodeWithTag("settings-list").performScrollToKey("torch")
        compose.onNodeWithTag("torch-toggle").assertIsDisplayed()
        compose.onNodeWithTag("settings-search").assertIsDisplayed()
    }

    @Test fun searchRemainsUsableAtOneAndAHalfFontScale() {
        show(fontScale = 1.5f)
        compose.onNodeWithTag("settings-search").performTextInput("peaking")
        // The monitoring scopes section matches "peaking" too and renders above the dedicated one,
        // and both spell the same label, so scroll the lazy list and accept the first match.
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText(label(R.string.monitor_peaking)))
        compose.onAllNodesWithText(label(R.string.monitor_peaking)).onFirst().assertIsDisplayed()
        compose.onNodeWithTag("settings-search").assertIsDisplayed()
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
