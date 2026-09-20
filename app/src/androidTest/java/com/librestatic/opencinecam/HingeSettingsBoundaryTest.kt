/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Synthetic window geometry verifies layout only, not physical fold/display behavior. */
class HingeSettingsBoundaryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowTopPaneStillKeepsSettingsBelowTheHinge() {
        val hostOrigin = mutableStateOf(Offset.Zero)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 400.dp))) {
                val density = LocalDensity.current
                // FoldingFeature bounds are window coordinates, not content/root coordinates.
                // The test activity can have a status-bar inset even under ForcedSize.
                val hinge = with(density) {
                    val x = hostOrigin.value.x.roundToInt()
                    val y = hostOrigin.value.y.roundToInt()
                    FoldHinge(x, y + 50.dp.roundToPx(), x + 360.dp.roundToPx(), y + 60.dp.roundToPx(), true)
                }
                Box(Modifier.fillMaxSize().testTag("settings-host").onGloballyPositioned {
                    hostOrigin.value = it.positionInWindow()
                    Log.i("HingeSettingsBoundary", "hostWindow=${it.positionInWindow()} hostRoot=${it.positionInRoot()} " +
                        "size=${it.size} density=${density.density} hinge=$hinge")
                }) {
                    HingeSafeSettingsPane(hinge) { Text("Settings", Modifier.testTag("settings-content")) }
                }
            }
        }
        val host = compose.onNodeWithTag("settings-host").fetchSemanticsNode().boundsInRoot
        val pane = compose.onNodeWithTag("hinge-safe-settings").fetchSemanticsNode().boundsInRoot
        Log.i("HingeSettingsBoundary", "settledHostWindow=${hostOrigin.value} hostRootBounds=$host paneRootBounds=$pane")
        assertEquals(host.top + host.height * 68f / 400f, pane.top, 1f)
        assertEquals(host.bottom, pane.bottom, 1f)
        compose.onNodeWithTag("settings-content").assertIsDisplayed()
    }

    @Test fun narrowRightPaneUsesTheLeftWithoutOccludingSettings() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 900.dp))) {
                val hinge = with(LocalDensity.current) {
                    FoldHinge(740.dp.roundToPx(), 0, 750.dp.roundToPx(), 900.dp.roundToPx(), false)
                }
                Box(Modifier.fillMaxSize().testTag("settings-host")) {
                    HingeSafeSettingsPane(hinge) { Text("Settings", Modifier.testTag("settings-content")) }
                }
            }
        }
        val host = compose.onNodeWithTag("settings-host").fetchSemanticsNode().boundsInRoot
        val pane = compose.onNodeWithTag("hinge-safe-settings").fetchSemanticsNode().boundsInRoot
        assertEquals(host.left, pane.left, 1f)
        assertEquals(host.left + host.width * 732f / 800f, pane.right, 1f)
        compose.onNodeWithTag("settings-content").assertIsDisplayed()
    }

    @Test fun physicalPaneCoordinatesDoNotMirrorInRtl() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 900.dp))) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val hinge = with(LocalDensity.current) {
                        FoldHinge(400.dp.roundToPx(), 0, 400.dp.roundToPx(), 900.dp.roundToPx(), false)
                    }
                    Box(Modifier.fillMaxSize().testTag("settings-host")) {
                        HingeSafeSettingsPane(hinge) { Text("Settings", Modifier.testTag("settings-content")) }
                    }
                }
            }
        }
        val host = compose.onNodeWithTag("settings-host").fetchSemanticsNode().boundsInRoot
        val pane = compose.onNodeWithTag("hinge-safe-settings").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("settings-content").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(pane.left > host.center.x)
        assertEquals(host.right, pane.right, 1f)
        assertTrue(content.left >= pane.left && content.right <= pane.right)
    }

    @Test fun editingStateSurvivesHingeMovementWindowResizeAndUnfold() {
        val hingePosition = mutableIntStateOf(400)
        val width = mutableStateOf(800.dp)
        val folded = mutableStateOf(true)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.value, 900.dp))) {
                val hinge = if (folded.value) with(LocalDensity.current) {
                    val x = hingePosition.intValue.dp.roundToPx()
                    FoldHinge(x, 0, x, 900.dp.roundToPx(), false)
                } else null
                MaterialTheme {
                    Box(Modifier.fillMaxSize().testTag("settings-host")) {
                        HingeSafeSettingsPane(hinge) {
                            var edits by remember { mutableIntStateOf(0) }
                            Button(onClick = { edits++ }, modifier = Modifier.testTag("settings-edit")) { Text("Edits: $edits") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("settings-edit").performClick()
        compose.runOnIdle { hingePosition.intValue = 740 }
        compose.onNodeWithText("Edits: 1").assertIsDisplayed()
        val host = compose.onNodeWithTag("settings-host").fetchSemanticsNode().boundsInRoot
        val leftPane = compose.onNodeWithTag("hinge-safe-settings").fetchSemanticsNode().boundsInRoot
        assertEquals(host.left, leftPane.left, 1f)
        compose.runOnIdle { width.value = 500.dp; hingePosition.intValue = 300 }
        compose.onNodeWithText("Edits: 1").assertIsDisplayed()
        compose.onNodeWithTag("settings-edit").performClick()
        compose.runOnIdle { folded.value = false }
        compose.onNodeWithText("Edits: 2").assertIsDisplayed()
        val expandedHost = compose.onNodeWithTag("settings-host").fetchSemanticsNode().boundsInRoot
        val expandedPane = compose.onNodeWithTag("hinge-safe-settings").fetchSemanticsNode().boundsInRoot
        assertEquals(expandedHost.left, expandedPane.left, 1f)
        assertEquals(expandedHost.right, expandedPane.right, 1f)
    }
}
