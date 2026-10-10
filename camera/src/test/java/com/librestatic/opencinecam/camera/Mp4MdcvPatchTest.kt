/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4MdcvPatchTest {
    private class MemoryIo(val bytes: ByteArray) : Mp4MdcvPatch.Io {
        override val size: Long get() = bytes.size.toLong()
        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), into, offset, count)
            return count
        }
        override fun write(position: Long, bytes: ByteArray) = System.arraycopy(bytes, 0, this.bytes, position.toInt(), bytes.size)
    }

    private fun box(type: String, vararg children: ByteArray, body: ByteArray = ByteArray(0)): ByteArray {
        val content = ByteArrayOutputStream().apply { write(body); children.forEach(::write) }.toByteArray()
        return ByteBuffer.allocate(8 + content.size).putInt(8 + content.size).put(type.toByteArray(Charsets.US_ASCII)).put(content).array()
    }

    private fun mdcv(minimum: Int): ByteArray = box("mdcv", body = ByteBuffer.allocate(24)
        .putShort(8500.toShort()).putShort(39850.toShort()).putShort(6550.toShort()).putShort(2300.toShort()).putShort(35400.toShort()).putShort(14600.toShort())
        .putShort(15635.toShort()).putShort(16450.toShort()).putInt(1000 * 10_000).putInt(minimum).array())

    private fun moov(entryType: String = "hvc1", minimum: Int? = 50 * 10_000): ByteArray {
        val entryChildren = listOfNotNull(box("hvcC", body = ByteArray(23)), minimum?.let(::mdcv), box("clli", body = ByteArray(4)))
        val entry = box(entryType, *entryChildren.toTypedArray(), body = ByteArray(78))
        val stsd = box("stsd", entry, body = ByteBuffer.allocate(8).putInt(0).putInt(1).array())
        return box("moov", box("mvhd", body = ByteArray(100)),
            box("trak", box("tkhd", body = ByteArray(84)), box("mdia", box("mdhd", body = ByteArray(24)),
                box("minf", box("stbl", stsd, box("stts", body = ByteArray(8)))))))
    }

    private fun file(vararg boxes: ByteArray) = ByteArrayOutputStream().apply { boxes.forEach(::write) }.toByteArray()
    private val ftyp = box("ftyp", body = "isom\u0000\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII))
    private val mdat = box("mdat", body = ByteArray(4096) { it.toByte() })

    private fun storedMinimum(bytes: ByteArray): Int {
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("mdcv") - 4
        return ByteBuffer.wrap(bytes, at + 28, 4).int
    }

    @Test fun patchesTheMinimumWhenMoovFollowsMdat() {
        val bytes = file(ftyp, mdat, moov())
        val before = bytes.copyOf()
        assertEquals(Mp4MdcvPatch.Outcome.PATCHED, Mp4MdcvPatch.patch(MemoryIo(bytes)))
        assertEquals(50, storedMinimum(bytes))
        // Only the four minimum bytes change.
        val minimumAt = String(before, Charsets.ISO_8859_1).indexOf("mdcv") - 4 + 28
        assertTrue(bytes.indices.filter { bytes[it] != before[it] }.all { it in minimumAt until minimumAt + 4 })
    }

    @Test fun patchesWhenMoovPrecedesALargeSizeMdat() {
        val payload = ByteArray(512)
        val large = ByteBuffer.allocate(16 + payload.size).putInt(1).put("mdat".toByteArray()).putLong(16L + payload.size).put(payload).array()
        val bytes = file(ftyp, moov(entryType = "hev1"), large)
        assertEquals(Mp4MdcvPatch.Outcome.PATCHED, Mp4MdcvPatch.patch(MemoryIo(bytes)))
        assertEquals(50, storedMinimum(bytes))
    }

    @Test fun leavesACorrectBoxAlone() {
        val bytes = file(ftyp, mdat, moov(minimum = 50))
        val before = bytes.copyOf()
        assertEquals(Mp4MdcvPatch.Outcome.ALREADY_CORRECT, Mp4MdcvPatch.patch(MemoryIo(bytes)))
        assertArrayEquals(before, bytes)
    }

    @Test fun leavesAnUnexpectedValueAlone() {
        val bytes = file(ftyp, mdat, moov(minimum = 1234))
        val before = bytes.copyOf()
        assertEquals(Mp4MdcvPatch.Outcome.UNEXPECTED_VALUE, Mp4MdcvPatch.patch(MemoryIo(bytes)))
        assertArrayEquals(before, bytes)
    }

    @Test fun reportsAFileWithoutMdcvOrMoov() {
        assertEquals(Mp4MdcvPatch.Outcome.NOT_FOUND, Mp4MdcvPatch.patch(MemoryIo(file(ftyp, mdat, moov(minimum = null)))))
        assertEquals(Mp4MdcvPatch.Outcome.NOT_FOUND, Mp4MdcvPatch.patch(MemoryIo(file(ftyp, mdat))))
        assertEquals(Mp4MdcvPatch.Outcome.NOT_FOUND, Mp4MdcvPatch.patch(MemoryIo(file(ftyp, box("moov", box("trak"))).copyOf(20))))
    }
}
