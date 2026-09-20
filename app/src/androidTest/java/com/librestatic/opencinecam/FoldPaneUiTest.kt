/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FoldPaneUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabletopSeparatesControlsAndLoadingMessageWithoutCrossingHinge() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 900.dp))) {
                val hinge = with(LocalDensity.current) { FoldHinge(0, 450.dp.roundToPx(), 800.dp.roundToPx(), 450.dp.roundToPx(), true) }
                MaterialTheme {
                    CaptureSurface(CameraUiState(), null, CameraSettings(), {}, {}, {},
                        fold = FoldDisplayState(posture = FoldPosture.TABLETOP, hinge = hinge))
                }
            }
        }
        val preview = compose.onNodeWithTag("fold-preview-pane").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("fold-controls-pane").fetchSemanticsNode().boundsInRoot
        assertTrue(preview.bottom < controls.top)
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.camera_loading)
        val loading = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(loading.bottom <= preview.bottom)
    }

    @Test fun settingsChooseOneContiguousPaneOnABookFold() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 900.dp))) {
                val hinge = with(LocalDensity.current) { FoldHinge(400.dp.roundToPx(), 0, 400.dp.roundToPx(), 900.dp.roundToPx(), false) }
                HingeSafeSettingsPane(hinge) { Text("Settings", Modifier.testTag("settings-fixture")) }
            }
        }
        val pane = compose.onNodeWithTag("hinge-safe-settings").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag("settings-fixture").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(pane.left > root.center.x)
        assertTrue(text.left >= pane.left && text.right <= pane.right)
    }
}
