/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Native JPEG inputs explicitly request orientation zero. Normalize EXIF and the frozen desired
 * rotation, then center-crop actual pixels. No sampling/upscaling or silent format conversion. */
internal fun cropPhotoAspectJpeg(bytes: ByteArray, aspect: PhotoAspectSelection, desiredOrientationDegrees: Int,
    quality: Int, checkRunning: () -> Unit): StillImagePayload {
    require(aspect.enabled && desiredOrientationDegrees in setOf(0, 90, 180, 270) && quality in 1..100)
    require(bytes.isNotEmpty() && bytes.size <= PhotoAspectBounds.MAX_ENCODED_BYTES)
    checkRunning()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0)
    require(bounds.outWidth.toLong() * bounds.outHeight <= PhotoAspectBounds.MAX_DECODED_PIXELS) { "JPEG exceeds the aspect processor pixel budget" }
    val exif = ByteArrayInputStream(bytes).use { input ->
        ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            .let { if (it == 0) 1 else it }
    }
    require(exif in 1..8) { "JPEG orientation is invalid" }
    val owned = mutableListOf<Bitmap>()
    fun own(bitmap: Bitmap): Bitmap = bitmap.also { if (owned.none { prior -> prior === it }) owned += it }
    fun retainOnly(bitmap: Bitmap) {
        owned.filter { it !== bitmap }.forEach { it.recycle() }
        owned.removeAll { it !== bitmap }
    }
    try {
        checkRunning()
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val decoded = own(requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)))
        require(decoded.width == bounds.outWidth && decoded.height == bounds.outHeight &&
            decoded.config == Bitmap.Config.ARGB_8888 && decoded.colorSpace?.isSrgb == true)
        checkRunning()
        val transform = Matrix().apply {
            val (a, b, c, d) = when (exif) {
                2 -> listOf(-1f, 0f, 0f, 1f)
                3 -> listOf(-1f, 0f, 0f, -1f)
                4 -> listOf(1f, 0f, 0f, -1f)
                5 -> listOf(0f, 1f, 1f, 0f)
                6 -> listOf(0f, -1f, 1f, 0f)
                7 -> listOf(0f, -1f, -1f, 0f)
                8 -> listOf(0f, 1f, -1f, 0f)
                else -> listOf(1f, 0f, 0f, 1f)
            }
            setValues(floatArrayOf(a, b, 0f, c, d, 0f, 0f, 0f, 1f))
            postRotate(desiredOrientationDegrees.toFloat())
        }
        val oriented = own(Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, false))
        retainOnly(oriented)
        val report = aspect.appliedReport(oriented.width, oriented.height)
        checkRunning()
        val crop = report.crop
        val cropped = own(Bitmap.createBitmap(oriented, crop.left, crop.top, crop.width, crop.height))
        retainOnly(cropped)
        // At most two bounded ARGB bitmaps overlap during a transform; encoding retains only
        // the cropped bitmap (<=64 MiB), not source/oriented intermediates or prior frames.
        val output = object : ByteArrayOutputStream() {
            override fun write(value: Int) {
                checkRunning(); check(count < PhotoAspectBounds.MAX_ENCODED_BYTES)
                super.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                checkRunning()
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                check(length <= PhotoAspectBounds.MAX_ENCODED_BYTES - count) { "Cropped JPEG exceeds its encoded budget" }
                super.write(bytes, offset, length)
            }
        }
        val encoded = cropped.compress(Bitmap.CompressFormat.JPEG, quality, output)
        // Android's encoder can swallow an OutputStream exception and return false.
        // Preserve cancellation before interpreting that native completion status.
        checkRunning()
        check(encoded) { "Cropped JPEG encoding failed" }
        return StillImagePayload.owned(StillImageKind.JPEG, output.toByteArray(), cropped.width, cropped.height, report)
    } catch (failure: OutOfMemoryError) {
        throw IllegalStateException("The aspect crop exceeds available bitmap memory", failure)
    } finally { owned.asReversed().forEach { it.recycle() } }
}
