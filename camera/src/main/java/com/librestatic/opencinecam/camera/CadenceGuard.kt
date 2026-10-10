/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/**
 * Decides from capture results whether automatic exposure is stretching the frame time past the
 * requested cadence, and so whether the shutter should be held at 1/fps (FPS LOCK).
 *
 * With the gain at its ceiling in a dark scene some HALs let the exposure grow beyond the frame
 * time, which silently drops the frame rate (a 4K60 take at ~33 fps). The guard engages when the
 * reported frame duration has exceeded [engageRatio] x the nominal frame time continuously for
 * [engageNs]. While engaged it remembers the highest ISO seen; it releases when the ISO has stayed
 * at or below [releaseIsoFraction] of that reference for [releaseNs], that is, when there is light
 * to spare and normal automatic exposure would no longer stretch.
 *
 * A sample with a missing or invalid reading is ignored. A gap of more than [maxGapNs] between
 * samples drops the pending timers but keeps the verdict. Pure and thread-safe; the caller supplies
 * a monotonic clock and calls [reset] when the camera, the target rate or the session changes.
 */
class CadenceGuard(
    private val engageRatio: Double = DEFAULT_ENGAGE_RATIO,
    private val engageNs: Long = DEFAULT_ENGAGE_NS,
    private val releaseIsoFraction: Double = DEFAULT_RELEASE_ISO_FRACTION,
    private val releaseNs: Long = DEFAULT_RELEASE_NS,
    private val maxGapNs: Long = DEFAULT_MAX_GAP_NS,
    private val settleNs: Long = DEFAULT_SETTLE_NS,
) {
    private var engagedState = false
    private var referenceIso = 0
    private var engagedAtNs = 0L
    private var pendingSinceNs: Long? = null
    private var lastSampleNs: Long? = null

    val engaged: Boolean @Synchronized get() = engagedState

    /** Feeds one capture result and returns the verdict. */
    @Synchronized
    fun update(nowNs: Long, targetFps: Int, frameDurationNs: Long?, iso: Int?): Boolean {
        if (targetFps <= 0 || frameDurationNs == null || iso == null || frameDurationNs <= 0L || iso <= 0) return engagedState
        val last = lastSampleNs
        lastSampleNs = nowNs
        if (last != null && nowNs - last > maxGapNs) pendingSinceNs = null
        if (engagedState) {
            // The first results after engaging still come from requests sent before the hold: they say
            // nothing about the held ISO, so they neither seed the reference nor count towards release.
            if (nowNs - engagedAtNs < settleNs) {
                pendingSinceNs = null
                return true
            }
            if (iso > referenceIso) referenceIso = iso
            if (iso.toDouble() > referenceIso * releaseIsoFraction) {
                pendingSinceNs = null
                return true
            }
            val since = pendingSinceNs ?: nowNs.also { pendingSinceNs = it }
            if (nowNs - since >= releaseNs) {
                engagedState = false
                referenceIso = 0
                pendingSinceNs = null
            }
        } else {
            if (frameDurationNs.toDouble() <= engageRatio * (1e9 / targetFps)) {
                pendingSinceNs = null
                return false
            }
            val since = pendingSinceNs ?: nowNs.also { pendingSinceNs = it }
            if (nowNs - since >= engageNs) {
                engagedState = true
                // Not seeded from this sample: it is the automatic exposure's ISO, not the held one.
                referenceIso = 0
                engagedAtNs = nowNs
                pendingSinceNs = null
            }
        }
        return engagedState
    }

    /** Back to "not engaged" with no history: camera, target rate or session changed, or the lock was turned off. */
    @Synchronized
    fun reset() {
        engagedState = false
        referenceIso = 0
        pendingSinceNs = null
        lastSampleNs = null
    }

    companion object {
        const val DEFAULT_ENGAGE_RATIO = 1.08
        const val DEFAULT_ENGAGE_NS = 250_000_000L
        const val DEFAULT_RELEASE_ISO_FRACTION = 0.5
        const val DEFAULT_RELEASE_NS = 1_000_000_000L
        const val DEFAULT_MAX_GAP_NS = 1_000_000_000L
        const val DEFAULT_SETTLE_NS = 400_000_000L

        /** Android 16 exposure-time priority is what lets AE pick the ISO while the shutter is held. */
        fun nativePriority(sdkInt: Int, priorities: Set<ExposureMode>): Boolean =
            sdkInt >= 36 && ExposureMode.SHUTTER_PRIORITY in priorities

        /**
         * Without native priority the hold is emulated: manual exposure at 1/fps with an ISO a software
         * loop steers (see [CadenceIsoLoop]). That needs manual sensor control with valid ranges.
         */
        fun supported(sdkInt: Int, caps: ExposureCapabilities): Boolean =
            nativePriority(sdkInt, caps.priorities) || caps.supports(ExposureMode.MANUAL)
    }
}
