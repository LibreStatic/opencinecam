/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

enum class FalseColorBand { BLACK, SHADOW, MID, HIGHLIGHT, CLIP }

data class ScopeThresholds(
    val black: Double = 0.05,
    val shadow: Double = 0.25,
    val highlight: Double = 0.75,
    val clip: Double = 0.95,
) {
    init { require(black in 0.0..1.0 && shadow in 0.0..1.0 && highlight in 0.0..1.0 && clip in 0.0..1.0 && black <= shadow && shadow <= highlight && highlight <= clip) }
}

data class LumaFrame(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
) {
    init { require(width > 0 && height > 0 && luma.size == width * height) }
}

data class Histogram(
    val bins: IntArray,
    val totalSamples: Int,
) {
    init { require(bins.size == 256 && bins.all { it >= 0 } && totalSamples >= 0) }
}

data class ColorScopeOverlay(
    val zebra: BooleanArray,
    val falseColor: Array<FalseColorBand>,
    val thresholds: ScopeThresholds,
) {
    init { require(zebra.size == falseColor.size) }
}

sealed interface ScopeComputation {
    data class Computed(val histogram: Histogram, val overlay: ColorScopeOverlay) : ScopeComputation
    data class Rejected(val reason: String) : ScopeComputation
}

/** Pure, bounded luma analysis for histogram/zebra/false-color overlays. */
class ColorScopeComputer(
    private val maxPixels: Int = 320 * 180,
    private val thresholds: ScopeThresholds = ScopeThresholds(),
) {
    init { require(maxPixels > 0) }

    fun compute(frame: LumaFrame): ScopeComputation {
        if (frame.luma.size > maxPixels) return ScopeComputation.Rejected("Analysis frame exceeds the scope pixel budget.")
        val bins = IntArray(256)
        val zebra = BooleanArray(frame.luma.size)
        val falseColor = Array(frame.luma.size) { FalseColorBand.MID }
        frame.luma.forEachIndexed { index, valueByte ->
            val value = valueByte.toInt() and 0xff
            bins[value]++
            val normalized = value / 255.0
            zebra[index] = normalized >= thresholds.clip || normalized <= thresholds.black
            falseColor[index] = when {
                normalized <= thresholds.black -> FalseColorBand.BLACK
                normalized <= thresholds.shadow -> FalseColorBand.SHADOW
                normalized < thresholds.highlight -> FalseColorBand.MID
                normalized < thresholds.clip -> FalseColorBand.HIGHLIGHT
                else -> FalseColorBand.CLIP
            }
        }
        return ScopeComputation.Computed(Histogram(bins, frame.luma.size), ColorScopeOverlay(zebra, falseColor, thresholds))
    }
}
