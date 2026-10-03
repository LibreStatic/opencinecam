/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPeakingTest {
    private val isp = MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR

    private fun raster(width: Int, height: Int, value: (x: Int, y: Int) -> Int) =
        ByteArray(width * height) { value(it % width, it / width).toByte() }

    @Test
    fun `a sharp vertical step marks a one pixel line`() {
        val mask = detectFocusEdges(raster(40, 20) { x, _ -> if (x < 17) 30 else 220 }, 40, 20, 35, isp)
        assertEquals(20, mask.edgeCount)
        for (y in 0 until 20) for (x in 0 until 40) assertEquals("($x, $y)", x == 16, mask[x, y])
    }

    @Test
    fun `a sharp horizontal step marks a one pixel line`() {
        val mask = detectFocusEdges(raster(30, 30) { _, y -> if (y < 9) 200 else 10 }, 30, 30, 35, isp)
        assertEquals(30, mask.edgeCount)
        assertTrue((0 until 30).all { mask[it, 8] })
    }

    @Test
    fun `a flat frame and a soft ramp peak nothing`() {
        assertEquals(0, detectFocusEdges(raster(64, 36) { _, _ -> 128 }, 64, 36, 1, isp).edgeCount)
        // The same 190-code contrast spread over 19 pixels: 10 codes per step, under the threshold.
        val ramp = raster(64, 36) { x, _ -> (30 + (x - 20).coerceIn(0, 19) * 10).coerceAtMost(220) }
        assertEquals(0, detectFocusEdges(ramp, 64, 36, 35, isp).edgeCount)
    }

    @Test
    fun `the threshold is inclusive and respected`() {
        val step = raster(8, 4) { x, _ -> if (x < 4) 100 else 140 }
        assertEquals(4, detectFocusEdges(step, 8, 4, 40, isp).edgeCount)
        assertEquals(0, detectFocusEdges(step, 8, 4, 41, isp).edgeCount)
        // Horizontal and vertical steps add up on a corner.
        val corner = raster(4, 4) { x, y -> if (x < 2 && y < 2) 100 else 120 }
        val mask = detectFocusEdges(corner, 4, 4, 40, isp)
        assertEquals(1, mask.edgeCount)
        assertTrue(mask[1, 1])
    }

    @Test
    fun `packing round trips and equality follows content`() {
        val pattern = { x: Int, y: Int -> (x * 7 + y * 3) % 5 == 0 || x == y }
        val mask = FocusPeakingMask.of(37, 11, isp, pattern)
        for (y in 0 until 11) for (x in 0 until 37) assertEquals(pattern(x, y), mask[x, y])
        val same = FocusPeakingMask.of(37, 11, isp, pattern)
        assertEquals(mask, same)
        assertEquals(mask.hashCode(), same.hashCode())
        assertNotEquals(mask, FocusPeakingMask.of(37, 11, MonitoringSignalDomain.OCLOG2_CODE, pattern))
        assertNotEquals(mask, FocusPeakingMask.of(37, 11, isp) { x, y -> pattern(x, y) xor (x == 36 && y == 10) })
        val argb = mask.toArgb(0x7f00ff00)
        for (y in 0 until 11) for (x in 0 until 37) assertEquals(if (pattern(x, y)) 0x7f00ff00 else 0, argb[y * 37 + x])
        assertEquals(argb.count { it != 0 }, mask.edgeCount)
    }

    @Test
    fun `luma copy honours strides, subsampling and short final rows`() {
        val width = 6
        val height = 3
        val rowStride = 16
        val plane = ByteBuffer.allocate(rowStride * (height - 1) + width)
        for (y in 0 until height) for (x in 0 until width) plane.put(y * rowStride + x, (y * 10 + x).toByte())
        val packed = ByteArray(width * height)
        copyLumaPlane(plane, rowStride, 1, width, height, 1, packed)
        assertEquals((0 until 18).map { (it / 6) * 10 + it % 6 }, packed.map { it.toInt() })
        val half = ByteArray(6)
        copyLumaPlane(plane, rowStride, 1, width, height, 2, half)
        assertEquals(listOf(0, 2, 4, 20, 22, 24), half.map { it.toInt() })
        val interleaved = ByteBuffer.allocate(2 * 2 * 2)
        for (i in 0 until 4) interleaved.put(i * 2, (i + 1).toByte())
        val unpacked = ByteArray(4)
        copyLumaPlane(interleaved, 4, 2, 2, 2, 1, unpacked)
        assertEquals(listOf(1, 2, 3, 4), unpacked.map { it.toInt() })
    }

    @Test
    fun `the sample step keeps the mask within budget`() {
        assertEquals(1, focusPeakingStep(320, 240))
        assertEquals(1, focusPeakingStep(640, 480))
        assertEquals(2, focusPeakingStep(1280, 720))
        assertEquals(3, focusPeakingStep(1920, 1080))
        assertTrue(listOf(641 to 480, 4000 to 3000, 1 to 100_000).all { (w, h) ->
            val s = focusPeakingStep(w, h)
            ((w + s - 1) / s).toLong() * ((h + s - 1) / s) <= FocusPeakingMask.MAX_PIXELS
        })
    }

    @Test
    fun `a 4 by 3 analysis frame overhangs a 16 by 9 preview of a 4 by 3 sensor`() {
        val frame = outputFrameInStream(320, 240, 1920, 1080, 4000, 3000)
        assertEquals(0f, frame.left, 1e-5f)
        assertEquals(1f, frame.width, 1e-5f)
        assertEquals(-1f / 6f, frame.top, 1e-5f)
        assertEquals(4f / 3f, frame.height, 1e-5f)
        assertEquals(NormalizedFrame(0f, 0f, 1f, 1f), outputFrameInStream(160, 90, 1920, 1080, 4000, 3000))
        // A 16:9 sensor: the 4:3 analysis is cut at the sides instead.
        val wide = outputFrameInStream(320, 240, 1920, 1080, 1920, 1080)
        assertEquals(.75f, wide.width, 1e-5f)
        assertEquals(.125f, wide.left, 1e-5f)
        assertEquals(1f, wide.height, 1e-5f)
    }

    @Test
    fun `photo mode on a 4 by 3 sensor analyses a stream of the preview aspect`() {
        // YUV sizes of a 4:3 sensor such as the emulator's back camera.
        val sizes = listOf(1920 to 1440, 1920 to 1080, 1440 to 1080, 1280 to 960, 1280 to 720, 640 to 480,
            640 to 360, 352 to 288, 320 to 240, 176 to 144)
        val preview = sizes[previewSizeIndex(sizes)]
        assertEquals(1920 to 1080, preview)
        val analysis = sizes[analysisSizeIndex(sizes, preview.first, preview.second)]
        assertEquals(640 to 360, analysis)
        // The same crop as the preview, so the mask needs no fitting.
        assertEquals(NormalizedFrame(0f, 0f, 1f, 1f), outputFrameInStream(analysis.first, analysis.second, 1920, 1080, 4000, 3000))
        assertEquals(320 to 180, (sizes + (320 to 180)).let { it[analysisSizeIndex(it, 1920, 1080)] })
        // No size of the preview aspect: the nearest to 320 x 240, fitted by the centred crop.
        val square = listOf(1920 to 1440, 640 to 480, 320 to 240)
        assertEquals(2, analysisSizeIndex(square, 1920, 1080))
        assertEquals(-1, analysisSizeIndex(emptyList(), 1920, 1080))
        // The reverse: a 4:3 preview (1280 x 960 video profile) gets a 4:3 analysis, and a 16:9 mask
        // over it would be fitted inside, 1/8 short at the top and bottom.
        assertEquals(320 to 240, sizes[analysisSizeIndex(sizes, 1280, 960)])
        val inside = outputFrameInStream(320, 180, 1280, 960, 4000, 3000)
        assertEquals(NormalizedFrame(0f, .125f, 1f, .75f), inside)
    }

    @Test
    fun `gpu readback keeps scope sampling and adds a full resolution mask`() {
        val width = 320
        val height = 180
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val value = if (x < 100) 20 else 230
            val offset = (y * width + x) * 4
            rgba[offset] = value.toByte(); rgba[offset + 1] = value.toByte(); rgba[offset + 2] = value.toByte(); rgba[offset + 3] = -1
        }
        val options = MonitoringOptions(peakingThreshold = 35)
        val peaking = analyzeRgbaFrame(width, height, rgba, 1L, options, scopeStep = 2, focusPeaking = true)
        val scopes = requireNotNull(peaking.scopes)
        assertEquals(160, scopes.sampledWidth)
        assertEquals(90, scopes.sampledHeight)
        val mask = requireNotNull(peaking.focusPeaking)
        assertEquals(width, mask.width)
        assertEquals(height, mask.height)
        assertEquals(height, mask.edgeCount)
        assertTrue(mask[99, 0] && mask[99, height - 1])
        assertFalse(mask[100, 0])
        assertEquals(MonitoringSignalDomain.SDR_BT709_CODE, mask.domain)
        assertNull(analyzeRgbaFrame(width, height, rgba, 1L, options, scopeStep = 2).focusPeaking)
    }

    @Test
    fun `a 640 by 360 mask fits in an analysis tick`() {
        val width = 640
        val height = 360
        var seed = 0x2545F491
        val luma = ByteArray(width * height) {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            val x = it % width
            val y = it / width
            ((if ((x / 24 + y / 24) % 2 == 0) 60 else 190) + (seed and 15)).toByte()
        }
        repeat(30) { detectFocusEdges(luma, width, height, 35, isp) }
        val runs = 60
        val start = System.nanoTime()
        var edges = 0
        repeat(runs) { edges += detectFocusEdges(luma, width, height, 35, isp).edgeCount }
        val perFrameMs = (System.nanoTime() - start) / 1e6 / runs
        println("Focus peaking mask 640x360: %.3f ms per frame (%d edges)".format(perFrameMs, edges / runs))
        assertTrue(edges > 0)
        assertTrue("$perFrameMs ms", perFrameMs < 25.0)
    }
}
