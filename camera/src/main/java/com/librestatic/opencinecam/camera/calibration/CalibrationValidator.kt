/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

enum class CalibrationIssue { LOW_TEXTURE, LOW_CHROMA, CLIPPED, UNSTABLE_AE, PARALLAX_SUSPECT, DIAGONAL_FALLBACK }

/** [score] in 0..1 (1 = trustworthy) and the [issues] that lowered it. */
data class CalibrationVerdict(val score: Float, val issues: Set<CalibrationIssue>)

/**
 * Judges whether a calibration capture can be trusted. Thresholds:
 * - LOW_TEXTURE: a geometry fit exists but is not [GeometryFit.usable] (under 5 inlier patches);
 *   a null geometry means geometry was not attempted and raises nothing.
 * - LOW_CHROMA: under 10 % of the reference cells (Y >= 0.01) have saturation (max - min) / max
 *   above 0.15, or the condition number of the colour fit's chroma system exceeds 1000.
 * - CLIPPED: more than 25 % of the cells clip over 2 % of their pixels in either frame.
 * - UNSTABLE_AE: [aeStable] is false.
 * - PARALLAX_SUSPECT: the geometry fit says so.
 * - DIAGONAL_FALLBACK: the colour fit fell back to identity.
 * The score starts at 1, loses 0.25 / 0.15 / 0.2 / 0.3 / 0.2 / 0.15 for those issues in that order,
 * is divided by 1 + max(0, residual dE - 3) / 10, and is 0 when no cell was usable.
 */
object CalibrationValidator {
    private const val CHROMA_SATURATION = 0.15f
    private const val CHROMA_CELL_FRACTION = 0.10f
    private const val CLIP_CELL_FRACTION = 0.25f
    private const val CLIP_CELL_PIXELS = 0.02f
    private const val DELTA_E_FREE = 3f

    fun evaluate(
        color: ColorFit,
        geometry: GeometryFit?,
        referenceCells: List<CellStat>,
        targetCells: List<CellStat>,
        aeStable: Boolean,
    ): CalibrationVerdict {
        val issues = linkedSetOf<CalibrationIssue>()
        var score = 1f
        fun flag(i: CalibrationIssue, penalty: Float) { issues += i; score -= penalty }

        if (geometry != null && !geometry.usable) flag(CalibrationIssue.LOW_TEXTURE, 0.25f)
        val lit = referenceCells.filter { it.luma >= 0.01f }
        val coloured = lit.count { c ->
            val mx = maxOf(c.r, c.g, c.b)
            mx > 0f && (mx - minOf(c.r, c.g, c.b)) / mx > CHROMA_SATURATION
        }
        if (lit.isEmpty() || coloured < CHROMA_CELL_FRACTION * lit.size || color.conditionNumber > 1e3f) flag(CalibrationIssue.LOW_CHROMA, 0.15f)
        val cellCount = minOf(referenceCells.size, targetCells.size)
        val clipped = (0 until cellCount).count {
            referenceCells[it].clipFraction > CLIP_CELL_PIXELS || targetCells[it].clipFraction > CLIP_CELL_PIXELS
        }
        if (cellCount > 0 && clipped > CLIP_CELL_FRACTION * cellCount) flag(CalibrationIssue.CLIPPED, 0.2f)
        if (!aeStable) flag(CalibrationIssue.UNSTABLE_AE, 0.3f)
        if (geometry?.parallaxSuspect == true) flag(CalibrationIssue.PARALLAX_SUSPECT, 0.2f)
        if (color.diagonalFallback) flag(CalibrationIssue.DIAGONAL_FALLBACK, 0.15f)

        score = score.coerceIn(0f, 1f) / (1f + maxOf(0f, color.residualDeltaE - DELTA_E_FREE) / 10f)
        if (color.cellsUsed == 0) score = 0f
        return CalibrationVerdict(score.coerceIn(0f, 1f), issues)
    }
}
