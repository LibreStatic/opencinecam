/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.librestatic.opencinecam.playback.LogPlaybackRenderer
import com.librestatic.opencinecam.storage.LocalMediaArtifact
import com.librestatic.opencinecam.storage.PreciseLogView
import kotlinx.coroutines.flow.MutableStateFlow

/** Provided by FoldDisplayCoordinator inside the subject window only. */
internal val LocalSubjectReviewFeed = staticCompositionLocalOf<SubjectReviewFeed?> { null }

/**
 * OCC-PLAN-068 U4. Plays the operator's pick on the cover. Nothing shows until the operator picks an
 * item: the pick must match [SubjectSessionCues.reviewUri], so the gallery is never exposed here.
 * Output only (touch-locked, no controls) and silent: the session is always muted, so it never takes
 * audio focus. A take that starts releases the player at once, before the controller restores the mode.
 */
@Composable
internal fun SubjectReviewContent(state: CameraUiState, settings: SubjectDisplaySettings, cues: SubjectSessionCues, modifier: Modifier = Modifier) {
    val feed = LocalSubjectReviewFeed.current
    val fallback = remember { MutableStateFlow<SubjectReviewPick?>(null) }
    val pick by (feed?.picks ?: fallback).collectAsState()
    val current = pick?.takeIf { it.uri == cues.reviewUri && !state.reviewBlockedByTake() }
    Box(modifier.background(Color.Black).testTag("subject-review")) {
        if (current == null) Text(stringResource(R.string.subject_review_waiting), color = Color.LightGray, fontSize = 16.sp)
        // A new pick is a new player: the old session and GL stage are released first.
        else key(current.uri) {
            SubjectReviewPlayer(current, if (settings.previewViewAssist) PreciseLogView.REC709 else PreciseLogView.FLAT_LOG,
                onFailure = { feed?.playbackFailed(current.uri) }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Muted, looping playback of one pick through the H4 review session, serial native-surface decoding and LOG stage. */
@Composable
private fun SubjectReviewPlayer(pick: SubjectReviewPick, logView: PreciseLogView, onFailure: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val failure by rememberUpdatedState(onFailure)
    val playback = PlaybackSettings(muted = true, loop = true, showFramePosition = false, logView = logView)
    var observation by remember { mutableStateOf(PlaybackObservation()) }
    var session by remember { mutableStateOf<MediaPlaybackSession?>(null) }
    var renderer by remember { mutableStateOf<LogPlaybackRenderer?>(null) }
    var holderSurface by remember { mutableStateOf<Surface?>(null) }
    var running by remember { mutableStateOf(true) }

    /** OCLog2 decodes into the review GL stage, which draws the selected view to the cover Surface. */
    fun output(holder: Surface?): Surface? {
        val log = pick.log
        if (holder == null || !holder.isValid || log == null) return holder
        val stage = renderer ?: runCatching { LogPlaybackRenderer(holder, log.fullRange, logView) }
            .onFailure { failure() }.getOrNull()?.also { renderer = it }
        return stage?.inputSurface
    }
    fun retireStage() { renderer?.close(); renderer = null }

    DisposableEffect(pick.uri) {
        val artifact = LocalMediaArtifact(pick.uri, pick.name, pick.mimeType, 0, 0)
        // Native-surface frames keep one decoder at a time: the exact reader retires before MediaPlayer connects.
        val next = MediaPlaybackSession(context, artifact, playback, nativeSurfaceFrames = pick.video, log = pick.log?.signal) { observation = it }
        session = next
        next.setSurface(output(holderSurface))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { running = false; next.suspendOutput() }
            if (event == Lifecycle.Event.ON_START) { running = true; next.resumeOutput() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Producers first, then the GL stage's window.
            next.close(); session = null; retireStage()
        }
    }
    LaunchedEffect(session, logView) { session?.update(playback); renderer?.setView(logView) }
    LaunchedEffect(observation.phase) { if (observation.phase == PlaybackPhase.ERROR) failure() }
    // Autoplay: a shown frame with an output plays; looping restarts it, and a re-created Surface resumes it.
    LaunchedEffect(observation.phase, observation.canPlay, running) {
        if (pick.video && running && observation.canPlay && observation.phase == PlaybackPhase.PAUSED) session?.play()
    }

    BoxWithConstraints(modifier.background(Color.Black).testTag("subject-review-player")) {
        if (pick.video) {
            val density = LocalDensity.current
            val scale = playbackFitScale(observation.videoWidth, observation.videoHeight,
                with(density) { maxWidth.roundToPx() }, with(density) { maxHeight.roundToPx() })
            AndroidView(factory = { viewContext ->
                SurfaceView(viewContext).apply {
                    isClickable = false; isFocusable = false
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            holderSurface = holder.surface; session?.setSurface(output(holder.surface))
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            holderSurface = holder.surface; renderer?.resize(width, height); session?.setSurface(output(holder.surface))
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            session?.setSurface(null); retireStage(); holderSurface = null
                        }
                    })
                }
            }, modifier = Modifier.align(Alignment.Center).size(maxWidth * scale.first, maxHeight * scale.second)
                .testTag("subject-review-surface"))
        }
        observation.bitmap?.takeIf { pick.photo }?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize().testTag("subject-review-photo"), contentScale = ContentScale.Fit)
        }
    }
}
