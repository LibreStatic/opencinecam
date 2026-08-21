/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavHeaderTest {
    @Test
    fun writesPacked24BitStereoHeaderWithDerivedByteRate() {
        val header = createWavHeader(288_000, 48_000, 24, 2, false).order(ByteOrder.LITTLE_ENDIAN)
        val bytes = ByteArray(4)
        header.get(bytes)
        assertEquals("RIFF", String(bytes, Charsets.US_ASCII))
        assertEquals(288_036, header.int)
        header.position(20)
        assertEquals(1, header.short.toInt())
        assertEquals(2, header.short.toInt())
        assertEquals(48_000, header.int)
        assertEquals(288_000, header.int)
        assertEquals(6, header.short.toInt())
        assertEquals(24, header.short.toInt())
        header.position(40)
        assertEquals(288_000, header.int)
    }

    @Test
    fun floatWavUsesIeeeFormatCode() {
        val header = createWavHeader(0, 96_000, 32, 1, true).order(ByteOrder.LITTLE_ENDIAN)
        header.position(20)
        assertEquals(3, header.short.toInt())
    }
}
