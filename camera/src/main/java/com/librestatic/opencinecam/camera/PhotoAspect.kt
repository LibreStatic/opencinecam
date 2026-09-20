/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Width/height describe the final oriented file, not sensor readout, zoom or viewfinder guides. */
data class PhotoAspectSelection(val enabled: Boolean = false, val width: Int = 4, val height: Int = 3) {
    init { require(width in 1..10_000 && height in 1..10_000 && gcd(width, height) == 1) }
    companion object {
        fun normalized(enabled: Boolean = false, width: Int = 4, height: Int = 3): PhotoAspectSelection {
            require(width in 1..10_000 && height in 1..10_000)
            val divisor = gcd(width, height)
            return PhotoAspectSelection(enabled, width / divisor, height / divisor)
        }
        private fun gcd(a: Int, b: Int): Int {
            var x = a; var y = b
            while (y != 0) { val remainder = x % y; x = y; y = remainder }
            return x
        }
    }
}

enum class PhotoAspectDisposition { APPLIED, FULL_FRAME, RAW_UNCHANGED }
data class PhotoCropRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    init { require(left >= 0 && top >= 0 && width > 0 && height > 0) }
}
data class PhotoAspectReport(val requested: PhotoAspectSelection, val disposition: PhotoAspectDisposition,
    val sourceWidth: Int, val sourceHeight: Int, val crop: PhotoCropRect,
    val resultWidth: Int, val resultHeight: Int, val outputOrientationDegrees: Int) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(crop.left.toLong() + crop.width <= sourceWidth && crop.top.toLong() + crop.height <= sourceHeight)
        require(resultWidth == crop.width && resultHeight == crop.height)
        require(outputOrientationDegrees in setOf(0, 90, 180, 270))
        if (disposition == PhotoAspectDisposition.APPLIED) {
            require(requested.enabled && outputOrientationDegrees == 0)
            require(resultWidth.toLong() * requested.height == resultHeight.toLong() * requested.width)
            require(crop.left == (sourceWidth - resultWidth) / 2 && crop.top == (sourceHeight - resultHeight) / 2)
        } else {
            require(crop == PhotoCropRect(0, 0, sourceWidth, sourceHeight))
            if (disposition == PhotoAspectDisposition.FULL_FRAME) require(!requested.enabled)
        }
    }
}

/** Largest exact integer multiple, centered with at most one pixel of odd-edge asymmetry. */
fun PhotoAspectSelection.centeredCrop(sourceWidth: Int, sourceHeight: Int): PhotoCropRect {
    require(sourceWidth > 0 && sourceHeight > 0)
    if (!enabled) return PhotoCropRect(0, 0, sourceWidth, sourceHeight)
    val multiple = minOf(sourceWidth / width, sourceHeight / height)
    require(multiple > 0) { "The source cannot represent this aspect ratio without upscaling" }
    val resultWidth = width * multiple
    val resultHeight = height * multiple
    return PhotoCropRect((sourceWidth - resultWidth) / 2, (sourceHeight - resultHeight) / 2, resultWidth, resultHeight)
}

internal fun PhotoAspectSelection.appliedReport(width: Int, height: Int): PhotoAspectReport {
    val rect = centeredCrop(width, height)
    return PhotoAspectReport(this, if (enabled) PhotoAspectDisposition.APPLIED else PhotoAspectDisposition.FULL_FRAME,
        width, height, rect, rect.width, rect.height, 0)
}
internal fun PhotoAspectSelection.rawReport(width: Int, height: Int, orientationDegrees: Int): PhotoAspectReport =
    PhotoAspectReport(this, PhotoAspectDisposition.RAW_UNCHANGED, width, height,
        PhotoCropRect(0, 0, width, height), width, height, orientationDegrees)

internal object PhotoAspectBounds {
    const val MAX_DECODED_PIXELS = 16 * 1024 * 1024
    const val MAX_ENCODED_BYTES = 32 * 1024 * 1024
}

internal fun cropAspectPixels(pixels: IntArray, width: Int, height: Int, crop: PhotoCropRect,
    checkRunning: () -> Unit = {}): IntArray {
    require(width > 0 && height > 0 && width.toLong() * height <= PhotoAspectBounds.MAX_DECODED_PIXELS)
    require(pixels.size.toLong() == width.toLong() * height)
    require(crop.left.toLong() + crop.width <= width && crop.top.toLong() + crop.height <= height)
    checkRunning()
    if (crop == PhotoCropRect(0, 0, width, height)) return pixels
    val result = IntArray(crop.width * crop.height)
    repeat(crop.height) { y ->
        checkRunning()
        val offset = (y + crop.top) * width + crop.left
        pixels.copyInto(result, y * crop.width, offset, offset + crop.width)
    }
    checkRunning()
    return result
}
