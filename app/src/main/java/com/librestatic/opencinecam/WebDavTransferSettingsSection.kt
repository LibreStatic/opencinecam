/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.transfers.*

/** Runtime methods only enqueue work; storage, credentials and network I/O stay off composition. */
@Composable internal fun WebDavTransferSettingsRuntimeSection() {
    val context = LocalContext.current
    val runtime = remember(context) { WebDavTransferRuntime.get(context) }
    val state by runtime.states.collectAsState()
    LaunchedEffect(runtime) { runtime.refresh() }
    WebDavTransferSettingsSection(state, runtime::refresh, runtime::send, runtime::cancel)
}

/** Pure presentation. Opening/refreshing this list never enrolls historical captures or sends one. */
@Composable internal fun WebDavTransferSettingsSection(
    state: WebDavTransferUiState,
    onRefresh: () -> Unit,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val bundles = state.bundles.filter { it.sealed }
    val canRequest = !state.busy && state.message !in setOf(WebDavTransferMessage.WAITING_RECORDING, WebDavTransferMessage.WAITING_MEDIA)
    Column(Modifier.fillMaxWidth().testTag("webdav-transfer-section"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.webdav_transfer_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        SettingsHelp(stringResource(R.string.webdav_transfer_help))
        Text(stringResource(transferMessage(state.message)), color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("webdav-transfer-message").semantics { liveRegion = LiveRegionMode.Polite })
        OutlinedButton(onClick = onRefresh, enabled = canRequest,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("webdav-transfer-refresh")) {
            Text(stringResource(R.string.webdav_transfer_refresh))
        }
        if (state.busy) Button(onClick = onCancel,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("webdav-transfer-cancel")) {
            Text(stringResource(R.string.webdav_transfer_cancel))
        }
        if (bundles.isEmpty()) Text(stringResource(R.string.webdav_transfer_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("webdav-transfer-empty"))
        for (bundle in bundles) key(bundle.id) {
            Column(Modifier.fillMaxWidth().testTag("webdav-transfer-bundle-${bundle.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bundle.artifacts.single { it.spec.role == WebDavArtifactRole.VIDEO }.spec.sourceName,
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                // Durable state is shown per bundle, independently of the last operation message.
                Text(stringResource(bundleStatus(bundle.state)), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("webdav-transfer-status-${bundle.id}"))
                if (state.busy && state.activeBundleId == bundle.id) {
                    Text(stringResource(R.string.webdav_transfer_selected), color = MaterialTheme.colorScheme.onSurface)
                }
                for (artifact in bundle.artifacts) Text(
                    stringResource(R.string.webdav_transfer_artifact_status,
                        stringResource(artifactRole(artifact.spec.role)), stringResource(artifactStatus(artifact.state))),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("webdav-transfer-artifact-${artifact.spec.id}"),
                )
                val eligible = bundle.artifacts.none { it.state == WebDavArtifactState.CONFLICT || it.attempt != null } &&
                    bundle.artifacts.any { it.state in setOf(WebDavArtifactState.QUEUED, WebDavArtifactState.UNCERTAIN, WebDavArtifactState.SOURCE_UNAVAILABLE) }
                if (eligible) Button(onClick = { onSend(bundle.id) }, enabled = canRequest,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("webdav-transfer-send-${bundle.id}")) {
                    Text(stringResource(if (bundle.artifacts.any { it.remoteMayExist })
                        R.string.webdav_transfer_verify else R.string.webdav_transfer_send))
                }
            }
        }
    }
}

private fun transferMessage(message: WebDavTransferMessage): Int = when (message) {
    WebDavTransferMessage.IDLE -> R.string.webdav_transfer_idle
    WebDavTransferMessage.SENDING -> R.string.webdav_transfer_sending
    WebDavTransferMessage.VERIFYING -> R.string.webdav_transfer_verifying
    WebDavTransferMessage.COMPLETE -> R.string.webdav_transfer_complete
    WebDavTransferMessage.WAITING_MEDIA -> R.string.webdav_transfer_waiting_media
    WebDavTransferMessage.WAITING_RECORDING -> R.string.webdav_transfer_waiting_recording
    WebDavTransferMessage.DISABLED -> R.string.webdav_transfer_disabled
    WebDavTransferMessage.NETWORK_UNAVAILABLE -> R.string.webdav_transfer_network_unavailable
    WebDavTransferMessage.CELLULAR_CONSENT_REQUIRED -> R.string.webdav_transfer_cellular_consent_required
    WebDavTransferMessage.AUTHENTICATION -> R.string.webdav_transfer_authentication
    WebDavTransferMessage.CONFLICT -> R.string.webdav_transfer_conflict
    WebDavTransferMessage.SOURCE_UNAVAILABLE -> R.string.webdav_transfer_source_unavailable
    WebDavTransferMessage.UNCERTAIN -> R.string.webdav_transfer_uncertain
    WebDavTransferMessage.ERROR -> R.string.webdav_transfer_error
    WebDavTransferMessage.CANCELLED -> R.string.webdav_transfer_cancelled
}

private fun bundleStatus(state: WebDavBundleState): Int = when (state) {
    WebDavBundleState.AWAITING_PUBLICATION -> R.string.webdav_transfer_pending_publication
    WebDavBundleState.QUEUED -> R.string.webdav_transfer_queued
    WebDavBundleState.ACTIVE -> R.string.webdav_transfer_active
    WebDavBundleState.UNCERTAIN -> R.string.webdav_transfer_uncertain
    WebDavBundleState.ATTENTION -> R.string.webdav_transfer_attention
    WebDavBundleState.COMPLETE -> R.string.webdav_transfer_verified_bundle
}

private fun artifactStatus(state: WebDavArtifactState): Int = when (state) {
    WebDavArtifactState.QUEUED -> R.string.webdav_transfer_queued
    WebDavArtifactState.UPLOADING -> R.string.webdav_transfer_active
    WebDavArtifactState.UNCERTAIN -> R.string.webdav_transfer_uncertain
    WebDavArtifactState.VERIFIED -> R.string.webdav_transfer_verified_artifact
    WebDavArtifactState.CONFLICT -> R.string.webdav_transfer_conflict
    WebDavArtifactState.SOURCE_UNAVAILABLE -> R.string.webdav_transfer_source_unavailable
}

private fun artifactRole(role: WebDavArtifactRole): Int = when (role) {
    WebDavArtifactRole.VIDEO -> R.string.webdav_transfer_video
    WebDavArtifactRole.VIDEO_METADATA -> R.string.webdav_transfer_video_metadata
    WebDavArtifactRole.AUDIO -> R.string.webdav_transfer_audio
    WebDavArtifactRole.AUDIO_METADATA -> R.string.webdav_transfer_audio_metadata
}
