/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftAgcTest {
    private fun directBuffer(samples: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach(buffer::putShort)
        return buffer
    }

    private fun sineFrame(frameIndex: Int, frequencyHz: Int, amplitude: Double): Short =
        (amplitude * sin(2.0 * Math.PI * frequencyHz * frameIndex / SAMPLE_RATE_HZ)).toInt().toShort()

    /** Processes [frameCount] frames block-by-block with fresh buffers, like the capture pipelines do. */
    private fun processBlocks(
        agc: SoftAgc,
        generator: (frameIndex: Int) -> Short,
        startFrame: Int,
        frameCount: Int,
        channels: Int = CHANNELS,
    ): Pair<DoubleArray, Double> {
        val peaks = DoubleArray(frameCount)
        var peakOverall = 0.0
        var done = 0
        while (done < frameCount) {
            val frames = minOf(BLOCK_FRAMES, frameCount - done)
            val interleaved = ShortArray(frames * channels) { index ->
                generator(startFrame + done + index / channels)
            }
            val buffer = directBuffer(interleaved)
            agc.processPcm16(buffer, frames * 2 * channels)
            for (frame in 0 until frames) {
                var framePeak = 0.0
                for (channel in 0 until channels) {
                    framePeak = maxOf(framePeak, abs(buffer.getShort((frame * channels + channel) * 2).toDouble()))
                }
                peaks[done + frame] = framePeak
                peakOverall = maxOf(peakOverall, framePeak)
            }
            done += frames
        }
        return peaks to peakOverall
    }

    @Test
    fun forcesLittleEndianForCodecOwnedBuffers() {
        val agc = SoftAgc(SAMPLE_RATE_HZ, CHANNELS)
        // ByteBuffer defaults to BIG_ENDIAN, while AudioRecord writes little-endian PCM bytes.
        val buffer = ByteBuffer.allocateDirect(BLOCK_FRAMES * 2)
        repeat(BLOCK_FRAMES) { index ->
            buffer.put(index * 2, 0x80.toByte())
            buffer.put(index * 2 + 1, 0x3e.toByte()) // +16000 little-endian
        }
        agc.processPcm16(buffer, BLOCK_FRAMES * 2)
        val sample = buffer.order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt()
        assertTrue("sample=$sample", sample in 1..15_999)
    }

    @Test
    fun quietProgramIsAmplifiedTowardTheTargetLevel() {
        val agc = SoftAgc(SAMPLE_RATE_HZ, CHANNELS)
        val (_, peakAfter) = processBlocks(agc, { sineFrame(it, 1_000, QUIET_AMPLITUDE * 32767.0) }, 0, SAMPLE_RATE_HZ)
        // Release ramps gradually, so the second half of the second must already sit near target.
        assertTrue("peak=$peakAfter", peakAfter >= 2_500.0)
        assertTrue("peak=$peakAfter", peakAfter <= 32_760.0)
    }

    @Test
    fun loudProgramIsAttenuatedInsteadOfClipping() {
        val agc = SoftAgc(SAMPLE_RATE_HZ, CHANNELS)
        val (_, peakAfter) = processBlocks(agc, { sineFrame(it, 1_000, 32_767.0) }, 0, SAMPLE_RATE_HZ)
        // Full-scale program converges to the -20 dBFS target (~peak 4600); the attack
        // transient keeps the very first blocks louder, but never at unity gain.
        assertTrue("peak=$peakAfter", peakAfter <= 30_000)
    }

    @Test
    fun gainNeverExceedsEighteenDb() {
        val agc = SoftAgc(SAMPLE_RATE_HZ, CHANNELS)
        repeat(BLOCKS_PER_SECOND) {
            agc.processPcm16(directBuffer(ShortArray(BLOCK_FRAMES)), BLOCK_FRAMES * 2)
        }
        assertTrue("gain=${agc.currentGain()}", agc.currentGain() <= MAX_GAIN_LINEAR + 1e-9)
    }

    @Test
    fun limiterKeepsOutputUnderFullScaleEvenWithSuddenLoudTransient() {
        val agc = SoftAgc(SAMPLE_RATE_HZ, CHANNELS)
        repeat(20) {
            agc.processPcm16(directBuffer(ShortArray(BLOCK_FRAMES)), BLOCK_FRAMES * 2)
        }
        val transient = ShortArray(BLOCK_FRAMES) { 32_000.toShort() }
        val buffer = directBuffer(transient)
        agc.processPcm16(buffer, BLOCK_FRAMES * 2)
        var peak = 0.0
        for (index in 0 until BLOCK_FRAMES) peak = maxOf(peak, abs(buffer.getShort(index * 2).toInt()).toDouble())
        assertTrue("peak=$peak", peak <= 32_767.0)
    }

    @Test
    fun stereoChannelsShareOneGainSoTheImageNeverShifts() {
        val agc = SoftAgc(SAMPLE_RATE_HZ, 2)
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        var done = 0
        while (done < SAMPLE_RATE_HZ) {
            val frames = minOf(BLOCK_FRAMES, SAMPLE_RATE_HZ - done)
            val interleaved = ShortArray(frames * 2) { index ->
                val frame = done + index / 2
                if (index % 2 == 0) sineFrame(frame, 1_000, QUIET_AMPLITUDE * 32767.0)
                else sineFrame(frame, 500, QUIET_AMPLITUDE * 32767.0)
            }
            val buffer = directBuffer(interleaved)
            agc.processPcm16(buffer, frames * 4)
            for (frame in 0 until frames) {
                leftEnergy += Math.pow(buffer.getShort(frame * 4).toDouble(), 2.0)
                rightEnergy += Math.pow(buffer.getShort(frame * 4 + 2).toDouble(), 2.0)
            }
            done += frames
        }
        // One shared gain means channel energy ratios are inherited untouched from the input,
        // where both tones use identical amplitudes.
        assertTrue("L=$leftEnergy R=$rightEnergy", abs(leftEnergy / rightEnergy - 1.0) < 1e-4)
    }

    @Test
    fun pcm24LowLevelSignalIsAmplifiedWithoutPackingCorruption() {
        val agc = SoftAgc(SAMPLE_RATE_HZ, CHANNELS)
        val originals = IntArray(SAMPLES_24) { index -> if (index % 2 == 0) 1_000 else -1_000 }
        val buffer = ByteBuffer.allocateDirect(3 * SAMPLES_24).order(ByteOrder.nativeOrder())
        repeat(600) {
            originals.forEachIndexed { index, value -> writePacked24(buffer, index, value) }
            agc.processPcm24(buffer, 3 * SAMPLES_24)
        }
        originals.forEachIndexed { index, value -> writePacked24(buffer, index, value) }
        agc.processPcm24(buffer, 3 * SAMPLES_24)
        val gain = agc.currentGain()
        assertTrue("gain=$gain", gain > 5.0) // -78 dBFS input must request the +18 dB ceiling
        originals.forEachIndexed { index, original ->
            val processed = readPacked24(buffer, index)
            assertEquals("sample#$index", original * gain, processed.toDouble(), 1.0)
        }
    }

    private fun writePacked24(buffer: ByteBuffer, index: Int, value: Int) {
        buffer.put(index * 3, value.toByte())
        buffer.put(index * 3 + 1, (value shr 8).toByte())
        buffer.put(index * 3 + 2, (value shr 16).toByte())
    }

    private fun readPacked24(buffer: ByteBuffer, index: Int): Int {
        val low = buffer.get(index * 3).toInt() and 0xff
        val mid = buffer.get(index * 3 + 1).toInt() and 0xff
        val high = buffer.get(index * 3 + 2).toInt()
        return (high shl 16) or (mid shl 8) or low
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNELS = 1
        const val BLOCK_FRAMES = 480 // 10 ms at 48 kHz
        const val BLOCKS_PER_SECOND = 100
        const val QUIET_AMPLITUDE = 0.05
        const val SAMPLES_24 = 96
        const val MAX_GAIN_LINEAR = 7.943282347242815
    }
}
