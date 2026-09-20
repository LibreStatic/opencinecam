/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Monotonic command receipts, not sensor epochs or inferred audio synchronization. */
data class TimelapsePauseEvent(val paused: Boolean, val elapsedMs: Long, val frameIndex: Long)
data class TimelapsePauseStatus(
    val takeId: Long,
    val paused: Boolean,
    val finished: Boolean,
    val observedAtMs: Long,
    val activeElapsedMs: Long,
    val pausedElapsedMs: Long,
    val events: List<TimelapsePauseEvent>,
    val submittedFrames: Long = 0,
    val missedIntervals: Long = 0,
    val policy: String = "TIMELAPSE_COMMAND_CLOCK",
) {
    fun activeElapsedAt(nowMs: Long): Long = activeElapsedMs +
        if (paused || finished) 0 else (nowMs - observedAtMs).coerceAtLeast(0)
}

/** GL-owned clock. Stop seals it before encoder retirement, excluding finalization latency. */
class TimelapsePauseClock(private val takeId: Long, private val nowMs: () -> Long) {
    private val startedAtMs = nowMs()
    private var lastMs = startedAtMs
    private var pausedAtMs: Long? = null
    private var excludedMs = 0L
    private var stoppedAtMs: Long? = null
    private val events = mutableListOf<TimelapsePauseEvent>()
    fun setPaused(paused: Boolean, frameIndex: Long): Boolean {
        require(frameIndex >= 0)
        if (stoppedAtMs != null || paused == (pausedAtMs != null)) return false
        val now = readNow()
        if (paused) pausedAtMs = now else {
            excludedMs += now - requireNotNull(pausedAtMs)
            pausedAtMs = null
        }
        events += TimelapsePauseEvent(paused, now - startedAtMs, frameIndex)
        return true
    }
    fun finish(): TimelapsePauseStatus {
        if (stoppedAtMs == null) stoppedAtMs = readNow()
        return snapshot()
    }
    fun snapshot(): TimelapsePauseStatus {
        val now = stoppedAtMs ?: readNow()
        val excluded = excludedMs + (pausedAtMs?.let { now - it } ?: 0)
        return TimelapsePauseStatus(takeId, pausedAtMs != null, stoppedAtMs != null, now,
            now - startedAtMs - excluded, excluded, events.toList())
    }
    private fun readNow(): Long = nowMs().coerceAtLeast(lastMs).also { lastMs = it }
}
