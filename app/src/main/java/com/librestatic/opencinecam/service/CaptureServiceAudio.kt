/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import android.content.pm.PackageManager
import android.Manifest
import android.os.Looper
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.settings.normalizedFor
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.storage.AudioRetirementGate
import com.librestatic.opencinecam.media.audio.ActiveAudioInput
import com.librestatic.opencinecam.media.audio.AudioInputLossAction
import com.librestatic.opencinecam.media.audio.AudioInputLossPolicy
import com.librestatic.opencinecam.media.audio.AudioInputRouteGuard
import com.librestatic.opencinecam.media.audio.audioInputKind
import com.librestatic.opencinecam.settings.audioInputTypeLabel
import com.librestatic.opencinecam.settings.audioInputUnavailable
import com.librestatic.opencinecam.settings.resolveAudioInputSelection
import com.librestatic.opencinecam.camera.PcmListeningSink
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.update
import com.librestatic.opencinecam.service.CaptureService.TakeAudioInput
import com.librestatic.opencinecam.service.CaptureService.Companion.LOST_INPUT_STOP_ATTEMPTS
import com.librestatic.opencinecam.service.CaptureService.Companion.LOST_INPUT_STOP_RETRY_MS

// Part of CaptureService: preview audio monitoring, level meters, listening and the take's audio input route.
// These are extensions so the service's state stays in one object; they keep the threading rules of its members.

/** Only the current PCM producer may publish an applied-gain receipt. */
internal fun CaptureService.newAudioLevelConsumer(): (AudioLevelSnapshot) -> Unit {
    retireAudioLevelConsumer(clearLevels = true)
    val epoch = audioLevelEpoch.get()
    return { snapshot ->
        mainHandler.post {
            if (!serviceDestroyed && audioLevelEpoch.get() == epoch) cameraState.update { current ->
                if (serviceDestroyed || audioLevelEpoch.get() != epoch) current else current.copy(
                    audioLevels = snapshot,
                    audioClipLatched = current.audioClipLatched || snapshot.clipped,
                    audioMonitoringActive = true,
                )
            }
        }
    }
}

internal fun CaptureService.newAudioListeningSink(): PcmListeningSink {
    val epoch = audioLevelEpoch.get()
    return PcmListeningSink { buffer, byteCount, encoding, rate, channels ->
        if (serviceDestroyed || audioLevelEpoch.get() != epoch) false
        else audioListeningController?.offer(buffer, byteCount, encoding, rate, channels,
            isCurrent = { !serviceDestroyed && audioLevelEpoch.get() == epoch }) ?: false
    }
}

internal fun CaptureService.retireAudioLevelConsumer(clearLevels: Boolean = false) {
    audioLevelEpoch.incrementAndGet()
    audioListeningController?.clearProducer()
    cameraState.update { it.copy(audioLevels = if (clearLevels) null else it.audioLevels, audioMonitoringActive = false) }
}

internal fun CaptureService.rememberPreviewAudioRetirement(receipt: CompletableFuture<Unit>) {
    previewAudioRetirement = CompletableFuture.allOf(previewAudioRetirement, receipt).thenApply { Unit }
}

internal fun CaptureService.stopPreviewAudioMonitor(clearLevels: Boolean = false): CompletableFuture<Unit> {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        // Fence queued PCM immediately; native owner changes remain serialized on main.
        retireAudioLevelConsumer(clearLevels)
        val receipt = CompletableFuture<Unit>()
        mainHandler.post {
            stopPreviewAudioMonitor(clearLevels).whenComplete { _, failure ->
                if (failure == null) receipt.complete(Unit) else receipt.completeExceptionally(failure)
            }
        }
        return receipt
    }
    previewAudioGeneration++
    val monitor = previewAudioMonitor
    previewAudioMonitor = null
    if (monitor != null || clearLevels) retireAudioLevelConsumer(clearLevels)
    if (monitor != null) rememberPreviewAudioRetirement(monitor.closeAsync())
    cameraState.update { it.copy(
        audioLevels = if (clearLevels) null else it.audioLevels,
        audioMonitoringActive = false,
    ) }
    // A caller may cancel its wait, never the actual owner's retirement.
    return previewAudioRetirement.thenApply { Unit }
}

internal fun CaptureService.previewAudioEligible(): Boolean {
    val current = cameraState.value
    return !serviceDestroyed && attachedPreviewSurface?.isValid == true &&
        current.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) &&
        current.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG) &&
        transferCapture.get() == null && !transferWaiting && !recordingWbPreparing &&
        settings.audioEnabled &&
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

internal fun CaptureService.startPreviewAudioMonitorIfEligible() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainHandler.post { startPreviewAudioMonitorIfEligible() }
        return
    }
    val retirement = stopPreviewAudioMonitor(clearLevels = false)
    val generation = previewAudioGeneration
    if (!previewAudioEligible()) {
        if (!settings.audioEnabled || cameraState.value.selectedMode !in setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG)) {
            cameraState.update { it.copy(audioLevels = null, audioMonitoringActive = false, activeAudioInput = null) }
        }
        return
    }
    retirement.whenComplete { _, retiredFailure ->
        mainHandler.post {
            if (generation != previewAudioGeneration || !previewAudioEligible()) return@post
            if (retiredFailure != null) {
                cameraState.update { it.copy(audioMonitoringActive = false,
                    message = getString(R.string.audio_retirement_failed)) }
                return@post
            }
            try {
                AudioRetirementGate.requireIdle()
                val onLevel = newAudioLevelConsumer()
                val epoch = audioLevelEpoch.get()
                if (audioInputRefreshDeferred) {
                    audioInputRefreshDeferred = false
                    localBinder.refreshAudioCapabilities()
                }
                val monitorSettings = cameraState.value.audioCapabilities?.let(settings::normalizedFor) ?: settings
                val monitor = PreviewAudioMonitor.create(
                    this,
                    monitorSettings,
                    onLevel,
                    onFailure = { failure ->
                        mainHandler.post failedAudio@ {
                            if (serviceDestroyed || audioLevelEpoch.get() != epoch) return@failedAudio
                            stopPreviewAudioMonitor(clearLevels = false)
                            cameraState.update { it.copy(audioMonitoringActive = false,
                                message = "Audio monitor unavailable: ${failure.message}") }
                        }
                    },
                    listeningSink = newAudioListeningSink(),
                    inputGuard = newAudioRouteGuard(monitorSettings, AudioInputLossAction.IGNORE),
                )
                previewAudioMonitor = monitor
                monitor.start()
            } catch (failure: Throwable) {
                if (failure is PreviewAudioMonitorCreationFailure) rememberPreviewAudioRetirement(failure.retirement)
                stopPreviewAudioMonitor(clearLevels = false)
                cameraState.update { it.copy(audioMonitoringActive = false,
                    message = "Audio monitor unavailable: ${failure.message}") }
            }
        }
    }
}

internal suspend fun CaptureService.awaitPreviewAudioRetirement(receipt: CompletableFuture<Unit>) {
    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
        receipt.whenComplete { _, failure ->
            if (failure == null) continuation.resumeWith(Result.success(Unit))
            else continuation.resumeWith(Result.failure(failure))
        }
        // Coroutine cancellation does not cancel or release the native owner.
    }
}

/**
 * Applies the loss policy to a remembered input that is missing before REC (OCC-AUDIO-012).
 * Null means the take is refused; the failure is already published.
 */
internal fun CaptureService.takeAudioInput(audioSettings: CameraSettings, audioRequested: Boolean): TakeAudioInput? {
    if (!audioRequested) return TakeAudioInput(audioSettings, null)
    val requested = AudioInputRouteGuard.inputDevice(this, audioSettings.audioInputDeviceId)
    val missing = (audioSettings.audioInputDeviceId != null && requested == null) ||
        cameraState.value.audioCapabilities?.let(audioSettings::audioInputUnavailable) == true
    if (!missing) return TakeAudioInput(audioSettings, newAudioRouteGuard(audioSettings, AudioInputLossAction.IGNORE, requested))
    val startDegraded = when (audioSettings.audioInputLossPolicy) {
        AudioInputLossPolicy.STOP_TAKE -> {
            fail("audio-input-missing", getString(R.string.audio_input_missing_refused))
            return null
        }
        AudioInputLossPolicy.FALLBACK_BUILTIN -> AudioInputLossAction.FALLBACK
        AudioInputLossPolicy.CONTINUE_SILENT -> AudioInputLossAction.SILENCE
    }
    val degraded = audioSettings.copy(audioInputDeviceId = null)
    return TakeAudioInput(degraded, newAudioRouteGuard(degraded, startDegraded, null))
}

internal fun CaptureService.newAudioRouteGuard(
    audioSettings: CameraSettings,
    startDegraded: AudioInputLossAction,
    requested: android.media.AudioDeviceInfo? = AudioInputRouteGuard.inputDevice(this, audioSettings.audioInputDeviceId),
): AudioInputRouteGuard {
    lateinit var guard: AudioInputRouteGuard
    guard = AudioInputRouteGuard(
        this, requested, audioSettings.audioInputLossPolicy, startDegraded,
        onRoute = { route -> mainHandler.post { publishAudioRoute(guard, route) } },
        onLoss = { action -> mainHandler.post { onAudioInputLost(guard, action) } },
    )
    audioRouteOwner = guard
    return guard
}

internal fun CaptureService.publishAudioRoute(guard: AudioInputRouteGuard, route: ActiveAudioInput) {
    if (serviceDestroyed || audioRouteOwner !== guard) return
    cameraState.update { it.copy(activeAudioInput = route) }
}

internal fun CaptureService.onAudioInputLost(guard: AudioInputRouteGuard, action: AudioInputLossAction) {
    if (serviceDestroyed || audioRouteOwner !== guard) return
    when (action) {
        AudioInputLossAction.STOP_TAKE -> {
            android.util.Log.w("CaptureService", "Stopping take: audio-input-lost")
            stopTakeForLostInput(guard, attemptsLeft = LOST_INPUT_STOP_ATTEMPTS)
        }
        AudioInputLossAction.FALLBACK ->
            cameraState.update { it.copy(message = getString(R.string.audio_input_lost_fallback)) }
        AudioInputLossAction.SILENCE ->
            cameraState.update { it.copy(message = getString(R.string.audio_input_lost_silent)) }
        AudioInputLossAction.IGNORE -> Unit
    }
}

/** A loss can arrive while the take is still starting; stopRecording only accepts a RECORDING take. */
internal fun CaptureService.stopTakeForLostInput(guard: AudioInputRouteGuard, attemptsLeft: Int) {
    if (serviceDestroyed || audioRouteOwner !== guard || !takeInProgress()) return
    if (localBinder.stopRecording(audioInputLostFailure())) {
        cameraState.update { it.copy(message = getString(R.string.audio_input_lost_stopped)) }
    } else if (attemptsLeft > 0) {
        mainHandler.postDelayed({ stopTakeForLostInput(guard, attemptsLeft - 1) }, LOST_INPUT_STOP_RETRY_MS)
    }
}

/** Hotplug: a microphone appeared or disappeared. A running take keeps its guard; the probe waits. */
internal fun CaptureService.onAudioInputsChanged() {
    if (serviceDestroyed) return
    if (takeInProgress()) {
        audioInputRefreshDeferred = true
        return
    }
    localBinder.refreshAudioCapabilities()
    startPreviewAudioMonitorIfEligible()
}

/** "Using USB microphone: <name>" when Auto moves to a newly connected external input. */
internal fun CaptureService.announceAutoAudioInput(capabilities: com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities) {
    val choice = settings.resolveAudioInputSelection(capabilities.inputs).autoChoice
    val previous = lastAutoAudioInput
    lastAutoAudioInput = choice?.key
    if (settings.audioInputKey != null || takeInProgress() || choice == null || !choice.isExternal) return
    if (previous == null || previous == choice.key) return
    val label = choice.label.ifBlank { getString(audioInputTypeLabel(audioInputKind(choice.type))) }
    cameraState.update { it.copy(message = getString(R.string.audio_input_auto_using,
        getString(audioInputTypeLabel(audioInputKind(choice.type))), label)) }
}
