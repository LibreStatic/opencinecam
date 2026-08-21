/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCaptureTest {
    private fun metadata(format: RawFormat, width: Int, height: Int, stride: Int) = RawFrameMetadata(
        width = width,
        height = height,
        rowStride = stride,
        pixelStride = if (format == RawFormat.RAW_SENSOR) 2 else 1,
        cfaPattern = "RGGB",
        blackLevel = 64,
        whiteLevel = 1023,
        timestampNs = 100,
        format = format,
    )

    @Test
    fun unpacksRaw10Raw12AndSensorWhilePreservingDngTags() {
        val raw10 = RawFrame(1, metadata(RawFormat.RAW10, 4, 1, 5),
            byteArrayOf(0x00, 0x40, 0x80.toByte(), 0xc0.toByte(), 0xe4.toByte()))
        assertArrayEquals(intArrayOf(0, 257, 514, 771), RawUnpacker.unpack(raw10))
        val raw12 = RawFrame(2, metadata(RawFormat.RAW12, 2, 1, 3),
            byteArrayOf(0x12, 0x34, 0x56))
        assertArrayEquals(intArrayOf(0x126, 0x345), RawUnpacker.unpack(raw12))
        val sensor = RawFrame(3, metadata(RawFormat.RAW_SENSOR, 2, 1, 4),
            byteArrayOf(0x34, 0x12, 0x78, 0x05))
        assertArrayEquals(intArrayOf(0x1234, 0x0578), RawUnpacker.unpack(sensor))
        val dng = RawStillDng.createFixture(raw10)
        assertEquals("RGGB", dng.tags["CFA"])
        assertEquals("64", dng.tags["BlackLevel"])
        assertTrue(RawStillDng.compare(dng, dng.copy(payload = dng.payload.copyOf())))
    }

    @Test
    fun packedRawAllowsAndroidZeroPixelStride() {
        val frame = RawFrame(
            4,
            metadata(RawFormat.RAW10, 4, 1, 5).copy(pixelStride = 0),
            byteArrayOf(0x00, 0x40, 0x80.toByte(), 0xc0.toByte(), 0xe4.toByte()),
        )
        assertArrayEquals(intArrayOf(0, 257, 514, 771), RawUnpacker.unpack(frame))
    }

    @Test
    fun ringIsByteBoundedNoDropAndOwnsPayloadCopies() {
        val frame = RawFrame(10, metadata(RawFormat.RAW10, 4, 1, 5), ByteArray(5))
        val ring = RawFrameRing(maxFrames = 1, maxBytes = 5)
        assertEquals(RawRingStatus.ENQUEUED, ring.offer(frame))
        assertEquals(RawRingStatus.DUPLICATE, ring.offer(frame))
        assertEquals(RawRingStatus.CAPACITY_EXCEEDED,
            ring.offer(frame.copy(frameId = 11)))
        frame.payload[0] = 99
        assertEquals(0, ring.poll()?.payload?.get(0)?.toInt())
        ring.close()
        assertEquals(RawRingStatus.CLOSED, ring.offer(frame.copy(frameId = 12)))
    }

    @Test
    fun invalidPackedRowsFailExplicitly() {
        val shortFrame = RawFrame(20, metadata(RawFormat.RAW12, 2, 1, 2), byteArrayOf(1, 2))
        val tooShort = shortFrame.copy(payload = byteArrayOf(1, 2))
        try {
            RawUnpacker.unpack(tooShort)
            throw AssertionError("truncated RAW12 must fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
