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
    @Test fun staleGraphsDisappearAndEnlargementLeavesStopAvailableWithoutChangingSettings() {
        val options = MonitoringOptions(waveformEnabled = true, vectorscopeEnabled = true)
        val frame = analyzeMonitoringRgb(1,1,byteArrayOf(127,127,127), options, MonitoringSignalDomain.SDR_BT709_CODE)
        val fresh = mutableStateOf(true); val requested = mutableStateOf(options); var stops = 0
        compose.setContent { MaterialTheme { Column(Modifier.width(360.dp).height(600.dp)) {
            Box(Modifier.weight(1f)) { ProfessionalScopesPanel(CameraUiState(monitoringScopes = frame, analysisIntervalMs = 250), requested.value, fresh.value) }
            Button(onClick = { stops++ }, modifier = Modifier.testTag("essential-stop")) { Text("STOP") }
        } } }
        compose.onNodeWithTag("monitoring-waveform-graph").performScrollTo().assertIsDisplayed()
        val before = compose.onNodeWithTag("monitoring-panel").getUnclippedBoundsInRoot().width
        compose.onNodeWithTag("monitoring-enlarge").performScrollTo().performClick()
        val after = compose.onNodeWithTag("monitoring-panel").getUnclippedBoundsInRoot().width
        assertTrue(after > before)
        compose.onNodeWithTag("essential-stop").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1,stops); requested.value = options.copy(zebraHighPercent = 89) }
        compose.onNodeWithTag("monitoring-waveform-graph").assertDoesNotExist()
        compose.onNodeWithTag("monitoring-vector-graph").assertDoesNotExist()
        compose.runOnIdle { requested.value = options }
        compose.onNodeWithTag("monitoring-waveform-graph").assertExists()
        compose.runOnIdle { fresh.value = false }
        compose.onNodeWithTag("monitoring-waveform-graph").assertDoesNotExist()
        compose.onNodeWithTag("monitoring-vector-graph").assertDoesNotExist()
        compose.onNodeWithTag("monitoring-freshness").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("monitoring-enlarge").performScrollTo().performClick()
        assertEquals(before, compose.onNodeWithTag("monitoring-panel").getUnclippedBoundsInRoot().width)
    }
}
