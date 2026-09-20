/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.librestatic.opencinecam.storage.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Synchronous IO boundary. The dialog retains the result even if its main continuation cancels. */
internal fun interface MediaShareSource {
    fun prepare(take: LocalMediaTake, settings: MediaSharingSettings): MediaSharePreparation
}
internal interface MediaSharePreparation {
    val files: List<PreparedMediaShareFile>
    val expiresAtEpochMs: Long
    fun buildIntent(): Intent
    fun markPublished()
    fun discard()
}

@Composable
internal fun MediaShareDialog(take: LocalMediaTake, settings: MediaSharingSettings,
    onSettings: (MediaSharingSettings) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val source = remember(context.applicationContext) {
        val exporter = MediaShareExporter(context.applicationContext)
        MediaShareSource { selected, options ->
            val prepared = exporter.prepare(selected, options)
            object : MediaSharePreparation {
                override val files get() = prepared.files
                override val expiresAtEpochMs get() = prepared.expiresAtEpochMs
                override fun buildIntent() = prepared.buildIntent()
                override fun markPublished() = prepared.markPublished()
                override fun discard() = prepared.discard()
            }
        }
    }
    val chooserTitle = stringResource(R.string.media_share_chooser)
    MediaShareDialogContent(take, settings, onSettings, onDismiss, source) { intent ->
        context.startActivity(Intent.createChooser(intent, chooserTitle)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private class ShareDialogOwner {
    val valid = AtomicBoolean(true)
    var job: Job? = null
    fun cancel() { valid.set(false); job?.cancel() }
}

internal fun canPrepareMediaShare(take: LocalMediaTake, settings: MediaSharingSettings): Boolean =
    if (settings.content == MediaShareContent.ORIGINALS_ONLY) take.originals.isNotEmpty()
    else take.relationStatus == LocalMediaRelationStatus.DECLARED &&
        (settings.metadata == MediaShareMetadata.TECHNICAL || take.slate != null)

@Composable
internal fun MediaShareDialogContent(take: LocalMediaTake, settings: MediaSharingSettings,
    onSettings: (MediaSharingSettings) -> Unit, onDismiss: () -> Unit,
    source: MediaShareSource, onLaunch: (Intent) -> Unit) {
    val owner = remember(take, settings, source) { ShareDialogOwner() }
    val scope = rememberCoroutineScope()
    var busy by remember(owner) { mutableStateOf(false) }
    var failed by remember(owner) { mutableStateOf(false) }
    var cleanupFailed by remember(owner) { mutableStateOf(false) }
    var launched by remember(owner) { mutableStateOf(false) }
    var files by remember(owner) { mutableStateOf<List<PreparedMediaShareFile>>(emptyList()) }
    val canPrepare = canPrepareMediaShare(take, settings)
    DisposableEffect(owner) { onDispose { owner.cancel() } }
    fun cancel() { owner.cancel(); onDismiss() }
    Dialog(onDismissRequest = ::cancel) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 680.dp).testTag("media-share-dialog"), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .testTag("media-share-scroll"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(take.primary.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when (take.relationStatus) {
                        LocalMediaRelationStatus.DECLARED -> R.string.gallery_declared
                        LocalMediaRelationStatus.LEGACY -> R.string.gallery_legacy
                        LocalMediaRelationStatus.MISSING_METADATA -> R.string.gallery_missing
                        LocalMediaRelationStatus.INVALID_METADATA -> R.string.gallery_invalid
                        LocalMediaRelationStatus.INCOMPLETE -> R.string.gallery_incomplete
                    }), Modifier.fillMaxWidth().testTag("media-share-relation"))
                    MediaSharingSettingsControls(settings, onSettings, enabled = !busy && !launched)
                    Text(stringResource(R.string.media_share_temporary), Modifier.fillMaxWidth().testTag("media-share-temporary"))
                    Text(stringResource(R.string.media_share_sources, take.originals.size, take.metadata.size),
                        Modifier.fillMaxWidth().testTag("media-share-sources"))
                    take.originals.forEach { Text(it.name, Modifier.fillMaxWidth()) }
                    take.metadata.forEach { Text(it.name, Modifier.fillMaxWidth()) }
                    if (!canPrepare) Text(stringResource(R.string.media_share_relation_required), Modifier.fillMaxWidth().testTag("media-share-relation-required"))
                    if (busy) Text(stringResource(R.string.media_share_preparing), Modifier.fillMaxWidth().testTag("media-share-preparing"))
                    if (failed) Text(stringResource(R.string.media_share_error), Modifier.fillMaxWidth().testTag("media-share-error"))
                    if (cleanupFailed) Text(stringResource(R.string.media_share_cleanup_error), Modifier.fillMaxWidth().testTag("media-share-cleanup-error"))
                    if (launched) {
                        Text(stringResource(R.string.media_share_launched), Modifier.fillMaxWidth().testTag("media-share-launched"))
                        Text(stringResource(R.string.media_share_prepared_files, files.size), Modifier.fillMaxWidth())
                        files.forEach { Text(it.name, Modifier.fillMaxWidth()) }
                    }
                }
                OutlinedButton(onClick = {
                    if (!busy && !launched && canPrepare && owner.valid.get()) {
                        busy = true; failed = false; cleanupFailed = false
                        owner.job = scope.launch {
                            var prepared: MediaSharePreparation? = null
                            try {
                                // Retain the handle INSIDE IO; prompt cancellation on return must not
                                // lose generated files. The backend checks interruption during bounded IO.
                                runInterruptible(Dispatchers.IO) { prepared = source.prepare(take, settings) }
                                currentCoroutineContext().ensureActive()
                                if (!owner.valid.get()) return@launch
                                val result = requireNotNull(prepared)
                                val intent = result.buildIntent()
                                // Both calls are synchronous and non-IO. No suspension/cancellation point
                                // may occur between marking these URIs handed off and launching the chooser.
                                result.markPublished()
                                onLaunch(intent)
                                files = result.files.toList()
                                launched = true
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { if (owner.valid.get()) failed = true }
                            finally {
                                val failure = withContext(NonCancellable + Dispatchers.IO) {
                                    runCatching { prepared?.discard() }.exceptionOrNull()
                                }
                                if (failure != null) android.util.Log.w("MediaShare", "Temporary export cleanup failed", failure)
                                if (owner.valid.get()) { busy = false; cleanupFailed = failure != null }
                            }
                        }
                    }
                }, enabled = canPrepare && !busy && !launched, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-share-confirm")) {
                    Text(stringResource(R.string.media_share_confirm), Modifier.weight(1f).testTag("media-share-confirm-label"), textAlign = TextAlign.Center)
                }
                OutlinedButton(onClick = ::cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-share-cancel")) {
                    Text(stringResource(R.string.media_share_cancel), Modifier.weight(1f).testTag("media-share-cancel-label"), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
