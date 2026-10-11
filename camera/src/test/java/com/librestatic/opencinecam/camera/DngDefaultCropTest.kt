/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DngDefaultCropTest {
    private val half = NormRect(0.25, 0.25, 0.75, 0.75)

    /** type 3 SHORT inline, 4 LONG at offset, 5 RATIONAL (den 1 or given). */
    private fun build(order: ByteOrder, type: Int, origin: IntArray, size: IntArray, sub: Boolean, den: Int = 1): ByteArray {
        val buf = ByteBuffer.allocate(512).order(order)
        buf.put(if (order == ByteOrder.LITTLE_ENDIAN) "II".toByteArray() else "MM".toByteArray())
        buf.putShort(42)
        val ifd0 = 8
        val ifd = if (sub) 64 else ifd0
        buf.putInt(ifd0)
        if (sub) {
            buf.position(ifd0)
            buf.putShort(1)
            buf.putShort(0x014A.toShort()); buf.putShort(4); buf.putInt(1); buf.putInt(ifd)
            buf.putInt(0)
        }
        val dataStart = 200
        val block = if (type == 4) 8 else 16
        buf.position(ifd)
        buf.putShort(2)
        fun entry(tag: Int, values: IntArray, at: Int) {
            buf.putShort(tag.toShort()); buf.putShort(type.toShort()); buf.putInt(2)
            when (type) {
                3 -> { buf.putShort(values[0].toShort()); buf.putShort(values[1].toShort()) }
                else -> buf.putInt(at)
            }
        }
        entry(0xC61F, origin, dataStart)
        entry(0xC620, size, dataStart + block)
        buf.putInt(0)
        if (type != 3) {
            buf.position(dataStart)
            for (v in origin + size) { if (type == 4) buf.putInt(v) else { buf.putInt(v * den); buf.putInt(den) } }
        }
        return buf.array()
    }

    private fun values(b: ByteArray, order: ByteOrder, type: Int, at: Int, n: Int = 2): List<Int> {
        val buf = ByteBuffer.wrap(b).order(order)
        return List(n) {
            when (type) {
                3 -> buf.getShort(at + it * 2).toInt()
                4 -> buf.getInt(at + it * 4)
                else -> buf.getInt(at + it * 8) / buf.getInt(at + it * 8 + 4)
            }
        }
    }

    @Test fun longAtOffsetBothOrders() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val b = build(order, 4, intArrayOf(8, 4), intArrayOf(4000, 3000), false)
            val len = b.size
            assertTrue(DngDefaultCrop.apply(b, half))
            assertEquals(len, b.size)
            assertEquals(listOf(1008, 754), values(b, order, 4, 200))
            assertEquals(listOf(2000, 1500), values(b, order, 4, 208))
        }
    }

    @Test fun shortInline() {
        val b = build(ByteOrder.BIG_ENDIAN, 3, intArrayOf(0, 0), intArrayOf(400, 300), false)
        assertTrue(DngDefaultCrop.apply(b, half))
        val buf = ByteBuffer.wrap(b)
        assertEquals(100, buf.getShort(8 + 2 + 8).toInt()); assertEquals(75, buf.getShort(8 + 2 + 10).toInt())
        assertEquals(200, buf.getShort(8 + 2 + 12 + 8).toInt()); assertEquals(150, buf.getShort(8 + 2 + 12 + 10).toInt())
    }

    @Test fun rationalKeepsDenominator() {
        val b = build(ByteOrder.LITTLE_ENDIAN, 5, intArrayOf(0, 0), intArrayOf(400, 300), false, den = 4)
        assertTrue(DngDefaultCrop.apply(b, half))
        assertEquals(listOf(100, 75), values(b, ByteOrder.LITTLE_ENDIAN, 5, 200))
        assertEquals(listOf(200, 150), values(b, ByteOrder.LITTLE_ENDIAN, 5, 216))
    }

    @Test fun tagsInsideSubIfd() {
        val b = build(ByteOrder.LITTLE_ENDIAN, 4, intArrayOf(0, 0), intArrayOf(4000, 3000), true)
        assertTrue(DngDefaultCrop.apply(b, half))
        assertEquals(listOf(1000, 750), values(b, ByteOrder.LITTLE_ENDIAN, 4, 200))
    }

    @Test fun clampsInsideOldCrop() {
        val b = build(ByteOrder.LITTLE_ENDIAN, 4, intArrayOf(10, 10), intArrayOf(100, 100), false)
        assertTrue(DngDefaultCrop.apply(b, NormRect(0.9, 0.9, 1.5, 1.5)))
        assertEquals(listOf(50, 50), values(b, ByteOrder.LITTLE_ENDIAN, 4, 200))
        assertEquals(listOf(60, 60), values(b, ByteOrder.LITTLE_ENDIAN, 4, 208))
    }

    @Test fun malformedLeavesBytesUntouched() {
        val good = build(ByteOrder.LITTLE_ENDIAN, 4, intArrayOf(0, 0), intArrayOf(4000, 3000), false)
        val missing = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(8 + 2, 0x0100) }
        val badMagic = good.copyOf().also { it[2] = 43 }
        val badOffset = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8 + 2 + 8, 100000) }
        val badIfd = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 100000) }
        for (b in listOf(missing, badMagic, badOffset, badIfd, ByteArray(3))) {
            val before = b.copyOf()
            assertFalse(DngDefaultCrop.apply(b, half))
            assertArrayEquals(before, b)
        }
    }
}
