/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Surface
import androidx.core.net.toUri
import com.librestatic.opencinecam.media.ocraw.OcrawAudioChunk
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawFrameIndex
import com.librestatic.opencinecam.media.ocraw.OcrawFrameRead
import com.librestatic.opencinecam.media.ocraw.OcrawFrameSource
import com.librestatic.opencinecam.media.ocraw.OcrawMissingReason
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.playback.RawDevelop
import com.librestatic.opencinecam.playback.RawDevelopParams
import com.librestatic.opencinecam.playback.RawDevelopQuality
import com.librestatic.opencinecam.playback.RawPlaybackRenderer
import com.librestatic.opencinecam.playback.RawStaticColour
import com.librestatic.opencinecam.storage.LocalMediaArtifact
import com.librestatic.opencinecam.storage.VideoFrameTimeline
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CompletableFuture

/** What a RAW session is doing now: playing at the take's rate, or one full-resolution frame at a time. */
internal enum class RawPlaybackRunMode { REALTIME, FRAMES }

/** Real time is judged over this much playing time (PLAN-071 §7 decision 5)... */
internal const val RAW_FALLBACK_WINDOW_NS = 2_000_000_000L

/** ...and only once it holds at least this many timed frames, so a very slow device is still judged. */
internal const val RAW_FALLBACK_MIN_SAMPLES = 10

/**
 * The automatic fall back to the frame viewer (PLAN-071 §7 decision 5): true when the median
 * per-frame read, decode and render time exceeds [frameIntervalNs], or more than a quarter of the
 * frames due were dropped. Undecided (false) with fewer than [RAW_FALLBACK_MIN_SAMPLES] samples.
 */
internal fun rawPlaybackShouldFallBack(samples: List<Long>, frameIntervalNs: Long, shown: Int, dropped: Int): Boolean {
    if (samples.size < RAW_FALLBACK_MIN_SAMPLES) return false
    val sorted = samples.sorted()
    val middle = sorted.size / 2
    val median = if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    if (median > frameIntervalNs) return true
    val due = shown + dropped
    return due > 0 && dropped * 4 > due
}

/** A pool buffer from a [RawFrameProvider]; [handle] is the provider's own slot. */
internal class RawFrameSlot(val buffer: ByteBuffer, val handle: Any)

/** Frames of one indexed take, read on the session's loop thread only. Closing it closes the file. */
internal interface RawFrameProvider : Closeable {
    val index: OcrawFrameIndex
    /** A free slot, or null when every slot is out. */
    fun acquire(): RawFrameSlot?
    fun release(slot: RawFrameSlot)
    fun read(frameIndex: Int, slot: RawFrameSlot): OcrawFrameRead
    /** Reads one audio chunk's checked payload into [dst] (see [OcrawStreamReader.readAudioChunk]). */
    fun readAudio(chunk: OcrawAudioChunk, dst: ByteBuffer): Int = throw IOException("no audio reader")
}

/** The GL stage, as [RawPlaybackRenderer] offers it. [render] may complete on any thread. */
internal interface RawFrameSink {
    fun attach(surface: Surface, width: Int, height: Int)
    fun detach()
    fun render(buffer: ByteBuffer, header: OcrawFrameHeader, params: RawDevelopParams, quality: RawDevelopQuality,
        onDone: (renderNanos: Long, drawn: Boolean) -> Unit)
    fun release()
}

/** The session's single serial thread. [postDelayed] returns a cancel action. */
internal interface RawPlaybackLoop {
    fun post(task: () -> Unit)
    fun postDelayed(delayNs: Long, task: () -> Unit): () -> Unit
    fun quit()
}

/**
 * Review player for `.ocraw` takes (PLAN-071 §7). Two modes share it:
 *
 * - **Real time** ([RawPlaybackRunMode.REALTIME]): Play starts a clock that maps to take time from
 *   the shown frame's sensor timestamp. The reader reads the latest frame due at the clock (a
 *   skipped frame counts as dropped) while the GL stage draws the previous one, so at most two
 *   pool slots are out; frames develop at half resolution. At the end it stops ([PlaybackPhase.ENDED])
 *   or wraps with [PlaybackSettings.loop]. Pause redraws the shown frame at full resolution.
 * - **Frame viewer** ([RawPlaybackRunMode.FRAMES]): every shown frame develops at full resolution;
 *   [step] and [seek] move to a frame. [play] does nothing and [PlaybackObservation.canPlay] stays
 *   false, so the dialog never shows a Play that would not move; it offers [tryRealtime] instead.
 *
 * Sound (PLAN-071 §7.1): in real time, a take with audio streams its PCM through a [RawAudioOutput]
 * from [createAudio], gaps filled with silence by [RawAudioFeeder], and the output's played position
 * is the master clock: the frame due is the one at the audio time. A take without audio, or an
 * output that could not be made, runs on [clock]. Every start (play, a seek while playing, a loop)
 * makes a fresh output at the new position; pause, the frame viewer and close release it. Muting
 * only silences the output, so the clock and the sync carry on, and gives up the audio focus.
 *
 * A frame that cannot be read ([OcrawFrameRead.Missing]) keeps the previous image and reports its
 * reason, but the position lands on it, so the frame number stays honest. During the first
 * [RAW_FALLBACK_WINDOW_NS] of real time (with [PlaybackSettings.rawPlayback] AUTOMATIC) the session
 * times every frame and falls back to the frame viewer by [rawPlaybackShouldFallBack].
 *
 * Threading: public calls come from one thread (main) and only post to [loop], except that a null
 * [setSurface] and [close] detach the sink at once, as the SurfaceHolder callback requires. The
 * loop owns all state, opens the take, reads frames and builds develop parameters per frame; the
 * sink completes renders on its own thread and the session hops back to the loop. Observations
 * reach [observe] through [deliver] and stop at [close].
 */
internal class RawPlaybackSession(
    settings: PlaybackSettings,
    private val openProvider: () -> RawFrameProvider,
    private val createSink: () -> RawFrameSink,
    private val loop: RawPlaybackLoop,
    private val clock: () -> Long,
    private val deliver: (() -> Unit) -> Unit,
    private val createAudio: (RawAudioFormat) -> RawAudioOutput? = { null },
    private val audioFocus: RawAudioFocus = RawAudioFocus.NONE,
    private val observe: (PlaybackObservation) -> Unit,
) : PlaybackController {
    override val retirement = CompletableFuture<Unit>()

    // Caller thread, read by the loop.
    @Volatile private var closed = false
    private val surfaceLock = Any()
    private var surface: Surface? = null // guarded by surfaceLock
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var attached: Surface? = null
    private var attachedWidth = 0
    private var attachedHeight = 0
    @Volatile private var sink: RawFrameSink? = null

    // Loop thread only from here on.
    private var options = settings
    private var provider: RawFrameProvider? = null
    private var colour: RawStaticColour? = null
    private var timeline: VideoFrameTimeline? = null
    private var timesUs = LongArray(0)
    private var intervalNs = DEFAULT_INTERVAL_NS
    private var state = PlaybackObservation()
    private var mode = if (settings.rawPlayback == RawPlaybackMode.FRAME_VIEWER) RawPlaybackRunMode.FRAMES else RawPlaybackRunMode.REALTIME
    private var autoFallback = settings.rawPlayback == RawPlaybackMode.AUTOMATIC
    private var fellBack = false
    private var failed = false

    private var playing = false
    private var clockStartNs = 0L
    private var anchorUs = 0L
    private var lastQueued = -1
    private var pending: Prepared? = null
    private var rendering: InFlight? = null
    private var timer: (() -> Unit)? = null
    private var slotsOut = 0
    private var shownIndex = 0
    private var lastGood = -1
    private var lastGoodQuality = RawDevelopQuality.FULL
    private var stillTarget: Int? = null
    private var atEnd = false
    private var dropped = 0
    /** Bumped at every real-time start, so a render from before a seek never moves the position back. */
    private var run = 0

    // Audio, the master clock while it plays.
    private var audioPlan: RawAudioPlan? = null
    private var audioOut: RawAudioOutput? = null
    private var feeder: RawAudioFeeder? = null
    private var carry: ByteBuffer? = null
    private var audioBaseUs = 0L
    private var feedTimer: (() -> Unit)? = null
    private var ownsFocus = false
    private var stallUs = -1L
    private var stallSinceNs = 0L

    // Fallback window.
    private var judged = false
    private val samples = ArrayList<Long>()
    private var windowShown = 0
    private var windowDropped = 0
    private var sampledNs = 0L
    private var playStartNs = 0L

    private var closing = false
    private var tornDown = false

    /** Most slots out at once; the pipeline keeps it at 2 in real time. */
    internal var peakSlotsOut = 0
        private set

    private sealed interface Prepared {
        val index: Int
        class Frame(override val index: Int, val slot: RawFrameSlot, val read: OcrawFrameRead.Frame, val readNs: Long) : Prepared
        class Missing(override val index: Int, val reason: OcrawMissingReason) : Prepared
    }

    /** [announce] false: a redraw of the previous good frame under a missing one; the observation keeps its index. */
    private class InFlight(val index: Int, val slot: RawFrameSlot, val quality: RawDevelopQuality, val realtime: Boolean,
        val readNs: Long, val announce: Boolean, val run: Int)

    init {
        loop.post { guarded { open() } }
    }

    // ---- PlaybackController (caller thread) ----

    override fun setSurface(surface: Surface?) {
        if (closed) return
        synchronized(surfaceLock) {
            if (this.surface === surface) return
            this.surface = surface
            if (surface == null) detachLocked()
        }
        loop.post { guarded {
            if (surface == null && playing) pauseInternal(redraw = true)
            attachIfReady()
            publish(state)
        } }
    }

    override fun setSurfaceSize(width: Int, height: Int) {
        if (closed) return
        synchronized(surfaceLock) { surfaceWidth = width; surfaceHeight = height }
        loop.post { guarded { attachIfReady() } }
    }

    override fun suspendOutput() = pause()

    override fun resumeOutput() {}

    override fun update(settings: PlaybackSettings) {
        if (closed) return
        loop.post { guarded {
            val previous = options
            options = settings
            if (settings.muted != previous.muted) {
                audioOut?.setMuted(settings.muted)
                if (settings.muted) releaseFocus()
                else if (audioOut != null && !takeFocus()) pauseInternal(redraw = true)
            }
            if (settings.rawPlayback != previous.rawPlayback) {
                if (settings.rawPlayback == RawPlaybackMode.FRAME_VIEWER) enterFrames(fallback = false)
                else if (mode == RawPlaybackRunMode.FRAMES && !fellBack) {
                    mode = RawPlaybackRunMode.REALTIME; autoFallback = true
                    publish(state)
                }
            }
        } }
    }

    override fun play() {
        if (closed) return
        loop.post { guarded { startPlaying() } }
    }

    override fun pause() {
        if (closed) return
        loop.post { guarded { if (playing) pauseInternal(redraw = true) } }
    }

    override fun seek(timeUs: Long) {
        if (closed) return
        loop.post { guarded {
            val line = timeline ?: return@guarded
            if (failed) return@guarded
            val target = nearestIndex(line, timeUs.coerceAtLeast(0))
            if (playing) {
                // Playing on from the new position, the sound restarting there.
                pauseInternal(redraw = false, keepFocus = true)
                shownIndex = target
                atEnd = false
                startPlaying()
                if (playing) return@guarded
                releaseFocus()
            }
            requestStill(target)
        } }
    }

    override fun step(delta: Int) {
        if (closed) return
        loop.post { guarded {
            val line = timeline ?: return@guarded
            if (failed || playing) return@guarded
            val base = stillTarget?.takeIf { it != CURRENT } ?: shownIndex
            requestStill(line.step(base, delta))
        } }
    }

    /**
     * Back to real time from the frame viewer, by the operator's choice: the automatic fall back is
     * off for the rest of this session, and playback starts when the Surface is there.
     */
    fun tryRealtime() {
        if (closed) return
        loop.post { guarded {
            if (failed) return@guarded
            autoFallback = false
            fellBack = false
            mode = RawPlaybackRunMode.REALTIME
            publish(state)
            startPlaying()
        } }
    }

    /**
     * Stops reading, waits for the frame on the GL stage, then releases the slots and closes the
     * source, the file and the GL stage, in that order; [retirement] completes after.
     */
    override fun close() {
        if (closed) return
        closed = true
        synchronized(surfaceLock) { surface = null; detachLocked() }
        loop.post { teardown() }
    }

    // ---- Loop thread ----

    private inline fun guarded(action: () -> Unit) {
        if (closed || closing) return
        try { action() } catch (failure: Exception) { fail(failure.message ?: failure.javaClass.simpleName) }
    }

    private fun open() {
        val source = openProvider()
        provider = source
        val index = source.index
        if (index.frames.isEmpty()) { fail(if (index.header == null) "Not an OpenCine RAW file" else "This RAW take has no frames"); return }
        timesUs = rawFrameTimesUs(index)
        intervalNs = rawFrameIntervalNs(index.metadata, timesUs)
        val line = VideoFrameTimeline(timesUs.asList())
        timeline = line
        colour = RawStaticColour.from(index.metadata)
        audioPlan = rawAudioPlan(index, timesUs)
        val size = index.frames.firstNotNullOfOrNull { it.header }
        sink = createSink()
        publish(state.copy(phase = PlaybackPhase.LOADING, timeline = line, durationUs = timesUs.last() + intervalNs / 1000,
            videoWidth = size?.width ?: 0, videoHeight = size?.height ?: 0, rawCodec = rawCodecLabel(index),
            rawAudioSampleRate = audioPlan?.format?.sampleRate))
        attachIfReady()
        stillTarget = 0
        pump()
    }

    private fun attachIfReady() {
        val target = sink ?: return
        synchronized(surfaceLock) {
            val next = surface ?: return
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            if (next === attached && surfaceWidth == attachedWidth && surfaceHeight == attachedHeight) return
            target.attach(next, surfaceWidth, surfaceHeight)
            attached = next; attachedWidth = surfaceWidth; attachedHeight = surfaceHeight
        }
    }

    private fun detachLocked() {
        if (attached != null) { attached = null; sink?.detach() }
    }

    private fun hasSurface() = synchronized(surfaceLock) { surface != null }

    private fun publish(value: PlaybackObservation) {
        if (closed) return
        val playable = mode == RawPlaybackRunMode.REALTIME && !failed && timeline != null && hasSurface() &&
            value.phase in PLAYABLE
        state = value.copy(canPlay = playable, rawMode = mode, droppedFrames = dropped, rawFallback = fellBack)
        val snapshot = state
        deliver { if (!closed) observe(snapshot) }
    }

    private fun fail(detail: String) {
        if (closed || closing) return
        failed = true
        playing = false
        cancelTimer()
        stopAudio()
        stillTarget = null
        releasePending()
        publish(state.copy(phase = PlaybackPhase.ERROR, detail = detail))
    }

    private fun startPlaying() {
        if (mode != RawPlaybackRunMode.REALTIME || playing || failed || timeline == null || !hasSurface()) return
        val start = if (atEnd) 0 else shownIndex
        anchorUs = timesUs[start]
        if (!startAudio()) return
        atEnd = false
        stillTarget = null
        clockStartNs = clock()
        playStartNs = clockStartNs
        lastQueued = start - 1
        dropped = 0
        run++
        playing = true
        publish(state.copy(phase = PlaybackPhase.PLAYING, missingFrame = null))
        pump()
    }

    /** Stops the clock; [redraw] shows whatever frame ends up on screen again at full resolution. */
    private fun pauseInternal(redraw: Boolean, keepFocus: Boolean = false) {
        playing = false
        cancelTimer()
        stopAudio(keepFocus)
        sampledNs += clock() - playStartNs
        releasePending()
        publish(state.copy(phase = PlaybackPhase.PAUSED))
        if (redraw) { stillTarget = CURRENT; pump() }
    }

    private fun enterFrames(fallback: Boolean) {
        if (playing) pauseInternal(redraw = true)
        mode = RawPlaybackRunMode.FRAMES
        fellBack = fallback
        publish(state)
    }

    private fun requestStill(index: Int) {
        atEnd = false
        stillTarget = index
        publish(state.copy(phase = PlaybackPhase.SEEKING, positionUs = timesUs[index]))
        pump()
    }

    private fun pump() {
        if (closing || failed || provider == null) return
        if (playing) pumpRealtime() else pumpStill()
    }

    private fun pumpStill() {
        if (rendering != null) return
        val target = stillTarget ?: return
        stillTarget = null
        val index = if (target == CURRENT) shownIndex else target
        when (val prepared = readFrame(index)) {
            is Prepared.Frame -> submit(prepared, RawDevelopQuality.FULL, realtime = false, announce = true)
            is Prepared.Missing -> {
                shownIndex = index
                publish(state.copy(phase = stillPhase(), frameIndex = index, positionUs = timesUs[index], missingFrame = prepared.reason))
                // The previous image stays up; bring it to full resolution if real time left it at half.
                if (lastGood >= 0 && lastGoodQuality == RawDevelopQuality.HALF) {
                    val good = readFrame(lastGood)
                    if (good is Prepared.Frame) submit(good, RawDevelopQuality.FULL, realtime = false, announce = false)
                }
            }
            null -> stillTarget = target
        }
    }

    private fun pumpRealtime() {
        val last = timesUs.lastIndex
        while (playing) {
            if (pending == null && slotsOut < PIPELINE_DEPTH) {
                val next = maxOf(lastQueued + 1, dueIndex())
                if (next > last) {
                    if (rendering == null) finishOrWrap()
                    return
                }
                val skipped = next - lastQueued - 1
                dropped += skipped
                if (!judged) windowDropped += skipped
                lastQueued = next
                pending = readFrame(next) ?: return
                continue
            }
            val prepared = pending ?: return
            if (rendering != null) return
            val wait = waitNs(timesUs[prepared.index])
            if (wait > 0) { schedule(wait); return }
            pending = null
            if (!judged) windowShown++
            when (prepared) {
                is Prepared.Frame -> submit(prepared, RawDevelopQuality.HALF, realtime = true, announce = true)
                is Prepared.Missing -> {
                    shownIndex = prepared.index
                    publish(state.copy(frameIndex = prepared.index, positionUs = timesUs[prepared.index], missingFrame = prepared.reason))
                }
            }
        }
    }

    private fun finishOrWrap() {
        val wait = waitNs(timesUs.last() + intervalNs / 1000)
        if (wait > 0 && !audioStalledAtEnd()) { schedule(minOf(wait, END_POLL_NS)); return }
        if (options.loop) {
            anchorUs = timesUs[0]
            stopAudio(keepFocus = true)
            startAudio()
            clockStartNs = clock()
            lastQueued = -1
            run++
            pumpRealtime()
            return
        }
        playing = false
        stopAudio()
        sampledNs += clock() - playStartNs
        atEnd = true
        publish(state.copy(phase = PlaybackPhase.ENDED))
        stillTarget = CURRENT
        pump()
    }

    /** Reads [index] into a fresh slot; null when no slot is free (the caller retries after a render). */
    private fun readFrame(index: Int): Prepared? {
        val source = checkNotNull(provider)
        val slot = source.acquire() ?: return null
        slotsOut++
        peakSlotsOut = maxOf(peakSlotsOut, slotsOut)
        val started = clock()
        val read = try { source.read(index, slot) } catch (failure: Exception) { releaseSlot(slot); throw failure }
        val readNs = clock() - started
        return when (read) {
            is OcrawFrameRead.Frame -> Prepared.Frame(index, slot, read, readNs)
            is OcrawFrameRead.Missing -> { releaseSlot(slot); Prepared.Missing(index, read.reason) }
        }
    }

    private fun submit(prepared: Prepared.Frame, quality: RawDevelopQuality, realtime: Boolean, announce: Boolean) {
        val read = prepared.read
        val params = RawDevelop.params(checkNotNull(colour), read.metadata, read.neutral?.let { RawDevelop.parseNumbers(it, 3) })
        val flight = InFlight(prepared.index, prepared.slot, quality, realtime, prepared.readNs, announce, run)
        val target = sink
        rendering = flight
        if (target == null) { rendered(flight, 0L, false); return }
        target.render(prepared.slot.buffer, read.header, params, quality) { renderNanos, drawn ->
            loop.post { rendered(flight, renderNanos, drawn) }
        }
    }

    private fun rendered(flight: InFlight, renderNanos: Long, drawn: Boolean) {
        if (rendering !== flight) return
        rendering = null
        releaseSlot(flight.slot)
        if (closing) { finishTeardown(); return }
        if (failed || closed) return
        guarded {
            if (flight.announce) {
                lastGood = flight.index
                lastGoodQuality = flight.quality
            } else if (flight.index == lastGood) lastGoodQuality = flight.quality
            val now = clock()
            if (flight.realtime && flight.run != run) {
                // Drawn for the position before a seek: the new run has moved on.
            } else if (flight.realtime) {
                shownIndex = flight.index
                if (playing) publish(state.copy(frameIndex = flight.index, positionUs = timesUs[flight.index], missingFrame = null,
                    renderedAtNs = if (drawn) now else state.renderedAtNs))
                if (playing && !judged) {
                    samples += flight.readNs + renderNanos
                    if (judgeFallback(now)) return@guarded
                }
            } else if (flight.announce) {
                shownIndex = flight.index
                if (!playing && stillTarget == null) publish(state.copy(phase = stillPhase(), frameIndex = flight.index,
                    positionUs = timesUs[flight.index], missingFrame = null, renderedAtNs = if (drawn) now else state.renderedAtNs))
            } else if (drawn && !playing) publish(state.copy(renderedAtNs = now))
            pump()
        }
    }

    /** True when it fell back (and has already moved on). */
    private fun judgeFallback(now: Long): Boolean {
        if (!autoFallback) { judged = true; return false }
        if (sampledNs + (now - playStartNs) < RAW_FALLBACK_WINDOW_NS || samples.size < RAW_FALLBACK_MIN_SAMPLES) return false
        judged = true
        if (!rawPlaybackShouldFallBack(samples, intervalNs, windowShown, windowDropped)) return false
        enterFrames(fallback = true)
        return true
    }

    private fun stillPhase() = if (atEnd) PlaybackPhase.ENDED else PlaybackPhase.PAUSED

    /** Take time now: the audio's played position while it plays, else the wall clock from the anchor. */
    private fun nowUs(): Long {
        val out = audioOut
        val plan = audioPlan
        if (out != null && plan != null) return audioBaseUs + plan.timeUsOf(out.playedFrames())
        return anchorUs + (clock() - clockStartNs) / 1000
    }

    private fun dueIndex(): Int = checkNotNull(timeline).indexAt(nowUs())

    private fun waitNs(timeUs: Long): Long = (timeUs - nowUs()) * 1000

    /**
     * True when every sample is written and the played position has stood still for [AUDIO_STALL_NS]: an
     * output that stops reporting short of the end never holds the take open.
     */
    private fun audioStalledAtEnd(): Boolean {
        if (audioOut == null || feeder?.exhausted != true) return false
        val now = nowUs()
        if (now != stallUs) { stallUs = now; stallSinceNs = clock(); return false }
        return clock() - stallSinceNs >= AUDIO_STALL_NS
    }

    // ---- Audio (loop thread) ----

    /** Starts the sound at [anchorUs] when the take has some; false only when the focus was refused. */
    private fun startAudio(): Boolean {
        val plan = audioPlan ?: return true
        if (!options.muted && !takeFocus()) return false
        val out = try { createAudio(plan.format) } catch (_: Exception) { null } ?: return true
        val source = checkNotNull(provider)
        audioOut = out
        audioBaseUs = anchorUs
        stallUs = -1L
        // A little past the last frame's end, so the played position always reaches it.
        val end = plan.sampleAt(timesUs.last() + intervalNs / 1000) + plan.format.sampleRate / 10
        feeder = RawAudioFeeder(plan, plan.sampleAt(anchorUs), end) { chunk, dst -> source.readAudio(chunk, dst) }
        carry = null
        out.setMuted(options.muted)
        feedAudio()
        out.play()
        scheduleFeed()
        return true
    }

    private fun feedAudio() {
        val out = audioOut ?: return
        val source = feeder ?: return
        while (true) {
            val block = carry?.takeIf { it.hasRemaining() } ?: source.next() ?: break
            carry = block
            out.write(block)
            if (block.hasRemaining()) break
        }
    }

    private fun scheduleFeed() {
        feedTimer?.invoke()
        feedTimer = loop.postDelayed(AUDIO_FEED_NS) {
            feedTimer = null
            guarded { if (audioOut != null) { feedAudio(); scheduleFeed() } }
        }
    }

    private fun stopAudio(keepFocus: Boolean = false) {
        feedTimer?.invoke(); feedTimer = null
        val out = audioOut
        audioOut = null; feeder = null; carry = null
        try { out?.release() } finally { if (!keepFocus) releaseFocus() }
    }

    private fun takeFocus(): Boolean {
        if (options.muted || ownsFocus) return true
        ownsFocus = audioFocus.request { pause() }
        return ownsFocus
    }

    private fun releaseFocus() {
        if (ownsFocus) { ownsFocus = false; audioFocus.abandon() }
    }

    private fun schedule(delayNs: Long) {
        cancelTimer()
        timer = loop.postDelayed(delayNs) { timer = null; guarded { pump() } }
    }

    private fun cancelTimer() { timer?.invoke(); timer = null }

    private fun releaseSlot(slot: RawFrameSlot) {
        provider?.release(slot)
        slotsOut--
    }

    private fun releasePending() {
        (pending as? Prepared.Frame)?.let { releaseSlot(it.slot) }
        pending = null
    }

    private fun teardown() {
        if (closing) return
        closing = true
        playing = false
        cancelTimer()
        stopAudio()
        stillTarget = null
        releasePending()
        if (rendering == null) finishTeardown()
        // The GL stage owns a slot until its render completes; never wait for it forever.
        else loop.postDelayed(TEARDOWN_TIMEOUT_NS) { finishTeardown() }
    }

    private fun finishTeardown() {
        if (tornDown) return
        tornDown = true
        val failures = ArrayList<Throwable>()
        rendering?.let { rendering = null; runCatching { releaseSlot(it.slot) } }
        runCatching { provider?.close() }.onFailure(failures::add)
        provider = null
        runCatching { sink?.release() }.onFailure(failures::add)
        sink = null
        if (failures.isEmpty()) retirement.complete(Unit) else retirement.completeExceptionally(failures.first())
        loop.quit()
    }

    internal companion object {
        const val PIPELINE_DEPTH = 2
        const val DEFAULT_INTERVAL_NS = 1_000_000_000L / 30
        const val TEARDOWN_TIMEOUT_NS = 2_000_000_000L
        /** The audio output is topped up this often; it holds about a second. */
        const val AUDIO_FEED_NS = 50_000_000L
        /** The longest wait at the end before the clock is checked again. */
        const val END_POLL_NS = 50_000_000L
        /** A played position standing still this long after the last sample counts as the end. */
        const val AUDIO_STALL_NS = 250_000_000L
        private const val CURRENT = -1
        private val PLAYABLE = setOf(PlaybackPhase.PAUSED, PlaybackPhase.ENDED, PlaybackPhase.PLAYING)

        /** The production session: the take opened read-only through [ContentResolver], the GL stage, a reader thread. */
        fun open(context: Context, artifact: LocalMediaArtifact, settings: PlaybackSettings,
            observe: (PlaybackObservation) -> Unit): RawPlaybackSession {
            val resolver = context.applicationContext.contentResolver
            val uri = artifact.uri.toUri()
            val main = Handler(Looper.getMainLooper())
            return RawPlaybackSession(
                settings = settings,
                openProvider = { OcrawFileFrameProvider.open(resolver, uri) },
                createSink = { RendererSink(RawPlaybackRenderer()) },
                loop = HandlerPlaybackLoop("ocraw-review-reader"),
                clock = SystemClock::elapsedRealtimeNanos,
                deliver = { action -> main.post(action) },
                createAudio = { format -> AudioTrackOutput(format) },
                audioFocus = AudioManagerFocus(context.applicationContext, main),
                observe = observe,
            )
        }

        /** Nearest frame to [timeUs] (ties go to the earlier). */
        internal fun nearestIndex(line: VideoFrameTimeline, timeUs: Long): Int {
            val floor = line.indexAt(timeUs)
            val times = line.timestampsUs
            if (floor >= times.lastIndex || timeUs <= times[floor]) return floor
            return if (times[floor + 1] - timeUs < timeUs - times[floor]) floor + 1 else floor
        }
    }
}

/**
 * Per-frame take time in microseconds from the first frame, strictly increasing so it can back a
 * [VideoFrameTimeline]. Sensor timestamps lead; a frame with an unreadable header sits between its
 * neighbours. With no readable header at all, the chunk ticks and the time base stand in.
 */
internal fun rawFrameTimesUs(index: OcrawFrameIndex): LongArray {
    val frames = index.frames
    val count = frames.size
    val known = LongArray(count) { -1 }
    val anyHeader = frames.any { it.header != null }
    val base = index.timeBase
    for (i in 0 until count) {
        val header = frames[i].header
        known[i] = when {
            header != null -> header.sensorTimestampNs
            !anyHeader && base != null -> frames[i].timestampTicks * base.numerator * 1_000_000_000L / base.denominator
            else -> -1
        }
    }
    val result = LongArray(count)
    val first = (0 until count).firstOrNull { known[it] >= 0 }
    if (first == null) {
        for (i in 0 until count) result[i] = i * (RawPlaybackSession.DEFAULT_INTERVAL_NS / 1000)
        return result
    }
    val origin = known[first]
    var previous = Long.MIN_VALUE
    for (i in 0 until count) {
        var us = if (known[i] >= 0) (known[i] - origin) / 1000 else {
            val before = (i - 1 downTo 0).firstOrNull { known[it] >= 0 }
            val after = (i + 1 until count).firstOrNull { known[it] >= 0 }
            when {
                before != null && after != null ->
                    (known[before] - origin) / 1000 + ((known[after] - known[before]) / 1000) * (i - before) / (after - before)
                before != null -> (known[before] - origin) / 1000 + (i - before) * (RawPlaybackSession.DEFAULT_INTERVAL_NS / 1000)
                else -> -(after!! - i) * (RawPlaybackSession.DEFAULT_INTERVAL_NS / 1000)
            }
        }
        if (us <= previous) us = previous + 1
        result[i] = us
        previous = us
    }
    // A leading unreadable frame would sit before zero; shift the take to start at zero.
    val shift = result[0]
    if (shift != 0L) for (i in 0 until count) result[i] -= shift
    return result
}

/** One frame interval: the take's `fps`, else the median timestamp step, else 1/30 s. */
internal fun rawFrameIntervalNs(metadata: Map<String, String>, timesUs: LongArray): Long {
    metadata["fps"]?.let(RawDevelop::parseNumber)?.takeIf { it > 0 && it.isFinite() }?.let { return (1e9 / it).toLong() }
    if (timesUs.size >= 2) {
        val steps = (1 until timesUs.size).map { timesUs[it] - timesUs[it - 1] }.sorted()
        return steps[steps.size / 2] * 1000
    }
    return RawPlaybackSession.DEFAULT_INTERVAL_NS
}

/** "RAW10", "LJ92", both joined for a mixed take, or null when no header is readable. */
internal fun rawCodecLabel(index: OcrawFrameIndex): String? {
    val encodings = index.frames.mapNotNullTo(sortedSetOf()) { it.header?.encoding }
    if (encodings.isEmpty()) return null
    return encodings.joinToString("+") {
        when (it) {
            OcrawFrameHeader.ENCODING_RAW10 -> "RAW10"
            OcrawFrameHeader.ENCODING_LJ92 -> "LJ92"
            OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK -> "BBP"
            OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED -> "BBPQ"
            else -> "0x%04x".format(it)
        }
    }
}

/** [OcrawFrameSource] over a read-only descriptor; closing it closes the source, the channel, then the descriptor. */
internal class OcrawFileFrameProvider private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val channel: FileChannel,
    private val source: OcrawFrameSource,
) : RawFrameProvider {
    // Its own reader: audio reads share the loop thread with frame reads, never a read position.
    private val audioReader by lazy { OcrawStreamReader(channel, scratchBytes = OcrawFrameHeader.BYTES) }
    override fun readAudio(chunk: OcrawAudioChunk, dst: ByteBuffer): Int = audioReader.readAudioChunk(chunk.fileOffset, dst)
    override val index: OcrawFrameIndex get() = source.index
    override fun acquire(): RawFrameSlot? = source.acquire()?.let { RawFrameSlot(it.buffer, it) }
    override fun release(slot: RawFrameSlot) = source.release(slot.handle as OcrawFrameSource.Slot)
    override fun read(frameIndex: Int, slot: RawFrameSlot): OcrawFrameRead = source.read(frameIndex, slot.handle as OcrawFrameSource.Slot)
    override fun close() {
        try { source.close() } finally { try { channel.close() } finally { descriptor.close() } }
    }

    companion object {
        fun open(resolver: ContentResolver, uri: Uri): OcrawFileFrameProvider {
            val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "r")) { "RAW take could not be opened" }
            try {
                // The stream owns a duplicate, so the channel and the descriptor close independently.
                val channel = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).channel
                try {
                    val index = OcrawStreamReader(channel, scratchBytes = OcrawFrameHeader.BYTES).indexFrames()
                    return OcrawFileFrameProvider(descriptor, channel, OcrawFrameSource(channel, index))
                } catch (failure: Throwable) { channel.close(); throw failure }
            } catch (failure: Throwable) { descriptor.close(); throw failure }
        }
    }
}

/** [RawPlaybackRenderer] as the session's sink. */
private class RendererSink(private val renderer: RawPlaybackRenderer) : RawFrameSink {
    override fun attach(surface: Surface, width: Int, height: Int) = renderer.attach(surface, width, height)
    override fun detach() = renderer.detach()
    override fun render(buffer: ByteBuffer, header: OcrawFrameHeader, params: RawDevelopParams, quality: RawDevelopQuality,
        onDone: (renderNanos: Long, drawn: Boolean) -> Unit) = renderer.render(buffer, header, params, quality, onDone)
    override fun release() = renderer.release()
}

/** A [HandlerThread] as the session loop. */
private class HandlerPlaybackLoop(name: String) : RawPlaybackLoop {
    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)
    override fun post(task: () -> Unit) { handler.post(task) }
    override fun postDelayed(delayNs: Long, task: () -> Unit): () -> Unit {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, (delayNs + 999_999) / 1_000_000)
        return { handler.removeCallbacks(runnable) }
    }
    override fun quit() { thread.quitSafely() }
}
