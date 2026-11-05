/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.DngCreator
import android.media.ImageReader
import android.media.Image
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.AccumulationRequest
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.BoundedDngOutput
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.BracketRequest
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.BurstRequest
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.CompressedStillPlan
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.JpegPayload
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.LegacyPhotoDelivery
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.PhotoRequest
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.PhotoTag
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.MAX_BURST_IMAGES
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.TAG

// Part of Camera2PreviewEngine: still capture: single photos, RAW, burst, bracket, accumulation and the night extension.
// These are extensions so the engine's state stays in one object; they run on the engine's executors like its members.

/** The physical lens that took the still of [owner], from its own result; null when unknown. */
internal fun Camera2PreviewEngine.stillPhysicalId(owner: PhotoRequest): String? {
    val result = owner.result ?: return null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let { return it }
    if (routedStillPhysicalId == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return result.physicalCameraTotalResults?.keys?.singleOrNull()
}

/** The lens correction plan for one compressed still, or null (with the reason logged). */
internal fun Camera2PreviewEngine.photoLensPlan(owner: PhotoRequest, kind: StillImageKind, width: Int, height: Int):
    com.librestatic.opencinecam.camera.calibration.PhotoLensPlan? {
    if (owner.descriptor.lensLock != null || owner.lensCropFactor != null) return null
    val (set, alignment) = photoLensCalibration ?: return null
    val physicalId = stillPhysicalId(owner)
    val resolution = com.librestatic.opencinecam.camera.calibration.PhotoLensCorrection.resolve(set, alignment, physicalId, width, height)
    resolution.note?.let { Log.w(TAG, "Lens correction for the $kind still: $it") }
    val plan = resolution.plan ?: return null
    if (kind == StillImageKind.HEIC && !owner.aspect.enabled) {
        // The container rotation is already applied by the decoder, so the sensor grid is not available.
        Log.w(TAG, "Lens correction skipped: HEIC stills are corrected only with the aspect crop on")
        return null
    }
    return plan
}

internal fun Camera2PreviewEngine.nightExtensionFor(cameraId: String): NightExtensionCapability? {
    if (Build.VERSION.SDK_INT < NIGHT_EXTENSION_MIN_SDK) return null
    // Probing may bind the OEM extension service, so each camera is asked once per engine.
    return nightExtensionCache.getOrPut(cameraId) {
        java.util.Optional.ofNullable(probeNightExtension(manager, cameraId))
    }.orElse(null)
}

/**
 * cameraExecutor: replaces the regular session with the night extension session. False means the
 * extension cannot even be attempted, so the caller builds the regular session. A later failure
 * before the first preview falls back the same way, once, with the device still open.
 */
internal fun Camera2PreviewEngine.startNightExtension(
    device: CameraDevice,
    descriptor: Camera2CameraDescriptor,
    surface: Surface,
    currentGeneration: Long,
): Boolean {
    val capability = descriptor.nightExtension ?: return false
    val sizes = capability.selectSizes(
        referenceJpeg = descriptor.jpegSize?.let { it.width to it.height },
        referencePreview = descriptor.previewSize.let { it.width to it.height },
    )
    val night = NightExtensionSession(
        manager, device, descriptor, surface, sizes, cameraExecutor, imageHandler,
        requireNotNull(listener),
        jpegOrientation = { jpegOrientation(descriptor) },
        isCurrent = { !disposed.get() && currentGeneration == generation && camera === device },
        onUnavailable = { reason ->
            cameraExecutor.execute {
                if (disposed.get() || currentGeneration != generation || camera !== device) return@execute
                nightSession = null
                Log.w(TAG, "Night extension fell back to the regular photo session: $reason")
                configureSession(device, descriptor, surface, currentGeneration)
            }
        },
    )
    nightSession = night
    night.start()
    return true
}

internal fun Camera2PreviewEngine.captureStillInternal(format: StillPhotoFormat, flash: PhotoFlashSelection, quality: Int, legacy: LegacyPhotoDelivery, bracket: BracketRequest? = null, accumulation: AccumulationRequest? = null, aspect: PhotoAspectSelection = PhotoAspectSelection(), burst: BurstRequest? = null, zeroShutterLag: Boolean = false): Boolean {
    require(quality in 1..100)
    if (disposed.get()) return false
    nightSession?.let { night ->
        // The extension takes plain JPEG stills only: no RAW, HEIC, groups or photographic flash.
        if (format != StillPhotoFormat.JPEG || bracket != null || accumulation != null || burst != null) return false
        return night.capture(quality, aspect, legacy == LegacyPhotoDelivery.JPEG)
    }
    val raw = if (StillImageKind.DNG in format.requiredKinds) rawReader ?: return false else null
    val reader = if (format == StillPhotoFormat.DNG) requireNotNull(raw) else jpegReader ?: return false
    val expectedFormat = when (format) {
        StillPhotoFormat.HEIC -> ImageFormat.HEIC
        StillPhotoFormat.DNG -> ImageFormat.RAW_SENSOR
        else -> ImageFormat.JPEG
    }
    if (reader.imageFormat != expectedFormat || raw?.imageFormat?.let { it != ImageFormat.RAW_SENSOR } == true) return false
    val request = PhotoRequest(photoIds.incrementAndGet(), generation, camera ?: return false,
        session ?: return false, reader, activeDescriptor ?: return false,
        listener ?: return false, flash, format, quality, raw, legacy, bracket, bracket?.frames?.size ?: 0, accumulation, aspect, burst, zeroShutterLag,
        lensCropFactor = lensLockCrop?.factor)
    synchronized(stillAdmissionLock) {
        if (bracketRequest.get() !== bracket || accumulationRequest.get() !== accumulation || burstRequest.get() !== burst || legacyStillRequest.get() != null || recording || recorderRequest.get() != null ||
            bracket != null && !ownsBracket(bracket) || accumulation != null && !ownsAccumulation(accumulation) ||
            burst != null && !ownsBurst(burst) || !photoRequest.compareAndSet(null, request)) return false
    }
    if (!ownsPhoto(request)) {
        photoRequest.compareAndSet(request, null)
        return false
    }
    cameraExecutor.execute {
        if (!ownsPhoto(request)) { retirePhoto(request, restore = false); return@execute }
        try {
            request.desiredOrientationDegrees = request.bracket?.aspectOrientationDegrees ?: request.burst?.orientationDegrees ?: jpegOrientation(request.descriptor)
            request.bracket?.let { if (it.aspectOrientationDegrees == null) it.aspectOrientationDegrees = request.desiredOrientationDegrees }
            request.burst?.let { if (it.orientationDegrees == null) it.orientationDegrees = request.desiredOrientationDegrees }
            val builder = request.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(request.reader.surface)
                request.raw?.takeIf { it !== request.reader }?.surface?.let(::addTarget)
                applyEngineRequest(EngineRequestMode.STILL_CAPTURE, EngineRequestParameters(
                    jpegQuality = request.quality, jpegOrientationDegrees = jpegOrientation(request.descriptor)))
                val frozen = request.bracket?.controls ?: request.accumulation?.controls ?: request.burst?.controls
                if (frozen != null) copyPhotoControls(frozen, this) else applyManualControls(this)
                if (request.format == StillPhotoFormat.DNG) restoreToneMapDefaults(this)
                // Multi-frame stills need identical frames: no boost-driven exposure or frame-rate changes.
                if (frozen == null && (request.bracket != null || request.accumulation != null || request.burst != null) &&
                    get(CaptureRequest.CONTROL_AE_MODE) == AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                if (request.accumulation != null || request.aspect.enabled && request.format != StillPhotoFormat.DNG)
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
                setTag(PhotoTag(request.id))
            }
            request.burst?.let { if (it.controls == null) it.controls = builder.build() }
            request.accumulation?.let { group ->
                if (group.controls == null) {
                    group.controls = builder.build()
                    group.outputOrientationDegrees = jpegOrientation(request.descriptor)
                    group.processor = AccumulationBitmapProcessor(group.selection, group.outputOrientationDegrees) {
                        if (!ownsAccumulation(group)) throw java.util.concurrent.CancellationException("Accumulation owner retired")
                    }
                }
            }
            if (request.bracket != null) {
                val group = request.bracket
                if (group.controls == null) {
                    if (professionalExposure?.mode?.let { it != ExposureMode.AUTO } == true ||
                        builder.get(CaptureRequest.CONTROL_AE_MODE) != CaptureRequest.CONTROL_AE_MODE_ON) {
                        failPhoto(request, "bracket-exposure-unsupported", "Exposure bracketing requires automatic exposure.")
                        return@execute
                    }
                    group.controls = builder.build()
                }
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    requireNotNull(group.plan).exposures[request.bracketIndex].compensationIndex)
            }
            // Resolve from the same effective request snapshot, after earlier queued setters.
            // Legacy ISO+shutter also produces manual AE without professionalExposure.
            val exposureMode = photoExposureMode(professionalExposure?.mode, builder.get(CaptureRequest.CONTROL_AE_MODE))
            if (exposureMode == null && request.selection.mode != PhotoFlashMode.OFF) {
                failPhoto(request, "photo-flash-rejected", "Photographic flash rejected: ${PhotoFlashRejection.EXPOSURE_UNSUPPORTED}")
                return@execute
            }
            val resolved = when (val resolution = request.selection.resolve(request.descriptor.photoFlashCapabilities, exposureMode ?: ExposureMode.AUTO)) {
                is PhotoFlashResolution.Plan -> resolution
                is PhotoFlashResolution.Rejected -> {
                    failPhoto(request, "photo-flash-rejected", "Photographic flash rejected: ${resolution.reason}")
                    return@execute
                }
            }
            val plan = photoStillPlan(request.selection, resolved,
                builder.get(CaptureRequest.FLASH_MODE),
                if (Build.VERSION.SDK_INT >= 35) builder.get(CaptureRequest.FLASH_STRENGTH_LEVEL) else null)
            applyPhotoPlan(builder, plan)
            applyZeroShutterLag(builder, request, plan, exposureMode)
            val still = builder.build()
            request.plan = plan
            request.sequence = PhotoCaptureSequence(plan.needsPrecapture)
            request.still = still
            val exposureMs = (still.get(CaptureRequest.SENSOR_EXPOSURE_TIME) ?: 0L).coerceAtLeast(0L) / 1_000_000L
            val deadline = (8_000L + exposureMs.coerceAtMost(120_000L))
            request.deadlineUptimeMs = android.os.SystemClock.uptimeMillis() + deadline
            request.timeout = Runnable { cameraExecutor.execute {
                if (ownsPhoto(request)) failPhoto(request, "photo-flash-timeout", "Photographic capture did not complete before its deadline.")
            } }.also { check(imageHandler.postDelayed(it, deadline)) { "Photo deadline owner is unavailable" } }
            if (request.bracket != null) startBracketMetering(request)
            else if (plan.needsPrecapture) startPhotoPrecapture(request, plan) else submitPhotoStill(request)
        } catch (failure: Exception) {
            failPhoto(request, "photo-flash-prepare-failed", failure.message ?: "Photographic capture preparation failed.")
        }
    }
    return true
}

internal fun Camera2PreviewEngine.ownsPhoto(request: PhotoRequest): Boolean = photoRequest.get() === request &&
    !request.cancelled.get() && (request.bracket == null || ownsBracket(request.bracket)) &&
    (request.accumulation == null || ownsAccumulation(request.accumulation)) &&
    (request.burst == null || ownsBurst(request.burst)) &&
    !disposed.get() && request.generation == generation &&
    request.device === camera && request.session === session &&
    request.reader === (if (request.format == StillPhotoFormat.DNG) rawReader else jpegReader) &&
    (request.raw == null || request.raw === rawReader)

internal fun Camera2PreviewEngine.samePhotoGraph(request: PhotoRequest): Boolean = !disposed.get() && request.generation == generation &&
    request.device === camera && request.session === session &&
    request.reader === (if (request.format == StillPhotoFormat.DNG) rawReader else jpegReader) &&
    (request.raw == null || request.raw === rawReader)

internal fun Camera2PreviewEngine.applyPhotoPlan(builder: CaptureRequest.Builder, plan: PhotoFlashResolution.Plan) {
    plan.aeMode?.let { builder.set(CaptureRequest.CONTROL_AE_MODE, it) }
    builder.set(CaptureRequest.FLASH_MODE, plan.flashMode)
    if (plan.needsPrecapture) builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
    // applyManualControls may have written a TORCH level; never carry it into SINGLE.
    if (Build.VERSION.SDK_INT >= 35 && (activeDescriptor?.torchCapabilities?.adjustable == true ||
            activeDescriptor?.photoFlashCapabilities?.adjustable == true)) {
        builder.set(CaptureRequest.FLASH_STRENGTH_LEVEL, plan.strength)
    }
    builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
}

/** Display EGL windows never become Camera2 targets in a GPU photographic graph. */
internal fun Camera2PreviewEngine.targetPreviewCameraSurface(): Surface? =
    if (gpuPreviewEnabled || logPreviewEnabled) logPipeline?.cameraInputSurface else previewSurface

internal fun Camera2PreviewEngine.startPhotoPrecapture(request: PhotoRequest, plan: PhotoFlashResolution.Plan) {
    val preview = requireNotNull(targetPreviewCameraSurface())
    val builder = request.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(preview)
        analysisReader?.surface?.let(::addTarget)
        applyEngineRequest(EngineRequestMode.PHOTO_PRECAPTURE)
        applyTargetFps(this, request.descriptor)
        applyManualControls(this)
        applyPhotoPlan(this, photoMeteringRepeatPlan(plan))
        setTag(PhotoTag(request.id))
    }
    // This private request never replaces/mutates the user's repeatingBuilder or light intent.
    request.repeatingChanged = true
    val callback = photoPrecaptureCallback(request)
    request.session.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, callback)
    // SINGLE belongs only to the one-shot metering trigger and final still, never
    // every preview frame. Trigger and still share the exact frozen AE/flash/level.
    applyPhotoPlan(builder, plan)
    builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
    builder.setTag(PhotoTag(request.id, trigger = true))
    request.session.captureSingleRequest(builder.build(), cameraExecutor, callback)
}

internal fun Camera2PreviewEngine.photoPrecaptureCallback(owner: PhotoRequest) = object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        if (!ownsPhoto(owner) || session !== owner.session) return
        val tag = request.tag as? PhotoTag ?: return
        if (tag.id != owner.id) return
        val sequence = owner.sequence ?: return
        if (tag.trigger) sequence.triggerCompleted(result.frameNumber)
        val ae = when (result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)) {
            android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED -> PhotoCaptureSequence.Ae.CONVERGED
            android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> PhotoCaptureSequence.Ae.FLASH_REQUIRED
            android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> PhotoCaptureSequence.Ae.PRECAPTURE
            else -> PhotoCaptureSequence.Ae.OTHER
        }
        if (sequence.observe(result.frameNumber, ae,
                result.get(android.hardware.camera2.CaptureResult.FLASH_STATE) == android.hardware.camera2.CaptureResult.FLASH_STATE_CHARGING)) {
            submitPhotoStill(owner)
        }
    }
    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
        if (ownsPhoto(owner) && owner.sequence?.stage in setOf(PhotoCaptureSequence.Stage.TRIGGER_PENDING, PhotoCaptureSequence.Stage.METERING))
            failPhoto(owner, "photo-flash-precapture-failed", "Flash metering failed (${failure.reason}).")
    }
    override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
        if (ownsPhoto(owner) && owner.sequence?.stage in setOf(PhotoCaptureSequence.Stage.TRIGGER_PENDING, PhotoCaptureSequence.Stage.METERING))
            failPhoto(owner, "photo-flash-precapture-aborted", "Flash metering was aborted.")
    }
}

/** Pragmatic ZSL: only the request flag, no reprocessable session; any ineligible still keeps today's request. */
internal fun Camera2PreviewEngine.applyZeroShutterLag(builder: CaptureRequest.Builder, request: PhotoRequest, plan: PhotoFlashResolution.Plan, exposureMode: ExposureMode?) {
    val eligible = zeroShutterLagEligible(ZeroShutterLagContext(
        requested = request.zeroShutterLag, supported = request.descriptor.zslSupported,
        singleStill = request.bracket == null && request.accumulation == null && request.burst == null,
        rawOutput = StillImageKind.DNG in request.format.requiredKinds,
        manualExposure = exposureMode != ExposureMode.AUTO,
        flashActive = photoFlashPlanActive(plan), needsPrecapture = plan.needsPrecapture))
    if (!eligible) return
    request.zslApplied = runCatching { builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, true) }
        .onFailure { Log.w(TAG, "CONTROL_ENABLE_ZSL rejected; keeping the standard still request.", it) }.isSuccess
}

internal fun Camera2PreviewEngine.submitPhotoStill(owner: PhotoRequest) {
    if (!ownsPhoto(owner)) return
    try {
        owner.stillSubmitted = true
        owner.repeatingChanged = true
        owner.session.captureSingleRequest(requireNotNull(owner.still), cameraExecutor,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    if (session !== owner.session || (request.tag as? PhotoTag)?.id != owner.id || owner.bracket != null || owner.accumulation != null || owner.burst != null) return
                    measureStillShutterLatency(owner.zslApplied, owner.pressedAtNs, SystemClock.elapsedRealtimeNanos())?.let {
                        latestStillShutterLatency = it
                        Log.i(TAG, "Still shutter latency: ${it.latencyMs} ms (zsl=${it.zslApplied})")
                    }
                }
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (!ownsPhoto(owner) || session !== owner.session || (request.tag as? PhotoTag)?.id != owner.id) return
                    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                    if (timestamp == null || owner.sequence?.result(timestamp) != true) {
                        failPhoto(owner, "photo-flash-result-invalid", "JPEG capture has no unique sensor timestamp.")
                        return
                    }
                    if (owner.bracket != null && result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION) !=
                            request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)) {
                        failPhoto(owner, "bracket-compensation-mismatch", "The still result does not confirm the requested exposure compensation.")
                        return
                    }
                    owner.result = result
                    reportStillRoute(result)
                    purgeUnmatchedRaw(owner, timestamp)
                    owner.report = PhotoFlashReport(owner.selection, request.get(CaptureRequest.CONTROL_AE_MODE),
                        requireNotNull(request.get(CaptureRequest.FLASH_MODE)), owner.plan?.strength,
                        result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE),
                        result.get(android.hardware.camera2.CaptureResult.FLASH_STATE),
                        if (Build.VERSION.SDK_INT >= 35) result.get(android.hardware.camera2.CaptureResult.FLASH_STRENGTH_LEVEL) else null,
                        timestamp)
                    deliverPhotoIfReady(owner)
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (ownsPhoto(owner)) failPhoto(owner, "photo-flash-capture-failed", "JPEG capture failed (${failure.reason}).")
                }
                override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                    if (ownsPhoto(owner)) failPhoto(owner, "photo-flash-capture-aborted", "JPEG capture was aborted.")
                }
            })
    } catch (failure: Exception) {
        failPhoto(owner, "photo-flash-submit-failed", failure.message ?: "JPEG submission failed.")
    }
}

internal fun Camera2PreviewEngine.deliverPhotoIfReady(owner: PhotoRequest) {
    if (!ownsPhoto(owner)) return
    val report = owner.report ?: return
    val timestamp = report.sensorTimestampNs ?: return
    compressedReadFailures.remove(timestamp)?.let {
        failPhoto(owner, "still-image-read-failed", it)
        return
    }
    val compressedKind = owner.format.requiredKinds.firstOrNull { it != StillImageKind.DNG }
    val compressed = if (compressedKind != null) pendingJpegs[timestamp]?.takeIf { it.kind == compressedKind } ?: return else null
    val raw = if (owner.raw != null) pendingRawFrames[timestamp] ?: return else null
    val parts = buildMap {
        compressed?.let { put(it.kind, timestamp) }
        raw?.let { put(StillImageKind.DNG, it.timestamp) }
    }
    if (!owner.format.matchesCompleteFrame(timestamp, parts)) return
    if (owner.accumulation != null) {
        completeAccumulationFrame(owner, requireNotNull(compressed))
        return
    }
    if (owner.burst != null && compressed != null &&
        compressed.bytes.size.toLong() > CapturedBurst.MAX_ENCODED_BYTES - owner.burst.encodedBytes) {
        failPhoto(owner, "burst-byte-limit", "The complete burst exceeds its encoded byte budget.")
        return
    }
    if (owner.bracket != null && compressed != null &&
        compressed.bytes.size.toLong() > CapturedBracket.MAX_ENCODED_BYTES - owner.bracket.encodedBytes) {
        failPhoto(owner, "bracket-byte-limit", "The complete bracket exceeds its encoded byte budget.")
        return
    }
    val images = try {
        buildList<StillImagePayload> {
            compressed?.let {
                val checkRunning = {
                    if (!ownsPhoto(owner)) throw java.util.concurrent.CancellationException("Aspect crop owner retired")
                    // The codec runs synchronously on the owner queue; its polling must also
                    // observe the existing request deadline while that queue is occupied.
                    if (it.kind == StillImageKind.HEIC && android.os.SystemClock.uptimeMillis() >= owner.deadlineUptimeMs)
                        throw java.util.concurrent.TimeoutException("HEIC processing exceeded the still request deadline")
                }
                val lens = photoLensPlan(owner, it.kind, it.width, it.height)
                // The lens lock's GPU crop (PLAN-070 slice 4): the still matches the cropped viewfinder.
                val zoomCrop = owner.lensCropFactor?.takeIf { factor -> factor > LensLockCrop.RECODE_THRESHOLD } ?: 1f
                add(if (!owner.aspect.enabled) {
                    if (zoomCrop > 1f && it.kind == StillImageKind.JPEG) {
                        try {
                            cropPhotoZoomJpeg(it.bytes, zoomCrop, owner.quality, java.io.File(appContext.cacheDir, "photo-lens"), checkRunning)
                        } catch (failure: java.util.concurrent.CancellationException) { throw failure
                        } catch (failure: java.util.concurrent.TimeoutException) { throw failure
                        } catch (failure: Exception) {
                            Log.w(TAG, "Lens lock crop failed; keeping the uncropped still", failure)
                            StillImagePayload.owned(it.kind, it.bytes, it.width, it.height)
                        }
                    } else if (zoomCrop > 1f) {
                        // The re-encoded HEIC would lose the container metadata (EXIF, orientation
                        // source), so only the aspect-crop path, which already re-encodes, crops it.
                        Log.w(TAG, "Lens lock crop skipped: HEIC stills are cropped only with the aspect crop on")
                        StillImagePayload.owned(it.kind, it.bytes, it.width, it.height)
                    } else if (lens != null) {
                        // Same pixel grid and size: re-encode with the EXIF carried over; on any failure keep the original.
                        try {
                            correctPhotoLensJpeg(it.bytes, lens, owner.quality, java.io.File(appContext.cacheDir, "photo-lens"), checkRunning)
                        } catch (failure: java.util.concurrent.CancellationException) { throw failure
                        } catch (failure: java.util.concurrent.TimeoutException) { throw failure
                        } catch (failure: Exception) {
                            Log.w(TAG, "Lens correction failed; keeping the uncorrected still", failure)
                            StillImagePayload.owned(it.kind, it.bytes, it.width, it.height)
                        }
                    } else StillImagePayload.owned(it.kind, it.bytes, it.width, it.height)
                }
                else if (it.kind == StillImageKind.HEIC) cropPhotoAspectHeic(it.bytes, owner.aspect,
                    owner.desiredOrientationDegrees, owner.quality, java.io.File(appContext.cacheDir, "photo-aspect"), lens, checkRunning, zoomCrop)
                else cropPhotoAspectJpeg(it.bytes, owner.aspect, owner.desiredOrientationDegrees, owner.quality, lens, checkRunning, zoomCrop))
            }
            raw?.let { image ->
                pendingRawFrames.remove(timestamp)
                // Ownership starts immediately after removal, before any Image getter,
                // output allocation, characteristics lookup or DNG constructor can fail.
                try {
                    val width = image.width
                    val height = image.height
                    val orientation = if (owner.aspect.enabled) owner.desiredOrientationDegrees
                        else requireNotNull(owner.still).get(CaptureRequest.JPEG_ORIENTATION) ?: 0
                    val output = BoundedDngOutput()
                    val characteristics = manager.getCameraCharacteristics(owner.descriptor.cameraId)
                    val creator = DngCreator(characteristics, requireNotNull(owner.result))
                    try {
                        creator.setOrientation(when (orientation) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 })
                        creator.writeImage(output, image)
                    } finally { creator.close() }
                    val dng = output.toByteArray()
                    // Camera2 never crops RAW_SENSOR, so record the digital zoom as DefaultCrop.
                    runCatching { rawZoomCrop(owner, characteristics)?.let { DngDefaultCrop.apply(dng, it) } }
                    add(StillImagePayload.owned(StillImageKind.DNG, dng, width, height,
                        owner.aspect.takeIf { it.enabled }?.rawReport(width, height, orientation)))
                } finally { image.close() }
            }
        }
    } catch (failure: Exception) {
        failPhoto(owner, "still-encode-failed", failure.message ?: "The complete still capture could not be encoded.")
        return
    }
    if (owner.bracket != null && images.sumOf { it.byteCount.toLong() } > CapturedBracket.MAX_ENCODED_BYTES - owner.bracket.encodedBytes) {
        failPhoto(owner, "bracket-byte-limit", "The processed bracket exceeds its encoded byte budget.")
        return
    }
    if (owner.burst != null && images.sumOf { it.byteCount.toLong() } > CapturedBurst.MAX_ENCODED_BYTES - owner.burst.encodedBytes) {
        failPhoto(owner, "burst-byte-limit", "The processed burst exceeds its encoded byte budget.")
        return
    }
    val capture = CapturedStill(owner.id, timestamp,
        if (owner.aspect.enabled) { if (owner.raw != null) owner.desiredOrientationDegrees else 0 }
        else requireNotNull(owner.still).get(CaptureRequest.JPEG_ORIENTATION) ?: 0,
        owner.quality, report, images, owner.aspect)
    if (owner.sequence?.complete(timestamp) != true) return
    // Linearize delivery against public cancellation before releasing the reservation.
    if (!owner.cancelled.compareAndSet(false, true)) { retirePhoto(owner, restore = true); return }
    if (!retirePhoto(owner, restore = owner.bracket == null && owner.burst == null) || !samePhotoGraph(owner)) return
    if (owner.burst != null) {
        completeBurstFrame(owner, capture)
        return
    }
    if (owner.bracket != null) {
        completeBracketFrame(owner, capture, capture.images.single().byteCount)
        return
    }
    when (owner.legacy) {
        LegacyPhotoDelivery.NONE -> owner.listener.onStillCaptured(capture)
        LegacyPhotoDelivery.JPEG -> {
            owner.listener.onPhotoFlashResult(report)
            val image = capture.images.single()
            owner.listener.onJpegCaptured(image.bytes, image.width, image.height)
        }
        LegacyPhotoDelivery.DNG -> {
            val image = capture.images.single()
            owner.listener.onDngCaptured(image.bytes, image.width, image.height)
        }
    }
}

internal fun Camera2PreviewEngine.retirePhoto(owner: PhotoRequest, restore: Boolean): Boolean {
    if (photoRequest.get() !== owner) return false
    owner.timeout?.let(imageHandler::removeCallbacks)
    owner.timeout = null
    val completed = owner.sequence?.stage == PhotoCaptureSequence.Stage.COMPLETE
    owner.sequence?.cancel()
    owner.report?.sensorTimestampNs?.let { removeCompressed(it); compressedReadFailures.remove(it) }
    clearPendingRaw()
    var restorationFailure: Exception? = null
    fun attempt(action: () -> Unit) {
        try { action() } catch (failure: Exception) {
            if (restorationFailure == null) restorationFailure = failure else restorationFailure?.addSuppressed(failure)
        }
    }
    if (restore && samePhotoGraph(owner) && owner.repeatingChanged) {
        if (!completed && owner.stillSubmitted) attempt { owner.session.abortCaptures() }
        if (!completed && owner.plan?.needsPrecapture == true) attempt {
            val cancel = owner.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(requireNotNull(targetPreviewCameraSurface()))
                analysisReader?.surface?.let(::addTarget)
                applyManualControls(this)
                applyEngineRequest(EngineRequestMode.PRECAPTURE_CANCEL)
            }.build()
            owner.session.captureSingleRequest(cancel, cameraExecutor, object : CameraCaptureSession.CaptureCallback() {})
        }
        // Even failed abort/CANCEL submission must not skip ordinary preview restoration.
        attempt {
            val builder = requireNotNull(repeatingBuilder)
            applyManualControls(builder)
            owner.session.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(owner.descriptor, false))
        }
    }
    photoRequest.compareAndSet(owner, null)
    restorationFailure?.let {
        owner.listener.onFailure("photo-flash-restore-failed", it.message ?: "Preview light restoration failed.", true)
    }
    return restorationFailure == null
}

internal fun Camera2PreviewEngine.failPhoto(owner: PhotoRequest, code: String, message: String) {
    val notify = ownsPhoto(owner)
    val restored = retirePhoto(owner, restore = true)
    owner.bracket?.let { retireBracket(it, restore = true) }
    owner.accumulation?.let { retireAccumulation(it, restore = true) }
    owner.burst?.let { retireBurst(it, restore = true) }
    if (notify && restored) owner.listener.onFailure(when {
        owner.burst != null && !code.startsWith("burst-") -> "burst-$code"
        owner.accumulation != null && !code.startsWith("accumulation-") -> "accumulation-$code"
        owner.bracket != null && !code.startsWith("bracket-") -> "bracket-$code"
        else -> code
    }, message, true)
}

internal fun Camera2PreviewEngine.removeCompressed(timestamp: Long): JpegPayload? = pendingJpegs.remove(timestamp)?.also {
    compressedHandoff.release(it.ticket)
}

internal fun Camera2PreviewEngine.receiveJpeg(reader: ImageReader, readerGeneration: Long, timestamp: Long, payload: JpegPayload) {
    if (disposed.get() || reader !== jpegReader || readerGeneration != generation) {
        compressedHandoff.release(payload.ticket)
        return
    }
    if (payload.kind == StillImageKind.JPEG && legacyJpegTimestamps.remove(timestamp)) {
        try { listener?.onJpegCaptured(payload.bytes, payload.width, payload.height) }
        finally { compressedHandoff.release(payload.ticket) }
        return
    }
    pendingJpegs.put(timestamp, payload)?.let { compressedHandoff.release(it.ticket) }
    while (pendingJpegs.size > MAX_BURST_IMAGES + 2) removeCompressed(pendingJpegs.keys.first())
    photoRequest.get()?.let(::deliverPhotoIfReady)
}

internal fun Camera2PreviewEngine.receiveCompressedFailure(reader: ImageReader, readerGeneration: Long, timestamp: Long, message: String) {
    if (disposed.get() || reader !== jpegReader || readerGeneration != generation) return
    if (legacyJpegTimestamps.remove(timestamp)) {
        listener?.onFailure("still-image-read-failed", message, true)
        return
    }
    compressedReadFailures[timestamp] = message
    while (compressedReadFailures.size > MAX_BURST_IMAGES + 2) compressedReadFailures.remove(compressedReadFailures.keys.first())
    photoRequest.get()?.let(::deliverPhotoIfReady)
}

internal fun Camera2PreviewEngine.legacyJpegStarted(reader: ImageReader, readerGeneration: Long, timestamp: Long) {
    if (disposed.get() || reader !== jpegReader || readerGeneration != generation || reader.imageFormat != ImageFormat.JPEG) return
    compressedReadFailures.remove(timestamp)?.let {
        listener?.onFailure("still-image-read-failed", it, true)
        return
    }
    val payload = pendingJpegs.remove(timestamp)
    if (payload != null) {
        try { listener?.onJpegCaptured(payload.bytes, payload.width, payload.height) }
        finally { compressedHandoff.release(payload.ticket) }
    }
    else {
        legacyJpegTimestamps += timestamp
        while (legacyJpegTimestamps.size > MAX_BURST_IMAGES + 2) legacyJpegTimestamps.remove(legacyJpegTimestamps.first())
    }
}

internal fun Camera2PreviewEngine.sameBracketGraph(owner: BracketRequest): Boolean = !disposed.get() && owner.generation == generation &&
    owner.device === camera && owner.session === session && owner.reader === jpegReader

internal fun Camera2PreviewEngine.ownsBracket(owner: BracketRequest): Boolean = bracketRequest.get() === owner &&
    !owner.cancelled.get() && sameBracketGraph(owner)

internal fun Camera2PreviewEngine.startBracketFrame(owner: BracketRequest) {
    if (!ownsBracket(owner)) { retireBracket(owner, restore = true); return }
    if (!captureStillInternal(StillPhotoFormat.JPEG, PhotoFlashSelection(), owner.quality, LegacyPhotoDelivery.NONE, owner, aspect = owner.aspect))
        failBracket(owner, "bracket-frame-unavailable", "The next exposure could not acquire its native still request.")
}

@Suppress("UNCHECKED_CAST")
internal fun Camera2PreviewEngine.copyPhotoControls(source: CaptureRequest, target: CaptureRequest.Builder) {
    source.keys.forEach { key ->
        val typed = key as CaptureRequest.Key<Any>
        target.set(typed, source.get(typed))
    }
}

internal fun Camera2PreviewEngine.startBracketMetering(owner: PhotoRequest) {
    val group = requireNotNull(owner.bracket)
    val compensation = requireNotNull(group.plan).exposures[owner.bracketIndex].compensationIndex
    val gate = BracketMeteringGate(compensation)
    val request = owner.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        copyPhotoControls(requireNotNull(owner.still), this)
        addTarget(requireNotNull(targetPreviewCameraSurface()))
        analysisReader?.surface?.let(::addTarget)
        applyEngineRequest(EngineRequestMode.BRACKET_METERING)
        setTag(PhotoTag(owner.id))
    }.build()
    group.repeatingChanged = true
    owner.repeatingChanged = true
    owner.session.setPhotoAwareRepeatingRequest(request, cameraExecutor, object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (!ownsPhoto(owner) || session !== owner.session || (request.tag as? PhotoTag)?.id != owner.id) return
            if (gate.observe(result.frameNumber,
                    result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
                    result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE))) submitPhotoStill(owner)
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            if (ownsPhoto(owner) && !owner.stillSubmitted)
                failPhoto(owner, "bracket-metering-failed", "Exposure bracket metering failed (${failure.reason}).")
        }
        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            if (ownsPhoto(owner) && !owner.stillSubmitted)
                failPhoto(owner, "bracket-metering-aborted", "Exposure bracket metering was aborted.")
        }
    })
}

internal fun Camera2PreviewEngine.completeBracketFrame(owner: PhotoRequest, capture: CapturedStill, encodedBytes: Int) {
    val group = requireNotNull(owner.bracket)
    if (!ownsBracket(group)) { retireBracket(group, restore = true); return }
    try {
        val exposure = requireNotNull(group.plan).exposures[owner.bracketIndex]
        val result = requireNotNull(owner.result)
        check(group.frames.lastOrNull()?.capture?.sensorTimestampNs?.let { it < capture.sensorTimestampNs } != false)
        group.frames += BracketFrame(owner.bracketIndex, exposure.ev, exposure.compensationIndex,
            result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
            result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
            result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY), capture)
        group.encodedBytes += encodedBytes
        group.listener.onBracketProgress(group.frames.size, group.selection.count)
        if (!ownsBracket(group)) { retireBracket(group, restore = true); return }
        if (group.frames.size < group.selection.count) { startBracketFrame(group); return }
        val completed = CapturedBracket(group.id, group.selection, group.frames)
        if (!group.cancelled.compareAndSet(false, true)) { retireBracket(group, restore = true); return }
        if (retireBracket(group, restore = true) && sameBracketGraph(group)) group.listener.onBracketCaptured(completed)
    } catch (failure: Exception) {
        failBracket(group, "bracket-result-invalid", failure.message ?: "The complete exposure bracket is invalid.")
    }
}

internal fun Camera2PreviewEngine.retireBracket(owner: BracketRequest, restore: Boolean): Boolean {
    if (bracketRequest.get() !== owner) return false
    owner.cancelled.set(true)
    owner.timeout?.let(imageHandler::removeCallbacks)
    owner.timeout = null
    val active = photoRequest.get()?.takeIf { it.bracket === owner }
    val photoRestored = active?.let { retirePhoto(it, restore) } ?: true
    var failure: Exception? = null
    if (restore && photoRestored && owner.repeatingChanged && sameBracketGraph(owner)) {
        try {
            val builder = requireNotNull(repeatingBuilder)
            applyManualControls(builder)
            owner.session.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(owner.descriptor, false))
        } catch (problem: Exception) { failure = problem }
    }
    owner.frames.clear()
    owner.controls = null
    owner.encodedBytes = 0L
    bracketRequest.compareAndSet(owner, null)
    failure?.let { owner.listener.onFailure("bracket-restore-failed", it.message ?: "Preview restoration failed.", true) }
    return photoRestored && failure == null
}

internal fun Camera2PreviewEngine.failBracket(owner: BracketRequest, code: String, message: String) {
    val notify = ownsBracket(owner)
    if (retireBracket(owner, restore = true) && notify) owner.listener.onFailure(code, message, true)
}

internal fun Camera2PreviewEngine.sameAccumulationGraph(owner: AccumulationRequest): Boolean = !disposed.get() && owner.generation == generation &&
    owner.device === camera && owner.session === session && owner.reader === jpegReader

internal fun Camera2PreviewEngine.ownsAccumulation(owner: AccumulationRequest): Boolean = accumulationRequest.get() === owner &&
    !owner.cancelled.get() && sameAccumulationGraph(owner)

internal fun Camera2PreviewEngine.accumulationElapsed(owner: AccumulationRequest): Long =
    (android.os.SystemClock.elapsedRealtime() - owner.startedAtMs).coerceAtLeast(0L)

internal fun Camera2PreviewEngine.startAccumulationFrame(owner: AccumulationRequest) {
    if (!ownsAccumulation(owner)) { retireAccumulation(owner, restore = true); return }
    owner.tick?.let(imageHandler::removeCallbacks)
    owner.tick = null
    if (owner.finishRequested.get() || accumulationElapsed(owner) >= owner.selection.durationMs ||
        owner.frames.size == CapturedAccumulation.MAX_FRAMES) {
        finishAccumulationGroup(owner)
        return
    }
    owner.lastFrameStartedAtMs = android.os.SystemClock.elapsedRealtime()
    if (!captureStillInternal(StillPhotoFormat.JPEG, PhotoFlashSelection(), owner.quality, LegacyPhotoDelivery.NONE,
            accumulation = owner)) failAccumulation(owner, "accumulation-frame-unavailable", "The next accumulation frame is unavailable.")
}

internal fun Camera2PreviewEngine.completeAccumulationFrame(owner: PhotoRequest, payload: JpegPayload) {
    val group = requireNotNull(owner.accumulation)
    if (!ownsPhoto(owner)) return
    try {
        require(payload.kind == StillImageKind.JPEG && payload.bytes.size <= CapturedAccumulation.MAX_ENCODED_BYTES)
        val result = requireNotNull(owner.result)
        val timestamp = requireNotNull(owner.report?.sensorTimestampNs)
        check(group.frames.lastOrNull()?.sensorTimestampNs?.let { it < timestamp } != false)
        group.repeatingChanged = true
        requireNotNull(group.processor).add(payload.bytes)
        if (!ownsPhoto(owner)) { retireAccumulation(group, restore = true); return }
        val frame = AccumulationFrame(group.frames.size, owner.id, timestamp,
            result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
            result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY))
        if (owner.sequence?.complete(timestamp) != true || !owner.cancelled.compareAndSet(false, true)) {
            retireAccumulation(group, restore = true)
            return
        }
        if (!retirePhoto(owner, restore = false)) { retireAccumulation(group, restore = true); return }
        group.frames += frame
        group.completedFrames = group.frames.size
        val elapsed = accumulationElapsed(group)
        group.listener.onAccumulationProgress(group.completedFrames, elapsed, group.selection.durationMs)
        if (!ownsAccumulation(group)) { retireAccumulation(group, restore = true); return }
        if (group.finishRequested.get() || elapsed >= group.selection.durationMs || group.frames.size == CapturedAccumulation.MAX_FRAMES) {
            finishAccumulationGroup(group)
            return
        }
        val untilNext = (group.lastFrameStartedAtMs + group.selection.intervalMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val untilTarget = (group.selection.durationMs - accumulationElapsed(group)).coerceAtLeast(0L)
        group.tick = Runnable { cameraExecutor.execute { startAccumulationFrame(group) } }
        check(imageHandler.postDelayed(requireNotNull(group.tick), minOf(untilNext, untilTarget))) { "Accumulation scheduler unavailable" }
    } catch (failure: Exception) {
        failAccumulation(group, "accumulation-image-processing-failed", failure.message ?: "Accumulation frame processing failed.")
    }
}

internal fun Camera2PreviewEngine.finishAccumulationGroup(owner: AccumulationRequest) {
    if (!ownsAccumulation(owner) || owner.finishing) return
    if (owner.frames.size < 2) {
        failAccumulation(owner, "accumulation-insufficient-frames", "Accumulation requires at least two completed frames.")
        return
    }
    owner.finishing = true
    owner.tick?.let(imageHandler::removeCallbacks)
    owner.tick = null
    try {
        val image = requireNotNull(owner.processor).finish(owner.quality, owner.aspect)
        val capture = CapturedAccumulation(owner.id, owner.selection, owner.frames, image, 0, owner.quality, owner.finishRequested.get())
        if (!owner.cancelled.compareAndSet(false, true)) { retireAccumulation(owner, restore = true); return }
        if (retireAccumulation(owner, restore = true) && sameAccumulationGraph(owner)) owner.listener.onAccumulationCaptured(capture)
    } catch (failure: Exception) {
        failAccumulation(owner, "accumulation-result-invalid", failure.message ?: "Accumulation output could not be encoded.")
    }
}

internal fun Camera2PreviewEngine.retireAccumulation(owner: AccumulationRequest, restore: Boolean): Boolean {
    if (accumulationRequest.get() !== owner) return false
    owner.cancelled.set(true)
    owner.timeout?.let(imageHandler::removeCallbacks)
    owner.tick?.let(imageHandler::removeCallbacks)
    owner.timeout = null
    owner.tick = null
    val active = photoRequest.get()?.takeIf { it.accumulation === owner }
    val photoRestored = active?.let { retirePhoto(it, restore) } ?: true
    var failure: Exception? = null
    if (restore && photoRestored && owner.repeatingChanged && sameAccumulationGraph(owner)) {
        try {
            val builder = requireNotNull(repeatingBuilder)
            applyManualControls(builder)
            owner.session.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(owner.descriptor, false))
        } catch (problem: Exception) { failure = problem }
    }
    owner.processor?.close()
    owner.processor = null
    owner.controls = null
    owner.frames.clear()
    owner.completedFrames = 0
    accumulationRequest.compareAndSet(owner, null)
    failure?.let { owner.listener.onFailure("accumulation-restore-failed", it.message ?: "Preview restoration failed.", true) }
    return photoRestored && failure == null
}

internal fun Camera2PreviewEngine.failAccumulation(owner: AccumulationRequest, code: String, message: String) {
    val notify = ownsAccumulation(owner)
    if (retireAccumulation(owner, restore = true) && notify) owner.listener.onFailure(code, message, true)
}

internal fun Camera2PreviewEngine.reserveLegacyStill(): Any? = synchronized(stillAdmissionLock) {
    if (disposed.get() || bracketRequest.get() != null || accumulationRequest.get() != null || burstRequest.get() != null || photoRequest.get() != null ||
        recording || recorderRequest.get() != null) null
    else Any().takeIf { legacyStillRequest.compareAndSet(null, it) }
}

internal fun Camera2PreviewEngine.sameBurstGraph(owner: BurstRequest): Boolean = !disposed.get() && owner.generation == generation &&
    owner.device === camera && owner.session === session && owner.reader === jpegReader

internal fun Camera2PreviewEngine.ownsBurst(owner: BurstRequest): Boolean = burstRequest.get() === owner &&
    !owner.cancelled.get() && sameBurstGraph(owner)

internal fun Camera2PreviewEngine.startBurstFrame(owner: BurstRequest) {
    if (!ownsBurst(owner)) { retireBurst(owner, restore = true); return }
    if (!captureStillInternal(StillPhotoFormat.JPEG, PhotoFlashSelection(), owner.quality, LegacyPhotoDelivery.NONE,
            aspect = owner.aspect, burst = owner)) failBurst(owner, "burst-frame-unavailable", "The next burst frame could not acquire a still request.")
}

internal fun Camera2PreviewEngine.completeBurstFrame(owner: PhotoRequest, capture: CapturedStill) {
    val group = requireNotNull(owner.burst)
    if (!ownsBurst(group)) { retireBurst(group, restore = true); return }
    try {
        val result = requireNotNull(owner.result)
        check(group.frames.lastOrNull()?.capture?.sensorTimestampNs?.let { it < capture.sensorTimestampNs } != false)
        group.frames += BurstFrame(group.frames.size, capture,
            result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
            result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY))
        group.encodedBytes += capture.images.single().byteCount
        group.repeatingChanged = true
        group.listener.onBurstProgress(group.frames.size, group.count)
        if (!ownsBurst(group)) { retireBurst(group, restore = true); return }
        if (group.frames.size < group.count) { startBurstFrame(group); return }
        val completed = CapturedBurst(group.id, group.count, group.frames, group.quality, group.aspect)
        if (!group.cancelled.compareAndSet(false, true)) { retireBurst(group, restore = true); return }
        if (retireBurst(group, restore = true) && sameBurstGraph(group)) group.listener.onBurstCaptured(completed)
    } catch (failure: Exception) {
        failBurst(group, "burst-result-invalid", failure.message ?: "The complete burst is invalid.")
    }
}

internal fun Camera2PreviewEngine.retireBurst(owner: BurstRequest, restore: Boolean): Boolean {
    if (burstRequest.get() !== owner) return false
    owner.cancelled.set(true)
    owner.timeout?.let(imageHandler::removeCallbacks)
    owner.timeout = null
    val active = photoRequest.get()?.takeIf { it.burst === owner }
    val photoRestored = active?.let { retirePhoto(it, restore) } ?: true
    var failure: Exception? = null
    if (restore && photoRestored && owner.repeatingChanged && sameBurstGraph(owner)) {
        try {
            val builder = requireNotNull(repeatingBuilder)
            applyManualControls(builder)
            owner.session.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(owner.descriptor, false))
        } catch (problem: Exception) { failure = problem }
    }
    owner.frames.clear()
    owner.controls = null
    owner.encodedBytes = 0L
    burstRequest.compareAndSet(owner, null)
    failure?.let { owner.listener.onFailure("burst-restore-failed", it.message ?: "Preview restoration failed.", true) }
    return photoRestored && failure == null
}

internal fun Camera2PreviewEngine.failBurst(owner: BurstRequest, code: String, message: String) {
    val notify = ownsBurst(owner)
    if (retireBurst(owner, restore = true) && notify) owner.listener.onFailure(code, message, true)
}

/** The framing the user saw through digital zoom, in RAW frame fractions; null when it is the full frame. */
internal fun Camera2PreviewEngine.rawZoomCrop(owner: PhotoRequest, characteristics: CameraCharacteristics): NormRect? {
    val result = owner.result ?: return null
    val active = owner.descriptor.sensorActiveArray ?: return null
    val zoomRatio = if (owner.descriptor.supportsZoomRatioApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)
    } else null
    val region = result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)
        ?.let { CropRect(it.left, it.top, it.right, it.bottom) }
    // The GPU-crop route narrows what the operator saw about the processed frame's centre.
    val fov = RawZoomCrop.digitalCrop(RawZoomCrop.processedFieldOfView(active.width(), active.height(), region, zoomRatio),
        owner.lensCropFactor?.toDouble() ?: 1.0)
    var scale = 1.0
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val physicalId = result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        if (physicalId != null) {
            val physical = runCatching { manager.getCameraCharacteristics(physicalId) }.getOrNull()
            if (physical != null) scale = RawZoomCrop.rawLensScale(fovWidth(characteristics), fovWidth(physical))
        }
    }
    return RawZoomCrop.rawCrop(fov, scale)
}

/**
 * Compressed still sizes the logical camera's public physical members advertise beyond its own
 * map, keyed by physical id (OCC-PLAN-069). A physical that cannot be read costs only its own
 * extras; the logical map stays the source of truth for everything it already covers.
 */
internal fun Camera2PreviewEngine.physicalExtraStillSizes(
    physicalIds: Set<String>,
    format: Int,
    logicalMap: android.hardware.camera2.params.StreamConfigurationMap,
): Map<String, List<Size>> {
    val logicalSizes = logicalMap.getOutputSizes(format)?.toList().orEmpty()
    return physicalIds
        .filter { it.isNotBlank() }
        .mapNotNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(format)?.toList().orEmpty()
                    .filterNot { it in logicalSizes }
            }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { extras -> id to extras.sortedByDescending { s -> s.width.toLong() * s.height } }
        }.toMap()
}

internal fun Camera2PreviewEngine.jpegOrientation(descriptor: Camera2CameraDescriptor): Int =
    if (descriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
        (descriptor.sensorOrientation + displayRotationDegrees) % 360
    } else {
        (descriptor.sensorOrientation - displayRotationDegrees + 360) % 360
    }

/**
 * Active compressed still size plus the public physical camera that publishes it when the
 * logical map does not (OCC-PLAN-069). A graph that already fell back to logical sizes never
 * routes physically again, so a rejected combination degrades instead of failing the preview.
 */
internal fun Camera2PreviewEngine.compressedStillPlan(descriptor: Camera2CameraDescriptor): CompressedStillPlan? {
    val heic = activeStillFormat == StillPhotoFormat.HEIC
    val logicalSizes = if (heic) descriptor.heicSizes else descriptor.jpegSizes
    val physicalSizes = if (heic) descriptor.physicalHeicSizes else descriptor.physicalJpegSizes
    val chosen = activeStillSize?.takeIf { it in logicalSizes || !physicalStillFallbackTaken && physicalSizes.values.any { s -> it in s } }
        ?: logicalSizes.firstOrNull()
    val size = chosen ?: return null
    val routed = physicalStillRouting(
        size.width to size.height,
        logicalSizes.map { it.width to it.height },
        physicalSizes.mapValues { entry -> entry.value.map { it.width to it.height } },
    )
    // A lens-locked session already runs on one physical camera: no physical still routing (OCC-PLAN-069).
    // The GPU-crop route pins one lens through the logical streams, so it has none either.
    return CompressedStillPlan(size, routed?.takeIf { !physicalStillFallbackTaken && descriptor.lensLock == null && lensLockCrop == null })
}

internal fun Camera2PreviewEngine.compressedStillSize(descriptor: Camera2CameraDescriptor): Size? = compressedStillPlan(descriptor)?.size

internal fun Camera2PreviewEngine.scheduleRawDrain(owner: PhotoRequest) {
    val reader = owner.raw ?: return
    imageHandler.post {
        if (photoRequest.get() !== owner || owner.cancelled.get() || disposed.get() ||
            owner.generation != generation || reader !== rawReader) return@post
        repeat(2) {
            val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return@post
            queueRawImage(reader, owner.generation, image)
        }
    }
}

internal fun Camera2PreviewEngine.purgeUnmatchedRaw(owner: PhotoRequest, timestamp: Long) {
    if (owner.raw == null || !ownsPhoto(owner)) return
    pendingRawFrames.keys.filter { it != timestamp }.forEach { pendingRawFrames.remove(it)?.close() }
    val staleQueued = synchronized(rawImageLock) {
        queuedRawImages.filter { it.timestamp != timestamp }.also { queuedRawImages.removeAll(it.toSet()) }
    }
    staleQueued.forEach { it.close() }
    // acquireNextImage may previously have hit its two-owned-image limit. Explicitly
    // drain after freeing slots instead of waiting for an unrelated future frame.
    scheduleRawDrain(owner)
}

/** Register before posting: close also owns frames whose executor callback is discarded. */
internal fun Camera2PreviewEngine.queueRawImage(reader: ImageReader, readerGeneration: Long, image: Image) {
    val queued = synchronized(rawImageLock) {
        if (disposed.get() || readerGeneration != generation || reader !== rawReader) false
        else queuedRawImages.add(image)
    }
    if (!queued) { image.close(); return }
    cameraExecutor.execute {
        if (!synchronized(rawImageLock) { queuedRawImages.remove(image) }) return@execute
        val owner = photoRequest.get()
        if (owner == null || !ownsPhoto(owner) || owner.raw !== reader || readerGeneration != generation) {
            image.close()
            return@execute
        }
        val timestamp = image.timestamp
        val expected = owner.result?.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
        if (expected != null && timestamp != expected) {
            image.close()
            scheduleRawDrain(owner)
            return@execute
        }
        pendingRawFrames.put(timestamp, image)?.close()
        while (pendingRawFrames.size > 2) pendingRawFrames.remove(pendingRawFrames.keys.first())?.close()
        deliverPhotoIfReady(owner)
    }
}

internal fun Camera2PreviewEngine.clearPendingRaw() {
    val queued = synchronized(rawImageLock) { queuedRawImages.toList().also { queuedRawImages.clear() } }
    (pendingRawFrames.values.toList() + queued).forEach { runCatching { it.close() } }
    pendingRawFrames.clear()
}
