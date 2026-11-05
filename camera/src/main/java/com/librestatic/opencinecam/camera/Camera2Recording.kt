/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import com.librestatic.opencinecam.media.video.VideoCodecPreference
import android.util.Size
import java.util.concurrent.CompletableFuture
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.RecorderRequest
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.RECORDER_LEVEL_INTERVAL_MS
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.TAG

// Part of Camera2PreviewEngine: MediaRecorder and GPU video recording lifecycle.
// These are extensions so the engine's state stays in one object; they run on the engine's executors like its members.

/** Starting/stopping the encoder leaves the preview camera session and subject leases intact. */
internal fun Camera2PreviewEngine.startExistingGpuVideo(output: ParcelFileDescriptor, audio: Camera2EmbeddedAudioConfig?, geometry: RecordingGeometry, bitrate: Int, timelapse: TimelapseCapture? = null, projectRateOverride: CaptureFrameRate? = null, separateAudioClock: CaptureEpochClock? = null, recordingLut: MonitorLut? = null, videoCodec: VideoCodecPreference = VideoCodecPreference.H264): Boolean {
    val pipeline = logPipeline ?: return false
    if (recording || session == null || !gpuPreviewEnabled) return false
    val cameraGeneration = generation
    timelapseTake = timelapse != null
    pipeline.onFirstRecordedFrame = { timestampNs ->
        if (generation == cameraGeneration && logPipeline === pipeline) listener?.onRecordingFirstFrame(timestampNs)
    }
    return pipeline.startRecording(output, bitrate, geometry, audio, minimumSensorTimestampNs = recordingWbMinimumTimestampNs,
        timelapse = timelapse, projectRateOverride = projectRateOverride, separateAudioClock = separateAudioClock,
        recordingLut = recordingLut, videoCodec = videoCodec,
        encoderPolicy = encoderPolicy,
        onSoftwareEncoder = { name ->
            if (generation == cameraGeneration && logPipeline === pipeline) listener?.onSoftwareVideoEncoder(name)
        },
        onCodecFallback = { reason ->
            if (generation == cameraGeneration && logPipeline === pipeline) listener?.onVideoCodecFallback(reason)
        },
        onStartRefused = { code, message ->
            if (generation == cameraGeneration && logPipeline === pipeline) listener?.onRecordingStartRefused(code, message)
        },
        onRecordingLutApplied = { evidence ->
            if (generation == cameraGeneration && logPipeline === pipeline && recording) listener?.onRecordingLutApplied(evidence)
        },
        onTimelapseProgress = { if (generation == cameraGeneration && logPipeline === pipeline) listener?.onTimelapseProgress(it) },
        onEncodedProgress = { progress ->
            if (generation == cameraGeneration && logPipeline === pipeline) latestEncodedProgress = progress
        },
        onTimelapsePauseChanged = { status ->
            if (generation == cameraGeneration && logPipeline === pipeline) {
                lastTimelapsePauseStatus = status
                listener?.onTimelapsePauseChanged(status)
            }
        },
        onStarted = {
            check(generation == cameraGeneration && logPipeline === pipeline) { "GPU recording owner changed during preparation" }
            lastRecordingLutEvidence = null
            recording = true
            listener?.onRecordingStarted(geometry.encodedSize.width, geometry.encodedSize.height)
        },
        onStopped = { success, evidence ->
            cameraExecutor.execute {
                if (generation != cameraGeneration || logPipeline !== pipeline) return@execute
                evidence?.encodedProgress?.let { latestEncodedProgress = it }
                lastCaptureEpochReport = evidence?.avTiming
                lastRecordingLutEvidence = evidence?.recordingLut
                recording = false
                timelapseTake = false
                reportRecordingStopped(success, pipeline.recordingFileRetirement())
            }
        })
}

/** Records normal SDR video through the same one-generation GPU geometry path as LOG. */
internal fun Camera2PreviewEngine.startGpuVideo(
    output: ParcelFileDescriptor,
    audio: Camera2EmbeddedAudioConfig?,
    recordingGeometry: RecordingGeometry,
    videoBitrate: Int,
    timelapse: TimelapseCapture? = null,
    projectRateOverride: CaptureFrameRate? = null,
    separateAudioClock: CaptureEpochClock? = null,
    recordingLut: MonitorLut? = null,
    videoCodec: VideoCodecPreference = VideoCodecPreference.H264,
): Boolean {
    val device = camera ?: return false
    val preview = previewSurface ?: return false
    val descriptor = activeDescriptor ?: return false
    if (recording) return false
    if (gpuPreviewEnabled) return startExistingGpuVideo(output, audio, recordingGeometry, videoBitrate, timelapse, projectRateOverride, separateAudioClock, recordingLut, videoCodec)
    if (logPipeline != null) return false
    val cameraGeneration = generation
    cameraExecutor.execute {
        // Superseded take: only startPreview/stopPreview/close advance the generation, so the
        // owner that did so already sees the newer graph (or no listener); reporting this stale
        // take to it would be the spurious-failure pattern the onConfigureFailed guards avoid.
        if (disposed.get() || cameraGeneration != generation) return@execute
        if (bracketRequest.get() != null || accumulationRequest.get() != null || burstRequest.get() != null) {
            listener?.onFailure("still-sequence-recording-busy", "A still sequence owns the camera graph.", true)
            return@execute
        }
        try {
            session?.stopRepeating()
            session?.close()
            session = null
            jpegReader?.close()
            jpegReader = null
            rawReader?.close()
            rawReader = null
            clearPendingRaw()
            closeAnalysisReader()
            analysisReader = null
            var analysisPipeline: OpenCineLogGpuPipeline? = null
            // Set when the pipeline already reported its own failure (e.g. log-recording-prepare-failed),
            // so a rejected start below does not report twice.
            val pipelineFailureReported = java.util.concurrent.atomic.AtomicBoolean(false)
            val pipeline = OpenCineLogGpuPipeline(
                size = Size(recordingGeometry.sourceSize.width, recordingGeometry.sourceSize.height),
                sourcePath = OpenCineLogSourcePath.SDR_BT709_ISP,
                // The direct Camera2 preview Surface can remain connected to the camera
                // producer briefly after its session closes. Connecting EGL to that same
                // Surface races with the OEM disconnect and fails with EGL_BAD_ALLOC. Keep
                // the last viewfinder frame during the one-generation GPU recording and
                // restore the direct preview after finalization instead.
                preview = null,
                sensorOrientationDegrees = descriptor.sensorOrientation,
                displayRotationDegrees = displayRotationDegrees,
                frontFacing = descriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
                viewAssist = false,
                targetFps = requestedTargetFps,
                passthroughSdr = true,
                appContext = appContext,
                cameraTimestampRealtime = descriptor.timestampSourceRealtime,
                onAnalysis = { analysis -> deliverGpuAnalysis(cameraGeneration, { analysisPipeline }, analysis) },
                onOperatorLutStatus = { status -> deliverGpuLutStatus(cameraGeneration, { analysisPipeline }, status) },
                onPreviewLost = { listener?.onPreviewSurfaceLost(it) },
            ) { code, message ->
                // A retired/superseded pipeline's late failure must not reach a newer owner's listener.
                val owner = analysisPipeline
                if (generation == cameraGeneration && owner != null && logPipeline === owner) {
                    pipelineFailureReported.set(true)
                    listener?.onFailure(code, message, true)
                }
            }
            logPipeline = pipeline
            analysisPipeline = pipeline
            synchronizeSubjectOutput()
            passthroughVideoPipeline = true
            pipeline.onFirstRecordedFrame = { timestampNs ->
                if (generation == cameraGeneration && logPipeline === pipeline) listener?.onRecordingFirstFrame(timestampNs)
            }
            val accepted = pipeline.startRecording(
                output = output,
                bitrate = videoBitrate,
                geometry = recordingGeometry,
                audio = audio,
                separateAudioClock = separateAudioClock,
                recordingLut = recordingLut,
                videoCodec = videoCodec,
                onCodecFallback = { reason ->
                    if (generation == cameraGeneration && logPipeline === pipeline) listener?.onVideoCodecFallback(reason)
                },
                onRecordingLutApplied = { evidence ->
                    if (generation == cameraGeneration && logPipeline === pipeline && recording) listener?.onRecordingLutApplied(evidence)
                },
                minimumSensorTimestampNs = recordingWbMinimumTimestampNs,
                timelapse = timelapse,
                projectRateOverride = projectRateOverride,
                onTimelapseProgress = { progress -> if (generation == cameraGeneration && logPipeline === pipeline) listener?.onTimelapseProgress(progress) },
                onEncodedProgress = { progress ->
                    if (generation == cameraGeneration && logPipeline === pipeline) latestEncodedProgress = progress
                },
                onTimelapsePauseChanged = { status ->
                    if (generation == cameraGeneration && logPipeline === pipeline) {
                        lastTimelapsePauseStatus = status
                        listener?.onTimelapsePauseChanged(status)
                    }
                },
                onStarted = { lastRecordingLutEvidence = null; recording = true },
                onStopped = { success, evidence ->
                    cameraExecutor.execute {
                        if (generation != cameraGeneration || logPipeline !== pipeline) return@execute
                        evidence?.encodedProgress?.let { latestEncodedProgress = it }
                        lastCaptureEpochReport = evidence?.avTiming
                        lastRecordingLutEvidence = evidence?.recordingLut
                        recording = false
                        logPipeline = null
                        synchronizeSubjectOutput()
                        passthroughVideoPipeline = false
                        retirePipeline(pipeline) {
                            if (generation == cameraGeneration) previewSurface?.takeIf { it.isValid }?.let { surface ->
                                camera?.let { activeCamera ->
                                    if (activeVideoProfile?.constrainedHighSpeed == true) {
                                        configureHighSpeedPreviewSession(activeCamera, descriptor, surface, generation)
                                    } else configureSession(activeCamera, descriptor, surface, generation)
                                }
                            }
                        }
                        reportRecordingStopped(success, pipeline.recordingFileRetirement())
                    }
                },
            )
            if (!accepted) {
                retirePipeline(pipeline)
                logPipeline = null
                passthroughVideoPipeline = false
                recording = false
                // Still this generation's take (checked above on this executor): the caller was
                // already told `true`, so a rejected start must terminate it via onFailure.
                if (!pipelineFailureReported.get()) {
                    listener?.onFailure("video-gpu-prepare-failed", "The GPU video recorder rejected the take.", true)
                }
                return@execute
            }
            configureGpuInputSession(device, descriptor, pipeline, recordingGeometry)
        } catch (failure: Throwable) {
            observeFailedInitialization(failure)
            logPipeline?.takeIf { passthroughVideoPipeline }?.let(::retirePipeline)
            logPipeline = null
            passthroughVideoPipeline = false
            recording = false
            listener?.onFailure("video-gpu-prepare-failed", failure.message ?: "GPU video recording could not be prepared.", true)
        }
    }
    return true
}

/** Camera thread: a recorder session reported no captures in flight. */
internal fun Camera2PreviewEngine.onRecorderSessionReady(configured: CameraCaptureSession) {
    if (configured !== drainingRecorderSession) return
    val continuation = recorderDrainContinuation ?: return
    drainingRecorderSession = null
    recorderDrainContinuation = null
    continuation()
}

/**
 * Camera thread, after the recorder session drained (or could not be drained). The next
 * preview session replaces [drained] directly: an explicit close makes the framework
 * unconfigure every stream first, which the Qualcomm HAL rejects as an empty stream set.
 */
internal fun Camera2PreviewEngine.finishRecorderStop(request: RecorderRequest, drained: CameraCaptureSession?) {
    if (!ownsRecorderRequest(request)) {
        drained?.let { runCatching { it.close() } }
        return
    }
    val descriptor = activeDescriptor
    val device = camera
    val surface = previewSurface
    val pipeline = logPipeline?.takeIf { recorderOverGpuPreview && gpuPreviewEnabled }
    var success = true
    try { recorder?.stop() } catch (failure: RuntimeException) {
        success = false
        Log.w(TAG, "MediaRecorder stop failed", failure)
    }
    Log.i(TAG, "Recorder take stopped: success=$success highSpeed=${activeVideoProfile?.constrainedHighSpeed == true}")
    releaseRecorder(request)
    reportRecordingStopped(success, recorderOutputRetirement())
    if (success && descriptor != null && device != null && pipeline != null) {
        configureGpuPreviewSession(device, descriptor, pipeline, generation)
    } else if (success && descriptor != null && device != null && surface?.isValid == true) {
        if (activeVideoProfile?.constrainedHighSpeed == true) {
            configureHighSpeedPreviewSession(device, descriptor, surface, generation)
        } else configureSession(device, descriptor, surface, generation)
    } else {
        drained?.let { runCatching { it.close() } }
        if (!success) listener?.onFailure("video-stop-failed", "The recording was too short or could not be finalized.", true)
    }
}

internal fun Camera2PreviewEngine.recorderOutputRetirement(): CompletableFuture<Unit> {
    val receipt = CompletableFuture<Unit>()
    val failure = retirementFailure
    if (failure != null) receipt.completeExceptionally(failure)
    else if (recorder != null || recorderRequest.get() != null) {
        receipt.completeExceptionally(IllegalStateException("MediaRecorder output remains owned"))
    } else receipt.complete(Unit)
    return receipt
}

internal fun Camera2PreviewEngine.reportRecordingStopped(success: Boolean, retirement: CompletableFuture<Unit>) {
    stoppedOutputRetirement = retirement
    val retired = retirement.isDone && !retirement.isCompletedExceptionally && !retirement.isCancelled
    listener?.onRecordingStopped(success && retired)
}

internal fun Camera2PreviewEngine.ownsRecorderRequest(request: RecorderRequest): Boolean =
    recorderRequest.get() === request && !disposed.get() && generation == request.generation && camera === request.device

/**
 * MediaRecorder owns the microphone on this route, so there is no PCM to meter. Its peak
 * amplitude is the only level it exposes: publish that as a peak-only mono reading, with no
 * RMS, VU or PPM value, so the meter never shows a measurement this route cannot make.
 */
internal fun Camera2PreviewEngine.startRecorderLevelPolling(request: RecorderRequest) {
    val sink = recorderLevelSink ?: return
    runCatching { recorder?.maxAmplitude } // The first read only resets the peak.
    val tick = object : Runnable {
        override fun run() {
            cameraExecutor.execute {
                if (!recording || !ownsRecorderRequest(request) || request.stopRequested.get()) return@execute
                val amplitude = runCatching { recorder?.maxAmplitude }.getOrNull() ?: return@execute
                sink(recorderPeakSnapshot(amplitude, SystemClock.elapsedRealtime()))
                recorderLevelHandler.postDelayed(this, RECORDER_LEVEL_INTERVAL_MS)
            }
        }
    }
    recorderLevelHandler.postDelayed(tick, RECORDER_LEVEL_INTERVAL_MS)
}

internal fun Camera2PreviewEngine.releaseRecorder(expected: RecorderRequest? = recorderRequest.get()) {
    if (recorderRequest.get() !== expected) return
    recording = false
    recorderLevelSink = null
    activeRecorderGuard?.detach()
    activeRecorderGuard = null
    runCatching { recorder?.reset() }
    val releaseFailure = runCatching { recorder?.release() }.exceptionOrNull()
    if (releaseFailure != null) {
        failNativeRetirement(releaseFailure)
        return // Keep the recorder/admission fence; an attempted release is not retirement.
    }
    recorder = null
    recordSurface = null
    recorderVideoCodec = null
    recorderOverGpuPreview = false
    // Release the acceptance fence last, never while MediaRecorder still owns native I/O.
    recorderRequest.compareAndSet(expected, null)
}
