/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class ProbeLayoutMode {
    PORTRAIT,
    LANDSCAPE,
    WIDE,
    FOLDABLE,
}

fun probeLayoutMode(widthDp: Int, heightDp: Int, separatingFold: Boolean = false): ProbeLayoutMode = when {
    separatingFold -> ProbeLayoutMode.FOLDABLE
    widthDp >= 600 && heightDp >= 600 -> ProbeLayoutMode.WIDE
    widthDp > heightDp -> ProbeLayoutMode.LANDSCAPE
    else -> ProbeLayoutMode.PORTRAIT
}

data class CameraProbeRow(
    val cameraId: String,
    val lensFacing: String,
    val hardwareLevel: String,
    val evidence: String,
)

data class ProbeUiState(
    val status: String,
    val cameras: List<CameraProbeRow>,
    val foldSeparating: Boolean = false,
)

private val emptyProbeState = ProbeUiState(
    status = "No probe has been run.",
    cameras = emptyList(),
)

@Composable
fun ProbeScreen(
    state: ProbeUiState = emptyProbeState,
    onRefresh: () -> Unit = {},
    controls: List<ManualControl> = emptyList(),
    hud: HardwareTruthHudState = HardwareTruthHudState(),
    onControl: (ManualControl) -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val mode = probeLayoutMode(maxWidth.value.toInt(), maxHeight.value.toInt(), state.foldSeparating)
        val contentModifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(modifier = contentModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OpenCineCam", style = MaterialTheme.typography.headlineMedium)
                    Text("Camera and device probe", style = MaterialTheme.typography.titleLarge)
                    Text("Layout: ${mode.name.lowercase()}")
                    Text(state.status, modifier = Modifier.semantics { contentDescription = "Probe status: ${state.status}" })
                    Button(onClick = onRefresh, modifier = Modifier.semantics { contentDescription = "Refresh camera probe" }) {
                        Text("Refresh probe")
                    }
                }
            }
            if (state.cameras.isEmpty()) {
                item {
                    Text("No camera observations yet. Unknown is kept distinct from unsupported.", modifier = contentModifier)
                }
            } else {
                items(state.cameras, key = { it.cameraId }) { camera ->
                    CameraCard(camera, mode, contentModifier)
                }
            }
            if (controls.isNotEmpty()) {
                item { ManualControlsPanel(controls, onControl, contentModifier) }
            }
            item { HardwareTruthHud(hud, contentModifier) }
        }
    }
}

@Composable
fun ManualControlsPanel(
    controls: List<ManualControl>,
    onControl: (ManualControl) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.semantics { contentDescription = "Accessible manual camera controls" }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Manual controls", style = MaterialTheme.typography.titleMedium)
            controls.forEach { control ->
                Button(
                    onClick = { onControl(control) },
                    enabled = control.enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "${control.label}: ${control.requestedValue}" },
                ) {
                    Text(if (control.lockReason == null) "${control.label}: ${control.requestedValue}" else "${control.label}: locked")
                }
                control.lockReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
fun HardwareTruthHud(state: HardwareTruthHudState, modifier: Modifier = Modifier) {
    Card(modifier = modifier.semantics { contentDescription = "Hardware Truth status" }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Hardware Truth", style = MaterialTheme.typography.titleMedium)
            Text("Route: ${state.routeTruth.name.lowercase()}")
            state.warnings.forEach { warning -> Text("Warning: $warning") }
            state.lockedControls.sorted().forEach { key -> Text("Locked: $key") }
            state.discrepancyHistory.takeLast(8).forEach { discrepancy ->
                Text("${discrepancy.key}: requested ${discrepancy.requested}, reported ${discrepancy.reported}")
            }
            if (state.discrepancyHistory.isEmpty() && state.warnings.isEmpty()) {
                Text("No requested/reported discrepancies recorded.")
            }
        }
    }
}

@Composable
private fun CameraCard(camera: CameraProbeRow, mode: ProbeLayoutMode, modifier: Modifier) {
    Card(modifier = modifier.semantics { contentDescription = "Camera ${camera.cameraId} observation" }) {
        val details = @Composable {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Camera ${camera.cameraId}", style = MaterialTheme.typography.titleMedium)
                Text("Lens: ${camera.lensFacing}")
                Text("Hardware: ${camera.hardwareLevel}")
                Text("Evidence: ${camera.evidence}")
            }
        }
        if (mode == ProbeLayoutMode.WIDE || mode == ProbeLayoutMode.FOLDABLE) {
            Row(modifier = Modifier.fillMaxWidth()) { details() }
        } else {
            details()
        }
    }
}
