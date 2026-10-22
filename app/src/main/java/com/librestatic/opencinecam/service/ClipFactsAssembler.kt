/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.HardwareTruthHudState
import com.librestatic.opencinecam.ROUTE_KEY
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.SharedCapturePauseReport
import com.librestatic.opencinecam.core.model.QuirkResolution
import com.librestatic.opencinecam.storage.ClipDeviceProfile
import com.librestatic.opencinecam.storage.ClipGraph
import com.librestatic.opencinecam.storage.ClipIntent
import com.librestatic.opencinecam.storage.ClipPause
import com.librestatic.opencinecam.storage.ClipTakeFacts
import com.librestatic.opencinecam.storage.ClipWarning
import java.time.Instant

/** The separate audio file a take wrote, as the prepared audio sidecar reports it. */
internal data class ClipSeparateAudio(val container: String, val sampleRateHz: Int, val bitDepthBits: Int, val files: List<String>)

/** Everything the service reads from the engine and its own state to describe one take; no Android types. */
internal data class ClipFactsInput(
    val startedAt: Instant,
    val intent: ClipIntent?,
    val cameraId: String?,
    val hardware: HardwareTruthHudState,
    val recordingWidth: Int?,
    val recordingHeight: Int?,
    val targetFps: Int,
    /** OCLog2 or HLG graph evidence; null for a plain SDR take. */
    val graphEvidence: OpenCineLogRecordingEvidence?,
    val hdr: Boolean,
    val lutBaked: Boolean,
    val timelapse: Boolean,
    /** Whether a MediaRecorder wrote the take, as the engine reported at its start; null if unknown. */
    val mediaRecorder: Boolean?,
    /** Mime of the codec the engine reported at the start of an SDR take. */
    val recorderCodecMime: String?,
    val softwareEncoderName: String?,
    val sharedPause: SharedCapturePauseReport?,
    val separateAudio: ClipSeparateAudio?,
    val embeddedAudio: Boolean,
    val deviceProfile: ClipDeviceProfile?,
    val noticeCodes: List<String>,
    /** Warnings the hardware-truth HUD opened during this take (not the ones carried over from earlier takes). */
    val hardwareWarnings: List<String>,
    val stopReason: String?,
    val durationMs: Long?,
    val encodedFrames: Long?,
    val success: Boolean,
    val nativeRetired: Boolean,
)

internal fun clipDeviceProfileOf(resolution: QuirkResolution): ClipDeviceProfile = ClipDeviceProfile(
    profileId = resolution.profileId,
    status = resolution.status.name,
    appliedQuirkIds = resolution.quirks.filter { it.enabled }.map { it.quirkId },
    buildCheck = resolution.buildCheck.name,
)

internal fun assembleClipFacts(input: ClipFactsInput): ClipTakeFacts {
    val evidence = input.graphEvidence
    val software = input.softwareEncoderName
    val graph = ClipGraph(
        cameraId = input.cameraId,
        routeTruth = input.hardware.routeTruth.name,
        requestedRoute = input.hardware.requested[ROUTE_KEY],
        reportedRoute = input.hardware.reported[ROUTE_KEY],
        recorder = when (input.mediaRecorder) {
            true -> "MediaRecorder"
            false -> "MediaCodec"
            null -> null
        },
        codecMime = evidence?.codecMime ?: input.recorderCodecMime,
        encoderName = software ?: evidence?.codecName,
        hardwareAccelerated = if (software != null) false else null,
        softwareEncoder = software != null,
        encodedWidth = evidence?.encodedWidth ?: input.recordingWidth,
        encodedHeight = evidence?.encodedHeight ?: input.recordingHeight,
        fps = evidence?.targetFps ?: input.targetFps,
        containerRotationDegrees = evidence?.containerRotationDegrees,
        colorKind = when {
            evidence != null && input.hdr -> "HLG"
            evidence != null && !input.lutBaked -> "OCLog2"
            else -> "SDR_BT709"
        },
        colorTransfer = evidence?.takeUnless { input.lutBaked && !input.hdr }?.curve,
        colorGamut = evidence?.takeUnless { input.lutBaked && !input.hdr }?.gamut,
        colorProfile = evidence?.codecProfile,
        audioStorage = when {
            input.separateAudio != null -> "SEPARATE_${input.separateAudio.container}"
            input.embeddedAudio -> "EMBEDDED_AAC"
            else -> null
        },
        audioContainer = input.separateAudio?.container,
        audioSampleRateHz = input.separateAudio?.sampleRateHz,
        audioBitDepth = input.separateAudio?.bitDepthBits,
        deviceProfile = input.deviceProfile,
    )
    val pauses = input.sharedPause?.let { report ->
        report.windows.map { window ->
            ClipPause(
                startUs = window.startFrame * 1_000_000L / report.sampleRateHz,
                endUs = window.endFrame?.let { it * 1_000_000L / report.sampleRateHz },
            )
        }
    }.orEmpty()
    val warnings = buildList {
        software?.let { add(ClipWarning("software-video-encoder", message = it)) }
        input.noticeCodes.forEach { add(ClipWarning("runtime-notice:$it")) }
        input.hardwareWarnings.forEach { add(ClipWarning("hardware-truth-warning", message = it)) }
    }
    return ClipTakeFacts(
        startedAt = input.startedAt,
        intent = input.intent,
        graph = graph,
        pauses = pauses,
        warnings = warnings,
        stopReason = input.stopReason,
        durationMs = input.durationMs,
        encodedFrames = input.encodedFrames,
        success = input.success,
        nativeRetired = input.nativeRetired,
        audioFiles = input.separateAudio?.files.orEmpty(),
    )
}
