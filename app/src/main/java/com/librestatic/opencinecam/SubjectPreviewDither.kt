/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

private const val DITHER_SIDE = 4

// 4x4 Bayer matrix, row-major, values 0..15.
private val Bayer4 = intArrayOf(
    0, 8, 2, 10,
    12, 4, 14, 6,
    3, 11, 1, 9,
    15, 7, 13, 5,
)

/** Which of the 16 cells of the dither tile are black when [level] percent of the pixels stay visible. */
internal fun ditherBlackCells(level: Int): BooleanArray {
    val visible = (level.coerceIn(0, 100) / 100f * Bayer4.size).roundToInt()
    return BooleanArray(Bayer4.size) { Bayer4[it] >= visible }
}

private fun ditherTile(level: Int): ImageBitmap {
    val bitmap = ImageBitmap(DITHER_SIDE, DITHER_SIDE, ImageBitmapConfig.Argb8888)
    val black = ditherBlackCells(level)
    val pixels = IntArray(Bayer4.size) { if (black[it]) Color.Black.toArgb() else Color.Transparent.toArgb() }
    return bitmap.also { it.asAndroidBitmap().setPixels(pixels, 0, DITHER_SIDE, 0, 0, DITHER_SIDE, DITHER_SIDE) }
}

/**
 * Draws a repeating black pixel mask over the camera preview so only [level] percent of its pixels show.
 * The tile is unscaled, so one tile cell is one device pixel.
 */
@Composable
fun PreviewDitherMask(level: Int, modifier: Modifier = Modifier) {
    if (level >= 100) return
    val brush: Brush = remember(level) {
        ShaderBrush(ImageShader(ditherTile(level), TileMode.Repeated, TileMode.Repeated))
    }
    Canvas(modifier) { drawRect(brush) }
}
