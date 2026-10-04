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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

/** Longest edge of the still shown while the first exact frame decodes. */
private const val POSTER_EDGE_PX = 1280

/** Below these heights the player keeps its size and the body scrolls instead of squeezing the picture away. */
private fun minimumBodyHeight(layout: PlaybackLayout): Dp = when (layout) {
    PlaybackLayout.STACKED -> 420.dp
    PlaybackLayout.SIDE_COLUMN -> 260.dp
    PlaybackLayout.INSPECTOR -> 400.dp
}

/**
 * Dialog-owned pieces the player places: navigation, actions, the member switcher and the take facts.
 * Each layout puts them where its width allows.
 */
private class PlaybackChrome(
    val title: String,
    val subtitle: String?,
    val busy: Boolean,
    val photo: Boolean,
    val close: @Composable () -> Unit,
    val takeNavigation: @Composable (position: Boolean) -> Unit,
    val actions: @Composable () -> Unit,
    val pageNotice: @Composable () -> Unit,
    val members: @Composable () -> Unit,
    val facts: @Composable () -> Unit,
)

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
    // Kept across takes, so the facts stay open while the operator steps through the review.
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
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
        val member = members[memberIndex]
        val photo = member.mimeType.startsWith("image/")
        FullscreenSystemBars(fullscreen)
        val (clipTitle, clipSubtitle) = rememberClipTitle(take)
        // A photo without slate facts is named for what it is; its date moves to the subtitle.
        val named = remember(take) { clipTitleParts(take).scene != null }
        val title = if (photo && !named) stringResource(R.string.playback_layout_photo_title) else clipTitle
        val subtitle = if (photo && !named) listOfNotNull(clipTitle, clipSubtitle).joinToString(" · ") else clipSubtitle
        val reviewPosition = stringResource(if (cursor != null) R.string.playback_screen_take_position_more
            else R.string.playback_screen_take_position, takeIndex + 1, takes.size)
        val shortPosition = stringResource(if (cursor != null) R.string.playback_layout_position_more
            else R.string.playback_layout_position, takeIndex + 1, takes.size)
        val pane = stringResource(if (photo) R.string.playback_layout_photo_title else R.string.playback_screen_title)
        Surface(Modifier.fillMaxSize().semantics { paneTitle = pane }.testTag("media-playback-dialog"),
            color = if (fullscreen) Color.Black else MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val keyboard = LocalAdaptiveWindow.current.hardwareKeyboard
                // The dialog fills the window, so its own size is the window the layouts are chosen for.
                val layout = playbackLayoutFor(AdaptiveWindow(maxWidth.value, maxHeight.value, keyboard))
                // Back closes the facts before it leaves the review; the inspector keeps them open for good.
                BackHandler(enabled = detailsOpen && !fullscreen && layout != PlaybackLayout.INSPECTOR) { detailsOpen = false }
                val chrome = PlaybackChrome(title, subtitle, pageBusy, photo,
                    close = { CineIconButton("media-playback-close", CineIcon.BACK, R.string.media_playback_close, onClick = onDismiss) },
                    takeNavigation = { position ->
                        CineIconButton("media-playback-previous-take", CineIcon.CHEVRON_LEFT, R.string.media_playback_previous_take,
                            enabled = takeIndex > 0 && !pageBusy) { takeIndex-- }
                        if (position) Text(shortPosition, Modifier.testTag("media-playback-position"), color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        CineIconButton("media-playback-next-take", CineIcon.CHEVRON_RIGHT, R.string.media_playback_next_take,
                            enabled = !pageBusy && (takeIndex < takes.lastIndex || cursor != null), onClick = ::nextTake)
                    },
                    actions = {
                        SubjectReviewShowAction(take, member, onDismiss)
                        onShare?.let { share ->
                            CineIconButton("media-playback-share", CineIcon.SHARE,
                                if (photo) R.string.playback_layout_share_photo else R.string.playback_screen_share) { share(take) }
                        }
                        onDelete?.let { delete ->
                            CineIconButton("media-playback-delete", CineIcon.DELETE,
                                if (photo) R.string.playback_layout_delete_photo else R.string.playback_screen_delete) { delete(take) }
                        }
                    },
                    pageNotice = {
                        if (pageError) Text(stringResource(R.string.media_playback_page_error), Modifier.padding(horizontal = 12.dp)
                            .testTag("media-playback-page-error"), color = LocalCineColors.current.pending, fontSize = 13.sp)
                    },
                    members = {
                        if (members.size > 1) MemberSwitcher(members, memberIndex, pageBusy, { memberIndex-- }, { memberIndex++ })
                    },
                    facts = { PlaybackFacts(take, members, memberIndex, title, subtitle, reviewPosition) })
                key(member.uri) {
                    MediaPlaybackView(take, member, settings, onSettings, layout, keyboard, fullscreen,
                        onFullscreen = { fullscreen = it; if (it) detailsOpen = false }, detailsOpen, { detailsOpen = it },
                        onDismiss, chrome, settingsOpen) { settingsOpen = it }
                }
            }
        }
    }
}

/** Full screen hides the status and navigation bars of the dialog window; a swipe shows them for a moment. */
@Composable
private fun FullscreenSystemBars(fullscreen: Boolean) {
    val view = LocalView.current
    val window = ((view as? DialogWindowProvider) ?: (view.parent as? DialogWindowProvider))?.window
    DisposableEffect(window, fullscreen) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen && controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (fullscreen) controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun PlaybackTopBar(chrome: PlaybackChrome, position: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        chrome.close()
        chrome.takeNavigation(position)
        PlaybackTitle(chrome, Modifier.weight(1f).padding(horizontal = 8.dp), lines = 1)
        chrome.actions()
    }
    chrome.pageNotice()
}

@Composable
private fun PlaybackTitle(chrome: PlaybackChrome, modifier: Modifier, lines: Int) {
    Column(modifier) {
        Text(chrome.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            maxLines = lines, overflow = TextOverflow.Ellipsis)
        (if (chrome.busy) stringResource(R.string.media_playback_loading) else chrome.subtitle)?.let {
            Text(it, color = SettingsMuted, fontSize = 12.sp, maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Only multi-member takes get the switcher on the player; a single member is named in the details. */
@Composable
private fun MemberSwitcher(members: List<LocalMediaArtifact>, memberIndex: Int, pageBusy: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp)).background(SettingsSurface),
        verticalAlignment = Alignment.CenterVertically) {
        CineIconButton("media-playback-previous-member", CineIcon.CHEVRON_LEFT, R.string.media_playback_previous_member,
            enabled = memberIndex > 0 && !pageBusy, onClick = onPrevious)
        Text(stringResource(R.string.media_playback_member, memberIndex + 1, members.size, members[memberIndex].name),
            Modifier.weight(1f).testTag("media-playback-member"), color = SettingsMuted, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        CineIconButton("media-playback-next-member", CineIcon.CHEVRON_RIGHT, R.string.media_playback_next_member,
            enabled = memberIndex < members.lastIndex && !pageBusy, onClick = onNext)
    }
}

/** The take in plain words: name, file, where it sits in this review and, for a single file, which one. */
@Composable
private fun PlaybackFacts(take: LocalMediaTake, members: List<LocalMediaArtifact>, memberIndex: Int, title: String,
    subtitle: String?, position: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, color = SettingsMuted, fontSize = 13.sp) }
        Fact(R.string.playback_layout_file_name, take.primary.name, "media-playback-take")
        Fact(R.string.playback_layout_review_position, position)
        if (members.size == 1) Text(stringResource(R.string.media_playback_member, memberIndex + 1, members.size, members[memberIndex].name),
            Modifier.fillMaxWidth().testTag("media-playback-member"), color = SettingsMuted, fontSize = 12.sp)
    }
}

@Composable
private fun Fact(label: Int, value: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(label), Modifier.weight(0.4f), color = SettingsMuted, fontSize = 13.sp)
        Text(value, Modifier.weight(0.6f).then(tag?.let { Modifier.testTag(it) } ?: Modifier),
            color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}

@Composable
private fun DetailsSection(label: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(label), color = SettingsMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/**
 * A field monitor in every window: the picture, its timecode, the scrubber and the transport are on screen
 * together and never scroll away. The take facts, decoding notes and playback settings sit in a sheet on
 * portrait phones, in the side column on landscape phones and in a permanent inspector on large windows.
 */
@Composable
private fun MediaPlaybackView(take: LocalMediaTake, artifact: LocalMediaArtifact, settings: PlaybackSettings,
    onSettings: (PlaybackSettings) -> Unit, layout: PlaybackLayout, keyboard: Boolean, fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit, detailsOpen: Boolean, onDetailsOpen: (Boolean) -> Unit, onClose: () -> Unit,
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
        val stage = renderer ?: runCatching { LogPlaybackRenderer(holder, clip.signal, currentSettings.logView) }
            .onFailure { stageError = it.message ?: it.javaClass.simpleName }.getOrNull()?.also { renderer = it }
        return stage?.inputSurface ?: holder
    }
    fun retireStage() { renderer?.close(); renderer = null }
    DisposableEffect(artifact) { onDispose { retireStage() } }
    LaunchedEffect(renderer, settings.logView) { renderer?.setView(settings.logView) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Direct display output is a device setting; the error card can still switch it for this member alone.
    var nativeOverride by remember(artifact, settings.nativeSurfaceFrames) { mutableStateOf<Boolean?>(null) }
    val nativeFrames = video && (nativeOverride ?: settings.nativeSurfaceFrames)
    // Another output starts from a blank observation and strict colour, as switching it always did.
    var state by remember(artifact, nativeFrames) { mutableStateOf(PlaybackObservation()) }
    var session by remember(artifact) { mutableStateOf<MediaPlaybackSession?>(null) }
    var surface by remember(artifact) { mutableStateOf<Surface?>(null) }
    var surfaceView by remember(artifact) { mutableStateOf<SurfaceView?>(null) }
    var displayHdrTypes by remember(artifact) { mutableStateOf(emptySet<Int>()) }
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
    var colorPolicy by remember(artifact, nativeFrames) { mutableStateOf(PreciseVideoColorPolicy.STRICT) }
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
    // The first exact frame can take seconds; a key-frame still holds the stage meanwhile instead of black.
    var poster by remember(artifact) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(artifact, logResolved) {
        if (video && logResolved) poster = withContext(Dispatchers.IO) {
            loadPoster(context, artifact.uri, POSTER_EDGE_PX, logClip, currentSettings.logView)
        }
    }
    // Decided in the same frame the reader reports it, so the poster never sits over the first real frame.
    val firstFrameNow = state.bitmap != null || state.renderedAtNs != null || state.phase == PlaybackPhase.PLAYING ||
        state.phase == PlaybackPhase.ERROR
    var firstFrame by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session, firstFrameNow) { if (firstFrameNow) firstFrame = true }
    // A seek clears the reader's frame until the next one decodes; the last frame stays up instead of a black flash.
    val held = remember(session) { arrayOfNulls<Bitmap>(1) }
    state.bitmap?.let { held[0] = it }
    if (state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.ERROR) held[0] = null
    val shown = state.bitmap ?: held[0]?.takeIf { state.phase == PlaybackPhase.SEEKING }
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

    val timestamps = state.timeline?.timestampsUs
    val fps = metadata?.frameRate ?: timestamps?.let { frameRateFromSampleTimes(it.take(120)) }
    val totalFrames = timestamps?.size?.toLong() ?: fps?.let { (durationUs * it / 1_000_000).roundToLong() }?.takeIf { it > 0 }
    val frame = state.frameIndex?.toLong() ?: fps?.let { (state.positionUs * it / 1_000_000).toLong() }
    val exact = state.frameIndex
    val paused = state.phase == PlaybackPhase.PAUSED || state.phase == PlaybackPhase.ENDED
    val pauseIntent = state.phase == PlaybackPhase.PLAYING
    val stepping = video && paused && exact != null && timestamps != null
    val canStepBack = stepping && exact!! > 0
    val canStepForward = stepping && exact!! < timestamps!!.lastIndex
    val seekable = ready && (video || state.canPlay)
    val canTogglePlay = !photo && ready && (pauseIntent || state.canPlay)
    val toggleSession = session
    val togglePlay = playbackToggleAction(pauseIntent, { toggleSession?.play() }, { toggleSession?.pause() })
    val logAvailable = logClip != null
    fun toggleLogView() = onSettings(settings.copy(logView =
        if (settings.logView == PreciseLogView.FLAT_LOG) PreciseLogView.REC709 else PreciseLogView.FLAT_LOG))
    var chromeVisible by remember(fullscreen) { mutableStateOf(true) }
    val stacked = layout == PlaybackLayout.STACKED
    val keyState = PlaybackKeyState(canTogglePlay = canTogglePlay, canStepBack = canStepBack, canStepForward = canStepForward,
        seekable = seekable, logView = logAvailable, detailsOpen = detailsOpen && layout != PlaybackLayout.INSPECTOR, fullscreen = fullscreen)

    var seekValue by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val strip = remember(filmstrip) { filmstrip.map { it.asImageBitmap() } }
    val seekLabel = stringResource(R.string.media_playback_seek_label)
    val locale = LocalConfiguration.current.locales[0]
    val onSurface = MaterialTheme.colorScheme.onSurface

    @Composable fun CurrentTimecode(modifier: Modifier = Modifier) {
        if (!photo) Text(playbackTimecode(state.positionUs, frame, fps), modifier.testTag("media-playback-timecode"), color = onSurface,
            fontSize = 20.sp, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
    }
    @Composable fun TotalTimecode(modifier: Modifier = Modifier) {
        if (!photo) Text(playbackTimecode(durationUs, totalFrames, fps), modifier, color = SettingsMuted, fontSize = 14.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
    }
    /** The single frame counter: exact while paused on a verified frame, otherwise the player's estimate. */
    @Composable fun Counter(modifier: Modifier = Modifier) {
        when {
            photo -> Unit
            video && settings.showFramePosition && exact != null && timestamps != null && paused -> {
                // Spoken in full, with the frame's own time, so the short counter is never ambiguous.
                val sentence = stringResource(R.string.media_playback_exact, exact + 1, timestamps.size, playbackSeconds(timestamps[exact], locale))
                Text(stringResource(R.string.playback_screen_frame_counter, exact + 1, timestamps.size),
                    modifier.testTag("media-playback-exact").semantics { contentDescription = sentence },
                    color = LocalCineColors.current.verified, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            video -> Text(if (frame != null && totalFrames != null)
                    stringResource(R.string.playback_layout_frame_estimate, (frame + 1).coerceAtMost(totalFrames), totalFrames) else "",
                modifier.testTag("media-playback-estimated"), color = SettingsMuted, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> Text(stringResource(R.string.media_playback_estimated, state.positionUs / 1000), modifier.testTag("media-playback-estimated"),
                color = SettingsMuted, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    /** The reader's own words; a photo only says something while it loads or when it fails. */
    @Composable fun Status(modifier: Modifier = Modifier) {
        if (photo && state.phase != PlaybackPhase.LOADING && state.phase != PlaybackPhase.ERROR) return
        Text(stringResource(when (state.phase) {
            PlaybackPhase.LOADING, PlaybackPhase.SEEKING -> R.string.media_playback_loading
            PlaybackPhase.PAUSED -> R.string.media_playback_paused
            PlaybackPhase.PLAYING -> R.string.media_playback_playing
            PlaybackPhase.ENDED -> R.string.media_playback_ended
            PlaybackPhase.ERROR -> R.string.media_playback_error
            PlaybackPhase.CLOSED -> R.string.media_playback_closed
        }), modifier.testTag("media-playback-status"), color = SettingsMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    @Composable fun TimecodeRow() {
        if (photo) return
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CurrentTimecode()
            Counter(Modifier.weight(1f).padding(horizontal = 8.dp).wrapContentWidth(Alignment.CenterHorizontally))
            TotalTimecode()
        }
    }
    @Composable fun SeekBar(modifier: Modifier = Modifier) {
        if (photo) return
        FilmstripSeek(if (dragging) seekValue else (state.positionUs.toDouble() / maximum).toFloat().coerceIn(0f, 1f),
            strip, seekable, seekLabel, onChange = { seekValue = it; dragging = true }, onFinished = {
                session?.seek((maximum * seekValue.toDouble()).toLong()); dragging = false
            }, modifier = modifier.testTag("media-playback-seek"))
    }
    @Composable fun Transport(primary: Dp) {
        if (photo) return
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaybackKey("media-playback-start", CineIcon.SKIP_START, stringResource(R.string.media_playback_start), null, keyboard, seekable) {
                session?.seek(0)
            }
            if (video) PlaybackKey("media-playback-previous-frame", CineIcon.FRAME_BACK, stringResource(R.string.media_playback_previous_frame),
                ShortcutAction.FRAME_BACK, keyboard, canStepBack) { session?.step(-1) }
            PlaybackKey("media-playback-play-pause", if (pauseIntent) CineIcon.PAUSE else CineIcon.PLAY,
                stringResource(if (pauseIntent) R.string.media_playback_pause else R.string.media_playback_play),
                ShortcutAction.PLAY_PAUSE, keyboard, canTogglePlay, primary, togglePlay)
            if (video) PlaybackKey("media-playback-next-frame", CineIcon.FRAME_FORWARD, stringResource(R.string.media_playback_next_frame),
                ShortcutAction.FRAME_FORWARD, keyboard, canStepForward) { session?.step(1) }
            PlaybackKey("media-playback-end", CineIcon.SKIP_END, stringResource(R.string.media_playback_end), null, keyboard, seekable) {
                session?.seek(maximum)
            }
        }
    }
    @Composable fun FullscreenButton() {
        if (!(video || photo)) return
        val label = stringResource(if (fullscreen) R.string.playback_screen_fullscreen_exit else R.string.playback_screen_fullscreen)
        CineIconButton("media-playback-fullscreen", if (fullscreen) CineIcon.FULLSCREEN_EXIT else CineIcon.FULLSCREEN,
            if (keyboard && fullscreen) withShortcut(label, ShortcutAction.DISMISS) else label, selected = fullscreen) { onFullscreen(!fullscreen) }
    }
    /** LOG/Rec.709, the luma histogram, and the badge that says frames bypass the verified colour preview. */
    @Composable fun Views() {
        if (logAvailable) {
            val monitoring = stringResource(R.string.playback_screen_monitoring)
            val spoken = if (keyboard) withShortcut(monitoring, ShortcutAction.LOG_VIEW) else monitoring
            ShortcutTooltip(spoken.takeIf { keyboard }) {
                SegmentedToggle(listOf(PreciseLogView.FLAT_LOG to stringResource(R.string.playback_screen_log),
                    PreciseLogView.REC709 to stringResource(R.string.playback_screen_rec709)), settings.logView,
                    { onSettings(settings.copy(logView = it)) }, "media-playback-log-view",
                    Modifier.semantics { contentDescription = spoken })
            }
            val label = stringResource(R.string.playback_screen_histogram)
            HistogramThumb(histogram, Modifier.size(72.dp, 40.dp).semantics { contentDescription = label })
        }
        if (nativeFrames) MetadataChip(stringResource(R.string.playback_layout_direct_output),
            Modifier.testTag("media-playback-native-frame-mode"), emphasized = true)
        if (stageError != null) CineIconButton("media-playback-stage-warning", CineIcon.WARNING, R.string.playback_screen_stage_error_title,
            tint = LocalCineColors.current.pending) { onDetailsOpen(true) }
    }
    val detailsTitle = stringResource(if (photo) R.string.playback_layout_photo_info else R.string.playback_screen_info)
    @Composable fun Details() {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            chrome.facts()
            if (photo) shown?.let {
                Fact(R.string.playback_layout_size, stringResource(R.string.playback_layout_photo_size, it.width, it.height), "media-playback-photo-size")
            }
            metadata?.let { DetailsSection(R.string.playback_layout_section_format) { ClipChips(it, logAvailable) } }
            if (video) DetailsSection(R.string.playback_layout_section_accuracy) {
                if (exact != null && timestamps != null && paused) Text(stringResource(R.string.media_playback_exact, exact + 1, timestamps.size,
                    playbackSeconds(timestamps[exact], locale)), Modifier.fillMaxWidth().testTag("media-playback-exact-detail"),
                    color = onSurface, fontSize = 13.sp)
                else Text(stringResource(R.string.playback_layout_estimated_detail), Modifier.fillMaxWidth().testTag("media-playback-estimated-detail"),
                    color = SettingsMuted, fontSize = 13.sp)
            }
            stageError?.let {
                CollapsibleErrorCard(stringResource(R.string.playback_screen_stage_error_title),
                    stringResource(R.string.playback_screen_stage_error_message), it, "media-playback-log-stage-error")
            }
            SignalNotes(artifact, video, state, shown, nativeFrames, displayHdrTypes, colorPolicy,
                onStrict = { state = PlaybackObservation(); colorPolicy = PreciseVideoColorPolicy.STRICT })
            if (!photo) PlaybackSettingsSection(settingsOpen, onSettingsOpen, settings, onSettings)
        }
    }
    @Composable fun InfoRow() {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = detailsTitle, role = Role.Button) { onDetailsOpen(true) }
            .testTag("media-playback-info").padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CineGlyph(CineIcon.INFO, SettingsMuted, Modifier.size(20.dp))
            Text(detailsTitle, Modifier.weight(1f), color = onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CineGlyph(CineIcon.CHEVRON_RIGHT, SettingsMuted, Modifier.size(20.dp))
        }
    }

    val stageAspect = (if (photo) shown?.let { pictureAspect(it.width, it.height) }
        else pictureAspect(state.videoWidth, state.videoHeight) ?: poster?.let { pictureAspect(it.width, it.height) }
            ?: metadata?.let { pictureAspect(it.width, it.height) })
    val scroll = rememberScrollState()
    LaunchedEffect(fullscreen) { if (fullscreen) scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize()
        .dialogShortcuts { action ->
            when (playbackCommandFor(action, keyState)) {
                PlaybackCommand.TOGGLE_PLAY -> togglePlay()
                PlaybackCommand.FRAME_BACK -> session?.step(-1)
                PlaybackCommand.FRAME_FORWARD -> session?.step(1)
                PlaybackCommand.JUMP_BACK -> session?.seek(playbackJumpTarget(state.positionUs, -PLAYBACK_JUMP_US, maximum))
                PlaybackCommand.JUMP_FORWARD -> session?.seek(playbackJumpTarget(state.positionUs, PLAYBACK_JUMP_US, maximum))
                PlaybackCommand.TOGGLE_LOG_VIEW -> toggleLogView()
                PlaybackCommand.CLOSE_DETAILS -> onDetailsOpen(false)
                PlaybackCommand.EXIT_FULLSCREEN -> onFullscreen(false)
                PlaybackCommand.CLOSE -> onClose()
                null -> return@dialogShortcuts false
            }
            true
        }
        .then(if (fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
        // The landscape phone has no height to spare for a bar: its navigation lives in the side column.
        if (!fullscreen && layout != PlaybackLayout.SIDE_COLUMN) PlaybackTopBar(chrome, position = layout == PlaybackLayout.INSPECTOR)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxHeight
            val sheetHeight = maxHeight * 0.88f
            val sideWidth = (maxWidth * 0.34f).coerceIn(240.dp, 320.dp)
            // Every layout keeps the stage at the same place in the tree, so full screen never recreates the Surface.
            Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = !fullscreen).testTag("media-playback-scroll")) {
                Row(Modifier.fillMaxWidth().height(if (fullscreen) viewport else viewport.coerceAtLeast(minimumBodyHeight(layout)))) {
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!fullscreen && layout != PlaybackLayout.SIDE_COLUMN) chrome.members()
                        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            PlaybackStageArea(
                                if (stacked && !fullscreen) Modifier.weight(1f, fill = false).aspectRatio(stageAspect ?: if (video || photo) 16f / 9f else 3f)
                                else Modifier.weight(1f).fillMaxWidth(),
                                artifact, video, photo, stageAspect, state, shown, poster.takeIf { video && !firstFrame && !firstFrameNow }, fullscreen,
                                onSurfaceView = { surfaceView = it },
                                onSurfaceCreated = { holder -> surface = holder.surface; updateDisplay(); session?.setSurface(output(holder.surface)) },
                                onSurfaceChanged = { holder, width, height ->
                                    surface = holder.surface; updateDisplay(); renderer?.resize(width, height)
                                    session?.setSurface(output(holder.surface))
                                },
                                onSurfaceDestroyed = {
                                    // Producers first, then the GL stage's window, before the holder destroys its Surface.
                                    session?.setSurface(null); retireStage(); surface = null; displayHdrTypes = emptySet()
                                }) {
                                // A failure takes the stage itself, where the missing picture is, rather than a card below the fold.
                                if (state.phase == PlaybackPhase.ERROR) Box(Modifier.matchParentSize()
                                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f)).verticalScroll(rememberScrollState())
                                    .padding(12.dp), contentAlignment = Alignment.Center) {
                                    PlaybackErrorCard(state, video, nativeFrames, colorPolicy, onRetry = { state = PlaybackObservation(); retry++ },
                                        onNativeFrames = { nativeOverride = !nativeFrames },
                                        onInterpret = { state = PlaybackObservation(); colorPolicy = PreciseVideoColorPolicy.INTERPRET_TRACK_SDR })
                                }
                                if (fullscreen) {
                                    val toggle = stringResource(if (chromeVisible) R.string.playback_layout_hide_controls else R.string.playback_layout_show_controls)
                                    Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null, onClickLabel = toggle,
                                        role = Role.Button) { chromeVisible = !chromeVisible }.testTag("media-playback-chrome-toggle"))
                                    if (chromeVisible) {
                                        val overlay = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                                        Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing
                                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(8.dp)
                                            .clip(RoundedCornerShape(12.dp)).background(overlay)) { FullscreenButton() }
                                        if (!photo) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(overlay)
                                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                                            .padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            TimecodeRow()
                                            SeekBar()
                                            Transport(56.dp)
                                        }
                                    }
                                }
                            }
                            if (!fullscreen) when (layout) {
                                PlaybackLayout.STACKED, PlaybackLayout.INSPECTOR -> Column(Modifier.widthIn(max = 960.dp).fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TimecodeRow()
                                    Status(Modifier.fillMaxWidth())
                                    SeekBar(Modifier.padding(vertical = 4.dp))
                                    Transport(if (stacked) 56.dp else 64.dp)
                                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                        Views()
                                        Spacer(Modifier.weight(1f))
                                        FullscreenButton()
                                    }
                                }
                                // Two short rows under the picture: the timeline, then the transport with the reader's status.
                                PlaybackLayout.SIDE_COLUMN -> Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CurrentTimecode()
                                        SeekBar(Modifier.weight(1f))
                                        TotalTimecode()
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Transport(56.dp)
                                        Column(Modifier.weight(1f)) {
                                            Counter()
                                            Status()
                                        }
                                        FullscreenButton()
                                    }
                                }
                            }
                        }
                        if (!fullscreen && stacked) {
                            HorizontalDivider(color = SettingsBorder)
                            InfoRow()
                        }
                    }
                    if (!fullscreen && layout == PlaybackLayout.SIDE_COLUMN) Column(Modifier.width(sideWidth).fillMaxHeight()
                        .background(SettingsSurface)) {
                        if (detailsOpen) {
                            DetailsHeader(detailsTitle, CineIcon.BACK) { onDetailsOpen(false) }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp).testTag("media-playback-details")) { Details() }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                chrome.close()
                                Spacer(Modifier.weight(1f))
                                chrome.takeNavigation(false)
                            }
                            chrome.pageNotice()
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlaybackTitle(chrome, Modifier.fillMaxWidth(), lines = 2)
                                chrome.members()
                                metadata?.let { ClipChips(it, logAvailable) }
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) { Views() }
                            }
                            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                CineIconButton("media-playback-info", CineIcon.INFO, detailsTitle) { onDetailsOpen(true) }
                                Spacer(Modifier.weight(1f))
                                chrome.actions()
                            }
                        }
                    }
                    if (!fullscreen && layout == PlaybackLayout.INSPECTOR) Column(Modifier.width(360.dp).fillMaxHeight()
                        .background(SettingsSurface).verticalScroll(rememberScrollState()).padding(16.dp).testTag("media-playback-details")) {
                        Details()
                    }
                }
            }
            if (!fullscreen && stacked && detailsOpen) {
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                    .clickable(interactionSource = null, indication = null, onClickLabel = stringResource(R.string.playback_layout_close_info)) {
                        onDetailsOpen(false)
                    })
                Surface(Modifier.align(Alignment.BottomCenter).widthIn(max = 840.dp).fillMaxWidth().heightIn(max = sheetHeight)
                    .semantics { paneTitle = detailsTitle }.testTag("media-playback-details"),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, SettingsBorder)) {
                    Column {
                        DetailsHeader(detailsTitle, CineIcon.COLLAPSE) { onDetailsOpen(false) }
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp)) { Details() }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsHeader(title: String, icon: CineIcon, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        CineIconButton("media-playback-details-close", icon, R.string.playback_layout_close_info, onClick = onClose)
    }
}

/** A transport key whose name carries its keyboard shortcut, shown as a tooltip, while a keyboard is attached. */
@Composable
private fun PlaybackKey(tag: String, icon: CineIcon, label: String, shortcut: ShortcutAction?, keyboard: Boolean, enabled: Boolean,
    primary: Dp? = null, onClick: () -> Unit) {
    val spoken = if (keyboard && shortcut != null) withShortcut(label, shortcut) else label
    ShortcutTooltip(spoken.takeIf { keyboard && shortcut != null }) {
        TransportButton(tag, icon, spoken, enabled, primary = primary != null, primarySize = primary ?: 64.dp, onClick = onClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutTooltip(label: String?, content: @Composable () -> Unit) {
    if (label == null) return content()
    TooltipBox(TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()) { content() }
}

private val SEEK_HEIGHT = 40.dp

/**
 * The picture sized to its own aspect inside [modifier]'s area: a 4:3 photo never sits in a 16:9 black box and
 * a 16:9 clip fills a 16:9 screen edge to edge. [overlay] covers the whole area (error card, full-screen chrome).
 */
@Composable
private fun PlaybackStageArea(modifier: Modifier, artifact: LocalMediaArtifact, video: Boolean, photo: Boolean, aspect: Float?,
    state: PlaybackObservation, shown: Bitmap?, poster: Bitmap?, fullscreen: Boolean, onSurfaceView: (SurfaceView) -> Unit,
    onSurfaceCreated: (SurfaceHolder) -> Unit, onSurfaceChanged: (SurfaceHolder, Int, Int) -> Unit, onSurfaceDestroyed: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier.background(if (fullscreen) Color.Black else Color.Transparent)) {
        val (width, height) = fitInside(if (video || photo) aspect else null, maxWidth.value, maxHeight.value)
        Box(Modifier.align(Alignment.Center).size(width.dp, height.dp).background(if (video || photo) Color.Black else SettingsSurface)) {
            if (video || photo) PlaybackStage(artifact, video, state, shown, poster, onSurfaceView, onSurfaceCreated, onSurfaceChanged, onSurfaceDestroyed)
            else Text(artifact.name, Modifier.align(Alignment.Center).padding(16.dp), color = SettingsMuted, fontSize = 13.sp,
                textAlign = TextAlign.Center)
        }
        overlay()
    }
}

/** Video fitted inside the black stage; photos fill it with their own fit. */
@Composable
private fun BoxScope.PlaybackStage(artifact: LocalMediaArtifact, video: Boolean, state: PlaybackObservation, shown: Bitmap?,
    poster: Bitmap?, onSurfaceView: (SurfaceView) -> Unit, onSurfaceCreated: (SurfaceHolder) -> Unit,
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
        poster?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize().testTag("media-playback-poster"), contentScale = ContentScale.Fit)
        }
        shown?.let {
            // Video bitmap bytes already carry crop/rotation. Fill the fitted display rect
            // so non-square pixels stretch exactly once; photos retain their original fit.
            Image(it.asImageBitmap(), artifact.name, (if (video) fitted else Modifier.fillMaxSize()).testTag("media-playback-frame"),
                contentScale = if (video) ContentScale.FillBounds else ContentScale.Fit)
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
        // For this member only; the lasting choice is in the playback settings.
        if (video) ActionPill("native-frames", if (nativeFrames) R.string.playback_screen_cpu_frames else R.string.playback_screen_native_surface, onNativeFrames)
        if (color) ActionPill("interpret-track", R.string.playback_screen_interpret_track, onInterpret)
    }
}

/** Secondary facts about how this member is being decoded; kept small but always readable. */
@Composable
private fun SignalNotes(artifact: LocalMediaArtifact, video: Boolean, state: PlaybackObservation, shown: Bitmap?, nativeFrames: Boolean,
    displayHdrTypes: Set<Int>, colorPolicy: PreciseVideoColorPolicy, onStrict: () -> Unit) {
    @Composable fun Note(text: String, tag: String) =
        Text(text, Modifier.fillMaxWidth().testTag("media-playback-$tag"), color = SettingsMuted, fontSize = 12.sp)
    val dng = artifact.mimeType.equals("image/x-adobe-dng", ignoreCase = true) && shown != null
    if (!(video && nativeFrames) && state.hdrPreview == null && colorPolicy != PreciseVideoColorPolicy.INTERPRET_TRACK_SDR && !dng) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (video && nativeFrames) {
            Note(stringResource(R.string.media_playback_native_frame_mode), "native-frame-note")
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
        if (dng) Note(stringResource(R.string.media_playback_dng_preview, shown!!.width, shown.height), "dng-preview")
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
