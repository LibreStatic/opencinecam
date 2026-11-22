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
}
