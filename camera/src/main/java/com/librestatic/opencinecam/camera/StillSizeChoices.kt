/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/**
 * Compressed still sizes worth offering in the RES panel, largest first.
 *
 * Only sizes in the native aspect of the largest output are kept, because framing is the
 * post-capture [PhotoAspectSelection] crop, not the reader size. Sizes under [MIN_STILL_PIXELS]
 * are dropped, and so is any size whose rounded megapixel label repeats a larger one.
 */
fun stillSizeChoices(sizes: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    val sorted = sizes.filter { (w, h) -> w > 0 && h > 0 }.sortedByDescending { (w, h) -> w.toLong() * h }
    val (maxW, maxH) = sorted.firstOrNull() ?: return emptyList()
    val nativeAspect = maxOf(maxW, maxH).toDouble() / minOf(maxW, maxH)
    val labels = mutableSetOf<Int>()
    return sorted.filter { (w, h) ->
        val aspect = maxOf(w, h).toDouble() / minOf(w, h)
        kotlin.math.abs(aspect - nativeAspect) < 0.01 &&
            w.toLong() * h >= MIN_STILL_PIXELS &&
            labels.add(stillMegapixels(w, h))
    }
}

/** Rounded megapixels, e.g. 4000x3000 -> 12. */
fun stillMegapixels(width: Int, height: Int): Int = Math.round(width.toLong() * height / 1_000_000.0).toInt()

/**
 * Public physical camera that publishes [size] as a compressed still when the logical camera's own
 * map does not, or null when the logical map already covers it (OCC-PLAN-069). Ties between
 * physical cameras resolve to the smallest id so the routing stays deterministic.
 */
fun physicalStillRouting(
    size: Pair<Int, Int>,
    logicalSizes: List<Pair<Int, Int>>,
    physicalSizes: Map<String, List<Pair<Int, Int>>>,
): String? {
    if (size in logicalSizes) return null
    return physicalSizes.keys.sorted().firstOrNull { id -> size in physicalSizes.getValue(id) }
}

private const val MIN_STILL_PIXELS = 1_900_000L
