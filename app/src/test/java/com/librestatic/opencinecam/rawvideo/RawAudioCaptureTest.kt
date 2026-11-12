/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of RAW take audio: the clock mapping, the chunking and timing, the audio decision. */
class RawAudioCaptureTest {
    private fun stereo(frames: Int, first: Int = 0): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames * 4)
        for (i in 0 until frames * 4) buffer.put(i, ((first * 4 + i) and 0xff).toByte())
        return buffer
    }

    @Test
    fun aRealtimeSensorTakesBootTimeAsIs() {
        val clock = RawAudioClock(sensorRealtime = true, boottimeMinusMonotonicNs = 123)
        assertEquals(5_000_000_000L, clock.sensorNs(5_000_000_000L))
        assertEquals(RawAudioKeys.CLOCK_BOOTTIME, clock.label)
    }

    @Test
    fun anUnknownSensorMapsBootTimeThroughTheMonotonicOffset() {
        // Asleep for 2 s since boot: boot time runs 2 s ahead of the monotonic clock the sensor uses.
        val clock = RawAudioClock(sensorRealtime = false, boottimeMinusMonotonicNs = 2_000_000_000L)
        assertEquals(3_000_000_000L, clock.sensorNs(5_000_000_000L))
        assertEquals(RawAudioKeys.CLOCK_MONOTONIC_OFFSET, clock.label)
    }

    @Test
    fun chunksAreAHundredMillisecondsStampedFromTheFirstTimestampEpoch() {
        val chunker = RawPcmChunker(48_000, 2, startReceiptBoottimeNs = 1)
        assertEquals(4_800, RawPcmChunker.defaultChunkFrames(48_000))
        // Frame 960 (20 ms) was captured at 10.020 s, so frame zero is at 10.000 s.
        chunker.observeTimestamp(960, 10_020_000_000L)
        val source = stereo(7_000)
        val first = chunker.append(source, 7_000 * 4)
        assertEquals(0, source.position())
        assertEquals(1, first.size)
        assertEquals(10_000_000_000L, first[0].firstSampleBoottimeNs)
        assertEquals(4_800, first[0].frames)
        assertArrayEquals(ByteArray(4_800 * 4) { (it and 0xff).toByte() }, first[0].payload)

        val second = chunker.append(stereo(3_000, first = 7_000), 3_000 * 4)
        assertEquals(1, second.size)
        assertEquals(10_100_000_000L, second[0].firstSampleBoottimeNs)
        // The chunk spans both reads, in order.
        assertArrayEquals(ByteArray(4_800 * 4) { ((4_800 * 4 + it) and 0xff).toByte() }, second[0].payload)

        // A later timestamp only measures drift; the epoch stays fixed.
        chunker.observeTimestamp(9_600, 10_200_050_000L)
        val tail = chunker.flush()
        assertEquals(1, tail.size)
        assertEquals(10_200_000_000L, tail[0].firstSampleBoottimeNs)
        assertEquals(10_000 - 2 * 4_800, tail[0].frames)
        assertTrue(chunker.timestampBacked)
        assertEquals(50_000L, chunker.maxResidualNs)
    }

    @Test
    fun aFlushCarriesTheLastPartialChunk() {
        val chunker = RawPcmChunker(48_000, 1, startReceiptBoottimeNs = 1, chunkFrames = 100)
        chunker.observeTimestamp(0, 1_000_000_000L)
        assertEquals(2, chunker.append(ByteBuffer.allocate(250 * 2), 250 * 2).size)
        val tail = chunker.flush()
        assertEquals(1, tail.size)
        assertEquals(50, tail[0].frames)
        assertEquals(1_000_000_000L + 200L * 1_000_000_000L / 48_000, tail[0].firstSampleBoottimeNs)
        assertTrue(chunker.flush().isEmpty())
    }

    @Test
    fun chunksWaitForTheFirstTimestampThenAreStampedRetroactively() {
        val chunker = RawPcmChunker(48_000, 1, startReceiptBoottimeNs = 7_000_000_000L, chunkFrames = 480, maxPendingChunks = 5)
        assertTrue(chunker.append(ByteBuffer.allocate(480 * 3 * 2), 480 * 3 * 2).isEmpty())
        chunker.observeTimestamp(1_440, 8_030_000_000L)
        val out = chunker.append(ByteBuffer.allocate(480 * 2), 480 * 2)
        assertEquals(listOf(8_000_000_000L, 8_010_000_000L, 8_020_000_000L, 8_030_000_000L), out.map { it.firstSampleBoottimeNs })
        assertTrue(chunker.timestampBacked)
    }

    @Test
    fun withoutTimestampsTheEpochIsEstimatedFromTheStartAfterAFewChunks() {
        val chunker = RawPcmChunker(48_000, 1, startReceiptBoottimeNs = 7_000_000_000L, chunkFrames = 480, maxPendingChunks = 2)
        assertTrue(chunker.append(ByteBuffer.allocate(480 * 2 * 2), 480 * 2 * 2).isEmpty())
        val out = chunker.append(ByteBuffer.allocate(480 * 2), 480 * 2)
        assertEquals(listOf(7_000_000_000L, 7_010_000_000L, 7_020_000_000L), out.map { it.firstSampleBoottimeNs })
        assertFalse(chunker.timestampBacked)
        assertNull(chunker.maxResidualNs)
        // A timestamp after the estimate does not move the epoch.
        chunker.observeTimestamp(1_440, 9_000_000_000L)
        assertEquals(7_030_000_000L, chunker.append(ByteBuffer.allocate(480 * 2), 480 * 2).single().firstSampleBoottimeNs)
    }

    @Test
    fun aRegressingTimestampIsIgnored() {
        val chunker = RawPcmChunker(48_000, 1, startReceiptBoottimeNs = 1, chunkFrames = 480)
        chunker.observeTimestamp(480, 2_010_000_000L)
        chunker.observeTimestamp(240, 2_000_000_000L)
        assertEquals(2_000_000_000L, chunker.append(ByteBuffer.allocate(480 * 2), 480 * 2).single().firstSampleBoottimeNs)
    }

    @Test
    fun audioFollowsTheSettingAndTheMicrophonePermission() {
        assertEquals(RawTakeAudio.NONE, RawTakeAudio.decide(audioRequested = false, microphoneGranted = true))
        assertEquals(RawTakeAudio.NONE, RawTakeAudio.decide(audioRequested = false, microphoneGranted = false))
        assertEquals(RawTakeAudio.RECORD, RawTakeAudio.decide(audioRequested = true, microphoneGranted = true))
        assertEquals(RawTakeAudio.REFUSE_PERMISSION, RawTakeAudio.decide(audioRequested = true, microphoneGranted = false))
    }
}
