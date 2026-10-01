/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HevcVuiTransferTest {
    /** SPS NAL units with the transfer and its RBSP bit offset as FFmpeg's trace_headers reports them. */
    private data class Fixture(val name: String, val sps: String, val transfer: Int?, val bitOffset: Int?)

    private val fixtures = listOf(
        // c2.qti.hevc.encoder OCLog2 recordings from two Snapdragon generations, both tagged PQ.
        Fixture("qualcomm-level-5.1", "420101222000000300b00000030000030099a003c0801107cad96bb9324ba9b848804817685094", 16, 237),
        Fixture("qualcomm-level-5", "420101222000000300b00000030000030096a003c0801107cad96bb9324ba9b848804817685094", 16, 237),
        Fixture("x265", "42010102200000030090000003000003003ca00a080f1365959a4932bc05b848804820000003002000000303c1", 16, 229),
        Fixture("x265-scaling-list-enabled", "42010102200000030090000003000003003ca00a080f1365959a49395e02d424482410000003001000000301e080", 18, 230),
        Fixture("x265-temporal-layers", "42010402200000030090000003000003003c0000a00a080f13659594aca565924caf016a02020208000003000800000300f040", 1, 271),
        Fixture("x265-conformance-window-sar-overscan", "42010102200000030090000003000003003ca00a480f9ddb65959a4932bc3a9424202410000003001000000301e080", 8, 238),
        Fixture("x265-no-colour-description", "42010102200000030090000003000003003ca00a080f1365959a4932bc05a020000003002000000303c1", null, null),
        // Sub-layer PTL, scaling list data, PCM, inter-predicted RPS, long-term references, SAR 255.
        Fixture("synthetic-full-syntax", SYNTHETIC_SPS, 14, 1374),
    )

    @Test
    fun locatesTheTransferWhereFfmpegDoes() {
        fixtures.forEach { fixture ->
            val rbsp = HevcVuiTransfer.unescape(hex(fixture.sps))
            assertEquals(fixture.name, fixture.bitOffset, HevcVuiTransfer.transferBitOffset(rbsp))
            assertEquals(fixture.name, fixture.transfer, HevcVuiTransfer.transferOf(annexB(fixture.sps)))
        }
    }

    @Test
    fun rewriteChangesOnlyTheTransferBits() {
        fixtures.filter { it.transfer != null }.forEach { fixture ->
            val result = HevcVuiTransfer.rewrite(annexB(fixture.sps), HevcVuiTransfer.UNSPECIFIED)
            assertEquals(fixture.name, fixture.transfer, result.previousTransfer)
            assertEquals(fixture.name, HevcVuiTransfer.UNSPECIFIED, HevcVuiTransfer.transferOf(result.bytes))
            val before = HevcVuiTransfer.unescape(hex(fixture.sps))
            val after = HevcVuiTransfer.unescape(result.bytes.copyOfRange(4, result.bytes.size))
            assertEquals(fixture.name, before.size, after.size)
            val offset = requireNotNull(fixture.bitOffset)
            for (bit in 0 until before.size * 8) {
                if (bit in offset until offset + 8) continue
                assertEquals("${fixture.name} bit $bit", bitAt(before, bit), bitAt(after, bit))
            }
        }
    }

    @Test
    fun rewriteKeepsOtherNalUnitsAndStartCodes() {
        val vps = "40010c01ffff02200000030090000003000003003c959809"
        val pps = "4401c172b46240"
        val slice = "2601af0000030100"
        val stream = hex("00000001$vps" + "00000001${fixtures[0].sps}" + "000001$pps" + "000001$slice")
        val rewritten = HevcVuiTransfer.rewrite(stream, HevcVuiTransfer.UNSPECIFIED).bytes
        val expectedSps = HevcVuiTransfer.rewrite(annexB(fixtures[0].sps), HevcVuiTransfer.UNSPECIFIED).bytes.copyOfRange(4, 4 + hex(fixtures[0].sps).size)
        assertArrayEquals(hex("00000001$vps" + "00000001") + expectedSps + hex("000001$pps" + "000001$slice"), rewritten)
    }

    @Test
    fun streamsWithoutColourDescriptionAreReturnedUnchanged() {
        val stream = annexB(fixtures.single { it.transfer == null }.sps)
        val result = HevcVuiTransfer.rewrite(stream, HevcVuiTransfer.UNSPECIFIED)
        assertSame(stream, result.bytes)
        assertNull(result.previousTransfer)
    }

    @Test
    fun rewriteIsIdempotent() {
        val once = HevcVuiTransfer.rewrite(annexB(fixtures[0].sps), HevcVuiTransfer.UNSPECIFIED)
        val twice = HevcVuiTransfer.rewrite(once.bytes, HevcVuiTransfer.UNSPECIFIED)
        assertArrayEquals(once.bytes, twice.bytes)
        assertEquals(HevcVuiTransfer.UNSPECIFIED, twice.previousTransfer)
    }

    @Test
    fun emulationPreventionIsAddedWhereThePatchNeedsIt() {
        assertArrayEquals(hex("00000302"), HevcVuiTransfer.escape(hex("000002")))
        assertArrayEquals(hex("000002"), HevcVuiTransfer.unescape(hex("00000302")))
        assertArrayEquals(hex("00000300000300"), HevcVuiTransfer.escape(hex("0000000000")))
        assertArrayEquals(hex("0000000000"), HevcVuiTransfer.unescape(hex("00000300000300")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedSpsIsRejected() {
        HevcVuiTransfer.rewrite(annexB(fixtures[0].sps.take(40)), HevcVuiTransfer.UNSPECIFIED)
    }

    private fun annexB(nal: String) = hex("00000001$nal")

    private fun bitAt(data: ByteArray, bit: Int) = (data[bit ushr 3].toInt() shr (7 - (bit and 7))) and 1

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val SYNTHETIC_SPS = "420103022000000300900000030000030078c000022000000300900000030000030078a003c08010e7" +
            "cad965cbc9225b5ad6b5a6b5ad6b4d6b5ad69ad6b5ad6b5ad6b5ad6b5ad6b5ad35ad6b5ad6b5ad6b5ad6b5ad6b5a6b5ad6b5ad6b5a" +
            "d6b5ad6b5ad6b4c20b5ad6b5ad6b5ad6b5ad6b5ad6b4c20b5ad6b5ad6b5ad6b5ad6b5ad6b4c20b5ad6b5ad6b5ad6b5ad6b5ad6b4c2" +
            "0b5ad6b5ad6b5ad6b5ad6b5ad6b5ddea46b5ae9a9b00887ffc0010000edc24382402"
    }
}
