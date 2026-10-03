/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.librestatic.opencinecam.storage.*
import kotlinx.coroutines.*

@Composable
internal fun MediaProxyDialog(take: LocalMediaTake, settings: ProxySettings,
    onSettings: (ProxySettings) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) { MediaProxyRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var result by remember(take) { mutableStateOf<MediaProxyResult?>(null) }
    var loading by remember(take) { mutableStateOf(true) }
    var actionBusy by remember(take) { mutableStateOf(false) }
    var confirmDelete by remember(take) { mutableStateOf(false) }
    var acknowledged by remember(take) { mutableStateOf(false) }
    var renaming by remember(take) { mutableStateOf(false) }
    var renameStem by remember(take) { mutableStateOf("") }
    var renameTooLong by remember(take) { mutableStateOf(false) }
    var renameFailure by remember(take) { mutableStateOf<String?>(null) }
    val queue = remember(context.applicationContext) { MediaProxyQueue.get(context.applicationContext) }
    val state by queue.states.collectAsState()
    val request = state.jobs.firstOrNull { it.take.id == take.id }
    val busy = request?.status in setOf(ProxyJobStatus.QUEUED, ProxyJobStatus.RUNNING, ProxyJobStatus.CANCELLING)
    var error by remember(take) { mutableStateOf<String?>(null) }
    LaunchedEffect(take, request?.status, state.loaded, actionBusy) {
        if (actionBusy || !state.loaded) return@LaunchedEffect
        loading = true; error = null
        try { result = repository.existing(take) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
        finally { loading = false }
    }
    MediaDialogFrame("media-proxy-dialog", { if (!actionBusy) onDismiss() }) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.proxy_title), style = MaterialTheme.typography.titleLarge)
                    MediaDialogTake(take, "media-proxy-original")
                    Text(stringResource(R.string.proxy_help), Modifier.fillMaxWidth())
                    if (!busy && result == null && request == null) ProxySettingsControls(settings, onSettings)
                    state.waiting[request?.id]?.let { reason ->
                        Text(stringResource(when (reason) {
                            ProxyWaitReason.BATTERY_UNKNOWN -> R.string.proxy_wait_battery_unknown
                            ProxyWaitReason.CHARGING -> R.string.proxy_wait_charging
                            ProxyWaitReason.BATTERY -> R.string.proxy_wait_battery
                            ProxyWaitReason.STORAGE_UNKNOWN -> R.string.proxy_wait_storage_unknown
                            ProxyWaitReason.STORAGE -> R.string.proxy_wait_storage
                            ProxyWaitReason.CAPTURE_ACTIVE -> R.string.proxy_wait_capture
                            ProxyWaitReason.TRANSFER_ACTIVE -> R.string.proxy_wait_transfer
                            ProxyWaitReason.MEDIA_BUSY -> R.string.proxy_wait_media_busy
                        }), Modifier.fillMaxWidth().testTag("media-proxy-waiting"))
                    }
                    request?.let {
                        val label = when (it.status) {
                            ProxyJobStatus.QUEUED -> R.string.proxy_queued
                            ProxyJobStatus.RUNNING -> R.string.proxy_running
                            ProxyJobStatus.CANCELLING -> R.string.proxy_cancelling
                            ProxyJobStatus.SUCCEEDED -> R.string.proxy_succeeded
                            ProxyJobStatus.FAILED -> R.string.proxy_failed
                            ProxyJobStatus.CANCELLED -> R.string.proxy_cancelled
                        }
                        Text(stringResource(label), Modifier.fillMaxWidth().testTag("media-proxy-job-status"))
                        Text("${it.settings.maxLongEdge} px · ${it.settings.videoBitrateMbps} Mbps", Modifier.fillMaxWidth().testTag("media-proxy-job-settings"),
                            color = SettingsMuted, fontSize = 13.sp)
                    }
                    if (loading || busy) Text(stringResource(R.string.proxy_busy), Modifier.fillMaxWidth().testTag("media-proxy-busy"))
                    // Provider and codec messages are for a bug report, not for the set: plain words first.
                    val failure = renameFailure ?: error ?: state.error ?: request?.error
                    if (failure != null) Text(stringResource(R.string.proxy_error), Modifier.fillMaxWidth().testTag("media-proxy-error"),
                        color = MaterialTheme.colorScheme.error)
                    result?.let { proxy ->
                        if (renameFailure == null && error == null) Text(stringResource(R.string.proxy_complete), Modifier.fillMaxWidth().testTag("media-proxy-result"))
                        Text(proxy.proxyDisplayName, Modifier.fillMaxWidth().testTag("media-proxy-name"), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.proxy_sizes, Formatter.formatShortFileSize(context, proxy.originalBytes),
                            Formatter.formatShortFileSize(context, proxy.proxyBytes)), Modifier.fillMaxWidth().testTag("media-proxy-sizes"),
                            color = SettingsMuted, fontSize = 13.sp)
                    }
                    if (request != null || result != null || failure != null) MediaDetails("media-proxy-details") {
                        request?.let { Text(it.id, Modifier.fillMaxWidth().testTag("media-proxy-job-id"), fontSize = 12.sp) }
                        result?.let { proxy ->
                            Text(stringResource(R.string.proxy_details_video, proxy.width, proxy.height, proxy.frames,
                                formatTakeDuration(proxy.durationUs / 1000) ?: "—"), Modifier.fillMaxWidth(), fontSize = 12.sp)
                            Text("SHA-256: ${proxy.proxySha256}", Modifier.fillMaxWidth(), fontSize = 12.sp)
                            Text(proxy.proxyUri, Modifier.fillMaxWidth().testTag("media-proxy-uri"), fontSize = 12.sp)
                            Text(proxy.metadataUri, Modifier.fillMaxWidth().testTag("media-proxy-relation"), fontSize = 12.sp)
                        }
                        failure?.let { Text(it, Modifier.fillMaxWidth().testTag("media-proxy-error-detail"), fontSize = 12.sp) }
                    }
                    result?.let { proxy ->
                        OutlinedButton(onClick = {
                            error = null
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                actionBusy = true
                                try { context.startActivity(Intent.createChooser(repository.prepareShare(take, proxy), null)) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
                                finally { actionBusy = false }
                            }
                        }, enabled = !loading && !busy && !actionBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-proxy-share")) {
                            Text(stringResource(R.string.proxy_share))
                        }
                        if (renaming) {
                            val preview = if (renameTooLong) null else runCatching { proxyFilename(renameStem) }.getOrNull()
                            Text(stringResource(R.string.proxy_rename_help), Modifier.fillMaxWidth())
                            OutlinedTextField(renameStem, { value ->
                                if (value != renameStem || !renameTooLong) {
                                    renameTooLong = value.length > 512
                                    if (!renameTooLong) renameStem = value
                                }
                            }, enabled = !actionBusy, singleLine = true, isError = preview == null,
                                label = { Text(stringResource(R.string.proxy_rename_stem)) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("media-proxy-rename-stem"))
                            if (preview != null) Text(preview, Modifier.fillMaxWidth().testTag("media-proxy-rename-preview"))
                            else Text(stringResource(R.string.media_rename_invalid), Modifier.fillMaxWidth())
                            OutlinedButton(onClick = {
                                val admittedStem = renameStem
                                error = null; renameFailure = null
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    actionBusy = true
                                    try {
                                        result = withContext(NonCancellable) { repository.renameProxy(take, proxy, admittedStem) }
                                        renaming = false
                                    } catch (failure: Exception) {
                                        renameFailure = failure.message ?: failure.javaClass.simpleName
                                        error = renameFailure
                                    }
                                    finally { actionBusy = false }
                                }
                            }, enabled = preview != null && !actionBusy && !busy && !loading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-proxy-rename-confirm")) {
                                Text(stringResource(R.string.proxy_rename_confirm))
                            }
                            OutlinedButton(onClick = { renaming = false }, enabled = !actionBusy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-proxy-rename-cancel")) { Text(stringResource(R.string.proxy_cancel)) }
                        } else OutlinedButton(onClick = {
                            confirmDelete = false; acknowledged = false; renameFailure = null
                            renameStem = proxy.proxyDisplayName.removeSuffix(".mp4"); renameTooLong = false; renaming = true
                        }, enabled = !actionBusy && !busy && !loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-proxy-rename")) { Text(stringResource(R.string.proxy_rename)) }
                        if (confirmDelete) {
                            Text(stringResource(R.string.proxy_delete_help), Modifier.fillMaxWidth())
                            Text(proxy.proxyDisplayName, Modifier.fillMaxWidth().testTag("media-proxy-delete-name"), fontWeight = FontWeight.SemiBold)
                            MediaDetails("media-proxy-delete-details") {
                                Text(proxy.proxyUri, Modifier.fillMaxWidth(), fontSize = 12.sp)
                                Text(proxy.metadataUri, Modifier.fillMaxWidth(), fontSize = 12.sp)
                            }
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                                .toggleable(acknowledged, enabled = !actionBusy, role = Role.Checkbox) { acknowledged = it }
                                .testTag("media-proxy-delete-ack"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(checked = acknowledged, onCheckedChange = null, enabled = !actionBusy)
                                Text(stringResource(R.string.proxy_delete_ack), Modifier.weight(1f))
                            }
                            OutlinedButton(onClick = {
                                error = null
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    actionBusy = true
                                    try {
                                        withContext(NonCancellable) { queue.deleteProxy(take, proxy) }
                                        result = null; confirmDelete = false; acknowledged = false
                                    } catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
                                    finally { actionBusy = false }
                                }
                            }, enabled = acknowledged && !actionBusy && !busy && !loading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-proxy-delete-confirm")) {
                                Text(stringResource(R.string.proxy_delete_confirm))
                            }
                            OutlinedButton(onClick = { confirmDelete = false; acknowledged = false }, enabled = !actionBusy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.proxy_cancel)) }
                        } else OutlinedButton(onClick = { renaming = false; confirmDelete = true; acknowledged = false }, enabled = !actionBusy && !busy && !loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-proxy-delete")) {
                            Text(stringResource(R.string.proxy_delete))
                        }
                        OutlinedButton(onClick = {
                            error = null
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                actionBusy = true
                                try {
                                    val intent = repository.prepareShare(take, proxy).apply {
                                        action = Intent.ACTION_VIEW
                                        setDataAndType(proxy.proxyUri.toUri(), "video/mp4")
                                        removeExtra(Intent.EXTRA_STREAM)
                                    }
                                    context.startActivity(intent)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
                                finally { actionBusy = false }
                            }
                        }, enabled = !actionBusy && !busy && !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-proxy-open")) { Text(stringResource(R.string.proxy_open)) }
                    }
                }
                if (!busy && result == null && request?.status != ProxyJobStatus.SUCCEEDED) OutlinedButton(onClick = {
                    error = null
                    val admittedSettings = settings
                    scope.launch {
                        try { if (request == null) queue.enqueue(take, admittedSettings) else queue.retry(request.id) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
                    }
                }, enabled = !loading && state.loaded && state.error == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-proxy-create")) { Text(stringResource(R.string.proxy_create)) }
                if (busy) OutlinedButton(onClick = {
                    scope.launch {
                        try { request?.let { queue.cancel(it.id) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
                    }
                }, enabled = request?.status != ProxyJobStatus.CANCELLING, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-proxy-cancel")) { Text(stringResource(R.string.proxy_cancel)) }
                OutlinedButton(onClick = onDismiss, enabled = !actionBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-proxy-close")) { Text(stringResource(R.string.proxy_close)) }
    }
}
