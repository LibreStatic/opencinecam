/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.storage.*
import kotlinx.coroutines.*

/** IO starts only after the user confirms the complete, purely computed local name preview. */
internal fun interface MediaRenameSource {
    fun rename(selected: LocalMediaTake, stem: String): MediaRenameResult
}

@Composable
internal fun MediaRenameDialog(take: LocalMediaTake, onDismiss: () -> Unit, onCompleted: () -> Unit) {
    val context = LocalContext.current
    val source = remember(context.applicationContext) {
        val renamer = MediaTakeRenamer(context.applicationContext)
        MediaRenameSource { selected, stem -> renamer.rename(selected, stem) }
    }
    MediaRenameDialogContent(take, onDismiss, onCompleted, source)
}

private class RenameDialogOwner { var valid = true }

@Composable
internal fun MediaRenameDialogContent(take: LocalMediaTake, onDismiss: () -> Unit,
    onCompleted: () -> Unit, source: MediaRenameSource) {
    val selected = remember(take) { take.copy(originals = take.originals.toList(), metadata = take.metadata.toList()) }
    val owner = remember(selected, source) { RenameDialogOwner() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var stem by rememberSaveable(selected.id) { mutableStateOf("") }
    var excessiveInput by rememberSaveable(selected.id) { mutableStateOf(false) }
    // An empty field is not a mistake yet: the name is judged once the operator edits or submits it.
    var edited by rememberSaveable(selected.id) { mutableStateOf(false) }
    var busy by remember(owner) { mutableStateOf(false) }
    var finished by remember(owner) { mutableStateOf(false) }
    var result by remember(owner) { mutableStateOf<MediaRenameResult?>(null) }
    val preview = remember(selected, stem, excessiveInput) {
        if (excessiveInput) null else try { mediaRenamePreview(selected, stem) } catch (_: Exception) { null }
    }
    val expected = selected.originals + selected.metadata
    DisposableEffect(owner) { onDispose { owner.valid = false } }
    fun dismiss() { if (!busy) { owner.valid = false; onDismiss() } }
    MediaDialogFrame("media-rename-dialog", ::dismiss) {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .testTag("media-rename-scroll"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.media_rename_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge)
                    MediaDialogTake(selected, "media-rename-take")
                    Text(stringResource(R.string.media_rename_help), Modifier.fillMaxWidth().testTag("media-rename-help"))
                    Text(stringResource(R.string.media_rename_remote_warning), Modifier.fillMaxWidth().testTag("media-rename-remote-warning"))
                    OutlinedTextField(stem, { candidate ->
                        if (!busy && !finished) {
                            // This is a saveable-state memory bound, not a second filename validator.
                            // Never truncate into a different accepted name; backend preview is authoritative.
                            // The String-based field/IME can echo the retained value after a
                            // rejected edit. That is not a new accepted name and must not clear
                            // the rejection or re-enable confirmation of the previous preview.
                            if (candidate != stem) edited = true
                            if (candidate != stem || !excessiveInput) {
                                excessiveInput = candidate.length > 512
                                if (!excessiveInput) stem = candidate
                            }
                        }
                    }, enabled = !busy && !finished, isError = edited && preview == null, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { edited = true; keyboard?.hide() }),
                        label = { Text(stringResource(R.string.media_rename_stem), Modifier.fillMaxWidth().testTag("media-rename-stem-label")) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("media-rename-stem"))
                    if (edited && preview == null) Text(stringResource(R.string.media_rename_invalid), Modifier.fillMaxWidth().testTag("media-rename-invalid"))
                    val observed = result
                    val exact = observed != null && preview != null && observed.files.size == expected.size &&
                        observed.files.map { it.artifact }.toSet() == expected.toSet() &&
                        observed.files.map { it.artifact.uri }.distinct().size == expected.size &&
                        observed.files.all { item -> preview.files.singleOrNull { it.artifact == item.artifact }?.newName == item.requestedName }
                    for ((index, artifact) in expected.withIndex()) {
                        Text(stringResource(R.string.media_rename_old, artifact.name), Modifier.fillMaxWidth().testTag("media-rename-member-$index-old"))
                        preview?.files?.singleOrNull { it.artifact == artifact }?.let {
                            Text(stringResource(R.string.media_rename_new, it.newName), Modifier.fillMaxWidth().testTag("media-rename-member-$index-new"))
                        }
                        if (finished) {
                            val member = observed?.files?.singleOrNull { it.artifact == artifact }
                            Text(member?.finalName?.let { stringResource(R.string.media_rename_current, it) }
                                ?: stringResource(R.string.media_rename_unknown_name), Modifier.fillMaxWidth().testTag("media-rename-member-$index-current"))
                            Text(stringResource(when (member?.status) {
                                MediaRenameStatus.RENAMED -> R.string.media_rename_status_renamed
                                MediaRenameStatus.UNCHANGED -> R.string.media_rename_status_unchanged
                                MediaRenameStatus.RESTORED -> R.string.media_rename_status_restored
                                MediaRenameStatus.PARTIAL -> R.string.media_rename_status_partial
                                MediaRenameStatus.NOT_ATTEMPTED -> R.string.media_rename_status_not_attempted
                                MediaRenameStatus.UNKNOWN, null -> R.string.media_rename_status_unknown
                            }), Modifier.fillMaxWidth().testTag("media-rename-member-$index-status"))
                            member?.detail?.let { detail ->
                                MediaDetails("media-rename-member-$index-details") {
                                    Text(detail, Modifier.fillMaxWidth().testTag("media-rename-member-$index-detail"), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (busy) Text(stringResource(R.string.media_rename_busy), Modifier.fillMaxWidth().testTag("media-rename-busy"))
                    if (finished) {
                        Text(stringResource(when {
                            !exact -> R.string.media_rename_unknown
                            observed.complete && observed.files.all { it.finalName == it.requestedName } -> R.string.media_rename_complete
                            observed.compensationComplete && observed.files.all { it.finalName == it.artifact.name } -> R.string.media_rename_compensated
                            observed.files.all { it.status == MediaRenameStatus.NOT_ATTEMPTED } -> R.string.media_rename_rejected
                            observed.partial -> R.string.media_rename_partial
                            else -> R.string.media_rename_unknown
                        }), Modifier.fillMaxWidth().testTag("media-rename-result"))
                        observed?.error?.let { error ->
                            MediaDetails("media-rename-details") {
                                Text(error, Modifier.fillMaxWidth().testTag("media-rename-error"), fontSize = 12.sp)
                            }
                        }
                        Text(stringResource(R.string.media_rename_refresh), Modifier.fillMaxWidth().testTag("media-rename-refresh"))
                    }
                }
                RenameButton("confirm", R.string.media_rename_confirm, preview != null && !busy && !finished) {
                    val confirmed = preview
                    if (owner.valid && confirmed != null && !busy && !finished) {
                        busy = true; keyboard?.hide()
                        // Confirmed work starts before this click returns and retires even on disposal.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            val outcome = withContext(NonCancellable + Dispatchers.IO) {
                                try { source.rename(selected, confirmed.stem) } catch (_: Exception) { null }
                            }
                            currentCoroutineContext().ensureActive()
                            if (owner.valid) {
                                result = outcome; busy = false; finished = true
                                onCompleted()
                            }
                        }
                    }
                }
                RenameButton("cancel", R.string.media_rename_cancel, !busy, ::dismiss)
    }
}

@Composable
private fun RenameButton(tag: String, label: Int, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-rename-$tag")) {
        Text(stringResource(label), Modifier.weight(1f).testTag("media-rename-$tag-label"), textAlign = TextAlign.Center)
    }
}
