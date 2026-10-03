/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Composable
internal fun ProxyCatalogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) { MediaProxyRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val queue = remember(context.applicationContext) { MediaProxyQueue.get(context.applicationContext) }
    var entries by remember { mutableStateOf<List<ProxyCatalogEntry>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var sharing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        loading = true; entries = emptyList(); error = null
        try { entries = repository.catalog() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
        finally { loading = false }
    }
    MediaDialogFrame("proxy-catalog-dialog", { if (!sharing) onDismiss() }) {
                Text(stringResource(R.string.proxy_catalog_title), style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f, fill = false).testTag("proxy-catalog-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(R.string.proxy_catalog_help)) }
                    if (loading) item { Text(stringResource(R.string.proxy_catalog_loading)) }
                    error?.let { message -> item {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.proxy_error), Modifier.fillMaxWidth().testTag("proxy-catalog-error"),
                                color = MaterialTheme.colorScheme.error)
                            MediaDetails("proxy-catalog-error-details") {
                                Text(message, Modifier.fillMaxWidth().testTag("proxy-catalog-error-detail"), fontSize = 12.sp)
                            }
                        }
                    } }
                    if (!loading && error == null && entries.isEmpty()) item { Text(stringResource(R.string.proxy_catalog_empty)) }
                    items(entries, key = { it.takeId }) { entry ->
                        Column(Modifier.fillMaxWidth().testTag("proxy-catalog-entry-${entry.result.proxyId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.result.proxyDisplayName, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.proxy_catalog_original, entry.originalDisplayName))
                            Text("${entry.result.width} × ${entry.result.height} · ${Formatter.formatShortFileSize(context, entry.result.proxyBytes)}",
                                color = SettingsMuted, fontSize = 13.sp)
                            MediaDetails("proxy-catalog-details-${entry.result.proxyId}") {
                                Text(entry.result.proxyUri, Modifier.fillMaxWidth(), fontSize = 12.sp)
                                Text(entry.result.metadataUri, Modifier.fillMaxWidth(), fontSize = 12.sp)
                            }
                            ProxyCatalogEntryActions(entry, !loading && !sharing, onOpen = {
                                sharing = true; error = null
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    try { context.startActivity(repository.prepareOpen(entry)) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
                                    finally { sharing = false }
                                }
                            }, onRename = { stem ->
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    sharing = true; error = null
                                    try {
                                        val renamed = withContext(NonCancellable) { repository.renameProxy(entry, stem) }
                                        entries = entries.map { if (it.takeId == renamed.takeId) renamed else it }
                                    } catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
                                    finally { sharing = false }
                                }
                            }, onDelete = {
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    sharing = true; error = null
                                    try {
                                        withContext(NonCancellable) { queue.deleteProxy(entry) }
                                        entries = entries.filterNot { it.takeId == entry.takeId }
                                        refresh++
                                    } catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
                                    finally { sharing = false }
                                }
                            })
                            OutlinedButton(onClick = {
                                sharing = true; error = null
                                scope.launch {
                                    try { context.startActivity(Intent.createChooser(repository.prepareShare(entry), null)) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
                                    finally { sharing = false }
                                }
                            }, enabled = !loading && !sharing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("proxy-catalog-share-${entry.result.proxyId}")) { Text(stringResource(R.string.proxy_share)) }
                        }
                    }
                }
                OutlinedButton(onClick = { refresh++ }, enabled = !loading && !sharing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("proxy-catalog-refresh")) {
                    Text(stringResource(R.string.proxy_catalog_refresh))
                }
                TextButton(onClick = onDismiss, enabled = !sharing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("proxy-catalog-close")) {
                    Text(stringResource(R.string.proxy_catalog_close))
                }
    }
}
