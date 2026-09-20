/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

enum class MonitorColor { CYAN, YELLOW, RED, GREEN, WHITE }
enum class FalseColorPalette { CLASSIC, HIGH_CONTRAST }
enum class MonitorAspectGuide(val width: Int, val height: Int) {
    NONE(0, 0), WIDE(16, 9), PHOTO(4, 3), SQUARE(1, 1), CINEMA(239, 100)
}
/** Signal coordinates, not a claim of sensor exposure, HDR delivery or display colorimetry. */
enum class MonitoringSignalDomain { ISP_YUV_ESTIMATED_SDR, SDR_BT709_CODE, OCLOG2_CODE }

data class MonitoringOptions(
    val waveformEnabled: Boolean = false,
    val vectorscopeEnabled: Boolean = false,
    val falseColorEnabled: Boolean = false,
    val zebraHighPercent: Int = 92,
    val zebraShadowEnabled: Boolean = false,
    val zebraLowPercent: Int = 5,
    val peakingThreshold: Int = 35,
    val opacityPercent: Int = 55,
    val zebraColor: MonitorColor = MonitorColor.YELLOW,
    val peakingColor: MonitorColor = MonitorColor.CYAN,
    val lumaColor: MonitorColor = MonitorColor.WHITE,
    val falseColorPalette: FalseColorPalette = FalseColorPalette.CLASSIC,
    val falseColorBlackPercent: Int = 5,
    val falseColorShadowPercent: Int = 25,
    val falseColorHighlightPercent: Int = 75,
    val falseColorClipPercent: Int = 95,
    val refreshHz: Int = 4,
    val aspectGuide: MonitorAspectGuide = MonitorAspectGuide.NONE,
    val safeAreaEnabled: Boolean = false,
    val safeAreaPercent: Int = 90,
) {
    init {
        require(zebraLowPercent in 0..99 && zebraHighPercent in 1..100 && zebraLowPercent < zebraHighPercent)
        require(peakingThreshold in 1..255 && opacityPercent in 10..100 && refreshHz in 1..10)
        require(falseColorBlackPercent in 0..99 && falseColorClipPercent in 1..100 &&
            falseColorBlackPercent < falseColorShadowPercent && falseColorShadowPercent < falseColorHighlightPercent &&
            falseColorHighlightPercent < falseColorClipPercent)
        require(safeAreaPercent in 50..100)
    }
    val periodMs: Long get() = 1000L / refreshHz
    val staleAfterMs: Long get() = maxOf(1000L, periodMs * 3)
}

fun monitoringSampleFresh(sampleAtMs: Long, nowMs: Long, options: MonitoringOptions): Boolean =
    sampleAtMs > 0 && nowMs >= sampleAtMs && nowMs - sampleAtMs <= options.staleAfterMs
