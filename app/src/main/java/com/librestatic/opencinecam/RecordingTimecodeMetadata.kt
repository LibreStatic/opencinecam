/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.RecordingTimecodeReport
import com.librestatic.opencinecam.camera.TimecodeMode
import org.json.JSONObject

/** App sidecar metadata, not an interoperable MP4/MOV timecode track. */
internal fun recordingTimecodeJson(report: RecordingTimecodeReport): JSONObject = JSONObject()
    .put("schema", "opencinecam.timecode.v1")
    .put("enabled", report.config.enabled)
    .put("rememberCompletedPosition", report.config.rememberPosition)
    .put("resetRevision", report.config.resetRevision)
    .put("mode", report.config.mode.name)
    .put("nominalFps", report.config.rate.nominalFps)
    .put("rateNumerator", report.config.rate.numerator)
    .put("rateDenominator", report.config.rate.denominator)
    .put("dropFrame", report.config.rate.dropFrame)
    .put("configuredStart", report.config.startValue.format())
    .put("configurationFrozenAt", "RECORDING_STARTED")
    .put("takeId", report.progress?.takeId ?: JSONObject.NULL)
    .put("encodedFrames", report.progress?.frameCount ?: JSONObject.NULL)
    .put("firstPtsUs", report.progress?.firstPtsUs ?: JSONObject.NULL)
    .put("lastPtsUs", report.progress?.lastPtsUs ?: JSONObject.NULL)
    .put("firstFrameTimecode", report.firstFrame?.format() ?: JSONObject.NULL)
    .put("lastFrameTimecode", report.lastFrame?.format() ?: JSONObject.NULL)
    .put("frameMapping", when {
        !report.config.enabled -> "DISABLED"
        report.config.mode == TimecodeMode.FREE_RUN -> "RECEIPT_CLOCK_DISPLAY_ONLY_SOURCE_MAPPING_UNQUALIFIED"
        report.progress == null -> "ENCODED_PROGRESS_UNAVAILABLE"
        report.progress?.frameCount == 0L -> "NO_ENCODED_FRAMES"
        else -> "CONTIGUOUS_MUXED_VIDEO_SAMPLE_INDEX"
    })
    .put("cursorPolicy", when (report.config.mode) {
        TimecodeMode.RECORD_RUN -> "CONSUME_ENCODED_FRAMES_INCLUDING_FAILED_PUBLICATION"
        TimecodeMode.REGEN -> "CONTINUE_AFTER_LAST_PUBLISHED_TAKE"
        TimecodeMode.FREE_RUN -> "MONOTONIC_RECEIPT_CLOCK_INCLUDING_PAUSES"
    })
    .put("containerTimecodeTrackWritten", false)
    .put("sourceClockSynchronizationVerified", false)
