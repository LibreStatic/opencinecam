/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Result of the colour fit. The model, all in scene-linear RGB, is
 * `corrected = 2^evOffset * matrix * (gains * target) ~ reference`. [matrix] is row-major with every
 * row summing to 1 (neutral-preserving) and [gains] are normalised so that
 * 0.2126 g_r + 0.7152 g_g + 0.0722 g_b = 1. [residualDeltaE] and [residualDeltaEBefore] are the mean
 * CIE76 differences over the used cells (Lab from linear Rec.709, D65), after and before correction.
 */
data class ColorFit(
    val matrix: FloatArray,
    val gains: FloatArray,
    val evOffset: Float,
    val residualDeltaE: Float,
    val residualDeltaEBefore: Float,
    val cellsUsed: Int,
    val diagonalFallback: Boolean,
    val conditionNumber: Float,
) {
    companion object {
        val IDENTITY_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        /** The do-nothing fit. */
        fun identity() = ColorFit(IDENTITY_MATRIX.copyOf(), floatArrayOf(1f, 1f, 1f), 0f, 0f, 0f, 0, true, 1f)
    }
}

/**
 * Tuning of [ColorSolver]. [ridgeLambda] pulls the matrix toward identity, relative to the trace of
 * the normal equations. Cells are rejected when either frame clips more than [maxClipFraction],
 * when linear Y is below [minLuma], or when their relative texture (sqrt(variance) / Y, the larger
 * of the two frames) is above the [variancePercentile] of the candidates. The matrix falls back to
 * identity under [minCells] valid cells or a condition number above [maxCondition].
 */
data class ColorSolverParams(
    val ridgeLambda: Float = 1e-3f,
    val minCells: Int = 16,
    val maxClipFraction: Float = 0.02f,
    val minLuma: Float = 0.01f,
    val variancePercentile: Float = 0.8f,
    val maxCondition: Float = 1e3f,
    val huberIterations: Int = 3,
    val huberK: Float = 1.345f,
)

object ColorSolver {
    /** Fits the correction that maps [target] cell colours to [reference] ones; the lists pair by index. */
    fun solve(reference: List<CellStat>, target: List<CellStat>, params: ColorSolverParams = ColorSolverParams()): ColorFit {
        require(reference.size == target.size) { "Reference and target need the same grid." }
        val cells = selectCells(reference, target, params)
        val n = cells.size
        if (n == 0) return ColorFit.identity()
        val t = Array(n) { doubleArrayOf(target[cells[it]].r.toDouble(), target[cells[it]].g.toDouble(), target[cells[it]].b.toDouble()) }
        val r = Array(n) { doubleArrayOf(reference[cells[it]].r.toDouble(), reference[cells[it]].g.toDouble(), reference[cells[it]].b.toDouble()) }
        val w = DoubleArray(n) { 1.0 }

        // Exposure and per-channel gains come from the weighted sums, then the condition of the
        // gain-corrected target decides whether a full matrix is trustworthy at all.
        var (ev, g) = evAndGains(r, t, w)
        var s = scaled(t, ev, g)
        val cond = conditionNumber(s, w)
        val fallback = n < params.minCells || cond > params.maxCondition || cond.isNaN()
        var m = identity3()
        if (!fallback) {
            for (iter in 0..params.huberIterations) {
                val eg = evAndGains(r, t, w)
                ev = eg.first; g = eg.second
                s = scaled(t, ev, g)
                m = fitMatrix(r, s, w, params.ridgeLambda.toDouble())
                if (iter == params.huberIterations) break
                val res = DoubleArray(n) { distance(r[it], mul(m, s[it])) }
                val scale = (1.4826 * median(res)).coerceAtLeast(1e-4) * params.huberK
                for (i in 0 until n) w[i] = if (res[i] <= scale) 1.0 else scale / res[i]
            }
        }
        var before = 0.0
        var after = 0.0
        for (i in 0 until n) {
            before += deltaE(r[i], t[i])
            after += deltaE(r[i], mul(m, s[i]))
        }
        return ColorFit(
            matrix = FloatArray(9) { m[it / 3][it % 3].toFloat() },
            gains = FloatArray(3) { g[it].toFloat() },
            evOffset = ev.toFloat(),
            residualDeltaE = (after / n).toFloat(),
            residualDeltaEBefore = (before / n).toFloat(),
            cellsUsed = n,
            diagonalFallback = fallback,
            conditionNumber = cond.toFloat(),
        )
    }

    private fun selectCells(reference: List<CellStat>, target: List<CellStat>, p: ColorSolverParams): List<Int> {
        val candidates = reference.indices.filter {
            reference[it].clipFraction <= p.maxClipFraction && target[it].clipFraction <= p.maxClipFraction &&
                reference[it].luma >= p.minLuma && target[it].luma >= p.minLuma
        }
        if (candidates.isEmpty()) return candidates
        val texture = candidates.map {
            maxOf(
                sqrt(reference[it].lumaVariance) / reference[it].luma,
                sqrt(target[it].lumaVariance) / target[it].luma,
            )
        }
        val sorted = texture.sorted()
        val limit = sorted[((p.variancePercentile.coerceIn(0f, 1f)) * (sorted.size - 1)).toInt()]
        return candidates.filterIndexed { i, _ -> texture[i] <= limit }
    }

    private fun evAndGains(r: Array<DoubleArray>, t: Array<DoubleArray>, w: DoubleArray): Pair<Double, DoubleArray> {
        val sr = DoubleArray(3); val st = DoubleArray(3)
        for (i in r.indices) for (c in 0 until 3) { sr[c] += w[i] * r[i][c]; st[c] += w[i] * t[i][c] }
        val yr = lumaOf(sr); val yt = lumaOf(st)
        val ev = ln(yr / yt) / ln(2.0)
        val raw = DoubleArray(3) { sr[it] / st[it] }
        val norm = lumaOf(raw)
        return ev to DoubleArray(3) { raw[it] / norm }
    }

    private fun scaled(t: Array<DoubleArray>, ev: Double, g: DoubleArray): Array<DoubleArray> {
        val k = 2.0.pow(ev)
        return Array(t.size) { i -> DoubleArray(3) { c -> k * g[c] * t[i][c] } }
    }

    /** Ridge fit toward identity with each row summing to 1: the third coefficient is 1 - a - b. */
    private fun fitMatrix(r: Array<DoubleArray>, s: Array<DoubleArray>, w: DoubleArray, lambda: Double): Array<DoubleArray> {
        val m = Array(3) { DoubleArray(3) }
        var a00 = 0.0; var a01 = 0.0; var a11 = 0.0
        for (i in s.indices) {
            val d0 = s[i][0] - s[i][2]; val d1 = s[i][1] - s[i][2]
            a00 += w[i] * d0 * d0; a01 += w[i] * d0 * d1; a11 += w[i] * d1 * d1
        }
        val mu = lambda * (a00 + a11) / 2 + 1e-12
        val m00 = a00 + mu; val m11 = a11 + mu
        val det = m00 * m11 - a01 * a01
        for (row in 0 until 3) {
            var b0 = 0.0; var b1 = 0.0
            for (i in s.indices) {
                val d0 = s[i][0] - s[i][2]; val d1 = s[i][1] - s[i][2]
                val y = r[i][row] - s[i][2]
                b0 += w[i] * d0 * y; b1 += w[i] * d1 * y
            }
            val p0 = if (row == 0) 1.0 else 0.0
            val p1 = if (row == 1) 1.0 else 0.0
            b0 += mu * p0; b1 += mu * p1
            val x0 = (b0 * m11 - a01 * b1) / det
            val x1 = (m00 * b1 - a01 * b0) / det
            m[row][0] = x0; m[row][1] = x1; m[row][2] = 1.0 - x0 - x1
        }
        return m
    }

    /** cond of SᵀWS from its three eigenvalues (Jacobi iteration). */
    private fun conditionNumber(s: Array<DoubleArray>, w: DoubleArray): Double {
        val a = Array(3) { DoubleArray(3) }
        for (i in s.indices) for (x in 0 until 3) for (y in 0 until 3) a[x][y] += w[i] * s[i][x] * s[i][y]
        repeat(30) {
            for ((p, q) in listOf(0 to 1, 0 to 2, 1 to 2)) {
                if (abs(a[p][q]) < 1e-18) continue
                val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                val tt = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(tt * tt + 1); val sn = tt * c
                for (k in 0 until 3) { val akp = a[k][p]; val akq = a[k][q]; a[k][p] = c * akp - sn * akq; a[k][q] = sn * akp + c * akq }
                for (k in 0 until 3) { val apk = a[p][k]; val aqk = a[q][k]; a[p][k] = c * apk - sn * aqk; a[q][k] = sn * apk + c * aqk }
            }
        }
        val ev = doubleArrayOf(a[0][0], a[1][1], a[2][2])
        val max = ev.max(); val min = ev.min()
        return if (min <= max * 1e-15) Double.POSITIVE_INFINITY else max / min
    }

    private fun identity3() = Array(3) { i -> DoubleArray(3) { if (it == i) 1.0 else 0.0 } }
    private fun lumaOf(v: DoubleArray) = 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]
    private fun mul(m: Array<DoubleArray>, v: DoubleArray) = DoubleArray(3) { m[it][0] * v[0] + m[it][1] * v[1] + m[it][2] * v[2] }
    private fun distance(a: DoubleArray, b: DoubleArray) = sqrt((0 until 3).sumOf { (a[it] - b[it]).pow(2) })
    private fun median(v: DoubleArray): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun deltaE(a: DoubleArray, b: DoubleArray): Double {
        val la = lab(a); val lb = lab(b)
        return sqrt((0 until 3).sumOf { (la[it] - lb[it]).pow(2) })
    }

    /** CIE Lab of a linear Rec.709 colour, D65 white with Y = 1. */
    private fun lab(rgb: DoubleArray): DoubleArray {
        val r = rgb[0].coerceAtLeast(0.0); val g = rgb[1].coerceAtLeast(0.0); val b = rgb[2].coerceAtLeast(0.0)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        fun f(v: Double) = if (v > 216.0 / 24389.0) cbrt(v) else (24389.0 / 27.0 * v + 16) / 116
        val fx = f(x); val fy = f(y); val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

}

/** Applies a solved correction to scene-linear colour. */
object LensColorTransform {
    /** `2^ev * matrix * (gains * rgb)` for a linear RGB triple; the result is not clamped. */
    fun apply(rgbLinear: FloatArray, matrix: FloatArray, gains: FloatArray, ev: Float): FloatArray {
        val k = 2f.pow(ev)
        val r = rgbLinear[0] * gains[0] * k
        val g = rgbLinear[1] * gains[1] * k
        val b = rgbLinear[2] * gains[2] * k
        return floatArrayOf(
            matrix[0] * r + matrix[1] * g + matrix[2] * b,
            matrix[3] * r + matrix[4] * g + matrix[5] * b,
            matrix[6] * r + matrix[7] * g + matrix[8] * b,
        )
    }

    fun apply(rgbLinear: FloatArray, fit: ColorFit): FloatArray = apply(rgbLinear, fit.matrix, fit.gains, fit.evOffset)
}
