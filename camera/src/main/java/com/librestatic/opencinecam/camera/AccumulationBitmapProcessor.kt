/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Camera inputs request JPEG_ORIENTATION=0. Normalize any declared EXIF transform before
 * accumulation, then physically rotate the final pixels once to the frozen desired orientation.
 * Only the running linear accumulator survives add(); JPEGs and decoded bitmaps do not. */
internal class AccumulationBitmapProcessor(private val selection: AccumulationSelection,
    private val outputOrientationDegrees: Int, private val checkRunning: () -> Unit) : AutoCloseable {
    private var accumulator: LinearLightAccumulator? = null
    private var closed = false
    init { require(outputOrientationDegrees in setOf(0, 90, 180, 270)) }

    fun add(jpeg: ByteArray) {
        check(!closed)
        require(jpeg.isNotEmpty() && jpeg.size <= CapturedAccumulation.MAX_ENCODED_BYTES)
        checkRunning()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outMimeType == "image/jpeg") { "Accumulation requires native JPEG inputs" }
        val sample = accumulationSampleSize(bounds.outWidth, bounds.outHeight, selection.maxEdge)
        val orientation = ByteArrayInputStream(jpeg).use { input ->
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                .let { if (it == ExifInterface.ORIENTATION_UNDEFINED) ExifInterface.ORIENTATION_NORMAL else it }
        }
        require(orientation in 1..8) { "Invalid source JPEG orientation" }
        checkRunning()
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)) { "JPEG decoding failed" }
        try {
            checkRunning()
            val width = bitmap.width
            val height = bitmap.height
            require(width in 1..selection.maxEdge && height in 1..selection.maxEdge)
            require(width.toLong() * height <= CapturedAccumulation.MAX_PIXELS)
            require(bitmap.colorSpace?.isSrgb == true) { "JPEG decoder did not provide sRGB pixels" }
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val oriented = orientAccumulationPixels(pixels, width, height, orientation, checkRunning)
            val normalizedWidth = if (orientation >= 5) height else width
            val normalizedHeight = if (orientation >= 5) width else height
            val current = accumulator ?: LinearLightAccumulator(normalizedWidth, normalizedHeight, selection).also { accumulator = it }
            check(current.width == normalizedWidth && current.height == normalizedHeight) { "Source image geometry changed during accumulation" }
            current.add(oriented, checkRunning)
        } finally { bitmap.recycle() }
    }

    fun finish(quality: Int, aspect: PhotoAspectSelection = PhotoAspectSelection()): StillImagePayload {
        check(!closed)
        require(quality in 1..100)
        checkRunning()
        val current = checkNotNull(accumulator)
        val orientation = when (outputOrientationDegrees) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 }
        val pixels = orientAccumulationPixels(current.pixels(checkRunning), current.width, current.height, orientation, checkRunning)
        val width = if (orientation >= 5) current.height else current.width
        val height = if (orientation >= 5) current.width else current.height
        val report = aspect.takeIf { it.enabled }?.appliedReport(width, height)
        val finalPixels = if (report != null) cropAspectPixels(pixels, width, height, report.crop, checkRunning) else pixels
        val finalWidth = report?.resultWidth ?: width
        val finalHeight = report?.resultHeight ?: height
        val bitmap = Bitmap.createBitmap(finalPixels, finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        try {
            checkRunning()
            val output = object : ByteArrayOutputStream() {
                override fun write(value: Int) {
                    checkRunning()
                    check(count < CapturedAccumulation.MAX_ENCODED_BYTES) { "Accumulated JPEG exceeds its encoded budget" }
                    super.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    checkRunning()
                    require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                    check(length <= CapturedAccumulation.MAX_ENCODED_BYTES - count) { "Accumulated JPEG exceeds its encoded budget" }
                    super.write(bytes, offset, length)
                }
            }
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "JPEG encoding failed" }
            checkRunning()
            return StillImagePayload.owned(StillImageKind.JPEG, output.toByteArray(), finalWidth, finalHeight, report)
        } finally { bitmap.recycle() }
    }
    override fun close() { closed = true; accumulator?.close(); accumulator = null }
}
