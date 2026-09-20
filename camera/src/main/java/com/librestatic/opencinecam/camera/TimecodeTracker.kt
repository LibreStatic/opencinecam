/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */
package com.librestatic.opencinecam.camera

import android.os.SystemClock

/** Count of video samples successfully handed to the muxer, not submitted GL images. */
data class EncodedRecordingProgress(
    val takeId: Long,
    val frameCount: Long,
    val firstPtsUs: Long?,
    val lastPtsUs: Long?,
) {
    init {
        require(takeId > 0 && frameCount >= 0)
        require(if (frameCount == 0L) firstPtsUs == null && lastPtsUs == null
            else firstPtsUs != null && lastPtsUs != null && firstPtsUs >= 0 && lastPtsUs >= firstPtsUs)
        require(frameCount != 1L || firstPtsUs == lastPtsUs)
        require(frameCount <= 1L || requireNotNull(lastPtsUs) > requireNotNull(firstPtsUs))
    }
}

/** Sidecar evidence only. FREE_RUN receipt-clock display is not a source-frame timestamp. */
data class RecordingTimecodeReport(
    val lifecycleToken: Long,
    val config: TimecodeConfig,
    val progress: EncodedRecordingProgress?,
    val firstFrame: SmpteTimecode?,
    val lastFrame: SmpteTimecode?,
)

class TimecodeTracker(
    private val continuationStore: TimecodeContinuationStore? = null,
    private val clockNs: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    @Volatile var continuationStorageFailed: Boolean = false
        private set
    private var config = TimecodeConfig()
    private var configured = false
    private var freeRunAnchorNs = 0L
    private var recordRunCursor = 0L
    private var regenNext: Long? = null
    private var active: Take? = null
    private var pending: TimecodeConfig? = null
    private var nextLifecycleToken = 0L

    private data class Take(val lifecycleToken: Long, val config: TimecodeConfig, val start: Long, var takeId: Long?,
        var progress: EncodedRecordingProgress? = null)

    @Synchronized
    fun configure(rate: TimecodeRate, mode: TimecodeMode, startTc: SmpteTimecode, enabled: Boolean,
        rememberPosition: Boolean = true, resetRevision: Int = 0) {
        require(resetRevision >= 0)
        startTc.toTotalFrames(rate) // Reject before mutating either the active or deferred configuration.
        val next = TimecodeConfig(enabled, mode, rate, startTc, rememberPosition, resetRevision)
        if (active != null) pending = next else applyConfiguration(next)
    }

    private fun applyConfiguration(next: TimecodeConfig, persist: Boolean = true) {
        val firstConfiguration = !configured
        val changed = !configured || next.copy(enabled = config.enabled) != config
        if (changed) {
            freeRunAnchorNs = clockNs()
            recordRunCursor = 0L
            regenNext = null
        }
        configured = true
        config = next
        if (firstConfiguration && next.rememberPosition) {
            try {
                continuationStore?.load()?.takeIf { it.matches(next) }?.let {
                    recordRunCursor = it.recordRunCursor
                    regenNext = it.regenNext
                }
            } catch (_: Exception) { continuationStorageFailed = true }
        }
        if (changed && persist) persistContinuation()
    }

    private fun persistContinuation() {
        val store = continuationStore ?: return
        try {
            if (config.rememberPosition) store.save(TimecodeContinuation(config, recordRunCursor, regenNext))
            else store.clear()
        } catch (_: Exception) { continuationStorageFailed = true }
    }

    private fun addWrapped(start: Long, frames: Long, rate: TimecodeRate): Long =
        (start + Math.floorMod(frames, rate.framesPerDay)) % rate.framesPerDay

    private fun nextStart(): Long = when (config.mode) {
        TimecodeMode.RECORD_RUN -> addWrapped(config.startValue.toTotalFrames(config.rate), recordRunCursor, config.rate)
        TimecodeMode.REGEN -> regenNext ?: config.startValue.toTotalFrames(config.rate)
        TimecodeMode.FREE_RUN -> freeRunFrames()
    }

    private fun freeRunFrames(): Long = addWrapped(config.startValue.toTotalFrames(config.rate),
        config.rate.framesForElapsedNs(Math.subtractExact(clockNs(), freeRunAnchorNs)), config.rate)

    @Synchronized
    fun onRecordingStarted(takeId: Long? = null): Long {
        require(takeId == null || takeId > 0)
        check(active == null) { "Timecode take already active" }
        // Freeze even disabled takes: enabling timecode during REC applies to the next take.
        val token = Math.incrementExact(nextLifecycleToken)
        active = Take(token, config, nextStart(), takeId)
        nextLifecycleToken = token
        return token
    }

    /** Reject stale, regressed, or contradictory observations without changing any state. */
    @Synchronized
    fun observeEncodedProgress(progress: EncodedRecordingProgress): Boolean {
        val take = active ?: return false
        if (take.takeId != null && take.takeId != progress.takeId) return false
        take.progress?.let { before ->
            if (progress.frameCount < before.frameCount) return false
            if (progress.frameCount == before.frameCount) return progress == before
            if (before.frameCount > 0 && (progress.firstPtsUs != before.firstPtsUs ||
                    requireNotNull(progress.lastPtsUs) <= requireNotNull(before.lastPtsUs))) return false
        }
        take.takeId = progress.takeId
        take.progress = progress
        return true
    }

    @Synchronized
    fun recordingReport(): RecordingTimecodeReport? {
        val take = active ?: return null
        val frames = take.progress?.frameCount ?: 0
        val hasLabels = take.config.enabled && frames > 0 && take.config.mode != TimecodeMode.FREE_RUN
        return RecordingTimecodeReport(take.lifecycleToken, take.config, take.progress,
            if (hasLabels) SmpteTimecode.fromTotalFrames(take.start, take.config.rate) else null,
            if (hasLabels) SmpteTimecode.fromTotalFrames(addWrapped(take.start, frames - 1, take.config.rate), take.config.rate) else null)
    }

    /** RECORD_RUN consumes recorded samples even on publication failure; REGEN commits only published takes. */
    @Synchronized
    fun onRecordingStopped(success: Boolean, lifecycleToken: Long? = null) {
        val take = active ?: return // Finalization is idempotent, including compensating cleanup.
        if (lifecycleToken != null && lifecycleToken != take.lifecycleToken) return
        val frames = take.progress?.frameCount ?: 0L
        if (take.config.enabled && frames > 0) when (take.config.mode) {
            TimecodeMode.RECORD_RUN -> recordRunCursor = addWrapped(recordRunCursor, frames, take.config.rate)
            TimecodeMode.REGEN -> if (success) regenNext = addWrapped(take.start, frames, take.config.rate)
            TimecodeMode.FREE_RUN -> Unit
        }
        active = null
        pending?.let { pending = null; applyConfiguration(it, persist = false) }
        persistContinuation()
    }

    /** Legacy label projection; lifecycle advancement uses only observeEncodedProgress. */
    @Suppress("UNUSED_PARAMETER")
    @Synchronized
    fun onFrame(ptsUs: Long, frameCount: Long): SmpteTimecode? {
        if (!config.enabled) return null
        require(frameCount >= 0)
        return SmpteTimecode.fromTotalFrames(if (config.mode == TimecodeMode.FREE_RUN) freeRunFrames()
            else addWrapped(active?.start ?: nextStart(), frameCount, config.rate), config.rate)
    }

    @Synchronized
    fun currentDisplayTc(): SmpteTimecode? {
        if (!config.enabled) return null
        return if (config.mode == TimecodeMode.FREE_RUN) SmpteTimecode.fromTotalFrames(freeRunFrames(), config.rate)
            else recordingReport()?.lastFrame // No invented count on MediaRecorder / before the first muxed image.
    }
}
