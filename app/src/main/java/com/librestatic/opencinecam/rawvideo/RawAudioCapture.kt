/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.SystemClock
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.camera.AudioLevelMeter
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.PcmCaptureEpoch
import com.librestatic.opencinecam.camera.PcmListeningSink
import com.librestatic.opencinecam.camera.PcmMeterEncoding
import com.librestatic.opencinecam.camera.offerListening
import com.librestatic.opencinecam.camera.pcmFrameDurationNs
import com.librestatic.opencinecam.media.audio.AudioCapturePath
import com.librestatic.opencinecam.media.audio.AudioInputRouteGuard
import com.librestatic.opencinecam.storage.AudioRetirementGate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sound of a RAW take (OCC-PLAN-071 §7.1): interleaved little-endian PCM16 delivered in chunks of
 * about 100 ms, each stamped with the capture time of its first sample on the camera's sensor clock.
 */
interface RawAudioSource {
    /**
     * Starts capture on the source's own thread; chunks go to [sink] until [stop]. Returns the static
     * `METADATA` keys of the take's sound ([RawAudioKeys]). Throws when audio cannot start.
     */
    fun start(sink: RawAudioSink): Map<String, String>

    /**
     * Stops capture, hands the last partial chunk to the sink and releases the input. Blocks until no
     * further sink call can happen. Idempotent; never throws.
     */
    fun stop()

    /** Keys known only once capture ended (clock evidence, an early end); read after [stop]. */
    fun finalMetadata(): Map<String, String> = emptyMap()

    /** Why capture ended before [stop] (a read failure), or null. Read after [stop]. */
    val failure: String? get() = null
}

/** Where a [RawAudioSource] puts its chunks. */
fun interface RawAudioSink {
    /**
     * One chunk of [frames] sample frames; [firstSampleSensorNs] is its first sample's capture time
     * on the sensor clock. Returns false when the chunk was not taken (it is then counted as dropped).
     */
    fun offer(firstSampleSensorNs: Long, payload: ByteArray, frames: Int): Boolean
}

/** Static and final `METADATA` keys of a RAW take's sound (`docs/formats/opencine-raw-v1.md`). */
object RawAudioKeys {
    const val SAMPLE_RATE = "audioSampleRate"
    const val CHANNELS = "audioChannels"
    const val SOURCE = "audioSource"
    /** How audio time was mapped to the sensor clock: [CLOCK_BOOTTIME] or [CLOCK_MONOTONIC_OFFSET]. */
    const val CLOCK = "audioClock"
    /** Sample frames (one sample per channel) the writer queue could not take; written at the end. */
    const val DROPPED_SAMPLES = "audioDroppedSamples"
    /** `true` when the chunk times come from AudioRecord timestamps, `false` when estimated from the start. */
    const val TIMESTAMP_BACKED = "audioTimestampBacked"
    /** Largest drift seen between later AudioRecord timestamps and the fixed audio epoch, in ns. */
    const val MAX_RESIDUAL_NS = "audioMaxResidualNs"
    /** Why the sound ended before the take did. */
    const val ERROR = "audioError"

    const val CLOCK_BOOTTIME = "boottime"
    const val CLOCK_MONOTONIC_OFFSET = "monotonic-offset"
}

/**
 * Maps AudioRecord `TIMEBASE_BOOTTIME` times to the camera sensor clock. A sensor whose
 * `SENSOR_INFO_TIMESTAMP_SOURCE` is REALTIME stamps frames in boot time, so the mapping is the
 * identity. An UNKNOWN source stamps them, in practice, on `CLOCK_MONOTONIC` (`System.nanoTime`), so
 * boot time maps through [boottimeMinusMonotonicNs], measured when the take starts.
 */
data class RawAudioClock(val sensorRealtime: Boolean, val boottimeMinusMonotonicNs: Long = 0) {
    fun sensorNs(boottimeNs: Long): Long =
        if (sensorRealtime) boottimeNs else Math.subtractExact(boottimeNs, boottimeMinusMonotonicNs)

    val label: String get() = if (sensorRealtime) RawAudioKeys.CLOCK_BOOTTIME else RawAudioKeys.CLOCK_MONOTONIC_OFFSET

    companion object {
        /** Reads the boot-time minus monotonic offset now, bracketing the monotonic read to halve the error. */
        fun measure(sensorRealtime: Boolean): RawAudioClock {
            if (sensorRealtime) return RawAudioClock(true)
            val before = SystemClock.elapsedRealtimeNanos()
            val monotonic = System.nanoTime()
            val after = SystemClock.elapsedRealtimeNanos()
            return RawAudioClock(false, before + (after - before) / 2 - monotonic)
        }
    }
}

/** Whether a RAW take records sound, from the same settings and permission as a video take. */
enum class RawTakeAudio {
    /** Audio is off for this take. */
    NONE,
    /** Record the microphone. */
    RECORD,
    /** Audio is on but the microphone permission is missing: refuse the take, as video does. */
    REFUSE_PERMISSION;

    companion object {
        fun decide(audioRequested: Boolean, microphoneGranted: Boolean): RawTakeAudio = when {
            !audioRequested -> NONE
            microphoneGranted -> RECORD
            else -> REFUSE_PERMISSION
        }
    }
}

/**
 * Cuts a PCM16 stream into chunks of [chunkFrames] sample frames and stamps each with the boot time
 * of its first sample. The times come from one fixed epoch (as [PcmCaptureEpoch] does for video):
 * the first AudioRecord timestamp fixes frame zero, later ones only measure drift. Until the first
 * timestamp arrives completed chunks wait; after [maxPendingChunks] of them (or at [flush]) the epoch
 * is estimated from [startReceiptBoottimeNs], the boot time when recording started.
 *
 * Not thread-safe: one capture thread.
 */
internal class RawPcmChunker(
    private val sampleRateHz: Int,
    channels: Int,
    private val startReceiptBoottimeNs: Long,
    private val chunkFrames: Int = defaultChunkFrames(sampleRateHz),
    private val maxPendingChunks: Int = MAX_PENDING_CHUNKS,
) {
    class Chunk(val firstSampleBoottimeNs: Long, val payload: ByteArray, val frames: Int)

    init {
        require(channels in 1..2) { "channels must be 1 or 2" }
        require(chunkFrames > 0 && maxPendingChunks >= 0)
    }

    private val frameBytes = channels * 2
    private val epoch = PcmCaptureEpoch(sampleRateHz)
    private val pending = ArrayDeque<Pair<Long, ByteArray>>()
    private var chunk = ByteArray(chunkFrames * frameBytes)
    private var fill = 0
    private var chunkStartFrame = 0L

    val timestampBacked: Boolean get() = epoch.current()?.timestampBacked == true
    val maxResidualNs: Long? get() = epoch.current()?.takeIf { it.timestampBacked }?.maxResidualNs

    /** An AudioRecord timestamp: [framePosition] was captured at [boottimeNs]. Bad ones are ignored. */
    fun observeTimestamp(framePosition: Long, boottimeNs: Long) {
        runCatching { epoch.observe(framePosition, boottimeNs) }
    }

    /** Appends [bytes] (whole frames) from [src]'s position, which is left unchanged; returns the chunks now timed. */
    fun append(src: ByteBuffer, bytes: Int): List<Chunk> {
        require(bytes >= 0 && bytes % frameBytes == 0) { "PCM16 reads must hold whole frames" }
        val view = src.duplicate()
        var left = bytes
        while (left > 0) {
            val n = minOf(left, chunk.size - fill)
            view.get(chunk, fill, n)
            fill += n
            left -= n
            if (fill == chunk.size) {
                pending.addLast(chunkStartFrame to chunk)
                chunkStartFrame += chunkFrames
                chunk = ByteArray(chunkFrames * frameBytes)
                fill = 0
            }
        }
        return release(force = false)
    }

    /** The last partial chunk and everything still waiting for a time. */
    fun flush(): List<Chunk> {
        if (fill > 0) {
            pending.addLast(chunkStartFrame to chunk.copyOf(fill))
            chunkStartFrame += fill / frameBytes
            fill = 0
        }
        return release(force = true)
    }

    private fun release(force: Boolean): List<Chunk> {
        if (pending.isEmpty()) return emptyList()
        if (epoch.current() == null && (force || pending.size > maxPendingChunks)) {
            epoch.estimate(startReceiptBoottimeNs.coerceAtLeast(1))
        }
        val zero = epoch.current()?.frameZeroNs ?: return emptyList()
        val out = ArrayList<Chunk>(pending.size)
        while (pending.isNotEmpty()) {
            val (start, data) = pending.removeFirst()
            out += Chunk(zero + pcmFrameDurationNs(start, sampleRateHz), data, data.size / frameBytes)
        }
        return out
    }

    companion object {
        /** About 100 ms per chunk (PLAN-071 §7.1.1). */
        fun defaultChunkFrames(sampleRateHz: Int): Int = (sampleRateHz / 10).coerceAtLeast(1)
        const val MAX_PENDING_CHUNKS = 5
    }
}

/**
 * [RawAudioSource] over an [AudioRecord] with the take's audio settings: source, sample rate, channels,
 * the preferred input device and its loss guard, and the manual digital gain. Always PCM16. It feeds
 * the level meters and the listening output like the video recorders do. Hardware effects (AGC, noise
 * suppression, echo cancelling) are not attached.
 */
class AudioRecordRawAudioSource(
    context: Context,
    private val settings: CameraSettings,
    private val sensorTimestampRealtime: Boolean,
    private val inputGuard: AudioInputRouteGuard? = null,
    private val onAudioLevel: ((AudioLevelSnapshot) -> Unit)? = null,
    private val listeningSink: PcmListeningSink? = null,
) : RawAudioSource {
    private val appContext = context.applicationContext
    private val channels = settings.audioChannels
    private val frameBytes = channels * 2
    private val stopped = AtomicBoolean(false)
    @Volatile private var running = false
    @Volatile private var readFailure: String? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var chunker: RawPcmChunker? = null
    private var sink: RawAudioSink? = null
    private var clock = RawAudioClock(sensorTimestampRealtime)

    override val failure: String? get() = readFailure

    @SuppressLint("MissingPermission")
    override fun start(sink: RawAudioSink): Map<String, String> {
        check(record == null) { "RAW audio started twice" }
        AudioRetirementGate.requireIdle()
        val rate = settings.audioSampleRateHz
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minimum = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "Unsupported PCM16 configuration" }
        val chunkBytes = RawPcmChunker.defaultChunkFrames(rate) * frameBytes
        val built = AudioRecord.Builder()
            .setAudioSource(settings.audioSource.androidSource)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, chunkBytes * 4) / frameBytes * frameBytes)
            .build()
        try {
            check(built.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
            check(built.sampleRate == rate && built.channelCount == channels) { "AudioRecord PCM format differs from the request" }
            settings.audioInputDeviceId?.let { id ->
                val device = AudioInputRouteGuard.inputDevice(appContext, id) ?: error("Requested audio input $id is no longer connected")
                check(built.setPreferredDevice(device)) { "AudioRecord rejected audio input $id" }
            }
            clock = RawAudioClock.measure(sensorTimestampRealtime)
            val receipt = SystemClock.elapsedRealtimeNanos()
            built.startRecording()
            check(built.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not enter RECORDING state" }
            chunker = RawPcmChunker(rate, channels, receipt)
        } catch (problem: Throwable) {
            runCatching { built.stop() }
            runCatching { built.release() }
            throw problem
        }
        record = built
        this.sink = sink
        runCatching { inputGuard?.attach(built, AudioCapturePath.AUDIO_RECORD) }
        running = true
        thread = Thread({ captureLoop(built, chunkBytes) }, "occ-raw-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return linkedMapOf(
            RawAudioKeys.SAMPLE_RATE to built.sampleRate.toString(),
            RawAudioKeys.CHANNELS to built.channelCount.toString(),
            RawAudioKeys.SOURCE to settings.audioSource.name,
            RawAudioKeys.CLOCK to clock.label,
        )
    }

    private fun captureLoop(audio: AudioRecord, chunkBytes: Int) {
        val buffer = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
        val timestamp = AudioTimestamp()
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_16, channels, sampleRateHz = audio.sampleRate)
        val gain = settings.audioRecordingGain
        val chunker = requireNotNull(chunker)
        try {
            while (true) {
                buffer.clear()
                val read = audio.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    if (running) readFailure = "audio-read-$read"
                    break
                }
                if (read == 0) {
                    if (!running) break
                    continue
                }
                val bytes = read / frameBytes * frameBytes
                if (bytes == 0) continue
                if (audio.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    chunker.observeTimestamp(timestamp.framePosition, timestamp.nanoTime)
                }
                inputGuard?.silenceIfMuted(buffer, bytes)
                meter.observeInput(buffer, bytes)
                gain.process(buffer, bytes, PcmMeterEncoding.PCM_16, channels)
                listeningSink.offerListening(buffer, bytes, PcmMeterEncoding.PCM_16, audio.sampleRate, channels)
                onAudioLevel?.let { publish ->
                    meter.analyze(buffer, bytes, SystemClock.elapsedRealtime())?.let { publish(it.copy(appliedRecordingGain = gain)) }
                }
                buffer.position(0)
                chunker.append(buffer, bytes).forEach(::deliver)
            }
        } catch (problem: Throwable) {
            readFailure = "audio-capture-failed: ${problem.javaClass.simpleName}"
            android.util.Log.e(TAG, "RAW audio capture failed", problem)
        }
    }

    private fun deliver(chunk: RawPcmChunker.Chunk) {
        val target = sink ?: return
        val sensorNs = runCatching { clock.sensorNs(chunk.firstSampleBoottimeNs) }.getOrNull() ?: return
        runCatching { target.offer(sensorNs, chunk.payload, chunk.frames) }
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        val audio = record ?: return
        running = false
        runCatching { audio.stop() }
        val worker = thread
        worker?.join(STOP_JOIN_MS)
        runCatching { inputGuard?.detach() }
        if (worker?.isAlive == true) {
            // Releasing under a blocked read could crash the capture thread; leave the record to it.
            readFailure = readFailure ?: "audio-thread-stalled"
            android.util.Log.e(TAG, "RAW audio thread still reading at stop; AudioRecord not released")
            sink = null
            return
        }
        chunker?.flush()?.forEach(::deliver)
        sink = null
        runCatching { audio.release() }
    }

    override fun finalMetadata(): Map<String, String> {
        val values = linkedMapOf<String, String>()
        chunker?.let { timing ->
            values[RawAudioKeys.TIMESTAMP_BACKED] = timing.timestampBacked.toString()
            timing.maxResidualNs?.let { values[RawAudioKeys.MAX_RESIDUAL_NS] = it.toString() }
        }
        return values
    }

    private companion object {
        const val TAG = "RawAudioCapture"
        const val STOP_JOIN_MS = 2_000L
    }
}
