/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureProfileCurvesTest {
    private val razr = ImageProcessingCapabilities(toneMapModes = setOf(0, 1, 2), maxCurvePoints = 512)
    private val shaped = listOf(PictureProfile.FLAT, PictureProfile.CINE)

    @Test fun standardHasNoCurve() {
        assertNull(PictureProfileCurves.points(PictureProfile.STANDARD))
    }

    @Test fun curvesAreMonotoneClampedAndPinnedAtTheEnds() {
        for (profile in shaped) for (count in listOf(16, 64, 96, 128)) {
            val pts = requireNotNull(PictureProfileCurves.points(profile, count))
            assertEquals(count * 2, pts.size)
            assertEquals(0f, pts[0], 0f); assertEquals(0f, pts[1], 0f)
            assertEquals(1f, pts[pts.size - 2], 0f); assertEquals(1f, pts[pts.size - 1], 0f)
            for (i in 0 until count) {
                assertTrue(pts[i * 2 + 1] in 0f..1f)
                if (i > 0) {
                    assertTrue("$profile input $i", pts[i * 2] > pts[i * 2 - 2])
                    assertTrue("$profile output $i", pts[i * 2 + 1] >= pts[i * 2 - 1])
                }
            }
        }
    }

    @Test fun flatLiftsShadowsAndCineHasMoreMidContrast() {
        val flat = requireNotNull(PictureProfileCurves.points(PictureProfile.FLAT, 101))
        val cine = requireNotNull(PictureProfileCurves.points(PictureProfile.CINE, 101))
        fun y(p: FloatArray, percent: Int) = p[percent * 2 + 1]
        assertTrue(y(flat, 10) > 0.10f)
        assertTrue(y(cine, 10) < 0.10f)
        assertTrue(y(cine, 60) - y(cine, 40) > y(flat, 60) - y(flat, 40))
    }

    @Test fun cineShoulderEasesIntoWhite() {
        val cine = requireNotNull(PictureProfileCurves.points(PictureProfile.CINE, 101))
        val midSlope = (cine[60 * 2 + 1] - cine[40 * 2 + 1]) / 0.2f
        val shoulderSlope = (cine[100 * 2 + 1] - cine[95 * 2 + 1]) / 0.05f
        assertTrue(shoulderSlope < midSlope)
    }

    @Test fun pointCountIsCappedByTheCameraLimit() {
        val small = razr.copy(maxCurvePoints = 32)
        assertEquals(32 * 2, PictureProfile.CINE.resolveCurve(small, ToneProfileContext())?.size)
        assertEquals(PictureProfileCurves.DEFAULT_POINT_COUNT * 2, PictureProfile.FLAT.resolveCurve(razr, ToneProfileContext())?.size)
    }

    @Test fun gatingKeepsTheTemplateWhereTheCurveMustNotApply() {
        assertNotNull(PictureProfile.CINE.resolveCurve(razr, ToneProfileContext()))
        assertNull(PictureProfile.STANDARD.resolveCurve(razr, ToneProfileContext()))
        assertNull(PictureProfile.CINE.resolveCurve(razr, ToneProfileContext(logOrHlg = true)))
        assertNull(PictureProfile.CINE.resolveCurve(razr, ToneProfileContext(constrainedHfr = true)))
        assertNull(PictureProfile.CINE.resolveCurve(ImageProcessingCapabilities(), ToneProfileContext()))
        assertNull(PictureProfile.CINE.resolveCurve(razr.copy(toneMapModes = setOf(1, 2)), ToneProfileContext()))
        assertNull(PictureProfile.CINE.resolveCurve(razr.copy(maxCurvePoints = 8), ToneProfileContext()))
    }

    @Test fun capabilitiesNeedContrastCurveAndEnoughPoints() {
        assertTrue(razr.supportsPictureProfiles)
        assertTrue(!ImageProcessingCapabilities().supportsPictureProfiles)
    }
}
