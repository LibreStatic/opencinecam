/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.Collections

/** Display-ready row-major scope grids in the declared signal domain, never physical exposure.
 * Waveform row zero is high luma; vectorscope +Cb is right and +Cr is up. Empty means disabled.
 * Counts refer to original input samples; false-color cells average spatial regions independently.
 */
class MonitoringScopeFrame(
    waveformDensity: List<Int>,
    vectorscopeCounts: List<Int>,
    falseColorBands: List<FalseColorBand>,
    val sampleCount: Int,
    val domain: MonitoringSignalDomain,
    val sampledWidth: Int,
    val sampledHeight: Int,
    val options: MonitoringOptions = MonitoringOptions(),
) {
    init {
        require(sampledWidth > 0 && sampledHeight > 0 && sampleCount in 1..MAX_PIXELS &&
            sampledWidth.toLong() * sampledHeight == sampleCount.toLong())
        require(waveformDensity.size in setOf(0, GRID_SIZE * GRID_SIZE) &&
            vectorscopeCounts.size in setOf(0, GRID_SIZE * GRID_SIZE) &&
            falseColorBands.size in setOf(0, FALSE_COLOR_WIDTH * FALSE_COLOR_HEIGHT))
    }
    val waveformDensity: List<Int> = Collections.unmodifiableList(waveformDensity.toList())
    val vectorscopeCounts: List<Int> = Collections.unmodifiableList(vectorscopeCounts.toList())
    val falseColorBands: List<FalseColorBand> = Collections.unmodifiableList(falseColorBands.toList())
    init {
        // The size checks live only in the first init. Repeating them let R8 reuse one cmp-long
        // result across calls, and ART's JIT drops that result from deopt frames, so the repeat
        // failed after a deopt (docs/performance/capture-jank/README.md, "R8 and the scope crash").
        for (counts in listOf(this.waveformDensity, this.vectorscopeCounts)) {
            require(counts.isEmpty() || counts.size == GRID_SIZE * GRID_SIZE)
            require(counts.all { it >= 0 } && (counts.isEmpty() || counts.sumOf { it.toLong() } == sampleCount.toLong()))
        }
        require(this.falseColorBands.isEmpty() || this.falseColorBands.size == FALSE_COLOR_WIDTH * FALSE_COLOR_HEIGHT)
    }
    companion object {
        const val GRID_SIZE = 64
        const val FALSE_COLOR_WIDTH = 64
        const val FALSE_COLOR_HEIGHT = 36
        const val MAX_PIXELS = 320 * 180
    }
}

/** Packed RGB8 input is top-down and read-only. No transfer-function conversion is implied.
 * Waveform/false-color luma uses (54R + 183G + 19B + 128) >> 8. Chroma uses exact BT.709
 * Kr=.2126 and Kb=.0722, with quantization/saturation only at the final 64x64 grid boundary.
 */
fun analyzeMonitoringRgb(width: Int, height: Int, rgb: ByteArray, options: MonitoringOptions,
    domain: MonitoringSignalDomain): MonitoringScopeFrame {
    require(width > 0 && height > 0)
    val pixels = width.toLong() * height
    require(pixels in 1..MonitoringScopeFrame.MAX_PIXELS.toLong() && rgb.size.toLong() == pixels * 3)
    val count = pixels.toInt()
    val waveform = if (options.waveformEnabled) IntArray(64 * 64) else IntArray(0)
    val vectors = if (options.vectorscopeEnabled) IntArray(64 * 64) else IntArray(0)
    val luma = if (options.falseColorEnabled) ByteArray(count) else ByteArray(0)
    if (options.waveformEnabled || options.vectorscopeEnabled || options.falseColorEnabled) {
        for (index in 0 until count) {
            val offset = index * 3
            val r = rgb[offset].toInt() and 255
            val g = rgb[offset + 1].toInt() and 255
            val b = rgb[offset + 2].toInt() and 255
            val y = (54 * r + 183 * g + 19 * b + 128) shr 8
            if (options.falseColorEnabled) luma[index] = y.toByte()
            if (options.waveformEnabled) {
                val x = (index % width).toLong() * 64 / width
                waveform[(63 - y / 4) * 64 + x.toInt()]++
            }
            if (options.vectorscopeEnabled) {
                // Integer numerator keeps all neutral values exactly centered (no float drift).
                val yNumerator = 2126 * r + 7152 * g + 722 * b
                val cb = (10000 * b - yNumerator).toDouble() / (2 * 9278 * 255)
                val cr = (10000 * r - yNumerator).toDouble() / (2 * 7874 * 255)
                val x = ((0.5 + cb) * 64).toInt().coerceIn(0, 63)
                val row = ((0.5 - cr) * 64).toInt().coerceIn(0, 63)
                vectors[row * 64 + x]++
            }
        }
    }
    val bands = if (options.falseColorEnabled) List(64 * 36) { cell ->
        val left = ((cell % 64).toLong() * width / 64).toInt()
        val top = ((cell / 64).toLong() * height / 36).toInt()
        // For smaller inputs an otherwise empty cell samples its nearest source pixel.
        val right = maxOf(left + 1, ((cell % 64 + 1).toLong() * width / 64).toInt()).coerceAtMost(width)
        val bottom = maxOf(top + 1, ((cell / 64 + 1).toLong() * height / 36).toInt()).coerceAtMost(height)
        var sum = 0L
        for (y in top until bottom) for (x in left until right) sum += luma[y * width + x].toInt() and 255
        val area = (right - left).toLong() * (bottom - top)
        val value = sum * 100
        val scale = area * 255
        when {
            value <= options.falseColorBlackPercent * scale -> FalseColorBand.BLACK
            value <= options.falseColorShadowPercent * scale -> FalseColorBand.SHADOW
            value < options.falseColorHighlightPercent * scale -> FalseColorBand.MID
            value < options.falseColorClipPercent * scale -> FalseColorBand.HIGHLIGHT
            else -> FalseColorBand.CLIP
        }
    } else emptyList()
    return MonitoringScopeFrame(waveform.toList(), vectors.toList(), bands, count, domain, width, height, options)
}
