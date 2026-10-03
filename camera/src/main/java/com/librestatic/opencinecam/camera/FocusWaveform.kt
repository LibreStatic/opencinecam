/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

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
