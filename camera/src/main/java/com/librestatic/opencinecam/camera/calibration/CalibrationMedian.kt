/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

/** Element-wise medians over the alternating calibration cycles, robust to one bad cycle. */
object CalibrationMedian {
    /**
     * Median of [fits]: matrix and gains element-wise, then matrix rows re-normalised to sum 1 and
     * gains to luma 1. The fallback flag is true when most fits fell back.
     */
    fun combine(fits: List<ColorFit>): ColorFit {
        require(fits.isNotEmpty()) { "Nothing to combine." }
        val matrix = FloatArray(9) { i -> median(fits.map { it.matrix[i] }) }
        for (r in 0 until 3) {
            val sum = matrix[r * 3] + matrix[r * 3 + 1] + matrix[r * 3 + 2]
            if (sum != 0f) for (c in 0 until 3) matrix[r * 3 + c] /= sum
        }
        val gains = FloatArray(3) { i -> median(fits.map { it.gains[i] }) }
        val luma = FrameOps.LUMA_R * gains[0] + FrameOps.LUMA_G * gains[1] + FrameOps.LUMA_B * gains[2]
        if (luma > 0f) for (c in 0 until 3) gains[c] /= luma
        return ColorFit(
            matrix, gains,
            median(fits.map { it.evOffset }),
            median(fits.map { it.residualDeltaE }),
            median(fits.map { it.residualDeltaEBefore }),
            median(fits.map { it.cellsUsed.toFloat() }).toInt(),
            fits.count { it.diagonalFallback } * 2 > fits.size,
            median(fits.map { it.conditionNumber }),
        )
    }

    /** Median of [fits] per field; flags are true when most fits set them. */
    fun combineGeometry(fits: List<GeometryFit>): GeometryFit {
        require(fits.isNotEmpty()) { "Nothing to combine." }
        return GeometryFit(
            median(fits.map { it.tx }), median(fits.map { it.ty }),
            median(fits.map { it.scale }), median(fits.map { it.rotationRad }),
            median(fits.map { it.residualPx }),
            median(fits.map { it.inliers.toFloat() }).toInt(),
            median(fits.map { it.patchesUsed.toFloat() }).toInt(),
            fits.count { it.parallaxSuspect } * 2 > fits.size,
            fits.count { it.clamped } * 2 > fits.size,
            fits[0].aspect,
        )
    }

    private fun median(v: List<Float>): Float {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }
}
