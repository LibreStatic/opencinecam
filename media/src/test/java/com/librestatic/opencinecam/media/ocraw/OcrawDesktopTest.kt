// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import com.librestatic.opencinecam.camera.RawFormat
import com.librestatic.opencinecam.camera.RawFrame
import com.librestatic.opencinecam.camera.RawFrameMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrawDesktopTest {
    private val header = OcrawHeader(ByteArray(16) { (it + 1).toByte() })
    private val timeBase = OcrawTimeBase(1, 48_000)

    @Test
    fun readerRebuildsIndexAndExtractsPcm() {
        val audio = OcrawChunk(
            OcrawChunkId.AUDIO_PCM16.wireId,
            1,
            0,
            timeBase,
            OcrawCodec.pcm16Le(shortArrayOf(-3, 4)),
        )
        val encoded = OcrawCodec.encodeContainer(header, listOf(audio))
        val reader = OcrawDesktopReader(encoded)
        assertTrue(reader.verify().valid)
        assertEquals(1, reader.rebuildIndex().size)
        assertArrayEquals(shortArrayOf(-3, 4), reader.extractPcm16())
    }

    @Test
    fun recoveryCliPreservesLastValidOffset() {
        val chunk = OcrawChunk(OcrawChunkId.METADATA.wireId, 1, 0, timeBase, byteArrayOf(7))
        val encoded = OcrawCodec.encodeContainer(header, listOf(chunk)).copyOfRange(0, 40)
        val report = OcrawRecoveryCli.verify(encoded)
        assertTrue(report.staleEvidence)
        assertEquals(0, report.recoveredChunkCount)
    }

    @Test
    fun dngExporterPreservesRawMetadataAndPixels() {
        val frame = RawFrame(
            frameId = 7,
            metadata = RawFrameMetadata(
                width = 2,
                height = 1,
                rowStride = 4,
                pixelStride = 2,
                cfaPattern = "RGGB",
                blackLevel = 64,
                whiteLevel = 1023,
                timestampNs = 99,
                format = RawFormat.RAW_SENSOR,
            ),
            payload = byteArrayOf(1, 0, 2, 0),
        )
        val fixture = OcrawDesktopReader(ByteArray(0)).exportDng(frame)
        assertEquals("RGGB", fixture.tags["CFA"])
        assertEquals("2", fixture.tags["Width"])
        assertEquals("1", fixture.tags["Height"])
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), fixture.payload)
    }
}
