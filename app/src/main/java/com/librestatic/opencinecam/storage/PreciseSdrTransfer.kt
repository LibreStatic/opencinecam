/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import kotlin.math.pow
import kotlin.math.roundToInt

/** Converts an admitted SDR8 YCbCr sample to the sRGB codes required by Bitmap.setPixels.
 * The caller validates SDR transfer (3, or the existing unspecified-SDR assumption), standard
 * and range before decoding. Manual matrix/range interpretation does not bypass those guards.
 * Inverse709/SMPTE170M then sRGB OETF are evaluated before the only8-bit quantization.
 * This preserves the existing601/709 matrix policy; it is not a color-primary/gamut transform
 * or HDR mapping. BT601 primary conversion and physical colorimetry remain separate gates.
 * https://android.googlesource.com/platform/frameworks/native/+/android13-release/libs/ui/ColorSpace.cpp
 * https://android.googlesource.com/platform/frameworks/base/+/android13-release/graphics/java/android/graphics/Bitmap.java
 */
internal fun sdr8ToSrgbArgb(y: Int, u: Int, v: Int, fullRange: Boolean, bt709: Boolean): Int {
    require(y in 0..255 && u in 0..255 && v in 0..255) { "SDR input must contain8-bit code values" }
    val luma = if (fullRange) y / 255.0 else (y - 16) / 219.0
    val cb = (u - 128) / if (fullRange) 255.0 else 224.0
    val cr = (v - 128) / if (fullRange) 255.0 else 224.0
    val kr = if (bt709) 0.2126 else 0.299
    val kb = if (bt709) 0.0722 else 0.114
    val kg = 1.0 - kr - kb
    // Clip reconstructed channels, not Y/C separately: excursions can combine into valid RGB.
    val red = sdrChannelToSrgb(luma + 2.0 * (1.0 - kr) * cr)
    val green = sdrChannelToSrgb(luma - 2.0 * kb * (1.0 - kb) / kg * cb - 2.0 * kr * (1.0 - kr) / kg * cr)
    val blue = sdrChannelToSrgb(luma + 2.0 * (1.0 - kb) * cb)
    return (255 shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun sdrChannelToSrgb(value: Double): Int {
    val signal = value.coerceIn(0.0, 1.0)
    val linear = if (signal < 0.081) signal / 4.5 else ((signal + 0.099) / 1.099).pow(1.0 / 0.45)
    val srgb = if (linear <= 0.0031308) 12.92 * linear else 1.055 * linear.pow(1.0 / 2.4) - 0.055
    return (255.0 * srgb).roundToInt().coerceIn(0, 255)
}
