/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/*
 * Focus scale maths for the focus panel. Camera2 reports focus in diopters (1 / metres): 0 D is
 * infinity and LENS_INFO_MINIMUM_FOCUS_DISTANCE is the nearest the lens reaches. Some HALs report
 * distances beyond the limit they declare (the emulator declares 0.1 D and reports 2.0 D), so the
 * scale spans whichever is larger and a reading never falls off its end.
 */

/** Smallest span the scale accepts, so a lens that declares 0 D never divides by zero. */
private const val MIN_FOCUS_SPAN = 0.01f

/** Diopter steps that read well on a scale; each tick also carries its distance in metres. */
private val NICE_DIOPTER_STEPS = floatArrayOf(0.01f, 0.02f, 0.05f, 0.1f, 0.2f, 0.25f, 0.5f, 1f, 2f, 2.5f, 5f, 10f, 20f)

/** Span of the scale in diopters: the declared near limit, widened to cover every value [shown] on it. */
fun focusScaleSpan(declaredNearDiopters: Float, shown: Collection<Float?>): Float =
    (shown.filterNotNull() + declaredNearDiopters).filter { it.isFinite() }.maxOrNull()?.coerceAtLeast(MIN_FOCUS_SPAN) ?: MIN_FOCUS_SPAN

/** Slider position (0 = ∞, 1 = the near end of the scale) for [diopters]. */
fun focusSliderPosition(diopters: Float, span: Float): Float =
    if (span <= 0f || !diopters.isFinite()) 0f else (diopters / span).coerceIn(0f, 1f)

/**
 * Focus to request for a slider [position]. The request never passes the lens's declared near
 * limit, which is what the engine would clamp it to anyway; the thumb then stops where the lens does.
 */
fun focusFromSliderPosition(position: Float, span: Float, declaredNearDiopters: Float): Float =
    (position.coerceIn(0f, 1f) * span).coerceIn(0f, declaredNearDiopters.coerceAtLeast(0f))

/**
 * Tick values for a scale of [span] diopters with at most [maxTicks] ticks. Steps are round
 * diopter values, the first tick is ∞ (0 D) and the last is always the span; an intermediate tick
 * closer than half a step to the end is dropped so two labels never collide.
 */
fun focusScaleTicks(span: Float, maxTicks: Int): List<Float> {
    val end = span.coerceAtLeast(MIN_FOCUS_SPAN)
    val limit = maxTicks.coerceAtLeast(2)
    for (step in NICE_DIOPTER_STEPS) {
        val ticks = ticksForStep(end, step)
        if (ticks.size <= limit) return ticks
    }
    return listOf(0f, end)
}

private fun ticksForStep(end: Float, step: Float): List<Float> {
    val count = floor(end / step + 1e-4f).toInt()
    return (0..count).map { round4(it * step) }.filter { it == 0f || it < end - step / 2f } + end
}

private fun round4(value: Float): Float = (value * 10_000f).roundToLong() / 10_000f

/** How many ticks fit along a scale [widthDp] wide; each tick carries two short labels. */
fun focusTickCount(widthDp: Float): Int = (widthDp / 56f).toInt().coerceIn(2, 7)

/** "2.0 D", "0.25 D", "0 D". Below 1 D two decimals keep near-infinity distances apart. */
fun formatDiopters(diopters: Float): String = "${formatDioptersValue(diopters, compact = false)} D"

/** "∞" at 0 D, then metres: "0.50 m", "2.5 m", "10 m". */
fun formatFocusMetres(diopters: Float): String {
    if (!diopters.isFinite() || diopters <= 0f) return "∞"
    val metres = 1f / diopters
    return when {
        metres < 1f -> String.format(Locale.ROOT, "%.2f m", metres)
        metres < 10f -> String.format(Locale.ROOT, "%.1f m", metres)
        else -> String.format(Locale.ROOT, "%.0f m", metres)
    }
}

/** Diopters first, metres second: "2.0 D · 0.50 m"; infinity reads "0 D · ∞". */
fun formatFocusReading(diopters: Float): String = "${formatDiopters(diopters)} · ${formatFocusMetres(diopters)}"

/** Tick label for the diopter line of the scale: "0", "0.5", "2", "0.05". */
fun formatTickDiopters(diopters: Float): String = formatDioptersValue(diopters, compact = true)

private fun formatDioptersValue(diopters: Float, compact: Boolean): String {
    val value = if (diopters.isFinite()) diopters.coerceAtLeast(0f) else 0f
    if (value == 0f) return "0"
    val text = when {
        value < 1f -> String.format(Locale.ROOT, "%.2f", value).let { if (it.endsWith("0")) it.dropLast(1) else it }
        else -> String.format(Locale.ROOT, "%.1f", value)
    }
    return if (compact && text.contains('.')) text.trimEnd('0').trimEnd('.') else text
}

/** True when the lens reports a focus far enough from the request to be worth a second line. */
fun focusReadbackDiffers(requested: Float?, reported: Float?): Boolean =
    requested != null && reported != null && abs(requested - reported) > maxOf(0.05f, requested * 0.05f)
