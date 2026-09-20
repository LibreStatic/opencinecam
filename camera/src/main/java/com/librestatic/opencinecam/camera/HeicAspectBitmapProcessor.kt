/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import java.io.File
import java.nio.ByteBuffer

/** A format-preserving HEIC re-encode, not a lossless bitstream crop. The software decoder
 * normalizes the container orientation; only the frozen camera rotation is applied afterwards.
 * Output is SDR/sRGB8-bit; no preservation of HDR, arbitrary EXIF or wide-gamut is claimed. */
internal fun cropPhotoAspectHeic(bytes: ByteArray, aspect: PhotoAspectSelection, desiredOrientationDegrees: Int,
    quality: Int, tempDirectory: File, checkRunning: () -> Unit): StillImagePayload {
    require(quality in 1..100)
    return withCroppedHeicBitmap(bytes, aspect, desiredOrientationDegrees, checkRunning) { bitmap, report ->
        val encoded = encodeHeicBitmap(bitmap, quality, tempDirectory, checkRunning)
        checkRunning()
        // Validate the actual HEIF container after codec/muxer retirement, before any publication.
        val verified = decodeHeicBitmap(encoded, checkRunning) { width, height ->
            require(width == report.resultWidth && height == report.resultHeight) { "HEIC encoder changed output geometry" }
        }
        try {
            checkRunning()
            require(verified.width == report.resultWidth && verified.height == report.resultHeight)
        } finally { verified.recycle() }
        StillImagePayload.owned(StillImageKind.HEIC, encoded, report.resultWidth, report.resultHeight, report)
    }
}

/** The callback consumes pixels while their native owner is live; all intermediates are recycled
 * on success, cancellation and any encoder/consumer failure. No test-only decoder replacement. */
internal fun <T> withCroppedHeicBitmap(bytes: ByteArray, aspect: PhotoAspectSelection,
    desiredOrientationDegrees: Int, checkRunning: () -> Unit, consume: (Bitmap, PhotoAspectReport) -> T): T {
    require(aspect.enabled && desiredOrientationDegrees in setOf(0, 90, 180, 270))
    val owned = mutableListOf<Bitmap>()
    fun own(bitmap: Bitmap): Bitmap = bitmap.also { if (owned.none { prior -> prior === it }) owned += it }
    fun retainOnly(bitmap: Bitmap) {
        owned.filter { it !== bitmap }.forEach { it.recycle() }
        owned.removeAll { it !== bitmap }
    }
    try {
        checkRunning()
        val decoded = own(decodeHeicBitmap(bytes, checkRunning))
        checkRunning()
        val matrix = Matrix().apply { postRotate(desiredOrientationDegrees.toFloat()) }
        val oriented = own(Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, false))
        retainOnly(oriented)
        val report = aspect.appliedReport(oriented.width, oriented.height)
        checkRunning()
        val rect = report.crop
        val cropped = own(Bitmap.createBitmap(oriented, rect.left, rect.top, rect.width, rect.height))
        retainOnly(cropped)
        checkRunning()
        return consume(cropped, report)
    } catch (failure: OutOfMemoryError) {
        throw IllegalStateException("The HEIC crop exceeds available bitmap memory", failure)
    } finally { owned.asReversed().forEach { it.recycle() } }
}

/** ImageDecoder reports oriented dimensions and applies HEIF container transforms itself.
 * Header limits precede allocation; partial streams, non-HEIC and non8-bit results reject. */
private fun decodeHeicBitmap(bytes: ByteArray, checkRunning: () -> Unit,
    checkDimensions: (Int, Int) -> Unit = { _, _ -> }): Bitmap {
    require(bytes.isNotEmpty() && bytes.size <= PhotoAspectBounds.MAX_ENCODED_BYTES)
    checkRunning()
    var width = 0; var height = 0
    val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        require(info.mimeType in setOf("image/heif", "image/heic")) { "The aspect source is not HEIC" }
        width = info.size.width; height = info.size.height
        require(width > 0 && height > 0 && width.toLong() * height <= PhotoAspectBounds.MAX_DECODED_PIXELS) {
            "HEIC exceeds the aspect processor pixel budget"
        }
        checkDimensions(width, height)
        checkRunning()
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setOnPartialImageListener { false }
    }
    try {
        checkRunning()
        require(decoded.width == width && decoded.height == height &&
            decoded.config == Bitmap.Config.ARGB_8888 && decoded.colorSpace?.isSrgb == true) {
            "HEIC crop requires software-decoded SDR sRGB8-bit pixels"
        }
        return decoded
    } catch (failure: Throwable) { decoded.recycle(); throw failure }
}
