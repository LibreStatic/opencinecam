// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.raw

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawQuantisationTest {
    private val day = RawQuantisation.NoiseModel(0.3747, 0.04022) // Razr, ISO 143
    private val dark = RawQuantisation.NoiseModel(0.6, 6.8974) // Razr, ISO 25600

    @Test
    fun codesAreMonotonicAndCoverEveryDn() {
        for (model in listOf(day, dark)) {
            val t = RawQuantisation.build(model, BLACK)
            assertEquals(0, t.forward[0])
            for (i in 1 until RawQuantisation.LUT_SIZE) {
                val step = t.forward[i] - t.forward[i - 1]
                assertTrue("code steps by 0 or 1 at $i", step == 0 || step == 1)
            }
            assertEquals(t.forward.last() + 1, t.count)
            // Every code decodes inside its own DNs, and the DNs it covers are contiguous.
            for (i in 0 until RawQuantisation.LUT_SIZE) assertEquals(t.forward[i], t.forward[t.reconstruct(i)])
        }
    }

    @Test
    fun tinyNoiseIsTheIdentity() {
        val t = RawQuantisation.build(RawQuantisation.NoiseModel(1e-6, 0.0), BLACK)
        assertEquals(1024, t.count)
        for (i in 0 until 1024) {
            assertEquals(i, t.forward[i])
            assertEquals(i, t.inverse[i])
        }
        // A zero sigma steps by one as well.
        val zero = RawQuantisation.build(RawQuantisation.NoiseModel(0.0, 0.0), BLACK)
        assertArrayEquals(t.forward, zero.forward)
    }

    /** The benchmark's numbers (occbench, k = 0.5): ISO 25600 max 20 DN = 0.298 sigma, ISO 143 0.47 sigma. */
    @Test
    fun razrModelsMatchTheBenchmark() {
        fun check(model: RawQuantisation.NoiseModel, count: Int, maxDn: Int, maxSigma: Double) {
            val t = RawQuantisation.build(model, BLACK)
            assertEquals(count, t.count)
            assertEquals(500, t.kMilli)
            var worstDn = 0
            var worstSigma = 0.0
            for (i in 0 until 1024) {
                val error = abs(t.reconstruct(i) - i)
                worstDn = maxOf(worstDn, error)
                worstSigma = maxOf(worstSigma, error / model.sigma(i, BLACK))
            }
            assertEquals(maxDn, worstDn)
            assertEquals(maxSigma, worstSigma, 1e-3)
            assertTrue("error within k / 2 sigma plus rounding", worstSigma <= 0.5)
        }
        check(day, count = 577, maxDn = 2, maxSigma = 0.4664)
        check(dark, count = 112, maxDn = 20, maxSigma = 0.2982)
        // A coarser step merges more DNs.
        assertTrue(RawQuantisation.build(dark, BLACK, k = 1.0).count < 112)
    }

    @Test
    fun belowBlackOnlyReadNoiseCounts() {
        val t = RawQuantisation.build(day, BLACK)
        // sigma = sqrt(0.3747) = 0.61 < 2: every DN up to black keeps its own code.
        for (i in 0..BLACK) assertEquals(i, t.reconstruct(i))
    }

    @Test
    fun invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RawQuantisation.build(day, -1) }
        assertThrows(IllegalArgumentException::class.java) { RawQuantisation.build(day, 1024) }
        assertThrows(IllegalArgumentException::class.java) { RawQuantisation.build(day, BLACK, k = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { RawQuantisation.NoiseModel(Double.NaN, 1.0) }
        val good = RawQuantisation.build(dark, BLACK)
        assertThrows(IllegalArgumentException::class.java) {
            RawQuantisation.Tables(good.forward, good.inverse.copyOf(good.count - 1), 500, BLACK)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawQuantisation.Tables(good.forward, good.inverse.copyOf().also { it[0] = 1024 }, 500, BLACK)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawQuantisation.Tables(good.forward.copyOf(1023), good.inverse, 500, BLACK)
        }
    }

    @Test
    fun prefixRoundTrips() {
        val t = RawQuantisation.build(dark, BLACK)
        val buffer = ByteBuffer.allocate(4 + RawQuantisation.prefixBytes(t.count)).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        RawQuantisation.writePrefix(t, 25600, buffer)
        assertEquals(buffer.capacity(), buffer.position())
        assertEquals(ByteOrder.BIG_ENDIAN, buffer.order())
        val le = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, le.get(4).toInt())
        assertEquals(0, le.get(5).toInt())
        assertEquals(112, le.getShort(6).toInt())
        assertEquals(25600, le.getInt(8))
        assertEquals(500, le.getShort(12).toInt())
        assertEquals(64, le.getShort(14).toInt())
        buffer.position(4)
        val prefix = checkNotNull(RawQuantisation.readPrefix(buffer))
        assertEquals(4, buffer.position())
        assertEquals(112, prefix.count)
        assertEquals(25600L, prefix.iso)
        assertEquals(500, prefix.kMilli)
        assertEquals(64, prefix.blackLevel)
        assertArrayEquals(t.inverse, prefix.inverse)
    }

    @Test
    fun malformedPrefixesAreNull() {
        val t = RawQuantisation.build(dark, BLACK)
        val good = ByteBuffer.allocate(RawQuantisation.prefixBytes(t.count)).also { RawQuantisation.writePrefix(t, 1, it) }.array()
        fun read(bytes: ByteArray) = RawQuantisation.readPrefix(ByteBuffer.wrap(bytes))
        fun patched(at: Int, vararg values: Int) = good.copyOf().also { b -> values.forEachIndexed { k, v -> b[at + k] = v.toByte() } }
        assertNotNull(read(good))
        assertNull(read(good.copyOf(11)))
        assertNull(read(good.copyOf(good.size - 1)))
        assertNull(read(patched(0, 2)))
        assertNull(read(patched(2, 0, 0)))
        assertNull(read(patched(2, 1, 4)))
        assertNull(read(patched(12, 0, 4)))
        // The reserved byte is ignored.
        assertNotNull(read(patched(1, 9)))
    }

    @Test
    fun codesMapThroughTheInverseTable() {
        val inverse = IntArray(600) { 1023 - it }
        val codes = IntArray(16) { it * 37 }
        val buffer = ByteBuffer.wrap(packRaw10(codes))
        assertTrue(RawQuantisation.mapCodes(buffer, buffer.capacity(), inverse))
        assertArrayEquals(IntArray(16) { inverse[codes[it]] }, unpackRaw10(buffer.array()))
        // A code past the table is corrupt.
        val bad = ByteBuffer.wrap(packRaw10(IntArray(4) { if (it == 3) 600 else 0 }))
        assertFalse(RawQuantisation.mapCodes(bad, 5, inverse))
    }

    /** The encoding 3 fixtures of tools/tests decode, through the pure-Kotlin path, to inverse[forward[x]]. */
    @Test
    fun fixtureDecodesThroughABlockPackDecoder() {
        val t = RawQuantisation.build(dark, BLACK)
        val data = File(FIXTURES, "blockpackq_64x34_s8_seed1.bin").readBytes()
        val src = ByteBuffer.wrap(data)
        val prefix = checkNotNull(RawQuantisation.readPrefix(src))
        assertArrayEquals(t.inverse, prefix.inverse)
        val codes = IntArray(64 * 34) { (it * 13) % t.count }
        var container: ByteArray? = null
        val dst = ByteBuffer.allocate(64 / 4 * 5 * 34)
        assertTrue(RawQuantisation.decodeWith(src, 64, 34, dst) { c, out ->
            container = ByteArray(c.remaining()).also { c.duplicate().get(it) }
            out.duplicate().put(packRaw10(codes))
            true
        })
        assertArrayEquals(data.copyOfRange(prefix.bytes, data.size), container)
        assertArrayEquals(IntArray(codes.size) { t.inverse[codes[it]] }, unpackRaw10(dst.array()))
        assertFalse(RawQuantisation.decodeWith(src, 64, 34, dst) { _, _ -> false })
        assertFalse(RawQuantisation.decodeWith(ByteBuffer.wrap(data.copyOf(10)), 64, 34, dst) { _, _ -> true })
    }

    private companion object {
        const val BLACK = 64
        val FIXTURES = File("../tools/tests/fixtures/ocraw_codecs")

        fun packRaw10(samples: IntArray): ByteArray {
            val out = ByteArray(samples.size / 4 * 5)
            for (g in 0 until samples.size / 4) {
                var low = 0
                for (k in 0 until 4) {
                    out[g * 5 + k] = (samples[g * 4 + k] shr 2).toByte()
                    low = low or ((samples[g * 4 + k] and 3) shl (2 * k))
                }
                out[g * 5 + 4] = low.toByte()
            }
            return out
        }

        fun unpackRaw10(bytes: ByteArray): IntArray = IntArray(bytes.size / 5 * 4) { i ->
            val g = i / 4
            val k = i % 4
            (bytes[g * 5 + k].toInt() and 0xff) shl 2 or ((bytes[g * 5 + 4].toInt() shr (2 * k)) and 3)
        }
    }
}
