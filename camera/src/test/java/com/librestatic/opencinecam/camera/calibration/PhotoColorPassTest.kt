/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoColorPassTest {
    private val identity = ColorFit.IDENTITY_MATRIX
    private val unit = floatArrayOf(1f, 1f, 1f)

    private fun allCodes(): IntArray = IntArray(256 * 3) { i ->
        val c = i / 3
        0xFF000000.toInt() or when (i % 3) { 0 -> (c shl 16) or (255 - c shl 8) or (c / 2); 1 -> (255 - c shl 16) or (c shl 8) or c; else -> (c / 3 shl 16) or (c shl 8) or (255 - c) }
    }

    @Test fun identityIsNoOpWithinOneCode() {
        for (curve in TransferCurve.values()) {
            val px = allCodes(); val orig = px.copyOf()
            PhotoColorPass.applyToArgb(px, identity, unit, 0f, curve)
            for (i in px.indices) {
                assertEquals(orig[i] ushr 24, px[i] ushr 24)
                for (s in intArrayOf(0, 8, 16)) assertTrue("$curve $i", Math.abs(((px[i] shr s) and 0xFF) - ((orig[i] shr s) and 0xFF)) <= 1)
            }
        }
    }

    @Test fun matchesLensColorTransform() {
        val matrix = floatArrayOf(0.9f, 0.07f, 0.03f, 0.05f, 0.92f, 0.03f, 0.02f, 0.08f, 0.9f)
        val gains = floatArrayOf(1.05f, 0.99f, 0.93f)
        val ev = -0.3f
        val px = allCodes(); val orig = px.copyOf()
        PhotoColorPass.applyToArgb(px, matrix, gains, ev)
        val lut = CalibrationTransfer.decodeLut(TransferCurve.SDR)
        for (i in px.indices) {
            val lin = floatArrayOf(lut[(orig[i] shr 16) and 0xFF], lut[(orig[i] shr 8) and 0xFF], lut[orig[i] and 0xFF])
            val want = LensColorTransform.apply(lin, matrix, gains, ev)
            for ((k, s) in intArrayOf(16, 8, 0).withIndex()) {
                val code = Math.round(CalibrationTransfer.toCode(TransferCurve.SDR, want[k]) * 255f)
                assertTrue("pixel $i ch $k want $code got ${(px[i] shr s) and 0xFF}", Math.abs(((px[i] shr s) and 0xFF) - code) <= 1)
            }
        }
    }

    @Test fun brighteningClampsAndKeepsAlpha() {
        val px = intArrayOf(0x80F0F0F0.toInt())
        PhotoColorPass.applyToArgb(px, identity, unit, 2f)
        assertEquals(0x80FFFFFF.toInt(), px[0])
    }
}
