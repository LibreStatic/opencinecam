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
    PLAY,
    SHARE,
    DELETE,
    RENAME,
    PROXY,
    FILES,
    FILTER,
    REFRESH,
    STAR,
    BACK,
    CHEVRON,
    PAUSE,
    SKIP_START,
    SKIP_END,
    FRAME_BACK,
    FRAME_FORWARD,
    FULLSCREEN,
    FULLSCREEN_EXIT,
    EXPAND,
    COLLAPSE,
    CHEVRON_LEFT,
    CHEVRON_RIGHT,
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
        CineIcon.PLAY -> poly(.30f to .16f, .84f to .50f, .30f to .84f, filled = true)
        CineIcon.SHARE -> {
            // Three connected nodes.
            drawCircle(color, radius = w * .10f, center = Offset(w * .74f, h * .22f), style = stroke)
            drawCircle(color, radius = w * .10f, center = Offset(w * .26f, h * .50f), style = stroke)
            drawCircle(color, radius = w * .10f, center = Offset(w * .74f, h * .78f), style = stroke)
            line(.35f, .45f, .65f, .27f); line(.35f, .55f, .65f, .73f)
        }
        CineIcon.DELETE -> {
            // Bin with lid and handle.
            line(.14f, .24f, .86f, .24f)
            poly(.40f to .24f, .40f to .12f, .60f to .12f, .60f to .24f, close = false)
            poly(.22f to .24f, .28f to .88f, .72f to .88f, .78f to .24f, close = false)
            line(.42f, .40f, .42f, .74f); line(.58f, .40f, .58f, .74f)
        }
        CineIcon.RENAME -> {
            // Pencil over a baseline.
            poly(.20f to .70f, .64f to .26f, .76f to .38f, .32f to .82f, .18f to .84f)
            line(.56f, .34f, .68f, .46f)
            line(.50f, .90f, .88f, .90f)
        }
        CineIcon.PROXY -> {
            // A large frame and its small copy.
            box(.10f, .16f, .70f, .62f)
            box(.50f, .52f, .90f, .84f, filled = true)
        }
        CineIcon.FILES -> {
            box(.30f, .12f, .84f, .74f)
            poly(.16f to .28f, .16f to .88f, .66f to .88f, close = false)
        }
        CineIcon.FILTER -> poly(.10f to .18f, .90f to .18f, .58f to .52f, .58f to .84f, .42f to .74f, .42f to .52f)
        CineIcon.REFRESH -> {
            drawArc(color, 300f, 290f, false, Offset(w * .16f, h * .16f), Size(w * .68f, h * .68f), style = stroke)
            poly(.66f to .08f, .70f to .28f, .50f to .30f, close = false)
        }
        CineIcon.BACK -> poly(.62f to .18f, .30f to .50f, .62f to .82f, close = false)
        CineIcon.CHEVRON -> poly(.38f to .18f, .70f to .50f, .38f to .82f, close = false)
        CineIcon.PAUSE -> {
            box(.26f, .18f, .42f, .82f, filled = true)
            box(.58f, .18f, .74f, .82f, filled = true)
        }
        CineIcon.SKIP_START -> {
            // To the first frame: a bar and a double rewind.
            line(.14f, .22f, .14f, .78f)
            poly(.52f to .22f, .22f to .50f, .52f to .78f, filled = true)
            poly(.86f to .22f, .56f to .50f, .86f to .78f, filled = true)
        }
        CineIcon.SKIP_END -> {
            poly(.14f to .22f, .44f to .50f, .14f to .78f, filled = true)
            poly(.48f to .22f, .78f to .50f, .48f to .78f, filled = true)
            line(.86f, .22f, .86f, .78f)
        }
        CineIcon.FRAME_BACK -> {
            // One frame back: a single step against the frame line.
            poly(.62f to .22f, .26f to .50f, .62f to .78f, filled = true)
            line(.78f, .22f, .78f, .78f)
        }
        CineIcon.FRAME_FORWARD -> {
            line(.22f, .22f, .22f, .78f)
            poly(.38f to .22f, .74f to .50f, .38f to .78f, filled = true)
        }
        CineIcon.FULLSCREEN -> {
            // Corners pointing out.
            poly(.14f to .36f, .14f to .14f, .36f to .14f, close = false)
            poly(.64f to .14f, .86f to .14f, .86f to .36f, close = false)
            poly(.86f to .64f, .86f to .86f, .64f to .86f, close = false)
            poly(.36f to .86f, .14f to .86f, .14f to .64f, close = false)
        }
        CineIcon.FULLSCREEN_EXIT -> {
            // Corners pointing in.
            poly(.14f to .36f, .36f to .36f, .36f to .14f, close = false)
            poly(.64f to .14f, .64f to .36f, .86f to .36f, close = false)
            poly(.86f to .64f, .64f to .64f, .64f to .86f, close = false)
            poly(.36f to .86f, .36f to .64f, .14f to .64f, close = false)
        }
        CineIcon.EXPAND -> poly(.20f to .36f, .50f to .66f, .80f to .36f, close = false)
        CineIcon.COLLAPSE -> poly(.20f to .64f, .50f to .34f, .80f to .64f, close = false)
        CineIcon.CHEVRON_LEFT -> poly(.62f to .22f, .34f to .50f, .62f to .78f, close = false)
        CineIcon.CHEVRON_RIGHT -> poly(.38f to .22f, .66f to .50f, .38f to .78f, close = false)
        CineIcon.STAR -> {
            val path = Path()
            for (i in 0 until 10) {
                val radius = if (i % 2 == 0) w * .42f else w * .18f
                val angle = Math.toRadians(-90.0 + i * 36.0)
                val x = cx + radius * cos(angle).toFloat()
                val y = cy + radius * sin(angle).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color)
        }
    }
}
