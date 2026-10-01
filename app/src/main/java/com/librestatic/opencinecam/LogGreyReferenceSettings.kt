/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.OpenCineLogGreyReference

/** OCLog2 middle-grey reference between source tiers; see docs/color/opencine-log-v2.md. */
@Composable
internal fun LogGreyReferenceSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val enabled = state.descriptor?.supportsOpenCineLog == true
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.log_grey_reference), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.log_grey_reference_summary), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCineLogGreyReference.entries.forEach { reference ->
                val selected = settings.logGreyReference == reference
                TextButton(
                    onClick = { onChange(settings.copy(logGreyReference = reference)) },
                    enabled = enabled,
                    modifier = Modifier.testTag("log-grey-reference-${reference.name}"),
                ) {
                    Text(
                        stringResource(reference.title),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Text(stringResource(settings.logGreyReference.detail), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

private val OpenCineLogGreyReference.title: Int
    get() = when (this) {
        OpenCineLogGreyReference.NATIVE -> R.string.log_grey_reference_native
        OpenCineLogGreyReference.MATCH_HLG -> R.string.log_grey_reference_match_hlg
        OpenCineLogGreyReference.MATCH_SDR -> R.string.log_grey_reference_match_sdr
    }

private val OpenCineLogGreyReference.detail: Int
    get() = when (this) {
        OpenCineLogGreyReference.NATIVE -> R.string.log_grey_reference_native_detail
        OpenCineLogGreyReference.MATCH_HLG -> R.string.log_grey_reference_match_hlg_detail
        OpenCineLogGreyReference.MATCH_SDR -> R.string.log_grey_reference_match_sdr_detail
    }
