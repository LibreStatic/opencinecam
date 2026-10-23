/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameOpsAndCellStatsTest {
    private fun solid(w: Int, h: Int, r: Int, g: Int, b: Int): RgbaFrame {
        val px = ByteArray(w * h * 4)
        for (i in 0 until w * h) { px[i * 4] = r.toByte(); px[i * 4 + 1] = g.toByte(); px[i * 4 + 2] = b.toByte(); px[i * 4 + 3] = 0xFF.toByte() }
        return RgbaFrame(w, h, px)
    }

    @Test fun cropScaleUnitIsIdentity() {
        val src = CalibrationTestSupport.cellFrame(CalibrationTestSupport.scene(), cell = 4)
        val out = FrameOps.cropScale(src, 0.5f, 0.5f, 1f, src.width, src.height)
        assertTrue(src.pixels.contentEquals(out.pixels))
    }

    @Test fun cropScaleZoomsIntoCentre() {
        // Left half black, right half white; zooming 2x on the left quarter point shows mostly black.
        val w = 64; val h = 16
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 4) px[(y * w + x) * 4 + c] = if (c == 3 || x >= w / 2) 0xFF.toByte() else 0
        val f = RgbaFrame(w, h, px)
        val left = FrameOps.cropScale(f, 0.25f, 0.5f, 2f, 16, 8)
        val right = FrameOps.cropScale(f, 0.75f, 0.5f, 2f, 16, 8)
        assertEquals(0, left.channel(8, 4, 0)); assertEquals(255, right.channel(8, 4, 0))
        val up = FrameOps.cropScale(f, 0.5f, 0.5f, 2f, 16, 8)
        assertEquals(0, up.channel(0, 4, 0)); assertEquals(255, up.channel(15, 4, 0))
    }

    @Test fun lumaUsesRec709Weights() {
        val f = solid(2, 2, 255, 0, 0)
        assertEquals(0.2126f, FrameOps.luma(f, linear = false).values[0], 1e-4f)
        assertEquals(0.2126f, FrameOps.luma(f, linear = true).values[0], 1e-4f)
        val grey = solid(2, 2, 128, 128, 128)
        assertEquals(128 / 255f, FrameOps.luma(grey, false).values[0], 1e-4f)
        assertEquals(CalibrationTransfer.toLinear(TransferCurve.SDR, 128 / 255f), FrameOps.luma(grey, true).values[0], 1e-4f)
    }

    @Test fun cellStatsLinearisesBeforeAveraging() {
        // Half the cell code 0, half code 255: linear mean 0.5, not the linear value of code 127.
        val w = 12; val h = 8
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val v = if (x % 2 == 0) 255 else 0
            for (c in 0 until 3) px[(y * w + x) * 4 + c] = v.toByte()
            px[(y * w + x) * 4 + 3] = 0xFF.toByte()
        }
        val cell = CellStats.compute(RgbaFrame(w, h, px), 1, 1)[0]
        assertEquals(0.5f, cell.r, 1e-4f)
        assertEquals(0.25f, cell.lumaVariance, 1e-3f)
        assertEquals(0.5f, cell.clipFraction, 1e-4f)
        assertEquals(0.5f, cell.darkFraction, 1e-4f)
    }

    @Test fun cellStatsGridAndFlatCells() {
        val cells = CellStats.compute(solid(120, 80, 100, 100, 100))
        assertEquals(96, cells.size)
        assertEquals(11, cells[11].col); assertEquals(1, cells[12].row)
        val lin = CalibrationTransfer.toLinear(TransferCurve.SDR, 100 / 255f)
        for (c in cells) {
            assertEquals(lin, c.g, 1e-5f); assertEquals(0f, c.lumaVariance, 1e-7f)
            assertEquals(0f, c.clipFraction, 0f); assertEquals(0f, c.darkFraction, 0f)
        }
        assertEquals(1f, CellStats.compute(solid(24, 16, 251, 10, 10), 2, 2)[0].clipFraction, 0f)
    }
}
