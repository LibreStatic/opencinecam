/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import com.librestatic.opencinecam.media.ocraw.OcrawAudioChunk
import com.librestatic.opencinecam.media.ocraw.OcrawFrameIndex
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** A RAW take's sound (PLAN-071 §7.1 decision 1): interleaved little-endian PCM16. */
internal data class RawAudioFormat(val sampleRate: Int, val channels: Int) {
    val frameBytes get() = 2 * channels
}

/** The take's audio format from its static metadata; null (silent) without the keys or with values the player cannot play. */
internal fun rawAudioFormat(metadata: Map<String, String>): RawAudioFormat? {
    val rate = metadata["audioSampleRate"]?.trim()?.toIntOrNull() ?: return null
    val channels = metadata["audioChannels"]?.trim()?.toIntOrNull() ?: return null
    if (rate !in 8_000..192_000 || channels !in 1..2) return null
    return RawAudioFormat(rate, channels)
}

/** One audio chunk placed on the take's sample line: [startSample] counts from the first frame's time. */
internal class RawAudioPlanChunk(val chunk: OcrawAudioChunk, val startSample: Long, val frames: Long)

/**
 * A take's audio chunks on one sample line whose zero is the first frame (take time 0), sorted by start.
 * Chunks and frames share the chunk tick base, so each chunk lands by its ticks relative to the first frame's.
 */
internal class RawAudioPlan(val format: RawAudioFormat, val chunks: List<RawAudioPlanChunk>) {
    val maxChunkBytes = chunks.maxOfOrNull { it.chunk.payloadLength } ?: 0

    /** Capture jitter up to this many samples between chunks plays on as contiguous; more is a gap or an overlap. */
    val toleranceSamples = (format.sampleRate / 50).toLong()

    fun sampleAt(timeUs: Long): Long = Math.floorDiv(timeUs * format.sampleRate, 1_000_000L)

    fun timeUsOf(samples: Long): Long = samples * 1_000_000L / format.sampleRate

    /** The first chunk that still has samples at or after [sample]. */
    fun firstChunkEndingAfter(sample: Long): Int {
        var low = 0
        var high = chunks.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (chunks[middle].startSample + chunks[middle].frames <= sample) low = middle + 1 else high = middle
        }
        return low
    }
}

/** The plan for [index], or null when the take is silent: no audio keys, no audio chunks, or no frames to anchor them. */
internal fun rawAudioPlan(index: OcrawFrameIndex, timesUs: LongArray): RawAudioPlan? {
    val format = rawAudioFormat(index.metadata) ?: return null
    val base = index.timeBase ?: return null
    if (index.audio.isEmpty() || index.frames.isEmpty() || timesUs.isEmpty()) return null
    val referenceTicks = index.frames[0].timestampTicks
    val referenceUs = timesUs[0]
    val chunks = index.audio.mapNotNull { chunk ->
        val frames = (chunk.payloadLength / format.frameBytes).toLong()
        if (frames <= 0) return@mapNotNull null
        // Relative ticks first, in floating point: absolute boot-time ticks times 10^6 would overflow a Long.
        val startUs = referenceUs + (chunk.timestampTicks - referenceTicks).toDouble() * base.numerator * 1e6 / base.denominator
        RawAudioPlanChunk(chunk, floor(startUs * format.sampleRate / 1e6).toLong(), frames)
    }.sortedBy { it.startSample }
    return if (chunks.isEmpty()) null else RawAudioPlan(format, chunks)
}

/**
 * Turns a [RawAudioPlan] into one continuous PCM stream from [startSample] to [endSample], so a track
 * playing it is a clock: silence before the first chunk, in gaps (dropped audio, an unreadable chunk)
 * and after the last; an overlap is cut. The first chunk lands exactly; later ones within
 * [RawAudioPlan.toleranceSamples] follow on directly, so capture jitter never clicks.
 *
 * [next] returns a buffer that stays valid until the following [next] call.
 */
internal class RawAudioFeeder(
    private val plan: RawAudioPlan,
    startSample: Long,
    private val endSample: Long,
    private val read: (OcrawAudioChunk, ByteBuffer) -> Int,
) {
    /** The next sample this feeder will produce, on the plan's sample line. */
    var writeSample = startSample
        private set

    /** Silence written so far, in sample frames. */
    var silenceFrames = 0L
        private set

    val exhausted get() = writeSample >= endSample

    private val frameBytes = plan.format.frameBytes
    private var cursor = plan.firstChunkEndingAfter(startSample)
    private var exact = true
    private val chunkBuffer = ByteBuffer.allocate(plan.maxChunkBytes).order(ByteOrder.LITTLE_ENDIAN)
    private val zeros = ByteBuffer.allocate(SILENCE_FRAMES * frameBytes)

    fun next(): ByteBuffer? {
        while (writeSample < endSample) {
            val left = endSample - writeSample
            val chunk = plan.chunks.getOrNull(cursor) ?: return silence(left)
            val delta = chunk.startSample - writeSample
            val tolerance = if (exact) 0L else plan.toleranceSamples
            if (delta > tolerance) return silence(minOf(delta, left))
            cursor++
            val skip = if (delta < -tolerance) -delta else 0L
            if (skip >= chunk.frames) continue
            chunkBuffer.clear()
            try {
                read(chunk.chunk, chunkBuffer)
            } catch (_: IOException) {
                continue // a damaged chunk is a gap: the next turn fills it with silence
            } catch (_: IllegalArgumentException) {
                continue
            }
            chunkBuffer.flip()
            val frames = minOf(chunk.frames - skip, left, (chunkBuffer.limit() / frameBytes).toLong() - skip)
            if (frames <= 0) continue
            chunkBuffer.position((skip * frameBytes).toInt())
            chunkBuffer.limit(chunkBuffer.position() + (frames * frameBytes).toInt())
            writeSample += frames
            exact = false
            return chunkBuffer
        }
        return null
    }

    private fun silence(frames: Long): ByteBuffer {
        val count = minOf(frames, SILENCE_FRAMES.toLong()).toInt()
        writeSample += count
        silenceFrames += count
        return zeros.duplicate().apply { clear(); limit(count * frameBytes) }
    }

    private companion object {
        const val SILENCE_FRAMES = 4_800
    }
}

/** The output a RAW session streams PCM into, as [AudioTrack] offers it. Used on the session loop only. */
internal interface RawAudioOutput {
    /** Writes what fits without blocking, advancing [pcm]; returns the bytes taken. */
    fun write(pcm: ByteBuffer): Int
    fun play()
    fun setMuted(muted: Boolean)
    /** Sample frames played since [play], never more than written and never going back. */
    fun playedFrames(): Long
    fun release()
}

/** Audio focus for a RAW session; [request] reports the loss of a granted focus through [onLoss]. */
internal interface RawAudioFocus {
    fun request(onLoss: () -> Unit): Boolean
    fun abandon()

    companion object {
        val NONE = object : RawAudioFocus {
            override fun request(onLoss: () -> Unit) = true
            override fun abandon() {}
        }
    }
}

private val reviewAudioAttributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()

/**
 * A streaming [AudioTrack] with about a second of buffer, so a slow frame read never starves it. Its
 * played position comes from [AudioTrack.getTimestamp], extrapolated to now, or the playback head before
 * the first timestamp.
 */
internal class AudioTrackOutput(format: RawAudioFormat) : RawAudioOutput {
    private val rate = format.sampleRate
    private val frameBytes = format.frameBytes
    private val track: AudioTrack = run {
        val mask = if (format.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(reviewAudioAttributes)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, rate * frameBytes))
            .build()
    }
    private val stamp = AudioTimestamp()
    private var written = 0L
    private var played = 0L

    override fun write(pcm: ByteBuffer): Int {
        val count = track.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        check(count >= 0) { "Audio output failed ($count)" }
        written += count / frameBytes
        return count
    }

    override fun play() = track.play()

    override fun setMuted(muted: Boolean) { track.setVolume(if (muted) 0f else 1f) }

    override fun playedFrames(): Long {
        val position = if (track.getTimestamp(stamp) && stamp.framePosition > 0) {
            stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * rate / 1_000_000_000L
        } else track.playbackHeadPosition.toLong() and 0xffff_ffffL
        played = position.coerceIn(played, written)
        return played
    }

    override fun release() {
        try { track.pause(); track.flush() } finally { track.release() }
    }
}

/** Transient media focus, as [MediaPlaybackSession] takes it; a loss is reported on [main]. */
internal class AudioManagerFocus(context: Context, private val main: Handler) : RawAudioFocus {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var held: AudioFocusRequest? = null

    override fun request(onLoss: () -> Unit): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(reviewAudioAttributes)
            .setOnAudioFocusChangeListener({ change -> if (change < 0) onLoss() }, main).build()
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) held = request
        return granted
    }

    override fun abandon() {
        held?.let { audio.abandonAudioFocusRequest(it) }
        held = null
    }
}
