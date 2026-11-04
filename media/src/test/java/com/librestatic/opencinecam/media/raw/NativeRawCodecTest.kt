// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.raw

import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Test

class NativeRawCodecTest {
    @Test
    fun codecIdsAreTheOcrawEncodings() {
        assertEquals(OcrawFrameHeader.ENCODING_LJ92, NativeRawCodec.Codec.LJ92.encoding)
        assertEquals(OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK, NativeRawCodec.Codec.BLOCK_PACK.encoding)
        assertEquals(1, NativeRawCodec.Codec.LJ92.encoding)
        assertEquals(2, NativeRawCodec.Codec.BLOCK_PACK.encoding)
        assertEquals(OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED, NativeRawCodec.Codec.BLOCK_PACK_QUANTISED.encoding)
        assertEquals(3, NativeRawCodec.Codec.BLOCK_PACK_QUANTISED.encoding)
    }

    /** The host JVM has no libocc_rawcodec: loading fails quietly and encoders refuse. */
    @Test
    fun unavailableLibraryIsReportedNotThrown() {
        assumeFalse("libocc_rawcodec loaded on this JVM", NativeRawCodec.available)
        assertEquals(false, NativeRawCodec.neonAvailable)
        assertThrows(IllegalStateException::class.java) {
            NativeRawCodec.newEncoder(NativeRawCodec.Codec.BLOCK_PACK, threads = 2)
        }
    }
}
