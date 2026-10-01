/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCineLogYcbcrConversionTest {
    private fun apply(conversion: OpenCineLogYcbcrConversion, y: Int, cb: Int, cr: Int): DoubleArray {
        val maxCode = ((1 shl conversion.bitDepth) - 1).toDouble()
        val yuv = doubleArrayOf(y / maxCode, cb / maxCode, cr / maxCode)
        val shifted = DoubleArray(3) { yuv[it] - conversion.offset[it] }
        return DoubleArray(3) { row -> (0 until 3).sumOf { column -> conversion.matrix[column * 3 + row] * shifted[column] } }
    }

    /** Forward R'G'B' to integer YCbCr, written independently of the production matrix. */
    private fun encode(r: Double, g: Double, b: Double, kr: Double, kb: Double, full: Boolean, bits: Int): IntArray {
        val y = kr * r + (1 - kr - kb) * g + kb * b
        val cb = (b - y) / (2 * (1 - kb))
        val cr = (r - y) / (2 * (1 - kr))
        val step = (1 shl (bits - 8)).toDouble()
        val maxCode = ((1 shl bits) - 1).toDouble()
        return if (full) {
            intArrayOf(Math.round(y * maxCode).toInt(), Math.round(128 * step + cb * maxCode).toInt(), Math.round(128 * step + cr * maxCode).toInt())
        } else {
            intArrayOf(Math.round((16 + 219 * y) * step).toInt(), Math.round((128 + 224 * cb) * step).toInt(), Math.round((128 + 224 * cr) * step).toInt())
        }
    }

    private fun assertRgb(expected: DoubleArray, actual: DoubleArray, tolerance: Double) {
        for (channel in 0 until 3) assertEquals("channel $channel", expected[channel], actual[channel], tolerance)
    }

    @Test
    fun hlgDataSpacesSelectBt2020WithTheirRange() {
        val full = OpenCineLogYcbcrConversion.forDataSpace(168165376, OpenCineLogSourcePath.HLG10_BT2020)
        assertEquals("BT2020/full/10-bit", full.label)
        val limited = OpenCineLogYcbcrConversion.forDataSpace(302383104, OpenCineLogSourcePath.HLG10_BT2020)
        assertEquals("BT2020/limited/10-bit", limited.label)
        assertRgb(doubleArrayOf(0.0, 0.0, 0.0), apply(limited, 64, 512, 512), 1e-6)
        assertRgb(doubleArrayOf(1.0, 1.0, 1.0), apply(limited, 940, 512, 512), 1e-6)
        assertRgb(doubleArrayOf(1.0, 1.0, 1.0), apply(full, 1023, 512, 512), 2e-3)
    }

    @Test
    fun saturatedPrimariesRoundTripPerStandardAndRange() {
        for (standard in OpenCineLogYcbcrConversion.Standard.entries) for (full in listOf(true, false)) for (bits in listOf(8, 10)) {
            val conversion = OpenCineLogYcbcrConversion.create(standard, full, bits)
            for (rgb in listOf(doubleArrayOf(0.7, 0.0, 0.0), doubleArrayOf(0.0, 0.7, 0.0), doubleArrayOf(0.0, 0.0, 0.7), doubleArrayOf(0.18, 0.18, 0.18))) {
                val codes = encode(rgb[0], rgb[1], rgb[2], standard.kr, standard.kb, full, bits)
                // One code of rounding, amplified by the widest matrix coefficient.
                val tolerance = 4.0 / ((1 shl bits) - 1)
                assertRgb(rgb, apply(conversion, codes[0], codes[1], codes[2]), tolerance)
            }
        }
    }

    @Test
    fun unspecifiedDataSpaceFallsBackToBt601LimitedAndSaysSo() {
        val conversion = OpenCineLogYcbcrConversion.forDataSpace(0, OpenCineLogSourcePath.SDR_BT709_ISP)
        assertFalse(conversion.fromDataSpace)
        assertEquals("BT601/limited/8-bit (default)", conversion.label)
        assertEquals(conversion, OpenCineLogYcbcrConversion.forDataSpace(null, OpenCineLogSourcePath.SDR_BT709_ISP))
        val bt709 = OpenCineLogYcbcrConversion.forDataSpace(281083904, OpenCineLogSourcePath.SDR_BT709_ISP)
        assertTrue(bt709.fromDataSpace)
        assertEquals("BT709/limited/8-bit", bt709.label)
        val jfif = OpenCineLogYcbcrConversion.forDataSpace(146931712, OpenCineLogSourcePath.SDR_BT709_ISP)
        assertEquals("BT601/full/8-bit", jfif.label)
    }
}
