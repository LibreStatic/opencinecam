/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import com.librestatic.opencinecam.storage.PreciseLogSignal
import com.librestatic.opencinecam.storage.PreciseLogView
import com.librestatic.opencinecam.storage.oclog2CodesToArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class LogThumbnailColorTest {
    // References evaluated independently in Python (double precision) from the OCLog2 curve, the
    // BT.2020->BT.709 matrix, the flat monitor's 0.68 saturation and viewAssist709.
    @Test fun neutralGreyFlatKeepsTheCodeAndRec709Assists() {
        assertRgb(145, 145, 145, oclog2CodesToArgb(145, 145, 145, PreciseLogView.FLAT_LOG))
        assertRgb(104, 104, 104, oclog2CodesToArgb(145, 145, 145, PreciseLogView.REC709))
    }

    @Test fun codesOutsideTheSignalRangeClampToPedestalAndWhite() {
        assertRgb(26, 26, 26, oclog2CodesToArgb(10, 10, 10, PreciseLogView.FLAT_LOG))
        assertRgb(0, 0, 0, oclog2CodesToArgb(10, 10, 10, PreciseLogView.REC709))
        assertRgb(230, 230, 230, oclog2CodesToArgb(250, 250, 250, PreciseLogView.FLAT_LOG))
        assertRgb(239, 239, 239, oclog2CodesToArgb(250, 250, 250, PreciseLogView.REC709))
    }

    @Test fun colourGoesThroughTheGamutMatrixAndFlatDesaturation() {
        assertRgb(208, 120, 111, oclog2CodesToArgb(200, 120, 80, PreciseLogView.FLAT_LOG))
        assertRgb(237, 46, 24, oclog2CodesToArgb(200, 120, 80, PreciseLogView.REC709))
        assertRgb(117, 181, 122, oclog2CodesToArgb(60, 180, 90, PreciseLogView.FLAT_LOG))
        assertRgb(0, 150, 83, oclog2CodesToArgb(60, 180, 90, PreciseLogView.REC709))
    }

    @Test fun legacyHlgTakesSkipTheGamutMatrix() {
        val legacy = PreciseLogSignal(fullRange = true, legacyBt709Primaries = true)
        assertRgb(190, 76, 38, oclog2CodesToArgb(200, 120, 80, PreciseLogView.REC709, legacy))
    }

    @Test fun outOfRangeInputsClampToEightBitCodes() {
        assertEquals(oclog2CodesToArgb(255, 0, 255, PreciseLogView.REC709), oclog2CodesToArgb(300, -4, 999, PreciseLogView.REC709))
    }

    private fun assertRgb(r: Int, g: Int, b: Int, argb: Int) {
        assertEquals(0xFF, argb ushr 24)
        assertEquals(listOf(r, g, b), listOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF))
    }
}
