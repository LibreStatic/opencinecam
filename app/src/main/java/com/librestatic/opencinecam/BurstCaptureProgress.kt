/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun BurstCaptureProgress(state: CameraUiState, onCancel: () -> Unit) {
    if (state.selectedMode != CaptureMode.BURST || !state.stillCapturePending) return
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (state.burstSaving) stringResource(R.string.burst_capture_saving)
            else stringResource(R.string.burst_progress, state.burstCaptured, state.burstExpected),
            color = Color.White, modifier = Modifier.testTag("burst-progress"))
        OutlinedButton(onClick = onCancel, enabled = !state.burstSaving,
            modifier = Modifier.heightIn(min = 48.dp).testTag("burst-cancel")) { Text(stringResource(R.string.burst_cancel)) }
    }
}
