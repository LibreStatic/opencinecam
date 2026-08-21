/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelMeterTest {
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
}
