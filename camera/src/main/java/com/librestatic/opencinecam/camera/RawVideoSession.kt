/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/**
 * OCC-PLAN-071 stable reasons for the RAW_VIDEO session and its takes. They reach
 * [Camera2PreviewListener.onRawVideoUnavailable], [Camera2PreviewListener.onRawVideoStopped] and
 * [RawVideoFrameConsumer.onRawStreamFailed] unchanged, so the service can key on them.
 */
object RawVideoReasons {
    /** CameraService (or the engine's pre-check) refused GPU input + RAW10: the mode is unavailable. RAW is never dropped. */
    const val GRAPH_REJECTED = "raw-video-graph-rejected"
    /** The consumer had no room for a frame (PLAN-071 never drops one): the take ended cleanly. */
    const val RING_FULL = "raw-ring-full"
    /** The consumer threw from one of its callbacks; the take ended. */
    const val CONSUMER_FAILED = "raw-consumer-failed"
    /** The camera disconnected mid-take. */
    const val CAMERA_DISCONNECTED = "raw-camera-disconnected"
    /** The camera device reported an error mid-take. */
    const val CAMERA_ERROR = "raw-camera-error"
    /** The capture session closed under the take without the engine asking for it. */
    const val SESSION_LOST = "raw-session-lost"
    /** The engine tore the session down (stopPreview, a new startPreview, close) during the take. */
    const val SESSION_CLOSED = "raw-session-closed"
    /** Camera2 reported a lost RAW buffer: a frame is missing, so the take ends (ADR-0019). */
    const val FRAME_LOST = "raw-frame-lost"
}

/** RAW10 buffers in flight between the HAL and the reader thread (PLAN-071 device evidence: 8 at 60 fps). */
const val RAW_VIDEO_MAX_IMAGES = 8

/**
 * The AE target range for a RAW_VIDEO session at [fps]: the fixed `[fps, fps]` range when the camera
 * advertises it, otherwise the narrowest advertised range that contains [fps] (on a tie the one with
 * the higher floor, so AE stretches exposure the least below [fps]). Null when no range contains it.
 */
fun rawVideoFpsRange(ranges: List<Pair<Int, Int>>, fps: Int): Pair<Int, Int>? {
    ranges.firstOrNull { it.first == fps && it.second == fps }?.let { return it }
    return ranges.filter { fps in it.first..it.second }
        .minWithOrNull(compareBy<Pair<Int, Int>> { it.second - it.first }.thenByDescending { it.first })
}

/**
 * How a RAW_VIDEO request reaches its frame rate: [aeRange] is the CONTROL_AE_TARGET_FPS_RANGE to set
 * (null: leave it unset), and [vendorFrameDurationNs], when not null, is the frame duration to force
 * through the Qualcomm vendor keys in [QtiRawFrameRateKeys].
 */
data class RawVideoFrameRatePlan(val aeRange: Pair<Int, Int>?, val vendorFrameDurationNs: Long?)

/** A rate counts as within a declared limit when the limit reaches this fraction of it (as the qualifier's tolerance). */
const val RAW_DECLARED_FPS_TOLERANCE = 0.95

/**
 * The frame-rate plan of a RAW_VIDEO request at [fps]. [declaredMinFrameDurationNs] is the RAW10
 * stream's declared minimum frame duration at its size (null or 0 or less: unknown).
 *
 * Without [qtiOverride], with an unknown declared duration, or when the declared rate already reaches
 * [fps] (within [RAW_DECLARED_FPS_TOLERANCE]), this is [rawVideoFpsRange] alone. Otherwise the AE
 * range stays within the declared limit (the Razr's CamX HAL delivers no frame at all when the range
 * reaches past it): the highest fixed `[n, n]` within it, else the highest-reaching range within it,
 * and the HAL is told the real frame duration, 1/[fps], through the vendor keys. With no advertised
 * range within the declared limit the plan stays [rawVideoFpsRange] without vendor keys.
 */
fun rawVideoFrameRatePlan(
    ranges: List<Pair<Int, Int>>,
    fps: Int,
    declaredMinFrameDurationNs: Long?,
    qtiOverride: Boolean,
): RawVideoFrameRatePlan {
    val unchanged = RawVideoFrameRatePlan(rawVideoFpsRange(ranges, fps), null)
    if (!qtiOverride || fps <= 0 || declaredMinFrameDurationNs == null || declaredMinFrameDurationNs <= 0) return unchanged
    val declaredMaxFps = 1e9 / declaredMinFrameDurationNs
    fun withinDeclared(rate: Int) = declaredMaxFps >= RAW_DECLARED_FPS_TOLERANCE * rate
    if (withinDeclared(fps)) return unchanged
    val range = ranges.filter { it.first == it.second && withinDeclared(it.second) }.maxByOrNull { it.second }
        ?: ranges.filter { withinDeclared(it.second) }
            .maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
        ?: return unchanged
    return RawVideoFrameRatePlan(range, 1_000_000_000L / fps)
}

/**
 * The Qualcomm CamX request keys that lift the RAW stream's declared frame-rate limit. They are not
 * listed in availableCaptureRequestKeys, yet the Razr's HAL honours them (device probe, PLAN-071).
 */
object QtiRawFrameRateKeys {
    /** Byte: 1 lifts the HAL's frame-rate limits for the request. */
    const val DISABLE_FPS_LIMITS = "org.quic.camera.manualExposure.disableFPSLimits"
    /** Long: the sensor frame duration in ns the HAL runs at instead of the clamped one. */
    const val OVERRIDE_SENSOR_FRAME_DURATION = "org.quic.camera.manualExposure.overrideSensorFrameDuration"
}

sealed interface RawVideoGraphDecision {
    data class Accepted(val fpsRange: Pair<Int, Int>) : RawVideoGraphDecision
    data class Rejected(val detail: String) : RawVideoGraphDecision
}

/**
 * Pre-check of a RAW_VIDEO graph before the session is built: the RAW10 size must be one the camera
 * advertises (PLAN-071: only full sensor size on the S24) and some AE range must contain the frame
 * rate. `minFrameDuration` is deliberately not checked: the S24 claims 30 fps for RAW10 and delivers 60.
 * A rejection is reported, never worked around by dropping RAW (ADR-0019).
 */
fun rawVideoGraphDecision(
    config: RawVideoSessionConfig,
    raw10Sizes: List<Pair<Int, Int>>,
    fpsRanges: List<Pair<Int, Int>>,
): RawVideoGraphDecision {
    if (raw10Sizes.isEmpty()) return RawVideoGraphDecision.Rejected("This camera advertises no RAW10 output.")
    if ((config.rawWidth to config.rawHeight) !in raw10Sizes) {
        return RawVideoGraphDecision.Rejected(
            "RAW10 ${config.rawWidth}x${config.rawHeight} is not advertised (" +
                raw10Sizes.joinToString { "${it.first}x${it.second}" } + ").",
        )
    }
    val range = rawVideoFpsRange(fpsRanges, config.fps)
        ?: return RawVideoGraphDecision.Rejected("No AE target range contains ${config.fps} fps.")
    return RawVideoGraphDecision.Accepted(range)
}

/** How a take ended: [reason] null is the operator's stop; [notifyStreamFailure] asks for onRawStreamFailed. */
data class RawTakeEnding(val reason: String?, val notifyStreamFailure: Boolean)

/**
 * One RAW take's lifecycle, confined to the engine's RAW reader thread. A take is accepted
 * ([PENDING]), becomes [ACTIVE] once the consumer heard onRawTakeStarted, and ends exactly once
 * whichever path gets there first: the operator's stop, a full ring, a consumer fault, or the
 * stream failing. Frames outside [ACTIVE] are not the take's: the caller closes them unseen.
 */
class RawVideoTakeState {
    enum class Phase { PENDING, ACTIVE, ENDED }

    sealed interface FrameOutcome {
        /** No active take: close the image. */
        data object Discarded : FrameOutcome
        /** The consumer took the frame; [first] for the take's first one. */
        data class Delivered(val first: Boolean) : FrameOutcome
        /** The consumer refused or failed on the frame and the take ended with [ending]. */
        data class Ended(val ending: RawTakeEnding) : FrameOutcome
    }

    var phase = Phase.PENDING
        private set
    var framesDelivered = 0L
        private set
    var ending: RawTakeEnding? = null
        private set

    /** PENDING to ACTIVE; false when the take already ended (or began). */
    fun begin(): Boolean {
        if (phase != Phase.PENDING) return false
        phase = Phase.ACTIVE
        return true
    }

    /** Offers one frame; [deliver] runs only while ACTIVE and returns the consumer's answer. */
    fun onFrame(deliver: () -> Boolean): FrameOutcome {
        if (phase != Phase.ACTIVE) return FrameOutcome.Discarded
        val accepted = try {
            deliver()
        } catch (_: Exception) {
            return FrameOutcome.Ended(requireNotNull(end(RawVideoReasons.CONSUMER_FAILED, streamFailure = false)))
        }
        if (!accepted) return FrameOutcome.Ended(requireNotNull(end(RawVideoReasons.RING_FULL, streamFailure = false)))
        framesDelivered++
        return FrameOutcome.Delivered(first = framesDelivered == 1L)
    }

    /** Ends the take; returns how, or null when it had already ended (every later path is a no-op). */
    fun end(reason: String?, streamFailure: Boolean): RawTakeEnding? {
        if (phase == Phase.ENDED) return null
        phase = Phase.ENDED
        return RawTakeEnding(reason, streamFailure && reason != null).also { ending = it }
    }
}
