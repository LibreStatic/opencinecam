/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** STANDARD leaves the device tone mapping exactly as the capture-request template has it. */
enum class PictureProfile { STANDARD, FLAT, CINE }

/** Raw TONEMAP_MODE_CONTRAST_CURVE; kept numeric so host tests need no framework. */
const val TONEMAP_MODE_CONTRAST_CURVE_VALUE = 0

/**
 * Display-referred (sRGB / Rec.709 encoded) tone curves for the ISP tone-map stage.
 *
 * Each profile is a short list of knots through (0,0) and (1,1), joined by a monotone cubic
 * Hermite spline (Fritsch-Carlson slopes), so the result cannot overshoot or fold back whatever
 * the knots are. The curve is sampled at evenly spaced inputs and the same list is used for R, G
 * and B, which keeps hue and saturation untouched.
 *
 * - FLAT: concave. Lifts the toe (slope ~1.6 at black), keeps mid contrast just under 1 and
 *   rolls the highlights off (slope ~0.7 at white). Less contrast to grade from.
 * - CINE: soft S. Gentle toe, mid contrast ~1.2 around mid grey, then a long shoulder from about
 *   70% that eases into white.
 */
object PictureProfileCurves {
    const val DEFAULT_POINT_COUNT = 96
    /** Fewer points than this cannot describe the shoulder; such a camera keeps its own tone mapping. */
    const val MIN_POINT_COUNT = 16

    private val FLAT_KNOTS = doubleArrayOf(
        0.0, 0.0, 0.05, 0.08, 0.18, 0.25, 0.5, 0.56, 0.8, 0.84, 0.95, 0.965, 1.0, 1.0)
    private val CINE_KNOTS = doubleArrayOf(
        0.0, 0.0, 0.08, 0.05, 0.25, 0.20, 0.5, 0.52, 0.7, 0.75, 0.85, 0.90, 0.95, 0.975, 1.0, 1.0)

    /** Camera2 TonemapCurve layout: interleaved (in, out) pairs, strictly increasing inputs, null for STANDARD. */
    fun points(profile: PictureProfile, count: Int = DEFAULT_POINT_COUNT): FloatArray? {
        val knots = when (profile) {
            PictureProfile.STANDARD -> return null
            PictureProfile.FLAT -> FLAT_KNOTS
            PictureProfile.CINE -> CINE_KNOTS
        }
        require(count >= 2) { "A curve needs at least two points." }
        val xs = DoubleArray(knots.size / 2) { knots[it * 2] }
        val ys = DoubleArray(xs.size) { knots[it * 2 + 1] }
        val slopes = monotoneSlopes(xs, ys)
        val out = FloatArray(count * 2)
        var previous = 0f
        for (i in 0 until count) {
            val x = i.toDouble() / (count - 1)
            var y = evaluate(xs, ys, slopes, x).coerceIn(0.0, 1.0).toFloat()
            if (y < previous) y = previous // float rounding must never fold the curve back
            previous = y
            out[i * 2] = if (i == count - 1) 1f else x.toFloat()
            out[i * 2 + 1] = if (i == 0) 0f else if (i == count - 1) 1f else y
        }
        return out
    }

    /** Fritsch-Carlson: harmonic-mean tangents, zero where the secants change sign. */
    private fun monotoneSlopes(xs: DoubleArray, ys: DoubleArray): DoubleArray {
        val n = xs.size
        val secant = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = DoubleArray(n)
        m[0] = secant[0]
        m[n - 1] = secant[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (secant[i - 1] * secant[i] <= 0.0) 0.0 else 2.0 * secant[i - 1] * secant[i] / (secant[i - 1] + secant[i])
        }
        return m
    }

    private fun evaluate(xs: DoubleArray, ys: DoubleArray, m: DoubleArray, x: Double): Double {
        var k = xs.size - 2
        for (i in 0 until xs.size - 1) if (x <= xs[i + 1]) { k = i; break }
        val h = xs[k + 1] - xs[k]
        val t = (x - xs[k]) / h
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] +
            (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1]
    }
}

/**
 * Whether the tone curve may be written. Never for HLG/LOG graphs (the GPU pipeline expects the
 * untouched signal) or constrained high-speed sessions. DNG-only stills are handled by the engine.
 */
data class ToneProfileContext(val logOrHlg: Boolean = false, val constrainedHfr: Boolean = false) {
    val eligible: Boolean get() = !logOrHlg && !constrainedHfr
}

/** The curve to submit, or null to leave TONEMAP_* exactly as the template has it. */
fun PictureProfile.resolveCurve(caps: ImageProcessingCapabilities, context: ToneProfileContext): FloatArray? {
    if (this == PictureProfile.STANDARD || !context.eligible || !caps.supportsPictureProfiles) return null
    return PictureProfileCurves.points(this, minOf(PictureProfileCurves.DEFAULT_POINT_COUNT, caps.maxCurvePoints))
}
