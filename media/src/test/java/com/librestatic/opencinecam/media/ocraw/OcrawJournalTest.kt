// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OcrawJournalTest {
    private val header = OcrawHeader(ByteArray(16) { it.toByte() })
    private val timeBase = OcrawTimeBase(1, 48_000)

    @Test
    fun finishAddsPeriodicFinalIndexAndEnd() {
        val journal = OcrawAppendJournal(maxChunks = 8, journalInterval = 2)
        repeat(3) { index ->
            assertEquals(
                OcrawAppendStatus.APPENDED,
                journal.append(index + 1L, OcrawChunk(OcrawChunkId.RAW_FRAME.wireId, index + 1L, index.toLong(), timeBase, byteArrayOf(index.toByte()))),
            )
        }
        val result = journal.finish(header)
        assertEquals(OcrawWriteOutcome.FINALIZED, result.outcome)
        assertEquals(1, result.periodicIndexCount)
        assertTrue(result.finalIndexAppended)
        assertTrue(result.endChunkAppended)
        val scan = OcrawCodec.scanContainer(result.bytes)
        assertFalse(scan.staleEvidence)
        assertEquals(OcrawChunkId.END, scan.chunks.last().knownId())
        val index = scan.chunks.first { it.knownId() == OcrawChunkId.FINAL_INDEX }
        assertEquals(3, OcrawIndexCodec.decode(index.payload).size)
    }

    @Test
    fun interruptLeavesRecoverablePrefixWithoutEnd() {
        val journal = OcrawAppendJournal(journalInterval = 2)
        assertEquals(OcrawAppendStatus.APPENDED, journal.append(1, OcrawChunk(OcrawChunkId.METADATA.wireId, 1, 0, timeBase, byteArrayOf(1))))
        val result = journal.interrupt(header)
        assertEquals(OcrawWriteOutcome.INTERRUPTED, result.outcome)
        val scan = OcrawCodec.scanContainer(result.bytes)
        assertEquals(1, scan.chunks.size)
        assertFalse(scan.chunks.any { it.knownId() == OcrawChunkId.END })
    }

    @Test
    fun indexDecoderRejectsNegativeWireValues() {
        val payload = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1)
            .putLong(-1)
            .putLong(0)
            .putLong(0)
            .array()
        assertThrows(IllegalArgumentException::class.java) { OcrawIndexCodec.decode(payload) }
    }
}
