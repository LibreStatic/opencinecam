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

data class AudioChannelLevel(val peakDbfs: Float, val rmsDbfs: Float)

data class AudioLevelSnapshot(
    val channels: List<AudioChannelLevel>,
    val clipped: Boolean,
    val capturedAtElapsedRealtimeMs: Long,
)

/** Pure bounded PCM level calculator. It never mutates the recorder-owned buffer. */
class AudioLevelMeter(
    private val encoding: PcmMeterEncoding,
    private val channels: Int,
    private val publishIntervalMs: Long = 100L,
) {
    private var lastPublishedAtMs = Long.MIN_VALUE

    init {
        require(channels in 1..2)
        require(publishIntervalMs >= 0L)
    }

    fun analyze(buffer: ByteBuffer, byteCount: Int, capturedAtElapsedRealtimeMs: Long): AudioLevelSnapshot? {
        if (lastPublishedAtMs != Long.MIN_VALUE && capturedAtElapsedRealtimeMs - lastPublishedAtMs < publishIntervalMs) return null
        val frameBytes = encoding.bytesPerSample * channels
        val usableBytes = byteCount.coerceAtMost(buffer.capacity()) / frameBytes * frameBytes
        if (usableBytes <= 0) return null
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            position(0)
            limit(usableBytes)
        }
        val peaks = DoubleArray(channels)
        val squares = DoubleArray(channels)
        val samples = IntArray(channels)
        var channel = 0
        while (input.remaining() >= encoding.bytesPerSample) {
            val normalized = when (encoding) {
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
            val magnitude = abs(normalized)
            peaks[channel] = maxOf(peaks[channel], magnitude)
            squares[channel] += normalized * normalized
            samples[channel]++
            channel = (channel + 1) % channels
        }
        if (samples.any { it == 0 }) return null
        lastPublishedAtMs = capturedAtElapsedRealtimeMs
        val levels = (0 until channels).map { index ->
            AudioChannelLevel(
                peakDbfs = amplitudeToDbfs(peaks[index]),
                rmsDbfs = amplitudeToDbfs(sqrt(squares[index] / samples[index])),
            )
        }
        return AudioLevelSnapshot(
            channels = levels,
            clipped = peaks.any { it >= CLIP_AMPLITUDE },
            capturedAtElapsedRealtimeMs = capturedAtElapsedRealtimeMs,
        )
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
