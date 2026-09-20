/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/** Digital recording gain, not analog microphone gain or headphone playback volume. */
data class DigitalRecordingGain(val enabled: Boolean = false, val decibels: Int = 0) {
    init { require(decibels in -24..24) { "Digital recording gain must be between -24 and +24 dB." } }
    private val multiplier = 10.0.pow(decibels / 20.0)

    /**
     * Processes complete interleaved little-endian PCM frames at [0, byteCount). The source
     * buffer's position, limit, mark and byte order are untouched; a slice bounds its own bytes.
     * Disabled/0 dB is byte-identical, including nonfinite float bit patterns. Active float
     * processing retains digital headroom above 1, sanitizes nonfinite inputs to zero, and
     * saturates only representational overflow, never claims protection from analog clipping.
     */
    fun process(buffer: ByteBuffer, byteCount: Int, encoding: PcmMeterEncoding, channels: Int) {
        require(channels in 1..2)
        require(byteCount in 0..buffer.capacity())
        val frameBytes = encoding.bytesPerSample * channels
        require(byteCount % frameBytes == 0) { "Digital gain requires complete PCM frames." }
        if (!enabled || decibels == 0 || byteCount == 0) return
        require(!buffer.isReadOnly) { "Digital gain requires a writable PCM buffer." }
        val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { clear(); limit(byteCount) }
        for (offset in 0 until byteCount step encoding.bytesPerSample) {
            when (encoding) {
                PcmMeterEncoding.PCM_16 -> {
                    val value = (pcm.getShort(offset).toDouble() * multiplier).coerceIn(-32768.0, 32767.0).roundToInt()
                    pcm.putShort(offset, value.toShort())
                }
                PcmMeterEncoding.PCM_24 -> {
                    val value = (pcm.get(offset).toInt() and 255) or
                        ((pcm.get(offset + 1).toInt() and 255) shl 8) or (pcm.get(offset + 2).toInt() shl 16)
                    val amplified = (value.toDouble() * multiplier).coerceIn(-8388608.0, 8388607.0).roundToInt()
                    pcm.put(offset, amplified.toByte())
                    pcm.put(offset + 1, (amplified shr 8).toByte())
                    pcm.put(offset + 2, (amplified shr 16).toByte())
                }
                PcmMeterEncoding.PCM_FLOAT -> {
                    val input = pcm.getFloat(offset)
                    val value = if (input.isFinite()) (input.toDouble() * multiplier)
                        .coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat() else 0f
                    pcm.putFloat(offset, value)
                }
            }
        }
    }
}
