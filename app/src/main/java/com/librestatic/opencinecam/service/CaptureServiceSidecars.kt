/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.metadata.recordingTimecodeJson
import com.librestatic.opencinecam.camera.RecordingTimecodeReport
import com.librestatic.opencinecam.metadata.captureEpochJson

import com.librestatic.opencinecam.camera.CaptureFrameRate

import android.os.Build
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.camera.HdrVideoFormat
import com.librestatic.opencinecam.camera.Hdr10Video
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import org.json.JSONObject

// Part of CaptureService: the JSON sidecar fragments and labels written next to each take.
// These are extensions so the service's state stays in one object; they keep the threading rules of its members.

internal fun CaptureService.captureEpochLabel(report: com.librestatic.opencinecam.camera.CaptureEpochReport?): String = report?.let {
    " · " + getString(when (it.policy) {
        "SHARED_BOOTTIME_CAPTURE_ANCHORS" -> if (it.audioSourceWindow != null) R.string.av_epoch_capture_anchors_calibrated else R.string.av_epoch_capture_anchors
        "BOOTTIME_ESTIMATED_AUDIO_START" -> R.string.av_epoch_estimated
        else -> R.string.av_epoch_unknown
    }) + if (it.audioSourceWindow != null) " · " + getString(R.string.aac_source_window_applied) else ""
}.orEmpty()

/** The transfer a HLG-mode take records: HDR10 only when chosen and the active profile offers it. */
internal fun CaptureService.hdrTakeFormat(state: CameraUiState): HdrVideoFormat =
    Hdr10Video.effectiveFormat(settings.hdrVideoFormat, state.activeLogProfile?.hdr10 == true)

/** Provenance for an HLG take. The file's own BT.2020/HLG tags are what players read; this records how they were made. */
internal fun CaptureService.hlgSidecarJson(evidence: OpenCineLogRecordingEvidence, avTiming: com.librestatic.opencinecam.camera.CaptureEpochReport?): String = JSONObject()
    .put("schema", "opencinecam.hdr-video.v1")
    .put("hdr", JSONObject()
        .put("transfer", evidence.curve)
        .put("transferStandard", evidence.curveVersion)
        .put("primaries", evidence.gamut)
        .put("range", evidence.range)
        .put("sourceDynamicRange", evidence.sourceDynamicRange)
        .put("sourcePrimaries", "BT.709")
        .put("codecName", evidence.codecName)
        .put("codecProfile", evidence.codecProfile)
        .put("encoderVuiTransfer", evidence.encoderVuiTransfer ?: JSONObject.NULL)
        .put("containerVuiTransfer", evidence.containerVuiTransfer ?: JSONObject.NULL)
        .put("ycbcrConversion", evidence.ycbcrConversion ?: JSONObject.NULL)
        .put("transformSha256", evidence.transformSha256)
        .put("sourceDataSpaceMismatchedFrames", evidence.sourceDataSpaceMismatchedFrames ?: JSONObject.NULL)
        .put("encodedFrames", evidence.encodedFrames)
        .put("targetFps", evidence.targetFps)
        .put("maxVideoPtsGapUs", evidence.maxVideoPtsGapUs ?: JSONObject.NULL)
        .put("videoPtsGapsOverThreshold", evidence.videoPtsGapsOverThreshold))
    .apply { avTiming?.let { put("avTiming", com.librestatic.opencinecam.metadata.captureEpochJson(it)) } }
    .toString(2)

/** The lens correction a take was encoded with; present only when one was active. */
internal fun CaptureService.lensCorrectionJson(lens: com.librestatic.opencinecam.camera.calibration.LensCorrectionEvidence): JSONObject = JSONObject()
    .put("setSha256", lens.setSha256)
    .put("splicedTransformSha256", lens.splicedTransformSha256)
    .put("alignment", lens.alignment)
    .put("perLensFrameCounts", JSONObject().apply { lens.perLensFrameCounts.forEach { (id, n) -> put(id, n) } })
    .put("fallbackFrames", lens.fallbackFrames)

/** The per-frame exposure gain a take could apply (the FPS LOCK hold over lens switches); present only when it was on. */
internal fun CaptureService.exposureCompensationJson(compensation: com.librestatic.opencinecam.camera.calibration.ExposureCompensationEvidence): JSONObject = JSONObject()
    .put("splicedTransformSha256", compensation.splicedTransformSha256)
    .put("compensatedFrames", compensation.compensatedFrames)
    .put("minGain", compensation.minGain.toDouble())
    .put("maxGain", compensation.maxGain.toDouble())

internal fun CaptureService.logSidecarJson(evidence: OpenCineLogRecordingEvidence, timecodeReport: RecordingTimecodeReport?): String = JSONObject()
    .put("schema", "opencinecam-oclog-sidecar-v2")
    .put("avTiming", evidence.avTiming?.let(::captureEpochJson) ?: JSONObject.NULL)
    .put("createdAtEpochMs", System.currentTimeMillis())
    .put("device", JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("fingerprint", Build.FINGERPRINT)
        .put("sdk", Build.VERSION.SDK_INT))
    .put("cameraId", cameraState.value.selectedCameraId)
    .put("provenance", evidence.provenance.name)
    .put("source", JSONObject()
        .put("path", evidence.sourcePath.name)
        .put("surface", evidence.sourceSurface)
        .put("dynamicRange", evidence.sourceDynamicRange)
        .put("colorSpace", evidence.sourceColorSpace)
        .put("transfer", evidence.sourceTransfer)
        .put("precision", evidence.sourcePrecision)
        // Primaries the decode assumed before converting to BT.2020. Clips without it are HLG takes
        // that read the BT.709-primaried stream as BT.2020 (see OpenCineLogGpuPipeline).
        .put("primaries", "BT.709")
        .put("androidDataSpace", evidence.sourceDataSpace ?: JSONObject.NULL)
        .put("dataSpaceMismatchedFrames", evidence.sourceDataSpaceMismatchedFrames ?: JSONObject.NULL)
        .put("unexpectedAndroidDataSpace", evidence.unexpectedSourceDataSpace ?: JSONObject.NULL)
        .put(
            "interpretation",
            if (evidence.sourcePath == OpenCineLogSourcePath.HLG10_BT2020) {
                "HLG OETF decoded to scene-linear, BT.709 primaries measured on device converted to BT.2020 in highp GLES"
            } else {
                "Standard-range ISP output interpreted with the BT.709 video transfer, linearized, and converted to BT.2020 in highp GLES; runtime dataspace is recorded and no source gamut, 10-bit, or HDR highlight claim is made"
            },
        ))
    .put("transform", JSONObject()
        .put("curve", evidence.curve)
        .put("version", evidence.curveVersion)
        .put("domain", evidence.recordingLut?.input?.name ?: "scene-linear")
        .put("gamut", evidence.gamut)
        .put("range", evidence.range)
        .put(if (evidence.recordingLut != null) "sourceShaderSha256" else "shaderSha256", evidence.transformSha256)
        // Runtime shader parameter, part of the qualified tuple: scene-linear gain before OCLog2.
        .put("greyReference", evidence.greyReference.name)
        .put("sceneGain", evidence.sceneGain.toDouble())
        // Matrix/range/bit depth the shader decoded the camera YCbCr with; null means the
        // driver's sampler chose them and the code values are not verifiable.
        .put("ycbcrConversion", evidence.ycbcrConversion ?: JSONObject.NULL))
    .put("encoding", JSONObject()
        .put("mime", evidence.codecMime)
        .put("profile", evidence.codecProfile)
        .put("codecName", evidence.codecName)
        .put("eglRenderTargetBits", evidence.eglRenderTargetBits)
        .put("colorPrimaries", if (evidence.recordingLut != null) "BT.709" else evidence.gamut)
        .put("transfer", when {
            evidence.recordingLut != null -> "BT.709 SDR video"
            evidence.containerVuiTransfer == 2 -> "unspecified (H.273 2); OCLog2 sidecar authoritative"
            else -> "encoder tag H.273 ${evidence.containerVuiTransfer ?: "absent"} kept; OCLog2 sidecar authoritative"
        })
        // H.273 transfer the encoder wrote, and the one the app left in the file.
        .put("encoderVuiTransfer", evidence.encoderVuiTransfer ?: JSONObject.NULL)
        .put("containerVuiTransfer", evidence.containerVuiTransfer ?: JSONObject.NULL)
        .put("range", evidence.range))
    .put("geometry", JSONObject()
        .put("mode", evidence.geometryMode.name)
        .put("deviceOrientationDegrees", evidence.deviceOrientationDegrees)
        .put("pixelRotationDegrees", evidence.pixelRotationDegrees)
        .put("rendererRotationDegrees", evidence.rendererRotationDegrees)
        .put("containerRotationDegrees", evidence.containerRotationDegrees)
        .put("encodedWidth", evidence.encodedWidth)
        .put("encodedHeight", evidence.encodedHeight)
        .put("displayWidth", evidence.displayWidth)
        .put("displayHeight", evidence.displayHeight)
        .put("sarWidth", evidence.pixelAspectRatioWidth)
        .put("sarHeight", evidence.pixelAspectRatioHeight))
    .put("monitoring", JSONObject()
        .put("viewAssist", "Rec.709 monitoring-only")
        .put("flatPreview", "OCLog2 after BT.2020-to-Rec.709 display-gamut conversion and bounded saturation compression")
        .put("bakedIntoRecording", false))
    .put("encodedFrames", evidence.encodedFrames)
    .put("targetFps", evidence.targetFps)
    .put("firstPtsUs", evidence.firstPtsUs)
    .put("lastPtsUs", evidence.lastPtsUs)
    .put("maxVideoPtsGapUs", evidence.maxVideoPtsGapUs)
    .put("videoPtsGapsOverThreshold", evidence.videoPtsGapsOverThreshold)
    .put("timecode", timecodeReport?.let(::recordingTimecodeJson) ?: JSONObject.NULL)
    .put("qualification", cameraState.value.activeLogProfile?.let { profile ->
        JSONObject()
            .put("stage", profile.qualificationStage.name)
            .put("reason", profile.qualificationReason)
            .put("evidenceId", profile.qualificationEvidenceId ?: JSONObject.NULL)
            .put("exactProfileVerified", profile.isVerified)
    } ?: JSONObject()
        .put("stage", "UNKNOWN")
        .put("reason", "active-log-profile-missing")
        .put("evidenceId", JSONObject.NULL)
        .put("exactProfileVerified", false))
    .toString(2)

internal fun CaptureService.timelapseTimingJson(status: com.librestatic.opencinecam.camera.TimelapsePauseStatus,
    rate: com.librestatic.opencinecam.camera.CaptureFrameRate): String = org.json.JSONObject()
    .put("schema", "opencinecam.timelapse-timing.v1")
    .put("audio", "silent")
    .put("projectNumerator", rate.numerator).put("projectDenominator", rate.denominator)
    .put("submittedFrames", status.submittedFrames).put("missedIntervals", status.missedIntervals)
    .put("activeCaptureMs", status.activeElapsedMs).put("pausedMs", status.pausedElapsedMs)
    .put("durationLimitClock", "active-monotonic-command-time")
    .put("resumeIntervalPolicy", "first-real-image-starts-new-interval")
    .put("projectPtsPolicy", "absolute-frame-index-rational")
    .put("pauseEvents", org.json.JSONArray().apply {
        status.events.forEach { event -> put(org.json.JSONObject().put("paused", event.paused)
            .put("commandElapsedMs", event.elapsedMs).put("nextProjectFrameIndex", event.frameIndex)) }
    }).toString(2)

internal fun CaptureService.formatTimelapseInterval(intervalMs: Long): String {
    val totalSeconds = intervalMs / 1000.0
    return when {
        totalSeconds < 1.0 -> "${"%.1f".format(totalSeconds)} s"
        totalSeconds < 60.0 -> "${totalSeconds.toInt()} s"
        totalSeconds < 3600.0 -> {
            val minutes = totalSeconds.toInt() / 60
            val seconds = totalSeconds.toInt() % 60
            if (seconds == 0) "$minutes min" else "$minutes min $seconds s"
        }
        else -> {
            val hours = totalSeconds.toInt() / 3600
            val minutes = (totalSeconds.toInt() % 3600) / 60
            if (minutes == 0) "$hours h" else "$hours h $minutes min"
        }
    }
}

internal fun CaptureService.fpsUnavailableNotice(transient: Boolean, mode: CaptureMode, requestedFps: Int, effectiveFps: Int): String? {
    if (!transient) return null
    val ispNote = if (cameraState.value.activeLogProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) " · ISP-derived" else ""
    return "$requestedFps fps unavailable at this resolution · using $effectiveFps fps$ispNote"
}

internal fun CaptureService.modeDisplayName(mode: CaptureMode): String = when (mode) {
    CaptureMode.PHOTO -> getString(R.string.photo_mode)
    CaptureMode.RAW_PHOTO -> getString(R.string.raw_photo_mode)
    CaptureMode.BURST -> getString(R.string.burst_mode)
    CaptureMode.VIDEO -> getString(R.string.video_mode)
    CaptureMode.SLOW_MOTION -> getString(R.string.slow_motion_mode)
    CaptureMode.TIME_LAPSE -> getString(R.string.time_lapse_mode)
    CaptureMode.BRACKET -> getString(R.string.bracket_mode)
    CaptureMode.LIGHT_TRAIL -> getString(R.string.light_trail_mode)
    CaptureMode.LOG -> getString(R.string.log_mode)
    CaptureMode.HLG -> getString(R.string.hlg_mode)
    CaptureMode.APV -> getString(R.string.apv_mode)
    CaptureMode.RAW_VIDEO -> getString(R.string.raw_video_mode)
}
