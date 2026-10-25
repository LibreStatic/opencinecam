/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlin.math.max
import kotlin.math.min

/**
 * Smooths the framing jump at a lens switch on a logical multi-camera.
 *
 * Around a switch the Razr's HAL stops applying zoom for about three frames while it brings the
 * other sensor up, then the first frame on the new lens lands on whatever ratio was requested in
 * the meantime: a pinch at 7 % a frame jumps 15-40 % at once. This decides, per capture result, a
 * GPU crop (>= 1) that the pipeline applies to that frame:
 * - zooming in, while the HAL holds, the crop supplies the missing `requested / result`, so the
 *   picture keeps moving and the crop is back at 1 when the new lens arrives on the requested ratio;
 * - zooming out, a crop cannot widen the frame, so the hold stays; the new lens then starts on a
 *   crop matching the previous framing that relaxes at the pinch's own speed times [catchUp].
 *
 * The visible ratio V never falls below the HAL's (the crop only narrows) and moves at most
 * [catchUp] times the requested speed per frame, so it follows the operator's motion, not the HAL's.
 * Once the request has been still for [settleFrames] results V returns to the HAL's ratio, so a
 * resting zoom the HAL rounds or clamps away from the request is never upscaled.
 */
internal class ZoomLagCompensator(
    private val catchUp: Float = 1.04f,
    private val maxCrop: Float = 2.5f,
    private val settleFrames: Int = 6,
) {
    private var visible = Float.NaN
    private var lastRequested = Float.NaN
    private var stillFrames = 0

    /** True when the latest result asked for motion or still carries a crop. */
    var moving = false
        private set

    /** The crop for the frame whose request asked [requested] and whose result reports [result]. */
    fun onResult(requested: Float?, result: Float?): Float {
        if (requested == null || result == null || !(requested > 0f) || !(result > 0f) ||
            !requested.isFinite() || !result.isFinite()) {
            reset()
            return 1f
        }
        val previousVisible = visible
        val previousRequested = lastRequested
        lastRequested = requested
        if (previousVisible.isNaN() || previousRequested.isNaN()) {
            visible = result
            stillFrames = 0
            moving = false
            return 1f
        }
        stillFrames = if (requested != previousRequested) 0 else stillFrames + 1
        val settled = stillFrames > settleFrames
        val target = if (settled) result else requested
        val speed = requested / previousRequested
        val lowest = previousVisible * min(1f, speed) / catchUp
        val highest = previousVisible * max(1f, speed) * catchUp
        val shown = max(target.coerceIn(lowest, highest), result).coerceAtMost(result * maxCrop)
        visible = shown
        val crop = shown / result
        moving = crop > 1f || !settled
        return crop
    }

    fun reset() {
        visible = Float.NaN
        lastRequested = Float.NaN
        stillFrames = 0
        moving = false
    }
}

/**
 * Ring of recent `sensor timestamp -> zoom crop` pairs, written from the capture callback thread
 * and read on the GL thread, which may wait briefly for a result that is late.
 */
internal class ZoomCropTimeline(private val capacity: Int = 32) {
    private val lock = Object()
    private val timestamps = LongArray(capacity)
    private val crops = FloatArray(capacity)
    private var count = 0
    private var next = 0
    private var newest = Long.MIN_VALUE
    private var moving = false

    /** GL thread only: whether the latest [cropFor] found its own result, and how long it waited. */
    var lastExact = true
        private set
    var lastWaitNs = 0L
        private set

    init { require(capacity > 0) }

    fun note(timestampNs: Long, crop: Float, moving: Boolean) = synchronized(lock) {
        timestamps[next] = timestampNs
        crops[next] = crop
        next = (next + 1) % capacity
        if (count < capacity) count++
        if (timestampNs > newest) newest = timestampNs
        this.moving = moving
        lock.notifyAll()
    }

    fun clear() = synchronized(lock) {
        count = 0
        next = 0
        newest = Long.MIN_VALUE
        moving = false
    }

    /**
     * The crop noted for exactly [timestampNs]. While the zoom is moving it waits up to [waitNs]
     * for that result; when it never comes, or a later one is already noted, the frame keeps the
     * crop of the latest result before it (1 when there is none), so a late result never snaps
     * the picture back to the HAL's framing for a frame.
     */
    fun cropFor(timestampNs: Long, waitNs: Long): Float {
        synchronized(lock) {
            val start = System.nanoTime()
            while (true) {
                for (i in 0 until count) {
                    if (timestamps[i] == timestampNs) {
                        lastExact = true
                        lastWaitNs = System.nanoTime() - start
                        return crops[i]
                    }
                }
                val remaining = waitNs - (System.nanoTime() - start)
                if (!moving || remaining <= 0L || newest > timestampNs) {
                    lastExact = false
                    lastWaitNs = System.nanoTime() - start
                    return cropBefore(timestampNs)
                }
                lock.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
            }
        }
    }

    private fun cropBefore(timestampNs: Long): Float {
        var best = -1
        for (i in 0 until count) {
            if (timestamps[i] < timestampNs && (best < 0 || timestamps[i] > timestamps[best])) best = i
        }
        return if (best >= 0) crops[best] else 1f
    }
}
