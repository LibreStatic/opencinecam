/* SPDX-License-Identifier: Apache-2.0 */

package com.librestatic.opencinecam.rawvideo

import com.librestatic.opencinecam.media.ocraw.OcrawImuMetadata
import com.librestatic.opencinecam.media.ocraw.OcrawImuSamples
import com.librestatic.opencinecam.motion.MotionSpoolFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The motion batches between the sensor thread and the RAW writer. */
class RawImuBufferTest {
    private fun RawImuBuffer.gyro(ns: Long, value: Float = 0f) = append(MotionSpoolFormat.KIND_GYRO, ns, value, value + 1, value + 2)

    @Test
    fun kindsMapToTheContainerKindsAndTimesMoveToTheFrameClock() {
        val buffer = RawImuBuffer(clockOffsetNs = 1_000L, batchSpanNs = 1_000_000_000L)
        buffer.append(MotionSpoolFormat.KIND_GYRO, 10_000L, 0.1f, 0.2f, 0.3f)
        buffer.append(MotionSpoolFormat.KIND_ACCEL, 11_000L, 1f, 2f, 3f)
        buffer.append(MotionSpoolFormat.KIND_MAG, 12_000L, -4f, -5f, -6f)
        // Frame and exposure marks are not sensor samples: ignored.
        buffer.append(MotionSpoolFormat.KIND_FRAME, 13_000L, 0f, 0f, 0f)
        buffer.append(MotionSpoolFormat.KIND_EXPOSURE, 14_000L, 0f, 0f, 0f)
        assertNull("a batch waits for its second", buffer.poll())

        buffer.close()
        val batch = buffer.poll()
        assertNotNull(batch)
        assertNull(buffer.poll())
        batch!!
        assertEquals(3, batch.count)
        assertArrayEquals(
            byteArrayOf(OcrawImuSamples.KIND_GYROSCOPE.toByte(), OcrawImuSamples.KIND_ACCELEROMETER.toByte(), OcrawImuSamples.KIND_MAGNETOMETER.toByte()),
            batch.kinds.copyOf(3),
        )
        assertArrayEquals(longArrayOf(9_000L, 10_000L, 11_000L), batch.timesNs.copyOf(3))
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 1f, 2f, 3f, -4f, -5f, -6f), batch.xyz.copyOf(9), 0f)
        assertEquals(0, buffer.droppedSamples)
    }

    @Test
    fun ocrawKindMapsOnlyTheThreeSensors() {
        assertEquals(OcrawImuSamples.KIND_GYROSCOPE, RawImuBuffer.ocrawKind(MotionSpoolFormat.KIND_GYRO))
        assertEquals(OcrawImuSamples.KIND_ACCELEROMETER, RawImuBuffer.ocrawKind(MotionSpoolFormat.KIND_ACCEL))
        assertEquals(OcrawImuSamples.KIND_MAGNETOMETER, RawImuBuffer.ocrawKind(MotionSpoolFormat.KIND_MAG))
        assertEquals(0, RawImuBuffer.ocrawKind(MotionSpoolFormat.KIND_FRAME))
        assertEquals(0, RawImuBuffer.ocrawKind(99))
    }

    @Test
    fun aBatchSpanningTheWindowOrFullIsHandedToTheWriter() {
        val buffer = RawImuBuffer(clockOffsetNs = 0, batchCapacity = 4, batches = 3, batchSpanNs = 100)
        buffer.gyro(1_000)
        buffer.gyro(1_050)
        assertNull(buffer.poll())
        // 100 ns after the batch's first sample: the batch, this sample included, goes.
        buffer.gyro(1_100)
        val bySpan = buffer.poll()!!
        assertEquals(3, bySpan.count)
        assertArrayEquals(longArrayOf(1_000, 1_050, 1_100), bySpan.timesNs.copyOf(3))
        buffer.recycle(bySpan)

        // Four samples within the window fill the batch.
        repeat(4) { buffer.gyro(2_000L + it) }
        val byCount = buffer.poll()!!
        assertEquals(4, byCount.count)
        assertArrayEquals(longArrayOf(2_000, 2_001, 2_002, 2_003), byCount.timesNs.copyOf(4))
        assertNull(buffer.poll())
        assertEquals(0, buffer.droppedSamples)
    }

    @Test
    fun withNoFreeBatchTheFillingOneIsDroppedAndCounted() {
        val buffer = RawImuBuffer(clockOffsetNs = 0, batchCapacity = 2, batches = 2, batchSpanNs = Long.MAX_VALUE)
        // Two batches: the first full one goes to the writer, which keeps it.
        buffer.gyro(1, 1f)
        buffer.gyro(2, 2f)
        val held = buffer.poll()!!
        // The second fills with nothing free to swap in: its two samples are dropped, the batch refilled.
        buffer.gyro(3, 3f)
        buffer.gyro(4, 4f)
        assertEquals(2, buffer.droppedSamples)
        assertNull(buffer.poll())
        buffer.gyro(5, 5f)
        // The writer gives its batch back: the next full batch is handed over again.
        buffer.recycle(held)
        buffer.gyro(6, 6f)
        val next = buffer.poll()!!
        assertArrayEquals(longArrayOf(5, 6), next.timesNs.copyOf(next.count))
        assertArrayEquals(floatArrayOf(5f, 6f, 7f, 6f, 7f, 8f), next.xyz.copyOf(6), 0f)
        // A batch the writer could not write counts as dropped too.
        buffer.discard(next)
        assertEquals(4, buffer.droppedSamples)
    }

    @Test
    fun closeHandsOverThePartialBatchAndRefusesLaterSamples() {
        val buffer = RawImuBuffer(clockOffsetNs = 0, batchCapacity = 8, batches = 2, batchSpanNs = Long.MAX_VALUE)
        buffer.gyro(10)
        buffer.close()
        buffer.gyro(11)
        buffer.close()
        val last = buffer.poll()!!
        assertEquals(1, last.count)
        assertEquals(10L, last.timesNs[0])
        assertNull(buffer.poll())
        buffer.recycle(last)
        assertEquals(0, last.count)
        assertEquals(0, buffer.droppedSamples)
    }

    @Test
    fun anEmptyBufferClosesWithoutABatch() {
        val buffer = RawImuBuffer(clockOffsetNs = 0)
        buffer.close()
        assertNull(buffer.poll())
    }

    @Test
    fun recycledBatchesAreReused() {
        val buffer = RawImuBuffer(clockOffsetNs = 0, batchCapacity = 1, batches = 2, batchSpanNs = Long.MAX_VALUE)
        buffer.gyro(1)
        val first = buffer.poll()!!
        buffer.recycle(first)
        buffer.gyro(2)
        val second = buffer.poll()!!
        buffer.recycle(second)
        buffer.gyro(3)
        assertSame(first, buffer.poll())
    }

    @Test
    fun takeMetadataNamesTheClock() {
        val shared = RawImuBuffer.takeMetadata("yXz", 0, sensorRealtime = true, takeStartUnixMs = 1_763_700_000_123L)
        assertEquals(
            mapOf(
                OcrawImuMetadata.ORIENTATION to "yXz",
                OcrawImuMetadata.READOUT_DIRECTION to "0",
                OcrawImuMetadata.CLOCK to OcrawImuMetadata.CLOCK_SHARED,
                OcrawImuMetadata.TAKE_START_UNIX_MS to "1763700000123",
            ),
            shared,
        )
        val estimated = RawImuBuffer.takeMetadata("yxz", 2, sensorRealtime = false, takeStartUnixMs = 5)
        assertEquals(OcrawImuMetadata.CLOCK_ESTIMATED, estimated[OcrawImuMetadata.CLOCK])
        assertEquals("2", estimated[OcrawImuMetadata.READOUT_DIRECTION])
    }
}
