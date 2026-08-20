// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCineRawTest {
    private val timeBase = OcrawTimeBase(1, 48_000)
    private val uuid = ByteArray(16) { index -> index.toByte() }

    @Test
    fun headerChunkRoundTripAndCrc() {
        val header = OcrawHeader(uuid, flags = 3)
        val chunk = OcrawChunk(
            wireId = OcrawChunkId.AUDIO_PCM16.wireId,
            sequence = 4,
            timestampTicks = 96_000,
            timeBase = timeBase,
            payload = OcrawCodec.pcm16Le(shortArrayOf(-2, 0, 2)),
        )
        val encoded = OcrawCodec.encodeContainer(header, listOf(chunk))
        assertEquals(header, OcrawCodec.decodeHeader(encoded))
        val decoded = OcrawCodec.decodeChunk(encoded, OcrawCodec.HEADER_BYTES)
        assertEquals(chunk.wireId, decoded.wireId)
        assertEquals(chunk.sequence, decoded.sequence)
        assertEquals(chunk.timeBase, decoded.timeBase)
        assertArrayEquals(chunk.payload, decoded.payload)
        val scan = OcrawCodec.scanContainer(encoded)
        assertEquals(1, scan.chunks.size)
        assertFalse(scan.truncated)
        assertFalse(scan.staleEvidence)
    }

    @Test
    fun metadataUsesCanonicalCborAndUnknownChunkIsForwardCompatible() {
        val metadata = mapOf("width" to "1920", "mode" to "raw")
        val encoded = OcrawMetadataCbor.encode(metadata)
        assertEquals(metadata, OcrawMetadataCbor.decode(encoded))
        val unknown = OcrawChunk(0x7fff, 1, 0, timeBase, byteArrayOf(1, 2, 3))
        assertEquals(null, unknown.knownId())
        val scan = OcrawCodec.scanContainer(
            OcrawCodec.encodeContainer(OcrawHeader(uuid), listOf(unknown)),
        )
        assertEquals(0x7fff, scan.chunks.single().wireId)
        assertFalse(scan.staleEvidence)
    }

    @Test
    fun recoveryStopsAtTruncatedOrCorruptChunk() {
        val first = OcrawChunk(OcrawChunkId.RAW_FRAME.wireId, 1, 1, timeBase, byteArrayOf(9))
        val second = OcrawChunk(OcrawChunkId.RAW_FRAME.wireId, 2, 2, timeBase, byteArrayOf(8))
        val complete = OcrawCodec.encodeContainer(OcrawHeader(uuid), listOf(first, second))
        val truncated = complete.copyOf(complete.size - 2)
        val result = OcrawCodec.scanContainer(truncated)
        assertEquals(1, result.chunks.size)
        assertTrue(result.truncated)
        assertTrue(result.staleEvidence)

        val corrupt = complete.copyOf()
        corrupt[OcrawCodec.HEADER_BYTES + OcrawCodec.CHUNK_HEADER_BYTES] = 0
        val corruptResult = OcrawCodec.scanContainer(corrupt)
        assertEquals(0, corruptResult.chunks.size)
        assertTrue(corruptResult.staleEvidence)
    }

    @Test
    fun truncatedHeaderIsAlwaysStaleEvidence() {
        val truncated = OcrawCodec.encodeHeader(OcrawHeader(uuid)).copyOf(OcrawCodec.HEADER_BYTES - 1)
        val result = OcrawCodec.scanContainer(truncated)
        assertEquals(null, result.header)
        assertTrue(result.truncated)
        assertTrue(result.staleEvidence)
    }

    @Test
    fun appendJournalExposesDuplicateCancellationAndCleanup() {
        val writer = OcrawAppendWriter(maxChunks = 1)
        val chunk = OcrawChunk(OcrawChunkId.METADATA.wireId, 1, 0, timeBase, byteArrayOf(1))
        assertEquals(OcrawAppendStatus.APPENDED, writer.append(10, chunk))
        assertEquals(OcrawAppendStatus.DUPLICATE_COMMAND, writer.append(10, chunk))
        assertEquals(OcrawAppendStatus.CAPACITY_EXCEEDED, writer.append(11, chunk))
        val cancelled = OcrawAppendWriter()
        assertEquals(OcrawAppendStatus.CANCELLED, cancelled.cancel(12))
        assertEquals(OcrawAppendStatus.CANCELLED, cancelled.append(12, chunk))
        cancelled.close()
        assertEquals(OcrawAppendStatus.CLOSED, cancelled.append(13, chunk))
        assertEquals(1, writer.snapshot().size)
    }
}
