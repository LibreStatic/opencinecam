/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.ui.theme.LocalCineColors

@Composable
internal fun ZeroShutterLagSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val supported = state.descriptor?.zslSupported == true
    val label = stringResource(R.string.zsl_toggle)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionTitle(stringResource(R.string.zsl_title), help = stringResource(R.string.zsl_help))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Switch(checked = settings.zeroShutterLag && supported, enabled = supported,
                onCheckedChange = { onChange(settings.copy(zeroShutterLag = it)) },
                modifier = Modifier.semantics { contentDescription = label }.testTag("zero-shutter-lag"))
        }
        if (!supported) Text(stringResource(R.string.zsl_unavailable), color = LocalCineColors.current.pending,
            modifier = Modifier.testTag("zero-shutter-lag-unavailable"))
    }
}
