/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraMetadata

/*
 * OCC-PLAN-068 U7: capability-gated out-of-frame warning from the HAL's own face statistics.
 *
 * Privacy contract: face rectangles are read from a capture result, reduced on the camera
 * callback thread to a Boolean and a coarse edge enum, and dropped. Nothing here stores, logs,
 * persists or forwards a rectangle, score, landmark or face id; [SubjectFramingStatus] is the
 * only output and carries no face geometry.
 */

/** Which kind of capture graph is active, as far as face statistics are concerned. */
enum class FaceDetectGraph {
    /** Photo / direct preview repeating request. */
    PREVIEW,
    /** SDR video preview and recording (direct or GPU). */
    VIDEO,
    /** OpenCineLog source graph. */
    LOG,
    /** Constrained high-speed (HFR) route: CHS accepts only a narrow request subset, so never. */
    HIGH_SPEED,
}

/** Coarse edge of the recorded frame (upright content orientation) the subject left through. */
enum class FramingEdge { NONE, LEFT, TOP, RIGHT, BOTTOM }

/**
 * Immutable derived framing state. [lastSeenMonotonicNanos] is the elapsedRealtimeNanos of the
 * last frame with a face inside the recorded area, or of the moment detection started when no
 * face has been seen since; it is only meaningful while [detecting].
 */
data class SubjectFramingStatus(
    /** The active camera and graph can run face statistics (advertised and not rejected). */
    val supported: Boolean = false,
    /** Face statistics are requested on the active graph and results are being evaluated. */
    val detecting: Boolean = false,
    val facePresentInFrame: Boolean = false,
    val lastSeenMonotonicNanos: Long = 0L,
    /** Only set while no face is in frame; NONE when unknown. */
    val exitEdge: FramingEdge = FramingEdge.NONE,
    /** False until the engine has evaluated a result of the current camera; [supported] is then authoritative. */
    val evaluated: Boolean = false,
)

/** Plain integer rectangle so the mapping stays host-testable without android.graphics.Rect. */
data class SensorRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

object FaceDetectCapability {
    /**
     * SIMPLE is preferred (cheapest: rectangles and scores only); FULL is used only when SIMPLE is
     * absent. Returns null when neither is advertised or when [graph] cannot carry the key.
     */
    fun select(availableModes: Set<Int>, graph: FaceDetectGraph): Int? {
        if (graph == FaceDetectGraph.HIGH_SPEED) return null
        return when {
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE in availableModes -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL in availableModes -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
            else -> null
        }
    }
}

object SubjectFramingGeometry {
    /** A face counts as in frame when at least this share of its box lies inside the recorded area. */
    const val INSIDE_FRACTION = 0.6

    /** Outer band (fraction of the recorded size) where an in-frame face hints its likely exit edge. */
    const val EDGE_BAND_FRACTION = 0.2

    /**
     * Recorded area in the same coordinate system as the face boxes: the HAL fills each output by
     * centre-cropping the effective crop region to the stream aspect ([streamWidth] x
     * [streamHeight], sensor-oriented). [cropRegion] is the result's SCALER_CROP_REGION, or the
     * active array when none is reported.
     */
    fun recordedArea(cropRegion: SensorRect, streamWidth: Int, streamHeight: Int): SensorRect {
        if (cropRegion.isEmpty || streamWidth <= 0 || streamHeight <= 0) return cropRegion
        val cropW = cropRegion.width.toLong()
        val cropH = cropRegion.height.toLong()
        // Compare cropW/cropH with streamW/streamH without floating point.
        return if (cropW * streamHeight > cropH * streamWidth) {
            val width = (cropH * streamWidth / streamHeight).toInt()
            val left = cropRegion.left + (cropRegion.width - width) / 2
            SensorRect(left, cropRegion.top, left + width, cropRegion.bottom)
        } else {
            val height = (cropW * streamHeight / streamWidth).toInt()
            val top = cropRegion.top + (cropRegion.height - height) / 2
            SensorRect(cropRegion.left, top, cropRegion.right, top + height)
        }
    }

    /** True when at least [INSIDE_FRACTION] of [face] overlaps [recorded]. */
    fun isInside(face: SensorRect, recorded: SensorRect): Boolean {
        if (face.isEmpty || recorded.isEmpty) return false
        val w = (minOf(face.right, recorded.right) - maxOf(face.left, recorded.left)).coerceAtLeast(0).toLong()
        val h = (minOf(face.bottom, recorded.bottom) - maxOf(face.top, recorded.top)).coerceAtLeast(0).toLong()
        return w * h >= INSIDE_FRACTION * face.width.toLong() * face.height.toLong()
    }

    /**
     * Sensor-raster edge for a face centre: outside the area, the side it is beyond; inside, the
     * side whose outer band it occupies (the likely exit), or NONE in the middle.
     */
    fun sensorEdge(face: SensorRect, recorded: SensorRect): FramingEdge {
        if (face.isEmpty || recorded.isEmpty) return FramingEdge.NONE
        // Normalized centre, 0..1 inside the recorded area.
        val u = ((face.left + face.right) / 2.0 - recorded.left) / recorded.width
        val v = ((face.top + face.bottom) / 2.0 - recorded.top) / recorded.height
        val band = EDGE_BAND_FRACTION
        val candidates = listOf(
            FramingEdge.LEFT to (band - u),
            FramingEdge.RIGHT to (u - (1 - band)),
            FramingEdge.TOP to (band - v),
            FramingEdge.BOTTOM to (v - (1 - band)),
        ).filter { it.second > 0 }
        return candidates.maxByOrNull { it.second }?.first ?: FramingEdge.NONE
    }

    /**
     * Rotates a sensor-raster edge into upright content, where [contentRotationDegrees] is the
     * clockwise rotation applied to the sensor image (the JPEG orientation formula). The content
     * is never mirrored, so front cameras need no extra flip here.
     */
    fun toContentEdge(edge: FramingEdge, contentRotationDegrees: Int): FramingEdge {
        if (edge == FramingEdge.NONE) return edge
        val clockwise = listOf(FramingEdge.LEFT, FramingEdge.TOP, FramingEdge.RIGHT, FramingEdge.BOTTOM)
        val steps = (((contentRotationDegrees % 360) + 360) % 360) / 90
        return clockwise[(clockwise.indexOf(edge) + steps) % 4]
    }
}

/**
 * Per-graph acceptance of the face-statistics key. A graph is rejected when its results keep
 * reporting a different mode (the HAL ignores the key) or when captures fail before any result
 * confirms it. Rejections are remembered per (camera, graph) for the engine's lifetime only.
 */
class FaceDetectGraphGate(
    private val confirmAfterResults: Int = 1,
    private val rejectAfterMismatches: Int = 10,
    private val rejectAfterFailures: Int = 3,
) {
    private data class Key(val cameraId: String, val graph: FaceDetectGraph)
    private val rejected = mutableSetOf<Key>()
    private var active: Key? = null
    private var confirmed = 0
    private var mismatches = 0
    private var failures = 0

    fun isRejected(cameraId: String, graph: FaceDetectGraph): Boolean = Key(cameraId, graph) in rejected

    /** A request carrying the key is being submitted on this graph; counters restart on a new graph. */
    fun arm(cameraId: String, graph: FaceDetectGraph) {
        val key = Key(cameraId, graph)
        if (active != key) {
            active = key
            confirmed = 0
            mismatches = 0
            failures = 0
        }
    }

    fun disarm() { active = null }

    /** Returns true when this observation rejects the graph. */
    fun onResult(requestedMode: Int, reportedMode: Int?): Boolean {
        val key = active ?: return false
        if (reportedMode == requestedMode) {
            confirmed++
            mismatches = 0
            return false
        }
        if (confirmed >= confirmAfterResults && reportedMode == null) return false
        mismatches++
        return (mismatches >= rejectAfterMismatches).also { if (it) reject(key) }
    }

    /** Returns true when this capture failure rejects the graph. */
    fun onCaptureFailed(): Boolean {
        val key = active ?: return false
        if (confirmed >= confirmAfterResults) return false
        failures++
        return (failures >= rejectAfterFailures).also { if (it) reject(key) }
    }

    /** Synchronous submission failure with the key set: reject immediately. */
    fun rejectActive() { active?.let(::reject) }

    private fun reject(key: Key) {
        rejected += key
        active = null
    }
}

/**
 * camera-callback-thread state machine turning per-frame face evaluations into a few
 * [SubjectFramingStatus] transitions. It holds only Booleans, an enum and timestamps.
 */
class SubjectFramingTracker {
    var status: SubjectFramingStatus = SubjectFramingStatus()
        private set
    private var lastEdgeHint = FramingEdge.NONE

    /** Sets capability/detection; returns the new status when it changed. */
    fun configure(supported: Boolean, detecting: Boolean, nowNanos: Long): SubjectFramingStatus? {
        val active = supported && detecting
        if (status.evaluated && status.supported == supported && status.detecting == active) return null
        val restart = active && !status.detecting
        lastEdgeHint = if (restart || !active) FramingEdge.NONE else lastEdgeHint
        status = SubjectFramingStatus(
            supported = supported,
            detecting = active,
            facePresentInFrame = if (restart || !active) false else status.facePresentInFrame,
            lastSeenMonotonicNanos = if (restart) nowNanos else if (active) status.lastSeenMonotonicNanos else 0L,
            evaluated = true,
        )
        return status
    }

    /**
     * One frame: [inFrame] is whether any face is inside the recorded area, [edgeHint] the content
     * edge of the most recent face position (NONE when no face is visible or it is central).
     */
    fun onFrame(inFrame: Boolean, edgeHint: FramingEdge, anyFace: Boolean, nowNanos: Long): SubjectFramingStatus? {
        if (!status.detecting) return null
        if (anyFace) lastEdgeHint = edgeHint
        val next = if (inFrame) {
            status.copy(facePresentInFrame = true, lastSeenMonotonicNanos = nowNanos, exitEdge = FramingEdge.NONE)
        } else {
            status.copy(facePresentInFrame = false, exitEdge = lastEdgeHint)
        }
        val changed = next.facePresentInFrame != status.facePresentInFrame || next.exitEdge != status.exitEdge
        status = next
        // While in frame the timestamp advances every frame; only transitions are published.
        return next.takeIf { changed }
    }
}

/** UI-side debounce: warn once no face has been in the recorded area for longer than the delay. */
object OutOfFrameWarningPolicy {
    fun shouldWarn(status: SubjectFramingStatus, enabled: Boolean, delaySeconds: Int, nowNanos: Long): Boolean {
        if (!enabled || !status.supported || !status.detecting || status.facePresentInFrame) return false
        val absentNanos = nowNanos - status.lastSeenMonotonicNanos
        return absentNanos > delaySeconds.coerceIn(1, 10) * 1_000_000_000L
    }

    /** Nanoseconds until [shouldWarn] can become true, for scheduling one UI re-check; null when never. */
    fun nanosUntilWarning(status: SubjectFramingStatus, enabled: Boolean, delaySeconds: Int, nowNanos: Long): Long? {
        if (!enabled || !status.supported || !status.detecting || status.facePresentInFrame) return null
        val due = status.lastSeenMonotonicNanos + delaySeconds.coerceIn(1, 10) * 1_000_000_000L
        return (due - nowNanos + 1).coerceAtLeast(0L)
    }
}
