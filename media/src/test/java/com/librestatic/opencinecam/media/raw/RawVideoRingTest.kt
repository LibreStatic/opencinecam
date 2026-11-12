// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.raw

import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVideoRingTest {
    private fun header(frameNumber: Long, width: Int = 8, height: Int = 2) =
        OcrawFrameHeader.raw10(width, height, 0, 0, frameNumber * 1000, frameNumber)

    @Test
    fun fullRingRefusesAndOccupancyIsTracked() {
        val ring = RawVideoRing(slotBytes = 64, slotCount = 4)
        assertEquals(256L, ring.capacityBytes)
        val held = ArrayList<RawVideoRing.Slot>()
        repeat(4) { index ->
            val slot = ring.tryAcquire()!!
            slot.buffer.put(ByteArray(20) { index.toByte() })
            ring.publish(slot, header(index.toLong()))
            held += slot
        }
        assertNull("full ring is backpressure", ring.tryAcquire())
        assertEquals(4, ring.usedSlots)
        assertEquals(256L, ring.usedBytes)
        assertTrue(ring.isAboveHighWater(0.9))
        assertEquals(4, ring.queuedSlots)
        val first = ring.take(0)!!
        assertSame(held[0], first)
        assertEquals(20, first.length)
        assertEquals(20, first.buffer.remaining())
        assertEquals(0L, first.header!!.frameNumber)
        assertNull("taken but not released still occupies", ring.tryAcquire())
        ring.release(first)
        assertEquals(3, ring.usedSlots)
        assertFalse(ring.isAboveHighWater(0.9))
        assertTrue(ring.isAboveHighWater(0.75))
        assertEquals(4, ring.peakSlots)
        assertEquals(1.0, ring.peakFraction, 0.0)
        val reused = ring.tryAcquire()!!
        assertSame(held[0], reused)
        assertEquals(0, reused.buffer.position())
        assertEquals(64, reused.buffer.limit())
        assertThrows(IllegalStateException::class.java) { ring.tryAcquire() }
        ring.cancel(reused)
        assertEquals(3, ring.usedSlots)
        assertThrows(IllegalStateException::class.java) { ring.release(held[2]) }
        assertThrows(IllegalArgumentException::class.java) {
            ring.publish(ring.tryAcquire()!!, header(9, width = 64, height = 2))
        }
    }

    @Test
    fun closeWakesTheConsumerAndDrains() {
        val ring = RawVideoRing(slotBytes = 32, slotCount = 3)
        val result = AtomicReference<RawVideoRing.Slot?>(null)
        val waited = Thread { result.set(ring.take(10_000)) }
        waited.start()
        Thread.sleep(50)
        val start = System.nanoTime()
        ring.close()
        waited.join(5_000)
        assertFalse(waited.isAlive)
        assertNull(result.get())
        assertTrue("close woke the consumer", System.nanoTime() - start < 2_000_000_000L)

        val draining = RawVideoRing(slotBytes = 32, slotCount = 3)
        repeat(2) { index ->
            val slot = draining.tryAcquire()!!
            draining.publish(slot, header(index.toLong()))
        }
        val pending = draining.tryAcquire()!!
        draining.close()
        assertTrue(draining.isClosed)
        assertThrows(IllegalStateException::class.java) { draining.publish(pending, header(2)) }
        draining.cancel(pending)
        assertNull(draining.tryAcquire())
        val a = draining.take(0)!!
        draining.release(a)
        val b = draining.take(0)!!
        assertEquals(1L, b.header!!.frameNumber)
        draining.release(b)
        assertNull(draining.take(100))
        assertEquals(0, draining.usedSlots)
    }

    @Test
    fun takeTimesOut() {
        val ring = RawVideoRing(slotBytes = 8, slotCount = 2)
        val start = System.nanoTime()
        assertNull(ring.take(30))
        assertTrue(System.nanoTime() - start >= 25_000_000L)
    }

    @Test
    fun producerConsumerKeepEveryFrameInOrder() {
        val ring = RawVideoRing(slotBytes = 40, slotCount = 8)
        val frames = 50_000
        var backpressure = 0L
        val failure = AtomicReference<Throwable?>(null)
        val producer = Thread {
            try {
                for (frame in 0 until frames) {
                    var slot = ring.tryAcquire()
                    while (slot == null) {
                        backpressure++
                        Thread.onSpinWait()
                        slot = ring.tryAcquire()
                    }
                    val length = 10 + frame % 21
                    for (i in 0 until length) slot.buffer.put((frame + i).toByte())
                    // RAW10 header 8x2 is 20 bytes; the published length is the header's.
                    val header = OcrawFrameHeader.raw10(8, 2, 0, 0, frame.toLong(), frame.toLong())
                    slot.buffer.putInt(0, frame)
                    ring.publish(slot, header)
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                ring.close()
            }
        }
        var expected = 0
        producer.start()
        while (true) {
            val slot = ring.take(5_000) ?: break
            assertEquals(expected.toLong(), slot.header!!.frameNumber)
            assertEquals(expected, slot.buffer.getInt(0))
            assertEquals(20, slot.buffer.remaining())
            for (i in 4 until minOf(20, 10 + expected % 21)) {
                assertEquals((expected + i).toByte(), slot.buffer.get(i))
            }
            if (expected % 997 == 0) Thread.sleep(1) // let the ring fill up now and then
            ring.release(slot)
            expected++
        }
        producer.join()
        failure.get()?.let { throw it }
        assertEquals(frames, expected)
        assertEquals(0, ring.usedSlots)
        assertEquals(8, ring.peakSlots)
        assertTrue("the producer saw a full ring", backpressure > 0)
    }

    @Test
    fun cropCopyMatchesNaiveReference() {
        val random = Random(17)
        repeat(60) {
            val width = 4 * random.nextInt(1, 40)
            val height = random.nextInt(2, 30)
            val rowBytes = width / 4 * 5
            val stride = rowBytes + random.nextInt(0, 3) * 16
            val offset = random.nextInt(0, 9)
            val plane = random.nextBytes(offset + stride * (height - 1) + rowBytes)
            val cropLeft = 4 * random.nextInt(0, width / 4)
            val cropWidth = 4 * random.nextInt(1, (width - cropLeft) / 4 + 1)
            val cropTop = 2 * random.nextInt(0, height / 2)
            val cropHeight = random.nextInt(1, height - cropTop + 1)
            val expected = ByteArray(cropWidth / 4 * 5 * cropHeight)
            var at = 0
            for (row in cropTop until cropTop + cropHeight) {
                for (byte in cropLeft / 4 * 5 until (cropLeft + cropWidth) / 4 * 5) {
                    expected[at++] = plane[offset + row * stride + byte]
                }
            }
            for (direct in listOf(false, true)) {
                val src = if (direct) {
                    ByteBuffer.allocateDirect(plane.size).put(plane).also { it.position(offset) }
                } else {
                    ByteBuffer.wrap(plane).also { it.position(offset) }
                }
                val dst = ByteBuffer.allocateDirect(expected.size + 7)
                dst.position(3)
                val written = copyRaw10Rows(src, stride, width, height, cropLeft, cropTop, cropWidth, cropHeight, dst)
                assertEquals(expected.size, written)
                assertEquals(3 + expected.size, dst.position())
                assertEquals(offset, src.position())
                assertEquals(plane.size, src.limit())
                val back = ByteArray(expected.size)
                dst.position(3)
                dst.get(back)
                assertArrayEquals(expected, back)
            }
        }
    }

    @Test
    fun cropCopyValidates() {
        val src = ByteBuffer.allocate(5 * 4)
        val dst = ByteBuffer.allocate(64)
        assertThrows(IllegalArgumentException::class.java) { copyRaw10Rows(src, 5, 4, 4, 2, 0, 2, 2, dst) }
        assertThrows(IllegalArgumentException::class.java) { copyRaw10Rows(src, 5, 4, 4, 0, 1, 4, 2, dst) }
        assertThrows(IllegalArgumentException::class.java) { copyRaw10Rows(src, 5, 4, 4, 0, 0, 4, 5, dst) }
        assertThrows(IllegalArgumentException::class.java) { copyRaw10Rows(src, 4, 4, 4, 0, 0, 4, 4, dst) }
        assertThrows(IllegalArgumentException::class.java) { copyRaw10Rows(src, 6, 4, 4, 0, 0, 4, 4, dst) }
        assertThrows(IllegalArgumentException::class.java) {
            copyRaw10Rows(src, 5, 4, 4, 0, 0, 4, 4, ByteBuffer.allocate(19))
        }
        assertEquals(20, copyRaw10Rows(src, 5, 4, 4, 0, 0, 4, 4, dst))
    }
}
