/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * Capture chrome symbols. They are drawn rather than typed, so they do not depend on the
 * platform font's coverage of Unicode glyphs, and they keep the stroke language of the operator
 * buttons without adding an icon library to the reproducible build.
 */
internal enum class CineIcon {
    MEDIA,
    DISPLAYS,
    RETURN,
    TORCH,
    SETTINGS,
    SWITCH_CAMERA,
    MONITORING,
    ZEBRA,
    PEAKING,
    HISTOGRAM,
    HISTOGRAM_MODE,
    GRID,
    GRID_MODE,
    LEVEL,
    WARNING,
    CHECK,
    CAMERA,
    VIDEO,
    AUDIO,
    CONTROLS,
    CLOUD,
    INFO,
}

@Composable
internal fun CineGlyph(icon: CineIcon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawCineGlyph(icon, tint) }
}

private fun DrawScope.drawCineGlyph(icon: CineIcon, color: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val sw = w * .085f
    val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(ax: Float, ay: Float, bx: Float, by: Float) =
        drawLine(color, Offset(w * ax, h * ay), Offset(w * bx, h * by), strokeWidth = sw, cap = StrokeCap.Round)
    fun box(l: Float, t: Float, r: Float, b: Float, filled: Boolean = false) =
        drawRect(color, Offset(w * l, h * t), Size(w * (r - l), h * (b - t)), style = if (filled) Fill else stroke)
    fun poly(vararg points: Pair<Float, Float>, close: Boolean = true, filled: Boolean = false) {
        val path = Path()
        points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(w * x, h * y) else path.lineTo(w * x, h * y) }
        if (close) path.close()
        drawPath(path, color, style = if (filled) Fill else stroke)
    }
    when (icon) {
        CineIcon.MEDIA -> {
            box(.12f, .20f, .88f, .80f)
            poly(.20f to .72f, .40f to .48f, .54f to .62f, .64f to .52f, .80f to .72f, close = false)
            drawCircle(color, radius = w * .06f, center = Offset(w * .66f, h * .36f))
        }
        CineIcon.DISPLAYS -> {
            // Two panels sharing a hinge.
            box(.10f, .22f, .47f, .78f)
            box(.53f, .22f, .90f, .78f)
        }
        CineIcon.RETURN -> {
            poly(.78f to .30f, .30f to .30f, close = false)
            poly(.42f to .16f, .26f to .30f, .42f to .44f, close = false)
            poly(.78f to .30f, .78f to .74f, .30f to .74f, close = false)
        }
        CineIcon.TORCH -> {
            // Lightning bolt: the flash/torch control, independent of the torch operator button.
            poly(.58f to .08f, .24f to .56f, .48f to .56f, .40f to .92f, .76f to .42f, .52f to .42f, filled = true)
        }
        CineIcon.SETTINGS -> {
            val outer = w * .40f
            val inner = w * .29f
            val path = Path()
            val teeth = 8
            for (i in 0 until teeth * 2) {
                val radius = if (i % 2 == 0) outer else inner
                val a0 = Math.toRadians((i * 180.0 / teeth) - 11.0)
                val a1 = Math.toRadians((i * 180.0 / teeth) + 11.0)
                val x0 = cx + radius * cos(a0).toFloat()
                val y0 = cy + radius * sin(a0).toFloat()
                if (i == 0) path.moveTo(x0, y0) else path.lineTo(x0, y0)
                path.lineTo(cx + radius * cos(a1).toFloat(), cy + radius * sin(a1).toFloat())
            }
            path.close()
            drawPath(path, color, style = stroke)
            drawCircle(color, radius = w * .12f, center = Offset(cx, cy), style = stroke)
        }
        CineIcon.SWITCH_CAMERA -> {
            drawArc(color, 200f, 150f, false, Offset(w * .18f, h * .18f), Size(w * .64f, h * .64f), style = stroke)
            drawArc(color, 20f, 150f, false, Offset(w * .18f, h * .18f), Size(w * .64f, h * .64f), style = stroke)
            poly(.74f to .16f, .80f to .34f, .62f to .36f, close = false)
            poly(.26f to .84f, .20f to .66f, .38f to .64f, close = false)
        }
        CineIcon.MONITORING -> {
            // A waveform trace inside a monitor frame.
            box(.10f, .18f, .90f, .78f)
            poly(.18f to .60f, .34f to .40f, .46f to .56f, .60f to .30f, .82f to .48f, close = false)
            line(.36f, .90f, .64f, .90f)
        }
        CineIcon.ZEBRA -> {
            box(.12f, .12f, .88f, .88f)
            line(.12f, .52f, .52f, .12f)
            line(.12f, .88f, .88f, .12f)
            line(.48f, .88f, .88f, .48f)
        }
        CineIcon.PEAKING -> {
            val inset = .12f
            val arm = .26f
            line(inset, inset, inset + arm, inset); line(inset, inset, inset, inset + arm)
            line(1 - inset, inset, 1 - inset - arm, inset); line(1 - inset, inset, 1 - inset, inset + arm)
            line(inset, 1 - inset, inset + arm, 1 - inset); line(inset, 1 - inset, inset, 1 - inset - arm)
            line(1 - inset, 1 - inset, 1 - inset - arm, 1 - inset); line(1 - inset, 1 - inset, 1 - inset, 1 - inset - arm)
            drawCircle(color, radius = w * .09f, center = Offset(cx, cy))
        }
        CineIcon.HISTOGRAM -> {
            box(.12f, .62f, .28f, .86f, filled = true)
            box(.34f, .26f, .50f, .86f, filled = true)
            box(.56f, .42f, .72f, .86f, filled = true)
            box(.78f, .70f, .90f, .86f, filled = true)
        }
        CineIcon.HISTOGRAM_MODE -> {
            // Three channel bars: the RGB/luma mode of the histogram.
            box(.14f, .30f, .32f, .84f, filled = true)
            box(.41f, .16f, .59f, .84f, filled = true)
            box(.68f, .40f, .86f, .84f, filled = true)
        }
        CineIcon.GRID -> {
            box(.12f, .12f, .88f, .88f)
            line(.37f, .12f, .37f, .88f); line(.63f, .12f, .63f, .88f)
            line(.12f, .37f, .88f, .37f); line(.12f, .63f, .88f, .63f)
        }
        CineIcon.GRID_MODE -> {
            box(.12f, .12f, .88f, .88f)
            line(.12f, .12f, .88f, .88f); line(.88f, .12f, .12f, .88f)
        }
        CineIcon.LEVEL -> {
            drawCircle(color, radius = w * .36f, center = Offset(cx, cy), style = stroke)
            line(.06f, .50f, .34f, .50f); line(.66f, .50f, .94f, .50f)
            drawCircle(color, radius = w * .07f, center = Offset(cx, cy))
        }
        CineIcon.WARNING -> {
            poly(.50f to .10f, .92f to .86f, .08f to .86f)
            line(.50f, .38f, .50f, .60f)
            drawCircle(color, radius = w * .05f, center = Offset(cx, h * .73f))
        }
        CineIcon.CHECK -> poly(.16f to .54f, .40f to .78f, .86f to .24f, close = false)
        CineIcon.CAMERA -> {
            // Stills body: housing, viewfinder hump and lens.
            poly(.10f to .32f, .34f to .32f, .40f to .20f, .60f to .20f, .66f to .32f, .90f to .32f, .90f to .82f, .10f to .82f)
            drawCircle(color, radius = w * .16f, center = Offset(cx, h * .56f), style = stroke)
        }
        CineIcon.VIDEO -> {
            // Cine body with the matte box to the side.
            box(.08f, .30f, .64f, .74f)
            poly(.64f to .44f, .92f to .28f, .92f to .76f, .64f to .60f)
        }
        CineIcon.AUDIO -> {
            // Level bars of a meter, tallest in the middle.
            line(.14f, .42f, .14f, .58f); line(.32f, .28f, .32f, .72f); line(.50f, .14f, .50f, .86f)
            line(.68f, .28f, .68f, .72f); line(.86f, .42f, .86f, .58f)
        }
        CineIcon.CONTROLS -> {
            // Three faders at different positions.
            line(.24f, .14f, .24f, .86f); line(.50f, .14f, .50f, .86f); line(.76f, .14f, .76f, .86f)
            drawCircle(color, radius = w * .08f, center = Offset(w * .24f, h * .36f))
            drawCircle(color, radius = w * .08f, center = Offset(w * .50f, h * .66f))
            drawCircle(color, radius = w * .08f, center = Offset(w * .76f, h * .44f))
        }
        CineIcon.CLOUD -> {
            drawArc(color, 150f, 180f, false, Offset(w * .10f, h * .40f), Size(w * .36f, h * .36f), style = stroke)
            drawArc(color, 190f, 170f, false, Offset(w * .30f, h * .20f), Size(w * .44f, h * .44f), style = stroke)
            drawArc(color, 230f, 170f, false, Offset(w * .56f, h * .38f), Size(w * .34f, h * .38f), style = stroke)
            line(.24f, .76f, .76f, .76f)
        }
        CineIcon.INFO -> {
            drawCircle(color, radius = w * .40f, center = Offset(cx, cy), style = stroke)
            line(.50f, .46f, .50f, .72f)
            drawCircle(color, radius = w * .05f, center = Offset(cx, h * .32f))
        }
    }
}
