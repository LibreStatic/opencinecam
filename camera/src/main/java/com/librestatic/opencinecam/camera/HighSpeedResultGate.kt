/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/**
 * Coalesces the results of a constrained high-speed burst (e.g. 240 fps), or of any session above
 * [COALESCE_ABOVE_FPS] (RAW video at 90 or 120 fps), down to about one fully processed result per preview
 * period, so the single camera executor keeps up and GPU analysis is not delivered late. The full work costs
 * the Razr about 9.7 ms per result, more than a 120 fps frame (8.3 ms): ungated, the results fell further
 * behind every second and a RAW take lost the white balance its frames are matched with.
 * One instance per armed callback (session / generation); not thread safe, used on the camera executor only.
 */
internal class HighSpeedResultGate(private val periodNs: Long = DEFAULT_PERIOD_NS) {
    private var lastProcessedNs: Long? = null

    /** True when this result should get the full per-result processing. A null timestamp always does. */
    fun shouldProcessFully(timestampNs: Long?): Boolean {
        if (timestampNs == null) return true
        val last = lastProcessedNs
        // A timestamp going backwards means a new session timeline: start over.
        if (last == null || timestampNs < last || timestampNs - last >= periodNs) {
            lastProcessedNs = timestampNs
            return true
        }
        return false
    }

    companion object {
        /** Slightly under one 30 fps preview frame (33.3 ms). */
        const val DEFAULT_PERIOD_NS = 30_000_000L

        /** Above this target rate a regular session's results are coalesced too. */
        const val COALESCE_ABOVE_FPS = 60

        /** Whether a session ([highSpeed] for constrained high-speed) at [targetFps] needs the gate. */
        fun needed(highSpeed: Boolean, targetFps: Int): Boolean = highSpeed || targetFps > COALESCE_ABOVE_FPS
    }
}
