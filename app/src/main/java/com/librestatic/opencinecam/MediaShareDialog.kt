/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.storage.*
import com.librestatic.opencinecam.ui.theme.LocalCineColors
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

/** Why a share cannot be prepared with the current choices; null when it can. */
internal enum class MediaShareBlocker { NO_ORIGINALS, GROUP_UNCHECKED, NO_SLATE }

internal fun mediaShareBlocker(take: LocalMediaTake, settings: MediaSharingSettings): MediaShareBlocker? = when {
    settings.content == MediaShareContent.ORIGINALS_ONLY -> if (take.originals.isEmpty()) MediaShareBlocker.NO_ORIGINALS else null
    take.relationStatus != LocalMediaRelationStatus.DECLARED -> MediaShareBlocker.GROUP_UNCHECKED
    settings.metadata != MediaShareMetadata.TECHNICAL && take.slate == null -> MediaShareBlocker.NO_SLATE
    else -> null
}

/** The one-tap change that clears [blocker], or null when no choice of what to send can. */
internal fun mediaShareFix(blocker: MediaShareBlocker, settings: MediaSharingSettings): MediaSharingSettings? = when (blocker) {
    MediaShareBlocker.NO_ORIGINALS -> null
    MediaShareBlocker.GROUP_UNCHECKED -> settings.copy(content = MediaShareContent.ORIGINALS_ONLY)
    MediaShareBlocker.NO_SLATE -> settings.copy(metadata = MediaShareMetadata.TECHNICAL)
}

internal fun canPrepareMediaShare(take: LocalMediaTake, settings: MediaSharingSettings): Boolean = mediaShareBlocker(take, settings) == null

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
    val blocker = mediaShareBlocker(take, settings)
    val canPrepare = blocker == null
    DisposableEffect(owner) { onDispose { owner.cancel() } }
    fun cancel() { owner.cancel(); onDismiss() }
    val placement = if (mediaDialogAtSide(LocalAdaptiveWindow.current.widthClass)) MediaDialogPlacement.SIDE else MediaDialogPlacement.CENTER
    MediaDialogFrame("media-share-dialog", ::cancel, placement) {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .testTag("media-share-scroll"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.media_share_action), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge)
                    MediaDialogTake(take, "media-share-take")
                    // Three steps, so a disabled button is never the only sign that something is missing.
                    ShareStep(1, R.string.media_share_step_choose) {
                        MediaSharingSettingsControls(settings, onSettings, enabled = !busy && !launched)
                    }
                    ShareStep(2, R.string.media_share_step_check) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val attention = takeNeedsAttention(take.relationStatus)
                            CineGlyph(if (attention) CineIcon.WARNING else CineIcon.CHECK,
                                if (attention) GalleryWarning else LocalCineColors.current.ok, Modifier.size(16.dp))
                            Text(stringResource(relationLabel(take.relationStatus)), Modifier.weight(1f).testTag("media-share-relation"))
                        }
                        Text(stringResource(R.string.media_share_sources, take.originals.size, take.metadata.size),
                            Modifier.fillMaxWidth().testTag("media-share-sources"), color = SettingsMuted, fontSize = 13.sp)
                        MediaDetails("media-share-files") {
                            take.originals.forEach { Text(it.name, Modifier.fillMaxWidth(), fontSize = 12.sp) }
                            take.metadata.forEach { Text(it.name, Modifier.fillMaxWidth(), fontSize = 12.sp) }
                        }
                        if (blocker != null) {
                            Text(stringResource(when (blocker) {
                                MediaShareBlocker.NO_ORIGINALS -> R.string.media_share_blocked_originals
                                MediaShareBlocker.GROUP_UNCHECKED -> R.string.media_share_blocked_group
                                MediaShareBlocker.NO_SLATE -> R.string.media_share_blocked_slate
                            }), Modifier.fillMaxWidth().testTag("media-share-relation-required"), color = GalleryWarning)
                            mediaShareFix(blocker, settings)?.let { fixed ->
                                OutlinedButton({ onSettings(fixed) }, enabled = !busy && !launched,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-share-fix")) {
                                    Text(stringResource(if (blocker == MediaShareBlocker.GROUP_UNCHECKED) R.string.media_share_fix_originals
                                        else R.string.media_share_fix_technical), Modifier.weight(1f).testTag("media-share-fix-label"),
                                        textAlign = TextAlign.Center)
                                }
                            }
                        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CineGlyph(CineIcon.CHECK, LocalCineColors.current.ok, Modifier.size(16.dp))
                            Text(stringResource(R.string.media_share_ready), Modifier.weight(1f).testTag("media-share-ready"))
                        }
                    }
                    ShareStep(3, R.string.media_share_step_send) {
                        Text(stringResource(R.string.media_share_temporary), Modifier.fillMaxWidth().testTag("media-share-temporary"),
                            color = SettingsMuted, fontSize = 13.sp)
                        if (busy) Text(stringResource(R.string.media_share_preparing), Modifier.fillMaxWidth().testTag("media-share-preparing"))
                        if (failed) Text(stringResource(R.string.media_share_error), Modifier.fillMaxWidth().testTag("media-share-error"),
                            color = MaterialTheme.colorScheme.error)
                        if (cleanupFailed) Text(stringResource(R.string.media_share_cleanup_error), Modifier.fillMaxWidth().testTag("media-share-cleanup-error"))
                        if (launched) {
                            Text(stringResource(R.string.media_share_launched), Modifier.fillMaxWidth().testTag("media-share-launched"))
                            Text(stringResource(R.string.media_share_prepared_files, files.size), Modifier.fillMaxWidth())
                            files.forEach { Text(it.name, Modifier.fillMaxWidth(), fontSize = 12.sp) }
                        }
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

@Composable
private fun ShareStep(number: Int, title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$number · ${stringResource(title)}", Modifier.fillMaxWidth().testTag("media-share-step-$number"), color = SettingsAccent,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}
