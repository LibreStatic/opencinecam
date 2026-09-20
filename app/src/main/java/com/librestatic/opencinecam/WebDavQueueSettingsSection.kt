/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.net.URI
import com.librestatic.opencinecam.transfers.WebDavLanTls
import com.librestatic.opencinecam.transfers.WebDavQueueSettings
import com.librestatic.opencinecam.transfers.WebDavCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun WebDavQueueSettingsSection(
    settings: WebDavQueueSettings? = null,
    credentials: WebDavCredentialStore? = null,
    showTransfers: Boolean = settings == null,
) {
    val context = LocalContext.current
    val repository = remember(context) { settings ?: WebDavQueueSettings.get(context) }
    val credentialStore = remember(context, credentials) { credentials ?: WebDavCredentialStore(context) }
    val state by repository.states.collectAsState()
    val preferences = state.preferences
    var draft by remember(preferences.activeEndpoint?.url) { mutableStateOf(preferences.activeEndpoint?.url.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(url: String, enabled: Boolean, cellular: Boolean, ignoreTlsErrors: Boolean? = null) {
        if (busy) return
        busy = true; failed = false
        scope.launch {
            try { withContext(Dispatchers.IO) { repository.save(url, enabled, cellular, ignoreTlsErrors) } }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.webdav_queue_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text(stringResource(R.string.webdav_queue_help), color = Color.LightGray)
        OutlinedTextField(draft, { draft = it }, label = { Text(stringResource(R.string.webdav_queue_endpoint)) },
            supportingText = { Text(stringResource(R.string.webdav_queue_https)) }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedLabelColor = Color.White, unfocusedLabelColor = Color.LightGray,
                focusedSupportingTextColor = Color.LightGray, unfocusedSupportingTextColor = Color.LightGray),
            enabled = !busy && !state.storageFailed,
            modifier = Modifier.fillMaxWidth().testTag("webdav-queue-endpoint"))
        Button(onClick = { save(draft, preferences.enabled, preferences.allowCellular) },
            enabled = !busy && !state.storageFailed, modifier = Modifier.heightIn(min = 48.dp).testTag("webdav-queue-save")) {
            Text(stringResource(R.string.webdav_queue_save))
        }
        QueueToggle(stringResource(R.string.webdav_queue_enabled), "webdav-queue-enabled", preferences.enabled,
            !busy && !state.storageFailed && preferences.activeEndpoint != null) {
            save(preferences.activeEndpoint?.url.orEmpty(), it, preferences.allowCellular)
        }
        QueueToggle(stringResource(R.string.webdav_queue_cellular), "webdav-queue-cellular", preferences.allowCellular,
            !busy && !state.storageFailed) {
            save(preferences.activeEndpoint?.url.orEmpty(), preferences.enabled, it)
        }
        QueueToggle(stringResource(R.string.webdav_queue_ignore_tls_label), "webdav-queue-ignore-tls",
            preferences.activeEndpoint?.ignoreTlsErrors == true,
            !busy && !state.storageFailed && preferences.activeEndpoint?.let { WebDavLanTls.isLocalAddress(URI(it.url)) } == true) {
            save(preferences.activeEndpoint?.url.orEmpty(), preferences.enabled, preferences.allowCellular, it)
        }
        Text(stringResource(R.string.webdav_queue_ignore_tls_help), color = Color.LightGray)
        Text(stringResource(R.string.webdav_queue_future_takes), color = Color.LightGray)
        if (failed || state.storageFailed) Text(stringResource(R.string.webdav_queue_settings_failed),
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("webdav-queue-error"))
        if (!state.storageFailed) preferences.activeEndpointId?.let { endpointId ->
            key(endpointId) { WebDavCredentialSettingsSection(endpointId, credentialStore) }
        }
        if (showTransfers) WebDavTransferSettingsRuntimeSection()
    }
}

@Composable private fun QueueToggle(label: String, tag: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), color = if (enabled) Color.White else Color.LightGray)
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
