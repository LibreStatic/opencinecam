/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import android.media.AudioFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The JNI surface of `libocc_aaudio.so` (`media/src/main/cpp/occ_aaudio_jni.cpp`). The library
 * loads on first use of [isAvailable]; host JVM tests and Robolectric have no such library, so
 * [isAvailable] is false there and nothing below may be called. [AAudioPcmInput] is the public
 * entry point.
 */
internal object AAudioNative {
    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("occ_aaudio")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    // aaudio_result_t and enum values from <aaudio/AAudio.h>.
    const val AAUDIO_OK = 0
    const val AAUDIO_ERROR_DISCONNECTED = -899
    const val AAUDIO_ERROR_TIMEOUT = -885
    const val AAUDIO_FORMAT_PCM_I16 = 1
    const val AAUDIO_PERFORMANCE_MODE_LOW_LATENCY = 12
    const val AAUDIO_INPUT_PRESET_UNPROCESSED = 9

    // Indices of the int[] nativeDescribe fills, as occ_aaudio_jni.cpp lays it out.
    const val INFO_SAMPLE_RATE = 0
    const val INFO_CHANNELS = 1
    const val INFO_FORMAT = 2
    const val INFO_PERFORMANCE_MODE = 3
    const val INFO_DEVICE_ID = 4
    const val INFO_FRAMES_PER_BURST = 5
    const val INFO_SESSION_ID = 6
    const val INFO_BUFFER_CAPACITY = 7
    const val INFO_COUNT = 8

    /** Returns the stream handle, or 0 with the `aaudio_result_t` in `result[0]`. */
    @JvmStatic external fun nativeOpen(
        sampleRate: Int,
        channels: Int,
        inputPreset: Int,
        /** 0 lets the platform route. */
        deviceId: Int,
        /** 0 keeps the platform's buffer capacity. */
        capacityFrames: Int,
        allocateSession: Boolean,
        result: IntArray,
    ): Long

    /** Fills [out] (`INFO_COUNT` ints, `INFO_*` indices); false for a 0 handle. */
    @JvmStatic external fun nativeDescribe(handle: Long, out: IntArray): Boolean

    @JvmStatic external fun nativeStart(handle: Long): Int

    @JvmStatic external fun nativeStop(handle: Long): Int

    /** Frames read into the start of [buffer] (fewer or 0 on a timeout), or a negative `aaudio_result_t`. */
    @JvmStatic external fun nativeRead(handle: Long, buffer: ByteBuffer, frames: Int, timeoutNanos: Long): Int

    /** `out[0]` frame position, `out[1]` CLOCK_BOOTTIME nanoseconds; false until published. */
    @JvmStatic external fun nativeGetTimestamp(handle: Long, out: LongArray): Boolean

    @JvmStatic external fun nativeXRunCount(handle: Long): Int

    /** Frees the stream; the handle must not be used again. */
    @JvmStatic external fun nativeClose(handle: Long)

    @JvmStatic external fun nativeResultText(result: Int): String

    /** `AAudio_convertResultToText`, or the number when the library gives no text. */
    fun resultText(result: Int): String =
        runCatching { nativeResultText(result) }.getOrDefault("").ifEmpty { "$result" }
}

/** The low-latency input could not be opened as asked; the caller falls back to `AudioRecord`. */
class PcmInputUnavailableException(
    /**
     * Machine-readable cause: [REASON_NATIVE_LIBRARY], `open-failed:<aaudio_result_t>`,
     * [REASON_SAMPLE_RATE_MISMATCH], [REASON_CHANNEL_MISMATCH], [REASON_FORMAT_MISMATCH],
     * [REASON_NOT_LOW_LATENCY] or [REASON_DESCRIBE_FAILED].
     */
    val reason: String,
    message: String,
) : IllegalStateException(message) {
    companion object {
        const val REASON_NATIVE_LIBRARY = "native-library"
        const val REASON_OPEN_FAILED_PREFIX = "open-failed:"
        const val REASON_DESCRIBE_FAILED = "describe-failed"
        const val REASON_SAMPLE_RATE_MISMATCH = "sample-rate-mismatch"
        const val REASON_CHANNEL_MISMATCH = "channel-mismatch"
        const val REASON_FORMAT_MISMATCH = "format-mismatch"
        const val REASON_NOT_LOW_LATENCY = "not-low-latency"
    }
}

/** What an AAudio stream actually opened as, read back after the open. */
internal data class AAudioStreamInfo(
    val sampleRateHz: Int,
    val channels: Int,
    val format: Int,
    val performanceMode: Int,
    val deviceId: Int,
    val framesPerBurst: Int,
    val sessionId: Int,
    val bufferCapacityFrames: Int,
)

/**
 * Why an opened stream is refused, or null when it carries what was asked: the requested rate
 * and channel count, PCM16 (PLAN-074 decision 4). AAudio may open a stream that differs from the
 * request; such a stream would resample or remix.
 *
 * A stream that lost the low-latency mode is refused too. On the Razr the audio server then reopens
 * it on the ordinary input, where `UNPROCESSED` is one microphone on both channels with 18 dB of
 * codec gain: the path that clips, not the one the bundled equaliser was measured on.
 */
internal fun aaudioStreamRefusal(requestedRateHz: Int, requestedChannels: Int, stream: AAudioStreamInfo): String? = when {
    stream.sampleRateHz != requestedRateHz -> PcmInputUnavailableException.REASON_SAMPLE_RATE_MISMATCH
    stream.channels != requestedChannels -> PcmInputUnavailableException.REASON_CHANNEL_MISMATCH
    stream.format != AAudioNative.AAUDIO_FORMAT_PCM_I16 -> PcmInputUnavailableException.REASON_FORMAT_MISMATCH
    stream.performanceMode != AAudioNative.AAUDIO_PERFORMANCE_MODE_LOW_LATENCY ->
        PcmInputUnavailableException.REASON_NOT_LOW_LATENCY
    else -> null
}

/**
 * `AAudioStream_read` results as [PcmInput.read] reports them: frames become bytes, a timeout
 * with nothing read is 0, a disconnected stream is [PcmInput.ERROR_DEAD] and every other error
 * [PcmInput.ERROR_INVALID].
 */
internal fun aaudioReadResult(result: Int, frameBytes: Int): Int = when {
    result >= 0 -> result * frameBytes
    result == AAudioNative.AAUDIO_ERROR_TIMEOUT -> 0
    result == AAudioNative.AAUDIO_ERROR_DISCONNECTED -> PcmInput.ERROR_DEAD
    else -> PcmInput.ERROR_INVALID
}

/**
 * The capacity asked of the stream: 0, the platform's own. The audio server grants its fast input
 * only to a client whose buffer fits the fast capture pipe; a larger one (the Razr refused 200 ms)
 * silently loses the low-latency mode. The reader therefore has to keep up with a short buffer.
 */
internal const val AAUDIO_PLATFORM_BUFFER_CAPACITY = 0

/**
 * The native AAudio input in low-latency mode (PLAN-074 decision 4): PCM16, shared, input preset
 * `UNPROCESSED` by default. Platform effects are not attached to it, and it has no `AudioRouting`;
 * a pulled device shows as [PcmInput.ERROR_DEAD] from [read].
 *
 * [close] may be called from another thread while [read] is blocked: it stops the stream first,
 * then waits for the read to return (at most [READ_TIMEOUT_NS]) before freeing it.
 */
class AAudioPcmInput private constructor(
    handle: Long,
    private val info: AAudioStreamInfo,
    /** The `aaudio_input_preset_t` the stream was opened with. */
    val inputPreset: Int,
) : PcmInput {
    private val lock = ReentrantReadWriteLock()
    private val closed = AtomicBoolean(false)
    // Read under the read lock, cleared under the write lock.
    private var handle: Long = handle
    private val stamp = LongArray(2)

    override val sampleRateHz: Int get() = info.sampleRateHz
    override val channels: Int get() = info.channels
    override val encoding: Int get() = AudioFormat.ENCODING_PCM_16BIT
    override val audioSessionId: Int? get() = info.sessionId.takeIf { it > 0 }
    override val routedDeviceId: Int? get() = info.deviceId.takeIf { it > 0 }
    override val path: PcmInputPath get() = PcmInputPath.AAUDIO_LOW_LATENCY

    /** The `aaudio_performance_mode_t` the stream reports; always low latency once opened. */
    val performanceMode: Int get() = info.performanceMode
    val framesPerBurst: Int get() = info.framesPerBurst
    val bufferCapacityFrames: Int get() = info.bufferCapacityFrames

    /** Overruns and underruns so far; 0 once closed. */
    val xRunCount: Int get() = lock.read { handle.let { if (it == 0L) 0 else AAudioNative.nativeXRunCount(it) } }

    private val frameBytes = info.channels * BYTES_PER_SAMPLE

    override fun start() {
        val result = lock.read {
            val h = handle
            check(h != 0L && !closed.get()) { "AAudio input is closed." }
            AAudioNative.nativeStart(h)
        }
        check(result == AAudioNative.AAUDIO_OK) { "AAudio input did not start: ${AAudioNative.resultText(result)}." }
    }

    override fun read(buffer: ByteBuffer, byteCount: Int): Int {
        val frames = byteCount / frameBytes
        if (frames <= 0 || !buffer.isDirect || byteCount > buffer.capacity()) return PcmInput.ERROR_INVALID
        return lock.read {
            val h = handle
            if (h == 0L || closed.get()) PcmInput.ERROR_DEAD
            else aaudioReadResult(AAudioNative.nativeRead(h, buffer, frames, READ_TIMEOUT_NS), frameBytes)
        }
    }

    override fun timestamp(out: PcmInputTimestamp): Boolean = lock.read {
        val h = handle
        if (h == 0L) return false
        synchronized(stamp) {
            if (!AAudioNative.nativeGetTimestamp(h, stamp)) return false
            out.framePosition = stamp[0]
            out.nanoTime = stamp[1]
        }
        true
    }

    override fun stop() {
        lock.read { handle.let { if (it != 0L) AAudioNative.nativeStop(it) } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Stopping wakes a blocked read sooner; the write lock then waits for it to leave.
        stop()
        lock.write {
            val h = handle
            handle = 0L
            // The stream keeps the platform's short buffer, so a stalled reader loses audio here.
            val xRuns = if (h == 0L) 0 else AAudioNative.nativeXRunCount(h)
            if (xRuns > 0) Log.w("OccAAudioInput", "Native input closed after $xRuns overruns.")
            AAudioNative.nativeClose(h)
        }
    }

    companion object {
        const val INPUT_PRESET_UNPROCESSED = AAudioNative.AAUDIO_INPUT_PRESET_UNPROCESSED

        /**
         * A blocking read gives up after this long and reports 0 bytes. It also bounds how long a
         * stop waits when `requestStop` does not wake the read.
         */
        const val READ_TIMEOUT_NS = 500_000_000L

        private const val BYTES_PER_SAMPLE = 2

        /**
         * Opens the low-latency input, or throws [PcmInputUnavailableException] when the library
         * is missing, the open fails, or the stream is not exactly what was asked (see
         * [PcmInputUnavailableException.reason]); a refused stream is closed before the throw.
         * Callers check `RECORD_AUDIO` themselves before calling. An invalid request (a rate or
         * channel count not above 0) throws [IllegalArgumentException].
         *
         * [deviceId] null lets the platform route. [allocateSession] asks AAudio for a session id
         * so platform effects could attach; AAudio warns it may cost the low-latency path, and a
         * stream that loses it is refused, so leave it off unless effects are needed.
         */
        fun open(
            sampleRateHz: Int,
            channels: Int,
            deviceId: Int? = null,
            inputPreset: Int = INPUT_PRESET_UNPROCESSED,
            allocateSession: Boolean = false,
        ): AAudioPcmInput {
            require(sampleRateHz > 0 && channels > 0) { "Invalid AAudio request: $sampleRateHz Hz, $channels channels." }
            if (!AAudioNative.isAvailable) {
                throw PcmInputUnavailableException(
                    PcmInputUnavailableException.REASON_NATIVE_LIBRARY, "libocc_aaudio.so did not load.")
            }
            val result = IntArray(1)
            val handle = AAudioNative.nativeOpen(
                sampleRateHz, channels, inputPreset, deviceId ?: 0,
                AAUDIO_PLATFORM_BUFFER_CAPACITY, allocateSession, result)
            if (handle == 0L) {
                throw PcmInputUnavailableException(
                    PcmInputUnavailableException.REASON_OPEN_FAILED_PREFIX + result[0],
                    "AAudio input did not open: ${AAudioNative.resultText(result[0])}.")
            }
            try {
                val values = IntArray(AAudioNative.INFO_COUNT)
                if (!AAudioNative.nativeDescribe(handle, values)) {
                    throw PcmInputUnavailableException(
                        PcmInputUnavailableException.REASON_DESCRIBE_FAILED, "AAudio input could not be described.")
                }
                val info = AAudioStreamInfo(
                    sampleRateHz = values[AAudioNative.INFO_SAMPLE_RATE],
                    channels = values[AAudioNative.INFO_CHANNELS],
                    format = values[AAudioNative.INFO_FORMAT],
                    performanceMode = values[AAudioNative.INFO_PERFORMANCE_MODE],
                    deviceId = values[AAudioNative.INFO_DEVICE_ID],
                    framesPerBurst = values[AAudioNative.INFO_FRAMES_PER_BURST],
                    sessionId = values[AAudioNative.INFO_SESSION_ID],
                    bufferCapacityFrames = values[AAudioNative.INFO_BUFFER_CAPACITY],
                )
                aaudioStreamRefusal(sampleRateHz, channels, info)?.let { reason ->
                    throw PcmInputUnavailableException(reason, "AAudio input refused ($reason): $info.")
                }
                return AAudioPcmInput(handle, info, inputPreset)
            } catch (t: Throwable) {
                AAudioNative.nativeClose(handle)
                throw t
            }
        }
    }
}
