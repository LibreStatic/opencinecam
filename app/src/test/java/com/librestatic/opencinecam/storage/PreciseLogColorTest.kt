/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.playback.LogPlaybackRenderer
import org.junit.Assert.*
import org.junit.Test

class PreciseLogColorTest {
    // References evaluated independently (Python, double precision) from the OCLog2 curve, the
    // BT.2020 NCL matrix, the capture shaders' BT.2020->BT.709 matrix and the Rec.709 OETF.
    @Test fun neutralMidGreyFlatKeepsCodesAndRec709Assists() {
        assertRgb(145, 145, 145, oclog2P010ToArgb(582, 512, 512, true, PreciseLogView.FLAT_LOG))
        assertRgb(104, 104, 104, oclog2P010ToArgb(582, 512, 512, true, PreciseLogView.REC709))
    }

    @Test fun limitedRangeMatchesFullRangeForTheSameCode() {
        assertRgb(145, 145, 145, oclog2P010ToArgb(562, 512, 512, false, PreciseLogView.FLAT_LOG))
        assertRgb(104, 104, 104, oclog2P010ToArgb(562, 512, 512, false, PreciseLogView.REC709))
    }

    @Test fun codesOutsideTheSignalRangeClampToPedestalAndWhite() {
        assertRgb(26, 26, 26, oclog2P010ToArgb(102, 512, 512, true, PreciseLogView.FLAT_LOG))
        assertRgb(0, 0, 0, oclog2P010ToArgb(102, 512, 512, true, PreciseLogView.REC709))
        assertRgb(230, 230, 230, oclog2P010ToArgb(921, 512, 512, true, PreciseLogView.FLAT_LOG))
        assertRgb(255, 255, 255, oclog2P010ToArgb(921, 512, 512, true, PreciseLogView.REC709))
    }

    @Test fun chromaFollowsBt2020AndTheFlatMonitorDesaturates() {
        assertRgb(227, 127, 127, oclog2P010ToArgb(600, 400, 700, true, PreciseLogView.FLAT_LOG))
        assertRgb(255, 39, 40, oclog2P010ToArgb(600, 400, 700, true, PreciseLogView.REC709))
    }

    @Test fun rejectsNon10BitSamples() {
        assertThrows(IllegalArgumentException::class.java) { oclog2P010ToArgb(1024, 512, 512, true, PreciseLogView.FLAT_LOG) }
    }

    @Test fun reviewShaderUsesTheSameConstantsAndOnlyRequestsY2YWhenAsked() {
        val y2y = LogPlaybackRenderer.reviewFragmentShader(shaderYcbcr = true)
        val oes = LogPlaybackRenderer.reviewFragmentShader(shaderYcbcr = false)
        for (shader in listOf(y2y, oes)) {
            assertTrue(shader.startsWith("#version 300 es\n"))
            for (constant in listOf("1.660491", "-0.587641", "-0.072850", "-0.124550", "1.132900", "-0.008349",
                "-0.018151", "-0.100579", "1.118730", "0.2126, 0.7152, 0.0722", "0.68", "50.0", "51.0", "0.018", "0.45")) {
                assertTrue(constant, constant in shader)
            }
        }
        assertTrue("GL_EXT_YUV_target" in y2y && "__samplerExternal2DY2YEXT" in y2y)
        assertFalse("GL_EXT_YUV_target" in oes || "uYcbcrToRgb" in oes)
    }

    private fun assertRgb(r: Int, g: Int, b: Int, argb: Int) {
        assertEquals(0xFF, argb ushr 24)
        assertEquals(listOf(r, g, b), listOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF))
    }
}
