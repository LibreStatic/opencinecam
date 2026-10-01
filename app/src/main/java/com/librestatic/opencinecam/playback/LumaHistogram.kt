/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/** Rec.709-weighted luma of 8-bit ARGB pixels in [bins] equal code slices, scaled so the tallest bin is 1. */
fun lumaHistogram(pixels: IntArray, bins: Int = 64): FloatArray {
    require(bins > 0) { "Histogram needs at least one bin" }
    val counts = IntArray(bins)
    for (p in pixels) {
        val luma = 0.2126f * (p shr 16 and 0xFF) + 0.7152f * (p shr 8 and 0xFF) + 0.0722f * (p and 0xFF)
        counts[(luma * bins / 256f).toInt().coerceIn(0, bins - 1)]++
    }
    val peak = counts.max()
    return if (peak == 0) FloatArray(bins) else FloatArray(bins) { counts[it].toFloat() / peak }
}

/** [lumaHistogram] of a copy no longer than 160 px on its long edge. */
fun bitmapLumaHistogram(bitmap: Bitmap, bins: Int = 64): FloatArray {
    if (bitmap.width <= 0 || bitmap.height <= 0) return lumaHistogram(IntArray(0), bins)
    val scale = 160f / max(bitmap.width, bitmap.height)
    val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val sample = if (scale >= 1f) readable else Bitmap.createScaledBitmap(readable,
        (readable.width * scale).roundToInt().coerceAtLeast(1), (readable.height * scale).roundToInt().coerceAtLeast(1), true)
    val pixels = IntArray(sample.width * sample.height)
    sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
    if (sample !== bitmap && sample !== readable) sample.recycle()
    if (readable !== bitmap) readable.recycle()
    return lumaHistogram(pixels, bins)
}
