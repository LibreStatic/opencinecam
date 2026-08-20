/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

data class FocusPeakingControls(
    val enabled: Boolean = true,
    val threshold: Double = 0.35,
    val maxPoints: Int = 2_048,
) {
    init { require(threshold in 0.0..1.0 && maxPoints > 0) }
}

data class PeakPoint(val x: Double, val y: Double, val strength: Double) {
    init { require(x in 0.0..1.0 && y in 0.0..1.0 && strength in 0.0..1.0) }
}

sealed interface FocusPeakingResult {
    data class Computed(val points: List<PeakPoint>) : FocusPeakingResult
    data object Disabled : FocusPeakingResult
    data class Rejected(val reason: String) : FocusPeakingResult
}

class FocusPeakingComputer(private val maxPixels: Int = 320 * 180) {
    init { require(maxPixels > 0) }

    fun compute(frame: LumaFrame, controls: FocusPeakingControls = FocusPeakingControls()): FocusPeakingResult {
        if (!controls.enabled) return FocusPeakingResult.Disabled
        if (frame.luma.size > maxPixels) return FocusPeakingResult.Rejected("Focus-peaking frame exceeds the pixel budget.")
        if (frame.width < 2 || frame.height < 2) return FocusPeakingResult.Rejected("Focus peaking requires at least a 2x2 frame.")
        val points = ArrayList<PeakPoint>(controls.maxPoints)
        for (y in 0 until frame.height - 1) {
            for (x in 0 until frame.width - 1) {
                val index = y * frame.width + x
                val current = (frame.luma[index].toInt() and 0xff) / 255.0
                val right = (frame.luma[index + 1].toInt() and 0xff) / 255.0
                val down = (frame.luma[index + frame.width].toInt() and 0xff) / 255.0
                val strength = (kotlin.math.abs(right - current) + kotlin.math.abs(down - current)) / 2.0
                if (strength >= controls.threshold) {
                    points += PeakPoint(x.toDouble() / (frame.width - 1), y.toDouble() / (frame.height - 1), strength.coerceIn(0.0, 1.0))
                    if (points.size == controls.maxPoints) return FocusPeakingResult.Computed(points)
                }
            }
        }
        return FocusPeakingResult.Computed(points)
    }
}

data class WaveformColumn(val minimum: Double, val average: Double, val maximum: Double) {
    init { require(minimum in 0.0..1.0 && average in 0.0..1.0 && maximum in 0.0..1.0 && minimum <= average && average <= maximum) }
}

sealed interface WaveformResult {
    data class Computed(val columns: List<WaveformColumn>) : WaveformResult
    data object Disabled : WaveformResult
    data class Rejected(val reason: String) : WaveformResult
}

class WaveformComputer(private val maxPixels: Int = 320 * 180) {
    fun compute(frame: LumaFrame, columns: Int = 64, enabled: Boolean = true): WaveformResult {
        if (!enabled) return WaveformResult.Disabled
        if (frame.luma.size > maxPixels) return WaveformResult.Rejected("Waveform frame exceeds the pixel budget.")
        require(columns > 0)
        val output = (0 until columns).map { column ->
            val startX = column * frame.width / columns
            val endX = ((column + 1) * frame.width / columns).coerceAtLeast(startX + 1).coerceAtMost(frame.width)
            var min = 1.0
            var max = 0.0
            var total = 0.0
            var count = 0
            for (y in 0 until frame.height) for (x in startX until endX) {
                val value = (frame.luma[y * frame.width + x].toInt() and 0xff) / 255.0
                min = minOf(min, value)
                max = maxOf(max, value)
                total += value
                count++
            }
            WaveformColumn(min, total / count, max)
        }
        return WaveformResult.Computed(output)
    }
}
