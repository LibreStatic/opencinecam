/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Deterministic synthetic scenes shared by the calibration tests. */
internal object CalibrationTestSupport {
    private val waves: List<DoubleArray> = Random(11).let { rng ->
        List(40) {
            val k = 0.15 + 1.2 * rng.nextDouble()
            val a = 2 * PI * rng.nextDouble()
            doubleArrayOf(k * cos(a), k * sin(a), 2 * PI * rng.nextDouble(), 0.16 / (0.5 + k))
        }
    }

    /** A richly textured analytic image (0..1) at continuous centred coordinates; fixed seed. */
    fun texture(x: Double, y: Double): Float {
        var v = 0.5
        for (w in waves) v += w[3] * sin(w[0] * x + w[1] * y + w[2])
        return v.toFloat().coerceIn(0f, 1f)
    }

    /** Renders a [w] x [h] luma image where sample (i, j) is texture at the given mapped centred position. */
    fun render(w: Int, h: Int, map: (Double, Double) -> Pair<Double, Double>): LumaImage {
        val v = FloatArray(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            val (x, y) = map(i + 0.5 - w / 2.0, j + 0.5 - h / 2.0)
            v[j * w + i] = texture(x, y)
        }
        return LumaImage(w, h, v)
    }

    /** Frame of [cols] x [rows] flat linear-colour cells of [cell] pixels, with optional per-pixel linear noise. */
    fun cellFrame(
        colors: List<FloatArray>,
        cols: Int = 12,
        rows: Int = 8,
        cell: Int = 8,
        noise: Float = 0f,
        seed: Int = 1,
        curve: TransferCurve = TransferCurve.SDR,
    ): RgbaFrame {
        val rng = Random(seed)
        val w = cols * cell; val h = rows * cell
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val c = colors[(y / cell) * cols + x / cell]
            for (k in 0 until 3) {
                val lin = c[k] * (1f + noise * (rng.nextFloat() * 2f - 1f))
                px[(y * w + x) * 4 + k] = Math.round(CalibrationTransfer.toCode(curve, lin) * 255f).toByte()
            }
            px[(y * w + x) * 4 + 3] = 0xFF.toByte()
        }
        return RgbaFrame(w, h, px)
    }

    /** 96 varied, saturated and neutral linear colours, all below clipping. */
    fun scene(seed: Int = 7, count: Int = 96): List<FloatArray> {
        val rng = Random(seed)
        return List(count) {
            if (it % 8 == 0) { val g = 0.08f + 0.5f * rng.nextFloat(); floatArrayOf(g, g, g) }
            else floatArrayOf(0.04f + 0.6f * rng.nextFloat(), 0.04f + 0.6f * rng.nextFloat(), 0.04f + 0.6f * rng.nextFloat())
        }
    }

    fun greyScene(count: Int = 96): List<FloatArray> = List(count) { val g = 0.05f + 0.6f * it / count; floatArrayOf(g, g, g) }

    @Suppress("unused") val twoPi = 2 * PI
}
