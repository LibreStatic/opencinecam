/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** OCC-PLAN-068 U3. Written in W1; run by the W2 integration on the isolated test emulator. */
class SubjectFillLightUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fill = SubjectDisplaySettings(mode = SubjectDisplayMode.FILL_LIGHT)

    @Test fun fillLightIsFullBleedWithoutOperatorActions() {
        compose.setContent { MaterialTheme { SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING), fill) } }
        compose.onNodeWithTag("subject-fill-light").assertIsDisplayed()
        compose.onNodeWithTag("subject-fill-light-notice").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun thermalAndTimeoutNoticesAreReadableAtDoubleFontScale() {
        val notice = mutableStateOf(FillLightNotice.THERMAL_WARM)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f),
                LocalSubjectFillLightOutput provides FillLightOutput(0.3f, 0.6f, notice.value)) {
                MaterialTheme { SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.RECORDING), fill) }
            }
        }
        for (next in FillLightNotice.entries) {
            notice.value = next
            compose.waitForIdle()
            compose.onNodeWithTag("subject-fill-light-notice").assertIsDisplayed()
            compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        }
    }

    @Test fun operatorSeesTheLockSuggestionOnlyWhenTheLightIsOn() {
        val subject = mutableStateOf(SubjectDisplaySettings())
        compose.setContent { MaterialTheme { SubjectFillLightSettings(CameraUiState(), subject.value) { subject.value = it } } }
        compose.onNodeWithTag("subject-fill-light-lock-suggestion").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.subject_fill_light_enable)).performClick()
        compose.waitForIdle()
        assertEquals(SubjectDisplayMode.FILL_LIGHT, subject.value.mode)
        // Only a suggestion: nothing here can lock AE/AWB.
        compose.onNodeWithTag("subject-fill-light-lock-suggestion").assertIsDisplayed()
        compose.onNodeWithTag("subject-fill-light-swatch").assertExists()
    }

    @Test fun aPresetSetsKelvinAndTintAndAManualChangeDeselectsIt() {
        val subject = mutableStateOf(SubjectDisplaySettings(mode = SubjectDisplayMode.FILL_LIGHT))
        compose.setContent { MaterialTheme { SubjectFillLightSettings(CameraUiState(), subject.value) { subject.value = it } } }
        compose.onNodeWithTag("subject-fill-preset-tungsten").performClick()
        compose.waitForIdle()
        assertEquals(3200, subject.value.fillLightKelvin)
        assertEquals(0, subject.value.fillLightTint)
        compose.onNodeWithTag("subject-fill-preset-tungsten").assertIsSelected()
        compose.onNodeWithTag("subject-fill-preset-fluorescent").performClick()
        compose.waitForIdle()
        assertEquals(FillLightPreset.FLUORESCENT.tint, subject.value.fillLightTint)
        subject.value = subject.value.copy(fillLightTint = 3)
        compose.waitForIdle()
        FillLightPreset.entries.forEach { compose.onNodeWithTag("subject-fill-preset-${it.name.lowercase()}").assertIsNotSelected() }
    }
}
