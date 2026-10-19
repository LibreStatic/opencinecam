/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.librestatic.opencinecam.core.model.PolicyMode

internal const val RECORDING_POLICY_SETTING_ID = "recording-policy"

/** Strict stops a take that leaves the safe range; Adaptive degrades and warns. Not live: it applies to the next take. */
@Composable
internal fun RecordingPolicySettings(settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    SettingsOptionRow(
        stringResource(R.string.recording_policy_title),
        stringResource(if (settings.recordingPolicy == PolicyMode.STRICT) R.string.recording_policy_strict_summary else R.string.recording_policy_adaptive_summary),
        PolicyMode.entries, settings.recordingPolicy,
        label = { stringResource(if (it == PolicyMode.STRICT) R.string.recording_policy_strict else R.string.recording_policy_adaptive) },
        tag = { "recording-policy-$it" }, onSelect = { onChange(settings.copy(recordingPolicy = it)) },
    )
}
