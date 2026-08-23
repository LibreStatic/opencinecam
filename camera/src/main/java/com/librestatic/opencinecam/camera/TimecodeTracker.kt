/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.os.SystemClock

class TimecodeTracker {
    private var freeRunAnchorMs: Long = 0L
    private var freeRunStartTc: SmpteTimecode = SmpteTimecode(1, 0, 0, 0, false)
    private var recordRunCursor: Long = 0L
    private var lastClipEndTc: SmpteTimecode? = null
    @Volatile private var currentRate: TimecodeRate = TimecodeRate(30, false)
    @Volatile private var currentMode: TimecodeMode = TimecodeMode.RECORD_RUN
    @Volatile private var currentStartTc: SmpteTimecode = SmpteTimecode(1, 0, 0, 0, false)
    @Volatile private var enabled: Boolean = false

    fun configure(rate: TimecodeRate, mode: TimecodeMode, startTc: SmpteTimecode, enabled: Boolean) {
        if (rate != currentRate || mode != currentMode || startTc != currentStartTc) {
            currentRate = rate
            currentMode = mode
            currentStartTc = startTc
            recordRunCursor = 0L
            freeRunAnchorMs = SystemClock.elapsedRealtime()
            freeRunStartTc = startTc
            lastClipEndTc = null
        }
        this.enabled = enabled
    }

    fun onRecordingStarted() {
        if (!enabled) return
    }

    fun onFrame(ptsUs: Long, frameCount: Long): SmpteTimecode? {
        if (!enabled) return null
        val startFrames = when (currentMode) {
            TimecodeMode.FREE_RUN -> {
                val elapsedMs = SystemClock.elapsedRealtime() - freeRunAnchorMs
                val elapsedFrames = (elapsedMs * 1000L / currentRate.frameDurationUs).toLong()
                freeRunStartTc.toTotalFrames(currentRate) + elapsedFrames
            }
            TimecodeMode.RECORD_RUN -> {
                currentStartTc.toTotalFrames(currentRate) + recordRunCursor
            }
            TimecodeMode.REGEN -> {
                lastClipEndTc?.toTotalFrames(currentRate)?.plus(1) ?: currentStartTc.toTotalFrames(currentRate)
            }
        }
        val totalFrames = startFrames + frameCount
        return SmpteTimecode.fromTotalFrames(totalFrames, currentRate)
    }

    fun onRecordingStopped(success: Boolean, lastFrameTc: SmpteTimecode?) {
        if (!enabled) return
        if (success && lastFrameTc != null) {
            lastClipEndTc = lastFrameTc
        }
    }

    fun advanceRecordRunCursor(frames: Long) {
        if (currentMode == TimecodeMode.RECORD_RUN) {
            recordRunCursor += frames
        }
    }

    fun currentDisplayTc(): SmpteTimecode? {
        if (!enabled) return null
        return when (currentMode) {
            TimecodeMode.FREE_RUN -> {
                val elapsedMs = SystemClock.elapsedRealtime() - freeRunAnchorMs
                val elapsedFrames = (elapsedMs * 1000L / currentRate.frameDurationUs).toLong()
                SmpteTimecode.fromTotalFrames(freeRunStartTc.toTotalFrames(currentRate) + elapsedFrames, currentRate)
            }
            else -> null
        }
    }
}

