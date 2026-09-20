/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/** A platform DNG preview, which may come from embedded JPEG or platform demosaicing.
 * BitmapFactory supplies un-oriented pixels; apply the DNG container orientation exactly once.
 * This does not change the original, expose a full RAW raster, or certify demosaicing/color.
 * BitmapFactory may decode a partial preview; bitmap availability does not certify RAW integrity.
 */
internal fun decodeDngPhotoPreview(resolver: ContentResolver, uri: Uri): Bitmap {
    fun running() { if (Thread.currentThread().isInterrupted) throw InterruptedException("DNG review cancelled") }
    running()
    val orientation = requireNotNull(resolver.openInputStream(uri)).use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            .let { value -> if (value == ExifInterface.ORIENTATION_UNDEFINED) ExifInterface.ORIENTATION_NORMAL else value }
    }
    require(orientation in 1..8) { "DNG orientation is invalid" }
    running()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    requireNotNull(resolver.openInputStream(uri)).use { BitmapFactory.decodeStream(it, null, bounds) }
    val sample = dngPreviewSampleSize(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
    running()
    var decoded: Bitmap? = null
    var oriented: Bitmap? = null
    try {
        decoded = requireNotNull(resolver.openInputStream(uri)).use {
            requireNotNull(BitmapFactory.decodeStream(it, null, options)) { "DNG preview decode failed" }
        }
        running()
        require(decoded.width in 1..2048 && decoded.height in 1..2048) { "DNG decoder exceeded preview bounds" }
        val result = orientDngPreview(decoded, orientation)
        oriented = result
        running()
        if (decoded !== result) decoded.recycle()
        decoded = null; oriented = null
        return result
    } catch (failure: OutOfMemoryError) {
        throw IllegalStateException("DNG preview exhausted decoder memory", failure)
    } finally {
        if (oriented !== decoded) oriented?.recycle()
        decoded?.recycle()
    }
}

internal fun dngPreviewSampleSize(width: Int, height: Int, maxEdge: Int = 2048): Int {
    require(width > 0 && height > 0 && maxEdge > 0) { "DNG preview dimensions must be positive" }
    var sample = 1L
    val edge = maxOf(width, height).toLong()
    while ((edge + sample - 1) / sample > maxEdge) sample *= 2
    require(sample <= Int.MAX_VALUE) { "DNG preview sample is not representable" }
    return sample.toInt()
}

/** Caller retains input ownership; transform result may alias input only for identity. */
internal fun orientDngPreview(bitmap: Bitmap, orientation: Int): Bitmap {
    require(orientation in 1..8) { "DNG orientation is invalid" }
    val (a, b, c, d) = when (orientation) {
        2 -> listOf(-1f, 0f, 0f, 1f)
        3 -> listOf(-1f, 0f, 0f, -1f)
        4 -> listOf(1f, 0f, 0f, -1f)
        5 -> listOf(0f, 1f, 1f, 0f)
        6 -> listOf(0f, -1f, 1f, 0f)
        7 -> listOf(0f, -1f, -1f, 0f)
        8 -> listOf(0f, 1f, -1f, 0f)
        else -> listOf(1f, 0f, 0f, 1f)
    }
    val matrix = Matrix().apply { setValues(floatArrayOf(a, b, 0f, c, d, 0f, 0f, 0f, 1f)) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
}
