/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.PolicyMode

/**
 * Tells a take whose frame delivery has broken down from one with a stray outlier. Frames the camera
 * dropped (gaps in the result frame numbers) are counted per second of sensor time; a second in
 * which at least [brokenFraction] of the expected frames are missing is broken, and the guard trips
 * only after [sustainSeconds] broken seconds in a row. A lone slow frame, a short burst of drops or
 * a variable-rate exposure change (which keeps frame numbers contiguous) never trips it. Latches.
 * Not thread-safe: one camera-thread owner.
 */
class SustainedCadenceGuard(
    private val brokenFraction: Double = 0.2,
    private val sustainSeconds: Int = 5,
    private val bucketNs: Long = 1_000_000_000L,
) {
    init { require(brokenFraction in 0.0..1.0 && sustainSeconds > 0 && bucketNs > 0) }

    private var bucketStartNs: Long? = null
    private var received = 0L
    private var dropped = 0L
    private var consecutiveBroken = 0
    var tripped = false
        private set

    /** One delivered frame at [sensorTimestampNs], preceded by [droppedBefore] frames that never arrived. Returns [tripped]. */
    fun observe(sensorTimestampNs: Long, droppedBefore: Long): Boolean {
        if (tripped) return true
        val start = bucketStartNs ?: sensorTimestampNs.also { bucketStartNs = it }
        if (sensorTimestampNs - start >= bucketNs) {
            val expected = received + dropped
            val broken = expected > 0 && dropped.toDouble() / expected >= brokenFraction
            consecutiveBroken = if (broken) consecutiveBroken + 1 else 0
            // A stall longer than a bucket leaves whole seconds with no frame at all: each is broken.
            val emptyBuckets = ((sensorTimestampNs - start) / bucketNs - 1).coerceAtLeast(0)
            if (emptyBuckets > 0) consecutiveBroken += emptyBuckets.coerceAtMost(sustainSeconds.toLong()).toInt()
            bucketStartNs = sensorTimestampNs
            received = 0
            dropped = 0
            if (consecutiveBroken >= sustainSeconds) tripped = true
        }
        received++
        dropped += droppedBefore.coerceAtLeast(0L)
        return tripped
    }

    fun reset() {
        bucketStartNs = null
        received = 0
        dropped = 0
        consecutiveBroken = 0
        tripped = false
    }
}

/** What a tripped [SustainedCadenceGuard] means under [policy]: Strict stops the take, Adaptive warns. */
fun cadenceLossAction(policy: PolicyMode): CaptureAuditAction =
    if (policy == PolicyMode.STRICT) CaptureAuditAction.STOP else CaptureAuditAction.WARNING
