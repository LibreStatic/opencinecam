/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * GL matrix for a [GeometryFit]. Output UV `q` is mapped to the UV where the target is sampled:
 * `sample = 0.5 + S(q - 0.5) / zoom` with `S(c) = (scale * R * (c.u * aspect, c.v) / (aspect, 1)) + (tx, ty)`
 * (see [GeometryFit] for the convention). `zoom` is the minimal uniform magnification (>= 1) that
 * keeps the mapped unit square inside [0, 1], times the caller's [extraZoom], so no edge is smeared.
 * The extra zoom narrows the sampled region (zoom > 1 samples a smaller centred region).
 */
object LensAlignment {
    private const val EPS = 1e-6f

    /** Minimal zoom (>= 1) needed so the mapped corners stay inside the unit square. */
    fun requiredZoom(tx: Float, ty: Float, scale: Float, rotationRad: Float, aspect: Float = 1f): Float {
        val l = linear(scale, rotationRad, aspect)
        var maxAbs = 0f
        for (cu in floatArrayOf(-0.5f, 0.5f)) for (cv in floatArrayOf(-0.5f, 0.5f)) {
            maxAbs = maxOf(maxAbs, abs(l[0] * cu + l[1] * cv + tx), abs(l[2] * cu + l[3] * cv + ty))
        }
        val z = 2f * maxAbs
        return if (z < 1f + EPS) 1f else z
    }

    /** The crop margin, per side as a fraction of the frame, the alignment costs: `0.5 * (1 - 1 / zoom)`. */
    fun requiredMargin(tx: Float, ty: Float, scale: Float, rotationRad: Float, aspect: Float = 1f): Float =
        0.5f * (1f - 1f / requiredZoom(tx, ty, scale, rotationRad, aspect))

    /** Column-major 4x4 acting on (u, v, 0, 1); identity for an identity fit and [extraZoom] = 1. */
    fun uvMatrix(
        tx: Float,
        ty: Float,
        scale: Float,
        rotationRad: Float,
        extraZoom: Float = 1f,
        aspect: Float = 1f,
    ): FloatArray {
        val zoom = requiredZoom(tx, ty, scale, rotationRad, aspect) * extraZoom
        val l = linear(scale, rotationRad, aspect)
        val m00 = l[0] / zoom; val m01 = l[1] / zoom
        val m10 = l[2] / zoom; val m11 = l[3] / zoom
        val bu = 0.5f + tx / zoom - 0.5f * (m00 + m01)
        val bv = 0.5f + ty / zoom - 0.5f * (m10 + m11)
        return floatArrayOf(
            m00, m10, 0f, 0f,
            m01, m11, 0f, 0f,
            0f, 0f, 1f, 0f,
            bu, bv, 0f, 1f,
        )
    }

    fun uvMatrix(fit: GeometryFit, extraZoom: Float = 1f): FloatArray =
        uvMatrix(fit.tx, fit.ty, fit.scale, fit.rotationRad, extraZoom, fit.aspect)

    /** Row-major 2x2 [m00 m01 m10 m11] of the UV-space linear part. */
    private fun linear(scale: Float, rot: Float, aspect: Float): FloatArray {
        val c = cos(rot); val s = sin(rot)
        return floatArrayOf(scale * c, -scale * s / aspect, scale * s * aspect, scale * c)
    }
}
