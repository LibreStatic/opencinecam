/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.librestatic.opencinecam.playback.*
import com.librestatic.opencinecam.storage.*
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal data class MediaReviewSelection(val takes: List<LocalMediaTake>, val artifact: LocalMediaArtifact,
    val settings: GallerySettings, val query: String, val next: LocalMediaCursor?)

/** The action belongs to the displayed intent, not to a later phase at input dispatch. */
internal fun playbackToggleAction(pauseIntent: Boolean, play: () -> Unit, pause: () -> Unit): () -> Unit =
    if (pauseIntent) pause else play

/** "HH:MM:SS:FF" on the nominal (rounded) frame rate; without one, whole seconds of [positionUs] with FF 00. */
internal fun playbackTimecode(positionUs: Long, frame: Long?, fps: Double?): String {
    val base = fps?.takeIf { it.isFinite() && it > 0 }?.roundToInt()?.coerceAtLeast(1)
    val (seconds, ff) = if (base != null && frame != null) frame.coerceAtLeast(0) / base to frame.coerceAtLeast(0) % base
        else positionUs.coerceAtLeast(0) / 1_000_000 to 0L
    return String.format(Locale.ROOT, "%02d:%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60, ff)
}

private const val FILMSTRIP_FRAMES = 10

/** Below this the fixed player would squeeze the stage; it keeps this height and the area under the top bar scrolls instead. */
private val PLAYER_MIN_HEIGHT = 470.dp

/** Dialog-owned pieces the player places: navigation above the stage, take facts in the details below it. */
private class PlaybackChrome(val topBar: @Composable () -> Unit, val members: @Composable () -> Unit,
    val info: @Composable () -> Unit)

/**
 * Review snapshots and pages retain URI identities; catalog mutations stay outside this dialog. Share and
 * delete are delegated to the catalog, which reports finished deletions back through [deleted] take ids.
 */
@Composable
internal fun MediaPlaybackDialog(selection: MediaReviewSelection, settings: PlaybackSettings,
    onSettings: (PlaybackSettings) -> Unit, onDismiss: () -> Unit,
    onPage: suspend (LocalMediaCursor) -> LocalMediaPage, onShare: ((LocalMediaTake) -> Unit)? = null,
    onDelete: ((LocalMediaTake) -> Unit)? = null, deleted: Set<String> = emptySet()) {
    var takes by remember(selection) { mutableStateOf(selection.takes) }
    var takeIndex by remember(selection) { mutableIntStateOf(selection.takes.indexOfFirst { selection.artifact in it.originals }.coerceAtLeast(0)) }
    val take = takes[takeIndex]
    val members = remember(take) { (listOf(take.primary) + take.originals).distinctBy { it.uri } }
    var memberIndex by remember(take.id) { mutableIntStateOf(members.indexOf(selection.artifact).coerceAtLeast(0)) }
    var cursor by remember(selection) { mutableStateOf(selection.next) }
    var pageBusy by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
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
    val currentDismiss by rememberUpdatedState(onDismiss)
    // A deleted take leaves the review: show the next one (the previous when it was last), or close.
    LaunchedEffect(deleted) {
        if (takes.none { it.id in deleted }) return@LaunchedEffect
        val remaining = takes.filter { it.id !in deleted }
        if (remaining.isEmpty()) { currentDismiss(); return@LaunchedEffect }
        val current = takes[takeIndex]
        val index = if (current.id in deleted) takes.take(takeIndex).count { it.id !in deleted }.coerceAtMost(remaining.lastIndex)
            else remaining.indexOfFirst { it.id == current.id }
        takes = remaining; takeIndex = index
    }
    // Edge to edge: with the platform fitting the decor, the window starts below the status bar but the
    // content is still measured for the full display height, which pushed the bottom controls off screen.
    Dialog(onDismissRequest = { if (fullscreen) fullscreen = false else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val title = stringResource(R.string.playback_screen_title)
        Surface(Modifier.fillMaxSize().semantics { paneTitle = title }.testTag("media-playback-dialog"),
            color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val chrome = PlaybackChrome(
                    topBar = {
                        PlaybackTopBar(take, stringResource(if (cursor != null) R.string.playback_screen_take_position_more
                            else R.string.playback_screen_take_position, takeIndex + 1, takes.size),
                            previousTake = takeIndex > 0 && !pageBusy, nextTake = !pageBusy && (takeIndex < takes.lastIndex || cursor != null),
                            pageBusy = pageBusy, pageError = pageError, onPreviousTake = { takeIndex-- }, onNextTake = ::nextTake,
                            onClose = onDismiss, onShare = onShare, onDelete = onDelete) {
                            SubjectReviewShowAction(take, members[memberIndex], onDismiss)
                        }
                    },
                    members = {
                        if (members.size > 1) MemberSwitcher(members, memberIndex, pageBusy, { memberIndex-- }, { memberIndex++ })
                    },
                    info = { PlaybackInfo(take, members, memberIndex) })
                key(members[memberIndex].uri) {
                    MediaPlaybackView(take, members[memberIndex], settings, onSettings, fullscreen, { fullscreen = it },
                        chrome, settingsOpen) { settingsOpen = it }
                }
            }
        }
    }
}

@Composable
private fun PlaybackTopBar(take: LocalMediaTake, position: String, previousTake: Boolean, nextTake: Boolean, pageBusy: Boolean,
    pageError: Boolean, onPreviousTake: () -> Unit, onNextTake: () -> Unit, onClose: () -> Unit,
    onShare: ((LocalMediaTake) -> Unit)?, onDelete: ((LocalMediaTake) -> Unit)?, subjectAction: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CineIconButton("media-playback-close", CineIcon.BACK, R.string.media_playback_close, onClick = onClose)
        CineIconButton("media-playback-previous-take", CineIcon.CHEVRON_LEFT, R.string.media_playback_previous_take,
            enabled = previousTake, onClick = onPreviousTake)
        Text(position, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        CineIconButton("media-playback-next-take", CineIcon.CHEVRON_RIGHT, R.string.media_playback_next_take,
            enabled = nextTake, onClick = onNextTake)
        Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            if (pageBusy) Text(stringResource(R.string.media_playback_loading), color = SettingsMuted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        subjectAction()
        onShare?.let { share -> CineIconButton("media-playback-share", CineIcon.SHARE, R.string.playback_screen_share) { share(take) } }
        onDelete?.let { delete -> CineIconButton("media-playback-delete", CineIcon.DELETE, R.string.playback_screen_delete) { delete(take) } }
    }
    if (pageError) Text(stringResource(R.string.media_playback_page_error), Modifier.padding(horizontal = 12.dp)
        .testTag("media-playback-page-error"), color = LocalCineColors.current.pending, fontSize = 13.sp)
}

/** Only multi-member takes get the switcher on the player; a single member is named in the details. */
@Composable
private fun MemberSwitcher(members: List<LocalMediaArtifact>, memberIndex: Int, pageBusy: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(SettingsSurface),
        verticalAlignment = Alignment.CenterVertically) {
        CineIconButton("media-playback-previous-member", CineIcon.CHEVRON_LEFT, R.string.media_playback_previous_member,
            enabled = memberIndex > 0 && !pageBusy, onClick = onPrevious)
        Text(stringResource(R.string.media_playback_member, memberIndex + 1, members.size, members[memberIndex].name),
            Modifier.weight(1f).testTag("media-playback-member"), color = SettingsMuted, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        CineIconButton("media-playback-next-member", CineIcon.CHEVRON_RIGHT, R.string.media_playback_next_member,
            enabled = memberIndex < members.lastIndex && !pageBusy, onClick = onNext)
    }
}

@Composable
private fun PlaybackInfo(take: LocalMediaTake, members: List<LocalMediaArtifact>, memberIndex: Int) {
    val (title, subtitle) = rememberClipTitle(take)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, color = SettingsMuted, fontSize = 13.sp) }
        Text(take.primary.name, Modifier.fillMaxWidth().testTag("media-playback-take"), color = SettingsMuted, fontSize = 11.sp)
        if (members.size == 1) Text(stringResource(R.string.media_playback_member, memberIndex + 1, members.size, members[memberIndex].name),
            Modifier.fillMaxWidth().testTag("media-playback-member"), color = SettingsMuted, fontSize = 11.sp)
    }
}

/**
 * A fixed player (navigation, stage, position, seek, transport, view toggles) fills the viewport and never
 * scrolls; the details (clip info, decoding notes, playback settings) sit below it, reached by Info/Settings.
 */
@Composable
private fun MediaPlaybackView(take: LocalMediaTake, artifact: LocalMediaArtifact, settings: PlaybackSettings,
    onSettings: (PlaybackSettings) -> Unit, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit,
    chrome: PlaybackChrome, settingsOpen: Boolean, onSettingsOpen: (Boolean) -> Unit) {
    val context = LocalContext.current
    val video = artifact.mimeType.startsWith("video/")
    val photo = artifact.mimeType.startsWith("image/")
    // OCLog2 is declared only by the sidecar; the session waits for that answer so it never starts as SDR.
    var logResolved by remember(artifact) { mutableStateOf(!video) }
    var logClip by remember(artifact) { mutableStateOf<OcLogClip?>(null) }
    LaunchedEffect(take, artifact) {
        if (video) {
            logClip = withContext(Dispatchers.IO) { runCatching { readOcLogClip(context.contentResolver, take, artifact) }.getOrNull() }
            logResolved = true
        }
    }
    val currentSettings by rememberUpdatedState(settings)
    var renderer by remember(artifact) { mutableStateOf<LogPlaybackRenderer?>(null) }
    var stageError by remember(artifact) { mutableStateOf<String?>(null) }
    /** OCLog2 clips decode into the review GL stage, which draws the selected view to the holder Surface. */
    fun output(holder: Surface?): Surface? {
        val clip = logClip
        if (holder == null || !holder.isValid || clip == null) return holder
        val stage = renderer ?: runCatching { LogPlaybackRenderer(holder, clip.fullRange, currentSettings.logView) }
            .onFailure { stageError = it.message ?: it.javaClass.simpleName }.getOrNull()?.also { renderer = it }
        return stage?.inputSurface ?: holder
    }
    fun retireStage() { renderer?.close(); renderer = null }
    DisposableEffect(artifact) { onDispose { retireStage() } }
    LaunchedEffect(renderer, settings.logView) { renderer?.setView(settings.logView) }
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
    DisposableEffect(artifact, retry, colorPolicy, nativeFrames, lifecycle, logResolved) {
        if (!logResolved) return@DisposableEffect onDispose { }
        var disposed = false
        var reader: MediaPlaybackSession? = null
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) reader?.suspendOutput()
            if (event == Lifecycle.Event.ON_RESUME) reader?.resumeOutput()
        }
        lifecycle.addObserver(observer)
        fun attach() {
            if (!disposed) {
                val next = MediaPlaybackSession(context, artifact, settings, colorPolicy, nativeFrames, logClip?.signal) { state = it }
                reader = next; session = next
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) next.suspendOutput()
                next.setSurface(output(surface))
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

    var metadata by remember(artifact) { mutableStateOf<ClipMetadata?>(null) }
    LaunchedEffect(artifact, logResolved) {
        if (video && logResolved) metadata = withContext(Dispatchers.IO) { probeClipMetadata(context, artifact.uri, logClip) }
    }
    val ready = state.phase in setOf(PlaybackPhase.PAUSED, PlaybackPhase.PLAYING, PlaybackPhase.ENDED)
    val maximum = (state.timeline?.timestampsUs?.lastOrNull() ?: state.durationUs).coerceAtLeast(1)
    val durationUs = metadata?.durationUs ?: state.durationUs
    // Thumbnails wait for the reader's first answer so they never compete with opening the clip.
    var started by remember(artifact) { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready) started = true }
    var filmstrip by remember(artifact) { mutableStateOf(emptyList<Bitmap>()) }
    val stripHeight = with(LocalDensity.current) { SEEK_HEIGHT.roundToPx() }
    LaunchedEffect(artifact, started, logClip, settings.logView, durationUs > 0) {
        // A strip rendered in the other LOG view would misrepresent the clip while the new one decodes.
        if (logClip != null) filmstrip = emptyList()
        if (video && started && durationUs > 0) filmstrip = runInterruptible(Dispatchers.IO) {
            loadFilmstrip(context, artifact.uri, durationUs, FILMSTRIP_FRAMES, stripHeight, logClip, settings.logView)
        }
    }
    var histogram by remember(artifact) { mutableStateOf<FloatArray?>(null) }
    val histogramSource = state.bitmap ?: filmstrip.takeIf { it.isNotEmpty() }?.let {
        it[(state.positionUs.toDouble() / maximum * it.size).toInt().coerceIn(0, it.lastIndex)]
    }
    LaunchedEffect(histogramSource, logClip) {
        val source = histogramSource
        if (logClip != null && source != null) histogram = withContext(Dispatchers.Default) { runCatching { bitmapLumaHistogram(source) }.getOrNull() }
    }
    if (fullscreen && !(video || photo)) LaunchedEffect(Unit) { onFullscreen(false) }
    LaunchedEffect(state.phase == PlaybackPhase.ERROR) { if (state.phase == PlaybackPhase.ERROR) onFullscreen(false) }

    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val infoRequester = remember { BringIntoViewRequester() }
    val settingsRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(fullscreen) { if (fullscreen) scroll.scrollTo(0) }
    // Back from the details returns to the player before it closes the review.
    BackHandler(enabled = !fullscreen && scroll.value > 0) { scope.launch { scroll.animateScrollTo(0) } }
    val onNativeFrames = { state = PlaybackObservation(); colorPolicy = PreciseVideoColorPolicy.STRICT; nativeFrames = !nativeFrames }
    val timestamps = state.timeline?.timestampsUs
    // The top bar stays put so close and take navigation are reachable from the details too.
    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) chrome.topBar()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = !fullscreen).testTag("media-playback-scroll"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.fillMaxWidth().height(if (fullscreen) viewport else viewport.coerceAtLeast(PLAYER_MIN_HEIGHT)),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!fullscreen) {
                        ClipLine(take, metadata, logClip != null)
                        chrome.members()
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(top = if (fullscreen) 0.dp else 4.dp)
                        .background(if (video || photo) Color.Black else SettingsSurface)) {
                        if (video || photo) PlaybackStage(artifact, video, state, onSurfaceView = { surfaceView = it },
                            onSurfaceCreated = { holder -> surface = holder.surface; updateDisplay(); session?.setSurface(output(holder.surface)) },
                            onSurfaceChanged = { holder, width, height ->
                                surface = holder.surface; updateDisplay(); renderer?.resize(width, height)
                                session?.setSurface(output(holder.surface))
                            },
                            onSurfaceDestroyed = {
                                // Producers first, then the GL stage's window, before the holder destroys its Surface.
                                session?.setSurface(null); retireStage(); surface = null; displayHdrTypes = emptySet()
                            })
                        else Text(artifact.name, Modifier.align(Alignment.Center).padding(16.dp), color = SettingsMuted, fontSize = 13.sp,
                            textAlign = TextAlign.Center)
                        // A failure takes the stage itself, where the missing picture is, rather than a card below the fold.
                        if (state.phase == PlaybackPhase.ERROR) Box(Modifier.matchParentSize()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f)).verticalScroll(rememberScrollState())
                            .padding(12.dp), contentAlignment = Alignment.Center) {
                            PlaybackErrorCard(state, video, nativeFrames, colorPolicy, onRetry = { state = PlaybackObservation(); retry++ },
                                onNativeFrames = onNativeFrames,
                                onInterpret = { state = PlaybackObservation(); colorPolicy = PreciseVideoColorPolicy.INTERPRET_TRACK_SDR })
                        }
                    }
                    Column((if (fullscreen) Modifier else Modifier.widthIn(max = 840.dp)).fillMaxWidth().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val fps = metadata?.frameRate ?: timestamps?.let { frameRateFromSampleTimes(it.take(120)) }
                        val totalFrames = timestamps?.size?.toLong() ?: fps?.let { (durationUs * it / 1_000_000).roundToLong() }?.takeIf { it > 0 }
                        val frame = state.frameIndex?.toLong() ?: fps?.let { (state.positionUs * it / 1_000_000).toLong() }
                        TimecodeRow(if (photo) null else playbackTimecode(state.positionUs, frame, fps),
                            if (photo) null else playbackTimecode(durationUs, totalFrames, fps),
                            if (video && frame != null && totalFrames != null)
                                stringResource(R.string.playback_screen_frame_counter, (frame + 1).coerceAtMost(totalFrames), totalFrames) else null,
                            fullscreen, video || photo, onFullscreen)
                        StatusLine(state, photo, settings)
                        if (!photo) {
                            val exact = state.frameIndex
                            val seekLabel = stringResource(R.string.media_playback_seek_label)
                            var seek by remember { mutableFloatStateOf(0f) }
                            var dragging by remember { mutableStateOf(false) }
                            val strip = remember(filmstrip) { filmstrip.map { it.asImageBitmap() } }
                            val seekable = ready && (video || state.canPlay)
                            FilmstripSeek(if (dragging) seek else (state.positionUs.toDouble() / maximum).toFloat().coerceIn(0f, 1f),
                                strip, seekable, seekLabel, onChange = { seek = it; dragging = true }, onFinished = {
                                    session?.seek((maximum * seek.toDouble()).toLong()); dragging = false
                                }, modifier = Modifier.padding(top = 6.dp).testTag("media-playback-seek"))
                            val pauseIntent = state.phase == PlaybackPhase.PLAYING
                            val toggleSession = session
                            val stepping = state.phase in setOf(PlaybackPhase.PAUSED, PlaybackPhase.ENDED) && exact != null
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically) {
                                TransportButton("media-playback-start", CineIcon.SKIP_START, stringResource(R.string.media_playback_start), seekable) { session?.seek(0) }
                                if (video) TransportButton("media-playback-previous-frame", CineIcon.FRAME_BACK, stringResource(R.string.media_playback_previous_frame),
                                    stepping && exact!! > 0) { session?.step(-1) }
                                TransportButton("media-playback-play-pause", if (pauseIntent) CineIcon.PAUSE else CineIcon.PLAY,
                                    stringResource(if (pauseIntent) R.string.media_playback_pause else R.string.media_playback_play),
                                    ready && (pauseIntent || state.canPlay), primary = true,
                                    onClick = playbackToggleAction(pauseIntent, { toggleSession?.play() }, { toggleSession?.pause() }))
                                if (video) TransportButton("media-playback-next-frame", CineIcon.FRAME_FORWARD, stringResource(R.string.media_playback_next_frame),
                                    stepping && exact!! < state.timeline!!.timestampsUs.lastIndex) { session?.step(1) }
                                TransportButton("media-playback-end", CineIcon.SKIP_END, stringResource(R.string.media_playback_end), seekable) { session?.seek(maximum) }
                            }
                        }
                        if (!fullscreen) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (logClip != null) {
                                val monitoring = stringResource(R.string.playback_screen_monitoring)
                                SegmentedToggle(listOf(PreciseLogView.FLAT_LOG to stringResource(R.string.playback_screen_log),
                                    PreciseLogView.REC709 to stringResource(R.string.playback_screen_rec709)), settings.logView,
                                    { onSettings(settings.copy(logView = it)) }, "media-playback-log-view",
                                    Modifier.semantics { contentDescription = monitoring })
                                val label = stringResource(R.string.playback_screen_histogram)
                                HistogramThumb(histogram, Modifier.size(72.dp, 40.dp).semantics { contentDescription = label })
                            }
                            Spacer(Modifier.weight(1f))
                            if (stageError != null) CineIconButton("media-playback-stage-warning", CineIcon.WARNING, R.string.playback_screen_stage_error_title,
                                tint = LocalCineColors.current.pending) { scope.launch { infoRequester.bringIntoView() } }
                            CineIconButton("media-playback-info-jump", CineIcon.INFO, R.string.playback_screen_info) {
                                scope.launch { infoRequester.bringIntoView() }
                            }
                            CineIconButton("media-playback-settings-jump", CineIcon.SETTINGS, R.string.playback_screen_settings) {
                                onSettingsOpen(true)
                                // Two frames: the opened section has to be composed and measured before it can be brought into view.
                                scope.launch { withFrameNanos { }; withFrameNanos { }; settingsRequester.bringIntoView() }
                            }
                        }
                    }
                }
                if (!fullscreen) Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsCard(Modifier.bringIntoViewRequester(infoRequester)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            chrome.info()
                            metadata?.let { ClipChips(it, logClip != null) }
                            stageError?.let {
                                CollapsibleErrorCard(stringResource(R.string.playback_screen_stage_error_title),
                                    stringResource(R.string.playback_screen_stage_error_message), it, "media-playback-log-stage-error")
                            }
                            SignalNotes(artifact, video, state, nativeFrames, displayHdrTypes, colorPolicy, onNativeFrames = onNativeFrames,
                                onStrict = { state = PlaybackObservation(); colorPolicy = PreciseVideoColorPolicy.STRICT })
                        }
                    }
                    PlaybackSettingsSection(settingsOpen, onSettingsOpen, settings, onSettings, Modifier.bringIntoViewRequester(settingsRequester))
                }
            }
        }
    }
}

private val SEEK_HEIGHT = 40.dp

/** Video fitted inside the black stage; photos fill it with their own fit. */
@Composable
private fun BoxScope.PlaybackStage(artifact: LocalMediaArtifact, video: Boolean, state: PlaybackObservation,
    onSurfaceView: (SurfaceView) -> Unit, onSurfaceCreated: (SurfaceHolder) -> Unit,
    onSurfaceChanged: (SurfaceHolder, Int, Int) -> Unit, onSurfaceDestroyed: () -> Unit) {
    val created by rememberUpdatedState(onSurfaceCreated)
    val changed by rememberUpdatedState(onSurfaceChanged)
    val destroyed by rememberUpdatedState(onSurfaceDestroyed)
    BoxWithConstraints(Modifier.matchParentSize()) {
        val density = LocalDensity.current
        val scale = playbackFitScale(state.videoWidth, state.videoHeight,
            with(density) { maxWidth.roundToPx() }, with(density) { maxHeight.roundToPx() })
        val fitted = Modifier.align(Alignment.Center).size(maxWidth * scale.first, maxHeight * scale.second)
        // SurfaceView preserves the decoder/compositor HDR path; TextureView can flatten it
        // to SDR. The SurfaceHolder owns its Surface, so never release that Surface ourselves.
        if (video) AndroidView(factory = { ctx ->
            SurfaceView(ctx).apply {
                tag = "media-playback-native-surface"
                onSurfaceView(this)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = created(holder)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = changed(holder, width, height)
                    override fun surfaceDestroyed(holder: SurfaceHolder) = destroyed()
                })
            }
        }, modifier = fitted.testTag("media-playback-surface"))
        state.bitmap?.let {
            // Video bitmap bytes already carry crop/rotation. Fill the fitted display rect
            // so non-square pixels stretch exactly once; photos retain their original fit.
            Image(it.asImageBitmap(), artifact.name, (if (video) fitted else Modifier.fillMaxSize()).testTag("media-playback-frame"),
                contentScale = if (video) ContentScale.FillBounds else ContentScale.Fit)
        }
    }
}

/** One line: the clip's name and its format facts, scrolling sideways rather than wrapping onto the stage. */
@Composable
private fun ClipLine(take: LocalMediaTake, metadata: ClipMetadata?, log: Boolean) {
    val (title) = rememberClipTitle(take)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        metadata?.let {
            MetadataChip(resolutionLabel(it.width, it.height))
            it.frameRate?.let { rate -> MetadataChip(frameRateLabel(rate)) }
            it.color?.let { color -> MetadataChip(color, emphasized = log) }
            it.codec?.let { codec -> MetadataChip(codec) }
            it.durationUs?.let { duration -> MetadataChip(durationLabel(duration)) }
        }
    }
}

@Composable
private fun ClipChips(metadata: ClipMetadata, log: Boolean) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetadataChip(resolutionLabel(metadata.width, metadata.height))
        metadata.frameRate?.let { MetadataChip(frameRateLabel(it)) }
        metadata.codec?.let { MetadataChip(it) }
        metadata.color?.let { MetadataChip(it, emphasized = log) }
        metadata.durationUs?.let { MetadataChip(durationLabel(it)) }
    }
}

@Composable
private fun TimecodeRow(current: String?, total: String?, counter: String?, fullscreen: Boolean, canExpand: Boolean,
    onFullscreen: (Boolean) -> Unit) {
    if (current == null && !canExpand) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        current?.let { Text(it, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontFamily = FontFamily.Monospace, maxLines = 1) }
        Text(counter.orEmpty(), Modifier.weight(1f).padding(horizontal = 6.dp), color = SettingsMuted, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        total?.let { Text(it, color = SettingsMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1) }
        if (canExpand) CineIconButton("media-playback-fullscreen", if (fullscreen) CineIcon.FULLSCREEN_EXIT else CineIcon.FULLSCREEN,
            if (fullscreen) R.string.playback_screen_fullscreen_exit else R.string.playback_screen_fullscreen,
            selected = fullscreen) { onFullscreen(!fullscreen) }
    }
}

/** The reader's own words on one line: phase, and whether the shown position is a verified frame or the player's estimate. */
@Composable
private fun StatusLine(state: PlaybackObservation, photo: Boolean, settings: PlaybackSettings) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(when (state.phase) {
            PlaybackPhase.LOADING, PlaybackPhase.SEEKING -> R.string.media_playback_loading
            PlaybackPhase.PAUSED -> R.string.media_playback_paused
            PlaybackPhase.PLAYING -> R.string.media_playback_playing
            PlaybackPhase.ENDED -> R.string.media_playback_ended
            PlaybackPhase.ERROR -> R.string.media_playback_error
            PlaybackPhase.CLOSED -> R.string.media_playback_closed
        }), Modifier.weight(0.4f, fill = false).testTag("media-playback-status"), color = SettingsMuted, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!photo) {
            val exact = state.frameIndex
            if (settings.showFramePosition && exact != null && state.phase in setOf(PlaybackPhase.PAUSED, PlaybackPhase.ENDED)) {
                Text(stringResource(R.string.media_playback_exact, exact + 1, state.timeline!!.timestampsUs.size, state.positionUs),
                    Modifier.weight(0.6f, fill = false).testTag("media-playback-exact"), color = SettingsMuted, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(stringResource(R.string.media_playback_estimated, state.positionUs / 1000),
                Modifier.weight(0.6f, fill = false).testTag("media-playback-estimated"), color = SettingsMuted, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The filmstrip is the seek bar: drag or tap anywhere on it, and the seek lands on release. Without
 * thumbnails it falls back to a plain track. Accessibility services get the same progress and SetProgress.
 */
@Composable
private fun FilmstripSeek(value: Float, strip: List<ImageBitmap>, enabled: Boolean, label: String,
    onChange: (Float) -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier.fillMaxWidth().height(SEEK_HEIGHT)
        .semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
            if (!enabled) disabled()
            setProgress { target -> if (enabled) { change(target.coerceIn(0f, 1f)); finished() }; enabled }
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            fun at(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume(); change(at(down.position.x))
                while (true) {
                    val move = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    move.consume()
                    if (!move.pressed) break
                    change(at(move.position.x))
                }
                finished()
            }
        }) {
        val shape = RoundedCornerShape(6.dp)
        if (strip.isNotEmpty()) Row(Modifier.fillMaxSize().clip(shape)) {
            strip.forEach { Image(it, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop,
                alpha = if (enabled) 0.85f else 0.4f) }
        } else Box(Modifier.align(Alignment.Center).fillMaxWidth().height(6.dp).clip(shape).background(SettingsBorder)) {
            Box(Modifier.fillMaxWidth(value).fillMaxHeight().background(if (enabled) accent else SettingsMuted))
        }
        Box(Modifier.offset(x = (maxWidth - 3.dp) * value).width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp))
            .background(if (enabled) accent else SettingsMuted))
    }
}

@Composable
private fun PlaybackErrorCard(state: PlaybackObservation, video: Boolean, nativeFrames: Boolean, colorPolicy: PreciseVideoColorPolicy,
    onRetry: () -> Unit, onNativeFrames: () -> Unit, onInterpret: () -> Unit) {
    val color = state.trackColorAvailable && colorPolicy == PreciseVideoColorPolicy.STRICT
    // A timeline means the clip was read and only exact frame decoding failed.
    val exactFailure = video && state.timeline != null
    val title = when { color -> R.string.playback_screen_color_error_title; exactFailure -> R.string.playback_screen_exact_error_title
        else -> R.string.playback_screen_read_error_title }
    val message = when { color -> R.string.playback_screen_color_error_message; exactFailure -> R.string.playback_screen_exact_error_message
        else -> R.string.playback_screen_read_error_message }
    CollapsibleErrorCard(stringResource(title), stringResource(message), state.detail, "media-playback-error") {
        ActionPill("retry", R.string.playback_screen_retry, onRetry)
        if (video) ActionPill("native-frames", if (nativeFrames) R.string.playback_screen_cpu_frames else R.string.playback_screen_native_surface, onNativeFrames)
        if (color) ActionPill("interpret-track", R.string.playback_screen_interpret_track, onInterpret)
    }
}

/** Secondary facts about how this member is being decoded; kept small but always readable. */
@Composable
private fun SignalNotes(artifact: LocalMediaArtifact, video: Boolean, state: PlaybackObservation, nativeFrames: Boolean,
    displayHdrTypes: Set<Int>, colorPolicy: PreciseVideoColorPolicy, onNativeFrames: () -> Unit, onStrict: () -> Unit) {
    @Composable fun Note(text: String, tag: String) =
        Text(text, Modifier.fillMaxWidth().testTag("media-playback-$tag"), color = SettingsMuted, fontSize = 12.sp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (video && state.phase != PlaybackPhase.ERROR) ActionPill("native-frames",
            if (nativeFrames) R.string.playback_screen_cpu_frames else R.string.playback_screen_native_surface, onNativeFrames)
        if (video && nativeFrames) {
            Note(stringResource(R.string.media_playback_native_frame_mode), "native-frame-mode")
            state.nativeHdrTransfer?.let { transfer ->
                val type = if (transfer == PreciseHdrTransfer.PQ) Display.HdrCapabilities.HDR_TYPE_HDR10 else Display.HdrCapabilities.HDR_TYPE_HLG
                Note(stringResource(if (type in displayHdrTypes) R.string.media_playback_native_hdr_available else R.string.media_playback_native_hdr_unavailable,
                    transfer.name), "native-hdr-display")
            }
        }
        state.hdrPreview?.let { Note(stringResource(R.string.media_playback_hdr_preview, it.transfer.name), "hdr-preview") }
        if (colorPolicy == PreciseVideoColorPolicy.INTERPRET_TRACK_SDR) {
            Note(stringResource(R.string.media_playback_color_interpreted), "color-interpretation")
            state.frameColor?.let { color ->
                Note(stringResource(R.string.media_playback_color_tags, color.standard, color.range, color.reportedStandard, color.reportedRange), "color-tags")
            }
            ActionPill("strict-color", R.string.media_playback_color_strict, onStrict)
        }
        if (artifact.mimeType.equals("image/x-adobe-dng", ignoreCase = true)) state.bitmap?.let { preview ->
            Note(stringResource(R.string.media_playback_dng_preview, preview.width, preview.height), "dng-preview")
        }
    }
}

@Composable
private fun PlaybackSettingsSection(open: Boolean, onOpen: (Boolean) -> Unit, settings: PlaybackSettings, onSettings: (PlaybackSettings) -> Unit,
    modifier: Modifier = Modifier) {
    SettingsCard(modifier) {
        val title = stringResource(R.string.playback_screen_settings)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = title, role = Role.Button) { onOpen(!open) }.testTag("media-playback-settings-toggle"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            CineGlyph(if (open) CineIcon.COLLAPSE else CineIcon.EXPAND, SettingsMuted, Modifier.size(20.dp))
        }
        if (open) PlaybackSettingsControls(settings, showTitle = false, onSettings = onSettings)
    }
}

@Composable
private fun ActionPill(tag: String, label: Int, onClick: () -> Unit) =
    SettingsPill(stringResource(label), "media-playback-$tag", role = Role.Button, onClick = onClick)

/** Fit fractions for the centered native SurfaceView, using the native player's oriented display dimensions. */
internal fun playbackFitScale(videoWidth: Int, videoHeight: Int, viewWidth: Int, viewHeight: Int): Pair<Float, Float> {
    if (minOf(videoWidth, videoHeight, viewWidth, viewHeight) <= 0) return 1f to 1f
    val scale = minOf(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
    return videoWidth * scale / viewWidth to videoHeight * scale / viewHeight
}
