/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.math.BigInteger

/** Capture interval and project cadence are separate clocks. No accumulating rounded deltas. */
data class TimelapseCapture(val intervalNs: Long, val projectRate: CaptureFrameRate, val frameLimit: Long? = null) {
    init {
        require(intervalNs in 100_000_000L..3_600_000_000_000L)
        require(projectRate.numerator.toLong() in projectRate.denominator.toLong()..120L * projectRate.denominator)
        require(frameLimit == null || frameLimit in 2..100_000)
    }
}
fun projectFrameTimestampNs(index: Long, rate: CaptureFrameRate): Long {
    require(index >= 0)
    val timestamp = BigInteger.valueOf(index).multiply(BigInteger.valueOf(1_000_000_000L * rate.denominator))
        .divide(BigInteger.valueOf(rate.numerator.toLong()))
    // BigInteger.longValueExact is absent on supported API29/30 runtimes.
    if (timestamp.bitLength() > 63) throw ArithmeticException("Project timestamp exceeds Long nanoseconds")
    return timestamp.toLong()
}

/** GL-owner-only selector. A late frame fills one slot, never a burst of duplicate catch-up frames. */
class TimelapseTimeline(val capture: TimelapseCapture) {
    private var originNs: Long? = null
    private var lastSourceNs: Long? = null
    private var lastSlot = -1L
    var paused = false
        private set
    private var restartInterval = false

    /** The next real image starts a fresh interval; intentional pauses are not missed slots. */
    fun setPaused(value: Boolean): Boolean {
        if (complete || value == paused) return false
        paused = value
        if (!value) restartInterval = true
        return true
    }
    var selectedFrames = 0L
        private set
    var missedIntervals = 0L
        private set
    val complete: Boolean get() = capture.frameLimit?.let { selectedFrames >= it } == true
    fun select(sourceTimestampNs: Long): Long? {
        if (complete || sourceTimestampNs <= 0 || lastSourceNs?.let { sourceTimestampNs <= it } == true) return null
        lastSourceNs = sourceTimestampNs
        if (paused) return null
        if (restartInterval) { originNs = null; lastSlot = -1L; restartInterval = false }
        val origin = originNs ?: sourceTimestampNs.also { originNs = it }
        val slot = (sourceTimestampNs - origin) / capture.intervalNs
        if (slot <= lastSlot) return null
        val pts = projectFrameTimestampNs(selectedFrames, capture.projectRate)
        missedIntervals += (slot - lastSlot - 1).coerceAtLeast(0)
        lastSlot = slot
        selectedFrames++
        return pts
    }
}

data class TimelapseProgress(val submittedFrames: Long, val missedIntervals: Long, val codecName: String, val hardwareAccelerated: Boolean)
