/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.AudioSpectrum
import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqFilterType
import com.librestatic.opencinecam.camera.EqPoint
import com.librestatic.opencinecam.camera.coerced
import com.librestatic.opencinecam.camera.responseDb
import kotlin.math.roundToInt

/** The plot's margins inside the graph: room for the gain labels on the left and the frequency labels underneath. */
private val PLOT_LEFT = 34.dp
private val PLOT_RIGHT = 16.dp
private val PLOT_TOP = 14.dp
private val PLOT_BOTTOM = 22.dp

/** A node's drawn radius, and the radius a touch finds it within (a 56 dp target). */
private val NODE_RADIUS = 11.dp
private val NODE_TOUCH_RADIUS = 28.dp

private val GRID_FREQUENCIES = listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0)
private val GRID_GAINS = listOf(18.0, 12.0, 6.0, 0.0, -6.0, -12.0, -18.0)
private const val RESPONSE_SAMPLES = 180

private fun gridLabel(frequencyHz: Double): String =
    if (frequencyHz >= 1_000.0) "${(frequencyHz / 1_000.0).roundToInt()}k" else frequencyHz.roundToInt().toString()

private fun Density.geometry(size: IntSize): EqGraphGeometry {
    val left = PLOT_LEFT.toPx()
    val top = PLOT_TOP.toPx()
    return EqGraphGeometry(left, top, (size.width - left - PLOT_RIGHT.toPx()).coerceAtLeast(1f), (size.height - top - PLOT_BOTTOM.toPx()).coerceAtLeast(1f))
}

private enum class NodeGesture { UP, MOVE, PINCH }

/**
 * The equaliser curve over a log-frequency axis (PLAN-074 decision 13): the grid, the live spectrum before and after
 * the equaliser behind everything with the held peaks of the latter, the selected point's own response shaded, the summed response, and a numbered
 * node per point. When [editable], a tap on an empty spot adds a bell there, a node drags (frequency, and gain for
 * the types that have one), a pinch changes the selected node's Q and a long press removes a node. A drag that does
 * not start on a node is left to the page, so the page still scrolls over the graph. Every gesture has its
 * equivalent in the band strip, which is what accessibility services use; the graph itself only describes the curve.
 */
@Composable
internal fun InputEqualizerGraph(
    curve: EqCurve,
    selected: Int?,
    spectrum: AudioSpectrum?,
    editable: Boolean,
    description: String,
    onSelect: (Int) -> Unit,
    onAdd: (EqPoint) -> Unit,
    onMove: (Int, EqPoint) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** The held peaks of the "after" layer, one per band of [spectrum]. */
    peakDb: List<Float>? = null,
    /** The recording clipped with this curve: the peak trace turns to the error colour. */
    clipped: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentCurve by rememberUpdatedState(curve)
    val currentSelected by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val add by rememberUpdatedState(onAdd)
    val move by rememberUpdatedState(onMove)
    val remove by rememberUpdatedState(onRemove)
    val bypassed = !curve.enabled
    val labelStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 10.sp)
    val nodeTextStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val responseColor = if (bypassed) colors.onSurfaceVariant.copy(alpha = 0.6f) else colors.primary
    val outputColor = colors.secondary
    val inputColor = colors.onSurfaceVariant
    val peakColor = if (clipped) colors.error else colors.secondary

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { size = it }
            // Keeps a drag near the screen edge in the graph rather than in the back gesture.
            .systemGestureExclusion()
            .semantics { contentDescription = description }
            .testTag("input-eq-graph")
            .pointerInput(editable) {
                val touchRadius = NODE_TOUCH_RADIUS.toPx()

                suspend fun AwaitPointerEventScope.pinch(index: Int) {
                    var startDistance = 0f
                    var startQ = currentCurve.points.getOrNull(index)?.q ?: return
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        event.changes.forEach { it.consume() }
                        if (pressed.isEmpty()) return
                        if (pressed.size < 2) { startDistance = 0f; continue }
                        val distance = (pressed[0].position - pressed[1].position).getDistance()
                        val point = currentCurve.points.getOrNull(index) ?: return
                        if (startDistance <= 0f) { startDistance = distance; startQ = point.q; continue }
                        // Spreading the fingers widens the band, which is a lower Q.
                        if (distance > 0f) move(index, point.coerced(q = roundEqQ(startQ * startDistance / distance)))
                    }
                }

                suspend fun AwaitPointerEventScope.drag(index: Int, pointer: PointerId) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        event.changes.forEach { it.consume() }
                        if (pressed.isEmpty()) return
                        if (pressed.size >= 2) return pinch(index)
                        val change = pressed.firstOrNull { it.id == pointer } ?: pressed.first()
                        val point = currentCurve.points.getOrNull(index) ?: return
                        val geometry = geometry(size)
                        move(index, geometry.moved(point, change.position.x.coerceIn(geometry.left, geometry.right), change.position.y))
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val geometry = geometry(size)
                    val slop = viewConfiguration.touchSlop
                    val node = geometry.nodeAt(currentCurve.points, down.position.x, down.position.y, touchRadius)
                    if (node == null) {
                        // An empty spot: a tap adds a point; a drag is the page's scroll, and two fingers pinch the
                        // selected node.
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.isConsumed }) return@awaitEachGesture
                            val pressed = event.changes.filter { it.pressed }
                            val target = currentSelected
                            if (editable && pressed.size >= 2 && target != null) return@awaitEachGesture pinch(target)
                            if (pressed.isEmpty()) {
                                if (!moved && editable) {
                                    val gain = roundEqGain(geometry.gain(down.position.y))
                                    add(EqPoint.coerced(EqFilterType.BELL, roundEqFrequency(geometry.frequency(down.position.x)), gain, NEW_POINT_Q))
                                }
                                return@awaitEachGesture
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                            if ((change.position - down.position).getDistance() > slop) moved = true
                        }
                    }
                    select(node)
                    if (!editable) return@awaitEachGesture
                    down.consume()
                    val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var result: NodeGesture? = null
                        while (result == null) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            result = when {
                                event.changes.count { it.pressed } >= 2 -> NodeGesture.PINCH
                                change == null || !change.pressed -> NodeGesture.UP
                                (change.position - down.position).getDistance() > slop -> NodeGesture.MOVE
                                else -> null
                            }
                            event.changes.forEach { it.consume() }
                        }
                        result
                    }
                    when (outcome) {
                        null -> {
                            remove(node)
                            // The finger is still down: the rest of this press does nothing.
                            do { val event = awaitPointerEvent(); event.changes.forEach { it.consume() } } while (event.changes.any { it.pressed })
                        }
                        NodeGesture.UP -> Unit
                        NodeGesture.MOVE -> drag(node, down.id)
                        NodeGesture.PINCH -> pinch(node)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val g = geometry(IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()))
            drawRect(colors.surfaceContainerLowest, Offset(g.left, g.top), androidx.compose.ui.geometry.Size(g.width, g.height))
            // Grid: a line per frequency label, one per 6 dB, the 0 dB line stronger.
            GRID_FREQUENCIES.forEach { f ->
                val x = g.x(f)
                drawLine(colors.outlineVariant, Offset(x, g.top), Offset(x, g.bottom), 1f)
                val label = textMeasurer.measure(gridLabel(f), labelStyle)
                val labelX = (x - label.size.width / 2f).coerceIn(0f, this.size.width - label.size.width)
                drawText(label, topLeft = Offset(labelX, g.bottom + 4.dp.toPx()))
            }
            GRID_GAINS.forEach { db ->
                val y = g.y(db)
                val zero = db == 0.0
                drawLine(if (zero) colors.outline else colors.outlineVariant, Offset(g.left, y), Offset(g.right, y), if (zero) 1.5.dp.toPx() else 1f)
                val text = if (db > 0) "+${db.roundToInt()}" else db.roundToInt().toString()
                val label = textMeasurer.measure(text, labelStyle)
                drawText(label, topLeft = Offset(g.left - label.size.width - 6.dp.toPx(), (y - label.size.height / 2f).coerceIn(0f, this.size.height - label.size.height)))
            }
            clipRect(g.left, g.top, g.right, g.bottom) {
                spectrum?.let {
                    drawSpectrum(g, it, inputColor, outputColor)
                    peakDb?.let { peaks -> drawPeaks(g, it, peaks, peakColor) }
                }
                val shown = curve.copy(enabled = true)
                selected?.let { shown.points.getOrNull(it) }?.let { point ->
                    val own = point.copy(enabled = true)
                    val area = responsePath(g) { own.responseDb(it, EQ_GRAPH_SAMPLE_RATE_HZ) }
                    area.lineTo(g.right, g.y(0.0))
                    area.lineTo(g.left, g.y(0.0))
                    area.close()
                    drawPath(area, responseColor.copy(alpha = 0.2f))
                }
                drawPath(responsePath(g) { shown.responseDb(it, EQ_GRAPH_SAMPLE_RATE_HZ) }, responseColor, style = Stroke(2.5.dp.toPx()))
            }
            curve.points.forEachIndexed { index, point ->
                val center = Offset(g.nodeX(point), g.nodeY(point))
                val isSelected = index == selected
                val on = point.enabled && !bypassed
                val ring = if (on) colors.primary else colors.onSurfaceVariant
                drawCircle(if (isSelected) ring else colors.surface, NODE_RADIUS.toPx(), center)
                drawCircle(ring, NODE_RADIUS.toPx(), center, style = Stroke(2.dp.toPx()))
                val number = textMeasurer.measure((index + 1).toString(),
                    nodeTextStyle.copy(color = if (isSelected) (if (on) colors.onPrimary else colors.surface) else colors.onSurface))
                drawText(number, topLeft = center - Offset(number.size.width / 2f, number.size.height / 2f))
            }
        }
        // One empty, tagged box per node, so tests and Compose Driver can find and drag a node; the band strip is the
        // accessible way to edit it.
        if (size != IntSize.Zero) {
            val g = with(density) { geometry(size) }
            val half = with(density) { NODE_TOUCH_RADIUS.roundToPx() }
            curve.points.forEachIndexed { index, point ->
                Box(Modifier.offset { IntOffset(g.nodeX(point).roundToInt() - half, g.nodeY(point).roundToInt() - half) }
                    .size(NODE_TOUCH_RADIUS * 2).testTag("input-eq-node-$index"))
            }
        }
    }
}

/** The response as a line across the plot, sampled on the log axis and clamped to the plot's gain range. */
private inline fun responsePath(g: EqGraphGeometry, response: (Double) -> Double): Path = Path().apply {
    for (i in 0..RESPONSE_SAMPLES) {
        val x = g.left + g.width * i / RESPONSE_SAMPLES
        val y = g.y(response(g.frequency(x)))
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
}

/** The bands of [spectrum] inside the plot's 20 Hz to 20 kHz that every one of [levels] has a value for. */
private fun plotBands(spectrum: AudioSpectrum, vararg levels: List<Float>): List<Int> = spectrum.bandCentresHz.indices.filter { band ->
    spectrum.bandCentresHz[band] >= EqPoint.MIN_FREQUENCY_HZ && spectrum.bandCentresHz[band] <= EqPoint.MAX_FREQUENCY_HZ &&
        levels.all { band < it.size }
}

/** The held peaks as a thin dashed line over the live layers, without a fill, so the live output stays readable. */
private fun DrawScope.drawPeaks(g: EqGraphGeometry, spectrum: AudioSpectrum, peaks: List<Float>, color: Color) {
    val bands = plotBands(spectrum, peaks)
    if (bands.size < 2) return
    val line = Path().apply {
        bands.forEachIndexed { i, band ->
            val x = g.x(spectrum.bandCentresHz[band].toDouble())
            val y = g.spectrumY(peaks[band])
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    val dash = 4.dp.toPx()
    drawPath(line, color, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.6f))))
}

/** The input as a muted fill from the floor, the output as a brighter fill under its own line. */
private fun DrawScope.drawSpectrum(g: EqGraphGeometry, spectrum: AudioSpectrum, input: Color, output: Color) {
    val bands = plotBands(spectrum, spectrum.inputDb, spectrum.outputDb)
    if (bands.size < 2) return
    fun area(levels: List<Float>): Path = Path().apply {
        moveTo(g.x(spectrum.bandCentresHz[bands.first()].toDouble()), g.bottom)
        bands.forEach { lineTo(g.x(spectrum.bandCentresHz[it].toDouble()), g.spectrumY(levels[it])) }
        lineTo(g.x(spectrum.bandCentresHz[bands.last()].toDouble()), g.bottom)
        close()
    }
    drawPath(area(spectrum.inputDb), input.copy(alpha = 0.22f))
    drawPath(area(spectrum.outputDb), output.copy(alpha = 0.18f))
    val line = Path().apply {
        bands.forEachIndexed { i, band ->
            val x = g.x(spectrum.bandCentresHz[band].toDouble())
            val y = g.spectrumY(spectrum.outputDb[band])
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    drawPath(line, output.copy(alpha = 0.75f), style = Stroke(1.5.dp.toPx()))
}
