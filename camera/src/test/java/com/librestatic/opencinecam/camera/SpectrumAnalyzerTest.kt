/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumAnalyzerTest {
    private val rate = 48_000

    @Test fun `a full-scale sine reads about 0 dBFS in its band and is far down two octaves away`() {
        for (encoding in PcmMeterEncoding.entries) {
            val spectrum = snapshot(SpectrumAnalyzer(encoding, 1, rate), encoding, tone(1_000.0), tone(1_000.0))
            val band = bandOf(spectrum, 1_000.0)
            assertEquals("$encoding", band, spectrum.inputDb.indices.maxBy { spectrum.inputDb[it] })
            assertEquals("$encoding", 0f, spectrum.inputDb[band], 1.5f)
            assertEquals("$encoding", 0f, spectrum.outputDb[band], 1.5f)
            for (far in listOf(250.0, 4_000.0)) {
                assertTrue("$encoding at $far Hz", spectrum.inputDb[bandOf(spectrum, far)] < spectrum.inputDb[band] - 40f)
            }
        }
    }

    @Test fun `the input and output sides are independent`() {
        val spectrum = snapshot(SpectrumAnalyzer(PcmMeterEncoding.PCM_16, 1, rate), PcmMeterEncoding.PCM_16, tone(1_000.0), tone(4_000.0))
        val low = bandOf(spectrum, 1_000.0)
        val high = bandOf(spectrum, 4_000.0)
        assertEquals(low, spectrum.inputDb.indices.maxBy { spectrum.inputDb[it] })
        assertEquals(high, spectrum.outputDb.indices.maxBy { spectrum.outputDb[it] })
        assertTrue(spectrum.inputDb[high] < -40f)
        assertTrue(spectrum.outputDb[low] < -40f)
    }

    @Test fun `silence floors at -120`() {
        for (encoding in PcmMeterEncoding.entries) {
            val spectrum = snapshot(SpectrumAnalyzer(encoding, 2, rate), encoding, DoubleArray(4_096), DoubleArray(4_096))
            assertTrue(spectrum.inputDb.all { it == SpectrumAnalyzer.MIN_DB })
            assertTrue(spectrum.outputDb.all { it == SpectrumAnalyzer.MIN_DB })
        }
    }

    @Test fun `bands are log spaced from 20 Hz to the top of the range`() {
        val spectrum = snapshot(SpectrumAnalyzer(PcmMeterEncoding.PCM_16, 1, rate), PcmMeterEncoding.PCM_16, tone(1_000.0), tone(1_000.0))
        assertEquals(64, spectrum.bandCentresHz.size)
        assertEquals(64, spectrum.inputDb.size)
        assertEquals(64, spectrum.outputDb.size)
        val ratios = spectrum.bandCentresHz.zipWithNext { a, b -> b / a }
        ratios.forEach { assertEquals(ratios.first(), it, 1e-3f) }
        val halfBand = sqrt(ratios.first())
        assertEquals(20f, spectrum.bandCentresHz.first() / halfBand, .01f)
        assertEquals(20_000f, spectrum.bandCentresHz.last() * halfBand, 1f)
        // At 32 kHz the top is 0.49 of the rate.
        val narrow = snapshot(SpectrumAnalyzer(PcmMeterEncoding.PCM_16, 1, 32_000, bands = 16), PcmMeterEncoding.PCM_16,
            tone(1_000.0, sampleRate = 32_000), tone(1_000.0, sampleRate = 32_000))
        assertEquals(16, narrow.bandCentresHz.size)
        val narrowHalfBand = sqrt(narrow.bandCentresHz[1] / narrow.bandCentresHz[0])
        assertEquals(15_680f, narrow.bandCentresHz.last() * narrowHalfBand, 1f)
    }

    @Test fun `stereo is mixed to mono`() {
        val both = snapshot(SpectrumAnalyzer(PcmMeterEncoding.PCM_FLOAT, 2, rate), PcmMeterEncoding.PCM_FLOAT,
            interleave(tone(1_000.0), tone(1_000.0)), interleave(tone(1_000.0), tone(1_000.0)))
        val band = bandOf(both, 1_000.0)
        assertEquals(0f, both.inputDb[band], 1.5f)
        val left = snapshot(SpectrumAnalyzer(PcmMeterEncoding.PCM_FLOAT, 2, rate), PcmMeterEncoding.PCM_FLOAT,
            interleave(tone(1_000.0), DoubleArray(4_096)), interleave(DoubleArray(4_096), tone(1_000.0)))
        assertEquals(both.inputDb[band] - 6.02f, left.inputDb[band], .1f)
        assertEquals(both.outputDb[band] - 6.02f, left.outputDb[band], .1f)
    }

    @Test fun `publishes only with both rings full and once per interval`() {
        val analyzer = SpectrumAnalyzer(PcmMeterEncoding.PCM_16, 1, rate)
        val half = encode(tone(1_000.0).copyOf(1_024), PcmMeterEncoding.PCM_16)
        analyzer.observeInput(half, half.capacity())
        assertNull(analyzer.observeOutput(half, half.capacity(), 0L))
        analyzer.observeInput(half, half.capacity())
        // The output ring is still half full.
        assertNull(analyzer.observeOutput(ByteBuffer.allocate(0), 0, 10L))
        assertNotNull(analyzer.observeOutput(half, half.capacity(), 20L))
        val block = encode(tone(1_000.0).copyOf(480), PcmMeterEncoding.PCM_16)
        analyzer.observeInput(block, block.capacity())
        assertNull(analyzer.observeOutput(block, block.capacity(), 50L))
        analyzer.observeInput(block, block.capacity())
        val next = analyzer.observeOutput(block, block.capacity(), 86L)
        assertNotNull(next)
        assertEquals(86L, next!!.capturedAtElapsedRealtimeMs)
        analyzer.observeInput(block, block.capacity())
        assertNull(analyzer.observeOutput(block, block.capacity(), 100L))
        // A clock that went back publishes rather than waiting out the gap.
        analyzer.observeInput(block, block.capacity())
        assertNotNull(analyzer.observeOutput(block, block.capacity(), 5L))
    }

    @Test fun `observing leaves the buffer's bytes, position, limit, mark and byte order alone`() {
        for (encoding in PcmMeterEncoding.entries) {
            val pcm = ByteBuffer.allocate(4_096 * encoding.bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
            val samples = tone(1_000.0)
            val encoded = encode(samples, encoding)
            pcm.put(encoded.duplicate().apply { clear() })
            val bytes = pcm.array().clone()
            pcm.order(ByteOrder.BIG_ENDIAN).position(1).mark().position(2).limit(3)
            val analyzer = SpectrumAnalyzer(encoding, 1, rate)
            analyzer.observeInput(pcm, pcm.capacity())
            val spectrum = analyzer.observeOutput(pcm, pcm.capacity(), 0L)
            assertArrayEquals(bytes, pcm.array())
            assertEquals(2, pcm.position())
            assertEquals(3, pcm.limit())
            assertEquals(ByteOrder.BIG_ENDIAN, pcm.order())
            pcm.reset()
            assertEquals(1, pcm.position())
            // The samples were still read little-endian and in full.
            assertEquals("$encoding", 0f, spectrum!!.inputDb[bandOf(spectrum, 1_000.0)], 1.5f)
        }
    }

    /** One snapshot after feeding [input] and [output] (interleaved samples) whole. */
    private fun snapshot(analyzer: SpectrumAnalyzer, encoding: PcmMeterEncoding, input: DoubleArray, output: DoubleArray): AudioSpectrum {
        val inputPcm = encode(input, encoding)
        val outputPcm = encode(output, encoding)
        analyzer.observeInput(inputPcm, inputPcm.capacity())
        return requireNotNull(analyzer.observeOutput(outputPcm, outputPcm.capacity(), 0L))
    }

    /** The band whose centre is nearest [frequencyHz] on a log scale. */
    private fun bandOf(spectrum: AudioSpectrum, frequencyHz: Double): Int =
        spectrum.bandCentresHz.indices.minBy { abs(ln(spectrum.bandCentresHz[it] / frequencyHz)) }

    /** 4096 samples of a full-scale sine. */
    private fun tone(frequencyHz: Double, amplitude: Double = 1.0, sampleRate: Int = rate): DoubleArray =
        DoubleArray(4_096) { amplitude * sin(2 * PI * frequencyHz * it / sampleRate + .3) }

    private fun interleave(left: DoubleArray, right: DoubleArray): DoubleArray =
        DoubleArray(left.size * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }

    private fun encode(samples: DoubleArray, encoding: PcmMeterEncoding): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * encoding.bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEachIndexed { index, value ->
            val offset = index * encoding.bytesPerSample
            when (encoding) {
                PcmMeterEncoding.PCM_16 -> buffer.putShort(offset, (value * 32768).coerceIn(-32768.0, 32767.0).roundToInt().toShort())
                PcmMeterEncoding.PCM_24 -> {
                    val v = (value * 8388608).coerceIn(-8388608.0, 8388607.0).roundToInt()
                    buffer.put(offset, v.toByte()); buffer.put(offset + 1, (v shr 8).toByte()); buffer.put(offset + 2, (v shr 16).toByte())
                }
                PcmMeterEncoding.PCM_FLOAT -> buffer.putFloat(offset, value.toFloat())
            }
        }
        return buffer
    }
}
