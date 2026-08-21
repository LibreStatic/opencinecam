/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import android.util.Size
import kotlin.math.exp
import kotlin.math.pow

class OpenCineLogGpuPipelineContractTest {
    @Test
    fun productionShaderIsPinnedToTheSidecarIdentity() {
        assertEquals(
            "21ad5d65afb57b87377eac537c3e2cb33a362f25757069dfea6c22dc480bd8f4",
            OpenCineLogGpuPipeline.TRANSFORM_SHA256,
        )
        assertEquals(
            "2b76aeae8909164a3f1620f6c8d361715aa1ac55413a0215df3e3dadb1710a3b",
            OpenCineLogGpuPipeline.SDR_TRANSFORM_SHA256,
        )
        assertNotEquals(OpenCineLogGpuPipeline.TRANSFORM_SHA256, OpenCineLogGpuPipeline.SDR_TRANSFORM_SHA256)
    }

    @Test
    fun hlgDecodeThenOcLog2MatchesReferenceCurve() {
        val hlgCodes = listOf(0.0, 0.25, 0.5, 0.75, 1.0)
        val results = hlgCodes.map { OpenCineLog2Curve.encode(inverseHlg(it)) }
        assertEquals(OpenCineLog2Curve.BLACK_CODE, results.first(), 1e-9)
        assertEquals(OpenCineLog2Curve.WHITE_CODE, results.last(), 1e-9)
        assertTrue(results.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun monitoringAssistIsNumericallySeparateFromFlatLog() {
        val linear = inverseHlg(0.75)
        val flat = OpenCineLog2Curve.encode(linear)
        val assisted = rec709Oetf(OpenCineLog2Curve.decode(flat))
        assertNotEquals(flat, assisted, 1e-3)
        assertEquals(linear, OpenCineLog2Curve.decode(flat), 1e-9)
    }

    @Test
    fun ocLog2ReservesBlackPedestalAndHighlightHeadroom() {
        assertEquals(0.10, OpenCineLog2Curve.encode(0.0), 1e-12)
        assertEquals(0.90, OpenCineLog2Curve.encode(1.0), 1e-12)
        listOf(0.0, 0.01, 0.18, 0.5, 1.0).forEach { linear ->
            assertEquals(linear, OpenCineLog2Curve.decode(OpenCineLog2Curve.encode(linear)), 1e-9)
        }
    }

    @Test
    fun ispHfrBranchDisclosesStandardRangeAndNeverClaimsTenBitSource() {
        val source = OpenCineLogSourcePath.SDR_BT709_ISP
        assertTrue(source.highSpeedDerived)
        assertEquals("STANDARD", source.dynamicRange)
        assertEquals("BT709_ASSUMED", source.colorSpace)
        assertTrue(source.sourcePrecision.contains("not claimed"))
        assertTrue(!OpenCineLogSourcePath.HLG10_BT2020.highSpeedDerived)
        assertThrows(IllegalArgumentException::class.java) {
            Camera2LogProfile(Size(1920, 1080), 120, OpenCineLogSourcePath.HLG10_BT2020, true)
        }
        Camera2LogProfile(Size(1920, 1080), 120, OpenCineLogSourcePath.SDR_BT709_ISP, true)
    }

    @Test
    fun inverseRec709IsMonotonicBeforeOcLogEncoding() {
        val codes = listOf(0.0, 0.04, 0.081, 0.5, 1.0)
        val linear = codes.map(::inverseRec709)
        assertEquals(0.0, linear.first(), 1e-12)
        assertEquals(1.0, linear.last(), 1e-12)
        assertTrue(linear.zipWithNext().all { (a, b) -> b > a })
        assertTrue(linear.map(OpenCineLog2Curve::encode).zipWithNext().all { (a, b) -> b > a })
    }

    private fun inverseHlg(code: Double): Double = if (code <= 0.5) {
        code * code / 3.0
    } else {
        (exp((code - 0.55991073) / 0.17883277) + 0.28466892) / 12.0
    }.coerceIn(0.0, 1.0)

    private fun rec709Oetf(linear: Double): Double = if (linear < 0.018) {
        4.5 * linear
    } else {
        1.099 * linear.pow(0.45) - 0.099
    }

    private fun inverseRec709(code: Double): Double = if (code < 0.081) {
        code / 4.5
    } else {
        ((code + 0.099) / 1.099).pow(1.0 / 0.45)
    }
}
