/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.librestatic.opencinecam.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class MediaReviewSelection(val takes: List<LocalMediaTake>, val artifact: LocalMediaArtifact,
    val settings: GallerySettings, val query: String, val next: LocalMediaCursor?)

/** The action belongs to the displayed intent, not to a later phase at input dispatch. */
internal fun playbackToggleAction(pauseIntent: Boolean, play: () -> Unit, pause: () -> Unit): () -> Unit =
    if (pauseIntent) pause else play

/** Review snapshots and pages retain URI identities; all catalog mutations remain outside this dialog. */
@Composable
internal fun MediaPlaybackDialog(selection: MediaReviewSelection, settings: PlaybackSettings,
    onSettings: (PlaybackSettings) -> Unit, onDismiss: () -> Unit,
    onPage: suspend (LocalMediaCursor) -> LocalMediaPage) {
    var takes by remember(selection) { mutableStateOf(selection.takes) }
    var takeIndex by remember(selection) { mutableIntStateOf(selection.takes.indexOfFirst { selection.artifact in it.originals }.coerceAtLeast(0)) }
    val take = takes[takeIndex]
    val members = remember(take) { (listOf(take.primary) + take.originals).distinctBy { it.uri } }
    var memberIndex by remember(take.id) { mutableIntStateOf(members.indexOf(selection.artifact).coerceAtLeast(0)) }
    var cursor by remember(selection) { mutableStateOf(selection.next) }
    var pageBusy by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun nextTake() {
        if (takeIndex < takes.lastIndex) { takeIndex++; return }
        if (cursor == null || pageBusy) return
        pageBusy = true; pageError = false
        scope.launch {
            try {
                var next = cursor
                var added = emptyList<LocalMediaTake>()
                while (next != null && added.isEmpty()) {
                    val page = onPage(next)
                    check(page.next == null || page.next !== next)
                    next = page.next
                    added = page.takes.filter { candidate -> takes.none { it.id == candidate.id } }
                    kotlinx.coroutines.yield()
                }
                cursor = next
                if (added.isNotEmpty()) { takes = takes + added; takeIndex++ }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { pageError = true }
            finally { pageBusy = false }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("media-playback-dialog")) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                PlaybackButton("close", R.string.media_playback_close, action = onDismiss)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("media-playback-scroll"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(take.primary.name, Modifier.fillMaxWidth().testTag("media-playback-take"), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.media_playback_member, memberIndex + 1, members.size, members[memberIndex].name),
                        Modifier.fillMaxWidth().testTag("media-playback-member"))
                    key(members[memberIndex].uri) { MediaPlaybackView(members[memberIndex], settings, onSettings) }
                    PlaybackButton("previous-member", R.string.media_playback_previous_member, memberIndex > 0 && !pageBusy) { memberIndex-- }
                    PlaybackButton("next-member", R.string.media_playback_next_member, memberIndex < members.lastIndex && !pageBusy) { memberIndex++ }
                    PlaybackButton("previous-take", R.string.media_playback_previous_take, takeIndex > 0 && !pageBusy) { takeIndex-- }
                    PlaybackButton("next-take", R.string.media_playback_next_take,
                        !pageBusy && (takeIndex < takes.lastIndex || cursor != null), ::nextTake)
                    if (pageBusy) Text(stringResource(R.string.media_playback_loading))
                    if (pageError) Text(stringResource(R.string.media_playback_page_error), Modifier.testTag("media-playback-page-error"))
                }
            }
        }
    }
}

@Composable
private fun MediaPlaybackView(artifact: LocalMediaArtifact, settings: PlaybackSettings, onSettings: (PlaybackSettings) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember(artifact) { mutableStateOf(PlaybackObservation()) }
    var session by remember(artifact) { mutableStateOf<MediaPlaybackSession?>(null) }
    var surface by remember(artifact) { mutableStateOf<Surface?>(null) }
    var surfaceView by remember(artifact) { mutableStateOf<SurfaceView?>(null) }
    var displayHdrTypes by remember(artifact) { mutableStateOf(emptySet<Int>()) }
    var nativeFrames by remember(artifact) { mutableStateOf(false) }
    val retiring = remember { arrayOf<java.util.concurrent.CompletableFuture<Unit>?>(null) }
    fun updateDisplay() {
        val display = surfaceView?.display
        displayHdrTypes = if (display == null || !display.isValid || surface?.isValid != true) emptySet() else if (Build.VERSION.SDK_INT >= 34)
            display.mode.supportedHdrTypes.toSet() else display.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
    }
    DisposableEffect(context, surfaceView) {
        val manager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = updateDisplay()
            override fun onDisplayChanged(id: Int) = updateDisplay()
            override fun onDisplayRemoved(id: Int) = updateDisplay()
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        updateDisplay()
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    var retry by remember { mutableIntStateOf(0) }
    var colorPolicy by remember(artifact) { mutableStateOf(PreciseVideoColorPolicy.STRICT) }
    DisposableEffect(artifact, retry, colorPolicy, nativeFrames, lifecycle) {
        var disposed = false
        var reader: MediaPlaybackSession? = null
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) reader?.suspendOutput()
            if (event == Lifecycle.Event.ON_RESUME) reader?.resumeOutput()
        }
        lifecycle.addObserver(observer)
        fun attach() {
            if (!disposed) {
                val next = MediaPlaybackSession(context, artifact, settings, colorPolicy, nativeFrames) { state = it }
                reader = next; session = next
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) next.suspendOutput()
                next.setSurface(surface)
            }
        }
        val previous = retiring[0]
        if (previous == null || previous.isDone && !previous.isCompletedExceptionally) attach()
        else previous.whenComplete { _, failure -> Handler(Looper.getMainLooper()).post {
            if (!disposed) {
                if (failure == null) attach()
                else state = PlaybackObservation(phase = PlaybackPhase.ERROR, detail = "Previous Surface producer retirement failed")
            }
        } }
        onDispose {
            disposed = true; lifecycle.removeObserver(observer)
            reader?.let { it.close(); retiring[0] = it.retirement }; session = null
        }
    }
    LaunchedEffect(settings, session) { session?.update(settings) }
    val video = artifact.mimeType.startsWith("video/")
    val photo = artifact.mimeType.startsWith("image/")
    if (video || photo) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp)) {
            val density = LocalDensity.current
            val scale = playbackFitScale(state.videoWidth, state.videoHeight,
                with(density) { maxWidth.roundToPx() }, with(density) { maxHeight.roundToPx() })
            // SurfaceView preserves the decoder/compositor HDR path; TextureView can flatten it
            // to SDR. The SurfaceHolder owns its Surface, so never release that Surface ourselves.
            if (video) AndroidView(factory = { ctx ->
                SurfaceView(ctx).apply {
                    tag = "media-playback-native-surface"
                    surfaceView = this
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surface = holder.surface; updateDisplay(); session?.setSurface(holder.surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            surface = holder.surface; updateDisplay(); session?.setSurface(holder.surface)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            session?.setSurface(null); surface = null; displayHdrTypes = emptySet()
                        }
                    })
                }
            }, modifier = Modifier.align(Alignment.Center).size(maxWidth * scale.first, maxHeight * scale.second)
                .testTag("media-playback-surface"))
            state.bitmap?.let {
                // Video bitmap bytes already carry crop/rotation. Fill the fitted display rect
                // so non-square pixels stretch exactly once; photos retain their original fit.
                val frameModifier = if (video) Modifier.align(Alignment.Center).size(maxWidth * scale.first, maxHeight * scale.second)
                    else Modifier.fillMaxSize()
                Image(it.asImageBitmap(), artifact.name, frameModifier.testTag("media-playback-frame"),
                    contentScale = if (video) ContentScale.FillBounds else ContentScale.Fit)
            }
        }
    }
    if (artifact.mimeType.equals("image/x-adobe-dng", ignoreCase = true)) state.bitmap?.let { preview ->
        Text(stringResource(R.string.media_playback_dng_preview, preview.width, preview.height),
            Modifier.fillMaxWidth().testTag("media-playback-dng-preview"))
    }
    Text(stringResource(when (state.phase) {
        PlaybackPhase.LOADING, PlaybackPhase.SEEKING -> R.string.media_playback_loading
        PlaybackPhase.PAUSED -> R.string.media_playback_paused
        PlaybackPhase.PLAYING -> R.string.media_playback_playing
        PlaybackPhase.ENDED -> R.string.media_playback_ended
        PlaybackPhase.ERROR -> R.string.media_playback_error
        PlaybackPhase.CLOSED -> R.string.media_playback_closed
    }), Modifier.fillMaxWidth().testTag("media-playback-status"))
    if (video) {
        PlaybackButton("native-frames", if (nativeFrames) R.string.media_playback_cpu_frames else R.string.media_playback_native_frames) {
            state = PlaybackObservation(); colorPolicy = PreciseVideoColorPolicy.STRICT; nativeFrames = !nativeFrames
        }
        if (nativeFrames) {
            Text(stringResource(R.string.media_playback_native_frame_mode), Modifier.fillMaxWidth().testTag("media-playback-native-frame-mode"))
            state.nativeHdrTransfer?.let { transfer ->
                val type = if (transfer == PreciseHdrTransfer.PQ) Display.HdrCapabilities.HDR_TYPE_HDR10 else Display.HdrCapabilities.HDR_TYPE_HLG
                Text(stringResource(if (type in displayHdrTypes) R.string.media_playback_native_hdr_available else R.string.media_playback_native_hdr_unavailable, transfer.name),
                    Modifier.fillMaxWidth().testTag("media-playback-native-hdr-display"))
            }
        }
    }
    state.hdrPreview?.let { preview ->
        Text(stringResource(R.string.media_playback_hdr_preview, preview.transfer.name),
            Modifier.fillMaxWidth().testTag("media-playback-hdr-preview"))
    }
    if (colorPolicy == PreciseVideoColorPolicy.INTERPRET_TRACK_SDR) {
        Text(stringResource(R.string.media_playback_color_interpreted), Modifier.fillMaxWidth().testTag("media-playback-color-interpretation"))
        state.frameColor?.let { color ->
            Text(stringResource(R.string.media_playback_color_tags, color.standard, color.range, color.reportedStandard, color.reportedRange),
                Modifier.fillMaxWidth().testTag("media-playback-color-tags"))
        }
        PlaybackButton("strict-color", R.string.media_playback_color_strict) { state = PlaybackObservation(); colorPolicy = PreciseVideoColorPolicy.STRICT }
    }
    if (state.phase == PlaybackPhase.ERROR) {
        state.detail?.let { Text(it, Modifier.fillMaxWidth().testTag("media-playback-error-detail")) }
        if (state.trackColorAvailable && colorPolicy == PreciseVideoColorPolicy.STRICT) {
            Text(stringResource(R.string.media_playback_color_choice), Modifier.fillMaxWidth())
            PlaybackButton("interpret-track", R.string.media_playback_interpret_track) {
                state = PlaybackObservation(); colorPolicy = PreciseVideoColorPolicy.INTERPRET_TRACK_SDR
            }
        }
        PlaybackButton("retry", R.string.media_playback_retry) { state = PlaybackObservation(); retry++ }
    }
    if (!photo) {
        val exact = state.frameIndex
        if (settings.showFramePosition && exact != null && state.phase in setOf(PlaybackPhase.PAUSED, PlaybackPhase.ENDED)) {
            Text(stringResource(R.string.media_playback_exact, exact + 1, state.timeline!!.timestampsUs.size, state.positionUs),
                Modifier.fillMaxWidth().testTag("media-playback-exact"))
        } else Text(stringResource(R.string.media_playback_estimated, state.positionUs / 1000),
            Modifier.fillMaxWidth().testTag("media-playback-estimated"))
        val ready = state.phase in setOf(PlaybackPhase.PAUSED, PlaybackPhase.PLAYING, PlaybackPhase.ENDED)
        var seek by remember { mutableFloatStateOf(0f) }
        var dragging by remember { mutableStateOf(false) }
        val maximum = (state.timeline?.timestampsUs?.lastOrNull() ?: state.durationUs).coerceAtLeast(1)
        val seekLabel = stringResource(R.string.media_playback_seek_label)
        Text(seekLabel, Modifier.fillMaxWidth().testTag("media-playback-seek-label"))
        Slider(value = if (dragging) seek else (state.positionUs.toDouble() / maximum).toFloat().coerceIn(0f, 1f),
            onValueChange = { seek = it; dragging = true }, onValueChangeFinished = {
                session?.seek((maximum * seek.toDouble()).toLong()); dragging = false
            }, enabled = ready && (video || state.canPlay), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-playback-seek").semantics { contentDescription = seekLabel })
        val pauseIntent = state.phase == PlaybackPhase.PLAYING
        val toggleSession = session
        PlaybackButton("play-pause", if (pauseIntent) R.string.media_playback_pause else R.string.media_playback_play,
            ready && (pauseIntent || state.canPlay), playbackToggleAction(pauseIntent,
                { toggleSession?.play() }, { toggleSession?.pause() }))
        PlaybackButton("start", R.string.media_playback_start, ready && (video || state.canPlay)) { session?.seek(0) }
        PlaybackButton("end", R.string.media_playback_end, ready && (video || state.canPlay)) { session?.seek(maximum) }
        if (video) {
            val stepping = state.phase in setOf(PlaybackPhase.PAUSED, PlaybackPhase.ENDED) && exact != null
            PlaybackButton("previous-frame", R.string.media_playback_previous_frame, stepping && exact!! > 0) { session?.step(-1) }
            PlaybackButton("next-frame", R.string.media_playback_next_frame,
                stepping && exact!! < state.timeline!!.timestampsUs.lastIndex) { session?.step(1) }
        }
    }
    PlaybackSettingsControls(settings, onSettings)
}

@Composable
private fun PlaybackButton(tag: String, label: Int, enabled: Boolean = true, action: () -> Unit) {
    OutlinedButton(action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-playback-$tag")) {
        Text(stringResource(label), Modifier.weight(1f).testTag("media-playback-$tag-label"))
    }
}

/** Fit fractions for the centered native SurfaceView, using the native player's oriented display dimensions. */
internal fun playbackFitScale(videoWidth: Int, videoHeight: Int, viewWidth: Int, viewHeight: Int): Pair<Float, Float> {
    if (minOf(videoWidth, videoHeight, viewWidth, viewHeight) <= 0) return 1f to 1f
    val scale = minOf(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
    return videoWidth * scale / viewWidth to videoHeight * scale / viewHeight
}
