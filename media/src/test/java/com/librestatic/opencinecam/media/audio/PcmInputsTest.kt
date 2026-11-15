/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmInputsTest {
    /** Delivers [samples] as PCM16 in reads of at most [maxFrames] frames, then times out with 0. */
    private class FakePcm16Input(
        private val samples: ShortArray,
        override val channels: Int = 2,
        private val maxFrames: Int = Int.MAX_VALUE,
        private val failWith: Int? = null,
    ) : PcmInput {
        override val sampleRateHz = 48_000
        override val encoding = AudioFormat.ENCODING_PCM_16BIT
        override val audioSessionId: Int? = null
        override val routedDeviceId: Int? = 7
        override val path = PcmInputPath.AAUDIO_LOW_LATENCY
        override var overrunCount = 0
        var started = false
        var stopped = false
        var closed = false
        var lastRequestedBytes = -1
        private var next = 0

        override fun start() { started = true }

        override fun read(buffer: ByteBuffer, byteCount: Int): Int {
            failWith?.let { return it }
            lastRequestedBytes = byteCount
            val frames = minOf(byteCount / (channels * 2), maxFrames, (samples.size - next) / channels)
            for (index in 0 until frames * channels) {
                val value = samples[next++].toInt()
                buffer.put(index * 2, value.toByte())
                buffer.put(index * 2 + 1, (value shr 8).toByte())
            }
            return frames * channels * 2
        }

        override fun timestamp(out: PcmInputTimestamp): Boolean {
            out.framePosition = 480
            out.nanoTime = 1_000_000L
            return true
        }

        override fun stop() { stopped = true }

        override fun close() { closed = true }
    }

    private val extremes = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 256)

    @Test
    fun widensPcm16To24BitPacked() {
        val fake = FakePcm16Input(extremes)
        val input = WideningPcmInput(fake, AudioFormat.ENCODING_PCM_24BIT_PACKED)
        val buffer = ByteBuffer.allocateDirect(64)
        val read = input.read(buffer, 18) // three stereo 24-bit frames
        assertEquals(18, read)
        assertEquals(12, fake.lastRequestedBytes) // three stereo PCM16 frames
        val decoded = (0 until 6).map { i ->
            (buffer.get(i * 3).toInt() and 255) or ((buffer.get(i * 3 + 1).toInt() and 255) shl 8) or (buffer.get(i * 3 + 2).toInt() shl 16)
        }
        assertEquals(extremes.map { it.toInt() shl 8 }, decoded)
        assertEquals(AudioFormat.ENCODING_PCM_24BIT_PACKED, input.encoding)
    }

    @Test
    fun widensPcm16ToFloat() {
        val fake = FakePcm16Input(extremes)
        val input = WideningPcmInput(fake, AudioFormat.ENCODING_PCM_FLOAT)
        val buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, input.read(buffer, 24))
        val decoded = (0 until 6).map { buffer.getFloat(it * 4) }
        assertEquals(extremes.map { it / 32768f }, decoded)
        assertEquals(-1f, decoded[4])
    }

    @Test
    fun widensUpToTheCapacityAndLeavesPositionAndLimitAlone() {
        val fake = FakePcm16Input(extremes)
        val input = WideningPcmInput(fake, AudioFormat.ENCODING_PCM_FLOAT)
        val buffer = ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4).limit(8)
        assertEquals(24, input.read(buffer, 24))
        assertEquals(4, buffer.position())
        assertEquals(8, buffer.limit())
        val whole = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        whole.limit(whole.capacity())
        assertEquals(extremes.map { it / 32768f }, (0 until 6).map { whole.getFloat(it * 4) })

        // Another buffer next is written, not the one before.
        val other = ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN)
        val again = WideningPcmInput(FakePcm16Input(extremes), AudioFormat.ENCODING_PCM_FLOAT)
        again.read(ByteBuffer.allocateDirect(8), 8)
        assertEquals(8, again.read(other, 8))
        assertEquals(extremes[2] / 32768f, other.getFloat(0))
    }

    @Test
    fun convertsWholeFramesOnlyAndPassesShortReads() {
        val fake = FakePcm16Input(extremes, channels = 2, maxFrames = 1)
        val input = WideningPcmInput(fake, AudioFormat.ENCODING_PCM_24BIT_PACKED)
        val buffer = ByteBuffer.allocateDirect(64)
        // 20 bytes hold three whole 6-byte frames; the 2 left over are not asked for.
        assertEquals(6, input.read(buffer, 20))
        assertEquals(12, fake.lastRequestedBytes)
        // Less than one frame is refused.
        assertEquals(PcmInput.ERROR_INVALID, input.read(buffer, 5))
    }

    @Test
    fun passesTimeoutsErrorsAndTheRestThrough() {
        val empty = WideningPcmInput(FakePcm16Input(ShortArray(0)), AudioFormat.ENCODING_PCM_FLOAT)
        assertEquals(0, empty.read(ByteBuffer.allocateDirect(16), 16))
        val dead = WideningPcmInput(FakePcm16Input(extremes, failWith = PcmInput.ERROR_DEAD), AudioFormat.ENCODING_PCM_FLOAT)
        assertEquals(PcmInput.ERROR_DEAD, dead.read(ByteBuffer.allocateDirect(16), 16))

        val fake = FakePcm16Input(extremes, channels = 1)
        val input = WideningPcmInput(fake, AudioFormat.ENCODING_PCM_24BIT_PACKED)
        assertEquals(1, input.channels)
        assertEquals(48_000, input.sampleRateHz)
        assertEquals(7, input.routedDeviceId)
        assertNull(input.audioSessionId)
        assertEquals(PcmInputPath.AAUDIO_LOW_LATENCY, input.path)
        assertEquals(0, input.overrunCount)
        fake.overrunCount = 3
        assertEquals(3, input.overrunCount)
        val stamp = PcmInputTimestamp()
        assertTrue(input.timestamp(stamp))
        assertEquals(480L, stamp.framePosition)
        assertEquals(1_000_000L, stamp.nanoTime)
        input.start(); input.stop(); input.close()
        assertTrue(fake.started && fake.stopped && fake.closed)
    }

    @Test
    fun refusesEncodingsItCannotWidenTo() {
        val fake = FakePcm16Input(extremes)
        assertThrows(IllegalArgumentException::class.java) { WideningPcmInput(fake, AudioFormat.ENCODING_PCM_32BIT) }
        assertThrows(IllegalArgumentException::class.java) { WideningPcmInput(fake, AudioFormat.ENCODING_PCM_16BIT) }
        assertThrows(IllegalArgumentException::class.java) { pcmEncodingBits(AudioFormat.ENCODING_PCM_32BIT) }
    }

    @Test
    fun lowLatencyIsForTheBuiltInMicrophoneOnly() {
        assertEquals(PcmInputRoute.AUDIO_RECORD, pcmInputRoute(lowLatency = false, preferredDeviceType = null))
        assertEquals(PcmInputRoute.AUDIO_RECORD, pcmInputRoute(false, AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(PcmInputRoute.LOW_LATENCY, pcmInputRoute(true, null))
        assertEquals(PcmInputRoute.LOW_LATENCY, pcmInputRoute(true, AudioDeviceInfo.TYPE_BUILTIN_MIC))
        for (external in listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_TELEPHONY)) {
            assertEquals(PcmInputRoute.EXTERNAL_FALLBACK, pcmInputRoute(true, external))
        }
    }

    @Test
    fun lowLatencyFallsBackToCamcorder() {
        assertTrue(AudioSourceSelection.LOW_LATENCY.lowLatency)
        assertEquals(AudioSourceSelection.CAMCORDER.androidSource, AudioSourceSelection.LOW_LATENCY.androidSource)
        assertFalse(AudioSourceSelection.entries.filter { it != AudioSourceSelection.LOW_LATENCY }.any { it.lowLatency })
        // Host JVMs have no native library, so the source is not offered there.
        assertFalse(lowLatencyInputAvailable())
    }

    @Test
    fun reportsCaptureBitsOfEachTakeEncoding() {
        assertEquals(16, pcmEncodingBits(AudioFormat.ENCODING_PCM_16BIT))
        assertEquals(24, pcmEncodingBits(AudioFormat.ENCODING_PCM_24BIT_PACKED))
        assertEquals(32, pcmEncodingBits(AudioFormat.ENCODING_PCM_FLOAT))
    }
}
