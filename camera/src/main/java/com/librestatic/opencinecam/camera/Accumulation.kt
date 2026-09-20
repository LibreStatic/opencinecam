/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.Collections
import kotlin.math.pow
import kotlin.math.roundToInt

/** Algorithms operate on decoded sRGB in linear light. BULB is simulated, not continuous shutter. */
enum class AccumulationMode { LIGHT, WATER, STARS, BULB }
data class AccumulationSelection(
    val mode: AccumulationMode = AccumulationMode.LIGHT,
    val durationMs: Long = 10_000,
    val intervalMs: Long = 250,
    val maxEdge: Int = 2048,
    val starsThreshold: Int = 16,
) {
    init {
        require(durationMs in 1_000L..300_000L)
        require(intervalMs in 100L..10_000L && intervalMs <= durationMs / 2)
        require(maxEdge in setOf(720, 1080, 2048))
        require(starsThreshold in 0..255)
    }
}

data class AccumulationFrame(val index: Int, val captureId: Long, val sensorTimestampNs: Long,
    val exposureTimeNs: Long?, val sensitivityIso: Int?) {
    init {
        require(index in 0 until CapturedAccumulation.MAX_FRAMES && captureId > 0 && sensorTimestampNs > 0)
        require(exposureTimeNs == null || exposureTimeNs > 0)
        require(sensitivityIso == null || sensitivityIso > 0)
    }
}

class CapturedAccumulation(val id: Long, val selection: AccumulationSelection,
    frames: List<AccumulationFrame>, val image: StillImagePayload,
    val orientationDegrees: Int, val quality: Int, val completedByUser: Boolean) {
    val frames: List<AccumulationFrame> = Collections.unmodifiableList(frames.toList())
    init {
        require(id > 0 && this.frames.size in 2..MAX_FRAMES)
        require(this.frames.map { it.index } == this.frames.indices.toList())
        require(this.frames.map { it.captureId }.distinct().size == this.frames.size)
        require(this.frames.zipWithNext().all { (a, b) -> a.sensorTimestampNs < b.sensorTimestampNs })
        require(image.kind == StillImageKind.JPEG && image.byteCount <= MAX_ENCODED_BYTES)
        require(image.width <= selection.maxEdge && image.height <= selection.maxEdge)
        require(orientationDegrees == 0 && quality in 1..100)
    }
    companion object {
        const val MAX_FRAMES = 4096
        const val MAX_ENCODED_BYTES = 32 * 1024 * 1024
        const val MAX_PIXELS = 2048 * 2048
        const val MAX_SOURCE_PIXELS = 128L * 1024 * 1024
    }
}

/** Incremental RGB only: at most 48 MiB accumulator, one input/output pixel buffer each.
 * A cancelled/failed add poisons its partial accumulator; no partially updated result escapes. */
internal class LinearLightAccumulator(val width: Int, val height: Int, private val selection: AccumulationSelection) {
    private var channels: FloatArray?
    var frameCount = 0
        private set
    private var poisoned = false
    init {
        require(width > 0 && height > 0 && width <= selection.maxEdge && height <= selection.maxEdge)
        val pixels = width.toLong() * height
        require(pixels <= CapturedAccumulation.MAX_PIXELS)
        channels = FloatArray(pixels.toInt() * 3)
    }
    fun add(argb: IntArray, checkRunning: () -> Unit = {}) {
        val data = checkNotNull(channels) { "Accumulator is closed" }
        check(!poisoned && frameCount < CapturedAccumulation.MAX_FRAMES)
        require(argb.size.toLong() == width.toLong() * height)
        try {
            checkRunning()
            argb.forEachIndexed { index, color ->
                if (index and 1023 == 0) checkRunning()
                val r = LINEAR[(color ushr 16) and 255]
                val g = LINEAR[(color ushr 8) and 255]
                val b = LINEAR[color and 255]
                val offset = index * 3
                when {
                    frameCount == 0 -> { data[offset] = r; data[offset + 1] = g; data[offset + 2] = b }
                    selection.mode == AccumulationMode.LIGHT -> {
                        data[offset] = maxOf(data[offset], r)
                        data[offset + 1] = maxOf(data[offset + 1], g)
                        data[offset + 2] = maxOf(data[offset + 2], b)
                    }
                    selection.mode == AccumulationMode.WATER -> {
                        val count = frameCount + 1f
                        data[offset] += (r - data[offset]) / count
                        data[offset + 1] += (g - data[offset + 1]) / count
                        data[offset + 2] += (b - data[offset + 2]) / count
                    }
                    selection.mode == AccumulationMode.STARS -> {
                        val luminance = .2126f * r + .7152f * g + .0722f * b
                        val previous = .2126f * data[offset] + .7152f * data[offset + 1] + .0722f * data[offset + 2]
                        if (luminance > selection.starsThreshold / 255f && luminance > previous) {
                            data[offset] = r; data[offset + 1] = g; data[offset + 2] = b
                        }
                    }
                    else -> {
                        data[offset] = (data[offset] + r).coerceAtMost(1f)
                        data[offset + 1] = (data[offset + 1] + g).coerceAtMost(1f)
                        data[offset + 2] = (data[offset + 2] + b).coerceAtMost(1f)
                    }
                }
            }
            checkRunning()
            frameCount++
        } catch (failure: Throwable) { poisoned = true; throw failure }
    }
    fun pixels(checkRunning: () -> Unit = {}): IntArray {
        val data = checkNotNull(channels) { "Accumulator is closed" }
        check(!poisoned && frameCount >= 2) { "At least two complete frames are required" }
        checkRunning()
        return IntArray(width * height) { index ->
            if (index and 1023 == 0) checkRunning()
            val offset = index * 3
            (255 shl 24) or (encode(data[offset]) shl 16) or (encode(data[offset + 1]) shl 8) or encode(data[offset + 2])
        }.also { checkRunning() }
    }
    fun close() { channels = null; poisoned = true }
    companion object {
        private val LINEAR = FloatArray(256) { value ->
            val encoded = value / 255.0
            (if (encoded <= .04045) encoded / 12.92 else ((encoded + .055) / 1.055).pow(2.4)).toFloat()
        }
        private fun encode(value: Float): Int {
            val linear = value.toDouble().coerceIn(0.0, 1.0)
            return ((if (linear <= .0031308) linear * 12.92 else 1.055 * linear.pow(1 / 2.4) - .055) * 255).roundToInt().coerceIn(0, 255)
        }
    }
}

/** Power-of-two sampling guarantees no upscaling and decoded edges no larger than the bound. */
internal fun accumulationSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    require(width > 0 && height > 0 && maxEdge in setOf(720, 1080, 2048))
    require(width.toLong() * height <= CapturedAccumulation.MAX_SOURCE_PIXELS)
    var sample = 1
    while ((maxOf(width, height).toLong() + sample - 1) / sample > maxEdge) sample *= 2
    return sample
}

/** EXIF orientations 1..8; result dimensions swap for 5..8. Caller owns both arrays. */
internal fun orientAccumulationPixels(pixels: IntArray, width: Int, height: Int, orientation: Int,
    checkRunning: () -> Unit = {}): IntArray {
    require(width > 0 && height > 0 && width.toLong() * height <= CapturedAccumulation.MAX_PIXELS)
    require(pixels.size.toLong() == width.toLong() * height && orientation in 1..8)
    checkRunning()
    if (orientation == 1) return pixels
    val outputWidth = if (orientation >= 5) height else width
    val output = IntArray(pixels.size)
    for (y in 0 until height) for (x in 0 until width) {
        val source = y * width + x
        if (source and 1023 == 0) checkRunning()
        val xx = when (orientation) {
            2, 3 -> width - 1 - x
            4 -> x
            5, 8 -> y
            else -> height - 1 - y
        }
        val yy = when (orientation) {
            2 -> y
            3, 4 -> height - 1 - y
            5, 6 -> x
            else -> width - 1 - x
        }
        output[yy * outputWidth + xx] = pixels[source]
    }
    checkRunning()
    return output
}
