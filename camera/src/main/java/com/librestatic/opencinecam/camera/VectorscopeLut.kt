/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

data class RgbFrame(
    val width: Int,
    val height: Int,
    val rgb: ByteArray,
) {
    init { require(width > 0 && height > 0 && rgb.size == width * height * 3) }
}

data class VectorScopePoint(val x: Double, val y: Double, val count: Int) {
    init { require(x in 0.0..1.0 && y in 0.0..1.0 && count > 0) }
}

sealed interface VectorScopeResult {
    data class Computed(val points: List<VectorScopePoint>) : VectorScopeResult
    data class Rejected(val reason: String) : VectorScopeResult
}

class VectorScopeComputer(private val maxPixels: Int = 320 * 180, private val gridSize: Int = 32) {
    init { require(maxPixels > 0 && gridSize > 1) }

    fun compute(frame: RgbFrame): VectorScopeResult {
        if (frame.rgb.size / 3 > maxPixels) return VectorScopeResult.Rejected("Vectorscope frame exceeds the pixel budget.")
        val bins = IntArray(gridSize * gridSize)
        for (index in frame.rgb.indices step 3) {
            val red = (frame.rgb[index].toInt() and 0xff) / 255.0
            val green = (frame.rgb[index + 1].toInt() and 0xff) / 255.0
            val blue = (frame.rgb[index + 2].toInt() and 0xff) / 255.0
            val chromaX = ((blue - red) / 2.0 + 0.5).coerceIn(0.0, 1.0)
            val chromaY = ((2.0 * green - red - blue) / 2.0 + 0.5).coerceIn(0.0, 1.0)
            val x = (chromaX * (gridSize - 1)).toInt()
            val y = (chromaY * (gridSize - 1)).toInt()
            bins[y * gridSize + x]++
        }
        val points = mutableListOf<VectorScopePoint>()
        bins.forEachIndexed { index, count ->
            if (count > 0) points += VectorScopePoint((index % gridSize).toDouble() / (gridSize - 1), (index / gridSize).toDouble() / (gridSize - 1), count)
        }
        return VectorScopeResult.Computed(points)
    }
}

data class LutProvenance(
    val id: String,
    val source: String,
    val version: String,
) {
    init { require(id.isNotBlank() && source.isNotBlank() && version.isNotBlank()) }
}

data class MonitoringLut(
    val provenance: LutProvenance,
    val table: ByteArray,
) {
    init { require(table.size >= 2) }
}

data class MonitoringFrame(
    val data: ByteArray,
    val provenance: LutProvenance,
    val recordingBufferUntouched: Boolean,
)

sealed interface MonitoringLutResult {
    data class Applied(val frame: MonitoringFrame) : MonitoringLutResult
    data class Rejected(val reason: String) : MonitoringLutResult
}

/** Monitoring-only LUT boundary: it always copies input and never mutates recorded buffers. */
class MonitoringLutBoundary(private val maxBytes: Int = 4 * 1024 * 1024) {
    fun apply(input: ByteArray, lut: MonitoringLut, enabled: Boolean = true): MonitoringLutResult {
        if (!enabled) return MonitoringLutResult.Applied(MonitoringFrame(input.copyOf(), lut.provenance, true))
        if (input.size > maxBytes) return MonitoringLutResult.Rejected("Monitoring LUT input exceeds the pixel budget.")
        val output = ByteArray(input.size)
        input.forEachIndexed { index, value ->
            val normalized = value.toInt() and 0xff
            val lutIndex = normalized * (lut.table.size - 1) / 255
            output[index] = lut.table[lutIndex]
        }
        return MonitoringLutResult.Applied(MonitoringFrame(output, lut.provenance, true))
    }
}
