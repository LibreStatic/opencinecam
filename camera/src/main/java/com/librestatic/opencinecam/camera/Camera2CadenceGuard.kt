/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.QcomPriorityKeys
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.CADENCE_TAG
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.CADENCE_TRACE_TAG
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.TAG

// Part of Camera2PreviewEngine: the cadence guard behind FPS LOCK, which holds the shutter at 1/fps in low light.
// These are extensions so the engine's state stays in one object; they run on the engine's executors like its members.

/**
 * Debug builds only: `adb shell setprop debug.opencinecam.cadence.trace 1` logs, for 1.5 s after
 * each lens switch under the emulated hold, every capture result (lens, ISO, boost) and every
 * analysed luma under the `CadenceTrace` tag, to measure the lenses' real ISO ratio offline.
 */
internal fun Camera2PreviewEngine.cadenceTraceEnabled(): Boolean = debugProperty("debug.opencinecam.cadence.trace").let { it == "1" || it == "true" }

internal fun Camera2PreviewEngine.cadenceTrace(line: () -> String) {
    if (cadenceTraceUntilNs != 0L && SystemClock.elapsedRealtimeNanos() < cadenceTraceUntilNs) Log.d(CADENCE_TRACE_TAG, line())
}

/** True when the held shutter would be sent now: wanted, engaged and every precondition still true. */
internal fun Camera2PreviewEngine.cadenceHoldActive(): Boolean = cadenceEngaged && cadenceGuardEligible()

internal fun Camera2PreviewEngine.cadenceGuardEligible(): Boolean {
    val descriptor = activeDescriptor ?: return false
    val videoGraph = logPreviewEnabled || (gpuPreviewEnabled && !gpuPhotoPreviewEnabled) || activeVideoProfile != null
    val autoExposure = requestedIso == null && requestedExposureNs == null &&
        (professionalExposure?.mode ?: ExposureMode.AUTO) == ExposureMode.AUTO
    return fpsLockRequested && descriptor.fpsLockSupported && videoGraph && autoExposure && !aeLockActive &&
        !dynamicFpsOn && !isHighSpeedSession() && !timelapseTake && nightSession == null
}

/** Feeds the cadence guard from a repeating-request result; re-issues the request when the verdict flips. */
internal fun Camera2PreviewEngine.driveCadenceGuard(result: TotalCaptureResult, sensorTimestampNs: Long?) {
    val aeMode = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_MODE)
    lastResultAeMode = aeMode
    noteCadenceLens(result)
    if (cadenceEngaged && cadenceHeldIso != null && !cadenceSawManual && aeMode == CaptureRequest.CONTROL_AE_MODE_OFF) {
        // The first result that really is manual: the AE frames still in the analysis pipe are AE's final state.
        cadenceSawManual = true
        cadenceManualFrames = 0
        val last = cadenceAeLumas.toList().sorted()
        if (last.isNotEmpty()) cadenceIsoLoop.setTarget(last[last.size / 2])
        Log.i(CADENCE_TAG, "hidden gain: first manual result; target=${cadenceIsoLoop.target?.let { "%.4f".format(it) }} from ${last.size} AE frames")
    }
    if (!cadenceGuardEligible()) {
        if (cadenceEngaged) setCadenceEngaged(false, null, null)
        cadenceGuard.reset()
        updateAnalysisBurst()
        noteCadenceFrameGain(sensorTimestampNs, null)
        return
    }
    if (cadenceEngaged && cadenceHeldIso != null) {
        // A jumped ISO counts from the first result that reports it (before this result can issue another).
        val sensorIso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
        val resultBoost = result.get(android.hardware.camera2.CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
        cadenceLastResultIso = "sensor=$sensorIso boost=$resultBoost"
        cadenceTrace { "result ts=$sensorTimestampNs lens=$cadenceLensId sensor=$sensorIso boost=$resultBoost " +
            "exp=${result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)} held=$cadenceHeldIso" }
        val effectiveIso = sensorIso?.let { (it.toLong() * resultBoost / 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        if (effectiveIso != null && sensorTimestampNs != null && cadenceIsoLoop.noteResult(sensorTimestampNs, effectiveIso)) {
            Log.i(CADENCE_TAG, "jumped iso in effect ${(SystemClock.elapsedRealtimeNanos() - cadenceLensSwitchAtNs) / 1_000_000} ms after the lens switch: $cadenceLastResultIso")
        }
        noteCadenceFrameGain(sensorTimestampNs, effectiveIso.takeIf { cadenceHoldEmulated() })
        // Walk the planned ISO ramp: one small step per due capture result.
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (cadenceExposureRamp.active) {
            // Exposure handoff: AE's own exposure walks down to 1/fps with the ISO compensating.
            cadenceExposureRamp.tick(nowNs)?.let { step ->
                cadenceHeldIso = step.iso
                if (step.done) {
                    cadenceIsoLoop.resume(step.iso, nowNs)
                    Log.i(CADENCE_TAG, "exposure ramp complete: exposure=${"%.2f ms".format(step.exposureNs / 1e6)} iso=${step.iso} " +
                        "after ${(nowNs - cadenceRampStartedNs) / 1_000_000} ms")
                    listener?.onCadenceGuardChanged(true)
                }
                reapplyRepeating()
            }
        } else cadenceIsoLoop.tick(nowNs)?.let { cadenceHeldIso = it; reapplyRepeating() }
    } else noteCadenceFrameGain(sensorTimestampNs, null)
    if (cadenceFaulted || sensorTimestampNs == null) return
    val frameDuration = result.get(android.hardware.camera2.CaptureResult.SENSOR_FRAME_DURATION)
    var iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
    if (cadenceEngaged && cadenceHoldPath() == CadenceHoldPath.QCOM) checkQcomHold(result)
    if (cadenceEngaged && iso != null) {
        // A hold may raise the ISO through the post-raw boost: judge the effective one.
        val boost = result.get(android.hardware.camera2.CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
        iso = (iso.toLong() * boost / 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    // The guard does not judge frames while the exposure ramp is still walking to 1/fps.
    val verdict = if (cadenceExposureRamp.active) cadenceEngaged
        else cadenceGuard.update(sensorTimestampNs, requestedTargetFps, frameDuration, iso,
            result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE) == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED)
    if (verdict != cadenceEngaged) {
        setCadenceEngaged(verdict, frameDuration, iso, result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
            result.get(android.hardware.camera2.CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100)
    }
    updateAnalysisBurst()
}

/**
 * Tells the GPU pipeline how much to brighten or darken this result's frame (1 without
 * [effectiveIso]): after a lens switch the frames still in the HAL's pipeline carry the old
 * lens' ISO, and [CadenceIsoLoop.frameGain] bridges them to the ISO the jump brings in.
 */
internal fun Camera2PreviewEngine.noteCadenceFrameGain(sensorTimestampNs: Long?, effectiveIso: Int?) {
    val pipeline = logPipeline ?: return
    if (sensorTimestampNs == null || !fpsLockRequested) return
    val gain = effectiveIso?.let { cadenceIsoLoop.frameGain(it) } ?: 1.0
    pipeline.noteExposureGainForTimestamp(sensorTimestampNs, gain.toFloat(), waiting = cadenceEngaged && cadenceHoldEmulated())
}

/** Native priority, else the Qualcomm exposure priority, else the emulated hold (manual exposure and a software ISO). */
internal fun Camera2PreviewEngine.cadenceHoldPath(): CadenceHoldPath? {
    val descriptor = activeDescriptor ?: return null
    return cadenceHoldPath(Build.VERSION.SDK_INT, descriptor.exposureCapabilities, cadenceHeldExposureNs(),
        descriptor.cameraId in qcomExposurePriorityUnusable)
}

internal fun Camera2PreviewEngine.cadenceHoldEmulated(): Boolean = cadenceHoldPath() == CadenceHoldPath.EMULATED

internal fun Camera2PreviewEngine.qcomPriorityKeys(): QcomPriorityKeys? {
    val id = activeDescriptor?.cameraId ?: return null
    return qcomPriorityKeyCache.getOrPut(id) {
        val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
        val gate = characteristics != null && QcomExposurePriority.parseRange(VendorKeys.staticLongs(characteristics, QcomExposurePriority.RANGE_KEY)) != null
        val select = characteristics?.let { VendorKeys.requestGated(it, id, QcomExposurePriority.SELECT_KEY, gate, Int::class.javaObjectType) }
        val use = characteristics?.let { VendorKeys.requestGated(it, id, QcomExposurePriority.USE_KEY, gate, Long::class.javaObjectType) }
        java.util.Optional.ofNullable(if (select != null && use != null) QcomPriorityKeys(select, use) else null)
    }.orElse(null)
}

/** Qualcomm hold self-check: log the result at 300 ms, and fall back to the emulated hold if the HAL ignored the request. */
internal fun Camera2PreviewEngine.checkQcomHold(result: TotalCaptureResult) {
    val sinceMs = (SystemClock.elapsedRealtimeNanos() - cadenceEngagedAtNs) / 1_000_000
    val exposure = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
    val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
    if (sinceMs >= 300 && !cadenceQcomLogged) {
        cadenceQcomLogged = true
        Log.i(CADENCE_TAG, "qcom hold at ${sinceMs} ms: exposure=${exposure?.let { "%.2f ms".format(it / 1e6) }} iso=$iso " +
            "boost=${result.get(android.hardware.camera2.CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)}")
    }
    if (sinceMs >= 600 && qcomHoldIgnored(exposure, cadenceHeldExposureNs())) {
        val id = activeDescriptor?.cameraId ?: return
        Log.w(CADENCE_TAG, "qcom exposure priority ignored by the HAL (exposure=${exposure?.let { "%.2f ms".format(it / 1e6) }} after $sinceMs ms): " +
            "using the emulated hold for camera $id")
        qcomExposurePriorityUnusable.add(id)
        setCadenceEngaged(false, null, null)
        cadenceGuard.reset()
    }
}

/** The exposure in force for the hold: the ramp's while it walks down, else 1/fps. */
internal fun Camera2PreviewEngine.cadenceHoldExposureNs(): Long =
    if (cadenceHoldEmulated() && cadenceExposureRamp.active) cadenceExposureRamp.exposureNs else cadenceHeldExposureNs()

internal fun Camera2PreviewEngine.cadenceHeldExposureNs(): Long {
    val frameDurationNs = 1_000_000_000L / requestedTargetFps.coerceAtLeast(1)
    val range = activeDescriptor?.exposureCapabilities?.timeRangeNs
    return (frameDurationNs - 100_000L).let { if (range != null) it.coerceIn(range.first, range.last) else it }
}

/**
 * Follows the physical lens of the logical camera. With AE off a switch hands the new lens the old
 * one's ISO, so the loop corrects it at once (see [CadenceIsoLoop.lensChanged]) and the guard forgets
 * its ISO reference (see [CadenceGuard.lensChanged]); AE frames from the old lens no longer describe
 * the scene, and a pending hidden-gain measurement would mix two lenses.
 */
internal fun Camera2PreviewEngine.noteCadenceLens(result: TotalCaptureResult) {
    val lens = activeDescriptor?.lensLock?.physicalId ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
    } else null
    val previous = cadenceLensId
    if (lens == null || lens == previous) return
    cadenceLensId = lens
    if (previous == null) return
    cadenceAeLumas.clear()
    if (cadenceEngaged) cadenceGuard.lensChanged(SystemClock.elapsedRealtimeNanos())
    if (!cadenceEngaged || cadenceHeldIso == null) return
    if (!cadenceGainMeasured) {
        cadenceGainMeasured = true
        Log.i(CADENCE_TAG, "hidden gain: not learned, the lens switched $previous -> $lens during the handoff")
    }
    if (!cadenceHoldEmulated() || !cadenceSawManual || cadenceExposureRamp.active) return
    val cameraId = activeDescriptor?.cameraId ?: return
    val from = cadenceMeasuredLensId ?: previous
    val expected = processLensIsoRatios.ratio(cameraId, from, lens)
    cadenceLensSwitchAtNs = SystemClock.elapsedRealtimeNanos()
    cadenceTraceUntilNs = if (cadenceTraceEnabled()) cadenceLensSwitchAtNs + 1_500_000_000L else 0L
    cadenceIsoLoop.lensChanged(cadenceLensSwitchAtNs, expected, result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP))
    // The loop's frames on target belong to the lens it was last told about; a switch it missed
    // (during the handoff or the exposure ramp) leaves them on another lens, so nothing is learned.
    cadenceRatioFromLensId = cadenceLoopLensId.takeIf { it == previous }
    cadenceLoopLensId = lens
    Log.i(CADENCE_TAG, "lens switch $previous -> $lens: iso ratio ${expected?.let { "%.2f".format(it) } ?: "unknown"} from $from")
    updateAnalysisBurst()
}

/** Analysis frame (camera executor, before the UI sees it): learns the luma, and steers the held ISO. */
internal fun Camera2PreviewEngine.observeCadenceLuma(analysis: Camera2Analysis, producer: String) {
    // Always the frame's own histogram: the ISP one, when present, is a different domain than the target.
    val luma = CadenceIsoLoop.meanLuma(analysis.histogram) ?: return
    // One producer only: the GPU readback and the YUV reader are in different luma domains.
    if (!cadenceLumaProducerAccepted(logPipeline != null, producer)) return
    cadenceTrace { "luma ts=${analysis.sensorTimestampNs} producer=$producer luma=${"%.4f".format(luma)} " +
        "hist=${analysis.histogram.joinToString(",") { "%.4f".format(it) }}" }
    // AE's own final brightness: the last frames analysed while the results still said AE was on.
    if (lastResultAeMode != null && lastResultAeMode != CaptureRequest.CONTROL_AE_MODE_OFF && cadenceGuardEligible() && cadenceHoldEmulated()) {
        cadenceAeLumas.addLast(luma)
        while (cadenceAeLumas.size > 3) cadenceAeLumas.removeFirst()
    }
    if (!cadenceEngaged || cadenceHeldIso == null) return
    if (!cadenceSawManual) return
    cadenceManualFrames++
    // The first manual frame plus one more of margin: compare it with AE's brightness to learn the hidden gain.
    if (!cadenceGainMeasured && cadenceManualFrames >= 2) {
        cadenceGainMeasured = true
        val goal = cadenceIsoLoop.target
        val gainKey = cadenceGainKey()
        if (goal != null && gainKey != null && cadenceIsoLoop.targetSource == "ae") {
            val old = processHiddenAeGain.gain(gainKey)
            val updated = processHiddenAeGain.update(gainKey, goal, luma)
            Log.i(CADENCE_TAG, "hidden gain: target=${"%.4f".format(goal)} manual luma=${"%.4f".format(luma)} G ${"%.2f".format(old)} -> ${"%.2f".format(updated)}")
        }
    }
    if (!cadenceFirstFrameLogged && cadenceIsoLoop.isSettled(SystemClock.elapsedRealtimeNanos())) {
        cadenceFirstFrameLogged = true
        Log.i(CADENCE_TAG, "first settled held frame: luma=${"%.4f".format(luma)} target=${cadenceIsoLoop.target?.let { "%.4f".format(it) }}")
    }
    // During the exposure ramp the loop only learns a target if none was measured; it plans once the ramp has landed.
    if (cadenceExposureRamp.active && cadenceIsoLoop.hasTarget) return
    if (!cadenceHoldActive() || analysisSuspended) return
    val descriptor = activeDescriptor
    val evDelta = (requestedAeCompensationIndex ?: 0) - cadenceEvIndexAtEngage
    val scale = if (descriptor != null && descriptor.aeCompensationStep > 0f && evDelta != 0)
        CadenceIsoLoop.evScale(descriptor.aeCompensationStep, evDelta) else 1.0
    val lensPending = cadenceIsoLoop.lensPending
    val awaiting = cadenceIsoLoop.awaitingEffect
    val measured = cadenceIsoLoop.update(SystemClock.elapsedRealtimeNanos(), luma, scale, analysis.sensorTimestampNs)
    if (awaiting && !cadenceIsoLoop.awaitingEffect) {
        Log.w(CADENCE_TAG, "jumped iso ${cadenceHeldIso} never reported within ${CadenceIsoLoop.EFFECT_TIMEOUT_NS / 1_000_000} ms; last result $cadenceLastResultIso")
    }
    if (lensPending && !cadenceIsoLoop.lensPending) {
        Log.i(CADENCE_TAG, "lens $cadenceLensId first frame ${(SystemClock.elapsedRealtimeNanos() - cadenceLensSwitchAtNs) / 1_000_000} ms after the switch: " +
            "luma=${"%.4f".format(luma)} iso ${cadenceHeldIso}")
        cadenceMeasuredLensId = cadenceLensId
    } else if (!lensPending) cadenceMeasuredLensId = cadenceLensId
    if (measured != null) {
        // Both lenses held the target: remember the ratio between the ISOs that did.
        val lens = cadenceLoopLensId
        val from = cadenceRatioFromLensId
        val cameraId = descriptor?.cameraId
        cadenceRatioFromLensId = null
        if (lens != null && from != null && cameraId != null) {
            val remembered = processLensIsoRatios.update(cameraId, from, lens, measured)
            Log.i(CADENCE_TAG, "lens $lens on target ${(SystemClock.elapsedRealtimeNanos() - cadenceLensSwitchAtNs) / 1_000_000} ms after the switch: " +
                "luma=${"%.4f".format(luma)} iso ratio from $from ${"%.2f".format(measured)} " +
                "(remembered ${remembered?.let { "%.2f".format(it) }})")
        }
    }
    updateAnalysisBurst()
}

/** The hidden gain differs per physical lens: keyed by the logical camera and the lens serving it, when known. */
internal fun Camera2PreviewEngine.cadenceGainKey(): String? {
    val cameraId = activeDescriptor?.cameraId ?: return null
    return cadenceLensId?.let { "$cameraId/$it" } ?: cameraId
}

/** Turns the fast analysis on around the handoff of the emulated hold, off otherwise. */
internal fun Camera2PreviewEngine.updateAnalysisBurst() {
    val wanted = cadenceBurstWanted(
        emulatedEligible = cadenceGuardEligible() && cadenceHoldEmulated(),
        suspended = analysisSuspended,
        engaged = cadenceEngaged && cadenceHeldIso != null,
        guardPending = cadenceGuard.pending,
        sinceEngageNs = SystemClock.elapsedRealtimeNanos() - cadenceEngagedAtNs,
        loopLearning = !cadenceIsoLoop.hasTarget,
        loopRamping = cadenceIsoLoop.ramping,
        lensPending = cadenceIsoLoop.lensPending,
    )
    if (wanted == analysisBurst) return
    analysisBurst = wanted
    logPipeline?.setAnalysisBurst(wanted)
}

internal fun Camera2PreviewEngine.setCadenceEngaged(engaged: Boolean, frameDurationNs: Long?, iso: Int?, exposureNs: Long? = null, aeBoost: Int = 100) {
    if (cadenceEngaged == engaged) return
    if (engaged) {
        val path = cadenceHoldPath()
        Log.i(CADENCE_TAG, "hold path: ${path?.name?.lowercase() ?: "none"}")
        cadenceEngagedAtNs = SystemClock.elapsedRealtimeNanos()
        cadenceQcomLogged = path != CadenceHoldPath.QCOM
    }
    if (engaged && cadenceHoldEmulated()) {
        val caps = activeDescriptor?.exposureCapabilities ?: return
        val range = CadenceIsoLoop.extendedRange(caps.isoRange ?: return, caps.postRawBoostRange)
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val aeIso = iso ?: return
        val heldExposure = cadenceHeldExposureNs()
        val timeRange = caps.timeRangeNs
        val aeExposure = exposureNs?.let { if (timeRange != null) it.coerceIn(timeRange.first, timeRange.last) else it }
        val rawEffectiveIso = (aeIso.toLong() * aeBoost.coerceAtLeast(100) / 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // AE ON applies an ISP gain AE OFF loses: start with that much more ISO.
        val hiddenGain = cadenceGainKey()?.let { processHiddenAeGain.gain(it) } ?: HiddenAeGain.HIDDEN_AE_GAIN_PRIOR
        val aeEffectiveIso = HiddenAeGain.startIso(rawEffectiveIso, hiddenGain, range)
        // Start where AE is (its exposure and total gain), so the first held frames match it by construction.
        val ramping = aeExposure != null && cadenceExposureRamp.start(aeExposure, aeEffectiveIso, heldExposure, range, nowNs)
        val initial = if (ramping) aeEffectiveIso else HiddenAeGain.startIso(CadenceIsoLoop.initialIso(aeIso, exposureNs, heldExposure, range, aeBoost), hiddenGain, range)
        cadenceRampStartedNs = nowNs
        cadenceEngagedAtNs = nowNs
        cadenceEvIndexAtEngage = requestedAeCompensationIndex ?: 0
        cadenceIsoLoop.engage(initial, range, nowNs)
        cadenceFirstFrameLogged = false
        cadenceSawManual = false
        cadenceManualFrames = 0
        cadenceGainMeasured = false
        cadenceMeasuredLensId = cadenceLensId
        cadenceLoopLensId = cadenceLensId
        cadenceRatioFromLensId = null
        Log.i(CADENCE_TAG, "handoff: lens=$cadenceLensId aeIso=$iso aeBoost=$aeBoost hiddenGain=${"%.2f".format(hiddenGain)} aeExposure=${exposureNs?.let { "%.2f ms".format(it / 1e6) }} " +
            "heldExposure=${"%.2f ms".format(heldExposure / 1e6)} startExposure=${"%.2f ms".format(cadenceHoldExposureNs() / 1e6)} startIso=$initial " +
            "exposureRamp=${if (ramping) "${cadenceExposureRamp.totalSteps} steps ~400 ms" else "none"}")
        cadenceHeldIso = initial
    } else if (!engaged) {
        cadenceExposureRamp.stop()
        cadenceIsoLoop.release()
        cadenceAeLumas.clear()
        cadenceHeldIso = null
    }
    cadenceEngaged = engaged
    Log.i(CADENCE_TAG, (if (engaged) "engaged" else "released") +
        ": frameDuration=${frameDurationNs?.let { "%.2f ms".format(it / 1e6) }} iso=$iso target=${requestedTargetFps}fps")
    // The hold delivers the target fps only once the exposure ramp has landed (reported then).
    if (!engaged || !cadenceExposureRamp.active) listener?.onCadenceGuardChanged(engaged)
    if (!reapplyRepeating()) {
        // The session kept its previous request: report what is really in force and stop trying.
        cadenceEngaged = !engaged
        if (engaged) { cadenceExposureRamp.stop(); cadenceIsoLoop.release(); cadenceHeldIso = null }
        cadenceFaulted = true
        cadenceGuard.reset()
        listener?.onCadenceGuardChanged(cadenceEngaged)
    }
}

/**
 * cameraExecutor: feeds the take's [SustainedCadenceGuard]. Constrained high-speed sessions
 * (results arrive per batch) and time-lapse takes (frames are taken at the interval, not the
 * stream rate) are not judged.
 */
internal fun Camera2PreviewEngine.checkSustainedCadence(sensorTimestamp: Long?, droppedFrames: Long, stats: CaptureAuditStats) {
    val (policy, guard) = cadenceLossGuard ?: return
    if (sensorTimestamp == null || sensorTimestamp < 0 || guard.tripped || isHighSpeedSession() || timelapseTake) return
    if (guard.observe(sensorTimestamp, droppedFrames)) {
        val action = cadenceLossAction(policy)
        Log.w(TAG, "Sustained cadence loss ($action): $stats")
        listener?.onSustainedCadenceLoss(action, "droppedFrames=${stats.droppedFrames} results=${stats.results}")
    }
}

/**
 * FPS LOCK engaged: AE stays on and still picks the ISO, but the shutter is held a hair under the
 * frame time so the HAL cannot stretch the frame. Compensation and antibanding stay as set above.
 */
/**
 * The sensor part of the held ISO stops at the lowest sensor maximum of the logical camera's
 * lenses, the boost making up the rest. The HAL hands the next lens the request as it is, and a
 * lens clamps what its sensor cannot reach (the Razr Fold's ultrawide and tele stop at 6400, the
 * 1x at the logical camera's 12800), so a split one lens cannot follow would darken the frames
 * after every switch to it.
 */
internal fun Camera2PreviewEngine.cadenceSensorIsoRange(range: IntRange): IntRange {
    val descriptor = activeDescriptor ?: return range
    if (descriptor.lensLock != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return range
    val top = cadenceSensorTopCache.getOrPut(descriptor.cameraId) {
        runCatching {
            manager.getCameraCharacteristics(descriptor.cameraId).physicalCameraIds.mapNotNull { id ->
                runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper }.getOrNull()
            }
        }.getOrDefault(emptyList()).let { CadenceIsoLoop.sharedSensorTop(range.last, it) }
    }
    return range.first..top
}

internal fun Camera2PreviewEngine.applyCadenceHold(builder: CaptureRequest.Builder) {
    val heldIso = cadenceHeldIso
    val exposureNs = cadenceHoldExposureNs()
    val frameDurationNs = CadenceExposureRamp.frameDurationNs(exposureNs, requestedTargetFps)
    if (cadenceHoldEmulated() && heldIso != null) {
        // Manual exposure at 1/fps; CONTROL_MODE stays whatever the auto branch set.
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        val caps = activeDescriptor?.exposureCapabilities
        val sensorRange = caps?.isoRange?.let { cadenceSensorIsoRange(it) }
        if (sensorRange != null) {
            // Above the sensor maximum the rest comes from the post-raw boost, where the camera has one.
            val (sensorIso, boost) = CadenceIsoLoop.split(heldIso, sensorRange, caps.postRawBoostRange)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, sensorIso)
            if (boost != null) builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, boost)
        } else builder.set(CaptureRequest.SENSOR_SENSITIVITY, heldIso)
    } else if (cadenceHoldPath() == CadenceHoldPath.QCOM && qcomPriorityKeys() != null) {
        // AE stays on and keeps this exposure; the HAL steers the ISO and its ISP gain. No manual sensor values.
        val keys = qcomPriorityKeys()!!
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(keys.select, QcomExposurePriority.SELECT_EXPOSURE_PRIORITY)
        builder.set(keys.use, cadenceHeldExposureNs())
        return
    } else {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        if (Build.VERSION.SDK_INT >= 36) {
            builder.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY)
        }
    }
    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
}

internal fun Camera2PreviewEngine.updateEffectiveFps(sensorTimestampNs: Long?) {
    val previous = previousSensorTimestampNs
    previousSensorTimestampNs = sensorTimestampNs
    if (previous == null || sensorTimestampNs == null) return
    val delta = sensorTimestampNs - previous
    if (delta !in 1_000_000L..1_000_000_000L) return
    val sample = 1_000_000_000.0 / delta.toDouble()
    effectiveFps = effectiveFps?.let { it * 0.8 + sample * 0.2 } ?: sample
}
