/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.ProjectMp4File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ProxyVideoPresentationTest {
    private fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it) } }.array()
    private fun box(name: String, bytes: ByteArray) = ints(bytes.size + 8) + name.toByteArray() + bytes
    private fun fixture(offset: Int = 250, scale: Int = 1000, wide: Boolean = false,
        rate: Int = 0x10000, declared: Int = 3400 + offset, repeats: Boolean = false, edits: Boolean = true): ByteArray {
        val mvhd = box("mvhd", ints(0, 0, 0, scale, declared))
        val tkhd = box("tkhd", if (wide) ByteBuffer.allocate(36).putInt(0x01000000).putLong(0).putLong(0)
            .putInt(1).putInt(0).putLong(declared.toLong()).array() else ints(0, 0, 0, 1, 0, declared))
        fun entry(span: Int, media: Int) = if (wide) ByteBuffer.allocate(20).putLong(span.toLong()).putLong(media.toLong()).putInt(rate).array()
            else ints(span, media, rate)
        val entries = (if (offset > 0) entry(offset, if (repeats) 0 else -1) else byteArrayOf()) + entry(3400, 72909)
        val edts = if (edits) box("edts", box("elst", ints(if (wide) 0x01000000 else 0, if (offset > 0) 2 else 1) + entries)) else byteArrayOf()
        val mdia = box("mdia", box("hdlr", ints(0, 0) + "vide".toByteArray()))
        return box("ftyp", byteArrayOf()) + box("moov", mvhd + box("trak", tkhd + edts + mdia)) + box("mdat", byteArrayOf(1, 2, 3))
    }
    private fun inspect(bytes: ByteArray): Long = inspectProxyVideoEndUs(object : ProjectMp4File {
        override val size = bytes.size.toLong()
        override fun read(offset: Long, length: Int) = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        override fun write(offset: Long, bytes: ByteArray): Unit = error("Inspector must not write")
    })
    private fun reject(bytes: ByteArray) {
        try { inspect(bytes); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun leadingEditIsPartOfEndpointNotMediaSpan() { assertEquals(3_650_000L, inspect(fixture())) }
    @Test fun zeroOffsetAndNoEditHaveExplicitTrackEndpoint() {
        assertEquals(3_400_000L, inspect(fixture(0)))
        assertEquals(3_400_000L, inspect(fixture(0, edits = false)))
    }
    @Test fun versionOneEditsAndTrackAreReadWithoutTruncation() { assertEquals(3_650_000L, inspect(fixture(wide = true))) }
    @Test fun endpointUsesCeilingRationalMovieClock() { assertEquals(3_646_354L, inspect(fixture(scale = 1001))) }
    @Test fun rejectsDisagreeingTrackAndEditDurations() { reject(fixture(declared = 3600)) }
    @Test fun rejectsRepeatedMediaEditsAndRateChanges() { reject(fixture(repeats = true)); reject(fixture(rate = 0x20000)) }
    @Test fun rejectsZeroTimescaleAndTruncatedBoxes() { reject(fixture(scale = 0)); reject(fixture().dropLast(1).toByteArray()) }
    @Test fun rejectsFragmentedMovie() { reject(fixture() + box("moof", byteArrayOf())) }
    private fun writable(backing: ByteArray) = object : ProjectMp4File {
        override val size = backing.size.toLong()
        override fun read(offset: Long, length: Int) = backing.copyOfRange(offset.toInt(), offset.toInt() + length)
        override fun write(offset: Long, bytes: ByteArray) { bytes.copyInto(backing, offset.toInt()) }
    }

    @Test fun repairsOnlyMissingLeadingGapInOwnedTrackHeader() {
        for (wide in listOf(false, true)) {
            val bytes = fixture(declared = 3400, wide = wide); val before = bytes.copyOf()
            finalizeProxyVideoTrackHeader(writable(bytes), 3_650_000)
            assertEquals(3_650_000L, inspect(bytes))
            assertTrue(bytes.indices.count { bytes[it] != before[it] } in 1..8)
            val repaired = bytes.copyOf(); finalizeProxyVideoTrackHeader(writable(bytes), 3_650_000)
            assertArrayEquals(repaired, bytes)
        }
    }
    @Test fun refusesRepairForWrongSourceEndpointOrUnrelatedMismatchWithoutWrites() {
        for ((declared, endpoint) in listOf(3400 to 3_700_000L, 3600 to 3_650_000L)) {
            val bytes = fixture(declared = declared); val before = bytes.copyOf()
            try { finalizeProxyVideoTrackHeader(writable(bytes), endpoint); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
            assertArrayEquals(before, bytes)
        }
    }

}
