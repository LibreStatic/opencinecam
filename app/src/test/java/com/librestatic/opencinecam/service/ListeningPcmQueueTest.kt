/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.PcmMeterEncoding
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ListeningPcmQueueTest {
    @Test fun boundsIncludeWorkerOwnedPacketAndClearNeverReleasesItsReservation() {
        val queue = ListeningPcmQueue()
        val pcm = ByteBuffer.allocate(65536)
        repeat(4) { assertTrue(queue.offer(pcm, 65536, PcmMeterEncoding.PCM_16, 48000, 2, 1)) }
        val owned = queue.poll()!!
        assertEquals(65536, owned.bytes.size)
        assertFalse(queue.offer(pcm, 4, PcmMeterEncoding.PCM_16, 48000, 2, 1))
        queue.clear()
        assertEquals(1, queue.reservedPackets)
        queue.complete()
        assertEquals(0, queue.reservedPackets)
    }

    @Test fun invalidFramesSizeRateAndChannelsRejectWithoutReservation() {
        val queue = ListeningPcmQueue(); val pcm = ByteBuffer.allocate(65540)
        for (size in listOf(-1, 0, 1, 3, 65540, Int.MAX_VALUE))
            assertFalse(queue.offer(pcm, size, PcmMeterEncoding.PCM_16, 48000, 2, 0))
        for (rate in listOf(0, 7999, 192001, Int.MAX_VALUE))
            assertFalse(queue.offer(pcm, 4, PcmMeterEncoding.PCM_16, rate, 2, 0))
        for (channels in listOf(-1, 0, 3, Int.MAX_VALUE))
            assertFalse(queue.offer(pcm, 4, PcmMeterEncoding.PCM_16, 48000, channels, 0))
        assertEquals(0, queue.reservedPackets)
    }

    @Test fun offerCopiesOnlyPrivateBytesAndPreservesCursorLimitMarkOrder() {
        val source = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply { putLong(0x0102030405060708L) }
        val expected = source.array().clone()
        source.order(ByteOrder.BIG_ENDIAN).position(1).mark().position(2).limit(3)
        val queue = ListeningPcmQueue()
        assertTrue(queue.offer(source, 8, PcmMeterEncoding.PCM_16, 48000, 2, 7))
        assertEquals(2, source.position()); assertEquals(3, source.limit()); assertEquals(ByteOrder.BIG_ENDIAN, source.order())
        source.reset(); assertEquals(1, source.position())
        assertArrayEquals(expected, source.array())
        source.array().fill(0)
        val packet = queue.poll()!!
        assertArrayEquals(expected, packet.bytes); assertEquals(7L, packet.generation)
        queue.complete()
    }

    @Test fun pcm16ListeningConversionPreservesEverySignedChannelSample() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort((-32768).toShort()); putShort(32767); putShort(-123); putShort(456)
        }.array()
        val output = listeningPcm16(ListeningPcmPacket(0, bytes, PcmMeterEncoding.PCM_16, 48000, 2))
        assertEquals(0, output.position()); assertEquals(8, output.remaining())
        assertEquals((-32768).toShort(), output.short); assertEquals(32767.toShort(), output.short)
        assertEquals((-123).toShort(), output.short); assertEquals(456.toShort(), output.short)
    }

    @Test fun packed24SignExtensionAndSaturationUsePrivatePlaybackCopy() {
        val bytes = byteArrayOf(0, 0, 0x80.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f,
            0, 0xff.toByte(), 0xff.toByte(), 0, 1, 0)
        val expected = bytes.clone()
        val output = listeningPcm16(ListeningPcmPacket(0, bytes, PcmMeterEncoding.PCM_24, 48000, 2))
        assertEquals((-32768).toShort(), output.short); assertEquals(32767.toShort(), output.short)
        assertEquals((-1).toShort(), output.short); assertEquals(1.toShort(), output.short)
        assertArrayEquals(expected, bytes)
    }

    @Test fun floatHeadroomClipsOnlyPlaybackAndNonfiniteSamplesBecomeSilence() {
        val bytes = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (value in floatArrayOf(2f, -2f, .5f, -.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) putFloat(value)
        }.array()
        val before = bytes.clone()
        val output = listeningPcm16(ListeningPcmPacket(0, bytes, PcmMeterEncoding.PCM_FLOAT, 48000, 1))
        assertArrayEquals(shortArrayOf(32767, -32768, 16384, -16384, 0, 0, 0), ShortArray(7) { output.short })
        assertArrayEquals(before, bytes)
    }

    @Test fun concurrentOffersCannotExceedFourCopies() {
        val queue = ListeningPcmQueue(); val go = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(12)
        try {
            val calls = (1..12).map { workers.submit<Boolean> {
                check(go.await(5, TimeUnit.SECONDS))
                queue.offer(ByteBuffer.allocate(64), 64, PcmMeterEncoding.PCM_16, 48000, 2, 9)
            } }
            go.countDown()
            assertEquals(4, calls.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(4, queue.reservedPackets)
            queue.clear(); assertEquals(0, queue.reservedPackets)
        } finally { go.countDown(); workers.shutdownNow() }
    }

    @Test fun readonlySlicedProducerBufferIsAcceptedWithoutModifyingItsParent() {
        val parent = ByteBuffer.allocate(12).apply { repeat(12) { put(it, it.toByte()) }; position(4); limit(8) }
        val queue = ListeningPcmQueue()
        assertTrue(queue.offer(parent.slice().asReadOnlyBuffer(), 4, PcmMeterEncoding.PCM_16, 44100, 2, 2))
        val packet = queue.poll()!!
        assertArrayEquals(byteArrayOf(4,5,6,7), packet.bytes)
        assertEquals(4, parent.position()); assertEquals(8, parent.limit())
        queue.complete()
    }
}
