/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import com.librestatic.opencinecam.camera.calibration.PhotoLensPlan
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

private const val STRIP_PIXELS = 1 shl 20

/**
 * Applies [plan] to [source] (ARGB_8888, sensor pixel grid) and returns a new bitmap of the same
 * size; [source] is left untouched and the caller recycles it. Alignment first (bilinear, in sensor
 * coordinates), then the colour pass in horizontal strips so no second full-size pixel array exists.
 */
internal fun applyLensPlan(source: Bitmap, plan: PhotoLensPlan, checkRunning: () -> Unit): Bitmap {
    val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val matrix = Matrix().apply { plan.bitmapMatrix?.let { setValues(it) } }
        canvas.drawBitmap(source, matrix, paint)
        if (plan.hasColor) {
            val stripRows = (STRIP_PIXELS / out.width).coerceIn(1, out.height)
            val pixels = IntArray(out.width * stripRows)
            var y = 0
            while (y < out.height) {
                checkRunning()
                val rows = minOf(stripRows, out.height - y)
                val strip = if (rows == stripRows) pixels else IntArray(out.width * rows)
                out.getPixels(strip, 0, out.width, 0, y, out.width, rows)
                plan.applyColor(strip)
                out.setPixels(strip, 0, out.width, 0, y, out.width, rows)
                y += rows
            }
        }
        return out
    } catch (failure: Throwable) { out.recycle(); throw failure }
}

/** EXIF tags carried over to a re-encoded JPEG (the bitmap encoder writes none). */
private val EXIF_TAGS = listOf(
    ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_OFFSET_TIME_DIGITIZED, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
    ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
    ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_METERING_MODE,
    ExifInterface.TAG_FLASH, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_SUBJECT_DISTANCE, ExifInterface.TAG_BRIGHTNESS_VALUE,
    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
)

/**
 * Lens-corrects a JPEG that is delivered without an aspect crop. The pixel grid and size are
 * unchanged, so the EXIF of [bytes] (orientation, timestamps, GPS, camera data) is copied onto the
 * re-encoded file through a temporary file in [tempDirectory]. If the copy fails this throws, so the
 * caller keeps the uncorrected original instead of publishing a picture that lost its orientation.
 */
internal fun correctPhotoLensJpeg(bytes: ByteArray, plan: PhotoLensPlan, quality: Int, tempDirectory: File,
    checkRunning: () -> Unit): StillImagePayload {
    require(quality in 1..100 && bytes.isNotEmpty() && bytes.size <= PhotoAspectBounds.MAX_ENCODED_BYTES)
    checkRunning()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0)
    require(bounds.outWidth.toLong() * bounds.outHeight <= PhotoAspectBounds.MAX_DECODED_PIXELS)
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
    var decoded: Bitmap? = null
    var corrected: Bitmap? = null
    try {
        decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        corrected = applyLensPlan(decoded, plan, checkRunning)
        decoded.recycle(); decoded = null
        val width = corrected.width; val height = corrected.height
        val output = ByteArrayOutputStream()
        val ok = corrected.compress(Bitmap.CompressFormat.JPEG, quality, output)
        checkRunning()
        check(ok) { "Corrected JPEG encoding failed" }
        corrected.recycle(); corrected = null
        val withExif = copyExif(bytes, output.toByteArray(), tempDirectory)
        check(withExif.size <= PhotoAspectBounds.MAX_ENCODED_BYTES) { "Corrected JPEG exceeds its encoded budget" }
        return StillImagePayload.owned(StillImageKind.JPEG, withExif, width, height)
    } catch (failure: OutOfMemoryError) {
        throw IllegalStateException("The lens correction exceeds available bitmap memory", failure)
    } finally { decoded?.recycle(); corrected?.recycle() }
}

private fun copyExif(source: ByteArray, encoded: ByteArray, tempDirectory: File): ByteArray {
    tempDirectory.mkdirs()
    val file = File.createTempFile("lens-", ".jpg", tempDirectory)
    try {
        file.writeBytes(encoded)
        val from = ByteArrayInputStream(source).use { ExifInterface(it) }
        val to = ExifInterface(file.absolutePath)
        for (tag in EXIF_TAGS) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
        to.saveAttributes()
        return file.readBytes()
    } finally { file.delete() }
}
