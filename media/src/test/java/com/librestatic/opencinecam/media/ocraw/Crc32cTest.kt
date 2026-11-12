// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class Crc32cTest {
    private val check = "123456789".toByteArray(Charsets.US_ASCII)

    @Test
    fun knownVectorsOnBothPaths() {
        for (preferPlatform in listOf(true, false)) {
            val accumulator = Crc32c.newAccumulator(preferPlatform)
            accumulator.update(check)
            assertEquals(0xE3069283.toInt(), accumulator.value())
            accumulator.reset()
            assertEquals(0, accumulator.value())
            accumulator.update(ByteArray(32))
            assertEquals(0x8A9136AA.toInt(), accumulator.value())
            accumulator.reset()
            accumulator.update(ByteArray(32) { 0xff.toByte() })
            assertEquals(0x62A8AB43, accumulator.value())
        }
        assertEquals(0xE3069283.toInt(), Crc32c.compute(check))
        assertFalse(Crc32c.newAccumulator(preferPlatform = false).usesPlatform)
    }

    @Test
    fun hostJvmUsesThePlatformClass() {
        // The host test JVM is Java 17, which has java.util.zip.CRC32C.
        assertTrue(Crc32c.platformAvailable)
        assertTrue(Crc32c.newAccumulator().usesPlatform)
    }

    @Test
    fun pathsAgreeOnRandomRangesOfEveryBufferKind() {
        val random = Random(71)
        val data = random.nextBytes(8192)
        val direct = ByteBuffer.allocateDirect(data.size).put(data).also { it.clear() }
        val heapBig = ByteBuffer.wrap(data.copyOf()).order(ByteOrder.BIG_ENDIAN)
        val heapLittle = ByteBuffer.wrap(data.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        repeat(400) {
            val offset = random.nextInt(0, 64)
            val length = random.nextInt(0, data.size - offset)
            val reference = bitwise(data, offset, length)
            val table = Crc32c.newAccumulator(preferPlatform = false)
            val platform = Crc32c.newAccumulator(preferPlatform = true)
            table.update(data, offset, length)
            platform.update(data, offset, length)
            assertEquals(reference, table.value())
            assertEquals(reference, platform.value())
            for (buffer in listOf(direct, heapBig, heapLittle)) {
                for (preferPlatform in listOf(true, false)) {
                    buffer.limit(offset + length).position(offset)
                    val accumulator = Crc32c.newAccumulator(preferPlatform)
                    accumulator.update(buffer)
                    assertEquals(reference, accumulator.value())
                    assertEquals("position untouched", offset, buffer.position())
                    assertEquals("limit untouched", offset + length, buffer.limit())
                    buffer.clear()
                    val absolute = Crc32c.newAccumulator(preferPlatform)
                    absolute.update(buffer, offset, length)
                    assertEquals(reference, absolute.value())
                    assertEquals(0, buffer.position())
                    assertEquals(buffer.capacity(), buffer.limit())
                }
            }
        }
    }

    @Test
    fun incrementalUpdatesMatchOneShot() {
        val random = Random(9)
        val data = random.nextBytes(5000)
        val expected = bitwise(data, 0, data.size)
        for (preferPlatform in listOf(true, false)) {
            val accumulator = Crc32c.newAccumulator(preferPlatform)
            var offset = 0
            while (offset < data.size) {
                val length = minOf(random.nextInt(1, 37), data.size - offset)
                if (offset % 2 == 0) {
                    accumulator.update(data, offset, length)
                } else {
                    accumulator.update(ByteBuffer.wrap(data, offset, length))
                }
                offset += length
            }
            assertEquals(expected, accumulator.value())
        }
    }

    @Test
    fun throughputSmoke() {
        val bytes = 32 * 1024 * 1024
        val buffer = ByteBuffer.allocateDirect(bytes)
        val random = Random(1)
        while (buffer.hasRemaining()) buffer.put(random.nextInt().toByte())
        buffer.clear()
        for (preferPlatform in listOf(true, false)) {
            val accumulator = Crc32c.newAccumulator(preferPlatform)
            repeat(2) { accumulator.update(buffer) } // warm up
            val rounds = 6
            val start = System.nanoTime()
            repeat(rounds) {
                accumulator.reset()
                accumulator.update(buffer)
            }
            val seconds = (System.nanoTime() - start) / 1e9
            val path = if (preferPlatform) "platform" else "slicing-by-8"
            println("CRC32C $path: %.0f MB/s".format(rounds * bytes / 1e6 / seconds))
        }
    }

    /** Runs only where libocc_rawcodec loads (not on the host JVM, where it is skipped). */
    @Test
    fun nativePathMatchesWhenLoaded() {
        assumeTrue("libocc_rawcodec not loadable on this JVM", Crc32c.nativeAvailable)
        assertTrue(Crc32c.newAccumulator().usesNative)
        val random = Random(33)
        val data = random.nextBytes(64 * 1024)
        val direct = ByteBuffer.allocateDirect(data.size).put(data).also { it.clear() }
        val heap = ByteBuffer.wrap(data.copyOf())
        repeat(200) {
            val offset = random.nextInt(0, 64)
            val length = random.nextInt(0, data.size - offset)
            val reference = bitwise(data, offset, length)
            val native = requireNotNull(Crc32c.newNativeAccumulator())
            native.update(data, offset, length)
            assertEquals(reference, native.value())
            for (buffer in listOf(direct, heap)) {
                buffer.limit(offset + length).position(offset)
                val accumulator = requireNotNull(Crc32c.newNativeAccumulator())
                // Split so both the native and the short-range table route run.
                val half = length / 2
                accumulator.update(buffer, offset, half)
                accumulator.update(buffer, offset + half, length - half)
                assertEquals(reference, accumulator.value())
                buffer.clear()
            }
        }
    }

    private fun bitwise(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = -1
        for (index in offset until offset + length) {
            crc = crc xor (bytes[index].toInt() and 0xff)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x82f63b78.toInt() else crc ushr 1 }
        }
        return crc.inv()
    }
}
