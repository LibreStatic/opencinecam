/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.librestatic.opencinecam.camera.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
class MonitoringOverlayUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun tabsSwitchScopesStaleGraphsDisappearAndTheHostOwnsEnlargement() {
        val options = MonitoringOptions(waveformEnabled = true, vectorscopeEnabled = true)
        val frame = analyzeMonitoringRgb(1,1,byteArrayOf(127,127,127), options, MonitoringSignalDomain.SDR_BT709_CODE)
        val fresh = mutableStateOf(true); val requested = mutableStateOf(options); val expanded = mutableStateOf(false); var stops = 0
        compose.setContent { MaterialTheme { Column(Modifier.width(360.dp).height(600.dp)) {
            // The host sizes the panel; a compact tray shows one scope at a time.
            Box(Modifier.size(if (expanded.value) 340.dp else 220.dp, 220.dp)) {
                ProfessionalScopesPanel(CameraUiState(monitoringScopes = frame, analysisIntervalMs = 250), requested.value, fresh.value,
                    expanded = expanded.value, onExpandedChange = { expanded.value = it })
            }
            Button(onClick = { stops++ }, modifier = Modifier.testTag("essential-stop")) { Text("STOP") }
        } } }
        compose.onNodeWithTag("monitoring-waveform-graph").assertIsDisplayed()
        compose.onNodeWithTag("monitoring-vector-graph").assertDoesNotExist()
        compose.onNodeWithTag("monitoring-tab-vectorscope").performClick()
        compose.onNodeWithTag("monitoring-vector-graph").assertIsDisplayed()
        compose.onNodeWithTag("monitoring-waveform-graph").assertDoesNotExist()
        val before = compose.onNodeWithTag("monitoring-panel").getUnclippedBoundsInRoot().width
        compose.onNodeWithTag("monitoring-enlarge").performClick()
        compose.runOnIdle { assertTrue(expanded.value) }
        val after = compose.onNodeWithTag("monitoring-panel").getUnclippedBoundsInRoot().width
        assertTrue(after > before)
        // The selected scope survives the host resizing the panel.
        compose.onNodeWithTag("monitoring-vector-graph").assertIsDisplayed()
        compose.onNodeWithTag("essential-stop").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1,stops); requested.value = options.copy(zebraHighPercent = 89) }
        compose.onNodeWithTag("monitoring-waveform-graph").assertDoesNotExist()
        compose.onNodeWithTag("monitoring-vector-graph").assertDoesNotExist()
        compose.runOnIdle { requested.value = options }
        compose.onNodeWithTag("monitoring-vector-graph").assertExists()
        compose.runOnIdle { fresh.value = false }
        compose.onNodeWithTag("monitoring-waveform-graph").assertDoesNotExist()
        compose.onNodeWithTag("monitoring-vector-graph").assertDoesNotExist()
        compose.onNodeWithTag("monitoring-freshness").assertIsDisplayed()
        compose.onNodeWithTag("monitoring-enlarge").performClick()
        compose.runOnIdle { assertFalse(expanded.value) }
        assertEquals(before, compose.onNodeWithTag("monitoring-panel").getUnclippedBoundsInRoot().width)
    }

    @Test fun aTallPaneStacksEveryEnabledScope() {
        val options = MonitoringOptions(waveformEnabled = true, vectorscopeEnabled = true, falseColorEnabled = true)
        val frame = analyzeMonitoringRgb(1,1,byteArrayOf(127,127,127), options, MonitoringSignalDomain.SDR_BT709_CODE)
        compose.setContent { MaterialTheme { Box(Modifier.size(380.dp, 760.dp)) {
            ProfessionalScopesPanel(CameraUiState(monitoringScopes = frame, analysisIntervalMs = 250), options, fresh = true)
        } } }
        compose.onNodeWithTag("monitoring-waveform-graph").assertIsDisplayed()
        compose.onNodeWithTag("monitoring-vector-graph").assertIsDisplayed()
        compose.onNodeWithTag("monitoring-false-color-legend").assertIsDisplayed()
        compose.onNodeWithTag("monitoring-tab-vectorscope").assertDoesNotExist()
        // Without a host callback there is nothing to enlarge.
        compose.onNodeWithTag("monitoring-enlarge").assertDoesNotExist()
    }
}
