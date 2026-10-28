/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GeometryTest {
    private val w = 384
    private val h = 216
    private val params = GeometrySolverParams(patchSize = 64)

    /** Reference is the base texture; the target shows the content at the similarity-mapped position. */
    private fun pair(tx: Double, ty: Double, scale: Double, rot: Double): Pair<LumaImage, LumaImage> {
        val ref = CalibrationTestSupport.render(w, h) { x, y -> x to y }
        // target(T(p)) = ref(p)  =>  target(q) = ref(T^-1(q))
        val c = cos(rot); val s = sin(rot)
        val tgt = CalibrationTestSupport.render(w, h) { qx, qy ->
            val ux = (qx - tx) / scale; val uy = (qy - ty) / scale
            (c * ux + s * uy) to (-s * ux + c * uy)
        }
        return ref to tgt
    }

    @Test fun identityStaysIdentity() {
        val (ref, tgt) = pair(0.0, 0.0, 1.0, 0.0)
        val fit = GeometrySolver.solve(ref, tgt, params)
        assertEquals(0f, fit.tx, 1e-3f); assertEquals(0f, fit.ty, 1e-3f)
        assertEquals(1f, fit.scale, 1e-3f); assertEquals(0f, fit.rotationRad, 1e-3f)
        assertFalse(fit.parallaxSuspect); assertFalse(fit.clamped)
        assertTrue(fit.usable)
    }

    @Test fun recoversShiftScaleAndRotation() {
        val rot = Math.toRadians(0.6)
        val (ref, tgt) = pair(2.5, -1.5, 1.012, rot)
        val fit = GeometrySolver.solve(ref, tgt, params)
        assertEquals(2.5 / w, fit.tx.toDouble(), 1.5 / w)
        assertEquals(-1.5 / h, fit.ty.toDouble(), 1.5 / h)
        assertEquals(1.012, fit.scale.toDouble(), 0.003)
        assertEquals(rot, fit.rotationRad.toDouble(), 0.003)
        assertTrue("inliers ${fit.inliers}/${fit.patchesUsed}", fit.inliers >= 18)
        assertTrue(fit.residualPx < 0.5f)
        assertFalse(fit.parallaxSuspect); assertFalse(fit.clamped)
        assertEquals(w.toFloat() / h, fit.aspect, 1e-5f)
    }

    @Test fun signConventionTargetShiftedRightGivesPositiveTx() {
        // Target content sits 3 px right of the reference's: sample the target 3 px right of the output pixel.
        val (ref, tgt) = pair(3.0, 0.0, 1.0, 0.0)
        assertTrue(GeometrySolver.solve(ref, tgt, params).tx * w > 2.5f)
    }

    @Test fun flagsParallaxWhenPatchShiftsDisagree() {
        val ref = CalibrationTestSupport.render(w, h) { x, y -> x to y }
        // Depth-dependent shift: vertical bands alternate between +4 px and -4 px.
        val tgt = CalibrationTestSupport.render(w, h) { x, y ->
            val band = Math.floor((x + w / 2.0) / 37.0).toInt()
            (x - if (band % 2 == 0) 4.0 else -4.0) to y
        }
        val fit = GeometrySolver.solve(ref, tgt, params)
        assertTrue("inliers ${fit.inliers}/${fit.patchesUsed} rms ${fit.residualPx}", fit.parallaxSuspect)
    }

    @Test fun clampsLargeMotion() {
        // 4 px of shift inside a 32 px patch is measurable but 8 px of width is > 3 % of 256? 4/256 = 1.6 %.
        val (ref, tgt) = pair(0.0, 0.0, 1.0, Math.toRadians(2.5))
        val fit = GeometrySolver.solve(ref, tgt, params.copy(maxShiftPx = 12f))
        assertTrue(fit.clamped)
        assertEquals(Math.toRadians(1.5), fit.rotationRad.toDouble(), 1e-6)
        val (ref2, tgt2) = pair(0.0, 0.0, 1.05, 0.0)
        val fit2 = GeometrySolver.solve(ref2, tgt2, params.copy(maxShiftPx = 12f))
        assertTrue(fit2.clamped); assertEquals(1.03f, fit2.scale, 1e-6f)
    }

    @Test fun flatImagesGiveUnusableIdentity() {
        val flat = LumaImage(w, h, FloatArray(w * h) { 0.4f })
        val fit = GeometrySolver.solve(flat, flat, params)
        assertFalse(fit.usable); assertEquals(0, fit.patchesUsed)
        assertEquals(1f, fit.scale, 0f)
    }

    // --- LensAlignment ---

    private fun apply(m: FloatArray, u: Float, v: Float) =
        (m[0] * u + m[4] * v + m[12]) to (m[1] * u + m[5] * v + m[13])

    @Test fun uvMatrixIdentityForIdentityInput() {
        val m = LensAlignment.uvMatrix(0f, 0f, 1f, 0f)
        val id = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        for (i in 0 until 16) assertEquals(id[i], m[i], 1e-6f)
        assertEquals(0f, LensAlignment.requiredMargin(0f, 0f, 1f, 0f), 0f)
    }

    @Test fun uvMatrixKeepsCornersInsideUnitSquare() {
        val cases = listOf(
            floatArrayOf(0.02f, -0.01f, 1f, 0f), floatArrayOf(0f, 0f, 1.03f, 0f), floatArrayOf(0f, 0f, 0.97f, 0.026f),
            floatArrayOf(-0.03f, 0.03f, 0.98f, -0.02f), floatArrayOf(0.01f, 0.02f, 1.02f, 0.015f),
        )
        for (aspect in listOf(1f, 16f / 9f)) for (c in cases) {
            val m = LensAlignment.uvMatrix(c[0], c[1], c[2], c[3], aspect = aspect)
            for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                val (su, sv) = apply(m, u, v)
                assertTrue("${c.toList()} corner $u,$v -> $su,$sv", su >= -1e-5f && su <= 1f + 1e-5f && sv >= -1e-5f && sv <= 1f + 1e-5f)
            }
            // Minimal: some corner touches the edge whenever zoom was needed.
            if (LensAlignment.requiredZoom(c[0], c[1], c[2], c[3], aspect) > 1f) {
                val touching = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).any { (u, v) ->
                    val (su, sv) = apply(m, u, v)
                    su < 1e-4f || su > 1f - 1e-4f || sv < 1e-4f || sv > 1f - 1e-4f
                }
                assertTrue(touching)
            }
        }
    }

    @Test fun uvMatrixTranslationAndExtraZoom() {
        // scale 1, tx > 0: output centre samples the target to the right (before the compensating zoom).
        val m = LensAlignment.uvMatrix(0.02f, 0f, 1f, 0f, extraZoom = 1f)
        val z = LensAlignment.requiredZoom(0.02f, 0f, 1f, 0f)
        assertEquals(1.04f, z, 1e-5f)
        assertEquals(0.5f + 0.02f / z, apply(m, 0.5f, 0.5f).first, 1e-5f)
        // extraZoom 2 samples a half-size centred region.
        val m2 = LensAlignment.uvMatrix(0f, 0f, 1f, 0f, extraZoom = 2f)
        assertEquals(0.25f, apply(m2, 0f, 0f).first, 1e-6f)
        assertEquals(0.75f, apply(m2, 1f, 1f).second, 1e-6f)
    }

    @Test fun uvMatrixRotationUsesAspect() {
        val aspect = 2f
        val m = LensAlignment.uvMatrix(0f, 0f, 1f, 0.1f, aspect = aspect)
        val z = LensAlignment.requiredZoom(0f, 0f, 1f, 0.1f, aspect)
        // Output pixel on the +x axis (c = (0.25, 0), pixel (0.25 * W, 0)): target point = R * p.
        val (su, sv) = apply(m, 0.75f, 0.5f)
        assertEquals((0.5f + 0.25f * cos(0.1f) / z), su, 1e-5f)
        assertEquals((0.5f + 0.25f * sin(0.1f) * aspect / z), sv, 1e-5f)
    }

    @Test fun solvedFitMapsReferenceToTarget() {
        // End to end: the fit's own convention, evaluated at an output pixel, lands on the target content.
        val (ref, tgt) = pair(2.0, 1.0, 1.01, Math.toRadians(0.4))
        val fit = GeometrySolver.solve(ref, tgt, params)
        val c = cos(fit.rotationRad.toDouble()); val s = sin(fit.rotationRad.toDouble())
        val px = 30.0; val py = -20.0
        val tx = fit.scale * (c * px - s * py) + fit.tx * w
        val ty = fit.scale * (s * px + c * py) + fit.ty * h
        assertEquals(CalibrationTestSupport.texture(px, py).toDouble(), CalibrationTestSupport.texture(
            // target(q) = ref(T^-1 q); evaluating the target at T(p) must give ref(p)
            ((tx - 2.0) / 1.01).let { ux -> val uy = (ty - 1.0) / 1.01; val r = Math.toRadians(0.4); cos(r) * ux + sin(r) * uy },
            ((ty - 1.0) / 1.01).let { uy -> val ux = (tx - 2.0) / 1.01; val r = Math.toRadians(0.4); -sin(r) * ux + cos(r) * uy },
        ).toDouble(), 0.02)
    }
}
