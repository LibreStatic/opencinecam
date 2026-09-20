/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

enum class PreciseHdrTransfer { PQ, HLG }

/** Describes an SDR preview of decoded 10-bit samples, never an HDR master or display validation. */
data class PreciseHdrPreview(val transfer: PreciseHdrTransfer, val decodedBitDepth: Int = 10) {
    init { require(decodedBitDepth == 10) { "This preview requires actual decoded 10-bit samples" } }
}

/** Reads a plane-relative sample without changing buffer position, limit, mark or byte order.
 * x/y already include the caller's crop origin (and chroma subsampling for a chroma plane).
 * Android P010 stores each sample in 16 little-endian bits with six zero low bits:
 * https://developer.android.com/reference/android/graphics/ImageFormat#YCBCR_P010
 */
fun readP010Sample(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, x: Int, y: Int): Int {
    require(rowStride >= 2 && pixelStride >= 2 && x >= 0 && y >= 0) { "Invalid P010 plane coordinates or strides" }
    val column = x.toLong() * pixelStride
    require(column + 2 <= rowStride.toLong()) { "P010 sample crosses its row stride" }
    val at = buffer.position().toLong() + y.toLong() * rowStride + column
    require(at >= buffer.position() && at + 1 < buffer.limit().toLong()) { "P010 sample exceeds plane buffer bounds" }
    val word = (buffer.get(at.toInt()).toInt() and 255) or ((buffer.get((at + 1).toInt()).toInt() and 255) shl 8)
    require(word and 63 == 0) { "P010 sample has nonzero unused low bits" }
    return word ushr 6
}

/** Absolute linear BT.2020 RGB, in nits, before gamut mapping or 8-bit quantization. */
internal data class HdrLinearRgb(val redNits: Double, val greenNits: Double, val blueNits: Double)

/** BT.2100 non-constant-luminance YCbCr; ICtCp and constant-luminance are not this encoding.
 * PQ uses its absolute 10000-nit EOTF. HLG uses a zero-black, 1000-nit reference display,
 * system gamma 1.2 and the luminance-coupled OOTF, not a per-channel gamma approximation.
 * Reconstructed signal excursions are explicitly clipped to [0,1] BEFORE transfer evaluation.
 * Constants/equations: https://www.itu.int/rec/R-REC-BT.2100
 * https://registry.khronos.org/DataFormat/specs/1.4/dataformat.1.4.html
 */
internal fun hdr10ToLinearBt2020(y: Int, u: Int, v: Int, fullRange: Boolean, transfer: PreciseHdrTransfer): HdrLinearRgb {
    require(y in 0..1023 && u in 0..1023 && v in 0..1023) { "HDR input must contain 10-bit code values" }
    val luma = if (fullRange) y / 1023.0 else (y - 64) / 876.0
    val cb = (u - 512) / if (fullRange) 1023.0 else 896.0
    val cr = (v - 512) / if (fullRange) 1023.0 else 896.0
    val red = (luma + 1.4746 * cr).coerceIn(0.0, 1.0)
    val blue = (luma + 1.8814 * cb).coerceIn(0.0, 1.0)
    val green = (luma - (0.0593 * 1.8814 / 0.6780) * cb - (0.2627 * 1.4746 / 0.6780) * cr).coerceIn(0.0, 1.0)
    return when (transfer) {
        PreciseHdrTransfer.PQ -> HdrLinearRgb(pqNits(red), pqNits(green), pqNits(blue))
        PreciseHdrTransfer.HLG -> {
            val r = inverseHlg(red); val g = inverseHlg(green); val b = inverseHlg(blue)
            val sceneLuminance = 0.2627 * r + 0.6780 * g + 0.0593 * b
            val gain = 1000.0 * sceneLuminance.pow(0.2)
            HdrLinearRgb(r * gain, g * gain, b * gain)
        }
    }
}

private fun pqNits(signal: Double): Double {
    val p = signal.pow(32.0 / 2523.0)
    return 10000.0 * (max(p - 3424.0 / 4096.0, 0.0) / (2413.0 / 128.0 - (2392.0 / 128.0) * p)).pow(16384.0 / 2610.0)
}

private fun inverseHlg(signal: Double): Double {
    val a = 0.17883277
    val b = 1.0 - 4.0 * a
    val c = 0.5 - a * ln(4.0 * a)
    // Rounded standard constants can put white a few parts in 1e8 above one.
    return (if (signal <= 0.5) signal * signal / 3.0 else (exp((signal - c) / a) + b) / 12.0).coerceIn(0.0, 1.0)
}

/** Deliberately lossy SDR preview: fixed 203-nit paper-white scale, luminance Reinhard,
 * final hard clipping to the sRGB gamut and sRGB OETF. 203 maps to 0.5 linear SDR;
 * the scale is a display assumption, NOT a measured source peak or mastering metadata.
 * No adaptation to ambient light, HDR10+ metadata or display peak is claimed.
 * BT.2020 -> XYZ(D65) -> linear sRGB/BT.709 matrix composed from W3C's rational matrices:
 * https://www.w3.org/TR/css-color-4/#color-conversion-code
 * Reinhard here is the fixed preview policy L/(1+L), with L = luminance/203.
 */
fun hdr10ToSdrArgb(y: Int, u: Int, v: Int, fullRange: Boolean, transfer: PreciseHdrTransfer): Int {
    val rgb = hdr10ToLinearBt2020(y, u, v, fullRange, transfer)
    val r = 1.6604910021084344 * rgb.redNits - 0.5876411387885495 * rgb.greenNits - 0.07284986331988488 * rgb.blueNits
    val g = -0.12455047452159074 * rgb.redNits + 1.1328998971259602 * rgb.greenNits - 0.008349422604369477 * rgb.blueNits
    val b = -0.0181507633549053 * rgb.redNits - 0.10057889800800738 * rgb.greenNits + 1.1187296613629127 * rgb.blueNits
    val luminance = max(0.0, 0.2126 * r + 0.7152 * g + 0.0722 * b)
    val scale = 1.0 / (203.0 + luminance)
    return (255 shl 24) or (sdrChannel(r * scale) shl 16) or (sdrChannel(g * scale) shl 8) or sdrChannel(b * scale)
}

private fun sdrChannel(linear: Double): Int {
    val clipped = linear.coerceIn(0.0, 1.0)
    val encoded = if (clipped <= 0.0031308) 12.92 * clipped else 1.055 * clipped.pow(1.0 / 2.4) - 0.055
    return (encoded * 255).roundToInt().coerceIn(0, 255)
}
