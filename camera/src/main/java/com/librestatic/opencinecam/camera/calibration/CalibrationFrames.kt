/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

/** An RGBA8888 readback frame, row-major, display-referred (the codes the encoder or JPEG would carry). */
class RgbaFrame(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0) { "Frame must be non-empty." }
        require(pixels.size == width * height * 4) { "Pixel buffer does not match ${width}x$height RGBA." }
    }

    /** Channel [c] (0 = R, 1 = G, 2 = B, 3 = A) of pixel ([x], [y]) as 0..255. */
    fun channel(x: Int, y: Int, c: Int): Int = pixels[(y * width + x) * 4 + c].toInt() and 0xFF
}

/** A single-channel float image, row-major. */
class LumaImage(val width: Int, val height: Int, val values: FloatArray) {
    init {
        require(width > 0 && height > 0) { "Image must be non-empty." }
        require(values.size == width * height) { "Value buffer does not match ${width}x$height." }
    }
}
