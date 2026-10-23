/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import com.librestatic.opencinecam.core.model.CalibrationSessionKind
import com.librestatic.opencinecam.core.model.CalibrationSet
import com.librestatic.opencinecam.core.model.CalibrationSourcePath
import com.librestatic.opencinecam.core.model.LensCorrection
import com.librestatic.opencinecam.core.model.Similarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoLensCorrectionTest {
    private fun lens(id: String, align: Similarity = Similarity.IDENTITY, ev: Float = 0f, gains: List<Float> = listOf(1f, 1f, 1f)) =
        LensCorrection.identity(id).copy(evOffset = ev, align = align, gains = gains)

    private fun set(source: CalibrationSourcePath = CalibrationSourcePath.SDR, vararg lenses: LensCorrection) = CalibrationSet(
        fingerprint = "fp", logicalId = "0", sessionKind = CalibrationSessionKind.LOGICAL, referencePhysicalId = "2",
        sourcePath = source, createdAtMs = 1L, illuminantHint = null, lenses = lenses.toList(),
    )

    /** Maps a source pixel centre through a row-major 3x3, the way Canvas does. */
    private fun map(m: FloatArray, x: Float, y: Float) = floatArrayOf(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])

    @Test fun glShiftMovesJpegContentTheSameWayOnScreen() {
        // GL ty > 0: the output samples a source v above centre, so the picture moves down on screen.
        // On the top-down JPEG grid that is larger y, although the stored v flips sign.
        val w = 400; val h = 300
        val m = PhotoAlignment.bitmapMatrix(0f, 0.05f, 1f, 0f, 1f, w, h)
        val moved = map(m, 200f, 150f)
        assertEquals(200f, moved[0], 1e-3f)
        assertEquals(150f + 0.05f * h, moved[1], 1e-3f)
        // And a positive tx moves the picture left (the output samples to the right), as in GL.
        val left = map(PhotoAlignment.bitmapMatrix(0.05f, 0f, 1f, 0f, 1f, w, h), 200f, 150f)
        assertEquals(200f - 0.05f * w, left[0], 1e-3f)
        assertEquals(150f, left[1], 1e-3f)
    }

    @Test fun rotationIsConjugatedByTheFlip() {
        val w = 200; val h = 200
        val jpeg = PhotoAlignment.bitmapMatrix(0f, 0f, 1f, 0.1f, 1f, w, h)
        // Rotation in GL (v up) is the opposite turn on the v-down grid: the matrix equals the
        // GL-convention matrix with the flipped fit.
        val flipped = PhotoAlignment.bitmapMatrix(0f, 0f, 1f, -0.1f, 1f, w, h)
        assertEquals(-jpeg[1], flipped[1], 1e-6f)
        assertEquals(-jpeg[3], flipped[3], 1e-6f)
        // The linear part inverts the UV matrix of the flipped fit.
        val uv = LensAlignment.uvMatrix(0f, 0f, 1f, -0.1f, 1f, 1f)
        val det = uv[0] * uv[5] - uv[4] * uv[1]
        assertEquals(uv[5] / det, jpeg[0], 1e-5f)
    }

    @Test fun zoomMagnifiesAboutTheCentre() {
        val m = PhotoAlignment.bitmapMatrix(0f, 0f, 1f, 0f, 1.25f, 640, 480)
        val centre = map(m, 320f, 240f)
        assertEquals(320f, centre[0], 1e-3f); assertEquals(240f, centre[1], 1e-3f)
        val corner = map(m, 0f, 0f)
        assertEquals(320f - 1.25f * 320f, corner[0], 1e-2f)
        assertEquals(240f - 1.25f * 240f, corner[1], 1e-2f)
    }

    @Test fun mappedImageCoversTheFrame() {
        // With the required zoom every output corner samples inside the source: the inverse of the matrix keeps corners in bounds.
        val s = Similarity(0.01f, -0.02f, 1.01f, 0.005f)
        val p = PhotoLensCorrection.resolve(set(lenses = arrayOf(lens("2"), lens("3", align = s))), true, "3", 400, 300).plan!!
        val m = android(p.bitmapMatrix!!)
        for ((ox, oy) in listOf(0f to 0f, 400f to 0f, 0f to 300f, 400f to 300f)) {
            val src = map(m, ox, oy)
            assertTrue("x ${src[0]}", src[0] >= -0.5f && src[0] <= 400.5f)
            assertTrue("y ${src[1]}", src[1] >= -0.5f && src[1] <= 300.5f)
        }
    }

    /** The source position an output pixel samples: the inverse of the (affine) bitmap matrix. */
    private fun android(m: FloatArray): FloatArray {
        val det = m[0] * m[4] - m[1] * m[3]
        val ia = m[4] / det; val ib = -m[1] / det; val ic = -m[3] / det; val id = m[0] / det
        return floatArrayOf(ia, ib, -(ia * m[2] + ib * m[5]), ic, id, -(ic * m[2] + id * m[5]))
            .let { floatArrayOf(it[0], it[1], it[2], it[3], it[4], it[5], 0f, 0f, 1f) }
    }

    @Test fun noSetUnknownOrMissingLensGivesNoPlan() {
        val s = set(lenses = arrayOf(lens("2"), lens("3", ev = 0.2f)))
        assertNull(PhotoLensCorrection.resolve(null, false, "3", 400, 300).plan)
        assertNull(PhotoLensCorrection.resolve(s, false, null, 400, 300).plan)
        assertNotNull(PhotoLensCorrection.resolve(s, false, null, 400, 300).note)
        assertNull(PhotoLensCorrection.resolve(s, false, "9", 400, 300).plan)
        assertNotNull(PhotoLensCorrection.resolve(s, false, "9", 400, 300).note)
    }

    @Test fun identityLensNeedsAPlanOnlyWhenAlignmentZoomsIt() {
        val s = set(lenses = arrayOf(lens("2"), lens("3", align = Similarity(0.02f, 0f, 1f, 0f))))
        assertNull(PhotoLensCorrection.resolve(s, false, "2", 400, 300).plan)
        val aligned = PhotoLensCorrection.resolve(s, true, "2", 400, 300).plan
        assertNotNull("the reference shares the session zoom", aligned)
        assertFalse(aligned!!.hasColor)
        assertNotNull(aligned.bitmapMatrix)
    }

    @Test fun sessionZoomIsSharedAcrossLenses() {
        val s = set(lenses = arrayOf(lens("2"), lens("3", align = Similarity(0.03f, 0.02f, 1f, 0f))))
        val a = PhotoLensCorrection.resolve(s, true, "2", 400, 300).plan!!.bitmapMatrix!!
        val b = PhotoLensCorrection.resolve(s, true, "3", 400, 300).plan!!.bitmapMatrix!!
        // The scale part of the two matrices is the same: only the shift differs.
        assertEquals(a[0], b[0], 1e-5f); assertEquals(a[4], b[4], 1e-5f)
    }

    @Test fun alignmentOffLeavesGeometryAlone() {
        val s = set(lenses = arrayOf(lens("2"), lens("3", align = Similarity(0.03f, 0f, 1f, 0f), ev = 0.3f)))
        val p = PhotoLensCorrection.resolve(s, false, "3", 400, 300).plan!!
        assertNull(p.bitmapMatrix)
        assertTrue(p.hasColor)
    }

    @Test fun hlgSetWarnsButStillCorrectsWithSdrCurve() {
        val s = set(CalibrationSourcePath.HLG, lens("2"), lens("3", ev = 0.3f))
        val r = PhotoLensCorrection.resolve(s, false, "3", 400, 300)
        assertNotNull(r.plan)
        assertNotNull(r.note)
    }

    @Test fun planColourMatchesThePhotoColorPass() {
        val s = set(lenses = arrayOf(lens("2"), lens("3", ev = -0.3f, gains = listOf(1.05f, 0.99f, 0.93f))))
        val plan = PhotoLensCorrection.resolve(s, false, "3", 4, 4).plan!!
        val px = IntArray(16) { 0xFF000000.toInt() or (it * 16 shl 16) or (200 - it * 8 shl 8) or (40 + it * 5) }
        val want = px.copyOf()
        PhotoColorPass.applyToArgb(want, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(1.05f, 0.99f, 0.93f), -0.3f, TransferCurve.SDR)
        plan.applyColor(px)
        assertTrue(px.contentEquals(want))
        assertFalse(px.contentEquals(IntArray(16) { 0 }))
    }
}
