/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Small similarity alignment of a target lens to the reference lens.
 *
 * Convention: take centred pixel coordinates `p` (origin at the image centre, +x right, +y down).
 * The point of the TARGET image that shows the content the REFERENCE shows at `p` is
 * `scale * R(rotationRad) * p + (tx * width, ty * height)`, with `R` the rotation
 * `[cos -sin; sin cos]` (y down, so positive angles turn clockwise on screen). Equivalently, for an
 * output UV `q`, sample the target at `0.5 + that point / size`; resampling the target through it
 * reproduces the reference. [tx] and [ty] are in UV units (fractions of width and height), [aspect]
 * is width / height, which the rotation needs to be a true rotation. [scale] > 1 means the target
 * shows a wider field of view than the reference. [residualPx] is the RMS inlier residual.
 */
data class GeometryFit(
    val tx: Float,
    val ty: Float,
    val scale: Float,
    val rotationRad: Float,
    val residualPx: Float,
    val inliers: Int,
    val patchesUsed: Int,
    val parallaxSuspect: Boolean,
    val clamped: Boolean,
    val aspect: Float = 16f / 9f,
) {
    /** Whether enough patches agreed for the fit to mean anything. */
    val usable: Boolean get() = inliers >= GeometrySolver.MIN_INLIERS

    companion object {
        fun identity(aspect: Float = 16f / 9f) = GeometryFit(0f, 0f, 1f, 0f, 0f, 0, 0, false, false, aspect)
    }
}

/**
 * Tuning of [GeometrySolver]. Patches are [patchSize] square (a power of two) on a [gridCols] x
 * [gridRows] grid; a patch is dropped when its luma standard deviation is under [minTexture]
 * (either image), its [PhaseShift.peakRatio] under [minPeakRatio], or its shift is over
 * [maxShiftPx]. A patch is an inlier within [inlierPx] of the model. The fit is flagged as parallax
 * when fewer than [parallaxInlierFraction] of the used patches agree or the inlier RMS exceeds
 * [parallaxRmsPx]. Results are clamped to [maxTranslationUv], [minScale]..[maxScale] and
 * [maxRotationRad].
 */
data class GeometrySolverParams(
    val gridCols: Int = 6,
    val gridRows: Int = 4,
    val patchSize: Int = 64,
    val minTexture: Float = 0.01f,
    val minPeakRatio: Float = 2f,
    val maxShiftPx: Float = patchSize / 3f,
    val inlierPx: Float = 1f,
    val parallaxInlierFraction: Float = 0.75f,
    val parallaxRmsPx: Float = 0.5f,
    val maxTranslationUv: Float = 0.03f,
    val minScale: Float = 0.97f,
    val maxScale: Float = 1.03f,
    val maxRotationRad: Float = (1.5 * PI / 180.0).toFloat(),
)

object GeometrySolver {
    const val MIN_INLIERS = 5

    private class Obs(val x: Double, val y: Double, val dx: Double, val dy: Double)

    /** Fits the similarity that maps [reference] content onto [target]; see [GeometryFit] for the convention. */
    fun solve(reference: LumaImage, target: LumaImage, params: GeometrySolverParams = GeometrySolverParams()): GeometryFit {
        require(reference.width == target.width && reference.height == target.height) { "Images must match in size." }
        val w = reference.width; val h = reference.height
        val ps = params.patchSize
        require(w >= ps && h >= ps) { "Image smaller than a patch." }
        val aspect = w.toFloat() / h
        val obs = ArrayList<Obs>()
        for (gy in 0 until params.gridRows) for (gx in 0 until params.gridCols) {
            val x0 = if (params.gridCols == 1) (w - ps) / 2 else Math.round(gx * (w - ps).toDouble() / (params.gridCols - 1)).toInt()
            val y0 = if (params.gridRows == 1) (h - ps) / 2 else Math.round(gy * (h - ps).toDouble() / (params.gridRows - 1)).toInt()
            val a = patch(reference, x0, y0, ps)
            val b = patch(target, x0, y0, ps)
            if (stdDev(a) < params.minTexture || stdDev(b) < params.minTexture) continue
            val s = PhaseCorrelation.estimate(a, b, ps)
            if (s.peakRatio < params.minPeakRatio || hypot(s.dx, s.dy) > params.maxShiftPx) continue
            val cx = x0 + ps / 2.0 + 0.5 - w / 2.0
            val cy = y0 + ps / 2.0 + 0.5 - h / 2.0
            obs += Obs(cx, cy, s.dx.toDouble(), s.dy.toDouble())
        }
        if (obs.size < MIN_INLIERS) return GeometryFit.identity(aspect).copy(patchesUsed = obs.size)

        // Exhaustive two-patch hypotheses (a similarity is fixed by two correspondences).
        var best: DoubleArray? = null
        var bestInliers = -1
        var bestCost = Double.MAX_VALUE
        for (i in obs.indices) for (j in i + 1 until obs.size) {
            val m = fromPair(obs[i], obs[j]) ?: continue
            var count = 0; var cost = 0.0
            for (o in obs) {
                val e = residual(m, o)
                if (e <= params.inlierPx) { count++; cost += e * e }
            }
            if (count > bestInliers || (count == bestInliers && cost < bestCost)) { best = m; bestInliers = count; bestCost = cost }
        }
        var model = best ?: return GeometryFit.identity(aspect).copy(patchesUsed = obs.size)
        var inl = obs.filter { residual(model, it) <= params.inlierPx }
        repeat(2) {
            if (inl.size >= 3) {
                model = leastSquares(inl) ?: model
                inl = obs.filter { residual(model, it) <= params.inlierPx }
            }
        }
        if (inl.size < 3) return GeometryFit.identity(aspect).copy(patchesUsed = obs.size)
        model = leastSquares(inl) ?: model
        val rms = sqrt(inl.sumOf { residual(model, it).let { e -> e * e } } / inl.size)

        val scale = hypot(model[0], model[1])
        val rot = atan2(model[1], model[0])
        val tx = model[2] / w
        val ty = model[3] / h
        val cScale = scale.toFloat().coerceIn(params.minScale, params.maxScale)
        val cRot = rot.toFloat().coerceIn(-params.maxRotationRad, params.maxRotationRad)
        val cTx = tx.toFloat().coerceIn(-params.maxTranslationUv, params.maxTranslationUv)
        val cTy = ty.toFloat().coerceIn(-params.maxTranslationUv, params.maxTranslationUv)
        val clamped = cScale != scale.toFloat() || cRot != rot.toFloat() || cTx != tx.toFloat() || cTy != ty.toFloat()
        val parallax = inl.size < params.parallaxInlierFraction * obs.size || rms > params.parallaxRmsPx
        return GeometryFit(cTx, cTy, cScale, cRot, rms.toFloat(), inl.size, obs.size, parallax, clamped, aspect)
    }

    /** Model [a, b, tx, ty] with target point = (a x - b y + tx, b x + a y + ty); a = s cos, b = s sin. */
    private fun fromPair(p: Obs, q: Obs): DoubleArray? {
        val ux = q.x - p.x; val uy = q.y - p.y
        val d = ux * ux + uy * uy
        if (d < 1e-6) return null
        val vx = (q.x + q.dx) - (p.x + p.dx); val vy = (q.y + q.dy) - (p.y + p.dy)
        // complex division v / u
        val a = (vx * ux + vy * uy) / d
        val b = (vy * ux - vx * uy) / d
        val tx = p.x + p.dx - (a * p.x - b * p.y)
        val ty = p.y + p.dy - (b * p.x + a * p.y)
        return doubleArrayOf(a, b, tx, ty)
    }

    private fun residual(m: DoubleArray, o: Obs): Double {
        val ex = m[0] * o.x - m[1] * o.y + m[2] - (o.x + o.dx)
        val ey = m[1] * o.x + m[0] * o.y + m[3] - (o.y + o.dy)
        return hypot(ex, ey)
    }

    private fun leastSquares(obs: List<Obs>): DoubleArray? {
        val ata = Array(4) { DoubleArray(5) }
        for (o in obs) {
            val rows = arrayOf(
                doubleArrayOf(o.x, -o.y, 1.0, 0.0, o.x + o.dx),
                doubleArrayOf(o.y, o.x, 0.0, 1.0, o.y + o.dy),
            )
            for (r in rows) for (i in 0 until 4) for (j in 0 until 5) ata[i][j] += r[i] * r[j]
        }
        for (c in 0 until 4) {
            var piv = c
            for (r in c + 1 until 4) if (abs(ata[r][c]) > abs(ata[piv][c])) piv = r
            if (abs(ata[piv][c]) < 1e-12) return null
            val t = ata[c]; ata[c] = ata[piv]; ata[piv] = t
            for (r in 0 until 4) {
                if (r == c) continue
                val f = ata[r][c] / ata[c][c]
                for (j in c until 5) ata[r][j] -= f * ata[c][j]
            }
        }
        return DoubleArray(4) { ata[it][4] / ata[it][it] }
    }

    private fun patch(img: LumaImage, x0: Int, y0: Int, size: Int): FloatArray =
        FloatArray(size * size) { img.values[(y0 + it / size) * img.width + x0 + it % size] }

    private fun stdDev(v: FloatArray): Float {
        var m = 0.0
        for (x in v) m += x
        m /= v.size
        var s = 0.0
        for (x in v) s += (x - m) * (x - m)
        return sqrt(s / v.size).toFloat()
    }

}
