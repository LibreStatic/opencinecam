/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.SystemClock
import android.util.Log
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.FOCUS_PULL_TICK_MS
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.TAG

// Part of Camera2PreviewEngine: focus pulls, tap focus, AF lock and face detection.
// These are extensions so the engine's state stays in one object; they run on the engine's executors like its members.

internal fun Camera2PreviewEngine.cancelFocusPullLocked(notify: Boolean = true) {
    focusPullTask?.let(imageHandler::removeCallbacks)
    focusPullTask = null
    if (focusPullSession.cancel() && notify) {
        listener?.onFocusSelectionChanged(requestedFocusDiopters)
        listener?.onFocusPullCancelled()
    }
}

internal fun Camera2PreviewEngine.scheduleFocusPull(token: Long, cameraGeneration: Long, delayMs: Long) {
    focusPullTask = Runnable {
        cameraExecutor.execute {
            if (generation != cameraGeneration || !focusPullSession.isCurrent(token)) return@execute
            val step = focusPullSession.tick(token, SystemClock.elapsedRealtime()) ?: return@execute
            requestedFocusDiopters = step.diopters
            requestedFocusCameraId = activeDescriptor?.cameraId
            reapplyRepeating()
            if (step.finished) {
                focusPullTask = null
                listener?.onFocusSelectionChanged(step.diopters)
                listener?.onFocusPullFinished()
            } else scheduleFocusPull(token, cameraGeneration, FOCUS_PULL_TICK_MS)
        }
    }.also { imageHandler.postDelayed(it, delayMs) }
}

internal fun Camera2PreviewEngine.reportTapFocusResult(request: CaptureRequest, result: TotalCaptureResult) {
    val token = activeTapFocusToken ?: return
    if ((request.tag as? TapFocusRequestTag)?.token != token || tapFocusResultReported) return
    when (result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)) {
        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
            tapFocusResultReported = true
            listener?.onTapFocusState(TapFocusState.FOCUSED)
        }
        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
            tapFocusResultReported = true
            listener?.onTapFocusState(TapFocusState.NOT_FOCUSED)
        }
    }
}

internal fun Camera2PreviewEngine.restoreContinuousFocusLocked() {
    if (activeTapFocusToken == null) return
    clearTapFocusLocked(notify = true)
    val builder = repeatingBuilder ?: return
    val configured = session ?: return
    applyManualControls(
        builder,
        if (recording || logPreviewEnabled || activeVideoProfile != null) {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        } else {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        },
    )
    runCatching {
        configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
    }.onFailure {
        listener?.onControlRejected("tap-focus-restore-failed", it.message ?: "Continuous autofocus could not be restored.")
    }
}

internal fun Camera2PreviewEngine.clearTapFocusLocked(notify: Boolean) {
    imageHandler.removeCallbacks(restoreTapFocus)
    tapFocusGeneration++
    activeTapFocusToken = null
    tapAfRegion = null
    tapAeRegion = null
    tapFocusResultReported = false
    repeatingBuilder?.apply {
        setTag(null)
        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        set(CaptureRequest.CONTROL_AF_REGIONS, null)
        set(CaptureRequest.CONTROL_AE_REGIONS, null)
    }
    if (notify) listener?.onTapFocusState(TapFocusState.IDLE)
}

internal fun Camera2PreviewEngine.faceDetectGraph(): FaceDetectGraph = when {
    isHighSpeedSession() -> FaceDetectGraph.HIGH_SPEED
    logPreviewEnabled || activeLogProfile != null -> FaceDetectGraph.LOG
    recording || activeVideoProfile != null || (gpuPreviewEnabled && !gpuPhotoPreviewEnabled) -> FaceDetectGraph.VIDEO
    else -> FaceDetectGraph.PREVIEW
}

/** Mode this camera/graph would carry if requested, ignoring the request flag; null when unavailable. */
internal fun Camera2PreviewEngine.faceDetectModeFor(descriptor: Camera2CameraDescriptor?): Int? {
    descriptor ?: return null
    val graph = faceDetectGraph()
    if (faceDetectGate.isRejected(descriptor.cameraId, graph)) return null
    return FaceDetectCapability.select(descriptor.faceDetectModes, graph)
}

internal fun Camera2PreviewEngine.applyFaceDetect(builder: CaptureRequest.Builder) {
    val descriptor = activeDescriptor
    val mode = faceDetectModeFor(descriptor)?.takeIf { subjectFramingRequested }
    if (descriptor != null && mode != null) {
        if (!faceDetectDefaults.containsKey(builder)) faceDetectDefaults[builder] = builder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE)
        builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, mode)
        faceDetectGate.arm(descriptor.cameraId, faceDetectGraph())
    } else {
        // Restore the template value only on builders this hook changed; others stay untouched.
        if (faceDetectDefaults.containsKey(builder)) builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectDefaults.remove(builder))
        faceDetectGate.disarm()
    }
}

/** cameraExecutor: resubmits the repeating request with only the face-statistics key changed. */
internal fun Camera2PreviewEngine.reissueFaceDetect() {
    val builder = repeatingBuilder ?: return
    val configured = session ?: return
    if (isHighSpeedSession()) return
    applyFaceDetect(builder)
    runCatching {
        configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
    }.onFailure {
        if (builder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) == faceDetectModeFor(activeDescriptor) && subjectFramingRequested) {
            // The graph refused the key synchronously: remember that and continue without it.
            faceDetectGate.rejectActive()
            applyFaceDetect(builder)
            runCatching { configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false)) }
        }
        Log.w(TAG, "Face statistics request update failed; continuing without the out-of-frame warning.")
    }
}

/**
 * cameraExecutor, per frame: reduces this result's face boxes to "inside the recorded area?"
 * plus a coarse exit edge. Boxes are read here and dropped; nothing is logged or retained.
 */
internal fun Camera2PreviewEngine.reportSubjectFraming(request: CaptureRequest, result: TotalCaptureResult) {
    val descriptor = activeDescriptor ?: return
    val now = SystemClock.elapsedRealtimeNanos()
    val supportedMode = faceDetectModeFor(descriptor)
    val requestedMode = request.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE)
    val detecting = subjectFramingRequested && supportedMode != null && requestedMode == supportedMode
    subjectFramingTracker.configure(supportedMode != null, detecting, now)?.let { listener?.onSubjectFramingChanged(it) }
    // detecting implies requestedMode == supportedMode != null (smart cast).
    if (!detecting) return
    if (faceDetectGate.onResult(requestedMode, result.get(android.hardware.camera2.CaptureResult.STATISTICS_FACE_DETECT_MODE))) {
        subjectFramingTracker.configure(false, false, now)?.let { listener?.onSubjectFramingChanged(it) }
        reissueFaceDetect()
        return
    }
    val active = descriptor.sensorActiveArray ?: return
    val crop = result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)
        ?.let { SensorRect(it.left, it.top, it.right, it.bottom) }
        ?: SensorRect(0, 0, active.width(), active.height())
    val stream = activeLogProfile?.size ?: activeVideoProfile?.size ?: descriptor.previewSize
    val recorded = SubjectFramingGeometry.recordedArea(crop, stream.width, stream.height)
    var inFrame = false
    var anyFace = false
    var largest = -1L
    var hint = FramingEdge.NONE
    result.get(android.hardware.camera2.CaptureResult.STATISTICS_FACES)?.forEach { face ->
        val bounds = face.bounds
        val box = SensorRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        anyFace = true
        if (SubjectFramingGeometry.isInside(box, recorded)) inFrame = true
        val area = box.width.toLong() * box.height
        if (area > largest) {
            largest = area
            hint = SubjectFramingGeometry.sensorEdge(box, recorded)
        }
    }
    val edge = SubjectFramingGeometry.toContentEdge(hint, jpegOrientation(descriptor))
    subjectFramingTracker.onFrame(inFrame, edge, anyFace, now)?.let { listener?.onSubjectFramingChanged(it) }
}

internal fun Camera2PreviewEngine.disableAfLock(notify: Boolean) {
    if (afLockState == LockState.OFF && afLockFrozenDiopters == null && afLockScanToken == null) return
    if (afLockScanToken != null) tapAfRegion = null
    afLockState = LockState.OFF
    afLockFrozenDiopters = null
    afLockScanToken = null
    afLockResultReported = false
    repeatingBuilder?.setTag(null)
    if (notify) listener?.onAfLockChanged(LockState.OFF)
}

internal fun Camera2PreviewEngine.startAfLockScan(descriptor: Camera2CameraDescriptor) {
    val configured = session ?: return
    val builder = repeatingBuilder ?: return
    if (configured is CameraConstrainedHighSpeedCaptureSession) return
    val active = descriptor.sensorActiveArray ?: return
    val region = lastTapFocusForAfLock ?: run {
        val cx = active.width() / 2
        val cy = active.height() / 2
        val half = (minOf(active.width(), active.height()) / 6).coerceAtLeast(1)
        MeteringRectangle(
            (cx - half).coerceAtLeast(0),
            (cy - half).coerceAtLeast(0),
            half * 2,
            half * 2,
            MeteringRectangle.METERING_WEIGHT_MAX,
        )
    }
    imageHandler.removeCallbacks(restoreTapFocus)
    afLockState = LockState.PENDING
    afLockScanToken = ++tapFocusGeneration
    afLockResultReported = false
    listener?.onAfLockChanged(LockState.PENDING)
    try {
        builder.setTag(TapFocusRequestTag(afLockScanToken!!))
        tapAfRegion = region
        applyManualControls(builder)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        configured.captureSingleRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        configured.captureSingleRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
    } catch (failure: Exception) {
        disableAfLock(notify = true)
        listener?.onControlRejected("af-lock-scan-failed", failure.message ?: "AF lock scan failed.")
    }
}

internal fun Camera2PreviewEngine.reportAfLockResult(request: CaptureRequest, result: TotalCaptureResult) {
    if (afLockState != LockState.PENDING) return
    val token = afLockScanToken ?: return
    if ((request.tag as? TapFocusRequestTag)?.token != token || afLockResultReported) return
    when (result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)) {
        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
            afLockResultReported = true
            afLockFrozenDiopters = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)
                ?: lastReportedFocusDiopters ?: 0f
            afLockState = LockState.LOCKED
            tapAfRegion = null
            afLockScanToken = null
            listener?.onAfLockChanged(LockState.LOCKED)
            if (!reapplyRepeating()) disableAfLock(notify = true)
        }
        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
            afLockResultReported = true
            disableAfLock(notify = true)
            reapplyRepeating()
            listener?.onControlRejected("af-lock-unfocused", "AF lock failed: focus could not be confirmed.")
        }
    }
}
