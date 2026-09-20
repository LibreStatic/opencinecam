/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Software replacement for the platform AutomaticGainControl effect, for devices whose audio HAL
 * does not expose the public effect (observed on a tested device: create() fails with status
 * -2 and isAvailable() reports false even though the system XML declares an "agc" effect).
 *
 * Pure Kotlin with no Android dependencies so the behaviour is unit-testable and identical
 * across every capture pipeline.
 *
 * Signal path per interleaved PCM block:
 *  1. Block RMS estimate (all channels share one gain so the stereo image never wobbles).
 *  2. Asymmetric exponential gain smoother: fast attack (~30 ms) pulls gain down before
 *     transients overshoot; slow release (~1.2 s) raises it back without audible pumping.
 *  3. Per-sample application with a soft-knee peak limiter (ceiling -1 dBFS) so underestimated
 *     peaks never hard-clip.
 *
 * Gain range is [0 dB, +18 dB] targeting -20 dBFS average program level.
 * All processing state is scalar: nothing is allocated in the per-block path.
 */
class SoftAgc(
    private val sampleRateHz: Int,
    private val channels: Int,
) {
    private var gain = INITIAL_GAIN

    /** Current linear gain; exposed for assertions and diagnostics. */
    fun currentGain(): Double = gain

    /** Processes interleaved little-endian 16-bit PCM in place. Resets position to 0. */
    fun processPcm16(buffer: ByteBuffer, byteCount: Int) {
        // AudioRecord PCM is little-endian. MediaCodec-owned/direct ByteBuffers otherwise
        // default to BIG_ENDIAN, which would swap every sample and corrupt level detection.
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = byteCount / BYTES_PER_SAMPLE_16
        if (sampleCount <= 0) return
        var sumSquares = 0.0
        for (index in 0 until sampleCount) {
            val sample = buffer.getShort(index * BYTES_PER_SAMPLE_16).toDouble()
            sumSquares += sample * sample
        }
        updateGain(sqrt(sumSquares / sampleCount) / PCM16_FULL_SCALE, sampleCount / channels)
        for (index in 0 until sampleCount) {
            val offset = index * BYTES_PER_SAMPLE_16
            val amplified = buffer.getShort(offset) * gain
            val limited = (limit(amplified / PCM16_FULL_SCALE) * Short.MAX_VALUE).toInt().toShort()
            buffer.putShort(offset, limited)
        }
        buffer.position(0)
    }

    /** Processes interleaved little-endian packed 24-bit PCM in place. Resets position to 0. */
    fun processPcm24(buffer: ByteBuffer, byteCount: Int) {
        val sampleCount = byteCount / BYTES_PER_SAMPLE_24
        if (sampleCount <= 0) return
        var sumSquares = 0.0
        for (index in 0 until sampleCount) {
            val sample = readInt24(buffer, index * BYTES_PER_SAMPLE_24).toDouble()
            sumSquares += sample * sample
        }
        updateGain(sqrt(sumSquares / sampleCount) / PCM24_FULL_SCALE, sampleCount / channels)
        for (index in 0 until sampleCount) {
            val offset = index * BYTES_PER_SAMPLE_24
            val amplified = readInt24(buffer, offset) * gain
            val scaled = (limit(amplified / PCM24_FULL_SCALE) * PCM24_FULL_SCALE).toInt()
            writeInt24(buffer, offset, scaled)
        }
        buffer.position(0)
    }

    private fun updateGain(blockRmsLinear: Double, frameCount: Int) {
        val measuredDbfs = 20.0 * log10(blockRmsLinear.coerceAtLeast(DBFS_FLOOR_LINEAR))
        val errorDb = TARGET_DBFS - measuredDbfs
        val desiredGain = exp(errorDb * DB_TO_LINEAR).coerceIn(MIN_GAIN_LINEAR, MAX_GAIN_LINEAR)
        val blockSeconds = frameCount.toDouble() / sampleRateHz
        val coefficient = if (desiredGain < gain) {
            1.0 - exp(-blockSeconds / ATTACK_TAU_SECONDS)
        } else {
            1.0 - exp(-blockSeconds / RELEASE_TAU_SECONDS)
        }
        gain += coefficient * (desiredGain - gain)
    }

    /** Rational soft knee: continuous and C1 at the ceiling, asymptotic below digital full scale. */
    private fun limit(linear: Double): Double {
        val magnitude = abs(linear)
        if (magnitude <= LIMITER_CEILING) return linear
        val excess = magnitude - LIMITER_CEILING
        val headroom = 1.0 - LIMITER_CEILING
        return sign(linear) * (LIMITER_CEILING + headroom * excess / (headroom + excess))
    }

    private companion object {
        const val TARGET_DBFS = -20.0
        const val MAX_GAIN_LINEAR = 7.943282347242815 // +18 dB
        const val MIN_GAIN_LINEAR = 0.1 // -20 dB floor for pathological inputs
        const val INITIAL_GAIN = 1.0
        const val DB_TO_LINEAR = 0.11512925464970229 // ln(10) / 20
        const val DBFS_FLOOR_LINEAR = 1.0e-4 // -80 dBFS measurement floor
        const val LIMITER_CEILING = 0.8912509381337456 // -1 dBFS
        const val ATTACK_TAU_SECONDS = 0.030
        const val RELEASE_TAU_SECONDS = 1.2
        const val BYTES_PER_SAMPLE_16 = 2
        const val BYTES_PER_SAMPLE_24 = 3
        const val PCM16_FULL_SCALE = 32767.0
        const val PCM24_FULL_SCALE = 8388607.0

        fun readInt24(buffer: ByteBuffer, offset: Int): Int {
            val low = buffer.get(offset).toInt() and 0xff
            val mid = buffer.get(offset + 1).toInt() and 0xff
            val high = buffer.get(offset + 2).toInt() // sign-extended byte
            return (high shl 16) or (mid shl 8) or low
        }

        fun writeInt24(buffer: ByteBuffer, offset: Int, value: Int) {
            buffer.put(offset, value.toByte())
            buffer.put(offset + 1, (value shr 8).toByte())
            buffer.put(offset + 2, (value shr 16).toByte())
        }
    }
}
