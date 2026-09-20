/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class AudioMeterBallisticsTest {
    // Primary historical specifications, not electrical certification:
    // https://tech.ebu.ch/docs/tech/tech3205.pdf Table 2, §3.10
    // https://www.itu.int/rec/R-REC-BS.645-2-199203-I/en Annex 2
    @Test fun historicalPpmBurstCurveKeepsEachPublishedToleranceAtEveryProducerRate() {
        for (rate in intArrayOf(44_100, 48_000, 88_200, 96_000, 192_000)) {
            for ((ms, frequency, expected, tolerance) in listOf(
                Burst(100.0, 5000, 0.0, .5), Burst(10.0, 5000, -2.0, .5),
                Burst(5.0, 5000, -4.0, .75), Burst(1.5, 5000, -9.0, 1.0),
                Burst(.5, 10000, -17.0, 2.0),
            )) {
                val meter = AudioMeterBallistics(rate)
                repeat((rate * ms / 1000).roundToInt()) { i ->
                    meter.process(sin(2 * PI * frequency * i / rate))
                }
                val steady = AudioMeterBallistics(rate)
                repeat(rate) { i -> steady.process(sin(2 * PI * frequency * i / rate)) }
                assertEquals("rate=$rate burst=$ms", expected,
                    db(meter.ppmAmplitude / steady.ppmAmplitude), tolerance)
            }
        }
    }

    @Test fun vuMovementReachesNinetyNinePercentAt300msWithSmallOvershoot() {
        for (rate in intArrayOf(8000, 44_100, 48_000, 96_000, 192_000)) {
            val meter = AudioMeterBallistics(rate)
            repeat((rate * .3).roundToInt()) { meter.process(2 / PI) }
            assertEquals("rate=$rate", .99, meter.vuAmplitude, 1e-8)
            var highest = meter.vuAmplitude
            repeat(rate) { meter.process(2 / PI); highest = maxOf(highest, meter.vuAmplitude) }
            assertTrue("overshoot=$highest", highest in 1.01..1.015)
            repeat(rate * 2) { meter.process(0.0) }
            assertTrue(meter.vuAmplitude < 1e-6)
        }
    }

    @Test fun actualSineVuResponseAndSteadyCalibrationUsePeakAmplitudeNotRms() {
        for (rate in intArrayOf(44_100, 48_000, 88_200, 96_000, 192_000)) {
            val meter = AudioMeterBallistics(rate)
            val amplitude = 10.0.pow(-18.0 / 20)
            var at300 = 0.0
            repeat(rate * 2) { i ->
                meter.process(amplitude * sin(2 * PI * 1000 * i / rate))
                if (i + 1 == (rate * .3).roundToInt()) at300 = meter.vuAmplitude
            }
            assertEquals(-18.0, db(meter.vuAmplitude), .03)
            assertEquals(.99, at300 / meter.vuAmplitude, .002)
            assertEquals(-18.0, db(meter.ppmAmplitude), .15)
        }
    }

    @Test fun ppmReleaseIsTwentyFourDbInTwoPointEightSecondsIndependentOfRate() {
        for (rate in intArrayOf(8000, 44_100, 48_000, 192_000)) {
            val meter = AudioMeterBallistics(rate)
            repeat(rate) { meter.process(2 / PI) }
            val start = meter.ppmAmplitude
            repeat((2.8 * rate).roundToInt()) { meter.process(0.0) }
            assertEquals(-24.0, db(meter.ppmAmplitude / start), .001)
        }
    }

    @Test fun polarityReversalOfAsymmetricSignalPreservesBothMetersExactly() {
        val positive = AudioMeterBallistics(48_000)
        val negative = AudioMeterBallistics(48_000)
        repeat(48_000) { i ->
            val value = sin(2 * PI * 917 * i / 48_000).let { if (it > .25) .25 else it }
            positive.process(value); negative.process(-value)
            assertEquals(positive.vuAmplitude, negative.vuAmplitude, 0.0)
            assertEquals(positive.ppmAmplitude, negative.ppmAmplitude, 0.0)
        }
    }

    @Test fun allBlocksContributeToPeakRmsAndPostGainClipBeforeThrottle() {
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_FLOAT, 1)
        meter.analyze(pcm(0f), 4, 0)
        assertNull(meter.analyze(pcm(1f, -1f), 8, 25))
        assertNull(meter.analyze(pcm(.25f, -.25f), 8, 75))
        val snapshot = meter.analyze(pcm(0f, 0f), 8, 100)!!
        assertEquals(0f, snapshot.channels.single().peakDbfs, 0f)
        assertEquals((10 * log10(2.125 / 6)).toFloat(), snapshot.channels.single().rmsDbfs, .0001f)
        assertTrue(snapshot.clipped)
        assertFalse(meter.analyze(pcm(0f), 4, 200)!!.clipped)
    }

    @Test fun sampleClockAndWindowAggregatesAreInvariantToChunkBoundaries() {
        val values = FloatArray(9600) { i -> (.5 * sin(2 * PI * 1277 * i / 48_000)).toFloat() }
        fun collect(chunk: Int): AudioChannelLevel {
            val meter = AudioLevelMeter(PcmMeterEncoding.PCM_FLOAT, 1, 100, 48_000)
            meter.analyze(pcm(0f), 4, 0)
            var offset = 0
            while (offset < values.size) {
                val end = minOf(offset + chunk, values.size)
                val bytes = pcm(*values.copyOfRange(offset, end))
                assertNull(meter.analyze(bytes, bytes.capacity(), 1))
                offset = end
            }
            return meter.analyze(pcm(0f), 4, 100)!!.channels.single()
        }
        val whole = collect(values.size)
        for (chunk in intArrayOf(1, 7, 128, 1000)) assertEquals(whole, collect(chunk))
    }

    @Test fun stereoBallisticsAreIndependentAndFloatHeadroomIsNotClamped() {
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_FLOAT, 2, 0, 48_000)
        val samples = FloatArray(48_000 * 2) { index ->
            (sin(2 * PI * 1000 * (index / 2) / 48_000) * if (index % 2 == 0) 2.0 else .25).toFloat()
        }
        val bytes = pcm(*samples)
        val levels = meter.analyze(bytes, bytes.capacity(), 0)!!.channels
        assertEquals(18.0618f, levels[0].vuDbfs!! - levels[1].vuDbfs!!, .001f)
        assertEquals(18.0618f, levels[0].ppmDbfs!! - levels[1].ppmDbfs!!, .001f)
        assertTrue(levels[0].vuDbfs!! > 6f)
        assertTrue(levels[0].ppmDbfs!! > 6f)
    }

    @Test fun nonfiniteFloatSamplesAreSilenceAndNeverPoisonLaterReadings() {
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_FLOAT, 1, 0)
        val bad = pcm(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val snapshot = meter.analyze(bad, bad.capacity(), 0)!!
        val zero = snapshot.channels.single()
        assertEquals(-120f, zero.vuDbfs!!, 0f); assertEquals(-120f, zero.ppmDbfs!!, 0f)
        assertFalse(snapshot.clipped)
        val next = meter.analyze(pcm(.5f), 4, 1)!!.channels.single()
        assertTrue(next.vuDbfs!!.isFinite()); assertTrue(next.ppmDbfs!!.isFinite())
        assertTrue(next.ppmDbfs!! > -120)
    }

    @Test fun analyzePreservesBytesPositionLimitMarkAndByteOrderWithPartialFrameIgnored() {
        val bytes = pcm(.5f, -.5f, 1f)
        val original = bytes.array().clone()
        bytes.order(ByteOrder.BIG_ENDIAN).position(1).mark().position(2).limit(3)
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_FLOAT, 2, 0)
        val snapshot = meter.analyze(bytes, 12, 0)!!
        assertFalse(snapshot.clipped)
        assertEquals(-6.0206f, snapshot.channels[0].peakDbfs, .0001f)
        assertArrayEquals(original, bytes.array()); assertEquals(2, bytes.position())
        assertEquals(3, bytes.limit()); assertEquals(ByteOrder.BIG_ENDIAN, bytes.order())
        bytes.reset(); assertEquals(1, bytes.position())
    }

    @Test fun publicationTimeDoesNotAdvanceBallisticsAndClockReversalDoesNotLoseSamples() {
        val first = AudioLevelMeter(PcmMeterEncoding.PCM_FLOAT, 1, 0)
        val second = AudioLevelMeter(PcmMeterEncoding.PCM_FLOAT, 1, 0)
        for ((i, time) in listOf(1000L, 1000000L, -500L, 3L).withIndex()) {
            val a = first.analyze(pcm(.5f), 4, time)!!.channels
            val b = second.analyze(pcm(.5f), 4, i.toLong())!!.channels
            assertEquals(a, b)
        }
    }

    @Test fun legacyConstructorsStayNullableButEveryMeterEncodingProducesBallistics() {
        assertNull(AudioChannelLevel(0f, 0f).vuDbfs)
        assertNull(AudioChannelLevel(0f, 0f).ppmDbfs)
        for (encoding in PcmMeterEncoding.entries) {
            val bytes = ByteBuffer.allocate(encoding.bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
            when (encoding) {
                PcmMeterEncoding.PCM_16 -> bytes.putShort(16384)
                PcmMeterEncoding.PCM_24 -> { bytes.put(0); bytes.put(0); bytes.put(0x40) }
                PcmMeterEncoding.PCM_FLOAT -> bytes.putFloat(.5f)
            }
            val level = AudioLevelMeter(encoding, 1).analyze(bytes, bytes.capacity(), 0)!!.channels.single()
            assertNotNull(level.vuDbfs); assertNotNull(level.ppmDbfs)
            assertEquals(-6.0206f, level.peakDbfs, .0001f)
        }
    }

    @Test fun emptyInputDoesNotFlushWindowOrAdvanceBallistics() {
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_FLOAT, 1)
        meter.analyze(pcm(0f), 4, 0)
        assertNull(meter.analyze(pcm(1f), 4, 10))
        assertNull(meter.analyze(pcm(), 0, 100))
        val published = meter.analyze(pcm(0f), 4, 100)!!
        assertTrue(published.clipped)
        assertEquals(0f, published.channels.single().peakDbfs, 0f)
        assertTrue(published.channels.single().ppmDbfs!! > -120)
    }

    @Test fun invalidRatesChannelsAndIntervalsRejectBeforeProcessing() {
        for (rate in intArrayOf(Int.MIN_VALUE, 0, 7999, 192001, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { AudioLevelMeter(PcmMeterEncoding.PCM_16, 1, 100, rate) }
        }
        for (channels in intArrayOf(-1, 0, 3)) {
            assertThrows(IllegalArgumentException::class.java) { AudioLevelMeter(PcmMeterEncoding.PCM_16, channels) }
        }
        assertThrows(IllegalArgumentException::class.java) { AudioLevelMeter(PcmMeterEncoding.PCM_16, 1, -1) }
    }

    private data class Burst(val ms: Double, val frequency: Int, val expected: Double, val tolerance: Double)
    private fun pcm(vararg samples: Float): ByteBuffer = ByteBuffer.allocate(samples.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putFloat(it) } }
    private fun db(value: Double): Double = 20 * log10(value.coerceAtLeast(1e-6))
}
