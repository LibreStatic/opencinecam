/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.storage.LocalMediaArtifact
import com.librestatic.opencinecam.storage.LocalMediaTake
import com.librestatic.opencinecam.storage.readOcLogClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OCC-PLAN-068 U4 operator command in the media viewer: shows [artifact] on the cover. Present only while a
 * simultaneous presentation is active; disabled while a take prepares or runs. After a successful pick the
 * viewer closes through [onShown], so the inner and cover players never decode the same file together.
 * Opening the item that is already on the cover takes it back from the subject for the same reason.
 */
@Composable
internal fun SubjectReviewShowAction(take: LocalMediaTake, artifact: LocalMediaArtifact, onShown: () -> Unit) {
    val coordinator = LocalFoldDisplayCoordinator.current ?: return
    val review = coordinator.review
    val display by coordinator.states.collectAsState()
    val busy by review.takeBusy.collectAsState()
    LaunchedEffect(artifact.uri) { if (review.states.value.pick?.uri == artifact.uri) review.clear() }
    val presenting = display.phase == DisplaySessionPhase.ACTIVE && display.operation == DisplayOperation.PRESENT
    val reviewable = artifact.mimeType.startsWith("video/") || artifact.mimeType.startsWith("image/")
    if (!presenting || !reviewable) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resolving by remember(artifact.uri) { mutableStateOf(false) }
    CineIconButton("media-playback-show-subject", CineIcon.DISPLAYS, R.string.subject_review_show, enabled = !busy && !resolving) {
        resolving = true
        scope.launch {
            try {
                // OCLog2 is declared only by the sidecar; the cover must never start a LOG clip as SDR.
                val log = withContext(Dispatchers.IO) { runCatching { readOcLogClip(context.contentResolver, take, artifact) }.getOrNull() }
                if (review.show(SubjectReviewPick(artifact.uri, artifact.name, artifact.mimeType, log), coordinator.states.value)) onShown()
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { resolving = false }
        }
    }
}

/**
 * Shows what the cover is reviewing, with the operator's stop command. Without a pick it renders nothing,
 * or only the how-to line when [help] is set (the REVIEW mode was selected by hand).
 */
@Composable
internal fun SubjectReviewOperatorBar(modifier: Modifier = Modifier, help: Boolean = false) {
    val review = LocalFoldDisplayCoordinator.current?.review
    val fallback = remember { MutableStateFlow(SubjectReviewState()) }
    val state by (review?.states ?: fallback).collectAsState()
    val pick = state.pick
    if (review == null || pick == null) {
        if (help) SettingsHelp(stringResource(R.string.subject_review_help))
        return
    }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SettingsSurface).heightIn(min = 48.dp)
        .padding(horizontal = 12.dp).testTag("subject-review-operator-bar"), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.subject_review_on_cover, pick.name), Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { review.clear() }, modifier = Modifier.heightIn(min = 48.dp).testTag("subject-review-stop")) {
            Text(stringResource(R.string.subject_review_stop))
        }
    }
}
