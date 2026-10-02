/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.core.net.toUri
import com.librestatic.opencinecam.storage.*
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal enum class PlaybackPhase { LOADING, PAUSED, PLAYING, SEEKING, ENDED, ERROR, CLOSED }
internal data class PlaybackObservation(
    val phase: PlaybackPhase = PlaybackPhase.LOADING,
    val bitmap: Bitmap? = null,
    val timeline: VideoFrameTimeline? = null,
    val frameIndex: Int? = null,
    val positionUs: Long = 0,
    val durationUs: Long = 0,
    val detail: String? = null,
    val videoWidth: Int = 0, val videoHeight: Int = 0,
    val canPlay: Boolean = false,
    val trackColorAvailable: Boolean = false,
    val frameColor: PreciseVideoColor? = null,
    val hdrPreview: PreciseHdrPreview? = null,
    val nativeHdrTransfer: PreciseHdrTransfer? = null,
    val renderedAtNs: Long? = null,
)

/** Native player sizes already include SAR/rotation. A paused exact frame owns its geometry. */
internal fun PlaybackObservation.withPlayerGeometry(width: Int, height: Int): PlaybackObservation =
    if (width > 0 && height > 0 && (phase == PlaybackPhase.PLAYING || frameIndex == null && bitmap == null))
        copy(videoWidth = width, videoHeight = height) else this

/** Main-thread player; one serial, interruptible readonly decoder. Continuous A/V time is
 * a player estimate. Only a decoded buffer's PTS is reported as a verified frame position. */
internal class MediaPlaybackSession(
    context: Context, val artifact: LocalMediaArtifact, settings: PlaybackSettings,
    private val colorPolicy: PreciseVideoColorPolicy = PreciseVideoColorPolicy.STRICT,
    private val nativeSurfaceFrames: Boolean = false,
    /** Sidecar-declared OCLog2 signal; exact CPU frames then honour [PlaybackSettings.logView]. */
    private val log: PreciseLogSignal? = null,
    private val observe: (PlaybackObservation) -> Unit,
) : Closeable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "media-review-reader") }
    val retirement = java.util.concurrent.CompletableFuture<Unit>()
    private var task: Future<*>? = null
    private var frames: PreciseVideoFrames? = null // worker-thread only
    @Volatile private var closed = false
    private var generation = 0L
    private var options = settings
    @Volatile private var logView = settings.logView
    private var player: MediaPlayer? = null
    private var prepared = false
    private var playerWidth = 0
    private var playerHeight = 0
    private var nativeSeeking = false
    private var seekTicket = 0L
    private var hasSurface = false
    private var outputSurface: Surface? = null
    private var suspended = false
    private var pendingFrameIndex = 0
    private var seekToPlay = false
    private var pendingAudioSeek = false
    private var state = PlaybackObservation()
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(audioAttributes).setOnAudioFocusChangeListener({ change ->
            if (change < 0 && !closed) pause()
        }, main).build()
    private var ownsFocus = false
    private val video = artifact.mimeType.startsWith("video/")
    private val photo = artifact.mimeType.startsWith("image/")

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        openSessions++
        if (photo) read {
            val bitmap = if (artifact.mimeType.equals("image/x-adobe-dng", ignoreCase = true))
                decodeDngPhotoPreview(this.context.contentResolver, artifact.uri.toUri())
            else ImageDecoder.decodeBitmap(ImageDecoder.createSource(this.context.contentResolver, artifact.uri.toUri())) { decoder, info, _ ->
                val edge = maxOf(info.size.width, info.size.height)
                if (edge > 2048) decoder.setTargetSampleSize((edge + 2047) / 2048)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            };
            { publish(state.copy(phase = PlaybackPhase.PAUSED, bitmap = bitmap)) }
        } else {
            try {
                if (!video || !nativeSurfaceFrames) preparePlayer()
                if (video) read {
                    val reader = PreciseVideoFrames(this.context, artifact.uri, colorPolicy, requireCpuPreview = !nativeSurfaceFrames, log = log)
                    reader.logView = logView
                    frames = reader
                    if (nativeSurfaceFrames) {
                        { publish(state.copy(timeline = reader.timeline, durationUs = reader.timeline.timestampsUs.last(),
                            videoWidth = reader.displayWidth, videoHeight = reader.displayHeight, nativeHdrTransfer = reader.hdrTransfer))
                          if (hasSurface && !suspended) decode(0) }
                    } else {
                        val frame = reader.frame(0);
                        { publish(state.copy(phase = if (prepared) PlaybackPhase.PAUSED else PlaybackPhase.LOADING, timeline = reader.timeline,
                            videoWidth = frame.displayWidth, videoHeight = frame.displayHeight,
                            frameIndex = frame.index, positionUs = frame.presentationTimeUs, bitmap = frame.bitmap, frameColor = frame.color, hdrPreview = frame.hdrPreview)) }
                    }
                }
            } catch (failure: Exception) { fail(failure.message) }
        }
    }

    /** Called only after the serial exact decoder has retired its Surface producer. */
    private fun preparePlayer(startAt: Long? = null) {
        check(player == null)
        val media = MediaPlayer()
        player = media
        media.also {
            media.setAudioAttributes(audioAttributes)
            media.setVolume(if (options.muted) 0f else 1f, if (options.muted) 0f else 1f)
            media.setOnVideoSizeChangedListener { _, width, height ->
                if (!closed && player === media) {
                    playerWidth = width; playerHeight = height
                    publish(state.withPlayerGeometry(width, height))
                }
            }
            media.setOnPreparedListener {
                if (!closed && player === media && state.phase != PlaybackPhase.ERROR) {
                    prepared = true
                    val duration = media.duration.toLong().coerceAtLeast(0) * 1000
                    publish(state.copy(durationUs = duration,
                        phase = if (!video || state.timeline != null && state.phase == PlaybackPhase.LOADING) PlaybackPhase.PAUSED else state.phase))
                    if (startAt != null) {
                        try { seekToPlay = true; seekNative(startAt) }
                        catch (failure: Exception) { fail(failure.message) }
                    }
                }
            }
            media.setOnErrorListener { _, what, extra -> if (!closed && player === media) fail("MediaPlayer $what/$extra"); true }
            media.setOnSeekCompleteListener {
                if (closed || player !== media) return@setOnSeekCompleteListener
                nativeSeeking = false; ++seekTicket
                if (seekToPlay) {
                    seekToPlay = false
                    if (video && !hasSurface || !requestFocus()) { fail("Playback output is no longer available"); return@setOnSeekCompleteListener }
                    try { media.start(); publish(state.copy(phase = PlaybackPhase.PLAYING, bitmap = null, frameIndex = null).withPlayerGeometry(playerWidth, playerHeight)); tick() }
                    catch (failure: Exception) { fail(failure.message) }
                } else if (!closed && pendingAudioSeek) {
                    pendingAudioSeek = false
                    publish(state.copy(phase = PlaybackPhase.PAUSED, positionUs = media.currentPosition.toLong() * 1000))
                } else publish(state)
            }
            media.setOnCompletionListener {
                // Native completion can already be queued when lifecycle/user pause wins.
                // Only a still-playing owner may loop; late completion must not restart it.
                if (!closed && player === media && state.phase == PlaybackPhase.PLAYING) {
                    releaseFocus()
                    if (options.loop) {
                        publish(state.copy(positionUs = 0))
                        if (nativeSurfaceFrames) {
                            try { seekToPlay = true; seekNative(0) } catch (failure: Exception) { fail(failure.message) }
                        } else play()
                    } else if (video) decode(state.timeline?.timestampsUs?.lastIndex ?: 0, ended = true)
                    else publish(state.copy(phase = PlaybackPhase.ENDED, positionUs = state.durationUs))
                }
            }
            media.setDataSource(this.context, artifact.uri.toUri())
            media.setSurface(outputSurface)
            media.prepareAsync()
            main.postDelayed({ if (!closed && player === media && !prepared) fail("Media preparation exceeded 30 seconds") }, 30_000)
        }
    }

    private fun retirePlayer() {
        player?.release(); player = null; prepared = false; playerWidth = 0; playerHeight = 0
        nativeSeeking = false; seekToPlay = false; pendingAudioSeek = false; ++seekTicket
        main.removeCallbacks(ticker)
    }

    private fun publish(value: PlaybackObservation) {
        if (!closed) {
            state = value.copy(canPlay = !nativeSeeking && value.phase != PlaybackPhase.ERROR &&
                if (video && nativeSurfaceFrames) !suspended && hasSurface && value.timeline != null && value.phase in setOf(PlaybackPhase.PAUSED, PlaybackPhase.ENDED, PlaybackPhase.PLAYING)
                else prepared && (!video || hasSurface && value.timeline != null))
            observe(state)
        }
    }
    private fun pauseNative() { if (prepared && player?.isPlaying == true) player?.pause() }
    private fun seekNative(timeUs: Long) {
        check(!nativeSeeking)
        nativeSeeking = true
        val ticket = ++seekTicket
        publish(state.copy(phase = PlaybackPhase.SEEKING))
        player?.seekTo(timeUs / 1000, MediaPlayer.SEEK_CLOSEST)
        main.postDelayed({ if (!closed && nativeSeeking && ticket == seekTicket) fail("Media seek exceeded 30 seconds") }, 30_000)
    }
    private fun fail(detail: String?, trackColorAvailable: Boolean = false) {
        if (closed) return
        seekToPlay = false; pendingAudioSeek = false
        ++generation; task?.cancel(true)
        player?.release(); player = null; prepared = false; nativeSeeking = false; ++seekTicket
        releaseFocus()
        publish(state.copy(phase = PlaybackPhase.ERROR, frameIndex = null, detail = detail, trackColorAvailable = trackColorAvailable))
    }
    private fun read(action: () -> (() -> Unit)) {
        val ticket = ++generation
        task?.cancel(true)
        task = worker.submit {
            try {
                if (closed) return@submit
                val result = action()
                main.post { if (!closed && ticket == generation) result() }
            } catch (failure: Exception) {
                main.post { if (!closed && ticket == generation) fail(failure.message, (failure as? PreciseVideoColorException)?.trackSdrAvailable == true) }
            }
        }
    }
    private fun decode(index: Int, ended: Boolean = false) {
        if (closed) return
        seekToPlay = false
        runCatching { pauseNative() }
        releaseFocus()
        if (nativeSurfaceFrames && video) {
            pendingFrameIndex = index
            retirePlayer()
            val surface = outputSurface
            publish(state.copy(phase = PlaybackPhase.SEEKING, positionUs = state.timeline!!.timestampsUs[index], bitmap = null, frameIndex = null, renderedAtNs = null))
            if (surface == null || suspended) return
            read {
                val frame = checkNotNull(frames).frameToSurface(index, surface);
                { if (outputSurface === surface && surface.isValid) publish(state.copy(
                    phase = if (ended) PlaybackPhase.ENDED else PlaybackPhase.PAUSED,
                    videoWidth = frame.displayWidth, videoHeight = frame.displayHeight,
                    frameIndex = frame.index, positionUs = frame.presentationTimeUs, renderedAtNs = frame.renderedAtNs)) }
            }
            return
        }
        publish(state.copy(phase = PlaybackPhase.SEEKING))
        read {
            val frame = checkNotNull(frames).also { it.logView = logView }.frame(index);
            { publish(state.copy(phase = if (ended) PlaybackPhase.ENDED else PlaybackPhase.PAUSED,
                videoWidth = frame.displayWidth, videoHeight = frame.displayHeight,
                            frameIndex = frame.index, positionUs = frame.presentationTimeUs, bitmap = frame.bitmap, frameColor = frame.color, hdrPreview = frame.hdrPreview)) }
        }
    }
    fun setSurface(surface: Surface?) {
        if (closed || photo) return
        if (outputSurface === surface) return
        if (video && nativeSurfaceFrames) {
            val position = if (prepared) runCatching { player!!.currentPosition.toLong() * 1000 }.getOrDefault(state.positionUs) else state.positionUs
            // Initial indexing owns no Surface producer; do not cancel it on first attachment.
            if (state.timeline != null) { ++generation; task?.cancel(true) }
            retirePlayer(); releaseFocus()
            outputSurface = surface; hasSurface = surface?.isValid == true
            if (state.phase == PlaybackPhase.ERROR) { publish(state); return }
            publish(state.copy(phase = PlaybackPhase.LOADING, positionUs = position, frameIndex = null, renderedAtNs = null))
            if (hasSurface && !suspended && state.timeline != null) decode(state.timeline!!.indexAt(position))
        } else {
            if (surface == null && state.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.SEEKING)) pause()
            outputSurface = surface; hasSurface = surface?.isValid == true
            try { player?.setSurface(surface); publish(state) } catch (failure: Exception) { fail(failure.message) }
        }
    }
    /** Lifecycle suspension retires output without replay; resumption restores only an exact still. */
    fun suspendOutput() {
        if (closed) return
        if (!video || !nativeSurfaceFrames) { pause(); return }
        suspended = true
        if (prepared) {
            val position = runCatching { player!!.currentPosition.toLong() * 1000 }.getOrDefault(state.positionUs)
            pendingFrameIndex = state.timeline?.indexAt(position) ?: 0
        }
        if (state.timeline != null) { ++generation; task?.cancel(true) }
        retirePlayer(); releaseFocus()
        if (state.phase != PlaybackPhase.ERROR) publish(state.copy(phase = PlaybackPhase.PAUSED, frameIndex = null, renderedAtNs = null))
    }
    fun resumeOutput() {
        if (closed || !video || !nativeSurfaceFrames) return
        val wasSuspended = suspended; suspended = false
        if (wasSuspended && state.phase != PlaybackPhase.ERROR && hasSurface && state.timeline != null) decode(pendingFrameIndex)
    }
    fun update(settings: PlaybackSettings) {
        if (closed) return
        options = settings
        if (log != null && logView != settings.logView) {
            logView = settings.logView
            // Native frames go through the review GL stage, which switches views itself.
            val shown = state.frameIndex
            if (!nativeSurfaceFrames && shown != null && state.phase in setOf(PlaybackPhase.PAUSED, PlaybackPhase.ENDED))
                decode(shown, ended = state.phase == PlaybackPhase.ENDED)
        }
        try { player?.setVolume(if (settings.muted) 0f else 1f, if (settings.muted) 0f else 1f) }
        catch (failure: Exception) { fail(failure.message); return }
        if (settings.muted) releaseFocus()
        else if (state.phase == PlaybackPhase.PLAYING && !requestFocus()) pause()
    }
    private fun requestFocus(): Boolean {
        if (options.muted || ownsFocus) return true
        ownsFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return ownsFocus
    }
    private fun releaseFocus() { if (ownsFocus) { audio.abandonAudioFocusRequest(focus); ownsFocus = false } }
    fun play() {
        if (!closed && video && nativeSurfaceFrames) {
            if (suspended || !state.canPlay || state.phase !in setOf(PlaybackPhase.PAUSED, PlaybackPhase.ENDED)) return
            val position = if (state.phase == PlaybackPhase.ENDED) 0 else state.positionUs
            publish(state.copy(phase = PlaybackPhase.SEEKING, frameIndex = null, renderedAtNs = null))
            // Serial barrier: cancellation finishes codec.stop/release before MediaPlayer connects.
            read { { if (hasSurface) {
                if (!requestFocus()) fail("Audio focus not granted")
                else try { preparePlayer(position) } catch (failure: Exception) { fail(failure.message) }
            } } }
            return
        }
        if (closed || photo || !prepared || nativeSeeking || video && (!hasSurface || state.timeline == null) || state.phase == PlaybackPhase.ERROR) return
        if (!requestFocus()) { fail("Audio focus not granted"); return }
        ++generation; task?.cancel(true)
        try {
            seekToPlay = true
            val position = if (state.phase == PlaybackPhase.ENDED) 0 else state.positionUs
            seekNative(position)
        } catch (failure: Exception) { fail(failure.message) }
    }
    fun pause() {
        if (!closed && video && nativeSurfaceFrames && !prepared && state.phase == PlaybackPhase.SEEKING) {
            ++generation; task?.cancel(true); retirePlayer(); releaseFocus()
            publish(state.copy(phase = PlaybackPhase.PAUSED, frameIndex = null, renderedAtNs = null))
            return
        }
        if (closed || photo || !prepared || state.phase !in setOf(PlaybackPhase.PLAYING, PlaybackPhase.SEEKING)) return
        seekToPlay = false
        try {
            pendingAudioSeek = false
            pauseNative()
            val time = player?.currentPosition?.toLong()?.times(1000) ?: state.positionUs
            releaseFocus()
            if (video && state.timeline != null) decode(state.timeline!!.indexAt(time))
            else publish(state.copy(phase = PlaybackPhase.PAUSED, positionUs = time))
        } catch (failure: Exception) { fail(failure.message) }
    }
    fun seek(timeUs: Long) {
        if (closed || photo || !prepared && !nativeSurfaceFrames || state.phase == PlaybackPhase.ERROR || !video && nativeSeeking) return
        if (video) state.timeline?.let { decode(it.indexAt(timeUs.coerceAtLeast(0))) }
        else try {
            seekToPlay = false; pauseNative(); releaseFocus(); pendingAudioSeek = true
            seekNative(timeUs.coerceIn(0, state.durationUs))
        } catch (failure: Exception) { fail(failure.message) }
    }
    fun step(delta: Int) {
        val timeline = state.timeline ?: return
        if (state.phase !in setOf(PlaybackPhase.PAUSED, PlaybackPhase.ENDED)) return
        decode(timeline.step(state.frameIndex ?: timeline.indexAt(state.positionUs), delta))
    }
    private val ticker = Runnable { tick() }
    private fun tick() {
        main.removeCallbacks(ticker)
        if (closed || state.phase != PlaybackPhase.PLAYING) return
        try { publish(state.copy(positionUs = player?.currentPosition?.toLong()?.times(1000) ?: 0)) }
        catch (failure: Exception) { fail(failure.message); return }
        main.postDelayed(ticker, 100)
    }
    override fun close() {
        if (closed) return
        check(Looper.myLooper() == Looper.getMainLooper())
        closed = true; ++generation; task?.cancel(true)
        openSessions--; lastRetirement = retirement
        main.removeCallbacksAndMessages(null)
        player?.release(); player = null; releaseFocus()
        worker.execute {
            try { frames?.close(); frames = null; retirement.complete(Unit) }
            catch (failure: Throwable) { retirement.completeExceptionally(failure) }
        }
        worker.shutdown()
    }

    internal companion object {
        // Main thread only. OCC-PLAN-068 U4: lets the cover review wait for the inner viewer to release its decoder.
        private var openSessions = 0
        private var lastRetirement: java.util.concurrent.CompletableFuture<Unit> = java.util.concurrent.CompletableFuture.completedFuture(Unit)

        /** True when no other session is open and the newest closed one has retired its exact reader. */
        val peersRetired: Boolean get() = openSessions == 0 && lastRetirement.isDone
    }
}
