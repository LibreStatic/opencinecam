/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class Pcm16WavHeaderTest {
    private fun header(dataBytes: Long, rate: Int = 48_000, bits: Int = 16, channels: Int = 2, float: Boolean = false): ByteBuffer =
        createWavHeader(dataBytes, rate, bits, channels, float)

    private fun ByteBuffer.patched(at: Int, block: ByteBuffer.(Int) -> Unit): ByteBuffer {
        val copy = ByteBuffer.allocate(remaining()).order(ByteOrder.LITTLE_ENDIAN)
        copy.put(duplicate()).flip()
        copy.block(at)
        return copy
    }

    private fun rejects(header: ByteBuffer, fileSize: Long) {
        assertThrows(IllegalArgumentException::class.java) { parsePcm16WavHeader(header, fileSize) }
    }

    @Test fun acceptsStereoAsWritten() {
        val parsed = parsePcm16WavHeader(header(192_000), 44 + 192_000)
        assertEquals(48_000, parsed.sampleRateHz)
        assertEquals(2, parsed.channels)
        assertEquals(4, parsed.blockAlign)
        assertEquals(192_000L, parsed.dataBytes)
        assertEquals(48_000L, parsed.frames)
    }

    @Test fun acceptsMonoAsWritten() {
        val parsed = parsePcm16WavHeader(header(88_200, rate = 44_100, channels = 1), 44 + 88_200)
        assertEquals(44_100, parsed.sampleRateHz)
        assertEquals(1, parsed.channels)
        assertEquals(44_100L, parsed.frames)
    }

    @Test fun acceptsTrailingBytesAfterTheData() {
        assertEquals(1000L, parsePcm16WavHeader(header(4000), 44 + 4000 + 17).frames)
    }

    @Test fun readsFromTheBufferPosition() {
        val shifted = ByteBuffer.allocate(50)
        shifted.position(6)
        shifted.put(header(400))
        shifted.position(6)
        assertEquals(100L, parsePcm16WavHeader(shifted, 444).frames)
        assertEquals(6, shifted.position())
    }

    @Test fun rejectsFloatAndOtherDepths() {
        rejects(header(4000, bits = 32, float = true), 44 + 4000)
        rejects(header(6000, bits = 24), 44 + 6000)
        rejects(header(8000, bits = 32), 44 + 8000)
    }

    @Test fun rejectsDataTheFileDoesNotHold() {
        rejects(header(4000), 44 + 3996)
        rejects(header(4000), 40)
    }

    @Test fun rejectsEmptyData() {
        rejects(header(0), 44)
    }

    @Test fun rejectsDataEndingInsideAFrame() {
        rejects(header(4002), 44 + 4002)
        rejects(header(4001, channels = 1), 44 + 4001)
    }

    @Test fun rejectsInconsistentBlockAlignment() {
        rejects(header(4000).patched(32) { putShort(it, 3) }, 44 + 4000)
        rejects(header(4000).patched(28) { putInt(it, 48_000 * 3) }, 44 + 4000)
    }

    @Test fun rejectsWrongMagic() {
        rejects(header(4000).patched(0) { put(it, 'X'.code.toByte()) }, 44 + 4000)
        rejects(header(4000).patched(8) { put(it, 'X'.code.toByte()) }, 44 + 4000)
        rejects(header(4000).patched(12) { put(it, 'X'.code.toByte()) }, 44 + 4000)
        rejects(header(4000).patched(36) { put(it, 'X'.code.toByte()) }, 44 + 4000)
    }

    @Test fun rejectsAStaleRiffSize() {
        rejects(header(4000).patched(4) { putInt(it, 36) }, 44 + 4000)
    }

    @Test fun rejectsBadChannelsAndRate() {
        rejects(header(4000).patched(22) { putShort(it, 3) }, 44 + 4000)
        rejects(header(4000).patched(24) { putInt(it, 0) }, 44 + 4000)
    }

    @Test fun rejectsATruncatedHeader() {
        val short = ByteBuffer.allocate(40).put(header(4000).limit(40) as ByteBuffer).flip() as ByteBuffer
        rejects(short, 44 + 4000)
    }
}
