package com.librestatic.opencinecam.camera

/**
 * Coalesces the results of a constrained high-speed burst (e.g. 240 fps) down to about one fully processed
 * result per preview period, so the single camera executor keeps up and GPU analysis is not delivered late.
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
    }
}
