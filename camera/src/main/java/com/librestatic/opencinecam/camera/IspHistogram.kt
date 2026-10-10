/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlin.math.ceil

/**
 * Where the HUD luma histogram came from. FRAME is the app's own YUV analysis stream (display
 * referred, after the ISP's tone mapping). The ISP sources come from the Qualcomm statistics
 * block through org.codeaurora.qcamera3.histogram.*.
 */
enum class HistogramSource {
    FRAME,
    /** ISP statistics reported as a YUV (luma) histogram. */
    ISP,
    /** ISP statistics in the Bayer domain: pre tone map, so not what the display shows. */
    ISP_BAYER,
}

/**
 * Qualcomm ISP histogram vendor tags (org.codeaurora.qcamera3.histogram).
 *
 * Evidence, Motorola Razr Fold (dumpsys media.camera, vendor tag table): enable (0x80c60000,
 * byte), buckets (0x80c60001, int32), max_count (0x80c60002, int32), stats (0x80c60003, int32),
 * stats_type (0x80c60004, int32). Camera 0 statics report buckets = [256], max_count = [256].
 * The same dump lists neither enable nor stats in android.request.availableRequestKeys or
 * availableResultKeys, so the gate below keeps the feature dormant there; Motorola's camera app
 * carries no reference to the tags either. The channel(s) stats carries are not documented by
 * the device: the stats_type value is the only hint (see [ispHistogramSource]).
 */
object IspHistogramKeys {
    const val ENABLE = "org.codeaurora.qcamera3.histogram.enable"
    const val BUCKETS = "org.codeaurora.qcamera3.histogram.buckets"
    const val STATS = "org.codeaurora.qcamera3.histogram.stats"
    const val STATS_TYPE = "org.codeaurora.qcamera3.histogram.stats_type"
}

/** The ISP result is older than this next to an analysis frame: use the frame histogram. */
const val ISP_HISTOGRAM_STALE_MS = 500L

/** Sanity bound on the advertised bucket count. */
private const val MAX_ISP_BUCKETS = 4096

/**
 * Whether a camera can feed the HUD from the ISP: the request key that switches the block on must
 * be advertised and the static bucket count must be a sane single value. Returns that bucket
 * count, or null (keep the frame histogram). The stats result key is checked per result, because
 * only the delivered result proves the HAL fills it.
 */
fun ispHistogramBuckets(requestKeyNames: Collection<String>, staticBuckets: Int?): Int? {
    if (IspHistogramKeys.ENABLE !in requestKeyNames) return null
    return staticBuckets?.takeIf { it in 2..MAX_ISP_BUCKETS }
}

/**
 * The source label for a stats_type result. The HAL's cam_histogram_type enumerates BAYER = 0 and
 * YUV = 1; an absent or unknown value is reported as Bayer, because that is the case where the
 * graph must not claim to match the display.
 */
fun ispHistogramSource(statsType: Int?): HistogramSource =
    if (statsType == 1) HistogramSource.ISP else HistogramSource.ISP_BAYER

/**
 * Resamples [raw] ISP buckets onto [bins] HUD bins and scales to fractions that sum to 1 (the
 * frame path's convention). Each bucket's count is shared between the bins it overlaps in
 * proportion to the overlap, so merging (256 to 64) and spreading (16 to 64) both preserve the
 * total. Negative counts read as zero. Null when [raw] is empty, [bins] is not positive or there
 * are no samples.
 */
fun normalizeIspHistogram(raw: IntArray, bins: Int): List<Float>? {
    if (raw.isEmpty() || bins <= 0) return null
    val total = raw.sumOf { it.coerceAtLeast(0).toLong() }
    if (total <= 0L) return null
    val out = DoubleArray(bins)
    val n = raw.size
    for (i in 0 until n) {
        val count = raw[i].coerceAtLeast(0).toDouble()
        if (count == 0.0) continue
        // Bucket i spans [i/n, (i+1)/n) of the range; bin j spans [j/bins, (j+1)/bins).
        val start = i.toDouble() * bins / n
        val end = (i + 1).toDouble() * bins / n
        val first = start.toInt().coerceIn(0, bins - 1)
        val last = (ceil(end).toInt() - 1).coerceIn(first, bins - 1)
        for (j in first..last) {
            val overlap = minOf(end, j + 1.0) - maxOf(start, j.toDouble())
            if (overlap > 0.0) out[j] += count * overlap / (end - start)
        }
    }
    val sum = out.sum()
    if (sum <= 0.0) return null
    return out.map { (it / sum).toFloat() }
}

/**
 * The latest ISP histogram and when it arrived. Written by capture-result callbacks, read when an
 * analysis frame is delivered, both on the camera executor but guarded anyway.
 */
class IspHistogramHold(private val staleMs: Long = ISP_HISTOGRAM_STALE_MS) {
    private var raw: IntArray? = null
    private var source = HistogramSource.ISP_BAYER
    private var updatedAtMs = 0L

    @Synchronized
    fun offer(buckets: IntArray, source: HistogramSource, nowMs: Long) {
        raw = buckets.copyOf()
        this.source = source
        updatedAtMs = nowMs
    }

    @Synchronized
    fun clear() { raw = null }

    /**
     * [analysis] with its luma histogram replaced by the ISP's when one arrived within [staleMs]
     * of the analysis frame, normalised to the frame's own bin count. Otherwise [analysis] itself.
     * Red, green and blue stay the frame path's: the ISP block reports one channel.
     */
    @Synchronized
    fun merge(analysis: Camera2Analysis): Camera2Analysis {
        val buckets = raw ?: return analysis
        val age = analysis.capturedAtElapsedRealtimeMs - updatedAtMs
        if (age > staleMs) return analysis
        val bins = analysis.histogram.size.takeIf { it > 0 } ?: return analysis
        val luma = normalizeIspHistogram(buckets, bins) ?: return analysis
        return analysis.copy(histogram = luma, histogramSource = source)
    }
}
