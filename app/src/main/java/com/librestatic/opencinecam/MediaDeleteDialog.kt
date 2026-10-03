/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.storage.*
import kotlinx.coroutines.*

/** Synchronous destructive IO, invoked only after explicit confirmation of the displayed members. */
internal fun interface MediaDeleteSource {
    fun delete(selected: LocalMediaTake): MediaDeleteResult
}

@Composable
internal fun MediaDeleteDialog(take: LocalMediaTake, onDismiss: () -> Unit, onCompleted: () -> Unit) {
    val context = LocalContext.current
    val source = remember(context.applicationContext) {
        val deleter = MediaTakeDeleter(context.applicationContext)
        MediaDeleteSource { deleter.delete(it) }
    }
    MediaDeleteDialogContent(take, onDismiss, onCompleted, source)
}

private class DeleteDialogOwner { var valid = true }

@Composable
internal fun MediaDeleteDialogContent(take: LocalMediaTake, onDismiss: () -> Unit,
    onCompleted: () -> Unit, source: MediaDeleteSource) {
    // Never rebind a confirmed operation to a later gallery item or mutable caller list.
    val selected = remember(take) { take.copy(originals = take.originals.toList(), metadata = take.metadata.toList()) }
    val owner = remember(selected, source) { DeleteDialogOwner() }
    val scope = rememberCoroutineScope()
    var acknowledged by remember(owner) { mutableStateOf(false) }
    var busy by remember(owner) { mutableStateOf(false) }
    var finished by remember(owner) { mutableStateOf(false) }
    var result by remember(owner) { mutableStateOf<MediaDeleteResult?>(null) }
    val expected = selected.originals + selected.metadata
    val canDelete = selected.originals.isNotEmpty() && expected.map { it.uri }.distinct().size == expected.size
    DisposableEffect(owner) { onDispose { owner.valid = false } }
    fun dismiss() { if (!busy) { owner.valid = false; onDismiss() } }
    MediaDialogFrame("media-delete-dialog", ::dismiss) {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .testTag("media-delete-scroll"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.media_delete_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge)
                    MediaDialogTake(selected, "media-delete-take")
                    Text(stringResource(R.string.media_delete_help), Modifier.fillMaxWidth().testTag("media-delete-help"))
                    Text(stringResource(R.string.media_delete_sources, selected.originals.size, selected.metadata.size), Modifier.fillMaxWidth())
                    val observed = result
                    val exact = observed != null && observed.files.size == expected.size &&
                        observed.files.map { it.artifact }.toSet() == expected.toSet() &&
                        observed.files.map { it.artifact.uri }.distinct().size == expected.size
                    for ((index, artifact) in expected.withIndex()) {
                        Column(Modifier.fillMaxWidth().testTag("media-delete-member-$index")) {
                            Text(stringResource(if (index < selected.originals.size) R.string.media_delete_original else R.string.media_delete_metadata,
                                artifact.name), Modifier.fillMaxWidth().testTag("media-delete-member-$index-label"))
                            if (finished) {
                                val member = observed?.files?.singleOrNull { it.artifact == artifact }
                                Text(stringResource(when (member?.status) {
                                    MediaDeleteStatus.ABSENT_VERIFIED -> R.string.media_delete_absent
                                    MediaDeleteStatus.RETAINED -> R.string.media_delete_remaining
                                    MediaDeleteStatus.NOT_ATTEMPTED -> R.string.media_delete_not_attempted
                                    MediaDeleteStatus.UNKNOWN, null -> R.string.media_delete_member_unknown
                                }), Modifier.fillMaxWidth().testTag("media-delete-member-$index-status"))
                                member?.detail?.let { detail ->
                                    MediaDetails("media-delete-member-$index-details") {
                                        Text(detail, Modifier.fillMaxWidth().testTag("media-delete-member-$index-detail"), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (!finished) {
                        // One row: the whole line toggles, so the label and the box can never drift apart.
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                            .toggleable(acknowledged, enabled = !busy, role = Role.Checkbox) { acknowledged = it }
                            .testTag("media-delete-acknowledge"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(acknowledged, onCheckedChange = null, enabled = !busy)
                            Text(stringResource(R.string.media_delete_acknowledge), Modifier.weight(1f).testTag("media-delete-acknowledge-label"))
                        }
                    }
                    if (busy) Text(stringResource(R.string.media_delete_busy), Modifier.fillMaxWidth().testTag("media-delete-busy"))
                    if (finished) {
                        Text(stringResource(when {
                            !exact -> R.string.media_delete_unknown
                            observed.complete -> R.string.media_delete_complete
                            observed.partial -> R.string.media_delete_partial
                            observed.files.all { it.status == MediaDeleteStatus.NOT_ATTEMPTED } -> R.string.media_delete_rejected
                            else -> R.string.media_delete_unknown
                        }), Modifier.fillMaxWidth().testTag("media-delete-result"))
                        observed?.error?.let { error ->
                            MediaDetails("media-delete-details") {
                                Text(error, Modifier.fillMaxWidth().testTag("media-delete-error"), fontSize = 12.sp)
                            }
                        }
                        Text(stringResource(R.string.media_delete_refresh), Modifier.fillMaxWidth().testTag("media-delete-refresh"))
                    }
                }
                DeleteButton("confirm", R.string.media_delete_confirm, acknowledged && canDelete && !busy && !finished) {
                    if (owner.valid && acknowledged && canDelete && !busy && !finished) {
                        busy = true
                        // Enter immediately: disposal after confirmation cannot cancel a queued but
                        // accepted destructive operation. No cancellation is advertised during IO.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            val outcome = withContext(NonCancellable + Dispatchers.IO) {
                                // Exceptions inside confirmed IO are an unknown outcome, not success.
                                // No CancellationException outside this work is swallowed.
                                try { source.delete(selected) } catch (_: Exception) { null }
                            }
                            currentCoroutineContext().ensureActive()
                            if (owner.valid) {
                                result = outcome; busy = false; finished = true
                                onCompleted() // Requery; never remove a row optimistically.
                            }
                        }
                    }
                }
                DeleteButton("cancel", R.string.media_delete_cancel, !busy, ::dismiss)
    }
}

@Composable
private fun DeleteButton(tag: String, label: Int, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-delete-$tag")) {
        Text(stringResource(label), Modifier.weight(1f).testTag("media-delete-$tag-label"), textAlign = TextAlign.Center)
    }
}
