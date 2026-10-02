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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    Dialog(onDismissRequest = { if (fullscreen) fullscreen = false else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("media-playback-dialog"), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val viewport = maxHeight
                val scroll = rememberScrollState()
                LaunchedEffect(fullscreen) { if (fullscreen) scroll.scrollTo(0) }
                Column(Modifier.fillMaxSize()) {
                    if (!fullscreen) PlaybackTopBar(take, onDismiss, onShare, onDelete) { SubjectReviewShowAction(take, members[memberIndex], onDismiss) }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("media-playback-scroll")
                        .padding(horizontal = if (fullscreen) 0.dp else 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Column((if (fullscreen) Modifier else Modifier.widthIn(max = 840.dp)).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!fullscreen) PlaybackHeader(take, members, memberIndex,
                                stringResource(if (cursor != null) R.string.playback_screen_take_position_more else R.string.playback_screen_take_position,
                                    takeIndex + 1, takes.size),
                                previousTake = takeIndex > 0 && !pageBusy, nextTake = !pageBusy && (takeIndex < takes.lastIndex || cursor != null),
                                pageBusy = pageBusy, pageError = pageError, onPreviousTake = { takeIndex-- }, onNextTake = ::nextTake,
                                onPreviousMember = { memberIndex-- }, onNextMember = { memberIndex++ })
                            key(members[memberIndex].uri) {
                                MediaPlaybackView(take, members[memberIndex], settings, onSettings, fullscreen, { fullscreen = it }, viewport,
                                    settingsOpen) { settingsOpen = it }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackTopBar(take: LocalMediaTake, onClose: () -> Unit, onShare: ((LocalMediaTake) -> Unit)?, onDelete: ((LocalMediaTake) -> Unit)?,
    subjectAction: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CineIconButton("media-playback-close", CineIcon.BACK, R.string.media_playback_close, onClick = onClose)
        Text(stringResource(R.string.playback_screen_title), Modifier.weight(1f).padding(horizontal = 8.dp), color = SettingsMuted,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subjectAction()
        onShare?.let { share -> CineIconButton("media-playback-share", CineIcon.SHARE, R.string.playback_screen_share) { share(take) } }
        onDelete?.let { delete -> CineIconButton("media-playback-delete", CineIcon.DELETE, R.string.playback_screen_delete) { delete(take) } }
    }
}

@Composable
private fun PlaybackHeader(take: LocalMediaTake, members: List<LocalMediaArtifact>, memberIndex: Int, position: String,
    previousTake: Boolean, nextTake: Boolean, pageBusy: Boolean, pageError: Boolean, onPreviousTake: () -> Unit,
    onNextTake: () -> Unit, onPreviousMember: () -> Unit, onNextMember: () -> Unit) {
    val (title, subtitle) = rememberClipTitle(take)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(take.primary.name, Modifier.fillMaxWidth().testTag("media-playback-take"), color = SettingsMuted, fontSize = 11.sp)
        subtitle?.let { Text(it, color = SettingsMuted, fontSize = 13.sp) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CineIconButton("media-playback-previous-take", CineIcon.CHEVRON_LEFT, R.string.media_playback_previous_take,
            enabled = previousTake, onClick = onPreviousTake)
        Text(position, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        CineIconButton("media-playback-next-take", CineIcon.CHEVRON_RIGHT, R.string.media_playback_next_take,
            enabled = nextTake, onClick = onNextTake)
        Spacer(Modifier.weight(1f))
        if (pageBusy) Text(stringResource(R.string.media_playback_loading), color = SettingsMuted, fontSize = 12.sp)
    }
    val member = stringResource(R.string.media_playback_member, memberIndex + 1, members.size, members[memberIndex].name)
    if (members.size > 1) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SettingsSurface),
        verticalAlignment = Alignment.CenterVertically) {
        CineIconButton("media-playback-previous-member", CineIcon.CHEVRON_LEFT, R.string.media_playback_previous_member,
            enabled = memberIndex > 0 && !pageBusy, onClick = onPreviousMember)
        Text(member, Modifier.weight(1f).testTag("media-playback-member"), color = SettingsMuted, fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        CineIconButton("media-playback-next-member", CineIcon.CHEVRON_RIGHT, R.string.media_playback_next_member,
            enabled = memberIndex < members.lastIndex && !pageBusy, onClick = onNextMember)
    } else Text(member, Modifier.fillMaxWidth().testTag("media-playback-member"), color = SettingsMuted, fontSize = 11.sp)
    if (pageError) Text(stringResource(R.string.media_playback_page_error), Modifier.testTag("media-playback-page-error"),
        color = LocalCineColors.current.pending, fontSize = 13.sp)
}

@Composable
private fun MediaPlaybackView(take: LocalMediaTake, artifact: LocalMediaArtifact, settings: PlaybackSettings,
    onSettings: (PlaybackSettings) -> Unit, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, viewport: Dp,
    settingsOpen: Boolean, onSettingsOpen: (Boolean) -> Unit) {
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
    val stripHeight = with(LocalDensity.current) { 40.dp.roundToPx() }
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

    if (!fullscreen) metadata?.let { ClipChips(it, logClip != null) }
    if (video || photo) {
        val known = when {
            state.videoWidth > 0 && state.videoHeight > 0 -> state.videoWidth.toFloat() / state.videoHeight
            photo && state.bitmap != null -> state.bitmap!!.width.toFloat() / state.bitmap!!.height.coerceAtLeast(1)
            metadata != null -> metadata!!.width.toFloat() / metadata!!.height.coerceAtLeast(1)
            else -> 16f / 9f
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cap = if (fullscreen) (viewport - if (photo) 76.dp else 212.dp).coerceAtLeast(160.dp) else (viewport * 0.62f).coerceAtLeast(160.dp)
            val stageWidth = maxWidth
            val stageHeight = if (fullscreen) cap else (stageWidth / known.coerceAtLeast(0.01f)).coerceIn(160.dp, cap)
            Box(Modifier.fillMaxWidth().height(stageHeight).background(Color.Black)) {
                val density = LocalDensity.current
                val scale = playbackFitScale(state.videoWidth, state.videoHeight,
                    with(density) { stageWidth.roundToPx() }, with(density) { stageHeight.roundToPx() })
                // SurfaceView preserves the decoder/compositor HDR path; TextureView can flatten it
                // to SDR. The SurfaceHolder owns its Surface, so never release that Surface ourselves.
                if (video) AndroidView(factory = { ctx ->
                    SurfaceView(ctx).apply {
                        tag = "media-playback-native-surface"
                        surfaceView = this
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                surface = holder.surface; updateDisplay(); session?.setSurface(output(holder.surface))
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                surface = holder.surface; updateDisplay(); renderer?.resize(width, height)
                                session?.setSurface(output(holder.surface))
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                // Producers first, then the GL stage's window, before the holder destroys its Surface.
                                session?.setSurface(null); retireStage(); surface = null; displayHdrTypes = emptySet()
                            }
                        })
                    }
                }, modifier = Modifier.align(Alignment.Center).size(stageWidth * scale.first, stageHeight * scale.second)
                    .testTag("media-playback-surface"))
                state.bitmap?.let {
                    // Video bitmap bytes already carry crop/rotation. Fill the fitted display rect
                    // so non-square pixels stretch exactly once; photos retain their original fit.
                    val frameModifier = if (video) Modifier.align(Alignment.Center).size(stageWidth * scale.first, stageHeight * scale.second)
                        else Modifier.fillMaxSize()
                    Image(it.asImageBitmap(), artifact.name, frameModifier.testTag("media-playback-frame"),
                        contentScale = if (video) ContentScale.FillBounds else ContentScale.Fit)
                }
            }
        }
    }
    val timestamps = state.timeline?.timestampsUs
    val fps = metadata?.frameRate ?: timestamps?.let { frameRateFromSampleTimes(it.take(120)) }
    val totalFrames = timestamps?.size?.toLong() ?: fps?.let { (durationUs * it / 1_000_000).roundToLong() }?.takeIf { it > 0 }
    val frame = state.frameIndex?.toLong() ?: fps?.let { (state.positionUs * it / 1_000_000).toLong() }
    TimecodeRow(if (photo) null else playbackTimecode(state.positionUs, frame, fps),
        if (photo) null else playbackTimecode(durationUs, totalFrames, fps), fullscreen, video || photo, onFullscreen)
    if (!photo) {
        val exact = state.frameIndex
        val seekLabel = stringResource(R.string.media_playback_seek_label)
        var seek by remember { mutableFloatStateOf(0f) }
        var dragging by remember { mutableStateOf(false) }
        val strip = remember(filmstrip) { filmstrip.map { it.asImageBitmap() } }
        val sliderValue = if (dragging) seek else (state.positionUs.toDouble() / maximum).toFloat().coerceIn(0f, 1f)
        CineSlider(value = sliderValue,
            onValueChange = { seek = it; dragging = true }, onValueChangeFinished = {
                session?.seek((maximum * seek.toDouble()).toLong()); dragging = false
            }, enabled = ready && (video || state.canPlay), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("media-playback-seek").semantics { contentDescription = seekLabel })
        // The strip sits under the slider rather than behind it: the Material track is opaque and would hide it.
        if (strip.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(40.dp).clip(RoundedCornerShape(6.dp))) {
            Row(Modifier.fillMaxSize()) {
                strip.forEach { Image(it, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop, alpha = 0.85f) }
            }
            Box(Modifier.offset(x = (maxWidth - 2.dp) * sliderValue).width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
        val pauseIntent = state.phase == PlaybackPhase.PLAYING
        val toggleSession = session
        val stepping = state.phase in setOf(PlaybackPhase.PAUSED, PlaybackPhase.ENDED) && exact != null
        val seekable = ready && (video || state.canPlay)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
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
        if (video && !fullscreen && frame != null && totalFrames != null) Text(
            stringResource(R.string.playback_screen_frame_counter, (frame + 1).coerceAtMost(totalFrames), totalFrames),
            Modifier.fillMaxWidth(), color = SettingsMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center)
    }
    if (!fullscreen) StatusLine(state, photo, settings)
    if (fullscreen) return
    if (logClip != null) MonitoringSection(settings, onSettings, histogram)
    if (state.phase == PlaybackPhase.ERROR) PlaybackErrorCard(state, video, nativeFrames, colorPolicy,
        onRetry = { state = PlaybackObservation(); retry++ },
        onNativeFrames = { state = PlaybackObservation(); colorPolicy = PreciseVideoColorPolicy.STRICT; nativeFrames = !nativeFrames },
        onInterpret = { state = PlaybackObservation(); colorPolicy = PreciseVideoColorPolicy.INTERPRET_TRACK_SDR })
    stageError?.let {
        CollapsibleErrorCard(stringResource(R.string.playback_screen_stage_error_title), stringResource(R.string.playback_screen_stage_error_message),
            it, "media-playback-log-stage-error")
    }
    SignalNotes(artifact, video, state, nativeFrames, displayHdrTypes, colorPolicy,
        onNativeFrames = { state = PlaybackObservation(); colorPolicy = PreciseVideoColorPolicy.STRICT; nativeFrames = !nativeFrames },
        onStrict = { state = PlaybackObservation(); colorPolicy = PreciseVideoColorPolicy.STRICT })
    PlaybackSettingsSection(settingsOpen, onSettingsOpen, settings, onSettings)
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
private fun TimecodeRow(current: String?, total: String?, fullscreen: Boolean, canExpand: Boolean, onFullscreen: (Boolean) -> Unit) {
    if (current == null && !canExpand) return
    Row(Modifier.fillMaxWidth().padding(horizontal = if (fullscreen) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        current?.let { Text(it, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontFamily = FontFamily.Monospace) }
        Spacer(Modifier.weight(1f))
        total?.let { Text(it, color = SettingsMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
        if (canExpand) CineIconButton("media-playback-fullscreen", if (fullscreen) CineIcon.FULLSCREEN_EXIT else CineIcon.FULLSCREEN,
            if (fullscreen) R.string.playback_screen_fullscreen_exit else R.string.playback_screen_fullscreen,
            selected = fullscreen) { onFullscreen(!fullscreen) }
    }
}

/** The reader's own words: phase, and whether the shown position is a verified frame or the player's estimate. */
@Composable
private fun StatusLine(state: PlaybackObservation, photo: Boolean, settings: PlaybackSettings) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(when (state.phase) {
            PlaybackPhase.LOADING, PlaybackPhase.SEEKING -> R.string.media_playback_loading
            PlaybackPhase.PAUSED -> R.string.media_playback_paused
            PlaybackPhase.PLAYING -> R.string.media_playback_playing
            PlaybackPhase.ENDED -> R.string.media_playback_ended
            PlaybackPhase.ERROR -> R.string.media_playback_error
            PlaybackPhase.CLOSED -> R.string.media_playback_closed
        }), Modifier.fillMaxWidth().testTag("media-playback-status"), color = SettingsMuted, fontSize = 12.sp)
        if (!photo) {
            val exact = state.frameIndex
            if (settings.showFramePosition && exact != null && state.phase in setOf(PlaybackPhase.PAUSED, PlaybackPhase.ENDED)) {
                Text(stringResource(R.string.media_playback_exact, exact + 1, state.timeline!!.timestampsUs.size, state.positionUs),
                    Modifier.fillMaxWidth().testTag("media-playback-exact"), color = SettingsMuted, fontSize = 11.sp)
            } else Text(stringResource(R.string.media_playback_estimated, state.positionUs / 1000),
                Modifier.fillMaxWidth().testTag("media-playback-estimated"), color = SettingsMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MonitoringSection(settings: PlaybackSettings, onSettings: (PlaybackSettings) -> Unit, histogram: FloatArray?) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.playback_screen_monitoring), color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                SegmentedToggle(listOf(PreciseLogView.FLAT_LOG to stringResource(R.string.playback_screen_log),
                    PreciseLogView.REC709 to stringResource(R.string.playback_screen_rec709)), settings.logView,
                    { onSettings(settings.copy(logView = it)) }, "media-playback-log-view")
            }
            val label = stringResource(R.string.playback_screen_histogram)
            HistogramThumb(histogram, Modifier.size(96.dp, 48.dp).semantics { contentDescription = label })
        }
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
private fun PlaybackSettingsSection(open: Boolean, onOpen: (Boolean) -> Unit, settings: PlaybackSettings, onSettings: (PlaybackSettings) -> Unit) {
    SettingsCard {
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
