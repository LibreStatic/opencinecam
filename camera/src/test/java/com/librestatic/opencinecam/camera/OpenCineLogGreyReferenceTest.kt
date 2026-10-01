/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

class OpenCineLogGreyReferenceTest {
    @Test
    fun ratioDerivesFromBt2408AndRec709Grey() {
        // BT.2408 grey at 38% HLG -> 0.38^2/3; Rec.709 OETF(0.18) inverts back to 0.18.
        assertEquals(0.0481333333, OpenCineLogGreyReference.HLG_REFERENCE_GREY_LINEAR, 1e-9)
        assertEquals(0.18, inverseRec709(rec709Oetf(0.18)), 1e-12)
        assertEquals(0.54 / 0.1444, OpenCineLogGreyReference.TIER_GREY_RATIO, 1e-12)
        assertEquals(1.9029, ln(OpenCineLogGreyReference.TIER_GREY_RATIO) / ln(2.0), 1e-4)
    }

    @Test
    fun gainPerTierAndMode() {
        val hlg = OpenCineLogSourcePath.HLG10_BT2020
        val sdr = OpenCineLogSourcePath.SDR_BT709_ISP
        val ratio = OpenCineLogGreyReference.TIER_GREY_RATIO.toFloat()
        assertEquals(1f, OpenCineLogGreyReference.NATIVE.sceneGain(hlg), 0f)
        assertEquals(1f, OpenCineLogGreyReference.NATIVE.sceneGain(sdr), 0f)
        assertEquals(1f, OpenCineLogGreyReference.MATCH_HLG.sceneGain(hlg), 0f)
        assertEquals(1f / ratio, OpenCineLogGreyReference.MATCH_HLG.sceneGain(sdr), 1e-7f)
        assertEquals(ratio, OpenCineLogGreyReference.MATCH_SDR.sceneGain(hlg), 1e-6f)
        assertEquals(1f, OpenCineLogGreyReference.MATCH_SDR.sceneGain(sdr), 0f)
    }

    @Test
    fun matchedModesPlaceTheSameGreyCardOnTheSameCode() {
        val hlgGrey = inverseHlg(OpenCineLogGreyReference.HLG_REFERENCE_GREY_SIGNAL)
        val sdrGrey = bt709GreyToBt2020(inverseRec709(rec709Oetf(0.18)))
        for (mode in listOf(OpenCineLogGreyReference.MATCH_HLG, OpenCineLogGreyReference.MATCH_SDR)) {
            val hlgCode = OpenCineLog2Curve.encode(hlgGrey * mode.sceneGain(OpenCineLogSourcePath.HLG10_BT2020))
            val sdrCode = OpenCineLog2Curve.encode(sdrGrey * mode.sceneGain(OpenCineLogSourcePath.SDR_BT709_ISP))
            assertEquals("$mode", hlgCode, sdrCode, 1e-6)
        }
        val nativeHlg = OpenCineLog2Curve.encode(hlgGrey)
        val nativeSdr = OpenCineLog2Curve.encode(sdrGrey)
        assertTrue(nativeSdr - nativeHlg > 0.1)
    }

    @Test
    fun matchHlgIsLosslessAndMatchSdrClipsAboveThreeQuartersHlg() {
        val sdrPeak = OpenCineLogGreyReference.MATCH_HLG.sceneGain(OpenCineLogSourcePath.SDR_BT709_ISP) * inverseRec709(1.0)
        assertTrue(sdrPeak < 1.0)
        val clip = OpenCineLogGreyReference.MATCH_SDR_CLIP_HLG_SIGNAL
        assertEquals(0.752, clip, 1e-3)
        val gain = OpenCineLogGreyReference.TIER_GREY_RATIO
        assertEquals(1.0, inverseHlg(clip) * gain, 1e-6)
        assertTrue(inverseHlg(clip - 0.01) * gain < 1.0)
        assertTrue(inverseHlg(clip + 0.01) * gain > 1.0)
    }

    @Test
    fun persistedNamesAreStable() {
        assertEquals(listOf("NATIVE", "MATCH_HLG", "MATCH_SDR"), OpenCineLogGreyReference.entries.map { it.name })
    }

    // Neutral input stays neutral through the BT.709-to-BT.2020 matrix (rows sum to 1).
    private fun bt709GreyToBt2020(linear: Double): Double = linear * (0.627404 + 0.329283 + 0.043313)

    private fun inverseHlg(code: Double): Double = if (code <= 0.5) {
        code * code / 3.0
    } else {
        (exp((code - 0.55991073) / 0.17883277) + 0.28466892) / 12.0
    }

    private fun rec709Oetf(linear: Double): Double = if (linear < 0.018) 4.5 * linear else 1.099 * linear.pow(0.45) - 0.099

    private fun inverseRec709(code: Double): Double = if (code < 0.081) code / 4.5 else ((code + 0.099) / 1.099).pow(1.0 / 0.45)
}
