/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.util.Log
import java.util.Locale

/**
 * Diagnostics for the hitch at a lens switch. With `adb shell setprop log.tag.LensSwitch DEBUG` the
 * engine logs the result cadence around every physical change ([LensSwitchTrace]) and the GPU
 * pipeline logs every viewfinder frame that arrives late or repeats ([ViewfinderGapTrace]), both
 * under the `LensSwitch` tag so one logcat shows where a frozen frame comes from.
 */
internal object LensSwitchDiagnostics {
    const val TAG = "LensSwitch"

    fun enabled(): Boolean = runCatching { Log.isLoggable(TAG, Log.DEBUG) }.getOrDefault(false)
}

/**
 * Logs capture results around each lens switch: the [before] results preceding it and the [after]
 * results following it, each with its sensor-timestamp interval and the frame numbers skipped.
 * Outside those windows only intervals over [gapFactor] times the previous one are logged.
 */
internal class LensSwitchTrace(
    private val before: Int = 5,
    private val after: Int = 30,
    private val gapFactor: Double = 1.5,
    private val log: (String) -> Unit = { Log.d(LensSwitchDiagnostics.TAG, it) },
) {
    private var lastTimestampNs = 0L
    private var lastFrameNumber = -1L
    private var lastPhysicalId: String? = null
    private var lastIntervalNs = 0L
    private var remaining = 0
    private val recent = ArrayDeque<String>()

    fun onResult(
        frameNumber: Long,
        timestampNs: Long,
        physicalId: String?,
        zoomRatio: Float?,
        exposureNs: Long?,
        requestedZoomRatio: Float? = null,
        targetZoomRatio: Float? = null,
    ) {
        val intervalNs = if (lastTimestampNs > 0L) timestampNs - lastTimestampNs else 0L
        val skipped = if (lastFrameNumber >= 0L) frameNumber - lastFrameNumber - 1 else 0L
        val line = String.format(
            Locale.ROOT, "result frame=%d dt=%.1fms skipped=%d lens=%s zoom=%s req=%s target=%s exp=%s",
            frameNumber, intervalNs / 1e6, skipped, physicalId, zoomRatio?.let { "%.3f".format(Locale.ROOT, it) },
            requestedZoomRatio?.let { "%.3f".format(Locale.ROOT, it) },
            targetZoomRatio?.let { "%.3f".format(Locale.ROOT, it) },
            exposureNs?.let { "%.2fms".format(Locale.ROOT, it / 1e6) },
        )
        val previous = lastPhysicalId
        if (previous != null && physicalId != null && physicalId != previous) {
            recent.forEach(log)
            recent.clear()
            log("switch $previous->$physicalId frame=$frameNumber ts=$timestampNs")
            remaining = after
        }
        when {
            remaining > 0 -> { log(line); remaining-- }
            (lastIntervalNs > 0L && intervalNs > lastIntervalNs * gapFactor) || skipped > 0 -> log("gap $line")
            else -> {
                recent.addLast(line)
                if (recent.size > before) recent.removeFirst()
            }
        }
        lastTimestampNs = timestampNs
        lastFrameNumber = frameNumber
        if (physicalId != null) lastPhysicalId = physicalId
        if (intervalNs > 0L) lastIntervalNs = intervalNs
    }
}

/**
 * Logs every viewfinder frame whose latch comes over [gapMs] after the previous one, or that
 * latches the same sensor timestamp again (the screen shows the old frame twice).
 */
internal class ViewfinderGapTrace(
    private val gapMs: Double = 50.0,
    private val log: (String) -> Unit = { Log.d(LensSwitchDiagnostics.TAG, it) },
) {
    private var lastTimestampNs = 0L
    private var lastLatchNs = 0L

    fun onFrame(timestampNs: Long, latchNs: Long) {
        if (lastLatchNs > 0L) {
            val wallMs = (latchNs - lastLatchNs) / 1e6
            val sensorMs = (timestampNs - lastTimestampNs) / 1e6
            if (timestampNs == lastTimestampNs) {
                log(String.format(Locale.ROOT, "viewfinder repeat ts=%d wall=%.1fms", timestampNs, wallMs))
            } else if (wallMs > gapMs || sensorMs > gapMs) {
                log(String.format(Locale.ROOT, "viewfinder gap ts=%d wall=%.1fms sensor=%.1fms", timestampNs, wallMs, sensorMs))
            }
        }
        lastTimestampNs = timestampNs
        lastLatchNs = latchNs
    }

    /** Logs every frame drawn with a zoom-lag crop, and every one whose capture result came too late. */
    fun onZoomCrop(timestampNs: Long, crop: Float, exact: Boolean, waitedNs: Long) {
        if (crop == 1f && (exact || waitedNs < 100_000L)) return
        log(String.format(Locale.ROOT, "zoomlag ts=%d crop=%.3f exact=%b wait=%.1fms", timestampNs, crop, exact, waitedNs / 1e6))
    }
}
