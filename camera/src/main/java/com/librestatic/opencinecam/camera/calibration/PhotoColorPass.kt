/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

/** Applies a lens colour correction to JPEG pixels, using the same curves as the GL path. */
object PhotoColorPass {
    /**
     * Corrects [pixels] (ARGB_8888 ints) in place: code -> linear (LUT), `2^ev * matrix * (gains * rgb)`,
     * clamp to 0..1, linear -> code through a 4096-entry table. Alpha is untouched.
     */
    fun applyToArgb(
        pixels: IntArray,
        matrix: FloatArray,
        gains: FloatArray,
        ev: Float,
        transfer: TransferCurve = TransferCurve.SDR,
    ) {
        val decode = CalibrationTransfer.decodeLut(transfer)
        val encode = CalibrationTransfer.encodeLut(transfer)
        val k = Math.pow(2.0, ev.toDouble()).toFloat()
        val m = FloatArray(9) { matrix[it] * k * gains[it % 3] }
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = decode[(p shr 16) and 0xFF]
            val g = decode[(p shr 8) and 0xFF]
            val b = decode[p and 0xFF]
            val or = CalibrationTransfer.encodeFast(encode, m[0] * r + m[1] * g + m[2] * b)
            val og = CalibrationTransfer.encodeFast(encode, m[3] * r + m[4] * g + m[5] * b)
            val ob = CalibrationTransfer.encodeFast(encode, m[6] * r + m[7] * g + m[8] * b)
            pixels[i] = (p and 0xFF000000.toInt()) or
                ((or + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((og + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (ob + 0.5f).toInt().coerceIn(0, 255)
        }
    }
}
