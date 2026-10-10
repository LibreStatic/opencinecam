/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import java.util.concurrent.ConcurrentHashMap

/** What a session output carries, independent of the Camera2 object that wraps its surface. */
enum class StreamRole {
    /** A viewfinder surface or the YUV analysis reader. */
    PREVIEW,

    /** A MediaRecorder or encoder input that only feeds a take. */
    RECORDING,

    /** One surface feeding both the viewfinder and a take, e.g. the GPU pipeline input. */
    PREVIEW_AND_RECORDING,

    /** A JPEG/HEIC reader. */
    STILL,

    /** RAW outputs stay on DEFAULT: CROPPED_RAW is a different stream. */
    RAW,
}

/** `SCALER_AVAILABLE_STREAM_USE_CASES_*`, spelled out so the mapping runs in host tests. */
object StreamUseCase {
    const val DEFAULT = 0L
    const val PREVIEW = 1L
    const val STILL_CAPTURE = 2L
    const val VIDEO_RECORD = 3L
    const val PREVIEW_VIDEO_STILL = 4L
    const val VIDEO_CALL = 5L

    /** `REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE` (API 33). */
    const val CAPABILITY = 19

    /** Vendor use cases start here and are never selected. */
    const val VENDOR_BASE = 0x10000L

    fun label(useCase: Long): String = when (useCase) {
        DEFAULT -> "DEFAULT"
        PREVIEW -> "PREVIEW"
        STILL_CAPTURE -> "STILL_CAPTURE"
        VIDEO_RECORD -> "VIDEO_RECORD"
        PREVIEW_VIDEO_STILL -> "PREVIEW_VIDEO_STILL"
        VIDEO_CALL -> "VIDEO_CALL"
        else -> useCase.toString()
    }
}

/**
 * The use case [role] should request given what the camera [advertised], or null to leave the
 * output on DEFAULT. Vendor values are ignored. A shared preview/recording output prefers
 * PREVIEW_VIDEO_STILL and otherwise VIDEO_RECORD (the take must not be starved).
 */
fun streamUseCaseFor(role: StreamRole, advertised: Set<Long>): Long? {
    val standard = advertised.filterTo(HashSet()) { it in StreamUseCase.PREVIEW..StreamUseCase.VIDEO_CALL }
    val wanted = when (role) {
        StreamRole.PREVIEW -> listOf(StreamUseCase.PREVIEW)
        StreamRole.RECORDING -> listOf(StreamUseCase.VIDEO_RECORD)
        StreamRole.PREVIEW_AND_RECORDING -> listOf(StreamUseCase.PREVIEW_VIDEO_STILL, StreamUseCase.VIDEO_RECORD)
        StreamRole.STILL -> listOf(StreamUseCase.STILL_CAPTURE)
        StreamRole.RAW -> emptyList()
    }
    return wanted.firstOrNull { it in standard }
}

/** Whether stream use cases may be set for a session: API 33, the capability and a non-high-speed session. */
fun streamUseCasesApplicable(sdk: Int, capabilities: Set<Int>, advertised: Set<Long>, highSpeed: Boolean): Boolean =
    sdk >= 33 && !highSpeed && StreamUseCase.CAPABILITY in capabilities && advertised.any { it in StreamUseCase.PREVIEW..StreamUseCase.VIDEO_CALL }

/**
 * Cameras whose HAL refused a session that carried use cases. Process-wide: the second attempt is
 * made once and every later session of that camera id starts without them.
 */
object StreamUseCaseRejections {
    private val rejected = ConcurrentHashMap.newKeySet<String>()
    private val applied = ConcurrentHashMap.newKeySet<String>()

    fun reject(cameraId: String) { rejected += cameraId; applied -= cameraId }
    fun isRejected(cameraId: String): Boolean = cameraId in rejected
    fun markApplied(cameraId: String) { if (cameraId !in rejected) applied += cameraId }
    fun wasApplied(cameraId: String): Boolean = cameraId in applied
    internal fun clearForTest() { rejected.clear(); applied.clear() }
}
