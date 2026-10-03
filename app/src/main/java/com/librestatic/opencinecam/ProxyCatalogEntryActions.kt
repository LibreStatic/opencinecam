/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.storage.ProxyCatalogEntry
import com.librestatic.opencinecam.storage.proxyFilename

@Composable
internal fun ProxyCatalogEntryActions(entry: ProxyCatalogEntry, enabled: Boolean,
    onOpen: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var renaming by remember(entry) { mutableStateOf(false) }
    var deleting by remember(entry) { mutableStateOf(false) }
    var acknowledged by remember(entry) { mutableStateOf(false) }
    var stem by remember(entry) { mutableStateOf(entry.result.proxyDisplayName.removeSuffix(".mp4")) }
    var tooLong by remember(entry) { mutableStateOf(false) }
    fun tag(action: String) = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("proxy-catalog-$action-${entry.result.proxyId}")
    OutlinedButton(onOpen, enabled = enabled, modifier = tag("open")) { Text(stringResource(R.string.proxy_open)) }
    if (renaming) {
        Text(stringResource(R.string.proxy_rename_help))
        val preview = if (tooLong) null else runCatching { proxyFilename(stem) }.getOrNull()
        OutlinedTextField(stem, { value ->
            tooLong = value.length > 512
            if (!tooLong) stem = value
        }, enabled = enabled, singleLine = true, isError = preview == null,
            label = { Text(stringResource(R.string.proxy_rename_stem)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("proxy-catalog-rename-stem-${entry.result.proxyId}"))
        if (preview != null) Text(preview, Modifier.testTag("proxy-catalog-rename-preview-${entry.result.proxyId}"))
        else Text(stringResource(R.string.media_rename_invalid))
        OutlinedButton({ onRename(stem) }, enabled = enabled && preview != null, modifier = tag("rename-confirm")) {
            Text(stringResource(R.string.proxy_rename_confirm))
        }
        OutlinedButton({ renaming = false }, enabled = enabled, modifier = tag("rename-cancel")) { Text(stringResource(R.string.proxy_cancel)) }
    } else OutlinedButton({ deleting = false; acknowledged = false; renaming = true }, enabled = enabled, modifier = tag("rename")) {
        Text(stringResource(R.string.proxy_rename))
    }
    if (deleting) {
        Text(stringResource(R.string.proxy_delete_help))
        MediaDetails("proxy-catalog-delete-details-${entry.result.proxyId}") {
            Text(entry.result.proxyUri, Modifier.fillMaxWidth(), fontSize = 12.sp)
            Text(entry.result.metadataUri, Modifier.fillMaxWidth(), fontSize = 12.sp)
        }
        // The whole line toggles, so the box and its sentence read and act as one control.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
            .toggleable(acknowledged, enabled = enabled, role = Role.Checkbox) { acknowledged = it }
            .testTag("proxy-catalog-delete-ack-${entry.result.proxyId}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(acknowledged, onCheckedChange = null, enabled = enabled)
            Text(stringResource(R.string.proxy_delete_ack), Modifier.weight(1f))
        }
        OutlinedButton(onDelete, enabled = enabled && acknowledged, modifier = tag("delete-confirm")) {
            Text(stringResource(R.string.proxy_delete_confirm))
        }
        OutlinedButton({ deleting = false; acknowledged = false }, enabled = enabled, modifier = tag("delete-cancel")) { Text(stringResource(R.string.proxy_cancel)) }
    } else OutlinedButton({ renaming = false; deleting = true; acknowledged = false }, enabled = enabled, modifier = tag("delete")) {
        Text(stringResource(R.string.proxy_delete))
    }
}
