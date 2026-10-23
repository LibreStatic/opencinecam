/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import kotlin.math.floor

/** Pixel operations on [RgbaFrame]s used before solving. */
object FrameOps {
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    /**
     * Resamples [frame] to [outW] x [outH] bilinearly, showing the region of size 1/[scale] of the
     * frame centred on ([centerX], [centerY]) (normalised 0..1). [scale] >= 1 zooms in. Samples
     * beyond the frame clamp to the edge.
     */
    fun cropScale(frame: RgbaFrame, centerX: Float, centerY: Float, scale: Float, outW: Int, outH: Int): RgbaFrame {
        require(scale > 0f && outW > 0 && outH > 0) { "Invalid crop." }
        val out = ByteArray(outW * outH * 4)
        val w = frame.width
        val h = frame.height
        for (oy in 0 until outH) {
            val sy = (centerY + ((oy + 0.5f) / outH - 0.5f) / scale) * h - 0.5f
            val y0f = floor(sy)
            val fy = sy - y0f
            val ya = y0f.toInt().coerceIn(0, h - 1)
            val yb = (y0f.toInt() + 1).coerceIn(0, h - 1)
            for (ox in 0 until outW) {
                val sx = (centerX + ((ox + 0.5f) / outW - 0.5f) / scale) * w - 0.5f
                val x0f = floor(sx)
                val fx = sx - x0f
                val xa = x0f.toInt().coerceIn(0, w - 1)
                val xb = (x0f.toInt() + 1).coerceIn(0, w - 1)
                for (c in 0 until 4) {
                    val top = frame.channel(xa, ya, c) * (1 - fx) + frame.channel(xb, ya, c) * fx
                    val bottom = frame.channel(xa, yb, c) * (1 - fx) + frame.channel(xb, yb, c) * fx
                    val v = top * (1 - fy) + bottom * fy
                    out[(oy * outW + ox) * 4 + c] = (v + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return RgbaFrame(outW, outH, out)
    }

    /** Pixel-wise mean of equally sized [frames] on the codes (rounded; alpha is opaque). */
    fun average(frames: List<RgbaFrame>): RgbaFrame {
        require(frames.isNotEmpty()) { "Nothing to average." }
        val first = frames[0]
        require(frames.all { it.width == first.width && it.height == first.height }) { "Frames must match in size." }
        if (frames.size == 1) return first
        val out = ByteArray(first.pixels.size)
        val n = frames.size
        for (i in out.indices) {
            if (i % 4 == 3) { out[i] = 0xFF.toByte(); continue }
            var sum = 0
            for (f in frames) sum += f.pixels[i].toInt() and 0xFF
            out[i] = ((sum + n / 2) / n).toByte()
        }
        return RgbaFrame(first.width, first.height, out)
    }

    /**
     * Rec.709-weighted luma in 0..1. With [linear] the codes are linearised through [curve]
     * first (relative luminance Y); otherwise it is the weighted sum of the codes.
     */
    fun luma(frame: RgbaFrame, linear: Boolean, curve: TransferCurve = TransferCurve.SDR): LumaImage {
        val lut = CalibrationTransfer.decodeLut(curve)
        val n = frame.width * frame.height
        val out = FloatArray(n)
        val p = frame.pixels
        for (i in 0 until n) {
            val r = p[i * 4].toInt() and 0xFF
            val g = p[i * 4 + 1].toInt() and 0xFF
            val b = p[i * 4 + 2].toInt() and 0xFF
            out[i] = if (linear) LUMA_R * lut[r] + LUMA_G * lut[g] + LUMA_B * lut[b]
            else (LUMA_R * r + LUMA_G * g + LUMA_B * b) / 255f
        }
        return LumaImage(frame.width, frame.height, out)
    }
}
