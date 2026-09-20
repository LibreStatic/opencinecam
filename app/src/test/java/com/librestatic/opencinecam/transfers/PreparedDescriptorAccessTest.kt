/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class PreparedDescriptorAccessTest {
    private fun parse(value: String) = parsePreparedDescriptorFlags(value.toByteArray(Charsets.US_ASCII))

    @Test fun kernelFieldsAndLeadingOctalZeroAreAccepted() {
        assertEquals(0x8000L, parse("pos:\t0\nflags:\t0100000\nmnt_id:\t24\nino:\t12345\n"))
        assertEquals(0L, parse("flags: 0"))
    }

    @Test fun accessModeBitsRemainExactForNativeReadonlyValidation() {
        assertEquals(0L, parse("flags:\t02100000\n") and 3L)
        assertEquals(1L, parse("flags:\t02100001\n") and 3L)
        assertEquals(2L, parse("flags:\t02100002\n") and 3L)
        assertEquals(3L, parse("flags:\t02100003\n") and 3L)
    }

    @Test fun missingAndEmptyFieldsAreRejected() {
        for (value in listOf("", "pos:\t0\n", "flags:\n", "flags: \n")) {
            assertThrows(value, Exception::class.java) { parse(value) }
        }
    }

    @Test fun duplicateFieldsIncludingZeroAreRejected() {
        for (value in listOf("flags:\t0\nflags:\t0\n", "flags: 1\nflags: 2", "flags: 0\n flags: 0")) {
            assertThrows(value, Exception::class.java) { parse(value) }
        }
    }

    @Test fun malformedOctalAndFieldSyntaxAreRejectedWithoutCoercion() {
        for (value in listOf("8", "9", "0x10", "+1", "-1", "1.0", "1e2", "1 2", "1\t", "1 ")) {
            assertThrows(value, Exception::class.java) { parse("flags:\t$value\n") }
        }
        for (value in listOf("flags:0", " flags: 0", "flags = 0", "flags : 0", "flagsExtra: 0")) {
            assertThrows(value, Exception::class.java) { parse(value) }
        }
    }

    @Test fun fullSigned64BitRangeIsRetainedAndOverflowRejected() {
        assertEquals(Long.MAX_VALUE, parse("flags: ${Long.MAX_VALUE.toString(8)}"))
        for (value in listOf("1000000000000000000000", "777777777777777777777777777777777")) {
            assertThrows(Exception::class.java) { parse("flags: $value") }
        }
    }

    @Test fun nonAsciiAndControlBytesAreRejectedAnywhere() {
        for (invalid in listOf(0, 1, 13, 31, 127, 128, 255)) {
            val bytes = "flags: 0\nother: ".toByteArray(Charsets.US_ASCII) + byteArrayOf(invalid.toByte())
            assertThrows("byte=$invalid", Exception::class.java) { parsePreparedDescriptorFlags(bytes) }
        }
    }

    @Test fun exactByteLimitAcceptedAndOversizeRejected() {
        val prefix = "flags: 0\n"
        val bounded = prefix + "x".repeat(4096 - prefix.length)
        assertEquals(0L, parse(bounded))
        assertThrows(Exception::class.java) { parse(bounded + "x") }
    }

    @Test fun boundedReaderConsumesOnlyOneOverflowSentinel() {
        val input = ByteArrayInputStream(ByteArray(10_000) { 32 })
        assertThrows(Exception::class.java) { readPreparedDescriptorFlags(input) }
        assertEquals(10_000 - 4097, input.available())
    }

    @Test fun shortReadsAreAccumulatedAndBorrowedStreamIsNotClosed() {
        var closed = false
        val input = object : ByteArrayInputStream("pos: 0\nflags:\t0100002\n".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 2))
            override fun close() { closed = true; super.close() }
        }
        assertEquals(0x8002L, readPreparedDescriptorFlags(input))
        assertFalse(closed)
        input.close()
        assertTrue(closed)
    }

    @Test fun readFailureIsPropagatedWithoutFabricatedFlags() {
        val failure = IOException("fdinfo denied")
        val input = object : InputStream() { override fun read(): Int = throw failure }
        assertSame(failure, assertThrows(IOException::class.java) { readPreparedDescriptorFlags(input) })
    }

    @Test fun stalledReaderFailsWithoutLooping() {
        val input = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertThrows(IllegalStateException::class.java) { readPreparedDescriptorFlags(input) }
    }
}
