/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSpoolFormatTest {
    private fun spool(block: MotionSpoolWriter.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        MotionSpoolWriter(bytes).apply(block).close()
        return bytes.toByteArray()
    }

    private fun read(bytes: ByteArray) = readSpool(bytes)

    @Test fun roundTripsEveryKind() {
        val out = ByteArrayOutputStream()
        val writer = MotionSpoolWriter(out)
        writer.sample(MotionSpoolFormat.KIND_GYRO, 10, 0.5f, -1.25f, 3f)
        writer.sample(MotionSpoolFormat.KIND_ACCEL, 11, 9.80665f, 0f, -0.1f)
        writer.sample(MotionSpoolFormat.KIND_MAG, 12, 40f, -12.5f, 3.75f)
        writer.frame(100, Long.MAX_VALUE - 7)
        writer.exposure(90, 16_666_666, 12_000_000)
        writer.sample(MotionSpoolFormat.KIND_GYRO, 20, Float.MIN_VALUE, Float.MAX_VALUE, -0f)
        writer.close()
        val bytes = out.toByteArray()

        assertEquals(MotionSpoolFormat.HEADER_BYTES + 6 * MotionSpoolFormat.RECORD_BYTES, bytes.size)
        assertEquals("OCMO", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(1, bytes[4].toInt())
        assertEquals(2L, writer.gyroCount)
        assertEquals(1L, writer.accelCount)
        assertEquals(1L, writer.magCount)
        assertEquals(1L, writer.frameCount)
        assertEquals(1L, writer.exposureCount)

        val spool = read(bytes)
        assertArrayEquals(longArrayOf(10, 20), spool.gyro.timeNs)
        assertArrayEquals(floatArrayOf(0.5f, Float.MIN_VALUE), spool.gyro.x, 0f)
        assertArrayEquals(floatArrayOf(-1.25f, Float.MAX_VALUE), spool.gyro.y, 0f)
        assertEquals((-0f).toRawBits(), spool.gyro.z[1].toRawBits())
        assertArrayEquals(longArrayOf(11), spool.accel.timeNs)
        assertArrayEquals(floatArrayOf(9.80665f), spool.accel.x, 0f)
        assertArrayEquals(floatArrayOf(3.75f), spool.mag.z, 0f)
        assertArrayEquals(longArrayOf(100), spool.frames.sensorNs)
        assertArrayEquals(longArrayOf(Long.MAX_VALUE - 7), spool.frames.filePtsNs)
        assertArrayEquals(longArrayOf(90), spool.exposures.fromNs)
        assertArrayEquals(intArrayOf(16_666_666), spool.exposures.exposureNs)
        assertArrayEquals(intArrayOf(12_000_000), spool.exposures.skewNs)
    }

    @Test fun recordsCrossTheBufferBoundary() {
        val count = 10_000
        val spool = read(spool { for (i in 0 until count) sample(MotionSpoolFormat.KIND_GYRO, i * 2_000_000L, i.toFloat(), 0f, 0f) })
        assertEquals(count, spool.gyro.size)
        assertEquals((count - 1).toFloat(), spool.gyro.x[count - 1], 0f)
    }

    @Test fun tornTailEndsTheRead() {
        val bytes = spool {
            sample(MotionSpoolFormat.KIND_GYRO, 1, 1f, 0f, 0f)
            sample(MotionSpoolFormat.KIND_GYRO, 2, 2f, 0f, 0f)
        }
        val spool = read(bytes.copyOf(bytes.size - 5))
        assertArrayEquals(longArrayOf(1), spool.gyro.timeNs)
    }

    @Test fun unknownKindIsSkipped() {
        val bytes = spool {
            sample(MotionSpoolFormat.KIND_GYRO, 1, 1f, 0f, 0f)
            sample(MotionSpoolFormat.KIND_GYRO, 2, 2f, 0f, 0f)
            sample(MotionSpoolFormat.KIND_ACCEL, 3, 3f, 0f, 0f)
        }
        bytes[MotionSpoolFormat.HEADER_BYTES + MotionSpoolFormat.RECORD_BYTES] = 42
        val spool = read(bytes)
        assertArrayEquals(longArrayOf(1), spool.gyro.timeNs)
        assertArrayEquals(longArrayOf(3), spool.accel.timeNs)
    }

    @Test fun badMagicOrVersionOrHeaderIsRejected() {
        val good = spool { sample(MotionSpoolFormat.KIND_GYRO, 1, 1f, 0f, 0f) }
        fun rejects(bytes: ByteArray) {
            try {
                read(bytes)
                throw AssertionError("expected a rejection")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotEmpty())
            }
        }
        rejects(good.copyOf().also { it[0] = 'X'.code.toByte() })
        rejects(good.copyOf().also { it[4] = 2 })
        rejects(good.copyOf(3))
    }

    @Test fun headerOnlySpoolIsEmpty() {
        val spool = read(spool { })
        assertEquals(0, spool.gyro.size)
        assertEquals(0, spool.frames.size)
        assertEquals(0, spool.exposures.size)
    }

    @Test fun outOfOrderAndDuplicateSamplesAreDropped() {
        val spool = read(
            spool {
                sample(MotionSpoolFormat.KIND_GYRO, 30, 3f, 0f, 0f)
                sample(MotionSpoolFormat.KIND_GYRO, 10, 1f, 0f, 0f)
                sample(MotionSpoolFormat.KIND_GYRO, 20, 2f, 0f, 0f)
                sample(MotionSpoolFormat.KIND_GYRO, 10, 9f, 0f, 0f)
                sample(MotionSpoolFormat.KIND_GYRO, 40, 4f, 0f, 0f)
                sample(MotionSpoolFormat.KIND_ACCEL, 5, 1f, 0f, 0f)
                sample(MotionSpoolFormat.KIND_ACCEL, 5, 2f, 0f, 0f)
                exposure(50, 1, 0)
                exposure(10, 2, 0)
            },
        )
        // Each sensor keeps only samples after its previous kept one; exposures are still sorted.
        assertArrayEquals(longArrayOf(30, 40), spool.gyro.timeNs)
        assertArrayEquals(floatArrayOf(3f, 4f), spool.gyro.x, 0f)
        assertArrayEquals(longArrayOf(5), spool.accel.timeNs)
        assertArrayEquals(floatArrayOf(1f), spool.accel.x, 0f)
        assertArrayEquals(longArrayOf(10, 50), spool.exposures.fromNs)
        assertArrayEquals(intArrayOf(2, 1), spool.exposures.exposureNs)
        val stats = spool.spool.gyroStats
        assertEquals(2L, stats.count)
        assertEquals(30L, stats.firstNs)
        assertEquals(40L, stats.lastNs)
        assertEquals(10L, stats.largestGapNs)
        assertEquals(1L, spool.spool.accelStats.count)
        assertEquals(null, spool.spool.accelStats.measuredHz)
    }

    @Test fun eachCursorReadsTheSpoolAgainAndClosesItsStream() {
        val bytes = spool { for (i in 0 until 3) sample(MotionSpoolFormat.KIND_GYRO, i + 1L, i.toFloat(), 0f, 0f) }
        var opened = 0
        var closed = 0
        val spool = MotionSpoolReader.scan {
            opened++
            object : java.io.ByteArrayInputStream(bytes) {
                override fun close() {
                    closed++
                    super.close()
                }
            }
        }
        assertEquals(1, opened)
        assertEquals(1, closed)
        spool.samples(MotionSpoolFormat.KIND_GYRO).use { cursor ->
            assertTrue(cursor.next())
            assertEquals(1L, cursor.time)
        }
        assertEquals(2, opened)
        assertEquals(2, closed)
        assertEquals(3L, spool.gyroStats.count)
    }

    @Test fun writesAfterCloseAreIgnored() {
        val out = ByteArrayOutputStream()
        val writer = MotionSpoolWriter(out)
        writer.close()
        writer.sample(MotionSpoolFormat.KIND_GYRO, 1, 0f, 0f, 0f)
        writer.frame(1, 1)
        writer.close()
        assertEquals(MotionSpoolFormat.HEADER_BYTES, out.size())
        assertEquals(0L, writer.gyroCount)
    }

    /** Writes through to [bytes]; the [failAt]th write (1-based) stores only its first [partial] bytes and throws. */
    private class FlakyStream(private val failAt: Int, private val partial: Int) : java.io.OutputStream() {
        val bytes = ByteArrayOutputStream()
        var writes = 0
        var closed = false
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            writes++
            if (writes == failAt) {
                bytes.write(b, off, minOf(partial, len))
                throw java.io.IOException("no space left")
            }
            bytes.write(b, off, len)
        }
        override fun close() {
            closed = true
        }
    }

    @Test fun aFailedWriteIsNeverReplayedAndTheStreamStillCloses() {
        // The header is two writes; the first buffer drain is the third, which stops 10 bytes into a 21-byte record.
        val out = FlakyStream(failAt = 3, partial = MotionSpoolFormat.RECORD_BYTES * 5 + 10)
        val writer = MotionSpoolWriter(out)
        val perBuffer = MotionSpoolFormat.BUFFER_BYTES / MotionSpoolFormat.RECORD_BYTES
        var thrown: Exception? = null
        try {
            for (i in 0..perBuffer) writer.sample(MotionSpoolFormat.KIND_GYRO, i + 1L, 1f, 0f, 0f)
        } catch (problem: java.io.IOException) {
            thrown = problem
        }
        assertTrue(thrown != null)
        assertTrue(writer.failed)
        // Storage is writable again, but nothing more reaches it: not a sample, a flush nor the close.
        writer.sample(MotionSpoolFormat.KIND_GYRO, 1_000_000L, 1f, 0f, 0f)
        writer.frame(1, 1)
        writer.flush()
        writer.close()
        assertTrue(out.closed)
        val bytes = out.bytes.toByteArray()
        assertEquals(MotionSpoolFormat.HEADER_BYTES + MotionSpoolFormat.RECORD_BYTES * 5 + 10, bytes.size)
        // The reader keeps the intact prefix and cuts the torn record.
        assertEquals(5, read(bytes).gyro.size)
    }

    @Test fun framesNeverWaitForAWriteInProgress() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val out = object : java.io.OutputStream() {
            var header = 0
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (header++ < 2) return
                entered.countDown()
                release.await()
            }
        }
        val writer = MotionSpoolWriter(out)
        val perBuffer = MotionSpoolFormat.BUFFER_BYTES / MotionSpoolFormat.RECORD_BYTES
        // The sensor thread fills the buffer, and its drain stalls in the write.
        val sensor = Thread { for (i in 0..perBuffer) writer.sample(MotionSpoolFormat.KIND_GYRO, i + 1L, 0f, 0f, 1f) }
        val frames = Thread {
            for (i in 0 until 100) writer.frame(i.toLong(), i.toLong())
            writer.exposure(1L, 10_000_000, 0)
        }
        var returned = false
        sensor.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            frames.start()
            frames.join(2_000)
            returned = !frames.isAlive
        } finally {
            // Also when an assertion fails: never leave the sensor thread parked in the stalled write.
            release.countDown()
            sensor.join(5_000)
            if (frames.isAlive) frames.join(5_000)
        }
        assertTrue("frame() waited for the stalled write", returned)
        assertEquals(100L, writer.frameCount)
        assertEquals(false, writer.failed)
    }

    @Test fun aFullFrameQueueFailsTheSpoolInsteadOfWaiting() {
        val out = ByteArrayOutputStream()
        val writer = MotionSpoolWriter(out, queueCapacity = 4)
        for (i in 0 until 4) writer.frame(i.toLong(), i * 10L)
        assertEquals(false, writer.failed)
        writer.frame(4, 40)
        assertTrue(writer.failed)
        writer.close()
        // What was queued is still written; the record that did not fit is not.
        assertEquals(listOf(0L, 1L, 2L, 3L), read(out.toByteArray()).frames.sensorNs.toList())
    }

    @Test fun queuedRecordsKeepTheirPlaceAmongSamples() {
        val bytes = spool {
            sample(MotionSpoolFormat.KIND_GYRO, 1, 0f, 0f, 0f)
            frame(5, 50)
            sample(MotionSpoolFormat.KIND_GYRO, 2, 0f, 0f, 0f)
            exposure(6, 1_000, 2_000)
        }
        // Kinds in file order: gyro, frame, gyro, exposure.
        val kinds = (0 until 4).map { bytes[MotionSpoolFormat.HEADER_BYTES + it * MotionSpoolFormat.RECORD_BYTES].toInt() }
        assertEquals(listOf(MotionSpoolFormat.KIND_GYRO, MotionSpoolFormat.KIND_FRAME, MotionSpoolFormat.KIND_GYRO,
            MotionSpoolFormat.KIND_EXPOSURE), kinds)
        assertEquals(listOf(1_000), read(bytes).exposures.exposureNs.toList())
        assertEquals(listOf(2_000), read(bytes).exposures.skewNs.toList())
    }
}
