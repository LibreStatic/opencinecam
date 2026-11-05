/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import com.librestatic.opencinecam.core.model.QuirkEffect
import android.os.SystemClock
import android.util.Log
import android.util.Range
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.DynamicFpsKeys
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.IspHistogramSupport
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.VendorMeteringKeys
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.TAG

// Part of Camera2PreviewEngine: capture request composition: exposure, image processing, frame rate, vendor keys and recording white balance.
// These are extensions so the engine's state stays in one object; they run on the engine's executors like its members.

internal fun Camera2PreviewEngine.clearRecordingWhiteBalance(resubmit: Boolean) {
    val held = heldRecordingWb != null
    val callback = recordingWbCallback
    recordingWbCallback = null
    recordingWbTimeout?.let(imageHandler::removeCallbacks)
    recordingWbTimeout = null
    heldRecordingWb = null
    recordingWbGate.reset()
    recordingWbMinimumTimestampNs = null
    if (resubmit && held) submitRecordingWhiteBalance()
    callback?.invoke(RecordingWhiteBalanceResult(RecordingWhiteBalanceStatus.FAILED, failure = "white-balance-preparation-cancelled"))
}

internal fun Camera2PreviewEngine.failRecordingWhiteBalance(reason: String) {
    val callback = recordingWbCallback
    recordingWbCallback = null
    clearRecordingWhiteBalance(resubmit = true)
    callback?.invoke(RecordingWhiteBalanceResult(RecordingWhiteBalanceStatus.FAILED, failure = reason))
}

internal fun Camera2PreviewEngine.submitRecordingWhiteBalance() {
    val builder = repeatingBuilder ?: return
    val configured = session ?: return
    if (configured is CameraConstrainedHighSpeedCaptureSession) return
    runCatching {
        applyManualControls(builder)
        configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
    }.onFailure {
        if (recordingWbCallback != null) failRecordingWhiteBalance("white-balance-request-rejected")
        else listener?.onProfessionalControlsRejected(it.message ?: "White balance restoration was rejected.")
    }
}

internal fun Camera2PreviewEngine.reportRecordingWhiteBalance(request: CaptureRequest, result: TotalCaptureResult) {
    if (recordingWbCallback == null) return
    val before = recordingWbGate.status
    val held = heldRecordingWb
    val fixed = held != WhiteBalanceSelection.Auto && held != WhiteBalanceSelection.Preset(CaptureRequest.CONTROL_AWB_MODE_AUTO)
    val status = if (fixed) recordingWbGate.observeFixed(recordingWbToken, SystemClock.elapsedRealtime(),
        fixedRecordingWhiteBalanceMatches(held, request, result), result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP))
    else recordingWbGate.observe(recordingWbToken, SystemClock.elapsedRealtime(),
        request.get(CaptureRequest.CONTROL_AWB_MODE) == CaptureRequest.CONTROL_AWB_MODE_AUTO,
        result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_MODE) == CaptureRequest.CONTROL_AWB_MODE_AUTO,
        request.get(CaptureRequest.CONTROL_AWB_LOCK) == true,
        result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_LOCK),
        result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE),
        result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP))
    when {
        status == RecordingWhiteBalanceStatus.FAILED -> failRecordingWhiteBalance("white-balance-lock-timeout")
        status == RecordingWhiteBalanceStatus.LOCKING && before != status -> submitRecordingWhiteBalance()
        status in setOf(RecordingWhiteBalanceStatus.LOCKED, RecordingWhiteBalanceStatus.FIXED) -> {
            recordingWbTimeout?.let(imageHandler::removeCallbacks)
            recordingWbTimeout = null
            recordingWbMinimumTimestampNs = recordingWbGate.minimumSensorTimestampNs
            val callback = recordingWbCallback
            recordingWbCallback = null
            callback?.invoke(RecordingWhiteBalanceResult(status, recordingWbMinimumTimestampNs))
        }
    }
}

internal fun Camera2PreviewEngine.fixedRecordingWhiteBalanceMatches(held: WhiteBalanceSelection?, request: CaptureRequest, result: TotalCaptureResult): Boolean {
    val mode = when (held) {
        is WhiteBalanceSelection.Preset -> held.awbMode
        is WhiteBalanceSelection.Kelvin -> CaptureRequest.CONTROL_AWB_MODE_OFF
        else -> return false
    }
    if (request.get(CaptureRequest.CONTROL_AWB_MODE) != mode || result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_MODE) != mode) return false
    if (held is WhiteBalanceSelection.Kelvin) {
        if (Build.VERSION.SDK_INT < 36) return false
        if (request.get(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE) != held.kelvin ||
            result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE) != held.kelvin) return false
        if (activeDescriptor?.tintSupported == true && (request.get(CaptureRequest.COLOR_CORRECTION_COLOR_TINT) != held.tint ||
                result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_COLOR_TINT) != held.tint)) return false
    }
    return true
}

internal fun Camera2PreviewEngine.applyTorch(builder: CaptureRequest.Builder) {
    val descriptor = activeDescriptor ?: return
    val resolved = descriptor.torchCapabilities.resolve(requestedTorchEnabled, requestedTorchStrength, isHighSpeedSession())
    builder.set(CaptureRequest.FLASH_MODE, if (resolved.enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    if (Build.VERSION.SDK_INT >= 35 && descriptor.torchCapabilities.adjustable) {
        builder.set(CaptureRequest.FLASH_STRENGTH_LEVEL, resolved.strengthLevel ?: descriptor.torchCapabilities.defaultLevel)
    }
}

/**
 * Discovers the Qualcomm ISP histogram by name, as the Motorola session keys are: the enable
 * key must be among the camera's advertised request keys and the static bucket count must be
 * sane. Nothing is assumed; any failure leaves the frame histogram in charge.
 */
internal fun Camera2PreviewEngine.ispHistogramSupport(): IspHistogramSupport? {
    val id = activeDescriptor?.cameraId ?: return null
    ispSupport?.takeIf { it.first == id }?.let { return it.second }
    val support = runCatching {
        val characteristics = manager.getCameraCharacteristics(id)
        val buckets = VendorKeys.staticInts(characteristics, IspHistogramKeys.BUCKETS)?.firstOrNull()
        val enable = VendorKeys.request(characteristics, id, IspHistogramKeys.ENABLE, IspHistogramKeys.BUCKETS, ByteArray::class.java)
        val count = ispHistogramBuckets(listOfNotNull(enable?.name), buckets)
        if (count != null && enable != null) IspHistogramSupport(id, count, enable) else null
    }.getOrNull()
    ispSupport = id to support
    ispStatsKey = null
    ispStatsTypeKey = null
    ispHistogramLogged = false
    ispHistogram.clear()
    return support
}

internal fun Camera2PreviewEngine.enableIspHistogram(builder: CaptureRequest.Builder) {
    val support = ispHistogramSupport() ?: return
    try { builder.set(support.enableKey, byteArrayOf(1)) } catch (_: IllegalArgumentException) { /* Frame histogram stays. */ }
}

/** cameraExecutor: keeps the latest ISP histogram of a delivered result for the next analysis frame. */
internal fun Camera2PreviewEngine.readIspHistogram(result: TotalCaptureResult) {
    val support = ispSupport?.second ?: return
    try {
        val statsKey = ispStatsKey ?: result.keys.firstOrNull { it.name == IspHistogramKeys.STATS }
            ?.also { ispStatsKey = it } ?: return
        val buckets = result.get(statsKey) as? IntArray ?: return
        if (buckets.size != support.buckets) return
        val typeKey = ispStatsTypeKey ?: result.keys.firstOrNull { it.name == IspHistogramKeys.STATS_TYPE }
            ?.also { ispStatsTypeKey = it }
        val statsType = typeKey?.let { (result.get(it) as? IntArray)?.firstOrNull() }
        val source = ispHistogramSource(statsType)
        if (!ispHistogramLogged) {
            ispHistogramLogged = true
            Log.i(TAG, "ISP histogram: ${buckets.size} buckets, stats_type=$statsType, source=$source")
        }
        ispHistogram.offer(buckets, source, SystemClock.elapsedRealtime())
    } catch (_: RuntimeException) { /* A vendor read must never disturb the preview callback. */ }
}

/**
 * Swaps plain AE_MODE_ON for the low-light boost mode when the user asked for it and
 * [resolveLowLightBoost] allows it. Video graphs (recording, video/LOG/HLG previews) keep
 * plain AE: the boost may lengthen frame duration, and a video take must hold its fixed
 * fps, so the exposure would also jump when a take starts. Only preview and PHOTO use it.
 * A flash plan applied later overrides the AE mode, so AE_MODE_ON_*FLASH always wins.
 */
internal fun Camera2PreviewEngine.applyLowLightBoost(builder: CaptureRequest.Builder, exposureMode: ExposureMode, videoGraph: Boolean) {
    if (Build.VERSION.SDK_INT < 35 || activeDescriptor?.lowLightBoostSupported != true) return
    val decision = resolveLowLightBoost(
        supported = true,
        enabled = professionalExposure?.lowLightBoost == true,
        exposureMode = exposureMode,
        scene = if (videoGraph) LowLightBoostScene.VIDEO else LowLightBoostScene.PREVIEW_OR_STILL,
        highSpeed = isHighSpeedSession(),
        maxTargetFps = builder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?.upper ?: requestedTargetFps,
    )
    if (decision.active) {
        runCatching { builder.set(CaptureRequest.CONTROL_AE_MODE, AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY) }
    }
}

internal fun Camera2PreviewEngine.reportExposureGate(selection: ExposureSelection, gate: ExposureGate) {
    val key = if (gate.code == null) null else listOf(selection, activeDescriptor?.cameraId, controlPolicy, gate.code)
    if (key == lastExposureGateKey) return
    lastExposureGateKey = key
    val message = gate.message ?: return
    val code = gate.code?.code ?: return
    if (gate.rejected) listener?.onControlRejected(code, message) else listener?.onControlAdapted(code, message)
}

internal fun Camera2PreviewEngine.applyManualControls(
    builder: CaptureRequest.Builder,
    defaultAfMode: Int = if (recording || logPreviewEnabled || (gpuPreviewEnabled && !gpuPhotoPreviewEnabled) || activeVideoProfile != null)
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
) {
    if (!isHighSpeedSession()) enableIspHistogram(builder)
    val pro = professionalExposure?.let { selection ->
        val rate = CaptureFrameRate(requestedTargetFps.coerceAtLeast(1))
        // High-speed sessions pass empty capabilities on purpose: they fall back to auto without a notice.
        if (isHighSpeedSession()) return@let selection.resolve(ExposureCapabilities(), rate)
        val gate = selection.gate(activeDescriptor?.exposureCapabilities ?: ExposureCapabilities(), rate, controlPolicy)
        reportExposureGate(selection, gate)
        gate.exposure
    }
    val iso = if (pro != null) pro.iso else requestedIso
    val exposure = if (pro != null) pro.timeNs else requestedExposureNs
    if (pro == null && Build.VERSION.SDK_INT >= 36 && activeDescriptor?.exposureCapabilities?.priorities?.isNotEmpty() == true) {
        builder.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CaptureRequest.CONTROL_AE_PRIORITY_MODE_OFF)
    }
    if (pro != null) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, if (pro.mode == ExposureMode.MANUAL) CaptureRequest.CONTROL_AE_MODE_OFF else CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, pro.iso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, pro.timeNs)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, pro.frameDurationNs)
        if (Build.VERSION.SDK_INT >= 36 && activeDescriptor?.exposureCapabilities?.priorities?.isNotEmpty() == true) {
            builder.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, when (pro.mode) {
                ExposureMode.ISO_PRIORITY -> CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY
                ExposureMode.SHUTTER_PRIORITY -> CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
                else -> CaptureRequest.CONTROL_AE_PRIORITY_MODE_OFF
            })
        }
        builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLockActive && pro.mode == ExposureMode.AUTO)
        requestedAeCompensationIndex?.let { builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
        pro.antibanding?.let { band -> builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, when (band) {
            Antibanding.AUTO -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
            Antibanding.OFF -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
            Antibanding.HZ50 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
            Antibanding.HZ60 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
        }) }
    } else if (iso != null && exposure != null) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        val frameDurationNs = 1_000_000_000L / requestedTargetFps.coerceAtLeast(1)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.coerceAtMost(frameDurationNs - 100_000L))
    } else {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, if (aeLockActive) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE)
        requestedAeCompensationIndex?.let { index ->
            if (activeDescriptor?.aeCompensationRange != null) {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
            }
        }
    }
    applyLowLightBoost(
        builder,
        pro?.mode ?: if (iso != null && exposure != null) ExposureMode.MANUAL else ExposureMode.AUTO,
        videoGraph = defaultAfMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
    )
    applyVendorMetering(builder, pro?.metering)
    if (cadenceHoldActive()) applyCadenceHold(builder)
    when {
        requestedFocusDiopters != null -> {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, requestedFocusDiopters!!)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
        }
        afLockState == LockState.LOCKED -> {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, afLockFrozenDiopters ?: 0f)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
        }
        // The lock scan needs a real AUTO sweep at its region: a trigger in a continuous mode
        // only locks wherever the lens already is.
        afLockState == LockState.PENDING -> {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, tapAfRegion?.let { arrayOf(it) })
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
        }
        activeTapFocusToken != null -> {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, tapAfRegion?.let { arrayOf(it) })
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, tapAeRegion?.let { arrayOf(it) })
        }
        else -> {
            builder.set(CaptureRequest.CONTROL_AF_MODE, defaultAfMode)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
        }
    }
    val wbDescriptor = activeDescriptor
    val effectiveWb = (heldRecordingWb ?: requestedWhiteBalance).adaptTo(
        wbDescriptor?.kelvinRange.takeUnless { isHighSpeedSession() },
        wbDescriptor?.tintSupported == true,
        if (isHighSpeedSession()) emptySet() else wbDescriptor?.availableAwbModes,
    )
    when (val wb = effectiveWb) {
        is WhiteBalanceSelection.Auto -> {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            if (Build.VERSION.SDK_INT >= 36) {
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
                builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, null)
                builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, null)
            }
        }
        is WhiteBalanceSelection.Kelvin -> {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            if (Build.VERSION.SDK_INT >= 36) {
                val range = activeDescriptor?.kelvinRange
                val clamped = if (range != null) wb.kelvin.coerceIn(range.first, range.last) else wb.kelvin
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_CCT)
                builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, clamped)
                if (wbDescriptor?.tintSupported == true) builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, wb.tint)
            }
        }
        is WhiteBalanceSelection.Preset -> {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, wb.awbMode)
            if (Build.VERSION.SDK_INT >= 36) {
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
                builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, null)
                builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, null)
            }
        }
    }
    if (wbDescriptor?.awbLockSupported == true) builder.set(CaptureRequest.CONTROL_AWB_LOCK, recordingWbGate.lockRequested)
    applyImageProcessing(builder)
    applyTorch(builder)
    applyZoom(builder)
    applyFaceDetect(builder)
}

internal fun Camera2PreviewEngine.applyImageProcessing(builder: CaptureRequest.Builder) {
    applyDynamicFps(builder)
    val caps = activeDescriptor?.imageProcessingCapabilities ?: return
    val defaults = processingDefaults.getOrPut(builder) {
        ImageProcessingDefaults(
            builder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE),
            builder.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE),
            builder.get(CaptureRequest.NOISE_REDUCTION_MODE),
            builder.get(CaptureRequest.EDGE_MODE),
        )
    }
    val resolved = imageProcessing.resolve(caps, defaults, isHighSpeedSession()).values
    if (caps.opticalModes.isNotEmpty()) builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, resolved.optical)
    if (caps.videoModes.isNotEmpty()) builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, resolved.video)
    if (caps.noiseModes.isNotEmpty()) builder.set(CaptureRequest.NOISE_REDUCTION_MODE, resolved.noise)
    if (caps.edgeModes.isNotEmpty()) builder.set(CaptureRequest.EDGE_MODE, resolved.edge)
    applyPictureProfile(builder, caps)
    applyPictureControls(builder, caps.pictureControls, resolved.edge)
}

/**
 * Picture profile as an ISP contrast curve. STANDARD, an ineligible graph or a camera without
 * the keys restores the template's tone mapping, captured once per builder like the other
 * processing defaults, so switching back never leaves a stale curve.
 */
internal fun Camera2PreviewEngine.applyPictureProfile(builder: CaptureRequest.Builder, caps: ImageProcessingCapabilities) {
    if (!caps.supportsPictureProfiles) return
    val defaults = toneMapDefaults.getOrPut(builder) { builder.get(CaptureRequest.TONEMAP_MODE) to builder.get(CaptureRequest.TONEMAP_CURVE) }
    val context = ToneProfileContext(
        logOrHlg = logPreviewEnabled || activeLogProfile != null,
        constrainedHfr = isHighSpeedSession(),
    )
    val points = imageProcessing.pictureProfile.resolveCurve(caps, context)
    try {
        if (points == null) {
            builder.set(CaptureRequest.TONEMAP_MODE, defaults.first)
            builder.set(CaptureRequest.TONEMAP_CURVE, defaults.second)
        } else {
            builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            builder.set(CaptureRequest.TONEMAP_CURVE, android.hardware.camera2.params.TonemapCurve(points, points, points))
        }
    } catch (failure: IllegalArgumentException) {
        Log.w(TAG, "Picture profile curve rejected; keeping the template tone mapping.", failure)
    }
}

/** A DNG-only still bypasses the ISP tone curve, so it goes out with the template's tone mapping. */
internal fun Camera2PreviewEngine.restoreToneMapDefaults(builder: CaptureRequest.Builder) {
    val defaults = toneMapDefaults[builder] ?: return
    runCatching {
        builder.set(CaptureRequest.TONEMAP_MODE, defaults.first)
        builder.set(CaptureRequest.TONEMAP_CURVE, defaults.second)
    }
}

internal fun Camera2PreviewEngine.pictureKeys(): PictureControlKeys? {
    val id = activeDescriptor?.cameraId ?: return null
    return pictureKeyCache.getOrPut(id) {
        runCatching { PictureControlKeys.of(manager.getCameraCharacteristics(id), id) }.getOrDefault(PictureControlKeys())
    }
}

/**
 * Qualcomm saturation and sharpness. LOG, HLG, RAW and constrained HFR keep neutral template
 * processing; a null value clears the key so a reused builder returns to the template.
 */
internal fun Camera2PreviewEngine.applyPictureControls(builder: CaptureRequest.Builder, caps: PictureControlCapabilities, edgeMode: Int?) {
    if (!caps.any) return
    val keys = pictureKeys() ?: return
    val neutral = isHighSpeedSession() || logPreviewEnabled || activeLogProfile != null || activeStillFormat == StillPhotoFormat.DNG
    val resolved = imageProcessing.resolvePictureControls(caps, edgeMode, neutral)
    keys.saturationRequest?.let { key -> runCatching { builder.set(key, resolved.saturation?.let { intArrayOf(it) }) } }
    keys.sharpnessRequest?.let { key -> runCatching { builder.set(key, resolved.sharpness?.let { intArrayOf(it) }) } }
}

internal fun Camera2PreviewEngine.pictureControlReadback(request: CaptureRequest, result: android.hardware.camera2.CaptureResult): PictureControlReadback? {
    val keys = pictureKeys() ?: return null
    fun read(key: CaptureRequest.Key<IntArray>?, reported: android.hardware.camera2.CaptureResult.Key<IntArray>?): Pair<Int?, Int?> =
        runCatching { (key?.let { request.get(it) }?.firstOrNull()) to (reported?.let { result.get(it) }?.firstOrNull()) }.getOrDefault(null to null)
    val (sentSaturation, gotSaturation) = read(keys.saturationRequest, keys.saturationResult)
    val (sentSharpness, gotSharpness) = read(keys.sharpnessRequest, keys.sharpnessResult)
    if (gotSaturation == null && gotSharpness == null) return null
    return PictureControlReadback(sentSaturation, gotSaturation, sentSharpness, gotSharpness)
}

/**
 * The auto frame rate keys, resolved by name through [VendorKeys] (the Razr's HAL leaves them out
 * of availableRequestKeys on some builds). Offered only when the static `IQdynamicFPS_support`
 * is 1 and the enable key and both session keys resolve.
 */
internal fun Camera2PreviewEngine.dynamicFpsKeys(characteristics: CameraCharacteristics, cameraId: String): DynamicFpsKeys =
    dynamicFpsKeyCache.getOrPut(cameraId) {
        runCatching {
            val support = DynamicFps.staticSupport(VendorKeys.staticInts(characteristics, DynamicFps.SUPPORT_KEY))
            if (!support) return@runCatching DynamicFpsKeys()
            val enable = VendorKeys.request(characteristics, cameraId, DynamicFps.ENABLE_KEY, DynamicFps.SUPPORT_KEY, IntArray::class.java)
            val legacy = VendorKeys.request(characteristics, cameraId, DynamicFps.LEGACY_ENABLE_KEY, DynamicFps.SUPPORT_KEY, IntArray::class.java)
            val viull = VendorKeys.request(characteristics, cameraId, DynamicFps.VIULL_KEY, DynamicFps.SUPPORT_KEY, IntArray::class.java)
            val config = VendorKeys.request(characteristics, cameraId, DynamicFps.CONFIG_KEY, DynamicFps.SUPPORT_KEY, FloatArray::class.java)
            DynamicFpsKeys(enable, legacy, viull, config,
                DynamicFps.offered(true, enable != null, viull != null, config != null))
        }.getOrDefault(DynamicFpsKeys())
    }

internal fun Camera2PreviewEngine.activeDynamicFpsKeys(): DynamicFpsKeys? {
    val id = activeDescriptor?.cameraId ?: return null
    return dynamicFpsKeyCache[id] ?: runCatching { dynamicFpsKeys(manager.getCameraCharacteristics(id), id) }.getOrNull()
}

/**
 * Auto frame rate on every request: an explicit 0 unless the operator turned it on for regular
 * video, so the HAL never lowers the cadence on its own. Constrained high-speed sessions accept
 * only a restricted key set and keep the template.
 */
internal fun Camera2PreviewEngine.applyDynamicFps(builder: CaptureRequest.Builder) {
    if (isHighSpeedSession()) return
    val keys = activeDynamicFpsKeys() ?: return
    val value = intArrayOf(if (dynamicFpsOn) 1 else 0)
    keys.enable?.let { key -> runCatching { builder.set(key, value) } }
    // The older switch is never on: only the IQ one carries the operator's choice.
    keys.legacyEnable?.let { key -> runCatching { builder.set(key, intArrayOf(0)) } }
}

/** Session-only half of the ON state, as the stock camera sends it: EnableVIULL and the rate window. */
internal fun Camera2PreviewEngine.applyDynamicFpsSession(builder: CaptureRequest.Builder) {
    if (!dynamicFpsOn || isHighSpeedSession()) return
    val keys = activeDynamicFpsKeys() ?: return
    keys.viull?.let { key -> runCatching { builder.set(key, intArrayOf(1)) } }
    keys.config?.let { key -> runCatching { builder.set(key, DynamicFps.config(requestedTargetFps)) } }
}

internal fun Camera2PreviewEngine.applyTargetFps(builder: CaptureRequest.Builder, descriptor: Camera2CameraDescriptor) {
    descriptor.targetFpsRanges
        .firstOrNull { it.lower == requestedTargetFps && it.upper == requestedTargetFps }
        ?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
}

internal fun Camera2PreviewEngine.engineEnumKey(key: String): CaptureRequest.Key<Int> = when (key) {
    "CONTROL_MODE" -> CaptureRequest.CONTROL_MODE
    "CONTROL_AF_MODE" -> CaptureRequest.CONTROL_AF_MODE
    "CONTROL_AE_MODE" -> CaptureRequest.CONTROL_AE_MODE
    "CONTROL_AWB_MODE" -> CaptureRequest.CONTROL_AWB_MODE
    "CONTROL_CAPTURE_INTENT" -> CaptureRequest.CONTROL_CAPTURE_INTENT
    "CONTROL_AE_PRECAPTURE_TRIGGER" -> CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER
    else -> throw IllegalArgumentException("No Camera2 request key for $key")
}

/** Constrained high-speed sessions accept only a restricted request-control subset. */
internal fun Camera2PreviewEngine.applyHighSpeedControls(builder: CaptureRequest.Builder) {
    // Keep TEMPLATE_RECORD defaults intact. Some Qualcomm HALs advertise these keys but
    // reject an HFR request when AF/AWB/AE mode is redundantly overridden.
    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(requestedTargetFps, requestedTargetFps))
    applyMotorolaHighSpeedSessionControls(builder)
    // Constrained high-speed sessions accept only a restricted request-control subset; only
    // apply zoom when the descriptor explicitly advertises HFR zoom support, otherwise the
    // HAL may reject the entire burst.
    if (activeDescriptor?.supportsHfrZoom == true) applyZoom(builder)
}

/**
 * Qualcomm exposure metering, set only while AE is automatic. Spot metering reads the AE region
 * of the request: the tap-to-meter rectangle while one is active, otherwise the HAL centres it.
 */
internal fun Camera2PreviewEngine.applyVendorMetering(builder: CaptureRequest.Builder, metering: ExposureMetering?) {
    val key = vendorMeteringKeys()?.request ?: return
    runCatching { builder.set(key, metering?.vendorValue) }
}

/** Discovered by name so devices without the vendor section are never touched. */
internal fun Camera2PreviewEngine.vendorMeteringKeys(): VendorMeteringKeys? {
    val id = activeDescriptor?.cameraId ?: return null
    return vendorMeteringKeyCache.getOrPut(id) {
        val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
        VendorMeteringKeys(
            characteristics?.let { VendorKeys.request(it, id, ExposureMetering.REQUEST_KEY_NAME, ExposureMetering.AVAILABLE_MODES_KEY_NAME, Int::class.javaObjectType) },
            characteristics?.let { VendorKeys.result(it, id, ExposureMetering.REQUEST_KEY_NAME, ExposureMetering.AVAILABLE_MODES_KEY_NAME, Int::class.javaObjectType) },
        )
    }
}

/** Advertised metering modes, only when both the request key and available_modes exist. */
internal fun Camera2PreviewEngine.vendorMeteringModes(characteristics: CameraCharacteristics): Set<ExposureMetering> = runCatching {
    val requestPresent = VendorKeys.request(characteristics, activeDescriptor?.cameraId, ExposureMetering.REQUEST_KEY_NAME, ExposureMetering.AVAILABLE_MODES_KEY_NAME, Int::class.javaObjectType) != null
    meteringModesOf(requestPresent, VendorKeys.staticInts(characteristics, ExposureMetering.AVAILABLE_MODES_KEY_NAME))
}.getOrDefault(emptySet())

/**
 * Motorola's public Camera2 characteristics expose these vendor session keys and its own
 * slow-motion client supplies both before configuring the constrained session. Without them,
 * the device advertises and configures 120/240 fps but its Qualcomm HAL rejects request zero.
 */
internal fun Camera2PreviewEngine.applyMotorolaHighSpeedSessionControls(builder: CaptureRequest.Builder) {
    val descriptor = activeDescriptor ?: return
    if (!deviceQuirks.resolve(descriptor.quirkCameraId).applies(QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS)) return
    val keys = runCatching {
        manager.getCameraCharacteristics(descriptor.cameraId).availableCaptureRequestKeys
    }.getOrNull().orEmpty()
    for (control in MotorolaHighSpeed.sessionValues(keys.map { it.name })) {
        val key = keys.first { it.name == control.keyName }
        @Suppress("UNCHECKED_CAST")
        when (control) {
            is HighSpeedSessionValue.Bytes -> builder.set(key as CaptureRequest.Key<ByteArray>, control.value)
            is HighSpeedSessionValue.Ints -> builder.set(key as CaptureRequest.Key<IntArray>, control.value)
        }
    }
}
