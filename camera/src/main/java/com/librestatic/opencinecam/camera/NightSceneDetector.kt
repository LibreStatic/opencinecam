/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/**
 * Decides from capture results whether the scene is dark enough to offer Night photo.
 *
 * The light index is ISO x exposure time in seconds: how much gain-time the metering needed for a
 * usable frame. With automatic exposure that tracks the scene directly (daylight is far below 1,
 * a dim room is in the hundreds). With manual, shutter- or ISO-priority exposure the reported
 * values still describe what the frame was shot with, so the index then reflects the operator's
 * choice as much as the scene; it is used all the same.
 *
 * The scene turns "dark" after the index has stayed at or above [enterIndex] for [enterNs], and
 * turns bright again after it has stayed at or below [leaveIndex] for [leaveNs]. Between the two
 * thresholds nothing changes, so the offer does not flicker. A sample with a missing reading is
 * ignored. A gap of more than [maxGapNs] between samples (night engaged, preview restarted) drops
 * the pending timer but keeps the current verdict.
 *
 * Pure and thread-safe; the caller supplies a monotonic clock.
 */
class NightSceneDetector(
    private val enterIndex: Double = DEFAULT_ENTER_INDEX,
    private val leaveIndex: Double = DEFAULT_LEAVE_INDEX,
    private val enterNs: Long = DEFAULT_ENTER_NS,
    private val leaveNs: Long = DEFAULT_LEAVE_NS,
    private val maxGapNs: Long = DEFAULT_MAX_GAP_NS,
) {
    private var darkState = false
    private var pendingSinceNs: Long? = null
    private var lastSampleNs: Long? = null

    val dark: Boolean @Synchronized get() = darkState

    /** Feeds one capture result and returns the verdict. */
    @Synchronized
    fun update(nowNs: Long, iso: Int?, exposureTimeNs: Long?): Boolean {
        if (iso == null || exposureTimeNs == null || iso <= 0 || exposureTimeNs <= 0L) return darkState
        val last = lastSampleNs
        lastSampleNs = nowNs
        if (last != null && nowNs - last > maxGapNs) pendingSinceNs = null
        val index = iso.toDouble() * exposureTimeNs / 1e9
        val crossing = if (darkState) index <= leaveIndex else index >= enterIndex
        if (!crossing) {
            pendingSinceNs = null
            return darkState
        }
        val since = pendingSinceNs ?: nowNs.also { pendingSinceNs = it }
        if (nowNs - since >= if (darkState) leaveNs else enterNs) {
            darkState = !darkState
            pendingSinceNs = null
        }
        return darkState
    }

    /** Back to "bright" with no history, e.g. after the camera changed. */
    @Synchronized
    fun reset() {
        darkState = false
        pendingSinceNs = null
        lastSampleNs = null
    }

    companion object {
        /** ISO 1600 at 1/30 s. */
        const val DEFAULT_ENTER_INDEX = 1600.0 / 30.0
        const val DEFAULT_LEAVE_INDEX = 20.0
        const val DEFAULT_ENTER_NS = 1_000_000_000L
        const val DEFAULT_LEAVE_NS = 1_500_000_000L
        const val DEFAULT_MAX_GAP_NS = 1_000_000_000L
    }
}
