/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

enum class PcmMeterEncoding(val bytesPerSample: Int) {
    PCM_16(2),
    PCM_24(3),
    PCM_FLOAT(4),
}

data class AudioChannelLevel(
    val peakDbfs: Float,
    val rmsDbfs: Float,
    val vuDbfs: Float? = null,
    val ppmDbfs: Float? = null,
)

data class AudioLevelSnapshot(
    val channels: List<AudioChannelLevel>,
    val clipped: Boolean,
    val capturedAtElapsedRealtimeMs: Long,
    val appliedRecordingGain: DigitalRecordingGain? = null,
    val effects: AudioEffectsSnapshot? = null,
    /** The microphone's own samples reached full scale, before the equaliser, the AGC and the gain. */
    val inputClipped: Boolean = false,
    /** The samples that go to the file reached full scale, after that processing. */
    val recordedClipped: Boolean = false,
    /**
     * The microphone's own samples clipped below full scale (PLAN-074 decision 14): an analogue or vendor stage
     * saturated before the app saw them, which [inputClipped] cannot tell.
     */
    val inputOverloaded: Boolean = false,
)

/**
 * A MediaRecorder peak amplitude (0..32767 since the previous read) as a mono peak-only reading.
 * RMS is NaN because the recorder never exposes the samples it came from.
 */
fun recorderPeakSnapshot(amplitude: Int, capturedAtElapsedRealtimeMs: Long): AudioLevelSnapshot {
    val clamped = amplitude.coerceIn(0, Short.MAX_VALUE.toInt())
    val peakDbfs = if (clamped == 0) -120f else (20.0 * log10(clamped / Short.MAX_VALUE.toDouble())).toFloat()
    return AudioLevelSnapshot(
        channels = listOf(AudioChannelLevel(peakDbfs = peakDbfs, rmsDbfs = Float.NaN)),
        clipped = clamped >= Short.MAX_VALUE,
        capturedAtElapsedRealtimeMs = capturedAtElapsedRealtimeMs,
    )
}

/** Pure bounded PCM level calculator. It never mutates the recorder-owned buffer. */
class AudioLevelMeter(
    private val encoding: PcmMeterEncoding,
    private val channels: Int,
    private val publishIntervalMs: Long = 100L,
    private val sampleRateHz: Int = 48_000,
) {
    private var lastPublishedAtMs = Long.MIN_VALUE
    private var inputClipPending = false
    private val peaks: DoubleArray
    private val squares: DoubleArray
    private val ballistics: Array<AudioMeterBallistics>
    private var windowFrames = 0L

    // The input-overload detector's window, per channel, between two published snapshots. The window's
    // peak is only known at its end, so the runs are measured against the running ceiling and start over
    // whenever the ceiling rises by more than one step.
    private val overloadCeilings: DoubleArray
    private val overloadSquares: DoubleArray
    private val overloadRunLengths: IntArray
    private val overloadRunSigns: IntArray
    private val overloadRuns: IntArray
    private val overloadRunSamples: LongArray
    private var overloadFrames = 0L

    init {
        require(channels in 1..2)
        require(publishIntervalMs >= 0L)
        require(sampleRateHz in 8_000..192_000)
        peaks = DoubleArray(channels)
        squares = DoubleArray(channels)
        ballistics = Array(channels) { AudioMeterBallistics(sampleRateHz) }
        overloadCeilings = DoubleArray(channels)
        overloadSquares = DoubleArray(channels)
        overloadRunLengths = IntArray(channels)
        overloadRunSigns = IntArray(channels)
        overloadRuns = IntArray(channels)
        overloadRunSamples = LongArray(channels)
    }

    /**
     * Observe recorder PCM before AGC/digital gain. A near-full-scale input survives attenuation
     * and throttled publications; clipping below full scale shows as [AudioLevelSnapshot.inputOverloaded].
     * Call on the same producer thread as [analyze]; neither method mutates the input buffer.
     */
    fun observeInput(buffer: ByteBuffer, byteCount: Int) {
        val frameBytes = encoding.bytesPerSample * channels
        val usableBytes = byteCount.coerceAtMost(buffer.capacity()) / frameBytes * frameBytes
        if (usableBytes <= 0) return
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            clear()
            limit(usableBytes)
        }
        while (input.remaining() >= frameBytes) {
            for (channel in 0 until channels) {
                val sample = readNormalized(input)
                if (abs(sample) >= CLIP_AMPLITUDE) inputClipPending = true
                observeOverload(channel, sample)
            }
            overloadFrames++
        }
    }

    /** One input sample into [channel]'s overload window: its ceiling, energy and flat runs at the ceiling. */
    private fun observeOverload(channel: Int, sample: Double) {
        val magnitude = abs(sample)
        overloadSquares[channel] += sample * sample
        val ceiling = overloadCeilings[channel]
        val sign = if (sample < 0.0) -1 else 1
        if (magnitude > ceiling + OVERLOAD_STEP) {
            // A higher ceiling: the runs so far were flat against a lower one, so they no longer count.
            overloadCeilings[channel] = magnitude
            overloadRuns[channel] = 0
            overloadRunSamples[channel] = 0L
            overloadRunLengths[channel] = 1
            overloadRunSigns[channel] = sign
            return
        }
        if (magnitude > ceiling) overloadCeilings[channel] = magnitude
        if (magnitude < ceiling - OVERLOAD_STEP) {
            overloadRunLengths[channel] = 0
            return
        }
        val length = if (overloadRunLengths[channel] > 0 && overloadRunSigns[channel] == sign) overloadRunLengths[channel] + 1 else 1
        overloadRunLengths[channel] = length
        overloadRunSigns[channel] = sign
        // A run counts once it is long enough; each later sample of it adds to the flat share.
        if (length == OVERLOAD_MIN_RUN) {
            overloadRuns[channel]++
            overloadRunSamples[channel] += OVERLOAD_MIN_RUN.toLong()
        } else if (length > OVERLOAD_MIN_RUN) {
            overloadRunSamples[channel]++
        }
    }

    /** Whether any channel's window clipped flat below full scale with a collapsed crest factor; then starts a new window. */
    private fun takeInputOverload(): Boolean {
        var overloaded = false
        if (overloadFrames > 0L) {
            for (channel in 0 until channels) {
                val peak = overloadCeilings[channel]
                val meanSquare = overloadSquares[channel] / overloadFrames
                if (peak >= OVERLOAD_MIN_PEAK && peak < CLIP_AMPLITUDE &&
                    overloadRuns[channel] >= OVERLOAD_MIN_RUNS &&
                    overloadRunSamples[channel] >= overloadFrames * OVERLOAD_MIN_FLAT_SHARE &&
                    peak * peak < meanSquare * OVERLOAD_MAX_CREST_POWER) overloaded = true
            }
        }
        overloadCeilings.fill(0.0)
        overloadSquares.fill(0.0)
        overloadRunLengths.fill(0)
        overloadRunSigns.fill(0)
        overloadRuns.fill(0)
        overloadRunSamples.fill(0L)
        overloadFrames = 0L
        return overloaded
    }

    /**
     * Consumes every complete PCM frame before publication throttling. Peak/RMS cover the entire
     * interval since the previous publication; VU/PPM retain continuous sample-clock ballistics.
     * A UI clock change never advances DSP time, and an empty call never releases pending evidence.
     */
    fun analyze(buffer: ByteBuffer, byteCount: Int, capturedAtElapsedRealtimeMs: Long): AudioLevelSnapshot? {
        val frameBytes = encoding.bytesPerSample * channels
        val usableBytes = byteCount.coerceAtMost(buffer.capacity()) / frameBytes * frameBytes
        if (usableBytes <= 0) return null
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            clear()
            limit(usableBytes)
        }
        while (input.remaining() >= frameBytes) {
            for (channel in 0 until channels) {
                val normalized = readNormalized(input)
                val magnitude = abs(normalized)
                peaks[channel] = maxOf(peaks[channel], magnitude)
                squares[channel] += normalized * normalized
                ballistics[channel].process(normalized)
            }
            windowFrames = Math.addExact(windowFrames, 1L)
        }
        if (lastPublishedAtMs != Long.MIN_VALUE && capturedAtElapsedRealtimeMs >= lastPublishedAtMs &&
            capturedAtElapsedRealtimeMs - lastPublishedAtMs < publishIntervalMs) return null
        lastPublishedAtMs = capturedAtElapsedRealtimeMs
        val levels = (0 until channels).map { index ->
            AudioChannelLevel(
                peakDbfs = amplitudeToDbfs(peaks[index]),
                rmsDbfs = amplitudeToDbfs(sqrt(squares[index] / windowFrames)),
                vuDbfs = amplitudeToDbfs(ballistics[index].vuAmplitude),
                ppmDbfs = amplitudeToDbfs(ballistics[index].ppmAmplitude),
            )
        }
        val inputClipped = inputClipPending
        val recordedClipped = peaks.any { it >= CLIP_AMPLITUDE }
        val inputOverloaded = takeInputOverload()
        inputClipPending = false
        peaks.fill(0.0)
        squares.fill(0.0)
        windowFrames = 0L
        return AudioLevelSnapshot(levels, inputClipped || recordedClipped, capturedAtElapsedRealtimeMs,
            inputClipped = inputClipped, recordedClipped = recordedClipped, inputOverloaded = inputOverloaded)
    }

    private fun readNormalized(input: ByteBuffer): Double = readPcmSample(input, encoding)

    private fun amplitudeToDbfs(amplitude: Double): Float =
        (20.0 * log10(amplitude.coerceAtLeast(MIN_AMPLITUDE))).coerceAtLeast(MIN_DBFS).toFloat()

    companion object {
        const val CLIP_DBFS = -0.5f
        private const val MIN_DBFS = -120.0
        private const val MIN_AMPLITUDE = 0.000001
        private val CLIP_AMPLITUDE = Math.pow(10.0, CLIP_DBFS / 20.0)

        // The input-overload thresholds are provisional until measured on devices (PLAN-074 Delivery 3).
        /** One 16-bit step: a flat run's samples are within this of the window's ceiling. */
        private const val OVERLOAD_STEP = 1.0 / 32_768.0
        /** A window quieter than -12 dBFS at its peak is not called overloaded. */
        private val OVERLOAD_MIN_PEAK = Math.pow(10.0, -12.0 / 20.0)
        /** Consecutive samples at the ceiling, of one sign, that make a flat run. */
        private const val OVERLOAD_MIN_RUN = 4
        /** Flat runs a window needs. */
        private const val OVERLOAD_MIN_RUNS = 3
        /**
         * Share of the window's samples that must be in flat runs. At 1 % a clean 16-bit sine at 50 Hz and -6 dBFS
         * already qualifies (up to five samples within one step of each crest), so 2 %.
         */
        private const val OVERLOAD_MIN_FLAT_SHARE = 0.02
        /** The crest factor (peak / rms) must be under 12 dB, here as a power ratio. */
        private val OVERLOAD_MAX_CREST_POWER = Math.pow(10.0, 12.0 / 10.0)
    }
}

/**
 * Reads one sample at [input]'s position as a fraction of full scale, advancing it; [input] must be little-endian.
 * A non-finite float reads as silence.
 */
internal fun readPcmSample(input: ByteBuffer, encoding: PcmMeterEncoding): Double = when (encoding) {
    PcmMeterEncoding.PCM_16 -> input.short.toInt() / 32768.0
    PcmMeterEncoding.PCM_24 -> {
        val raw = (input.get().toInt() and 0xff) or
            ((input.get().toInt() and 0xff) shl 8) or
            ((input.get().toInt() and 0xff) shl 16)
        val signed = if (raw and 0x800000 != 0) raw or -0x1000000 else raw
        signed / 8_388_608.0
    }
    PcmMeterEncoding.PCM_FLOAT -> input.float.let { if (it.isFinite()) it.toDouble() else 0.0 }
}
