/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/** Rewrites DefaultCropOrigin/DefaultCropSize of a classic-TIFF DNG in place; the file size never changes. */
object DngDefaultCrop {
    private const val TAG_SUB_IFDS = 0x014A
    private const val TAG_ORIGIN = 0xC61F
    private const val TAG_SIZE = 0xC620
    private const val MAX_IFDS = 16

    private class Entry(val type: Int, val valueOffset: Int, val den: Long)

    /** Returns false, leaving [bytes] untouched, when the crop cannot be applied. */
    fun apply(bytes: ByteArray, rect: NormRect): Boolean = try { patch(bytes, rect) } catch (_: RuntimeException) { false }

    private fun patch(bytes: ByteArray, rect: NormRect): Boolean {
        if (bytes.size < 8) return false
        val order = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return false
        }
        val buf = ByteBuffer.wrap(bytes).order(order)
        if (buf.getShort(2).toInt() and 0xFFFF != 42) return false
        val queue = ArrayDeque<Long>()
        queue.add(buf.getInt(4).toLong() and 0xFFFFFFFFL)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_IFDS) {
            val ifd = queue.removeFirst()
            if (ifd < 8 || ifd + 2 > bytes.size) continue
            val count = buf.getShort(ifd.toInt()).toInt() and 0xFFFF
            if (ifd + 2 + count * 12L > bytes.size) continue
            var origin: Entry? = null
            var size: Entry? = null
            for (i in 0 until count) {
                val e = ifd.toInt() + 2 + i * 12
                val tag = buf.getShort(e).toInt() and 0xFFFF
                val type = buf.getShort(e + 2).toInt() and 0xFFFF
                val n = buf.getInt(e + 4).toLong() and 0xFFFFFFFFL
                when (tag) {
                    TAG_ORIGIN, TAG_SIZE -> {
                        val entry = entry(buf, e, type, n) ?: return false
                        if (tag == TAG_ORIGIN) origin = entry else size = entry
                    }
                    TAG_SUB_IFDS -> if (type == 4 || type == 13) {
                        if (n * 4 > bytes.size) return false
                        val base = if (n <= 1) e + 8 else (buf.getInt(e + 8).toLong() and 0xFFFFFFFFL).toInt()
                        if (base < 0 || base + n * 4 > bytes.size) return false
                        for (k in 0 until n.toInt()) queue.add(buf.getInt(base + k * 4).toLong() and 0xFFFFFFFFL)
                    }
                }
            }
            if (origin != null && size != null) return write(buf, origin, size, rect)
        }
        return false
    }

    private fun entry(buf: ByteBuffer, e: Int, type: Int, count: Long): Entry? {
        if (count != 2L) return null
        val bytes = when (type) { 3 -> 4; 4 -> 8; 5 -> 16; else -> return null }
        val valueOffset = if (bytes <= 4) e + 8 else (buf.getInt(e + 8).toLong() and 0xFFFFFFFFL).toInt()
        if (valueOffset < 0 || valueOffset.toLong() + bytes > buf.limit()) return null
        val den = if (type == 5) buf.getInt(valueOffset + 4).toLong() and 0xFFFFFFFFL else 1L
        if (den == 0L) return null
        return Entry(type, valueOffset, den)
    }

    private fun read(buf: ByteBuffer, e: Entry, index: Int): Long = when (e.type) {
        3 -> (buf.getShort(e.valueOffset + index * 2).toLong() and 0xFFFF)
        4 -> buf.getInt(e.valueOffset + index * 4).toLong() and 0xFFFFFFFFL
        else -> (buf.getInt(e.valueOffset + index * 8).toLong() and 0xFFFFFFFFL) / e.den
    }

    private fun store(buf: ByteBuffer, e: Entry, index: Int, value: Long) {
        when (e.type) {
            3 -> buf.putShort(e.valueOffset + index * 2, value.toShort())
            4 -> buf.putInt(e.valueOffset + index * 4, value.toInt())
            else -> buf.putInt(e.valueOffset + index * 8, (value * e.den).toInt())
        }
    }

    private fun maxValue(e: Entry): Long = when (e.type) { 3 -> 0xFFFFL; 4 -> 0xFFFFFFFFL; else -> 0xFFFFFFFFL / e.den }

    private fun write(buf: ByteBuffer, origin: Entry, size: Entry, rect: NormRect): Boolean {
        val oldOrigin = longArrayOf(read(buf, origin, 0), read(buf, origin, 1))
        val oldSize = longArrayOf(read(buf, size, 0), read(buf, size, 1))
        if (oldSize[0] <= 0 || oldSize[1] <= 0) return false
        val lo = doubleArrayOf(rect.left, rect.top)
        val span = doubleArrayOf(rect.width, rect.height)
        val newOrigin = LongArray(2)
        val newSize = LongArray(2)
        for (axis in 0..1) {
            val size0 = oldSize[axis]
            val sz = (span[axis] * size0).roundToLong().coerceIn(1L, size0)
            val off = (lo[axis] * size0).roundToLong().coerceIn(0L, size0 - sz)
            newSize[axis] = sz
            newOrigin[axis] = oldOrigin[axis] + off
            if (newOrigin[axis] > maxValue(origin) || newSize[axis] > maxValue(size)) return false
        }
        for (axis in 0..1) {
            store(buf, origin, axis, newOrigin[axis])
            store(buf, size, axis, newSize[axis])
        }
        return true
    }
}
