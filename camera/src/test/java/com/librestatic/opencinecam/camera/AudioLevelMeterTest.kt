/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelMeterTest {
    @Test
    fun recorderPeakIsPeakOnlyMono() {
        val full = recorderPeakSnapshot(Short.MAX_VALUE.toInt(), 10L)
        assertEquals(0f, full.channels.single().peakDbfs, 0.001f)
        assertTrue(full.clipped)
        assertTrue(full.channels.single().rmsDbfs.isNaN())
        assertNull(full.channels.single().vuDbfs)
        val half = recorderPeakSnapshot(Short.MAX_VALUE / 2, 20L)
        assertEquals(-6.02f, half.channels.single().peakDbfs, 0.05f)
        assertFalse(half.clipped)
        assertEquals(-120f, recorderPeakSnapshot(0, 30L).channels.single().peakDbfs, 0f)
    }

    @Test fun `pcm16 reports independent stereo peaks and clipping`() {
        val pcm = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(32767.toShort()); putShort(16384.toShort())
            putShort((-32768).toShort()); putShort((-16384).toShort())
        }
        val snapshot = AudioLevelMeter(PcmMeterEncoding.PCM_16, 2).analyze(pcm, 8, 100L)!!

        assertEquals(0f, snapshot.channels[0].peakDbfs, .01f)
        assertEquals(-6.02f, snapshot.channels[1].peakDbfs, .02f)
        assertTrue(snapshot.clipped)
    }

    @Test fun `pcm24 sign extension and float samples are bounded`() {
        val pcm24 = ByteBuffer.allocateDirect(6).apply {
            put(0xff.toByte()); put(0xff.toByte()); put(0x7f)
            put(0); put(0); put(0x80.toByte())
        }
        assertTrue(AudioLevelMeter(PcmMeterEncoding.PCM_24, 1).analyze(pcm24, 6, 0L)!!.clipped)

        val float = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(.25f); putFloat(-.25f)
        }
        val snapshot = AudioLevelMeter(PcmMeterEncoding.PCM_FLOAT, 1).analyze(float, 8, 0L)!!
        assertEquals(-12.04f, snapshot.channels.single().peakDbfs, .02f)
        assertFalse(snapshot.clipped)
    }

    @Test fun `tells a clipped input from clipped recorded samples`() {
        fun pcm(sample: Short) = ByteBuffer.allocateDirect(2).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(sample) }
        // Only the processed samples reach full scale, as a gain pushed too far does.
        val boosted = AudioLevelMeter(PcmMeterEncoding.PCM_16, 1).run {
            observeInput(pcm(8_000), 2)
            analyze(pcm(Short.MAX_VALUE), 2, 0L)!!
        }
        assertTrue(boosted.clipped)
        assertFalse(boosted.inputClipped)
        assertTrue(boosted.recordedClipped)
        // A source that never shows its input counts everything as recorded.
        val unobserved = AudioLevelMeter(PcmMeterEncoding.PCM_16, 1).analyze(pcm(Short.MAX_VALUE), 2, 0L)!!
        assertFalse(unobserved.inputClipped)
        assertTrue(unobserved.recordedClipped)
        val quiet = AudioLevelMeter(PcmMeterEncoding.PCM_16, 1).run { observeInput(pcm(8_000), 2); analyze(pcm(8_000), 2, 0L)!! }
        assertFalse(quiet.clipped || quiet.inputClipped || quiet.recordedClipped)
    }

    @Test fun `publishes at no more than ten hertz`() {
        val pcm = ByteBuffer.allocateDirect(2).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(1) }
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_16, 1)
        assertTrue(meter.analyze(pcm, 2, 1_000L) != null)
        assertNull(meter.analyze(pcm, 2, 1_050L))
        assertTrue(meter.analyze(pcm, 2, 1_100L) != null)
    }

    @Test fun inputClippingSurvivesAttenuationForEveryPcmEncoding() {
        for (encoding in PcmMeterEncoding.entries) {
            val input = fullScaleStereo(encoding)
            val meter = AudioLevelMeter(encoding, 2)
            meter.observeInput(input, input.capacity())
            DigitalRecordingGain(true, -24).process(input, input.capacity(), encoding, 2)
            val snapshot = meter.analyze(input, input.capacity(), 0)!!
            assertTrue("Input clipping must survive $encoding attenuation", snapshot.clipped)
            assertTrue(snapshot.inputClipped)
            assertFalse("The attenuated samples that are recorded did not clip", snapshot.recordedClipped)
            snapshot.channels.forEach { level ->
                assertEquals(-24f, level.peakDbfs, .02f)
                assertEquals(-24f, level.rmsDbfs, .02f)
            }
        }
    }

    @Test fun inputClippingBetweenPublicationsIsLatchedUntilNextPublishedSnapshot() {
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_16, 2)
        val quiet = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        assertFalse(meter.analyze(quiet, 4, 1000)!!.clipped)
        val clipped = fullScaleStereo(PcmMeterEncoding.PCM_16)
        meter.observeInput(clipped, 4)
        DigitalRecordingGain(true, -24).process(clipped, 4, PcmMeterEncoding.PCM_16, 2)
        assertNull(meter.analyze(clipped, 4, 1050))
        meter.observeInput(quiet, 4)
        assertNull(meter.analyze(quiet, 4, 1099))
        val published = meter.analyze(quiet, 4, 1100)!!
        assertTrue(published.clipped)
        // The attenuated block between publications now contributes to the whole-window peak.
        assertEquals(-24f, published.channels[0].peakDbfs, .02f)
        assertFalse(meter.analyze(quiet, 4, 1200)!!.clipped)
    }

    @Test fun emptyAnalysisDoesNotConsumePendingInputClipping() {
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_24, 2)
        meter.observeInput(fullScaleStereo(PcmMeterEncoding.PCM_24), 6)
        assertNull(meter.analyze(ByteBuffer.allocate(0), 0, 1000))
        assertTrue(meter.analyze(ByteBuffer.allocate(6), 6, 1000)!!.clipped)
        assertFalse(meter.analyze(ByteBuffer.allocate(6), 6, 1100)!!.clipped)
    }

    @Test fun observingInputPreservesBytesCursorLimitMarkAndByteOrder() {
        val pcm = fullScaleStereo(PcmMeterEncoding.PCM_FLOAT)
        val bytes = pcm.array().clone()
        pcm.order(ByteOrder.BIG_ENDIAN).position(1).mark().position(2).limit(3)
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_FLOAT, 2)
        meter.observeInput(pcm, 8)
        assertArrayEquals(bytes, pcm.array())
        assertEquals(2, pcm.position())
        assertEquals(3, pcm.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, pcm.order())
        pcm.reset()
        assertEquals(1, pcm.position())
        assertTrue(meter.analyze(ByteBuffer.allocate(8), 8, 0)!!.clipped)
    }

    @Test fun inputObservationUsesCompleteFramesAndFiniteNearFullScaleThreshold() {
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_FLOAT, 2, 0)
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        pcm.putFloat(0, Float.NaN).putFloat(4, Float.POSITIVE_INFINITY)
        meter.observeInput(pcm, 8)
        assertFalse(meter.analyze(ByteBuffer.allocate(8), 8, 0)!!.clipped)
        pcm.putFloat(0, .94f).putFloat(4, -.94f)
        meter.observeInput(pcm, 8)
        assertFalse(meter.analyze(ByteBuffer.allocate(8), 8, 1)!!.clipped)
        pcm.putFloat(0, .95f).putFloat(4, -.95f)
        meter.observeInput(pcm, 4) // An incomplete stereo frame is not input evidence.
        assertFalse(meter.analyze(ByteBuffer.allocate(8), 8, 2)!!.clipped)
        meter.observeInput(pcm, 8)
        assertTrue(meter.analyze(ByteBuffer.allocate(8), 8, 3)!!.clipped)
    }

    @Test fun postGainClippingStillContributesWhenInputDidNotClip() {
        val pcm = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(10000) }
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_16, 1)
        meter.observeInput(pcm, 2)
        DigitalRecordingGain(true, 24).process(pcm, 2, PcmMeterEncoding.PCM_16, 1)
        val snapshot = meter.analyze(pcm, 2, 0)!!
        assertTrue(snapshot.clipped)
        assertEquals(0f, snapshot.channels.single().peakDbfs, .01f)
    }

    private fun fullScaleStereo(encoding: PcmMeterEncoding): ByteBuffer =
        ByteBuffer.allocate(encoding.bytesPerSample * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            when (encoding) {
                PcmMeterEncoding.PCM_16 -> { putShort(32767); putShort((-32768).toShort()) }
                PcmMeterEncoding.PCM_24 -> {
                    put(0xff.toByte()); put(0xff.toByte()); put(0x7f)
                    put(0); put(0); put(0x80.toByte())
                }
                PcmMeterEncoding.PCM_FLOAT -> { putFloat(1f); putFloat(-1f) }
            }
        }

    // ---- input overload (clipping below full scale) ---------------------------------------------------

    @Test fun `a sine clipped at -6 dBFS is an input overload and not a clip`() {
        for (encoding in PcmMeterEncoding.entries) {
            val snapshot = window(AudioLevelMeter(encoding, 1), encoding, clip(tone(1_000.0, amplitude = 1.0), .5))
            assertTrue("$encoding", snapshot.inputOverloaded)
            assertFalse("$encoding", snapshot.clipped)
            assertFalse("$encoding", snapshot.inputClipped)
        }
    }

    @Test fun `clean sines at -6 dBFS are not an overload`() {
        for (encoding in PcmMeterEncoding.entries) {
            for (frequency in listOf(50.0, 200.0, 1_000.0, 8_000.0)) {
                for (phase in listOf(0.0, .1, .3, 1.3)) {
                    val snapshot = window(AudioLevelMeter(encoding, 1), encoding, tone(frequency, phase = phase))
                    assertFalse("$encoding $frequency Hz phase $phase", snapshot.inputOverloaded)
                }
            }
        }
    }

    @Test fun `a quiet clipped signal under -12 dBFS is not an overload`() {
        val snapshot = window(AudioLevelMeter(PcmMeterEncoding.PCM_16, 1), PcmMeterEncoding.PCM_16, clip(tone(1_000.0, amplitude = .4), .2))
        assertFalse(snapshot.inputOverloaded)
    }

    @Test fun `clipping at full scale is an input clip and not an overload`() {
        for (encoding in PcmMeterEncoding.entries) {
            val snapshot = window(AudioLevelMeter(encoding, 1), encoding, clip(tone(1_000.0, amplitude = 2.0), 1.0))
            assertTrue("$encoding", snapshot.inputClipped)
            assertFalse("$encoding", snapshot.inputOverloaded)
        }
    }

    @Test fun `white noise peaking at -6 dBFS is not an overload`() {
        val random = Random(7)
        val noise = DoubleArray(4_800) { random.nextDouble(-.5, .5) }
        for (encoding in PcmMeterEncoding.entries) assertFalse("$encoding", window(AudioLevelMeter(encoding, 1), encoding, noise).inputOverloaded)
    }

    @Test fun `the overload flag clears on the next clean window`() {
        val meter = AudioLevelMeter(PcmMeterEncoding.PCM_16, 1)
        assertTrue(window(meter, PcmMeterEncoding.PCM_16, clip(tone(1_000.0, amplitude = 1.0), .5), 1_000L).inputOverloaded)
        assertFalse(window(meter, PcmMeterEncoding.PCM_16, tone(1_000.0), 1_100L).inputOverloaded)
        // An overload between publications is kept until the next published snapshot.
        val clipped = encode(clip(tone(1_000.0, amplitude = 1.0), .5), PcmMeterEncoding.PCM_16)
        meter.observeInput(clipped, clipped.capacity())
        assertNull(meter.analyze(clipped, clipped.capacity(), 1_150L))
        assertTrue(window(meter, PcmMeterEncoding.PCM_16, DoubleArray(4_800), 1_200L).inputOverloaded)
        assertFalse(window(meter, PcmMeterEncoding.PCM_16, DoubleArray(4_800), 1_300L).inputOverloaded)
    }

    @Test fun `one saturated channel of two is enough`() {
        val clean = tone(1_000.0)
        val saturated = clip(tone(1_000.0, amplitude = 1.0), .5)
        for (encoding in listOf(PcmMeterEncoding.PCM_16, PcmMeterEncoding.PCM_FLOAT)) {
            assertTrue("$encoding", window(AudioLevelMeter(encoding, 2), encoding, interleave(clean, saturated)).inputOverloaded)
            assertTrue("$encoding", window(AudioLevelMeter(encoding, 2), encoding, interleave(saturated, clean)).inputOverloaded)
            assertFalse("$encoding", window(AudioLevelMeter(encoding, 2), encoding, interleave(clean, tone(200.0))).inputOverloaded)
        }
    }

    /** One published window of [samples] (interleaved), observed as input and analysed unchanged. */
    private fun window(meter: AudioLevelMeter, encoding: PcmMeterEncoding, samples: DoubleArray, at: Long = 0L): AudioLevelSnapshot {
        val pcm = encode(samples, encoding)
        meter.observeInput(pcm, pcm.capacity())
        return meter.analyze(pcm, pcm.capacity(), at)!!
    }

    /** 100 ms of a sine at 48 kHz; the default amplitude is -6 dBFS. */
    private fun tone(frequencyHz: Double, amplitude: Double = .5, phase: Double = 0.0): DoubleArray =
        DoubleArray(4_800) { amplitude * sin(2 * PI * frequencyHz * it / 48_000 + phase) }

    private fun clip(samples: DoubleArray, ceiling: Double): DoubleArray = DoubleArray(samples.size) { samples[it].coerceIn(-ceiling, ceiling) }

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
