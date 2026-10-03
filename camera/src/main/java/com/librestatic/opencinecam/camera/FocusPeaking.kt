/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer

/**
 * In-focus edges of one analysis frame, one bit per pixel, row-major.
 *
 * Bit (x, y) is set where the luma step to the right or downward neighbour reaches the operator's
 * threshold, so it marks the boundary between x and x + 1 (and y and y + 1); a drawer moves it
 * half a pixel right and down. [domain] names the raster: ISP_YUV is the sensor-oriented YUV
 * analysis stream, which the HAL crops to its own aspect; the GPU domains are the readback of the
 * monitored source, which covers exactly that source.
 *
 * Equality compares the bits, so an unchanged mask neither recomposes nor rebuilds its image.
 */
class FocusPeakingMask(
    val width: Int,
    val height: Int,
    private val bits: ByteArray,
    val domain: MonitoringSignalDomain,
) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS)
        require(bits.size == packedSize(width, height))
    }

    val edgeCount: Int = bits.sumOf { Integer.bitCount(it.toInt() and 0xff) }

    private val hash = (31 * (31 * (31 * width + height) + domain.hashCode())) + bits.contentHashCode()

    operator fun get(x: Int, y: Int): Boolean {
        require(x in 0 until width && y in 0 until height)
        val index = y * width + x
        return bits[index ushr 3].toInt() shr (index and 7) and 1 != 0
    }

    /** Row-major ARGB pixels: [color] on an edge, transparent elsewhere. */
    fun toArgb(color: Int): IntArray {
        val pixels = IntArray(width * height)
        for (byteIndex in bits.indices) {
            var byte = bits[byteIndex].toInt() and 0xff
            while (byte != 0) {
                val bit = Integer.numberOfTrailingZeros(byte)
                pixels[(byteIndex shl 3) + bit] = color
                byte = byte and (byte - 1)
            }
        }
        return pixels
    }

    override fun equals(other: Any?): Boolean = this === other || other is FocusPeakingMask &&
        width == other.width && height == other.height && domain == other.domain && hash == other.hash &&
        bits.contentEquals(other.bits)

    override fun hashCode(): Int = hash

    override fun toString(): String = "FocusPeakingMask(${width}x$height, $edgeCount edges, $domain)"

    companion object {
        /** VGA: a 4:3 or 16:9 analysis frame at that size is used pixel for pixel. */
        const val MAX_PIXELS = 640 * 480

        internal fun packedSize(width: Int, height: Int): Int = ((width.toLong() * height + 7) / 8).toInt()

        /** A mask whose edges are wherever [isEdge] says; previews and tests. */
        fun of(width: Int, height: Int, domain: MonitoringSignalDomain, isEdge: (x: Int, y: Int) -> Boolean): FocusPeakingMask {
            val bits = ByteArray(packedSize(width, height))
            for (y in 0 until height) for (x in 0 until width) if (isEdge(x, y)) {
                val index = y * width + x
                bits[index ushr 3] = (bits[index ushr 3].toInt() or (1 shl (index and 7))).toByte()
            }
            return FocusPeakingMask(width, height, bits, domain)
        }
    }
}

/**
 * Edge map of a row-major 8-bit luma raster ([luma] holds at least width × height bytes).
 *
 * The step is |L(x+1, y) − L(x, y)| + |L(x, y+1) − L(x, y)|. Forward differences keep a sharp step
 * one pixel wide, and a defocused edge spreads the same contrast over several pixels, each of
 * which stays under the threshold: that is what separates focus from mere contrast.
 */
fun detectFocusEdges(luma: ByteArray, width: Int, height: Int, threshold: Int, domain: MonitoringSignalDomain): FocusPeakingMask {
    require(width > 0 && height > 0 && luma.size >= width * height && threshold > 0)
    val bits = ByteArray(FocusPeakingMask.packedSize(width, height))
    var accumulator = 0
    var index = 0
    for (y in 0 until height) {
        val row = y * width
        val below = if (y + 1 < height) row + width else -1
        for (x in 0 until width) {
            val center = luma[row + x].toInt() and 0xff
            var step = if (x + 1 < width) kotlin.math.abs((luma[row + x + 1].toInt() and 0xff) - center) else 0
            if (below >= 0) step += kotlin.math.abs((luma[below + x].toInt() and 0xff) - center)
            if (step >= threshold) accumulator = accumulator or (1 shl (index and 7))
            if (index and 7 == 7) { bits[index ushr 3] = accumulator.toByte(); accumulator = 0 }
            index++
        }
    }
    if (index and 7 != 0) bits[index ushr 3] = accumulator.toByte()
    return FocusPeakingMask(width, height, bits, domain)
}

/** Smallest whole subsampling step that keeps a [width] × [height] frame within the mask budget. */
fun focusPeakingStep(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    var step = 1
    while (((width + step - 1) / step).toLong() * ((height + step - 1) / step) > FocusPeakingMask.MAX_PIXELS) step++
    return step
}

/**
 * Copies every [step]-th sample of a YUV luma plane into [out] as a packed (width / step) ×
 * (height / step) raster, rounding up. Whole rows are bulk-copied when the plane is packed.
 * Samples past the buffer's limit (a short final row) read as 0.
 */
fun copyLumaPlane(plane: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int, step: Int, out: ByteArray) {
    require(rowStride > 0 && pixelStride > 0 && width > 0 && height > 0 && step > 0)
    val columns = (width + step - 1) / step
    val rows = (height + step - 1) / step
    require(out.size >= columns * rows)
    val source = plane.duplicate()
    val limit = source.limit()
    for (y in 0 until rows) {
        val start = y * step * rowStride
        val target = y * columns
        if (step == 1 && pixelStride == 1 && start + columns <= limit) {
            source.position(start)
            source.get(out, target, columns)
        } else for (x in 0 until columns) {
            val index = start + x * step * pixelStride
            out[target + x] = if (index < limit) source.get(index) else 0
        }
    }
}

/** A normalised rectangle: left, top, width, height. */
data class NormalizedFrame(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Where a [frameWidth] × [frameHeight] output sits inside a [streamWidth] × [streamHeight] one, in
 * the stream's normalised sensor-raster coordinates. The HAL fills every output by centre-cropping
 * the same crop region ([cropWidth] × [cropHeight], the active array's shape) to that output's
 * aspect, so a 4:3 analysis frame reaches above and below a 16:9 preview (top < 0, height > 1).
 */
fun outputFrameInStream(frameWidth: Int, frameHeight: Int, streamWidth: Int, streamHeight: Int,
    cropWidth: Int, cropHeight: Int): NormalizedFrame {
    require(frameWidth > 0 && frameHeight > 0 && streamWidth > 0 && streamHeight > 0 && cropWidth > 0 && cropHeight > 0)
    /** The share of the crop region's width and height an output of this aspect keeps. */
    fun coverage(width: Int, height: Int): Pair<Double, Double> =
        if (cropWidth.toLong() * height > cropHeight.toLong() * width)
            (cropHeight.toDouble() * width / height) / cropWidth to 1.0
        else 1.0 to (cropWidth.toDouble() * height / width) / cropHeight
    val (frameW, frameH) = coverage(frameWidth, frameHeight)
    val (streamW, streamH) = coverage(streamWidth, streamHeight)
    val width = (frameW / streamW).toFloat()
    val height = (frameH / streamH).toFloat()
    return NormalizedFrame((1f - width) / 2f, (1f - height) / 2f, width, height)
}
