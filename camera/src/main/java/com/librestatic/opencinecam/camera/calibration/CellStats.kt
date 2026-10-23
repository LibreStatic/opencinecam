/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

/**
 * Statistics of one grid cell. [r], [g], [b] are the mean scene-linear values (each pixel is
 * linearised before averaging), [lumaVariance] the variance of linear Y inside the cell,
 * [clipFraction] the share of pixels with any channel code >= 250 and [darkFraction] the share
 * with linear Y < 0.01.
 */
data class CellStat(
    val col: Int,
    val row: Int,
    val r: Float,
    val g: Float,
    val b: Float,
    val lumaVariance: Float,
    val clipFraction: Float,
    val darkFraction: Float,
) {
    /** Relative luminance of the mean colour. */
    val luma: Float get() = FrameOps.LUMA_R * r + FrameOps.LUMA_G * g + FrameOps.LUMA_B * b
}

object CellStats {
    const val CLIP_CODE = 250
    const val DARK_LUMA = 0.01f

    /** Row-major list of [cols] x [rows] cell statistics for [frame]. */
    fun compute(frame: RgbaFrame, cols: Int = 12, rows: Int = 8, curve: TransferCurve = TransferCurve.SDR): List<CellStat> {
        require(cols > 0 && rows > 0 && frame.width >= cols && frame.height >= rows) { "Grid does not fit the frame." }
        val lut = CalibrationTransfer.decodeLut(curve)
        val out = ArrayList<CellStat>(cols * rows)
        for (row in 0 until rows) {
            val y0 = row * frame.height / rows
            val y1 = (row + 1) * frame.height / rows
            for (col in 0 until cols) {
                val x0 = col * frame.width / cols
                val x1 = (col + 1) * frame.width / cols
                var sr = 0.0; var sg = 0.0; var sb = 0.0
                var sy = 0.0; var syy = 0.0
                var clipped = 0; var dark = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    val cr = frame.channel(x, y, 0)
                    val cg = frame.channel(x, y, 1)
                    val cb = frame.channel(x, y, 2)
                    val lr = lut[cr]; val lg = lut[cg]; val lb = lut[cb]
                    val yy = FrameOps.LUMA_R * lr + FrameOps.LUMA_G * lg + FrameOps.LUMA_B * lb
                    sr += lr; sg += lg; sb += lb
                    sy += yy; syy += yy.toDouble() * yy
                    if (cr >= CLIP_CODE || cg >= CLIP_CODE || cb >= CLIP_CODE) clipped++
                    if (yy < DARK_LUMA) dark++
                }
                val n = ((x1 - x0) * (y1 - y0)).toDouble()
                val meanY = sy / n
                out += CellStat(
                    col, row,
                    (sr / n).toFloat(), (sg / n).toFloat(), (sb / n).toFloat(),
                    (syy / n - meanY * meanY).coerceAtLeast(0.0).toFloat(),
                    (clipped / n).toFloat(), (dark / n).toFloat(),
                )
            }
        }
        return out
    }
}
