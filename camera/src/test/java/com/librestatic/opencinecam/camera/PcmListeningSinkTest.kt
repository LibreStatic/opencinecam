/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ReadOnlyBufferException
import org.junit.Assert.*
import org.junit.Test

class PcmListeningSinkTest {
    @Test fun listeningHasPrivateReadOnlyCursorAndCannotChangeRecorderBytesOrMark() {
        val source = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        repeat(16) { source.put(it, it.toByte()) }
        source.position(3); source.mark(); source.position(5); source.limit(12)
        val expected = ByteArray(16) { it.toByte() }
        val sink = PcmListeningSink { view, count, encoding, rate, channels ->
            assertTrue(view.isReadOnly); assertEquals(12, count)
            assertEquals(PcmMeterEncoding.PCM_24, encoding); assertEquals(48000, rate); assertEquals(2, channels)
            view.clear(); view.position(1); assertEquals(1, view.get().toInt())
            try { view.put(0, 99.toByte()); fail("Listening must not write capture PCM") }
            catch (_: ReadOnlyBufferException) { }
            true
        }
        assertTrue(sink.offerListening(source, 12, PcmMeterEncoding.PCM_24, 48000, 2))
        assertEquals(5, source.position()); assertEquals(12, source.limit()); assertEquals(ByteOrder.LITTLE_ENDIAN, source.order())
        source.reset(); assertEquals(3, source.position())
        assertArrayEquals(expected, source.array())
    }
    @Test fun optionalConsumerExceptionDoesNotFailRecorderOrModifyItsBuffer() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        val sink = PcmListeningSink { _, _, _, _, _ -> throw AssertionError("Optional listening rejected") }
        assertFalse(sink.offerListening(source, 4, PcmMeterEncoding.PCM_16, 48000, 2))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), source.array()); assertEquals(0, source.position())
    }
    @Test fun absentOrFullListeningConsumerDropsOnlyListeningBranch() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2))
        assertFalse((null as PcmListeningSink?).offerListening(source, 2, PcmMeterEncoding.PCM_16, 48000, 1))
        assertFalse(PcmListeningSink { _, _, _, _, _ -> false }.offerListening(source, 2, PcmMeterEncoding.PCM_16, 48000, 1))
        assertArrayEquals(byteArrayOf(1, 2), source.array())
    }
}
