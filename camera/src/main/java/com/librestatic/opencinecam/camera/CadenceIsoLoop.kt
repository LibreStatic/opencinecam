/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import kotlin.math.abs
import kotlin.math.pow

/**
 * Software ISO control for the emulated FPS LOCK hold: shutter fixed at 1/fps with AE off, so the
 * ISO has to follow the scene by itself. It keeps the mean luma of the analysis frames at the value
 * seen just before the hold engaged.
 *
 * Each frame the ratio target / measured moves the ISO by that ratio, limited to
 * [minStep]..[maxStep] per frame, with a [deadband] around the target. A new ISO is reported only
 * when it differs from the last reported one by [reissueDelta] and [minIntervalNs] has passed.
 * With no known target, or with no frames, the ISO stays where it is. Pure; the caller supplies
 * the clock and the mean luma (0..1).
 */
class CadenceIsoLoop(
    private val deadband: Double = 0.06,
    private val minStep: Double = 0.85,
    private val maxStep: Double = 1.18,
    private val reissueDelta: Double = 0.03,
    private val minIntervalNs: Long = 150_000_000L,
    private val settleNs: Long = 400_000_000L,
) {
    private var settleUntilNs = 0L
    private var targetLuma: Double? = null
    private val learned = ArrayList<Double>()
    private var iso = 0.0
    private var issuedIso = 0
    private var lastIssueNs: Long? = null
    private var isoRange: IntRange = 1..Int.MAX_VALUE

    /**
     * Starts holding at [initialIso], which already keeps the automatic exposure's ISO x time, so the
     * first settled held frames have its brightness. The target is learned from them (see [update]),
     * never from frames taken before the hold, which may still show the automatic exposure converging.
     */
    @Synchronized
    fun engage(initialIso: Int, range: IntRange, nowNs: Long = 0L) {
        isoRange = range
        iso = initialIso.coerceIn(range).toDouble()
        issuedIso = iso.toInt()
        targetLuma = null
        learned.clear()
        lastIssueNs = null
        settleUntilNs = nowNs + settleNs
    }

    @Synchronized
    fun release() {
        targetLuma = null
        learned.clear()
        lastIssueNs = null
    }

    @Synchronized
    fun reset() = release()

    val hasTarget: Boolean @Synchronized get() = targetLuma != null

    /**
     * Feeds one analysis frame; returns the ISO to re-issue, or null to leave the request alone.
     * After the settle window the first [LEARN_FRAMES] valid frames only teach the target (their
     * median, so one outlier is rejected); the ISO does not move until then.
     * [targetScale] scales the target (exposure compensation: 2^EV).
     */
    @Synchronized
    fun update(nowNs: Long, luma: Double, targetScale: Double = 1.0): Int? {
        if (nowNs < settleUntilNs) return null
        if (!luma.isFinite() || luma < MIN_LUMA || luma > 1.0) return null
        if (targetLuma == null) {
            learned.add(luma)
            if (learned.size >= LEARN_FRAMES) {
                targetLuma = learned.sorted()[learned.size / 2]
                learned.clear()
            }
            return null
        }
        val target = targetLuma!!.times(targetScale).coerceIn(MIN_LUMA, 1.0)
        val ratio = target / luma
        if (abs(ratio - 1.0) > deadband) {
            iso = (iso * ratio.coerceIn(minStep, maxStep)).coerceIn(isoRange.first.toDouble(), isoRange.last.toDouble())
        }
        val next = iso.toInt().coerceIn(isoRange)
        if (abs(next - issuedIso) < issuedIso * reissueDelta) return null
        val last = lastIssueNs
        if (last != null && nowNs - last < minIntervalNs) return null
        issuedIso = next
        lastIssueNs = nowNs
        settleUntilNs = nowNs + settleNs
        return next
    }

    companion object {
        const val MIN_LUMA = 0.002
        const val LEARN_FRAMES = 3

        /** Sensitivity range extended by the post-raw boost, never beyond 8x the sensor maximum. */
        fun extendedRange(isoRange: IntRange, boostRange: IntRange?): IntRange {
            if (boostRange == null || boostRange.last <= 100) return isoRange
            val top = (isoRange.last.toLong() * boostRange.last / 100).coerceAtMost(8L * isoRange.last).toInt()
            return isoRange.first..top
        }

        /** Splits an effective ISO into SENSOR_SENSITIVITY and CONTROL_POST_RAW_SENSITIVITY_BOOST (null: no boost key). */
        fun split(iso: Int, isoRange: IntRange, boostRange: IntRange?): Pair<Int, Int?> {
            if (boostRange == null) return iso.coerceIn(isoRange) to null
            if (iso <= isoRange.last) return iso.coerceIn(isoRange) to 100.coerceIn(boostRange)
            return isoRange.last to Math.round(100.0 * iso / isoRange.last).toInt().coerceIn(boostRange)
        }

        /** ISO that keeps the exposure the auto frame had, now that the exposure time is [heldExposureNs]. */
        fun initialIso(autoIso: Int, autoExposureNs: Long?, heldExposureNs: Long, range: IntRange): Int {
            val scaled = if (autoExposureNs != null && autoExposureNs > 0 && heldExposureNs > 0)
                autoIso.toDouble() * autoExposureNs / heldExposureNs else autoIso.toDouble()
            return scaled.toInt().coerceIn(range)
        }

        /** Mean of a luma histogram, bin-index weighted and normalised to 0..1; null when empty. */
        fun meanLuma(histogram: List<Float>): Double? {
            if (histogram.size < 2) return null
            var total = 0.0
            var weighted = 0.0
            histogram.forEachIndexed { i, v -> if (v > 0f) { total += v; weighted += v * i } }
            return if (total > 0.0) weighted / total / (histogram.size - 1) else null
        }

        fun evScale(step: Float, indexDelta: Int): Double = 2.0.pow((step * indexDelta).toDouble())
    }
}
