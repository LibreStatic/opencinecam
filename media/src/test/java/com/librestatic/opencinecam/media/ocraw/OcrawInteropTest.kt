// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import org.junit.Assert.assertTrue
import org.junit.Test

class OcrawInteropTest {
    @Test
    fun deterministicFaultMatrixPasses() {
        val chunks = listOf(
            OcrawChunk(OcrawChunkId.METADATA.wireId, 1, 0, OcrawTimeBase(1, 1), byteArrayOf()),
            OcrawChunk(OcrawChunkId.AUDIO_PCM16.wireId, 2, 1, OcrawTimeBase(1, 48_000), byteArrayOf(3, 4)),
        )
        val results = OcrawInteropValidator.run(OcrawHeader(ByteArray(16)), chunks)
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.passed })
    }
}
