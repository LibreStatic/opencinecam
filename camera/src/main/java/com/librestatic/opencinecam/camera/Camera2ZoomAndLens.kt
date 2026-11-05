/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.LensLockCropRoute
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.LensLockPlan
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.TAG
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.ZOOM_REPORT_PERIOD_MS

// Part of Camera2PreviewEngine: zoom, lens lock, lens-switch tracing and instant AEC at a switch.
// These are extensions so the engine's state stays in one object; they run on the engine's executors like its members.

/** Feeds the calibration joiner with the frame's capture metadata. */
internal fun Camera2PreviewEngine.noteCalibrationMeta(result: android.hardware.camera2.CaptureResult) {
    val joiner = calibrationJoiner ?: return
    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
    val gains = result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS)
        ?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) }
    joiner.offerMeta(
        com.librestatic.opencinecam.camera.calibration.CalibrationFrameMeta(
            timestampNs = timestamp,
            physicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            } else null,
            aeState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE),
            awbState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE),
            zoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)
            } else null,
            exposureNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY),
            awbGains = gains,
        ),
    )
}

/** Feeds [LensSwitchTrace] when `log.tag.LensSwitch` is DEBUG; a new session starts a new trace. */
internal fun Camera2PreviewEngine.traceLensSwitch(armedSession: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
    if (lensSwitchTraceSession !== armedSession) {
        lensSwitchTraceSession = armedSession
        lensSwitchTrace = if (LensSwitchDiagnostics.enabled()) LensSwitchTrace() else null
    }
    val trace = lensSwitchTrace ?: return
    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
    trace.onResult(
        frameNumber = result.frameNumber,
        timestampNs = timestamp,
        physicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        } else null,
        zoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)
        } else null,
        exposureNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
        requestedZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            request.get(CaptureRequest.CONTROL_ZOOM_RATIO)
        } else null,
        targetZoomRatio = requestedZoomRatio,
    )
}

/**
 * Hands the GPU pipeline the [ZoomLagCompensator] crop for this frame, which smooths the zoom
 * the HAL holds back around a lens switch. Opt-in through [setSmoothLensSwitch]; off below
 * API 30, while lens-locked and in high-speed sessions.
 */
internal fun Camera2PreviewEngine.noteZoomLag(armedSession: CameraCaptureSession, result: TotalCaptureResult) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val pipeline = logPipeline ?: return
    if (zoomLagSession !== armedSession) {
        zoomLagSession = armedSession
        zoomLag.reset()
        pipeline.clearZoomLag()
    }
    if (!smoothLensSwitch || lensLockEngaged() || isHighSpeedSession()) {
        if (zoomLag.moving) pipeline.clearZoomLag()
        zoomLag.reset()
        return
    }
    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
    // The latest ratio sent, not the one echoed in this frame's request: with the zoom settings
    // override the HAL applies a ratio about two frames before its own request completes, so
    // the echo trails the result and would hide the hold.
    val crop = zoomLag.onResult(
        requestedZoomRatio,
        result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO),
    )
    pipeline.noteZoomCropForTimestamp(timestamp, crop, zoomLag.moving)
}

/** Forwards the frame's active physical lens to the GPU pipeline's timeline. */
internal fun Camera2PreviewEngine.noteLensForFrame(result: android.hardware.camera2.CaptureResult) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        (lensCorrectionTable == null && lensCalibration == null && calibrationReadback == null)) return
    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
    val physicalId = result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: return
    logPipeline?.notePhysicalForTimestamp(timestamp, physicalId)
}

/** True while the session serves a lens lock, directly or by the GPU crop. */
internal fun Camera2PreviewEngine.lensLockEngaged(): Boolean = activeDescriptor?.lensLock != null || lensLockCrop != null

/**
 * Debug builds only: `adb shell setprop debug.opencinecam.lenslock.forcecrop 1` skips the direct
 * physical open so the GPU-crop route can be exercised on a device that opens physicals fine (G2).
 */
internal fun Camera2PreviewEngine.lensLockCropForced(): Boolean = debugProperty("debug.opencinecam.lenslock.forcecrop").let { it == "1" || it == "true" }

/**
 * Debug builds only: `adb shell setprop debug.opencinecam.instantaec.lens 0` stops Instant AEC
 * from engaging at a lens switch, for an A/B of the switch hitch.
 */
internal fun Camera2PreviewEngine.instantAecOnLensSwitchDisabled(): Boolean =
    debugProperty("debug.opencinecam.instantaec.lens").let { it == "0" || it == "false" }

/** The GPU-crop route for [binding] on [logical], or null when the lens is not a lock candidate. */
internal fun Camera2PreviewEngine.lensLockCropRoute(binding: LensLockBinding, logical: Camera2CameraDescriptor): LensLockCropRoute? {
    val candidate = lensLockCandidates(binding.logicalId).firstOrNull { it.physicalId == binding.physicalId } ?: return null
    return LensLockCropRoute(binding, candidate.physicalZoomRange,
        LensLockCrop.pinnedRatio(binding.anchorRatio, logical.effectiveZoomRange))
}

internal fun Camera2PreviewEngine.zoomRangeOf(c: CameraCharacteristics): ClosedFloatingPointRange<Float>? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.takeIf { it.lower < it.upper }?.let { return it.lower..it.upper }
    }
    return c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.takeIf { it > 1f }?.let { 1f..it }
}

/** The physical camera's own descriptor for [binding], built for the app's target size; null when it cannot be. */
internal fun Camera2PreviewEngine.physicalDescriptor(binding: LensLockBinding, logical: Camera2CameraDescriptor): Camera2CameraDescriptor? {
    // ADR-0036: only an id the logical camera itself reports is ever opened.
    if (lensLockCandidates(binding.logicalId).none { it.physicalId == binding.physicalId }) return null
    val (width, height) = descriptorTarget ?: (logical.previewSize.width to logical.previewSize.height)
    return physicalDescriptorCache.getOrPut(Triple(binding.physicalId, width, height)) {
        java.util.Optional.ofNullable(descriptor(binding.physicalId, width, height))
    }.orElse(null)?.copy(lensLock = binding)
}

/**
 * Resolves a lens lock for one start: the physical descriptor and the requested profiles and
 * still size mapped onto it, or null. High speed and night photo refuse (reported as
 * unavailable); a physical that cannot serve the session (descriptor, HEIC, LOG), a lens that
 * already failed to open or configure, or the debug override hands [onCrop] the GPU-crop route.
 */
internal fun Camera2PreviewEngine.lensLockPlan(
    binding: LensLockBinding,
    logical: Camera2CameraDescriptor,
    listener: Camera2PreviewListener,
    openCineLog: Boolean,
    nightExtension: Boolean,
    gpuPhotoPreview: Boolean,
    videoProfile: Camera2VideoProfile?,
    logProfile: Camera2LogProfile?,
    stillFormat: StillPhotoFormat,
    stillSize: Size?,
    targetFps: Int,
    onCrop: (LensLockCropRoute) -> Unit,
): LensLockPlan? {
    fun refuse(reason: String): LensLockPlan? {
        Log.i(TAG, "Lens lock on ${binding.physicalId} not applied ($reason); opening camera ${logical.cameraId}")
        listener.onLensLockUnavailable(binding.physicalId, reason)
        return null
    }
    fun crop(reason: String): LensLockPlan? {
        val route = lensLockCropRoute(binding, logical) ?: return refuse(reason)
        Log.i(TAG, "Lens lock on ${binding.physicalId} served by the GPU crop ($reason); camera ${logical.cameraId} pinned at ${route.pinnedRatio}x")
        onCrop(route)
        return null
    }
    val highSpeed = (!gpuPhotoPreview && videoProfile?.constrainedHighSpeed == true) ||
        (openCineLog && logProfile?.constrainedHighSpeed == true)
    if (highSpeed) return refuse(LensLockUnavailable.HIGH_SPEED)
    if (nightExtensionEngaged(nightExtension, Build.VERSION.SDK_INT, logical.nightExtension) && !openCineLog &&
        videoProfile == null && logProfile == null && stillFormat == StillPhotoFormat.JPEG) return refuse(LensLockUnavailable.NIGHT)
    if (binding.physicalId in lensLockCropFallbacks) return crop("failed before")
    if (lensLockCropForced()) return crop("forced by debug.opencinecam.lenslock.forcecrop")
    val physical = physicalDescriptor(binding, logical) ?: return crop(LensLockUnavailable.DESCRIPTOR)
    if (stillFormat == StillPhotoFormat.HEIC && physical.heicSize == null) return crop(LensLockUnavailable.FORMAT)
    if (openCineLog && !physical.supportsOpenCineLog) return crop(LensLockUnavailable.LOG)
    val mappedVideo = videoProfile?.let { requested ->
        val candidates = physical.videoProfiles.filterNot { it.constrainedHighSpeed }
        LensLockMapping.profileIndex(requested.size.width, requested.size.height, requested.fps,
            candidates.map { Triple(it.size.width, it.size.height, it.fps) })?.let(candidates::get)
    }
    val mappedLog = if (!openCineLog) null else {
        val regular = physical.logProfiles.filterNot { it.constrainedHighSpeed }
        val fps = logProfile?.fps ?: targetFps
        logProfile?.let { requested ->
            val candidates = regular.filter { it.sourcePath == requested.sourcePath }
            LensLockMapping.profileIndex(requested.size.width, requested.size.height, requested.fps,
                candidates.map { Triple(it.size.width, it.size.height, it.fps) }, anyRate = false)?.let(candidates::get)
        } ?: preferredLogProfile(regular.filter { it.fps == fps }, fps) ?: preferredLogProfile(regular, fps)
    }
    val mappedStill = stillSize?.let { requested ->
        val sizes = if (stillFormat == StillPhotoFormat.HEIC) physical.heicSizes else physical.jpegSizes
        LensLockMapping.stillSize(requested.width, requested.height, sizes.map { it.width to it.height })
            ?.let { (w, h) -> Size(w, h) }
    }
    Log.i(TAG, "Lens lock: camera ${logical.cameraId} opens physical ${binding.physicalId} (anchor ${binding.anchorRatio}); " +
        "video ${videoProfile?.let { "${it.size}@${it.fps}" }} -> ${mappedVideo?.let { "${it.size}@${it.fps}" }}, " +
        "log ${logProfile?.let { "${it.size}@${it.fps}" }} -> ${mappedLog?.let { "${it.size}@${it.fps}" }}, still $stillSize -> $mappedStill")
    return LensLockPlan(physical, mappedVideo, mappedLog, mappedStill)
}

/** Hands a failed lens-locked open or configuration to the fallback; false when the session is not a pending lock. */
internal fun Camera2PreviewEngine.withdrawLensLock(reason: String): Boolean {
    val withdraw = lensLockWithdraw ?: return false
    withdraw(reason)
    return true
}

/** The descriptor the app knows: the logical one for a lens-locked session. */
internal fun Camera2PreviewEngine.appDescriptor(descriptor: Camera2CameraDescriptor): Camera2CameraDescriptor =
    if (descriptor.lensLock != null) lockedLogicalDescriptor ?: descriptor else descriptor

/** A physical zoom ratio on the logical scale while lens-locked. */
internal fun Camera2PreviewEngine.uiZoom(physical: Float): Float =
    activeDescriptor?.lensLock?.let { LensLockZoom.toUi(physical, it.anchorRatio) } ?: physical

internal fun Camera2PreviewEngine.zoomOverrideEligible(descriptor: Camera2CameraDescriptor): Boolean =
    ZoomSettingsOverride.eligible(
        Build.VERSION.SDK_INT, descriptor.supportsZoomRatioApi, descriptor.supportsZoomSettingsOverride, isHighSpeedSession(),
    )

/** Asks the HAL to apply zoom ahead of other settings while zoom is moving; OFF otherwise. */
internal fun Camera2PreviewEngine.applyZoomSettingsOverride(builder: CaptureRequest.Builder, descriptor: Camera2CameraDescriptor) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !zoomOverrideEligible(descriptor)) return
    try {
        builder.set(
            CaptureRequest.CONTROL_SETTINGS_OVERRIDE,
            if (zoomOverrideArmed) CaptureRequest.CONTROL_SETTINGS_OVERRIDE_ZOOM else CaptureRequest.CONTROL_SETTINGS_OVERRIDE_OFF,
        )
    } catch (_: IllegalArgumentException) {
        zoomOverrideArmed = false
    }
}

/** Returns the override to OFF on the next repeating request once zoom has been idle. */
internal fun Camera2PreviewEngine.releaseZoomOverrideIfIdle(result: TotalCaptureResult) {
    if (!zoomOverrideArmed) return
    val descriptor = activeDescriptor ?: return
    val resultOverride = if (Build.VERSION.SDK_INT >= 34) {
        result.get(android.hardware.camera2.CaptureResult.CONTROL_SETTINGS_OVERRIDE)
    } else null
    if (!ZoomSettingsOverride.shouldRelease(
            armed = true,
            nowMs = SystemClock.elapsedRealtime(),
            lastChangeMs = lastZoomChangeAtMs,
            resultZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)
            } else null,
            requestedRatio = requestedZoomRatio,
            resultOverrideIsOff = resultOverride == CaptureRequest.CONTROL_SETTINGS_OVERRIDE_OFF,
        )) return
    zoomOverrideArmed = false
    val builder = repeatingBuilder ?: return
    val configured = session ?: return
    reissueRepeating(builder, configured, descriptor)
}

internal fun Camera2PreviewEngine.applyZoom(builder: CaptureRequest.Builder) {
    val descriptor = activeDescriptor ?: return
    val range = descriptor.effectiveZoomRange ?: return
    applyZoomSettingsOverride(builder, descriptor)
    val ratio = requestedZoomRatio
    if (ratio <= range.start && range.start >= 1f) {
        // At or below the wide end: no crop needed.
        if (descriptor.supportsZoomRatioApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, range.start)
        } else {
            builder.set(CaptureRequest.SCALER_CROP_REGION, null)
        }
        return
    }
    if (descriptor.supportsZoomRatioApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
    } else {
        // API 29 fallback: only digital zoom >= 1x is representable via SCALER_CROP_REGION.
        val cropRatio = ratio.coerceAtLeast(1f)
        builder.set(CaptureRequest.SCALER_CROP_REGION, ZoomMath.cropRegionFor(descriptor.sensorActiveArray, cropRatio))
    }
}

internal fun Camera2PreviewEngine.fovWidth(c: CameraCharacteristics): Double? {
    val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
    return RawZoomCrop.fovWidth(
        c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width, active.width(),
        c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.width ?: return null,
        c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
    )
}

/** Tells the listener which physical lens serves the logical preview streams, when that changes. */
internal fun Camera2PreviewEngine.reportActiveLens(result: TotalCaptureResult) {
    // A direct physical open carries no active-physical key: the locked lens is the active one.
    val active = activeDescriptor?.lensLock?.physicalId ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
    } else null
    val report = true to active
    if (report == lastActiveLensReport) return
    lastActiveLensReport = report
    listener?.onActiveLens(active)
}

/** On the GPU-crop route, warns (debounced) while the HAL serves the streams from another lens. */
internal fun Camera2PreviewEngine.reportLensLockSwitch(result: TotalCaptureResult) {
    val crop = lensLockCrop ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val active = result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
    lensSwitchDebounce.offer(active, crop.binding.physicalId)?.let { switched ->
        Log.w(TAG, "Lens lock crop on ${crop.binding.physicalId}: the HAL ${if (switched) "switched to $active" else "is back on the locked lens"}")
        listener?.onLensLockSwitched(switched)
    }
}

/**
 * Confirms the still route from the still's own result: a still on a physical output stream always
 * comes from that camera, so its physical results name the camera that produced it.
 */
internal fun Camera2PreviewEngine.reportStillRoute(result: TotalCaptureResult) {
    val requested = routedStillPhysicalId ?: return
    val ids = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        result.physicalCameraTotalResults?.keys
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        @Suppress("DEPRECATION") result.physicalCameraResults?.keys
    } else null
    listener?.onPhysicalRoute(requested, stillRouteReportedId(requested, ids.orEmpty()))
}

internal fun Camera2PreviewEngine.reportEffectiveZoom(result: TotalCaptureResult) {
    val descriptor = activeDescriptor ?: return
    val range = descriptor.effectiveZoomRange ?: return
    val now = SystemClock.elapsedRealtime()
    if (now - lastZoomReportedAtMs < ZOOM_REPORT_PERIOD_MS) return
    lastZoomReportedAtMs = now
    val ratio = if (descriptor.supportsZoomRatioApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)
    } else {
        ZoomMath.ratioFromCropRegion(
            descriptor.sensorActiveArray,
            result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION),
        )
    } ?: return
    val crop = lensLockCrop
    val coerced = if (crop != null) LensLockCrop.uiRatio(ZoomMath.coerce(ratio, range), crop.factor)
        else descriptor.lensLock?.let { LensLockZoom.toUi(ZoomMath.coerce(ratio, range), it.anchorRatio) }
        ?: ZoomMath.coerce(ratio, range)
    effectiveZoomRatio = coerced
    listener?.onZoomEffective(coerced)
}

internal fun Camera2PreviewEngine.notifyZoomRange() {
    val descriptor = activeDescriptor ?: return
    lensLockCrop?.let { crop ->
        lensLockWithdraw = null
        listener?.onLensLockCrop(crop.binding.physicalId, crop.uiRange)
        return
    }
    val lock = descriptor.lensLock
    if (lock != null) {
        // The locked session is configured: later failures are ordinary failures, not a fallback.
        lensLockWithdraw = null
        val range = descriptor.effectiveZoomRange ?: (1f..1f)
        listener?.onLensLock(lock.physicalId, LensLockZoom.uiRange(lock.anchorRatio, range))
        return
    }
    descriptor.effectiveZoomRange?.let { range ->
        listener?.onZoomRangeAvailable(range.start, range.endInclusive, descriptor.opticalAnchors)
    }
    listener?.onLensLock(null, null)
}

/**
 * Qualcomm instant AEC: boost AE convergence when a preview session starts and when a logical
 * camera switches its active physical lens (zoom crossing a switchover), then restore the normal
 * mode on convergence or after the timeout. Inside a take only the fast mode is used and only
 * for a lens switch; AE lock, manual exposure and constrained high-speed never boost. Silent
 * no-op without the vendor request key and an advertised boost mode.
 */
internal fun Camera2PreviewEngine.driveInstantAec(armedSession: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
    // A lens-locked session never switches lens, and its vendor keys belong to the physical camera.
    if (activeDescriptor?.lensLock != null) return
    runCatching {
        val builder = repeatingBuilder ?: return
        val eligible = InstantAec.eligible(
            highSpeed = armedSession is CameraConstrainedHighSpeedCaptureSession,
            manualExposure = request.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_OFF,
            aeLocked = request.get(CaptureRequest.CONTROL_AE_LOCK) == true,
        )
        val now = SystemClock.elapsedRealtime()
        val physicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        } else null
        if (instantAecSession !== armedSession) {
            instantAecSession = armedSession
            instantAecPhysicalId = physicalId
            instantAecKey = null
            instantAecModes = null
            instantAec.reset()
            val descriptor = activeDescriptor ?: return
            val characteristics = manager.getCameraCharacteristics(descriptor.cameraId)
            val key = VendorKeys.request(characteristics, descriptor.cameraId, InstantAec.MODE_KEY, InstantAec.AVAILABLE_MODES_KEY, Int::class.javaObjectType) ?: return
            instantAecModes = VendorKeys.staticInts(characteristics, InstantAec.AVAILABLE_MODES_KEY) ?: return
            instantAecKey = key
            // A session that starts mid-take (a recorder graph) is not boosted: only a lens switch is.
            if (!recording) engageInstantAec(armedSession, builder, now, eligible, "session start")
            return
        }
        val key = instantAecKey ?: return
        val previousPhysicalId = instantAecPhysicalId
        if (physicalId != null) instantAecPhysicalId = physicalId
        if (!instantAec.active) {
            if (InstantAec.physicalLensSwitched(previousPhysicalId, physicalId) && !instantAecOnLensSwitchDisabled()) {
                engageInstantAec(armedSession, builder, now, eligible, "lens $previousPhysicalId->$physicalId")
            }
            return
        }
        val (release, elapsedMs) = instantAec.onResult(
            now,
            result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE),
            eligible,
            recording,
            boostApplied = request.get(key) == instantAec.mode,
        ) ?: return
        builder.set(key, InstantAec.MODE_NORMAL)
        armedSession.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
        Log.i(TAG, "Instant AEC released reason=$release timeToConvergeMs=$elapsedMs recording=$recording")
    }.onFailure { Log.w(TAG, "Instant AEC skipped: ${it.message}") }
}

internal fun Camera2PreviewEngine.engageInstantAec(
    armedSession: CameraCaptureSession,
    builder: CaptureRequest.Builder,
    now: Long,
    eligible: Boolean,
    trigger: String,
) {
    val key = instantAecKey ?: return
    val boost = InstantAec.boostMode(instantAecModes, recording) ?: return
    if (!instantAec.begin(now, boost, eligible, recording)) return
    try {
        builder.set(key, boost)
        armedSession.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
        Log.i(TAG, "Instant AEC engaged mode=$boost trigger=$trigger recording=$recording")
    } catch (failure: Throwable) {
        instantAec.reset()
        runCatching { builder.set(key, InstantAec.MODE_NORMAL) }
        throw failure
    }
}
