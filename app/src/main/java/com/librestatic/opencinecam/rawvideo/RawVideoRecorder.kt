/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoFrameConsumer
import com.librestatic.opencinecam.camera.RawVideoSessionConfig
import com.librestatic.opencinecam.media.ocraw.OcrawChunkId
import com.librestatic.opencinecam.media.ocraw.OcrawCodec
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawHeader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamStats
import com.librestatic.opencinecam.media.ocraw.OcrawStreamWriter
import com.librestatic.opencinecam.media.ocraw.OcrawTimeBase
import com.librestatic.opencinecam.media.raw.RawVideoRing
import com.librestatic.opencinecam.media.raw.SharedMemorySlots
import com.librestatic.opencinecam.media.raw.copyRaw10Rows
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Where a RAW take's file lives while it is written; [RawVideoRecorder] never closes [channel] itself. */
interface RawVideoTakeOutput {
    val displayName: String

    /** Empty and positioned at 0; readable as well as writable, because recovery reads what was written. */
    val channel: FileChannel

    /** Closes [channel] and makes the file visible. */
    fun publish(): Uri

    /** Closes [channel] and removes the file. Only used for a take without a single frame. */
    fun discard()
}

/** Ring slot memory; the production one is anonymous shared memory outside the Java heap. */
interface RawSlotMemory : AutoCloseable {
    fun allocate(bytes: Int): ByteBuffer
}

/** [SharedMemorySlots] as [RawSlotMemory]. */
class SharedMemorySlotMemory : RawSlotMemory {
    private val slots = SharedMemorySlots()
    override fun allocate(bytes: Int): ByteBuffer = slots.allocate(bytes)
    override fun close() = slots.close()
}

/** Per-take values that are not camera data. [takeId] is a UUID; it becomes the clip UUID. */
data class RawTakeInfo(
    val takeId: String,
    val model: String,
    val appVersion: String,
    /** Extra static keys (e.g. `fingerprint`); the recorder's own keys win over them. */
    val extraMetadata: Map<String, String> = emptyMap(),
)

/** The ring a mode entry armed. */
data class RawVideoArmState(
    val config: RawVideoConfig,
    val crop: RawVideoCrop,
    val slotBytes: Int,
    val slotCount: Int,
    /** True when frames are encoded on the writer thread ([RawVideoConfig.isCompressed]: block-pack or LJ92). */
    val compressed: Boolean = false,
    /** True when a block-pack configuration records near-lossless (quantised block-pack, [NearLosslessFrameEncoder]). */
    val nearLossless: Boolean = false,
) {
    val ringBytes: Long get() = slotBytes.toLong() * slotCount
}

/** Stable reasons a RAW take ended early; shown to the operator and stored as evidence. */
object RawTakeReasons {
    /** The ring passed its high-water mark: storage fell behind, so the take stopped (ADR-0019). */
    const val RING_FULL = "raw-ring-full"

    /** The file could not be written; what reached it is kept. */
    const val WRITER_ERROR = "raw-writer-error"

    /** Prefix of `raw-stream-failed:<engine reason>`. */
    const val STREAM_FAILED_PREFIX = "raw-stream-failed:"

    /** A frame did not match the armed configuration (size or crop). */
    const val FRAME_INVALID = "raw-frame-invalid"

    /** The writer thread did not finish in time; the file stays pending for recovery. */
    const val WRITER_STALLED = "raw-writer-stalled"

    /** A compressed configuration cannot be armed: the native codec library is not available. */
    const val CODEC_UNAVAILABLE = "raw-codec-unavailable"
}

/** [RawVideoRecorder.arm] refused the configuration for a stable [reason] ([RawTakeReasons]). */
class RawVideoArmException(val reason: String) : IllegalStateException(reason)

/** Live numbers for the UI; every field is a snapshot, safe to read from any thread. */
data class RawVideoLiveStats(
    val recording: Boolean,
    val framesCaptured: Long,
    val framesWritten: Long,
    val bytesWritten: Long,
    val ringUsedFraction: Double,
    val ringPeakFraction: Double,
    /** Always 0: a full ring ends the take instead (ADR-0019). Exposed so the invariant is visible. */
    val droppedFrames: Long,
    val elapsedNs: Long,
    val failureReason: String?,
    /** Uncompressed over stored bytes so far; null for an uncompressed take or before its first frame. */
    val compressionRatio: Double? = null,
    /** Mean encode time per frame so far; null for an uncompressed take or before its first frame. */
    val encodeMeanMs: Double? = null,
) {
    companion object {
        val IDLE = RawVideoLiveStats(false, 0, 0, 0, 0.0, 0.0, 0, 0, null)
    }
}

data class RawTakeResult(
    /** The published file, or null when nothing was published. */
    val uri: Uri?,
    val displayName: String,
    val framesCaptured: Long,
    /** Complete frames in the file (after recovery when the writer failed). */
    val framesWritten: Long,
    val bytesWritten: Long,
    val droppedFrames: Long,
    val frameMetadataWritten: Long,
    /** Capture results that never met their frame (dropped after the match window). */
    val unmatchedResults: Long,
    val peakRingFraction: Double,
    /** Last minus first sensor timestamp written. */
    val mediaDurationNs: Long,
    val elapsedNs: Long,
    /** Why the take ended early, or null for an operator stop. */
    val reason: String?,
    /** True when the file was repaired with [recoverRawTakeFile] before publication. */
    val recovered: Boolean,
    /** Set when publishing or discarding the output failed; the row then stays pending for recovery. */
    val outputError: String?,
    val writerStats: OcrawStreamStats?,
    /** The configured encoding, [RawVideoConfig.encoding]. */
    val encoding: String = RawVideoConfig.ENCODING_RAW10,
    /** True when the block-pack take was recorded near-lossless ([RawVideoArmState.nearLossless]). */
    val nearLossless: Boolean = false,
    /** Frames written encoded; the rest of a compressed take's frames were stored as RAW10. */
    val framesEncoded: Long = 0,
    /** Frames of a compressed take stored uncompressed because their encoding did not fit the slot. */
    val framesStoredUncompressed: Long = 0,
    val compressionRatio: Double? = null,
    val encodeMeanMs: Double? = null,
    val encodeMaxMs: Double? = null,
    /** The take's sound: its rate and channels, or null for a silent take. */
    val audioSampleRate: Int? = null,
    val audioChannels: Int? = null,
    /** PCM sample frames (one sample per channel) written as `AUDIO_PCM16` chunks. */
    val audioFramesWritten: Long = 0,
    /** Sample frames the audio queue or a failed writer could not take; the take went on without them. */
    val audioDroppedFrames: Long = 0,
    /** Why audio did not start (then [audioSampleRate] is null) or ended early; null otherwise. */
    val audioError: String? = null,
)

/**
 * OCC-PLAN-071 RAW video recorder: the camera's RAW10 frames go through a preallocated RAM ring to
 * one writer thread that appends them to an `.ocraw` file with [OcrawStreamWriter].
 *
 * **Lifecycle.** [arm] allocates the ring once per mode entry (size from [RawRingBudget]); [disarm]
 * frees it. Each take is [start] … [stop]. Call [start] before the engine starts delivering frames,
 * and [stop] after the engine has stopped them, so every capture result has arrived.
 *
 * **Threads.**
 * - Producer: the engine's RAW reader thread calls [onRawTakeStarted], [onRawFrame] and (usually)
 *   [onRawResult]/[onRawStreamFailed]. [onRawFrame] copies the image's rows (crop applied, stride
 *   removed) into a free slot and returns; it allocates only the frame header and never waits for
 *   the writer: a busy writer only shows up as ring occupancy.
 * - Consumer: one writer thread per take drains the slots in order, writes `METADATA` before the
 *   first frame, each `RAW_FRAME`, and each `FRAME_METADATA` right after its frame (or later, when
 *   the capture result comes after the frame was written).
 * - Control: [arm], [disarm], [start] and [stop] are serialised; [stop] blocks until the writer has
 *   finished. [liveStats] may be called from any thread.
 * - Results: [onRawResult] may come from any thread. It only queues the extracted values in a bounded
 *   inbox; the writer matches them to frames by sensor timestamp. Results that arrive before their
 *   frame wait in a map, and frames written before their result wait for it; either side gives up
 *   after [resultWindowFrames] frames.
 *
 * **No dropped frames (ADR-0019).** When the ring reaches [highWaterFraction] after a frame is
 * stored, [onRawFrame] returns false and the engine ends the take; the file is finalised and the
 * result carries [RawTakeReasons.RING_FULL]. A writer failure ([RawTakeReasons.WRITER_ERROR]) also
 * ends the take: the file is recovered in place and published if it holds at least one frame.
 *
 * [onTakeMustEnd] (per take, from [start]) is called once, on whichever thread found the problem,
 * when the recorder itself decides the take must end. It must not block.
 *
 * **Compression (slice G2b).** For a compressed configuration ([RawVideoConfig.ENCODING_BLOCK_PACK],
 * the "Save space" codec, or [RawVideoConfig.ENCODING_LJ92]) [arm] also creates one persistent
 * encoder for its encoding ([encoderFactory]; its own pool of [RawFrameEncoders.defaultThreads]
 * threads: 2 for block-pack, 4 for LJ92) and one output buffer of the slot size from the same
 * off-heap slot memory. The producer path is unchanged: it still only copies RAW10 rows into a slot.
 * The writer thread encodes each slot into the output buffer right before writing it, so frames stay
 * in order and the producer never waits; the ring absorbs encode jitter like write jitter, and the
 * high-water mark still ends the take. A frame whose encoding does not fit the slot is written as
 * RAW10 (encoding 0). Without the native library a compressed configuration is refused at [arm] with
 * [RawTakeReasons.CODEC_UNAVAILABLE].
 *
 * **Near-lossless.** A block-pack configuration armed with a [RawNoiseProfile] records quantised
 * block-pack instead ([nearLosslessEncoderFactory], [NearLosslessFrameEncoder]); the configuration and
 * its qualification stay block-pack. The writer passes each frame's ISO from its capture result, or the
 * most recent one known when the result has not arrived yet, so the encoder rebuilds its tables only on
 * an ISO change and on the writer thread; frames before the first known ISO are lossless block-pack.
 *
 * **Audio (PLAN-071 §7.1).** A take given a [RawAudioSource] starts it right after the writer thread;
 * the source captures on its own thread and offers ~100 ms PCM16 chunks, stamped on the sensor clock,
 * to a small bounded queue beside the ring ([audioQueueChunks]). The writer writes the static
 * `METADATA` (with the audio keys) before the first frame and drains the queue after every frame and
 * while it waits, so `AUDIO_PCM16` chunks interleave with the frames and never precede the metadata.
 * A full queue drops the chunk and counts its samples; audio never ends a take, nor does an audio
 * source that fails to start (the take is then silent). [stop] stops the source before the ring
 * closes, so its last partial chunk is written, and a final `METADATA` chunk records
 * [RawAudioKeys.DROPPED_SAMPLES]. Every take-end path goes through [stop].
 */
class RawVideoRecorder(
    private val slotMemoryFactory: () -> RawSlotMemory = ::SharedMemorySlotMemory,
    private val writerOptions: OcrawStreamWriter.Options = OcrawStreamWriter.Options(),
    private val highWaterFraction: Double = HIGH_WATER_FRACTION,
    private val resultWindowFrames: Int = RESULT_WINDOW_FRAMES,
    private val writerJoinTimeoutMs: Long = WRITER_JOIN_TIMEOUT_MS,
    private val finalResultWaitMs: Long = FINAL_RESULT_WAIT_MS,
    private val clock: () -> Long = System::nanoTime,
    /** The encoder of a compressed configuration's [RawVideoConfig.encoding]; null when the codec is unavailable. */
    private val encoderFactory: (String) -> RawFrameEncoder? = { RawFrameEncoders.native(it) },
    /** The near-lossless encoder of a block-pack configuration armed with a noise profile; null when the codec is unavailable. */
    private val nearLosslessEncoderFactory: (RawNoiseProfile) -> RawFrameEncoder? = { RawFrameEncoders.nativeNearLossless(it) },
    private val audioQueueChunks: Int = AUDIO_QUEUE_CHUNKS,
) : RawVideoFrameConsumer {
    init {
        require(highWaterFraction > 0.0 && highWaterFraction <= 1.0) { "highWaterFraction must be within (0, 1]" }
        require(resultWindowFrames > 0) { "resultWindowFrames must be positive" }
        require(audioQueueChunks > 0) { "audioQueueChunks must be positive" }
    }

    /** One audio chunk on its way to the writer; [ticks] is already in [TIME_BASE]. */
    private class AudioBlock(val ticks: Long, val payload: ByteArray, val frames: Int)

    private class Armed(
        val state: RawVideoArmState,
        val buffers: List<ByteBuffer>,
        val memory: RawSlotMemory,
        val compressor: RawFrameCompressor?,
        val encoder: RawFrameEncoder?,
    )

    /** One capture result reduced to its metadata values; [arrivedAtFrame] is writer-thread state. */
    private class FrameResult(val timestampNs: Long, val frameNumber: Long, val values: Map<String, String>) {
        var arrivedAtFrame = 0L
        /** The frame's sensitivity (`iso`), when the result carries one. */
        val iso: Int? get() = values[ISO_KEY]?.toIntOrNull()?.takeIf { it > 0 }
    }

    private class Take(
        val output: RawVideoTakeOutput,
        val info: RawTakeInfo,
        val armed: RawVideoArmState,
        val ring: RawVideoRing,
        val startedAtNs: Long,
        val onTakeMustEnd: (String) -> Unit,
        val compressor: RawFrameCompressor?,
        audioQueueChunks: Int,
    ) {
        val failure = AtomicReference<String?>()
        val results = ArrayBlockingQueue<FrameResult>(RESULT_INBOX)

        // Audio: set by start() before the engine delivers frames; the queue is the only hand-off.
        @Volatile var audio: RawAudioSource? = null
        @Volatile var audioMetadata: Map<String, String> = emptyMap()
        @Volatile var audioStartError: String? = null
        /** Set by stop() once the source has stopped: later offers are refused, uncounted. */
        @Volatile var audioClosed = false
        val audioQueue = ArrayBlockingQueue<AudioBlock>(audioQueueChunks)
        val audioDroppedFrames = AtomicLong()
        @Volatile var audioFramesWritten = 0L

        // Producer thread (guarded by the Take monitor against stop()).
        var stopped = false
        @Volatile var framesCaptured = 0L
        @Volatile var startedCameraId: String? = null
        @Volatile var sessionConfig: RawVideoSessionConfig? = null
        @Volatile var staticValues: Map<String, String> = emptyMap()

        // Writer thread.
        @Volatile var framesWritten = 0L
        @Volatile var bytesWritten = 0L
        @Volatile var frameMetadataWritten = 0L
        @Volatile var firstTimestampNs = -1L
        @Volatile var lastTimestampNs = -1L
        @Volatile var writerError: Throwable? = null
        @Volatile var writerStats: OcrawStreamStats? = null
        val unmatchedResults = AtomicLong()
        lateinit var thread: Thread

        /** First failure wins; returns true when this call set it. */
        fun fail(reason: String): Boolean {
            if (!failure.compareAndSet(null, reason)) return false
            runCatching { onTakeMustEnd(reason) }
            return true
        }
    }

    private val control = Any()
    private var armed: Armed? = null

    /** A writer that outlived [stop]; its slot memory must not be freed while it runs. */
    private var stalledWriter: Thread? = null

    @Volatile private var take: Take? = null

    val armState: RawVideoArmState? get() = synchronized(control) { armed?.state }

    val isRecording: Boolean get() = take != null

    /** The running take's audio sample rate, or null when it records no sound. */
    fun liveAudioSampleRate(): Int? = take?.audioMetadata?.get(RawAudioKeys.SAMPLE_RATE)?.toIntOrNull()

    /** The running take's audio channel count, or null when it records no sound. */
    fun liveAudioChannels(): Int? = take?.audioMetadata?.get(RawAudioKeys.CHANNELS)?.toIntOrNull()

    /**
     * Allocates the ring for [config]: as many frame slots as fit in [ringBytes] (at least
     * [RawRingBudget.MIN_SLOTS]). If memory runs out part way, the slots already allocated are kept
     * when there are enough of them. Re-arming frees the previous ring first. A block-pack [config]
     * with a [noiseProfile] records near-lossless; the profile is ignored for any other encoding.
     */
    fun arm(config: RawVideoConfig, ringBytes: Long, noiseProfile: RawNoiseProfile? = null): RawVideoArmState = synchronized(control) {
        check(take == null) { "cannot arm during a take" }
        disarmLocked()
        require(config.encoding == RawVideoConfig.ENCODING_RAW10 || config.encoding in RawVideoConfig.COMPRESSED_ENCODINGS) {
            "unsupported RAW video encoding ${config.encoding}"
        }
        val crop = RawVideoCrop.centred(config)
        val frameBytes = OcrawFrameHeader.raw10RowBytes(crop.width).toLong() * crop.height
        require(frameBytes <= OcrawCodec.MAX_CHUNK_PAYLOAD - OcrawFrameHeader.BYTES) { "frame exceeds the bounded payload" }
        val slotBytes = frameBytes.toInt()
        val wanted = (ringBytes / slotBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        require(wanted >= RawRingBudget.MIN_SLOTS) { "a ring of $ringBytes bytes holds fewer than ${RawRingBudget.MIN_SLOTS} frames" }
        val nearLossless = noiseProfile?.takeIf { config.encoding == RawVideoConfig.ENCODING_BLOCK_PACK }
        val encoder = if (config.isCompressed) {
            (if (nearLossless != null) nearLosslessEncoderFactory(nearLossless) else encoderFactory(config.encoding))
                ?: throw RawVideoArmException(RawTakeReasons.CODEC_UNAVAILABLE)
        } else null
        val memory: RawSlotMemory
        val compressor: RawFrameCompressor?
        try {
            memory = slotMemoryFactory()
            // The encoded frame's buffer comes first, so a ring that fills memory cannot starve it.
            compressor = encoder?.let {
                try {
                    RawFrameCompressor(it, memory.allocate(slotBytes))
                } catch (problem: Throwable) {
                    runCatching { memory.close() }
                    throw problem
                }
            }
        } catch (problem: Throwable) {
            runCatching { encoder?.close() }
            throw problem
        }
        val buffers = ArrayList<ByteBuffer>(wanted)
        try {
            repeat(wanted) { buffers += memory.allocate(slotBytes) }
        } catch (problem: Exception) {
            keepPartial(buffers, memory, encoder, problem)
        } catch (problem: OutOfMemoryError) {
            keepPartial(buffers, memory, encoder, problem)
        }
        val state = RawVideoArmState(config, crop, slotBytes, buffers.size, compressed = compressor != null,
            nearLossless = compressor != null && nearLossless != null)
        armed = Armed(state, buffers, memory, compressor, encoder)
        state
    }

    private fun keepPartial(buffers: List<ByteBuffer>, memory: RawSlotMemory, encoder: RawFrameEncoder?, problem: Throwable) {
        if (buffers.size >= RawRingBudget.MIN_SLOTS) return
        runCatching { memory.close() }
        runCatching { encoder?.close() }
        throw problem
    }

    /** Frees the ring. Not allowed during a take. */
    fun disarm() = synchronized(control) {
        check(take == null) { "stop the take before disarming" }
        disarmLocked()
    }

    private fun disarmLocked() {
        val current = armed ?: return
        armed = null
        stalledWriter?.let { writer ->
            writer.join(writerJoinTimeoutMs)
            if (writer.isAlive) {
                // Freeing the mappings (or the encoder) under a running writer would crash it; leak them instead.
                android.util.Log.e(TAG, "RAW writer still running at disarm; ring memory not freed")
                return
            }
            stalledWriter = null
        }
        runCatching { current.encoder?.close() }
        runCatching { current.memory.close() }
    }

    /**
     * Starts a take into [output]; [output]'s channel must be empty. With [audio] the take also records
     * sound; an audio source that fails to start leaves the take silent ([RawTakeResult.audioError]).
     */
    fun start(output: RawVideoTakeOutput, info: RawTakeInfo, audio: RawAudioSource? = null, onTakeMustEnd: (String) -> Unit = {}) {
        synchronized(control) {
            val current = checkNotNull(armed) { "arm the recorder before starting a take" }
            check(take == null) { "a take is already recording" }
            check(stalledWriter?.isAlive != true) { "the previous take's writer is still running" }
            var next = 0
            val ring = RawVideoRing(current.state.slotBytes, current.buffers.size) { current.buffers[next++] }
            current.compressor?.reset()
            val started = Take(output, info, current.state, ring, clock(), onTakeMustEnd, current.compressor, audioQueueChunks)
            started.thread = Thread({ runWriter(started) }, "occ-raw-writer").apply {
                priority = Thread.MAX_PRIORITY - 1
            }
            take = started
            started.thread.start()
            if (audio != null) startAudio(started, audio)
        }
    }

    private fun startAudio(current: Take, audio: RawAudioSource) {
        try {
            val metadata = audio.start { sensorNs, payload, frames -> offerAudio(current, sensorNs, payload, frames) }
            current.audio = audio
            current.audioMetadata = LinkedHashMap(metadata)
        } catch (problem: Throwable) {
            android.util.Log.w(TAG, "RAW take records no audio: the source did not start", problem)
            current.audioStartError = "audio-start-failed: ${problem.javaClass.simpleName}: ${problem.message.orEmpty().take(120)}"
            runCatching { audio.stop() }
        }
    }

    /** Audio thread: queues one chunk for the writer, or counts it as dropped when the queue is full. */
    private fun offerAudio(current: Take, sensorNs: Long, payload: ByteArray, frames: Int): Boolean {
        if (current.audioClosed || frames <= 0) return false
        if (current.audioQueue.offer(AudioBlock(ticksOf(sensorNs), payload, frames))) return true
        current.audioDroppedFrames.addAndGet(frames.toLong())
        return false
    }

    /** Stops the take's audio source (its last chunk reaches the queue) and closes the queue. */
    private fun stopAudio(current: Take) {
        current.audio?.let { runCatching { it.stop() } }
        current.audioClosed = true
    }

    /**
     * Ends the take: no more frames are accepted, the writer drains the ring and closes the file,
     * and the output is published (or discarded when it holds no frame). Blocks until then.
     */
    fun stop(): RawTakeResult = synchronized(control) {
        val current = checkNotNull(take) { "no take is recording" }
        // Audio stops first, so its last chunk is queued before the writer can finish.
        stopAudio(current)
        synchronized(current) {
            current.stopped = true
            current.ring.close()
        }
        current.thread.join(writerJoinTimeoutMs)
        if (current.thread.isAlive) {
            current.fail(RawTakeReasons.WRITER_STALLED)
            stalledWriter = current.thread
            take = null
            return@synchronized result(current, uri = null, framesOnDisk = current.framesWritten, recovered = false,
                outputError = "writer still running; the file stays pending for recovery")
        }
        val clean = current.writerError == null && current.writerStats?.endWritten == true
        var framesOnDisk = current.framesWritten
        var recovered = false
        if (!clean) {
            val repair = recoverRawTakeFile(current.output.channel, fallbackFrames = current.framesWritten)
            framesOnDisk = repair.frames.toLong()
            recovered = repair.action != null
        }
        var uri: Uri? = null
        var outputError: String? = null
        try {
            if (framesOnDisk > 0) uri = current.output.publish() else current.output.discard()
        } catch (problem: Exception) {
            outputError = "${problem.javaClass.simpleName}: ${problem.message.orEmpty().take(160)}"
            android.util.Log.e(TAG, "RAW take output could not be finalised", problem)
        }
        take = null
        result(current, uri, framesOnDisk, recovered, outputError)
    }

    private fun result(current: Take, uri: Uri?, framesOnDisk: Long, recovered: Boolean, outputError: String?) =
        RawTakeResult(
            uri = uri,
            displayName = current.output.displayName,
            framesCaptured = current.framesCaptured,
            framesWritten = framesOnDisk,
            bytesWritten = current.bytesWritten,
            droppedFrames = 0,
            frameMetadataWritten = current.frameMetadataWritten,
            unmatchedResults = current.unmatchedResults.get(),
            peakRingFraction = current.ring.peakFraction,
            mediaDurationNs = if (current.firstTimestampNs >= 0) current.lastTimestampNs - current.firstTimestampNs else 0,
            elapsedNs = clock() - current.startedAtNs,
            reason = current.failure.get(),
            recovered = recovered,
            outputError = outputError,
            writerStats = current.writerStats,
            encoding = current.armed.config.encoding,
            nearLossless = current.armed.nearLossless,
            framesEncoded = current.compressor?.framesEncoded ?: 0,
            framesStoredUncompressed = current.compressor?.framesStoredUncompressed ?: 0,
            compressionRatio = current.compressor?.compressionRatio,
            encodeMeanMs = current.compressor?.encodeMeanMs,
            encodeMaxMs = current.compressor?.encodeMaxMs,
            audioSampleRate = current.audioMetadata[RawAudioKeys.SAMPLE_RATE]?.toIntOrNull(),
            audioChannels = current.audioMetadata[RawAudioKeys.CHANNELS]?.toIntOrNull(),
            audioFramesWritten = current.audioFramesWritten,
            audioDroppedFrames = current.audioDroppedFrames.get(),
            audioError = current.audioStartError ?: current.audio?.failure,
        )

    fun liveStats(): RawVideoLiveStats {
        val current = take ?: return RawVideoLiveStats.IDLE
        return RawVideoLiveStats(
            recording = true,
            framesCaptured = current.framesCaptured,
            framesWritten = current.framesWritten,
            bytesWritten = current.bytesWritten,
            ringUsedFraction = current.ring.usedFraction,
            ringPeakFraction = current.ring.peakFraction,
            droppedFrames = 0,
            elapsedNs = clock() - current.startedAtNs,
            failureReason = current.failure.get(),
            compressionRatio = current.compressor?.compressionRatio,
            encodeMeanMs = current.compressor?.encodeMeanMs,
        )
    }

    // region RawVideoFrameConsumer

    override fun onRawTakeStarted(cameraId: String, characteristics: CameraCharacteristics, config: RawVideoSessionConfig) {
        onTakeStarted(cameraId, config, RawVideoMetadata.staticValues(characteristics))
    }

    override fun onRawFrame(image: Image): Boolean {
        val plane = image.planes[0]
        return onFrame(plane.buffer, plane.rowStride, image.width, image.height, image.timestamp)
    }

    override fun onRawResult(result: TotalCaptureResult) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        if (take == null) return
        onResult(timestamp, result.frameNumber, RawVideoMetadata.frameValues(result))
    }

    override fun onRawStreamFailed(reason: String) {
        take?.fail(RawTakeReasons.STREAM_FAILED_PREFIX + reason)
    }

    // endregion

    /** [onRawTakeStarted] without `CameraCharacteristics`, for tests. */
    internal fun onTakeStarted(cameraId: String, config: RawVideoSessionConfig, staticValues: Map<String, String>) {
        val current = take ?: return
        current.startedCameraId = cameraId
        current.sessionConfig = config
        current.staticValues = staticValues
    }

    /** [onRawFrame] over the plane of a RAW10 image. */
    internal fun onFrame(plane: ByteBuffer, rowStride: Int, width: Int, height: Int, timestampNs: Long): Boolean {
        val current = take ?: return false
        synchronized(current) {
            if (current.stopped || current.failure.get() != null) return false
            val ring = current.ring
            val crop = current.armed.crop
            val slot = ring.tryAcquire() ?: run {
                // Unreachable while the high-water check below runs after every frame; kept as a guard.
                current.fail(RawTakeReasons.RING_FULL)
                return false
            }
            val header = try {
                copyRaw10Rows(plane, rowStride, width, height, crop.left, crop.top, crop.width, crop.height, slot.buffer)
                OcrawFrameHeader.raw10(crop.width, crop.height, crop.left, crop.top, timestampNs, current.framesCaptured)
            } catch (problem: IllegalArgumentException) {
                ring.cancel(slot)
                current.fail(RawTakeReasons.FRAME_INVALID)
                return false
            }
            ring.publish(slot, header)
            current.framesCaptured++
            if (ring.isAboveHighWater(highWaterFraction)) {
                // The frame is kept; the take ends before the ring can overflow.
                current.fail(RawTakeReasons.RING_FULL)
                return false
            }
            return true
        }
    }

    /** [onRawResult] with the values already extracted. */
    internal fun onResult(timestampNs: Long, frameNumber: Long, values: Map<String, String>) {
        val current = take ?: return
        val withFrameNumber = if (frameNumber >= 0) values + (FRAME_NUMBER_KEY to frameNumber.toString()) else values
        val result = FrameResult(timestampNs, frameNumber, withFrameNumber)
        while (!current.results.offer(result)) {
            // The writer is far behind on results: the oldest one goes.
            if (current.results.poll() != null) current.unmatchedResults.incrementAndGet()
        }
    }

    // region writer thread

    private fun runWriter(current: Take) {
        val writer = try {
            OcrawStreamWriter(current.output.channel, OcrawHeader(uuidBytes(current.info.takeId)), TIME_BASE, writerOptions)
        } catch (problem: Exception) {
            writerFailed(current, problem)
            null
        }
        val pending = HashMap<Long, FrameResult>()
        val awaiting = object : LinkedHashMap<Long, Long>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > resultWindowFrames
        }
        var metadataWritten = false
        // The most recent ISO a capture result reported, for frames whose own result is still to come.
        var latestIso: Int? = null

        fun writable() = writer != null && current.writerError == null

        fun handle(result: FrameResult) {
            result.iso?.let { latestIso = it }
            val sequence = awaiting.remove(result.timestampNs)
            if (sequence != null) {
                if (writable()) guarded(current) {
                    writer!!.writeFrameMetadata(sequence, result.values, result.timestampNs)
                    current.frameMetadataWritten++
                }
            } else {
                result.arrivedAtFrame = current.framesWritten
                pending.put(result.timestampNs, result)?.let { current.unmatchedResults.incrementAndGet() }
            }
        }

        fun drainAudio() {
            // Nothing before the static metadata, which carries the rate and channels.
            if (!metadataWritten) return
            while (true) {
                val block = current.audioQueue.poll() ?: break
                if (writable()) {
                    guarded(current) {
                        writer!!.writeChunk(OcrawChunkId.AUDIO_PCM16.wireId, block.ticks, ByteBuffer.wrap(block.payload))
                        current.audioFramesWritten += block.frames
                        current.bytesWritten = writer.stats().bytesWritten
                    }
                    if (current.writerError != null) current.audioDroppedFrames.addAndGet(block.frames.toLong())
                } else {
                    current.audioDroppedFrames.addAndGet(block.frames.toLong())
                }
            }
        }

        fun drainResults() {
            while (true) handle(current.results.poll() ?: break)
            if (pending.isNotEmpty()) {
                val iterator = pending.values.iterator()
                while (iterator.hasNext()) {
                    if (current.framesWritten - iterator.next().arrivedAtFrame > resultWindowFrames) {
                        iterator.remove()
                        current.unmatchedResults.incrementAndGet()
                    }
                }
            }
        }

        while (true) {
            val slot = current.ring.take(WRITER_POLL_MS)
            drainResults()
            if (slot == null) {
                drainAudio()
                if (current.ring.isClosed && current.ring.queuedSlots == 0) break
                continue
            }
            try {
                if (writable()) guarded(current) {
                    // The header's frame number is the take-local index set at capture (contiguous
                    // from 0, so a reader can detect a gap); the Camera2 frame number is metadata.
                    val header = requireNotNull(slot.header)
                    val timestamp = header.sensorTimestampNs
                    if (!metadataWritten) {
                        writer!!.writeMetadata(staticMetadata(current), ticksOf(timestamp))
                        metadataWritten = true
                    }
                    val result = pending.remove(timestamp)
                    // Compressed takes encode here, off the producer; the slot is released only after the write.
                    val (frameHeader, data) = current.compressor?.compress(header, slot.buffer, result?.iso ?: latestIso)
                        ?: (header to slot.buffer)
                    val sequence = writer!!.writeRawFrame(frameHeader, data, ticksOf(timestamp))
                    if (current.firstTimestampNs < 0) current.firstTimestampNs = timestamp
                    current.lastTimestampNs = timestamp
                    current.framesWritten++
                    if (result != null) {
                        writer.writeFrameMetadata(sequence, result.values, timestamp)
                        current.frameMetadataWritten++
                    } else {
                        awaiting[timestamp] = sequence
                    }
                    current.bytesWritten = writer.stats().bytesWritten
                }
            } finally {
                current.ring.release(slot)
            }
            drainAudio()
        }

        // Results for the last frames may trail them slightly.
        if (writable() && awaiting.isNotEmpty() && finalResultWaitMs > 0) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(finalResultWaitMs)
            while (awaiting.isNotEmpty() && System.nanoTime() < deadline) {
                handle(current.results.poll(WRITER_POLL_MS, TimeUnit.MILLISECONDS) ?: continue)
            }
        }
        drainResults()
        current.unmatchedResults.addAndGet(pending.size.toLong())

        // stop() stopped the audio source before closing the ring, so every chunk is queued by now.
        drainAudio()
        while (true) current.audioDroppedFrames.addAndGet((current.audioQueue.poll() ?: break).frames.toLong())
        if (current.audioMetadata.isNotEmpty() && metadataWritten && writable()) guarded(current) {
            writer!!.writeMetadata(finalAudioMetadata(current))
        }

        if (writer != null) {
            current.writerStats = if (current.writerError == null) {
                try {
                    writer.finish()
                } catch (problem: Exception) {
                    writerFailed(current, problem)
                    writer.abort()
                }
            } else {
                writer.abort()
            }
            current.bytesWritten = current.writerStats?.bytesWritten ?: current.bytesWritten
        }
    }

    private inline fun guarded(current: Take, block: () -> Unit) {
        try {
            block()
        } catch (problem: Exception) {
            writerFailed(current, problem)
        }
    }

    private fun writerFailed(current: Take, problem: Throwable) {
        if (current.writerError == null) current.writerError = problem
        android.util.Log.e(TAG, "RAW writer failed", problem)
        current.fail(RawTakeReasons.WRITER_ERROR)
    }

    private fun staticMetadata(current: Take): Map<String, String> {
        val session = current.sessionConfig
        val config = current.armed.config
        val values = LinkedHashMap<String, String>()
        values.putAll(current.info.extraMetadata)
        values.putAll(current.staticValues)
        values.putAll(
            RawVideoMetadata.baseValues(
                cameraId = current.startedCameraId ?: config.cameraId,
                rawWidth = session?.rawWidth ?: config.sensorWidth,
                rawHeight = session?.rawHeight ?: config.sensorHeight,
                crop = current.armed.crop,
                fps = session?.fps ?: config.fps,
                model = current.info.model,
                appVersion = current.info.appVersion,
            ),
        )
        values[RawFrameEncoders.KEY_ENCODING] = config.encoding
        // A near-lossless encoder's keys replace the encoding with `bpq`.
        current.compressor?.let { values.putAll(it.metadata) }
        values.putAll(current.audioMetadata)
        return values
    }

    /** The closing `METADATA` chunk of a take with sound; a later key wins over the first chunk's. */
    private fun finalAudioMetadata(current: Take): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        current.audio?.let { audio ->
            runCatching { values.putAll(audio.finalMetadata()) }
            audio.failure?.let { values[RawAudioKeys.ERROR] = it }
        }
        values[RawAudioKeys.DROPPED_SAMPLES] = current.audioDroppedFrames.get().toString()
        return values
    }

    // endregion

    companion object {
        private const val TAG = "RawVideoRecorder"
        const val HIGH_WATER_FRACTION = 0.9
        /** FRAME_METADATA key of the Camera2 capture frame number (`TotalCaptureResult.getFrameNumber`). */
        const val FRAME_NUMBER_KEY = "frameNumber"
        /** The `FRAME_METADATA` key of the sensitivity ([RawVideoMetadata.frameValues]). */
        const val ISO_KEY = "iso"
        /**
         * How many frames a capture result may trail its frame (or lead it) and still become its
         * metadata. The Razr has stalled its results for about 0.75 s at a take's start and then
         * delivered them in a burst, up to 45 frames late at 60 fps; 256 frames (over 4 s at 60 fps)
         * cost a few hundred bytes of timestamps and leave room for that.
         */
        const val RESULT_WINDOW_FRAMES = 256
        const val WRITER_JOIN_TIMEOUT_MS = 30_000L
        const val FINAL_RESULT_WAIT_MS = 100L
        private const val RESULT_INBOX = 256
        private const val WRITER_POLL_MS = 20L
        val TIME_BASE = OcrawTimeBase(1, 1_000_000_000)
        /** About 3.2 s of 100 ms audio chunks between the audio thread and the writer. */
        const val AUDIO_QUEUE_CHUNKS = 32

        /**
         * Chunk ticks of a sensor-clock time in ns, for frames and audio alike: [TIME_BASE] is 1 ns, so
         * this is the time itself, kept non-negative as the format requires.
         */
        fun ticksOf(sensorNs: Long): Long = sensorNs.coerceAtLeast(0)

        internal fun uuidBytes(takeId: String): ByteArray {
            val uuid = runCatching { UUID.fromString(takeId) }.getOrElse { UUID.randomUUID() }
            return ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        }
    }
}
