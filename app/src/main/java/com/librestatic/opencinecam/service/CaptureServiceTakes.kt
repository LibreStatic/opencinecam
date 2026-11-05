/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.resolve

import com.librestatic.opencinecam.settings.projectLabel
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.diagnostics.DeviceQuirksProvider
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.RecordingGeometry
import com.librestatic.opencinecam.settings.CaptureNameSnapshot
import com.librestatic.opencinecam.settings.ProductionSlateSettings
import com.librestatic.opencinecam.settings.afterSavedProductionSlate
import com.librestatic.opencinecam.storage.MotionLogRecorder
import com.librestatic.opencinecam.storage.MotionLogTarget
import com.librestatic.opencinecam.storage.VideoOutput
import com.librestatic.opencinecam.storage.IncompleteTake
import com.librestatic.opencinecam.storage.ClipIntent
import com.librestatic.opencinecam.storage.ClipSidecarProvider
import com.librestatic.opencinecam.storage.clipSidecarProvider
import com.librestatic.opencinecam.storage.finalizePreparedRecordingTake
import com.librestatic.opencinecam.transfers.CaptureTransferReservation
import com.librestatic.opencinecam.transfers.WebDavTransferRuntime
import com.librestatic.opencinecam.storage.AudioRetirementGate
import com.librestatic.opencinecam.transfers.CaptureTransferPublication
import com.librestatic.opencinecam.storage.AudioSidecarRecorder
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import com.librestatic.opencinecam.camera.monitorLutIdentity
import com.librestatic.opencinecam.settings.SettingsRepositories
import kotlinx.coroutines.flow.update
import com.librestatic.opencinecam.service.CaptureService.DetachedTake

// Part of CaptureService: take ownership, recovery guards, interrupted-take salvage and transfer publication.
// These are extensions so the service's state stays in one object; they keep the threading rules of its members.

internal fun CaptureService.reserveRecordingRecoveryGuard(output: VideoOutput) {
    val guard = output.recoveryGroup.retainNativeWriter()
    if (recordingRecoveryGuards.putIfAbsent(output, guard) != null) {
        guard.close()
        error("Recording output already has a native guard")
    }
}

internal fun CaptureService.releaseRecordingRecoveryGuard(output: VideoOutput?) {
    if (output == null) return
    recordingRecoveryGuards.remove(output)?.close()
}

internal fun CaptureService.retireRecordingRecoveryGuards(receipt: java.util.concurrent.CompletableFuture<Unit>) {
    val guards = recordingRecoveryGuards.entries.map { it.key to it.value }
    receipt.whenComplete { _, failure ->
        if (failure == null) guards.forEach { (output, guard) ->
            if (recordingRecoveryGuards.remove(output, guard)) runCatching { guard.close() }
                .onFailure { android.util.Log.e("RecordingRecovery", "Retired take cleanup retained for retry", it) }
        }
    }
}

internal fun CaptureService.transferCoordinator(): WebDavTransferRuntime = transferRuntime
    ?: WebDavTransferRuntime.get(this).also { transferRuntime = it }

/** An error/timeout is not native retirement; callers choose a proven terminal boundary. */
internal fun CaptureService.releaseTransferCapture(reservation: CaptureTransferReservation?, onReleased: (() -> Unit)? = null) {
    if (reservation == null) return
    AudioRetirementGate.whenIdle().whenComplete { _, failure ->
        if (failure == null && transferCapture.compareAndSet(reservation, null)) {
            val intent = recordingLutIntent.get()
            if (intent?.owner === reservation && recordingLutIntent.compareAndSet(intent, null)) mainHandler.post {
                if (!serviceDestroyed && recordingLutIntent.get() == null) {
                    cameraState.update { it.copy(recordingLutStatus = waitingRecordingLutStatus(), recordingLutSelectionPending = false) }
                    localBinder.applySettings(SettingsRepositories.get(this@releaseTransferCapture).states.value)
                }
            }
            reservation.close()
            onReleased?.invoke()
        }
    }
}

internal fun CaptureService.dispatchTransferCapture() {
    transferCaptureDispatched = true
    dispatchedTake.dispatch(requireNotNull(transferCapture.get()) { "Recording reservation missing" })
    submitCaptureState(stateCommands.takeDispatched())
}

/**
 * A dispatched start that never reached RECORDING releases its exact admission once the
 * engine proves native retirement. A failed receipt (preparation still retiring) keeps it
 * held for onRecordingStopped, a later failure/recovery receipt, or engine closure.
 */
internal fun CaptureService.releaseFailedTakeStart(receipt: CompletableFuture<Unit>,
    dispatch: DispatchedTakeAdmission.Dispatch<CaptureTransferReservation>? = dispatchedTake.pendingStart()) {
    if (dispatch == null) return
    receipt.whenComplete { _, failure ->
        if (failure != null) return@whenComplete
        dispatchedTake.claimFailedStart(dispatch)?.let { reservation ->
            releaseTransferCapture(reservation) { startPreviewAudioMonitorIfEligible() }
        }
    }
}

/**
 * Opt-in motion log for plain video, LOG and HLG takes (never high speed, slow motion or time-lapse).
 * Silent by design: no gyroscope or no sensor access just means no log.
 */
internal fun CaptureService.attachMotionLog(output: VideoOutput, wanted: Boolean, descriptor: Camera2CameraDescriptor?, geometry: RecordingGeometry?) {
    if (!wanted || descriptor == null || geometry == null) return
    MotionLogRecorder.start(this, MotionLogTarget(
        videoFileName = output.displayName,
        sensorOrientationDegrees = descriptor.sensorOrientation,
        frontFacing = descriptor.lensFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT,
        storedPixelRotationDegrees = geometry.pixelRotationDegrees,
        timestampsSynced = descriptor.timestampSourceRealtime,
        rollingShutterSkewNs = latestRollingShutterSkewNs,
    ))?.let(output::attachMotionRecorder)
}

/** Engine callbacks and main detach the take-owned outputs exactly once; the caller finishes them. */
internal fun CaptureService.detachTake(): DetachedTake = synchronized(takeOwnershipLock) {
    DetachedTake(videoOutput, audioSidecarRecorder, activeTransferPublication, audioStartFailure, activeRecordingGeometry).also {
        videoOutput = null
        audioSidecarRecorder = null
        activeTransferPublication = null
        audioStartFailure = null
        activeRecordingGeometry = null
    }
}

/** Stable failure codes pass through as incomplete reasons; anything else is an encoder failure. */
internal fun CaptureService.failureReason(code: String): String =
    code.takeIf { it.length in 1..48 && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } } ?: IncompleteTake.ENCODER_FAILED

/** The operator's request, frozen at the take start from the settings and the capture state. */
internal fun CaptureService.clipIntentSnapshot(): ClipIntent {
    val state = cameraState.value
    val separateAudio = settings.audioOutputFormat != AudioOutputFormat.AAC_MP4
    return ClipIntent(
        recordingPolicy = settings.recordingPolicy.name,
        captureMode = state.selectedMode.name,
        requestedWidth = state.targetVideoWidth,
        requestedHeight = state.targetVideoHeight,
        requestedFps = state.targetFps,
        projectRate = state.recordingProjectRate?.projectLabel(),
        videoCodecPreference = settings.videoCodec.name,
        videoBitrateMbps = settings.videoBitrateMbps,
        audioEnabled = settings.audioEnabled,
        audioFormat = if (settings.audioEnabled) settings.audioOutputFormat.name else null,
        audioSampleRateHz = settings.audioSampleRateHz.takeIf { settings.audioEnabled && separateAudio },
        audioBitDepth = settings.audioBitDepth.bits.takeIf { settings.audioEnabled && separateAudio },
        audioChannels = settings.audioChannels.takeIf { settings.audioEnabled && separateAudio },
        lutSelectionId = monitorLutIdentity(recordingLutIntent.get()?.lut),
    )
}

/** The device profile the camera resolved to; never fails a take. */
internal fun CaptureService.clipDeviceProfile(cameraId: String?) = cameraId?.let { id ->
    runCatching { clipDeviceProfileOf(DeviceQuirksProvider.get(this).resolve(id)) }.getOrNull()
}

/**
 * A clip sidecar for a take that was cut short and has nothing but what its outputs hold: no engine evidence,
 * only the intent and the state read now. Null when no take start was recorded.
 */
internal fun CaptureService.interruptedClipSidecar(reasons: List<String>, encodedFrames: Long?): ClipSidecarProvider? {
    val started = takeClipStartedAt ?: return null
    val intent = takeClipIntent
    val codecMime = takeClipCodecMime
    val mediaRecorder = takeClipMediaRecorder
    takeClipMediaRecorder = null
    takeClipStartedAt = null
    takeClipIntent = null
    takeClipCodecMime = null
    val state = cameraState.value
    val facts = assembleClipFacts(ClipFactsInput(
        startedAt = started, intent = intent, cameraId = state.selectedCameraId, hardware = state.hardwareTruth,
        recordingWidth = state.recordingWidth, recordingHeight = state.recordingHeight, targetFps = state.targetFps,
        graphEvidence = null, hdr = state.selectedMode == CaptureMode.HLG, lutBaked = false,
        timelapse = state.selectedMode == CaptureMode.TIME_LAPSE, mediaRecorder = mediaRecorder, recorderCodecMime = codecMime,
        softwareEncoderName = takeSoftwareEncoder, sharedPause = null, separateAudio = null, embeddedAudio = false,
        deviceProfile = clipDeviceProfile(state.selectedCameraId),
        noticeCodes = listOfNotNull(runtimeNotice, avNotice, cadenceNotice).map { it.name.lowercase().replace('_', '-') },
        hardwareWarnings = emptyList(), stopReason = reasons.firstOrNull(), durationMs = activeRecordingElapsedMs(),
        encodedFrames = encodedFrames, success = false, nativeRetired = false,
    ))
    return clipSidecarProvider { facts }
}

/**
 * ADR-0011 for a take that recorded and then ended abnormally (engine failure, preview recovery, a
 * native writer that is not yet retired). Nothing that holds bytes is deleted. The audio is sealed
 * at once so no microphone keeps running; once the engine proves native retirement the retained
 * members are published as incomplete on the storage executor and the group is committed. Until
 * then the rows stay pending and journalled, so a process death is covered by crash recovery.
 * [run] does the MediaStore work; [onSaved] is called on main only after something was published.
 */
internal fun CaptureService.salvageAfterRetirement(
    output: VideoOutput?,
    audio: AudioSidecarRecorder?,
    retirement: CompletableFuture<Unit>,
    earlyReasons: List<String>,
    run: () -> com.librestatic.opencinecam.storage.PreparedTakeResult<android.net.Uri, com.librestatic.opencinecam.storage.AudioSidecarRecordingResult>,
    onSaved: (List<String>) -> Unit,
) {
    if (output == null && audio == null) return
    runCatching { output?.recoveryGroup?.beginSalvage() }
    if (audio != null) try {
        storageExecutor.execute {
            runCatching { audio.prepareIncompleteCompletion(earlyReasons) }
                .onFailure { android.util.Log.e("RecordingRecovery", "Audio of an interrupted take could not be sealed", it) }
        }
    } catch (_: RejectedExecutionException) {
        runCatching { audio.prepareIncompleteCompletion(earlyReasons) }
    }
    retirement.whenComplete { _, failure ->
        if (failure != null) {
            android.util.Log.e("RecordingRecovery", "Native retirement failed; the interrupted take stays pending for recovery", failure)
            return@whenComplete
        }
        // Explicit, so the publication below never depends on the order of the future's dependents.
        runCatching { releaseRecordingRecoveryGuard(output) }
        try {
            storageExecutor.execute {
                val result = try { run() } catch (problem: Throwable) {
                    android.util.Log.e("RecordingRecovery", "Incomplete publication failed; rows stay pending for recovery", problem)
                    return@execute
                }
                result.incompleteFailures.forEach {
                    android.util.Log.e("RecordingRecovery", "Incomplete publication step failed; rows stay pending for recovery", it)
                }
                if (result.incomplete) mainHandler.post { if (!serviceDestroyed) onSaved(result.incompleteReasons) }
            }
        } catch (_: RejectedExecutionException) {
            android.util.Log.w("RecordingRecovery", "Storage executor is closed; the interrupted take stays pending for recovery")
        }
    }
}

/** The Take-saved-as-incomplete message, once published, replaces only the failure message still on screen. */
internal fun CaptureService.announceIncompleteSaved(expected: String?, reasons: List<String>) {
    cameraState.update { current ->
        if (current.message != expected) current else current.copy(
            message = getString(R.string.take_saved_incomplete, reasons.joinToString(", ")), messageTransient = false)
    }
}

/** [salvageAfterRetirement] for a take with nothing but what the outputs hold: no sidecar from the engine's evidence. */
internal fun CaptureService.salvageInterruptedTake(take: DetachedTake, retirement: CompletableFuture<Unit>, reasons: List<String>,
    encodedFrames: Long?, expectedMessage: String?) {
    val output = take.video
    val audio = take.audio
    val clipSidecar = interruptedClipSidecar(reasons, encodedFrames)
    salvageAfterRetirement(output, audio, retirement, reasons, run = {
        finalizePreparedRecordingTake<
            com.librestatic.opencinecam.storage.PreparedVideoOutput,
            com.librestatic.opencinecam.storage.PreparedAudioSidecar,
            android.net.Uri,
            com.librestatic.opencinecam.storage.AudioSidecarRecordingResult,
        >(
            success = false,
            prepareAudio = null,
            prepareVideo = { null },
            publishAudio = { _ -> audio?.publishPrepared() },
            publishVideo = { _ -> output?.publishPrepared() },
            discardAudio = { audio?.finish(false); Unit },
            discardVideo = { output?.finish(false); Unit },
            salvage = com.librestatic.opencinecam.storage.IncompleteTakeSalvage(
                reasons = reasons,
                prepareAudio = audio?.let { sidecar -> { r -> sidecar.prepareIncompleteCompletion(r) } },
                prepareVideo = { r ->
                    output?.sealMotionLog(keep = false)
                    output?.prepareIncomplete(r, null, false, false, encodedFrames, clipSidecar)
                },
            ),
        )
    }, onSaved = { announceIncompleteSaved(expectedMessage, it) })
}

internal fun CaptureService.cancelTransferPreparation(): Boolean {
    if (!transferWaiting) return false
    transferWaiting = false
    transferPreparationToken++
    releaseTransferCapture(transferCapture.get()) // No capture resources were admitted yet.
    cameraState.value = cameraState.value.let { it.copy(
        phase = if (it.phase == CameraUiPhase.CAPTURING) CameraUiPhase.PREVIEWING else it.phase,
        transferRetirementPending = false, audioRetirementPending = false, message = getString(R.string.pro_wb_cancelled)) }
    return true
}

internal fun CaptureService.admitTransferPublication() {
    abortTransferPublication()
    activeTransferPublication = try { CaptureTransferPublication.admit(this) }
    catch (problem: Exception) {
        android.util.Log.e("CapturePublication", "Admission bookkeeping failed; capture remains independent", problem)
        null
    }
}

internal fun CaptureService.abortTransferPublication(publication: CaptureTransferPublication? =
    synchronized(takeOwnershipLock) { activeTransferPublication.also { activeTransferPublication = null } }) {
    publication?.abort()?.forEach { problem ->
        android.util.Log.e("CapturePublication", "Aborted take bookkeeping failed", problem)
    }
}

internal fun CaptureService.incrementSavedSlate(saved: ProductionSlateSettings) {
    try {
        SettingsRepositories.get(this).update { afterSavedProductionSlate(it, saved) }
    } catch (failure: Exception) {
        android.util.Log.e("ProductionSlate", "Saved media retained; next take number was not persisted", failure)
        mainHandler.post {
            if (!serviceDestroyed) cameraState.update { it.copy(message =
                listOfNotNull(it.message, getString(R.string.production_slate_increment_failed)).joinToString(" · ")) }
        }
    }
}

internal fun CaptureService.admittedCaptureNames(): CaptureNameSnapshot {
    val intent = SettingsRepositories.get(this).states.value
    return CaptureNameSnapshot(intent.captureNaming, intent.productionSlate, System.currentTimeMillis(),
        com.librestatic.opencinecam.settings.CaptureLocations.get(this).snapshot())
}
