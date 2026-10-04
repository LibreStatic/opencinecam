/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The aspect families the RES panel groups sizes into, in panel order. */
internal enum class ResolutionAspect(val label: String?) {
    WIDE("16:9"),
    STANDARD("4:3"),
    SCOPE("2.33:1"),
    SQUARE("1:1"),
    /** Whatever else the sensor reads out, such as an open-gate 1.1:1; labelled from resources. */
    OPEN(null),
}

internal data class ResolutionGroup(val aspect: ResolutionAspect, val sizes: List<Pair<Int, Int>>)

internal fun resolutionAspect(width: Int, height: Int): ResolutionAspect {
    val ratio = maxOf(width, height).toDouble() / minOf(width, height).coerceAtLeast(1)
    return when {
        abs(ratio - 16.0 / 9) < 0.02 -> ResolutionAspect.WIDE
        abs(ratio - 4.0 / 3) < 0.02 -> ResolutionAspect.STANDARD
        ratio in 2.2..2.5 -> ResolutionAspect.SCOPE
        abs(ratio - 1.0) < 0.02 -> ResolutionAspect.SQUARE
        else -> ResolutionAspect.OPEN
    }
}

/** Sizes by aspect, largest first inside each group; empty groups are left out. */
internal fun groupResolutions(sizes: List<Pair<Int, Int>>): List<ResolutionGroup> =
    sizes.distinct().groupBy { (w, h) -> resolutionAspect(w, h) }
        .toSortedMap()
        .map { (aspect, members) ->
            ResolutionGroup(aspect, members.sortedWith(
                compareByDescending<Pair<Int, Int>> { it.first.toLong() * it.second }.thenByDescending { maxOf(it.first, it.second) }))
        }

/**
 * The name a video size goes by: 3840×2160 is 4K and 4000×2250 4K+, 1920×1080 is 1080p, 4:3
 * reads in megapixels, scope adds "scope", and other sizes read their long edge in thousands
 * ("3.4K"). The panel shows the pixels under it.
 */
internal fun resolutionShortName(width: Int, height: Int): String {
    val long = maxOf(width, height)
    val short = minOf(width, height)
    if (short <= 0) return "—"
    val k = when {
        long == 3840 -> "4K"
        long in 3841..4199 -> "4K+"
        else -> thousands(long) + "K"
    }
    return when (resolutionAspect(width, height)) {
        ResolutionAspect.WIDE -> if (long < 3840 && short <= 1440) "${short}p" else k
        ResolutionAspect.STANDARD -> if (long == 3840) "4K 4:3" else megapixelName(width, height)
        ResolutionAspect.SCOPE -> "$k scope"
        ResolutionAspect.SQUARE, ResolutionAspect.OPEN -> thousands(long) + "K"
    }
}

/** A still size in megapixels: whole from 8 MP up, otherwise with one decimal ("12 MP", "1.6 MP"). */
internal fun megapixelName(width: Int, height: Int): String {
    val mp = width.toLong() * height / 1_000_000.0
    val text = if (mp >= 7.5) mp.roundToInt().toString() else trimmed(mp)
    return "$text MP"
}

private fun thousands(pixels: Int): String = trimmed(pixels / 1000.0)

private fun trimmed(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else String.format(Locale.ROOT, "%.1f", rounded)
}
