/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.graphics.ImageFormat
import android.graphics.ColorSpace
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.ImageReader
import android.os.Build
import com.librestatic.opencinecam.core.model.Knowledge
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.RejectedExecutionException
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.CompressedStillPlan
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.JpegPayload
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.PreviewStartedReceipt
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.RecorderRequest
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.CAPTURE_AUDIT_SUMMARY_MS
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.MAX_BURST_IMAGES
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.METADATA_PERIOD_MS
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.TAG

// Part of Camera2PreviewEngine: capture session graphs: preview, recording, high-speed and LOG sessions and their callbacks.
// These are extensions so the engine's state stays in one object; they run on the engine's executors like its members.

internal fun Camera2PreviewEngine.configureGpuPreviewSession(device: CameraDevice, descriptor: Camera2CameraDescriptor, pipeline: OpenCineLogGpuPipeline, currentGeneration: Long) {
    val constrained = activeVideoProfile?.constrainedHighSpeed == true
    val input = pipeline.cameraInputSurface
    fun sessionException(failure: Throwable) {
        if (withdrawLensLock(LensLockUnavailable.CONFIGURE_FAILED)) return
        listener?.onFailure("video-preview-gpu-session-exception", failure.message ?: "The GPU preview stream could not be created.", true)
    }
    val output = sessionOutput(input, ::sessionException) ?: return
    if (!constrained) applyStreamUse(output, descriptor, StreamRole.PREVIEW_AND_RECORDING)
    val configuration = SessionConfiguration(
        if (constrained) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR,
        listOf(output), cameraExecutor,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (currentGeneration != generation || logPipeline !== pipeline || !gpuPreviewEnabled) { configured.close(); return }
                session = configured
                try {
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(input)
                        if (constrained) { applyEngineRequest(EngineRequestMode.HIGH_SPEED_PREVIEW); applyHighSpeedControls(this) } else {
                            applyEngineRequest(EngineRequestMode.VIDEO_RECORD)
                            applyTargetFps(this, descriptor)
                            applyManualControls(this, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                    }
                    repeatingBuilder = builder
                    val callback = previewCaptureCallback(descriptor, true)
                    if (constrained) {
                        val highSpeed = configured as CameraConstrainedHighSpeedCaptureSession
                        highSpeed.setRepeatingBurstRequests(highSpeed.createHighSpeedRequestList(builder.build()), cameraExecutor, callback)
                    } else configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, callback)
                    notifyZoomRange()
                } catch (failure: Throwable) {
                    if (withdrawLensLock(LensLockUnavailable.CONFIGURE_FAILED)) return
                    listener?.onFailure("video-preview-gpu-request-failed", failure.message ?: "GPU preview request failed.", true)
                }
            }
            override fun onConfigureFailed(configured: CameraCaptureSession) {
                configured.close()
                if (currentGeneration == generation && !withdrawLensLock(LensLockUnavailable.CONFIGURE_FAILED)) listener?.onFailure("video-preview-gpu-session-failed", "The camera rejected the GPU preview stream.", true)
            }
        },
    )
    try {
        configureProcessingSession(configuration, device, CameraDevice.TEMPLATE_RECORD)
        createSessionRetryingWithoutStreamUse(device, descriptor, configuration)
    } catch (failure: Exception) {
        sessionException(failure)
    }
}

internal fun Camera2PreviewEngine.configureGpuInputSession(
    device: CameraDevice,
    descriptor: Camera2CameraDescriptor,
    pipeline: OpenCineLogGpuPipeline,
    geometry: RecordingGeometry,
) {
    val input = pipeline.cameraInputSurface
    val constrained = activeVideoProfile?.constrainedHighSpeed == true
    val currentGeneration = generation
    val inputOutput = OutputConfiguration(input)
    if (!constrained) applyStreamUse(inputOutput, descriptor, StreamRole.PREVIEW_AND_RECORDING)
    val configuration = SessionConfiguration(
        if (constrained) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR,
        listOf(inputOutput),
        cameraExecutor,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (logPipeline !== pipeline || !recording) {
                    configured.close()
                    return
                }
                session = configured
                try {
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(input)
                        if (constrained) {
                            applyEngineRequest(EngineRequestMode.HIGH_SPEED_PREVIEW)
                            applyHighSpeedControls(this)
                        } else {
                            applyEngineRequest(EngineRequestMode.VIDEO_RECORD)
                            applyTargetFps(this, descriptor)
                            applyManualControls(this, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                    }
                    repeatingBuilder = builder
                    val recordingCallback = recordingCaptureCallback(descriptor) {
                        listener?.onRecordingStarted(geometry.encodedSize.width, geometry.encodedSize.height)
                    }
                    if (constrained) {
                        val highSpeed = configured as? CameraConstrainedHighSpeedCaptureSession
                            ?: error("Camera did not return a constrained high-speed session.")
                        highSpeed.setRepeatingBurstRequests(
                            highSpeed.createHighSpeedRequestList(builder.build()),
                            cameraExecutor,
                            recordingCallback,
                        )
                    } else {
                        configured.setPhotoAwareRepeatingRequest(
                            builder.build(),
                            cameraExecutor,
                            recordingCallback,
                        )
                    }
                } catch (failure: Throwable) {
                    pipeline.stopRecording()
                    listener?.onFailure("video-gpu-session-request-failed", failure.message ?: "GPU video request failed.", true)
                }
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                configured.close()
                // A replaced graph's late failure must not stop or report on the current owner.
                if (currentGeneration != generation || logPipeline !== pipeline || !recording) return
                pipeline.stopRecording()
                listener?.onFailure("video-gpu-session-failed", "CameraService rejected the GPU video session.", true)
            }
        },
    )
    configureProcessingSession(configuration, device, CameraDevice.TEMPLATE_RECORD)
    createSessionRetryingWithoutStreamUse(device, descriptor, configuration)
}

internal fun Camera2PreviewEngine.configureRecordingSession(
    device: CameraDevice,
    descriptor: Camera2CameraDescriptor,
    preview: Surface?,
    recordingSurface: Surface,
    startRecorder: Boolean,
) {
    if (activeVideoProfile?.constrainedHighSpeed == true) {
        configureHighSpeedRecordingSession(device, descriptor, preview, recordingSurface, startRecorder)
        return
    }
    val request = requireNotNull(recorderRequest.get())
    val currentRecordingGeneration = ++recordingSessionGeneration
    fun ownsConfiguration(): Boolean = ownsRecorderRequest(request) &&
        currentRecordingGeneration == recordingSessionGeneration && recorder != null && !request.stopRequested.get()
    val surfaces = buildList {
        preview?.takeIf { it.isValid }?.let(::add)
        add(recordingSurface)
    }
    try {
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            surfaces.map { surface ->
                OutputConfiguration(surface).also {
                    applyStreamUse(it, descriptor, if (surface === recordingSurface) StreamRole.RECORDING else StreamRole.PREVIEW)
                }
            },
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (!ownsConfiguration()) {
                        configured.close()
                        return
                    }
                    session = configured
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            preview?.takeIf { it.isValid }?.let(::addTarget)
                            addTarget(recordingSurface)
                            applyEngineRequest(EngineRequestMode.VIDEO_RECORD)
                            applyTargetFps(this, descriptor)
                            applyManualControls(this, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        repeatingBuilder = builder
                        configured.setPhotoAwareRepeatingRequest(
                            builder.build(),
                            cameraExecutor,
                            previewCaptureCallback(descriptor, false),
                        )
                        if (startRecorder) {
                            requireNotNull(recorder).start()
                            recording = true
                            startRecorderLevelPolling(request)
                            listener?.onRecordingStarted(descriptor.previewSize.width, descriptor.previewSize.height)
                        }
                    } catch (failure: Exception) {
                        if (startRecorder) {
                            releaseRecorder(request)
                            listener?.onFailure("video-start-failed", failure.message ?: "Video recording could not start.", true)
                        } else {
                            finalizeRecordingAfterSessionFailure(request)
                        }
                    }
                }

                override fun onReady(configured: CameraCaptureSession) = onRecorderSessionReady(configured)

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    if (!ownsConfiguration()) return
                    if (startRecorder) {
                        releaseRecorder(request)
                        listener?.onFailure("video-session-failed", "Video recording configuration failed.", true)
                    } else {
                        finalizeRecordingAfterSessionFailure(request)
                    }
                }
            },
        )
        configureProcessingSession(configuration, device, CameraDevice.TEMPLATE_RECORD)
        recordingSessionObserver?.invoke(configuration)
        createSessionRetryingWithoutStreamUse(device, descriptor, configuration)
    } catch (failure: Exception) {
        if (!ownsConfiguration()) return
        if (startRecorder) {
            releaseRecorder(request)
            listener?.onFailure("video-session-exception", failure.message ?: "Video recording configuration failed.", true)
        } else {
            finalizeRecordingAfterSessionFailure(request)
        }
    }
}

internal fun Camera2PreviewEngine.configureHighSpeedPreviewSession(
    device: CameraDevice,
    descriptor: Camera2CameraDescriptor,
    surface: Surface,
    currentGeneration: Long,
) {
    fun sessionException(failure: Throwable) {
        listener?.onFailure("high-speed-preview-session-exception", failure.message ?: "High-speed preview could not be created.", true)
    }
    val output = sessionOutput(surface, ::sessionException) ?: return
    val configuration = SessionConfiguration(
        SessionConfiguration.SESSION_HIGH_SPEED,
        listOf(output),
        cameraExecutor,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (currentGeneration != generation || configured !is CameraConstrainedHighSpeedCaptureSession) {
                    configured.close()
                    return
                }
                session = configured
                try {
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        applyEngineRequest(EngineRequestMode.HIGH_SPEED_PREVIEW)
                        applyHighSpeedControls(this)
                    }
                    repeatingBuilder = builder
                    val burst = configured.createHighSpeedRequestList(builder.build())
                    configured.setRepeatingBurstRequests(burst, cameraExecutor, previewCaptureCallback(descriptor, false))
                    listener?.onPreviewStarted(appDescriptor(descriptor))
                    notifyZoomRange()
                } catch (failure: Throwable) {
                    listener?.onFailure("high-speed-preview-request-failed", failure.message ?: "High-speed preview request failed.", true)
                }
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                configured.close()
                if (currentGeneration != generation) return
                listener?.onFailure("high-speed-preview-session-failed", "CameraService rejected the constrained high-speed preview.", true)
            }
        },
    )
    // CONTROL_AE_TARGET_FPS_RANGE is advertised as a session key on Motorola devices.
    // Supplying it only after configuration lets the Qualcomm HAL choose the HFR graph
    // without knowing which fixed sensor cadence the first request will require.
    runCatching {
        configuration.setSessionParameters(
            device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                applyHighSpeedControls(this)
            }.build(),
        )
        device.createCaptureSession(configuration)
    }.onFailure(::sessionException)
}

internal fun Camera2PreviewEngine.configureHighSpeedRecordingSession(
    device: CameraDevice,
    descriptor: Camera2CameraDescriptor,
    preview: Surface?,
    recordingSurface: Surface,
    startRecorder: Boolean,
) {
    val request = requireNotNull(recorderRequest.get())
    val currentRecordingGeneration = ++recordingSessionGeneration
    fun ownsConfiguration(): Boolean = ownsRecorderRequest(request) &&
        currentRecordingGeneration == recordingSessionGeneration && recorder != null && !request.stopRequested.get()
    val surfaces = buildList {
        preview?.takeIf { it.isValid }?.let(::add)
        add(recordingSurface)
    }
    try {
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_HIGH_SPEED,
            surfaces.map(::OutputConfiguration),
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (!ownsConfiguration()) {
                        configured.close()
                        return
                    }
                    if (configured !is CameraConstrainedHighSpeedCaptureSession) {
                        configured.close()
                        if (startRecorder) {
                            releaseRecorder(request)
                            listener?.onFailure("high-speed-video-session-failed", "CameraService returned a non-high-speed recording session.", true)
                        } else finalizeRecordingAfterSessionFailure(request)
                        return
                    }
                    session = configured
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            preview?.takeIf { it.isValid }?.let(::addTarget)
                            addTarget(recordingSurface)
                            applyEngineRequest(EngineRequestMode.HIGH_SPEED_RECORD)
                            applyHighSpeedControls(this)
                        }
                        repeatingBuilder = builder
                        val burst = configured.createHighSpeedRequestList(builder.build())
                        configured.setRepeatingBurstRequests(burst, cameraExecutor, previewCaptureCallback(descriptor, false))
                        if (startRecorder) {
                            requireNotNull(recorder).start()
                            recording = true
                            startRecorderLevelPolling(request)
                            val size = activeVideoProfile?.size ?: descriptor.previewSize
                            listener?.onRecordingStarted(size.width, size.height)
                        }
                    } catch (failure: Throwable) {
                        if (startRecorder) {
                            releaseRecorder(request)
                            listener?.onFailure("high-speed-video-start-failed", failure.message ?: "High-speed video could not start.", true)
                        } else finalizeRecordingAfterSessionFailure(request)
                    }
                }

                override fun onReady(configured: CameraCaptureSession) = onRecorderSessionReady(configured)

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    Log.w(TAG, "High-speed recording session rejected: startRecorder=$startRecorder owns=${ownsConfiguration()} preview=${preview != null}")
                    if (!ownsConfiguration()) return
                    if (startRecorder) {
                        releaseRecorder(request)
                        listener?.onFailure("high-speed-video-session-failed", "CameraService rejected the constrained high-speed recording.", true)
                    } else finalizeRecordingAfterSessionFailure(request)
                }
            },
        )
        configuration.setSessionParameters(
            device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                surfaces.forEach(::addTarget)
                applyHighSpeedControls(this)
            }.build(),
        )
        recordingSessionObserver?.invoke(configuration)
        device.createCaptureSession(configuration)
    } catch (failure: Exception) {
        if (!ownsConfiguration()) return
        if (startRecorder) {
            releaseRecorder(request)
            listener?.onFailure("high-speed-video-session-exception", failure.message ?: "High-speed recording could not be created.", true)
        } else finalizeRecordingAfterSessionFailure(request)
    }
}

internal fun Camera2PreviewEngine.finalizeRecordingAfterSessionFailure(request: RecorderRequest) {
    if (!ownsRecorderRequest(request)) return
    var success = true
    try {
        recorder?.stop()
    } catch (_: RuntimeException) {
        success = false
    }
    releaseRecorder(request)
    reportRecordingStopped(success, recorderOutputRetirement())
}

internal fun Camera2PreviewEngine.configureLogSession(
    device: CameraDevice,
    descriptor: Camera2CameraDescriptor,
    currentGeneration: Long,
) {
    val profile = activeLogProfile ?: run {
        listener?.onFailure("log-profile-missing", "The selected OCLog profile is unavailable.", false)
        return
    }
    if (profile.constrainedHighSpeed) {
        configureHighSpeedLogSession(device, descriptor, profile, currentGeneration)
        return
    }
    if (profile.sourcePath != OpenCineLogSourcePath.HLG10_BT2020) {
        listener?.onFailure("log-source-invalid", "Regular OCLog requires the HLG10 source path.", false)
        return
    }
    val input = logPipeline?.cameraInputSurface ?: run {
        listener?.onFailure("log-input-surface-missing", "The OCLog camera input surface is unavailable.", false)
        return
    }
    fun sessionException(failure: Throwable) {
        if (withdrawLensLock(LensLockUnavailable.CONFIGURE_FAILED)) return
        listener?.onFailure("log-session-exception", failure.message ?: "The HLG10 OCLog graph could not be created.", false)
    }
    val output = sessionOutput(input, ::sessionException) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        output.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
    }
    applyStreamUse(output, descriptor, StreamRole.PREVIEW_AND_RECORDING)
    val configuration = SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR,
        listOf(output),
        cameraExecutor,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (currentGeneration != generation || !logPreviewEnabled) {
                    configured.close()
                    return
                }
                session = configured
                try {
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(input)
                        applyEngineRequest(EngineRequestMode.LOG_RECORD)
                        applyTargetFps(this, descriptor)
                        applyManualControls(this, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }
                    repeatingBuilder = builder
                    configured.setPhotoAwareRepeatingRequest(
                        builder.build(),
                        cameraExecutor,
                        previewCaptureCallback(descriptor, true),
                    )
                    notifyZoomRange()
                } catch (failure: Throwable) {
                    if (withdrawLensLock(LensLockUnavailable.CONFIGURE_FAILED)) return
                    listener?.onFailure("log-request-failed", failure.message ?: "The HLG10 source request failed.", false)
                }
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                configured.close()
                if (currentGeneration != generation || !logPreviewEnabled) return
                if (withdrawLensLock(LensLockUnavailable.CONFIGURE_FAILED)) return
                listener?.onFailure("log-session-failed", "CameraService rejected the HLG10 OCLog graph.", false)
            }
        },
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        configuration.setColorSpace(ColorSpace.Named.BT2020_HLG)
    }
    try {
        configureProcessingSession(configuration, device, CameraDevice.TEMPLATE_RECORD)
        createSessionRetryingWithoutStreamUse(
            device, descriptor, configuration,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ColorSpace.Named.BT2020_HLG else null,
        )
    } catch (failure: Throwable) {
        sessionException(failure)
    }
}

/**
 * HFR cannot be combined with HLG/HDR on the validated Motorola HAL. This session therefore uses
 * the standard-range camera stream and leaves the explicit SDR→linear→OCLog conversion to the
 * GPU pipeline. The profile and sidecar retain that reduced-provenance disclosure.
 */
internal fun Camera2PreviewEngine.configureHighSpeedLogSession(
    device: CameraDevice,
    descriptor: Camera2CameraDescriptor,
    profile: Camera2LogProfile,
    currentGeneration: Long,
) {
    if (profile.sourcePath != OpenCineLogSourcePath.SDR_BT709_ISP) {
        listener?.onFailure("log-hfr-source-invalid", "High-speed OCLog requires the disclosed ISP-derived source.", false)
        return
    }
    val input = logPipeline?.cameraInputSurface ?: run {
        listener?.onFailure("log-input-surface-missing", "The OCLog camera input surface is unavailable.", false)
        return
    }
    fun sessionException(failure: Throwable) {
        listener?.onFailure(
            "log-hfr-session-exception",
            failure.message ?: "The ISP-derived OCLog high-speed graph could not be created.",
            true,
        )
    }
    val output = sessionOutput(input, ::sessionException) ?: return
    val configuration = SessionConfiguration(
        SessionConfiguration.SESSION_HIGH_SPEED,
        listOf(output),
        cameraExecutor,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (currentGeneration != generation || !logPreviewEnabled ||
                    configured !is CameraConstrainedHighSpeedCaptureSession
                ) {
                    configured.close()
                    return
                }
                session = configured
                try {
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(input)
                        applyEngineRequest(EngineRequestMode.HIGH_SPEED_RECORD)
                        applyHighSpeedControls(this)
                    }
                    repeatingBuilder = builder
                    configured.setRepeatingBurstRequests(
                        configured.createHighSpeedRequestList(builder.build()),
                        cameraExecutor,
                        previewCaptureCallback(descriptor, true),
                    )
                    notifyZoomRange()
                } catch (failure: Throwable) {
                    listener?.onFailure(
                        "log-hfr-request-failed",
                        failure.message ?: "The ISP-derived OCLog HFR request failed.",
                        true,
                    )
                }
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                configured.close()
                if (currentGeneration != generation || !logPreviewEnabled) return
                listener?.onFailure(
                    "log-hfr-session-failed",
                    "CameraService rejected the ISP-derived OCLog high-speed graph.",
                    true,
                )
            }
        },
    )
    runCatching {
        configuration.setSessionParameters(
            device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(input)
                applyHighSpeedControls(this)
            }.build(),
        )
        device.createCaptureSession(configuration)
    }.onFailure(::sessionException)
}

internal fun Camera2PreviewEngine.configureSession(
    device: CameraDevice,
    descriptor: Camera2CameraDescriptor,
    surface: Surface,
    currentGeneration: Long,
) {
    val photoPipeline = logPipeline.takeIf { gpuPhotoPreviewEnabled }
    if (gpuPhotoPreviewEnabled) check(photoPipeline != null && surface === photoPipeline.cameraInputSurface)
    val previewOutput = sessionOutput(surface) { failure ->
        if (!withdrawLensLock(LensLockUnavailable.CONFIGURE_FAILED)) listener?.onFailure("preview-session-exception", failure.message ?: "Camera preview configuration failed.", true)
    } ?: return
    applyStreamUse(previewOutput, descriptor, StreamRole.PREVIEW)
    val outputs = mutableListOf(previewOutput)
    var stillOutput: OutputConfiguration? = null
    var rawOutput: OutputConfiguration? = null
    var graphRawReader: ImageReader? = null
    var compressedPlan: CompressedStillPlan? = null
    if (activeVideoProfile == null) {
    val compressedFormat = if (activeStillFormat == StillPhotoFormat.HEIC) ImageFormat.HEIC else ImageFormat.JPEG
    compressedPlan = compressedStillPlan(descriptor)
    routedStillPhysicalId = compressedPlan?.physicalId
    forwardedAuditWarnings.clear()
    lastActiveLensReport = null
    compressedPlan?.physicalId?.let { listener?.onPhysicalRoute(it, null) }
    val compressedSize = compressedPlan?.size
    compressedSize?.let { size ->
        jpegReader = ImageReader.newInstance(size.width, size.height, compressedFormat, MAX_BURST_IMAGES + 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = runCatching { reader.acquireNextImage() }
                    .onFailure { Log.w(TAG, "Compressed still acquire failed; image dropped.", it) }
                    .getOrNull() ?: return@setOnImageAvailableListener
                var imageTimestamp: Long? = null
                var ticket: StillImageHandoff.Ticket<JpegPayload>? = null
                var transferred = false
                try {
                    image.use {
                        imageTimestamp = it.timestamp
                        val buffer = it.planes.first().buffer
                        if (photoRequest.get()?.aspect?.enabled == true) require(buffer.remaining() <= PhotoAspectBounds.MAX_ENCODED_BYTES) {
                            "Aspect source JPEG exceeds its encoded budget"
                        }
                        if (accumulationRequest.get() != null) require(buffer.remaining() <= CapturedAccumulation.MAX_ENCODED_BYTES) {
                            "Accumulation source JPEG exceeds its encoded budget"
                        }
                        burstRequest.get()?.let { group ->
                            require(buffer.remaining().toLong() <= CapturedBurst.MAX_ENCODED_BYTES - group.encodedBytes) {
                                "The complete burst exceeds its encoded byte budget"
                            }
                        }
                        bracketRequest.get()?.let { group ->
                            require(buffer.remaining().toLong() <= CapturedBracket.MAX_ENCODED_BYTES - group.encodedBytes) {
                                "The complete bracket exceeds its encoded byte budget"
                            }
                        }
                        require(buffer.remaining() in 1..StillImagePayload.MAX_COMPRESSED_BYTES) { "Compressed still exceeds its byte limit" }
                        val reservation = checkNotNull(compressedHandoff.reserve(buffer.remaining()) {
                            !disposed.get() && currentGeneration == generation && reader === jpegReader
                        }) { "Compressed still handoff byte budget is full or its graph retired" }
                        ticket = reservation
                        // Allocate only after reserving across producer, queue and pending map.
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val timestamp = it.timestamp
                        val payload = JpegPayload(bytes, it.width, it.height,
                            if (compressedFormat == ImageFormat.HEIC) StillImageKind.HEIC else StillImageKind.JPEG, reservation)
                        transferred = compressedHandoff.publish(reservation, payload)
                        if (transferred) cameraExecutor.execute {
                            // The runnable retains only the ticket; close can discard its
                            // payload even if this executor intentionally drops the callback.
                            compressedHandoff.take(reservation)?.let { value -> receiveJpeg(reader, currentGeneration, timestamp, value) }
                        }
                    }
                } catch (failure: Exception) {
                    imageTimestamp?.let { timestamp -> cameraExecutor.execute {
                        receiveCompressedFailure(reader, currentGeneration, timestamp, failure.message ?: "Still image could not be read.")
                    } }
                } finally { if (!transferred) ticket?.let(compressedHandoff::release) }
            }, imageHandler)
        }
        stillOutput = OutputConfiguration(requireNotNull(jpegReader).surface).also { output ->
            // OCC-PLAN-069: a size only a public physical camera advertises rides that
            // physical's stream; the preview and analysis keep flowing from the logical device.
            compressedPlan?.physicalId?.let(output::setPhysicalCameraId)
            applyStreamUse(output, descriptor, StreamRole.STILL)
            outputs += output
        }
    }
    descriptor.rawSize?.takeIf { activeStillFormat != StillPhotoFormat.HEIC }?.let { size ->
        rawReader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2).apply {
            setOnImageAvailableListener({ reader ->
                val received = runCatching { reader.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                queueRawImage(reader, currentGeneration, received)
            }, imageHandler)
        }
        graphRawReader = rawReader
        rawOutput = OutputConfiguration(requireNotNull(rawReader).surface).also { outputs += it }
    }
    }
    // GPU photo monitor already supplies pre-LUT analysis; avoid a redundant fourth Camera2
    // stream there. Video preview keeps the YUV analysis stream too so peaking/zebra/histogram
    // do not silently die when the mode leaves photo. `logPreviewEnabled` is belt-and-braces
    // only: LOG graphs are built by configureLogSession and never reach this function.
    var analysisOutput: OutputConfiguration? = null
    var analysisLease: ReaderLease<ImageReader>? = null
    descriptor.analysisSize?.takeUnless { gpuPhotoPreviewEnabled || logPreviewEnabled }?.let { size ->
        analysisReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { reader ->
            val lease = ReaderLease(reader)
            analysisReaderLease = lease
            analysisLease = lease
            reader.setOnImageAvailableListener({
                val analysis = lease.read { ownedReader ->
                    val image = runCatching { ownedReader.acquireLatestImage() }
                        .onFailure { Log.w(TAG, "Analysis image acquire failed; frame dropped.", it) }
                        .getOrNull()
                    image?.use {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastAnalysisAtMs >= analysisPeriodMs(monitoringOptions.periodMs, analysisBurst)) {
                            lastAnalysisAtMs = now
                            // Suspended scopes still acquire and close frames; only analysis stops.
                            if (!analysisSuspended) analyzeImage(it) else null
                        } else null
                    }
                }
                if (analysis != null && !disposed.get()) {
                    try { cameraExecutor.execute {
                        if (!disposed.get() && currentGeneration == generation && analysisReaderLease === lease) { observeCadenceLuma(analysis, "yuv"); deliverAnalysisToListener(analysis) }
                    } } catch (_: java.util.concurrent.RejectedExecutionException) { /* Terminal owner. */ }
                }
            }, imageHandler)
        }
        analysisOutput = OutputConfiguration(requireNotNull(analysisReader).surface).also {
            applyStreamUse(it, descriptor, StreamRole.PREVIEW)
            outputs += it
        }
    }
    fun ownsGraph(): Boolean = currentGeneration == generation &&
        (photoPipeline == null || !disposed.get() && logPipeline === photoPipeline)
    // The YUV and RAW streams are optional: a HAL rejecting the combination must cost the
    // scopes (then DNG capture), never the viewfinder or JPEG/HEIC. The negotiator orders the
    // attempts; the first one is exactly the graph assembled above.
    val configs = listOfNotNull(
        PREVIEW_GRAPH_PREVIEW to outputs.first(),
        stillOutput?.let { PREVIEW_GRAPH_STILL to it },
        rawOutput?.let { PREVIEW_GRAPH_RAW to it },
        analysisOutput?.let { PREVIEW_GRAPH_ANALYSIS to it },
    ).toMap()
    fun Size.stream() = StreamSize(width, height)
    val compressedStreamSize = compressedStillSize(descriptor)?.stream()
    val streams = runCatching { StreamCapabilityProbe(Camera2StreamMetadataSource(manager)).probe(descriptor.cameraId) }.getOrNull()
    val graphs = GraphNegotiator().fallbackGraphs(
        previewGraphRequest(
            cameraId = descriptor.cameraId,
            fps = requestedTargetFps,
            preview = (activeVideoProfile?.size ?: descriptor.previewSize).stream(),
            still = compressedStreamSize?.takeIf { stillOutput != null },
            raw = descriptor.rawSize?.stream()?.takeIf { rawOutput != null },
            analysis = descriptor.analysisSize?.stream()?.takeIf { analysisOutput != null },
        ),
        streams,
        PREVIEW_GRAPH_DROP_ORDER,
    )
    fun releaseDropped(kept: Set<String>) {
        if (PREVIEW_GRAPH_ANALYSIS !in kept && analysisLease != null && analysisReaderLease === analysisLease) closeAnalysisReader()
        if (PREVIEW_GRAPH_RAW !in kept && graphRawReader != null && rawReader === graphRawReader) {
            runCatching { rawReader?.close() }
            rawReader = null
        }
    }
    PreviewGraphFallback(
        graphCount = graphs.size,
        ownsGraph = ::ownsGraph,
        onRetry = { rejected, reason ->
            val dropped = graphs[rejected].outputs.map { it.id } - graphs[rejected + 1].outputs.map { it.id }.toSet()
            // analysis: scopes unavailable; raw: DNG capture unavailable (captureStill returns false).
            Log.w(TAG, "Preview graph rejected; retrying without ${dropped.joinToString()} ($reason).")
        },
        onFailure = { code, message ->
            val routedPhysical = compressedPlan?.physicalId
            if (routedPhysical != null && !physicalStillFallbackTaken) {
                Log.w(
                    TAG,
                    "Physical still routing to camera $routedPhysical was rejected ($code: $message); " +
                        "retrying the graph with logical-only still sizes.",
                )
                physicalStillFallbackTaken = true
                retireGraphReaders()
                configureSession(device, descriptor, surface, currentGeneration)
            } else if (!withdrawLensLock(LensLockUnavailable.CONFIGURE_FAILED)) {
                listener?.onFailure(code, message, true)
            }
        },
    ) { index ->
        val ids = graphs[index].outputs.map { it.id }
        releaseDropped(ids.toSet())
        // The first attempt uses the original configurations; retries get fresh ones. Wrapping
        // a surface again throws once the UI abandoned it, so this stays inside the attempt.
        val sessionOutputs = if (index == 0) ids.map(configs::getValue)
            else ids.mapNotNull { id -> configs.getValue(id).surface?.let { OutputConfiguration(it) }?.also { copy ->
                previewGraphRole(id)?.let { applyStreamUse(copy, descriptor, it) }
            } }
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            sessionOutputs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (currentGeneration != generation ||
                        photoPipeline != null && (disposed.get() || logPipeline !== photoPipeline || !gpuPhotoPreviewEnabled)) {
                        configured.close()
                        return
                    }
                    session = configured
                    listener?.onPreviewGraphLoss(PreviewGraphLoss.of(graphs[0].outputs.map { it.id }, ids, physicalStillFallbackTaken))
                    startRepeating(device, configured, descriptor)
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    configureFailed(index)
                }
            },
        )
        configureProcessingSession(configuration, device, CameraDevice.TEMPLATE_PREVIEW)
        var support = sessionConfigurationSupport { device.isSessionConfigurationSupported(configuration) }
        if ((support as? Knowledge.Known)?.value == false && clearStreamUse(configuration)) {
            // The query rejected the use cases, not necessarily the graph: ask again on DEFAULT.
            StreamUseCaseRejections.reject(descriptor.cameraId)
            Log.w(TAG, "Camera ${descriptor.cameraId} rejected stream use cases in isSessionConfigurationSupported; using DEFAULT.")
            support = sessionConfigurationSupport { device.isSessionConfigurationSupported(configuration) }
        }
        if (skipBySessionPreCheck(support, hasFallback(index))) {
            return@PreviewGraphFallback false
        }
        createSessionRetryingWithoutStreamUse(device, descriptor, configuration)
        true
    }.start()
}

internal fun Camera2PreviewEngine.startRepeating(
    device: CameraDevice,
    configured: CameraCaptureSession,
    descriptor: Camera2CameraDescriptor,
) {
    val surface = targetPreviewCameraSurface() ?: return
    try {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            analysisReader?.surface?.let(::addTarget)
            applyEngineRequest(EngineRequestMode.PREVIEW)
            applyTargetFps(this, descriptor)
            applyManualControls(this)
        }
        repeatingBuilder = builder
        configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, true))
        notifyZoomRange()
    } catch (failure: Exception) {
        if (withdrawLensLock(LensLockUnavailable.CONFIGURE_FAILED)) return
        listener?.onFailure("preview-request-failed", failure.message ?: "Camera preview request failed.", true)
    }
}

internal fun Camera2PreviewEngine.previewCaptureCallback(
    descriptor: Camera2CameraDescriptor?,
    notifyStarted: Boolean,
): CameraCaptureSession.CaptureCallback {
    val callbackGeneration = generation
    // Bind to the session armed now rather than to the callback's session argument: a
    // constrained high-speed session reports its bursts with the inner capture session, not
    // the CameraConstrainedHighSpeedCaptureSession the engine holds, so an identity check
    // against the argument dropped every HFR result and a GPU HFR preview never "started".
    val armedSession = session
    if (notifyStarted && descriptor != null && !disposed.get()) {
        armedSession?.let { pendingPreviewStartedReceipt = PreviewStartedReceipt(it, callbackGeneration, descriptor) }
    }
    return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureProgressed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                partialResult: android.hardware.camera2.CaptureResult,
            ) {
                if (disposed.get() || callbackGeneration != generation) return
                noteLensForFrame(partialResult)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (disposed.get() || callbackGeneration != generation || armedSession == null ||
                    armedSession !== this@previewCaptureCallback.session) return
                noteLensForFrame(result)
                noteZoomLag(armedSession, result)
                noteCalibrationMeta(result)
                traceLensSwitch(armedSession, request, result)
                pendingPreviewStartedReceipt?.takeIf { it.generation == callbackGeneration && it.session === armedSession }?.let { receipt ->
                    // Consume before notifying: reentrant callbacks or a late original
                    // request cannot announce the same armed graph a second time.
                    pendingPreviewStartedReceipt = null
                    listener?.onPreviewStarted(appDescriptor(receipt.descriptor))
                }
                val sensorTimestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                updateEffectiveFps(sensorTimestamp)
                auditCaptureResult(armedSession, result, sensorTimestamp)
                driveCadenceGuard(result, sensorTimestamp)
                reportTapFocusResult(request, result)
                reportAfLockResult(request, result)
                reportRecordingWhiteBalance(request, result)
                reportEffectiveZoom(result)
                reportActiveLens(result)
                reportLensLockSwitch(result)
                releaseZoomOverrideIfIdle(result)
                reportSubjectFraming(request, result)
                readIspHistogram(result)
                driveInstantAec(armedSession, request, result)
                val now = SystemClock.elapsedRealtime()
                if (now - lastMetadataAtMs >= METADATA_PERIOD_MS) {
                    lastMetadataAtMs = now
                    listener?.onMetadata(
                        Camera2PreviewMetadata(
                            result.frameNumber,
                            sensorTimestamp,
                            result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY),
                            result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
                            result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE).also {
                                it?.let { distance -> lastReportedFocusDiopters = distance }
                            },
                            result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE),
                            result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE),
                            if (Build.VERSION.SDK_INT >= 36) {
                                result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE)
                            } else null,
                            effectiveFps,
                            result.get(android.hardware.camera2.CaptureResult.FLASH_MODE)?.let { it == CaptureRequest.FLASH_MODE_TORCH },
                            if (Build.VERSION.SDK_INT >= 35 && descriptor?.torchCapabilities?.adjustable == true) {
                                result.get(android.hardware.camera2.CaptureResult.FLASH_STRENGTH_LEVEL)
                            } else null,
                            afMode = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_MODE),
                            submittedAfMode = request.get(CaptureRequest.CONTROL_AF_MODE),
                            submittedFocusDistanceDiopters = request.get(CaptureRequest.LENS_FOCUS_DISTANCE),
                            rollingShutterSkewNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
                            awbGains = result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS)
                                ?.let { listOf(it.red, it.greenEven, it.greenOdd, it.blue) },
                            awbLocked = result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_LOCK),
                            colorTint = if (Build.VERSION.SDK_INT >= 36) result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_COLOR_TINT) else null,
                            exposureMode = when (result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_MODE)) {
                                CaptureRequest.CONTROL_AE_MODE_OFF -> if (cadenceHoldActive()) ExposureMode.AUTO else ExposureMode.MANUAL
                                null -> null
                                else -> if (Build.VERSION.SDK_INT >= 36) when (result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_PRIORITY_MODE)) {
                                    CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY -> ExposureMode.ISO_PRIORITY
                                    CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY ->
                                        if (cadenceHoldActive()) ExposureMode.AUTO else ExposureMode.SHUTTER_PRIORITY
                                    CaptureRequest.CONTROL_AE_PRIORITY_MODE_OFF -> ExposureMode.AUTO
                                    else -> if (professionalExposure?.mode in setOf(ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY)) null else ExposureMode.AUTO
                                } else ExposureMode.AUTO
                            },
                            antibanding = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_ANTIBANDING_MODE),
                            lowLightBoostActive = if (Build.VERSION.SDK_INT >= 35) lowLightBoostActive(
                                request.get(CaptureRequest.CONTROL_AE_MODE),
                                result.get(android.hardware.camera2.CaptureResult.CONTROL_LOW_LIGHT_BOOST_STATE)) else null,
                            meteringMode = vendorMeteringKeys()?.result?.let { key -> runCatching { result.get(key) }.getOrNull() },
                            opticalStabilization = result.get(android.hardware.camera2.CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
                            videoStabilization = result.get(android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
                            noiseReduction = result.get(android.hardware.camera2.CaptureResult.NOISE_REDUCTION_MODE),
                            edgeEnhancement = result.get(android.hardware.camera2.CaptureResult.EDGE_MODE),
                            toneMapMode = result.get(android.hardware.camera2.CaptureResult.TONEMAP_MODE),
                            submittedToneMapMode = request.get(CaptureRequest.TONEMAP_MODE),
                            cropRegion = result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)?.let { listOf(it.left, it.top, it.right, it.bottom) },
                            submittedImageProcessing = ImageProcessingDefaults(
                                request.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE), request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE),
                                request.get(CaptureRequest.NOISE_REDUCTION_MODE), request.get(CaptureRequest.EDGE_MODE)),
                            pictureControls = pictureControlReadback(request, result),
                        ),
                    )
                }
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                if (disposed.get() || callbackGeneration != generation || armedSession == null ||
                    armedSession !== this@previewCaptureCallback.session) return
                runCatching { graphAuditor(armedSession).recordCaptureFailure() }
                if (request.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) == faceDetectModeFor(activeDescriptor) &&
                    faceDetectModeFor(activeDescriptor) != null && faceDetectGate.onCaptureFailed()) reissueFaceDetect()
            }
        }
}

/** cameraExecutor: the current graph's DIAGNOSTIC auditor, replaced (and summarized) when the session changes. */
internal fun Camera2PreviewEngine.graphAuditor(session: CameraCaptureSession): CaptureResultAuditor {
    graphAudit?.takeIf { it.first === session }?.let { return it.second }
    retireGraphAudit()
    val frameUs = 1_000_000L / requestedTargetFps.coerceAtLeast(1)
    return CaptureResultAuditor(CaptureAuditPolicy.DIAGNOSTIC, cadenceTargetUs = frameUs, cadenceToleranceUs = frameUs / 4,
        correlationId = "graph-audit").also { graphAudit = session to it }
}

internal fun Camera2PreviewEngine.retireGraphAudit() {
    val auditor = graphAudit?.second ?: return
    graphAudit = null
    Log.i(TAG, "Capture audit (graph retired): ${auditor.diagnostics()}")
    auditor.close()
}

/**
 * cameraExecutor, DIAGNOSTIC only: collects cadence, dropped-frame and AE/AF statistics for
 * the current graph and logs them once per summary interval. It never stops or fails a take;
 * any auditing error is swallowed so the preview callback is unaffected.
 */
internal fun Camera2PreviewEngine.auditCaptureResult(session: CameraCaptureSession, result: TotalCaptureResult, sensorTimestamp: Long?) {
    runCatching {
        val auditor = graphAuditor(session)
        val metadata = buildMap {
            result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)?.let {
                put(AUDIT_AE_STATE, if (it == CaptureRequest.CONTROL_AE_STATE_SEARCHING) AUDIT_AE_SEARCHING else it.toString())
            }
            result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)?.let { put(AUDIT_AF_STATE, it.toString()) }
        }
        val droppedBefore = auditor.diagnostics().droppedFrames
        auditor.auditResult(CaptureResultSample(result.frameNumber, sensorTimestamp?.takeIf { it >= 0 }, metadata))
            .forEach { event -> auditWarningText(event)?.takeIf(forwardedAuditWarnings::add)?.let { listener?.onAuditWarning(it) } }
        val stats = auditor.diagnostics()
        checkSustainedCadence(sensorTimestamp, stats.droppedFrames - droppedBefore, stats)
        latestCaptureDiagnostics = stats
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureAuditSummaryAtMs >= CAPTURE_AUDIT_SUMMARY_MS) {
            lastCaptureAuditSummaryAtMs = now
            Log.i(TAG, "Capture audit: $stats")
        }
    }.onFailure { Log.w(TAG, "Capture audit skipped a result.", it) }
}

internal fun Camera2PreviewEngine.recordingCaptureCallback(
    descriptor: Camera2CameraDescriptor,
    onFirstFrame: () -> Unit,
): CameraCaptureSession.CaptureCallback {
    val metadataDelegate = previewCaptureCallback(descriptor, false)
    return object : CameraCaptureSession.CaptureCallback() {
        var pendingStart = true

        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long,
        ) {
            if (pendingStart) {
                pendingStart = false
                onFirstFrame()
            }
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) = metadataDelegate.onCaptureCompleted(session, request, result)
    }
}

/** Supply advertised expensive controls up front; later between-take changes may reconfigure HAL. */
internal fun Camera2PreviewEngine.configureProcessingSession(configuration: SessionConfiguration, device: CameraDevice, template: Int) {
    if (configuration.sessionType == SessionConfiguration.SESSION_HIGH_SPEED || configuration.sessionParameters != null) return
    val dynamicFps = activeDescriptor?.lowLightAutoFps == true && !isHighSpeedSession()
    if (!dynamicFps && activeDescriptor?.imageProcessingCapabilities?.sessionControls.isNullOrEmpty()) return
    configuration.setSessionParameters(device.createCaptureRequest(template).apply {
        applyImageProcessing(this)
        applyDynamicFpsSession(this)
    }.build())
}

internal fun Camera2PreviewEngine.previewGraphRole(id: String): StreamRole? = when (id) {
    PREVIEW_GRAPH_PREVIEW, PREVIEW_GRAPH_ANALYSIS -> StreamRole.PREVIEW
    PREVIEW_GRAPH_STILL -> StreamRole.STILL
    else -> null
}

/**
 * Tags [output] with the stream use case for [role] when the camera advertises it (API 33+),
 * unless this camera already refused a session carrying them. Never called for constrained
 * high-speed sessions. Any failure leaves the output on DEFAULT.
 */
internal fun Camera2PreviewEngine.applyStreamUse(output: OutputConfiguration, descriptor: Camera2CameraDescriptor, role: StreamRole) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || StreamUseCaseRejections.isRejected(descriptor.cameraId)) return
    val useCase = streamUseCaseFor(role, descriptor.streamUseCases) ?: return
    try {
        output.streamUseCase = useCase
        StreamUseCaseRejections.markApplied(descriptor.cameraId)
    } catch (failure: RuntimeException) {
        Log.w(TAG, "Stream use case ${StreamUseCase.label(useCase)} could not be set for $role.", failure)
    }
}

/** Puts every output of [configuration] back on DEFAULT; true when any carried a use case. */
internal fun Camera2PreviewEngine.clearStreamUse(configuration: SessionConfiguration): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    var cleared = false
    configuration.outputConfigurations.forEach { output ->
        if (output.streamUseCase != StreamUseCase.DEFAULT) {
            output.streamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT.toLong()
            cleared = true
        }
    }
    return cleared
}

/**
 * createCaptureSession that survives a HAL refusing the stream use cases: when a session whose
 * outputs carry them fails to configure, the same configuration is resubmitted once on DEFAULT
 * and the camera id is remembered for the process. The caller's callback only hears about a
 * failure of that second attempt (or of a session that never carried use cases).
 */
internal fun Camera2PreviewEngine.createSessionRetryingWithoutStreamUse(
    device: CameraDevice,
    descriptor: Camera2CameraDescriptor,
    configuration: SessionConfiguration,
    colorSpace: ColorSpace.Named? = null,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        configuration.outputConfigurations.none { it.streamUseCase != StreamUseCase.DEFAULT }
    ) {
        device.createCaptureSession(configuration)
        return
    }
    Log.i(TAG, "Camera ${descriptor.cameraId} session uses stream use cases " +
        configuration.outputConfigurations.joinToString { StreamUseCase.label(it.streamUseCase) })
    val delegate = configuration.stateCallback
    val wrapped = SessionConfiguration(configuration.sessionType, configuration.outputConfigurations, configuration.executor,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = delegate.onConfigured(session)
            override fun onReady(session: CameraCaptureSession) = delegate.onReady(session)
            override fun onActive(session: CameraCaptureSession) = delegate.onActive(session)
            override fun onCaptureQueueEmpty(session: CameraCaptureSession) = delegate.onCaptureQueueEmpty(session)
            override fun onClosed(session: CameraCaptureSession) = delegate.onClosed(session)
            override fun onSurfacePrepared(session: CameraCaptureSession, surface: Surface) = delegate.onSurfacePrepared(session, surface)
            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (!clearStreamUse(configuration)) { delegate.onConfigureFailed(session); return }
                StreamUseCaseRejections.reject(descriptor.cameraId)
                Log.w(TAG, "Camera ${descriptor.cameraId} rejected a session with stream use cases; retrying on DEFAULT.")
                runCatching { session.close() }
                try {
                    // The original configuration owns the caller's callback, so this is the only retry.
                    device.createCaptureSession(configuration)
                } catch (failure: Throwable) {
                    Log.w(TAG, "Session retry without stream use cases failed.", failure)
                    delegate.onConfigureFailed(session)
                }
            }
        })
    configuration.sessionParameters?.let(wrapped::setSessionParameters)
    // The caller names the color space it set: on the Razr getColorSpace() reports SRGB for a
    // configuration that never set one, and copying it makes the HAL refuse the RAW output.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) colorSpace?.let(wrapped::setColorSpace)
    device.createCaptureSession(wrapped)
}
