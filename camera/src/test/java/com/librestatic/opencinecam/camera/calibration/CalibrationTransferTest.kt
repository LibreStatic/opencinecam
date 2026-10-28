/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTransferTest {
    @Test fun sdrMatchesShaderFormulas() {
        // inverseRec709: e / 4.5 below 0.081, otherwise ((e + 0.099) / 1.099)^(1 / 0.45)
        assertEquals(0.05f / 4.5f, CalibrationTransfer.toLinear(TransferCurve.SDR, 0.05f), 1e-6f)
        assertEquals(Math.pow((0.5 + 0.099) / 1.099, 1 / 0.45).toFloat(), CalibrationTransfer.toLinear(TransferCurve.SDR, 0.5f), 1e-6f)
        // rec709Oetf: 4.5 x below 0.018, otherwise 1.099 x^0.45 - 0.099
        assertEquals(4.5f * 0.01f, CalibrationTransfer.toCode(TransferCurve.SDR, 0.01f), 1e-6f)
        assertEquals((1.099 * Math.pow(0.2, 0.45) - 0.099).toFloat(), CalibrationTransfer.toCode(TransferCurve.SDR, 0.2f), 1e-6f)
    }

    @Test fun hlgDecodeMatchesShader() {
        assertEquals(0.25f / 3f, CalibrationTransfer.toLinear(TransferCurve.HLG, 0.5f), 1e-6f)
        val e = 0.8
        val expected = ((Math.exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0).toFloat()
        assertEquals(expected, CalibrationTransfer.toLinear(TransferCurve.HLG, 0.8f), 1e-6f)
    }

    @Test fun lutRoundTripsEveryCode() {
        for (curve in TransferCurve.values()) {
            val lut = CalibrationTransfer.decodeLut(curve)
            assertEquals(256, lut.size)
            for (code in 0..255) {
                assertEquals(code / 255f, CalibrationTransfer.toCode(curve, lut[code]), 1e-4f)
                val fast = CalibrationTransfer.encodeFast(CalibrationTransfer.encodeLut(curve), lut[code])
                assertTrue("$curve code $code gave $fast", Math.abs(fast - code) < 0.5f)
            }
            assertEquals(0f, lut[0], 0f)
            assertEquals(1f, lut[255], 1e-5f)
        }
    }
}
