/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import android.os.Build
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.ZoomAnchor
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import com.librestatic.opencinecam.service.CaptureService.CalibrationRun
import com.librestatic.opencinecam.service.CaptureService.ServiceCalibrationRig

// Part of CaptureService: lens lock routing and the lens calibration run.
// These are extensions so the service's state stays in one object; they keep the threading rules of its members.

/** The lenses [cameraId] can be locked to, widest first (the engine caches the characteristics read). */
internal fun CaptureService.lensLockOptions(cameraId: String?): List<com.librestatic.opencinecam.capture.LensLockOption> =
    cameraId?.let { id ->
        previewEngine.lensLockCandidates(id).map { com.librestatic.opencinecam.capture.LensLockOption(it.physicalId, it.anchorRatio, it.focalMm) }
    }.orEmpty()

/**
 * The lock every preview start of [cameraId] passes to the engine, or null to open the logical
 * camera: no saved or valid lock, a calibration run, constrained high speed or night photo.
 */
internal fun CaptureService.currentLensLockBinding(cameraId: String? = cameraState.value.selectedCameraId): com.librestatic.opencinecam.camera.LensLockBinding? {
    val current = cameraState.value
    return lensLockBindingFor(
        cameraId,
        lensLockOptions(cameraId),
        cameraId?.let { settings.lensLock[it] },
        calibrating = calibrationRun != null,
        highSpeed = lensLockHighSpeed(current.selectedMode, current.activeVideoProfile?.constrainedHighSpeed == true,
            current.activeLogProfile?.constrainedHighSpeed == true),
        night = nightExtensionActive(current.selectedMode),
    )
}

/** True while [cameraId]'s session opens (or runs on) a locked physical lens. */
internal fun CaptureService.lensLockedSession(cameraId: String?): Boolean = !lensLockFallback && currentLensLockBinding(cameraId) != null

/**
 * Ends a direct-physical or GPU-crop route: puts back the logical camera's zoom range and anchors
 * that a locked session replaced, and drops the switched-lens warning. A no-op when no lock was
 * being served.
 */
internal fun CaptureService.clearLensLockRoute() {
    cameraState.update { state ->
        if (state.lensLock.route == null && !state.lensLock.lensSwitchedWarning) return@update state
        if (state.lensLock.route == null) return@update state.copy(lensLock = state.lensLock.copy(lensSwitchedWarning = false))
        val range = state.descriptor?.effectiveZoomRange
        state.copy(
            lensLock = state.lensLock.copy(route = null, lensSwitchedWarning = false),
            zoomMinRatio = range?.start ?: state.zoomMinRatio,
            zoomMaxRatio = range?.endInclusive ?: state.zoomMaxRatio,
            zoomSupported = range?.let { it.start < it.endInclusive } ?: state.zoomSupported,
            opticalAnchors = state.descriptor?.opticalAnchors ?: state.opticalAnchors,
            zoomRatio = range?.let { state.zoomRatio.coerceIn(it.start, it.endInclusive) } ?: state.zoomRatio,
        )
    }
}

/** Publishes the lock options of [cameraId] and its saved lock when it is one of them. */
internal fun CaptureService.refreshLensLockState(cameraId: String? = cameraState.value.selectedCameraId) {
    val options = lensLockOptions(cameraId)
    val locked = cameraId?.let { settings.lensLock[it] }?.takeIf { id -> options.size >= 2 && options.any { it.physicalId == id } }
    cameraState.update { state ->
        val route = state.lensLock.route.takeIf { locked != null && state.lensLock.lockedPhysicalId == locked }
        val next = state.lensLock.copy(options = options, lockedPhysicalId = locked, route = route,
            lensSwitchedWarning = lensLockSwitchedWarning(state.lensLock.lensSwitchedWarning, route))
        if (next == state.lensLock) state else state.copy(lensLock = next)
    }
}

/**
 * The calibration of [cameraId] on this build when the video correction is on. Read lazily so
 * the GPU-viewfinder decision for a camera being switched to sees its own calibration.
 */
internal fun CaptureService.lensCalibrationFor(cameraId: String?): com.librestatic.opencinecam.core.model.CalibrationSet? =
    if (settings.lensCorrectionVideo) loadLensCalibration(cameraId) else null

/** Like [lensCalibrationFor] for still photos, gated by the separate "apply to photos" setting. */
internal fun CaptureService.photoLensCalibrationFor(cameraId: String?): com.librestatic.opencinecam.core.model.CalibrationSet? =
    if (settings.lensCorrectionPhotos) loadLensCalibration(cameraId) else null

/** The cached store read shared by the video and photo gates; null while a calibration run is active. */
internal fun CaptureService.loadLensCalibration(cameraId: String?): com.librestatic.opencinecam.core.model.CalibrationSet? {
    // A run measures the uncorrected signal, so nothing is applied while it is going.
    if (calibrationRun != null) return null
    if (cameraId == null) return null
    // The logical calibration describes the HAL's lens switching; a locked lens is not corrected by it.
    if (lensLockedSession(cameraId)) return null
    lensCalibrationCache?.takeIf { it.first == cameraId }?.let { return it.second }
    val set = runCatching {
        com.librestatic.opencinecam.calibration.LensCalibrationStore.forContext(this)
            .load(Build.FINGERPRINT, cameraId, com.librestatic.opencinecam.core.model.CalibrationSessionKind.LOGICAL)
    }.getOrNull()
    lensCalibrationCache = cameraId to set
    return set
}

/**
 * Hands the calibration of [cameraId] (or null) to the engine. Called on camera open and when
 * the correction settings change; the engine applies it to the current and later pipelines and
 * freezes it for the length of a take.
 */
internal fun CaptureService.refreshLensCorrection(cameraId: String? = cameraState.value.selectedCameraId) {
    previewEngine.setLensCorrection(lensCalibrationFor(cameraId), settings.lensCorrectionAlignment)
    previewEngine.setPhotoLensCorrection(photoLensCalibrationFor(cameraId), settings.lensCorrectionAlignment)
}

internal fun CaptureService.calibrationLensesOf(descriptor: Camera2CameraDescriptor?): List<com.librestatic.opencinecam.camera.calibration.CalibrationLens> =
    descriptor?.opticalAnchors.orEmpty()
        .filter { it.physicalCameraId != null }
        .distinctBy { it.physicalCameraId }
        .map { com.librestatic.opencinecam.camera.calibration.CalibrationLens(it.physicalCameraId!!, it.ratio) }

/** Logs each calibration cycle and, in debuggable builds, keeps its frames under the app's external files. */
internal fun CaptureService.logCalibrationCycle(c: com.librestatic.opencinecam.camera.calibration.CalibrationCycle) {
    val g = c.geometry
    android.util.Log.i("LensCalibration", "cycle ${c.targetId}#${c.cycle} zoom ${c.referenceZoom}/${c.targetZoom} " +
        "geometry patches=${g.patchesUsed} inliers=${g.inliers} rms=${g.residualPx} parallax=${g.parallaxSuspect} " +
        "color cells=${c.color.cellsUsed} cond=${c.color.conditionNumber} fallback=${c.color.diagonalFallback} dE=${c.color.residualDeltaEBefore}->${c.color.residualDeltaE}")
    if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    val dir = getExternalFilesDir("calibration-debug") ?: return
    runCatching {
        for ((name, frame) in listOf("ref" to c.reference, "tgt" to c.target)) {
            val bitmap = android.graphics.Bitmap.createBitmap(frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(frame.pixels))
            java.io.File(dir, "${c.targetId}-${c.cycle}-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

internal fun CaptureService.calibrationRows(progress: com.librestatic.opencinecam.camera.calibration.RunProgress) = progress.lenses.map {
    com.librestatic.opencinecam.calibration.LensCalibrationLensRow(
        physicalId = it.physicalId,
        nominalRatio = it.nominalRatio,
        status = com.librestatic.opencinecam.calibration.LensCalibrationLensStatus.valueOf(it.status.name),
        progress = it.progress,
        colorDeltaEBefore = it.colorDeltaEBefore,
        colorDeltaEAfter = it.colorDeltaEAfter,
        alignPxBefore = it.alignPxBefore,
        alignPxAfter = it.alignPxAfter,
        score = it.score,
        flags = it.flags,
    )
}

internal fun CaptureService.savedCalibrationRows(
    set: com.librestatic.opencinecam.core.model.CalibrationSet,
    anchors: List<ZoomAnchor>,
) = set.lenses.map { lens ->
    val reference = lens.physicalId == set.referencePhysicalId
    com.librestatic.opencinecam.calibration.LensCalibrationLensRow(
        physicalId = lens.physicalId,
        nominalRatio = anchors.firstOrNull { it.physicalCameraId == lens.physicalId }?.ratio ?: 0f,
        status = if (reference) com.librestatic.opencinecam.calibration.LensCalibrationLensStatus.REFERENCE
            else com.librestatic.opencinecam.calibration.LensCalibrationLensStatus.DONE,
        progress = 1f,
        colorDeltaEAfter = lens.quality.colorResidualDeltaE.takeIf { !reference },
        alignPxAfter = lens.quality.alignResidualPx.takeIf { !reference },
        score = lens.quality.score.takeIf { !reference },
        flags = lens.quality.flags,
    )
}.sortedBy { it.nominalRatio }

/**
 * Reads the saved calibration of [descriptor]'s camera for the saved-state fields and the
 * illuminant check, whatever the video correction setting. A separate read from
 * [lensCalibrationFor], which stays gated by that setting.
 */
internal fun CaptureService.refreshSavedCalibration(descriptor: Camera2CameraDescriptor?) {
    val cameraId = descriptor?.cameraId
    val set = cameraId?.let {
        runCatching {
            com.librestatic.opencinecam.calibration.LensCalibrationStore.forContext(this)
                .load(Build.FINGERPRINT, it, com.librestatic.opencinecam.core.model.CalibrationSessionKind.LOGICAL)
        }.getOrNull()
    }
    savedCalibration = set
    liveRedBlueRatio = null
    lensCalibrationState.update {
        it.copy(
            savedCreatedAtMs = set?.createdAtMs,
            savedLenses = if (set != null && descriptor != null) savedCalibrationRows(set, descriptor.opticalAnchors) else emptyList(),
            illuminantMismatch = false,
        )
    }
}

/** Compares the scene's AWB red/blue gain ratio with the saved calibration's; more than 15% apart is a mismatch. */
internal fun CaptureService.noteLiveIlluminant(gains: List<Float>) {
    if (calibrationRun != null || gains.size < 4) return
    val saved = savedCalibration?.illuminantHint?.awbGains?.takeIf { it.size >= 4 && it[3] > 0f && it[0] > 0f }
    val live = gains[0] / gains[3]
    val mismatch = if (saved == null || !live.isFinite() || live <= 0f) false else {
        val smoothed = liveRedBlueRatio?.let { it * 0.9f + live * 0.1f } ?: live
        liveRedBlueRatio = smoothed
        kotlin.math.abs(smoothed / (saved[0] / saved[3]) - 1f) > illuminantMismatchLimit
    }
    if (lensCalibrationState.value.illuminantMismatch != mismatch) lensCalibrationState.update { it.copy(illuminantMismatch = mismatch) }
}

/** The camera went away (or a reopen invalidated the measurement) while a run was going. */
internal fun CaptureService.calibrationCameraLost() {
    val run = calibrationRun ?: return
    run.lost = true
    run.runner?.abort(com.librestatic.opencinecam.camera.calibration.RunFailure.CAMERA_LOST)
}

internal fun CaptureService.calibrationWaitUntil(run: CalibrationRun, timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
    while (!run.lost && !run.cancelled && !serviceDestroyed) {
        if (condition()) return true
        if (android.os.SystemClock.elapsedRealtime() >= deadline) return false
        try { Thread.sleep(50) } catch (_: InterruptedException) { return false }
    }
    return false
}

/** Worker thread: waits for the video graph, runs the solver, then hands the outcome to main. */
internal fun CaptureService.runCalibration(
    run: CalibrationRun,
    lenses: List<com.librestatic.opencinecam.camera.calibration.CalibrationLens>,
    mode: CaptureMode,
    zoomRange: ClosedFloatingPointRange<Float>,
    openedBefore: Int,
    switched: Boolean,
) {
    var outcome: com.librestatic.opencinecam.camera.calibration.RunOutcome? = null
    try {
        if (switched) calibrationWaitUntil(run, calibrationModeSwitchGraceMs) { openingCount.get() > openedBefore }
        val ready = calibrationWaitUntil(run, calibrationPrepareTimeoutMs) {
            val s = cameraState.value
            s.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) && s.selectedMode == mode &&
                s.selectedCameraId == run.restore.cameraId && previewEngine.usesGpuViewfinder()
        }
        val queue = LinkedBlockingQueue<com.librestatic.opencinecam.camera.calibration.CalibrationSample>(calibrationQueueSize)
        val started = ready && calibrationWaitUntil(run, calibrationCaptureTimeoutMs) {
            previewEngine.startCalibrationCapture(
                sink = { sample -> while (!queue.offer(sample)) queue.poll() },
                onUnavailable = {
                    run.unsupported = true
                    run.runner?.abort(com.librestatic.opencinecam.camera.calibration.RunFailure.INTERNAL)
                },
            )
        }
        if (started && !run.lost && !run.cancelled) {
            run.sessionLocked = true
            val runner = com.librestatic.opencinecam.camera.calibration.LensCalibrationRunner(
                rig = ServiceCalibrationRig(queue),
                lenses = lenses,
                logicalId = run.restore.cameraId,
                fingerprint = Build.FINGERPRINT,
                sourcePath = previewEngine.calibrationSourcePath() ?: com.librestatic.opencinecam.core.model.CalibrationSourcePath.SDR,
                zoomRange = zoomRange,
                onProgress = { progress ->
                    if (calibrationRun === run && !run.cancelled) lensCalibrationState.update {
                        it.copy(
                            phase = com.librestatic.opencinecam.calibration.LensCalibrationPhase.valueOf(progress.phase.name),
                            referencePhysicalId = progress.referencePhysicalId,
                            lenses = calibrationRows(progress),
                        )
                    }
                },
                onCycle = ::logCalibrationCycle,
            )
            run.runner = runner
            // A flag set before the runner existed is carried over.
            if (run.lost) runner.abort(com.librestatic.opencinecam.camera.calibration.RunFailure.CAMERA_LOST)
            if (run.cancelled) runner.cancel()
            outcome = runner.run()
        }
    } catch (_: Throwable) {
        outcome = null
    } finally {
        previewEngine.stopCalibrationCapture()
    }
    mainHandler.post { finishCalibration(run, outcome) }
}

/**
 * Main thread. Ends [run]: releases the controls, restores the mode, zoom and lens correction
 * the operator had, and publishes the outcome (a result waiting for Save, a failure, or idle).
 */
internal fun CaptureService.finishCalibration(run: CalibrationRun, outcome: com.librestatic.opencinecam.camera.calibration.RunOutcome?) {
    if (calibrationRun !== run || serviceDestroyed) return
    calibrationRun = null
    run.runner = null
    cameraState.update { it.copy(lensCalibrationRunning = false) }
    val restore = run.restore
    // The camera may be gone; the mode and zoom are still put back, without a reopen then.
    // A saved lens lock was set aside for the run; it returns with one restart.
    val relock = !run.lost && currentLensLockBinding() != null
    if (cameraState.value.selectedMode != restore.mode) localBinder.selectMode(restore.mode, reopen = !run.lost && !relock)
    if (relock) attachedPreviewSurface?.takeIf { it.isValid }?.let { localBinder.attachPreview(it, attachedPreviewRotationDegrees) }
    localBinder.setZoomRatio(restore.zoom)
    refreshLensCorrection()
    val success = outcome as? com.librestatic.opencinecam.camera.calibration.RunOutcome.Success
    val failure = outcome as? com.librestatic.opencinecam.camera.calibration.RunOutcome.Failure
    (success?.progress ?: failure?.progress)?.lenses?.forEach {
        android.util.Log.i("LensCalibration", "lens ${it.physicalId} ${it.status} score=${it.score} dE=${it.colorDeltaEBefore}->${it.colorDeltaEAfter} " +
            "px=${it.alignPxBefore}->${it.alignPxAfter} flags=${it.flags}")
    }
    failure?.let { android.util.Log.i("LensCalibration", "run failed: ${it.failure}") }
    when {
        run.cancelled -> lensCalibrationState.update {
            it.copy(phase = com.librestatic.opencinecam.calibration.LensCalibrationPhase.IDLE, referencePhysicalId = null, lenses = emptyList(), failure = null)
        }
        success != null && !run.lost -> {
            calibrationPending = success.set
            lensCalibrationState.update {
                it.copy(
                    phase = com.librestatic.opencinecam.calibration.LensCalibrationPhase.RESULT,
                    logicalCameraId = restore.cameraId,
                    referencePhysicalId = success.set.referencePhysicalId,
                    lenses = calibrationRows(success.progress),
                    failure = null,
                )
            }
        }
        else -> {
            val reason = when {
                run.lost -> com.librestatic.opencinecam.calibration.LensCalibrationFailure.CAMERA_LOST
                run.unsupported || outcome == null -> com.librestatic.opencinecam.calibration.LensCalibrationFailure.UNSUPPORTED_MODE
                failure != null -> com.librestatic.opencinecam.calibration.LensCalibrationFailure.valueOf(failure.failure.name)
                else -> com.librestatic.opencinecam.calibration.LensCalibrationFailure.INTERNAL
            }
            lensCalibrationState.update {
                it.copy(
                    phase = com.librestatic.opencinecam.calibration.LensCalibrationPhase.FAILED,
                    logicalCameraId = restore.cameraId,
                    lenses = failure?.let { f -> calibrationRows(f.progress) } ?: it.lenses,
                    failure = reason,
                )
            }
        }
    }
}
