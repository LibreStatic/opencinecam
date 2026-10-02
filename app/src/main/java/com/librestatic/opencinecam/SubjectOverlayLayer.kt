/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Drawn above every subject mode, including the full-bleed fill light. It is output-only: no
 * pointer input and no capture or settings actions (ADR-0031). The tally border and the giant
 * countdown (OCC-PLAN-068 U2) belong here; the self-monitor guides (U1) draw inside the preview.
 */
@Composable
internal fun SubjectOverlayLayer(
    state: CameraUiState,
    settings: SubjectDisplaySettings,
    cues: SubjectSessionCues,
    modifier: Modifier = Modifier,
) {
    Box(modifier.testTag("subject-overlay")) {
        SubjectOutOfFrameWarning(state, settings, Modifier.align(Alignment.BottomCenter))
    }
}
