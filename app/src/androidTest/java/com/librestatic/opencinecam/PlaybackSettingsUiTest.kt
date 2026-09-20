/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val initial = CameraSettings(audioEnabled = false, audioInputDeviceId = 29,
        productionSlate = ProductionSlateSettings(project = "Preserve"))
    private val settings = mutableStateOf(initial)
    private var changes = 0
    @Test fun allPlaybackControlsUpdateOnlyPlaybackAndOpeningDoesNotChangeAnything() {
        show()
        compose.runOnIdle { assertEquals(0, changes); assertEquals(initial, settings.value) }
        node("muted").performScrollTo().assertIsOff().performClick().assertIsOn()
        node("loop").performScrollTo().assertIsOff().performClick().assertIsOn()
        node("frame-position").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertEquals(initial.copy(playback = PlaybackSettings(true, true, false)), settings.value); assertEquals(3, changes) }
    }
    @Test fun doubleFontFullLabelsAreUnclippedAndTargetsAtLeastFortyEightDp() {
        show(doubleFont = true)
        for (tag in listOf("muted", "loop", "frame-position")) node(tag).performScrollTo().assertHeightIsAtLeast(48.dp)
        for (tag in listOf("help", "muted-label", "loop-label", "frame-position-label")) {
            val layouts = mutableListOf<TextLayoutResult>()
            node(tag).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            assertFalse("Overflow: $tag ${layouts.single().size}", layouts.single().hasVisualOverflow)
        }
    }
    private fun show(doubleFont: Boolean = false) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(if (doubleFont) 2f else 1f)) { MaterialTheme {
                Column(Modifier.width(280.dp).height(480.dp).verticalScroll(rememberScrollState())) {
                    PlaybackSettingsControls(settings.value.playback) { settings.value = settings.value.copy(playback = it); changes++ }
                }
            } }
        }
    }
    private fun node(tag: String) = compose.onNodeWithTag("playback-$tag", useUnmergedTree = true)
}
