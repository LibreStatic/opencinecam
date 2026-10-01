/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.OpenCineLog2Curve
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** How review shows an OCLog2 recording. Neither view changes the recorded bytes. */
enum class PreciseLogView { FLAT_LOG, REC709 }

/** A recording whose sidecar declares OCLog2 code values in BT.2020, 10-bit YCbCr. */
data class PreciseLogSignal(val fullRange: Boolean)

/**
 * The same monitoring math the capture shaders use (OpenCineLogGpuPipeline HLG/SDR tiers), on the CPU:
 * BT.2020 non-constant-luminance YCbCr to OCLog2 R'G'B' codes, then either the Rec.709 view assist
 * (decode, BT.2020 to BT.709, Rec.709 OETF) or the flat monitor (display gamut, 0.68 saturation, OCLog2).
 */
fun oclog2P010ToArgb(y: Int, u: Int, v: Int, fullRange: Boolean, view: PreciseLogView): Int {
    val code = oclog2Codes(y, u, v, fullRange)
    val linear = DoubleArray(3) { OpenCineLog2Curve.decode(code[it].coerceIn(OpenCineLog2Curve.BLACK_CODE, OpenCineLog2Curve.WHITE_CODE)) }
    val display = bt2020ToBt709(linear).map { max(it, 0.0) }
    val out = when (view) {
        PreciseLogView.REC709 -> display.map(::rec709Oetf)
        PreciseLogView.FLAT_LOG -> {
            val luma = 0.2126 * display[0] + 0.7152 * display[1] + 0.0722 * display[2]
            display.map { encodeOcLog2(luma + (it - luma) * 0.68) }
        }
    }
    return (255 shl 24) or (channel(out[0]) shl 16) or (channel(out[1]) shl 8) or channel(out[2])
}

/** OCLog2 R'G'B' code values in [0, 1], clamped like a normalized sampler result. */
internal fun oclog2Codes(y: Int, u: Int, v: Int, fullRange: Boolean): DoubleArray {
    require(y in 0..1023 && u in 0..1023 && v in 0..1023) { "OCLog2 input must contain 10-bit code values" }
    val luma = if (fullRange) y / 1023.0 else (y - 64) / 876.0
    val cb = (u - 512) / if (fullRange) 1023.0 else 896.0
    val cr = (v - 512) / if (fullRange) 1023.0 else 896.0
    return doubleArrayOf(
        (luma + 1.4746 * cr).coerceIn(0.0, 1.0),
        (luma - (0.0593 * 1.8814 / 0.6780) * cb - (0.2627 * 1.4746 / 0.6780) * cr).coerceIn(0.0, 1.0),
        (luma + 1.8814 * cb).coerceIn(0.0, 1.0),
    )
}

private fun bt2020ToBt709(c: DoubleArray) = doubleArrayOf(
    1.660491 * c[0] - 0.587641 * c[1] - 0.072850 * c[2],
    -0.124550 * c[0] + 1.132900 * c[1] - 0.008349 * c[2],
    -0.018151 * c[0] - 0.100579 * c[1] + 1.118730 * c[2],
)

internal fun rec709Oetf(x: Double): Double {
    val clamped = x.coerceIn(0.0, 1.0)
    return if (clamped < 0.018) 4.5 * clamped else 1.099 * clamped.pow(0.45) - 0.099
}

private fun encodeOcLog2(linear: Double): Double =
    0.10 + 0.80 * ln(1.0 + 50.0 * linear.coerceIn(0.0, 1.0)) / ln(51.0)

private fun channel(value: Double): Int = (value.coerceIn(0.0, 1.0) * 255).roundToInt().coerceIn(0, 255)
