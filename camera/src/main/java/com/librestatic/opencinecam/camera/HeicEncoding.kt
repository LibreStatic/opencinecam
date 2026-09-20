/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Display dimensions stay exact; HEIF grid decoding trims only right/bottom padding. */
internal data class HeicEncodingGrid(val width: Int, val height: Int, val tileWidth: Int, val tileHeight: Int) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS)
        require(tileWidth > 0 && tileHeight > 0 && tileWidth % 2 == 0 && tileHeight % 2 == 0)
        require(tileWidth.toLong() * tileHeight <= MAX_PIXELS)
    }
    val columns = ((width.toLong() + tileWidth - 1) / tileWidth).toInt()
    val rows = ((height.toLong() + tileHeight - 1) / tileHeight).toInt()
    val count: Int = (columns.toLong() * rows).also { require(it <= 4096) }.toInt()
    fun origin(index: Int): Pair<Int, Int> {
        require(index in 0 until count)
        return index % columns * tileWidth to index / columns * tileHeight
    }
    companion object {
        const val MAX_PIXELS = 16 * 1024 * 1024
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}

internal fun heicCodecQuality(quality: Int, lower: Int, upper: Int): Int {
    require(quality in 1..100 && lower >= 0 && upper > lower)
    return (lower.toLong() + (upper.toLong() - lower) * quality / 100).toInt()
}

/** BT.601 limited-range SDR matrix, paired with matching codec color-aspect keys. */
internal fun heicLuma(rgb: Int): Int {
    val r = rgb ushr 16 and 255; val g = rgb ushr 8 and 255; val b = rgb and 255
    return ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
}
internal fun heicChroma(r: Int, g: Int, b: Int, u: Boolean): Int =
    if (u) (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240)
    else (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240)

/** Checked addressing also supports interleaved U/V planes and truncated final-row padding. */
internal fun heicPlaneOffset(x: Int, y: Int, rowStride: Int, pixelStride: Int, base: Int, limit: Int): Int {
    require(x >= 0 && y >= 0 && rowStride > 0 && pixelStride > 0 && base >= 0)
    val offset = base.toLong() + y.toLong() * rowStride + x.toLong() * pixelStride
    require(offset < limit && offset <= Int.MAX_VALUE) { "HEIC input plane is truncated" }
    return offset.toInt()
}

/** Retirement never replaces the identity of a cancellation or earlier codec/write failure. */
internal inline fun <T> heicRetiring(retire: () -> Unit, block: () -> T): T {
    var primary: Throwable? = null
    try { return block() } catch (failure: Throwable) { primary = failure; throw failure }
    finally {
        try { retire() } catch (failure: Throwable) {
            if (primary == null) throw failure else if (failure !== primary) primary.addSuppressed(failure)
        }
    }
}

/** Two RGB rows only. Edge replication initializes every padding pixel, without scaling content. */
internal fun writeHeicYuv420(sourceWidth: Int, sourceHeight: Int, originX: Int, originY: Int,
    width: Int, height: Int, readRow: (Int, Int, Int, IntArray) -> Unit,
    put: (Int, Int, Int, Int) -> Unit, checkRunning: () -> Unit) {
    require(sourceWidth > 0 && sourceHeight > 0 && originX in 0 until sourceWidth && originY in 0 until sourceHeight)
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
    require(width.toLong() * height <= HeicEncodingGrid.MAX_PIXELS)
    checkRunning()
    val rows = arrayOf(IntArray(width), IntArray(width))
    val copyWidth = minOf(width, sourceWidth - originX)
    for (y in 0 until height step 2) {
        checkRunning()
        for (row in 0..1) {
            val sourceY = minOf(originY.toLong() + y + row, (sourceHeight - 1).toLong()).toInt()
            readRow(originX, sourceY, copyWidth, rows[row])
            java.util.Arrays.fill(rows[row], copyWidth, width, rows[row][copyWidth - 1])
            for (x in 0 until width) {
                if (x % 1024 == 0) checkRunning()
                put(0, x, y + row, heicLuma(rows[row][x]))
            }
        }
        for (x in 0 until width step 2) {
            if (x % 1024 == 0) checkRunning()
            var r = 0; var g = 0; var b = 0
            for (row in 0..1) for (column in 0..1) {
                val rgb = rows[row][x + column]
                r += rgb ushr 16 and 255; g += rgb ushr 8 and 255; b += rgb and 255
            }
            put(1, x / 2, y / 2, heicChroma((r + 2) / 4, (g + 2) / 4, (b + 2) / 4, true))
            put(2, x / 2, y / 2, heicChroma((r + 2) / 4, (g + 2) / 4, (b + 2) / 4, false))
        }
    }
    checkRunning()
}
