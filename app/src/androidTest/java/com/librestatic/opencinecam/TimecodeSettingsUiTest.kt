/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TimecodeSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(CameraSettings(timecodeEnabled = true, timecodeDropFrame = true))
    private fun show(scale: Float = 1f) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
            MaterialTheme { Column(Modifier.width(320.dp).height(500.dp).verticalScroll(rememberScrollState())) { TimecodeSettings(state.value) { state.value = it } } }
        } }
    }
    private fun edit(index: Int, value: String) { compose.onNodeWithTag("timecode-start-$index").performScrollTo().performTextReplacement(value) }
    @Test fun skippedDropFrameLabelCannotBeSavedButValidLabelCan() {
        show(); edit(0, "0"); edit(1, "1"); edit(2, "0"); edit(3, "0")
        compose.onNodeWithTag("timecode-start-apply").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("timecode-start-error").assertExists()
        compose.runOnIdle { assertEquals(1, state.value.timecodeStartHours); assertEquals(0, state.value.timecodeStartMinutes) }
        edit(3, "2"); compose.onNodeWithTag("timecode-start-apply").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, state.value.timecodeStartHours); assertEquals(1, state.value.timecodeStartMinutes); assertEquals(2, state.value.timecodeStartFrames) }
    }
    @Test fun incompleteDraftDoesNotMutatePersistedSettings() {
        show(); edit(0, "")
        compose.onNodeWithTag("timecode-start-apply").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, state.value.timecodeStartHours) }
    }
    @Test fun localRememberToggleAndConfirmedResetPreserveConfiguredStart() {
        show(); compose.onNodeWithTag("timecode-remember").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(state.value.timecodeRememberPosition) }
        compose.onNodeWithTag("timecode-reset").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, state.value.timecodeResetRevision) }
        compose.onNodeWithTag("timecode-reset-confirm").performClick()
        compose.runOnIdle { assertEquals(1, state.value.timecodeResetRevision); assertEquals(1, state.value.timecodeStartHours) }
    }
    @Test fun fieldsAndSaveRemainReachableAtDoubleFontScale() {
        show(2f)
        for (index in 0..3) { compose.onNodeWithTag("timecode-start-$index").performScrollTo().assertIsDisplayed() }
        edit(0, "2"); compose.onNodeWithTag("timecode-start-apply").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, state.value.timecodeStartHours) }
        compose.onNodeWithTag("timecode-remember").performScrollTo().assertIsDisplayed()
    }
}
