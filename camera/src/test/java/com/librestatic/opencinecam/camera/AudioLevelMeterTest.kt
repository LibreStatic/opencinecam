/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
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
}
