/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.ui.geometry.Offset
import com.librestatic.opencinecam.camera.MonitoringSignalDomain
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusPeakingOverlayTest {
    private val gpu = MonitoringSignalDomain.SDR_BT709_CODE
    private val isp = MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals("x of $actual", expected.x, actual.x, 1e-2f)
        assertEquals("y of $actual", expected.y, actual.y, 1e-2f)
    }

    private fun assertPlacement(origin: Offset, xStep: Offset, yStep: Offset, actual: FocusPeakingPlacement) {
        assertOffset(origin, actual.origin); assertOffset(xStep, actual.xStep); assertOffset(yStep, actual.yStep)
    }

    @Test
    fun `an upright gpu mask scales onto the overlay with edges on pixel boundaries`() {
        val placement = focusPeakingPlacement(100, 50, gpu, 1920, 1080, 4000, 3000, 90, 0, false, 1000f, 500f, 1f, 1f)
        assertPlacement(Offset(5f, 5f), Offset(10f, 0f), Offset(0f, 10f), placement)
        assertPlacement(Offset(0f, 0f), Offset(10f, 0f), Offset(0f, 10f),
            focusPeakingPlacement(100, 50, gpu, 1920, 1080, 4000, 3000, 90, 0, false, 1000f, 500f, 1f, 1f, edgeShift = 0f))
    }

    @Test
    fun `a sensor mounted at 90 degrees turns mask rows into overlay columns`() {
        assertPlacement(Offset(295f, 5f), Offset(0f, 10f), Offset(-10f, 0f),
            focusPeakingPlacement(40, 30, isp, 40, 30, 40, 30, 90, 0, false, 300f, 400f, 1f, 1f))
    }

    @Test
    fun `a 4 by 3 analysis mask overhangs a 16 by 9 preview by the stream crop`() {
        assertPlacement(Offset(2.5f, -147.5f), Offset(5f, 0f), Offset(0f, 5f),
            focusPeakingPlacement(320, 240, isp, 1920, 1080, 4000, 3000, 0, 0, false, 1600f, 900f, 1f, 1f))
        // Portrait phone: the overhang moves to the sides and the stream's rows 30 to 209 fill the width.
        val portrait = focusPeakingPlacement(320, 240, isp, 1920, 1080, 4000, 3000, 90, 0, false, 1080f, 1920f, 1f, 1f)
        assertPlacement(Offset(1257f, 3f), Offset(0f, 6f), Offset(-6f, 0f), portrait)
        assertEquals(1077f, portrait.map(0f, 30f).x, 1e-2f)
        assertEquals(3f, portrait.map(0f, 209f).x, 1e-2f)
    }

    @Test
    fun `photo mode in portrait on a 4 by 3 sensor registers the mask with the preview`() {
        // Photo mode: 1920 x 1080 preview, 640 x 360 analysis (see FocusPeakingTest), active array
        // 4:3, sensor at 90 degrees, back camera, phone portrait, overlay sized to the 9:16 preview.
        val placement = focusPeakingPlacement(640, 360, isp, 1920, 1080, 4000, 3000, 90, 0, false, 1080f, 1920f, 1f, 1f)
        assertPlacement(Offset(1078.5f, 1.5f), Offset(0f, 3f), Offset(-3f, 0f), placement)
        // Mask rows fill the width edge to edge and columns the height, with no overhang.
        assertEquals(1080f, placement.map(0f, -.5f).x, 1e-2f)
        assertEquals(0f, placement.map(0f, 359.5f).x, 1e-2f)
        assertEquals(1920f, placement.map(639.5f, 0f).y, 1e-2f)
    }

    @Test
    fun `the gpu letterbox shrinks the mask about the centre`() {
        assertPlacement(Offset(252.5f, 127.5f), Offset(5f, 0f), Offset(0f, 5f),
            focusPeakingPlacement(100, 50, gpu, 1920, 1080, 4000, 3000, 0, 0, false, 1000f, 500f, .5f, .5f))
    }

    @Test
    fun `the front camera mirrors the mask`() {
        assertPlacement(Offset(995f, 5f), Offset(-10f, 0f), Offset(0f, 10f),
            focusPeakingPlacement(100, 50, gpu, 1920, 1080, 4000, 3000, 270, 0, true, 1000f, 500f, 1f, 1f))
    }

    @Test
    fun `the draw matrix is the placement`() {
        val placement = focusPeakingPlacement(320, 240, isp, 1920, 1080, 4000, 3000, 90, 90, true, 1080f, 1920f, .9f, .8f)
        for ((x, y) in listOf(0f to 0f, 320f to 0f, 0f to 240f, 123f to 45f)) {
            assertOffset(placement.map(x, y), placement.matrix().map(Offset(x, y)))
        }
    }
}
