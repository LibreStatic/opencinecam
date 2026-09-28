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

    init {
        require(channels in 1..2)
        require(publishIntervalMs >= 0L)
        require(sampleRateHz in 8_000..192_000)
        peaks = DoubleArray(channels)
        squares = DoubleArray(channels)
        ballistics = Array(channels) { AudioMeterBallistics(sampleRateHz) }
    }

    /**
     * Observe recorder PCM before AGC/digital gain. A near-full-scale input survives attenuation
     * and throttled publications, without claiming that hidden analog clipping can be detected.
     * Call on the same producer thread as [analyze]; neither method mutates the input buffer.
     */
    fun observeInput(buffer: ByteBuffer, byteCount: Int) {
        if (inputClipPending) return
        val frameBytes = encoding.bytesPerSample * channels
        val usableBytes = byteCount.coerceAtMost(buffer.capacity()) / frameBytes * frameBytes
        if (usableBytes <= 0) return
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            clear()
            limit(usableBytes)
        }
        while (input.remaining() >= encoding.bytesPerSample) {
            if (abs(readNormalized(input)) >= CLIP_AMPLITUDE) {
                inputClipPending = true
                return
            }
        }
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
        val clipped = inputClipPending || peaks.any { it >= CLIP_AMPLITUDE }
        inputClipPending = false
        peaks.fill(0.0)
        squares.fill(0.0)
        windowFrames = 0L
        return AudioLevelSnapshot(levels, clipped, capturedAtElapsedRealtimeMs)
    }

    private fun readNormalized(input: ByteBuffer): Double = when (encoding) {
        PcmMeterEncoding.PCM_16 -> input.short.toInt() / 32768.0
        PcmMeterEncoding.PCM_24 -> {
            val raw = (input.get().toInt() and 0xff) or
                ((input.get().toInt() and 0xff) shl 8) or
                ((input.get().toInt() and 0xff) shl 16)
            val signed = if (raw and 0x800000 != 0) raw or -0x1000000 else raw
            signed / 8_388_608.0
        }
        PcmMeterEncoding.PCM_FLOAT -> input.float.takeIf { it.isFinite() }?.toDouble() ?: 0.0
    }

    private fun amplitudeToDbfs(amplitude: Double): Float =
        (20.0 * log10(amplitude.coerceAtLeast(MIN_AMPLITUDE))).coerceAtLeast(MIN_DBFS).toFloat()

    companion object {
        const val CLIP_DBFS = -0.5f
        private const val MIN_DBFS = -120.0
        private const val MIN_AMPLITUDE = 0.000001
        private val CLIP_AMPLITUDE = Math.pow(10.0, CLIP_DBFS / 20.0)
    }
}
