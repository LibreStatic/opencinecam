/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

data class CaptureLifecycleSnapshot(
    val serviceGeneration: Long,
    val state: CaptureState,
    val cameraId: String?,
    val ownerId: String?,
    val recordingId: String?,
    val previewSurfaceId: String?,
)

sealed interface CaptureLifecycleEffect {
    data class Surface(val event: PreviewSurfaceEvent) : CaptureLifecycleEffect
    data object PreviewClosedInBackground : CaptureLifecycleEffect
    data object RecordingContinuesWithoutPreview : CaptureLifecycleEffect
    data class Disconnected(val failure: StableFailure, val snapshot: CaptureLifecycleSnapshot) : CaptureLifecycleEffect
    data class RecoveryStarted(val commands: List<CaptureCommand>) : CaptureLifecycleEffect
    data class ReprobeRequired(val snapshot: CaptureLifecycleSnapshot) : CaptureLifecycleEffect
    data object NoOp : CaptureLifecycleEffect
}

/** Coordinates UI lifecycle signals with the service-owned state machine. */
class CaptureLifecycleCoordinator(
    val stateMachine: CaptureStateMachine = CaptureStateMachine(),
    val surfaceSession: PreviewSurfaceSession = PreviewSurfaceSession(),
    private var serviceGeneration: Long = 1,
) {
    private var cameraId: String? = null
    private var ownerId: String? = null
    private var recordingId: String? = null
    private var previewSurfaceId: String? = null

    fun open(owner: String, camera: String): CaptureTransition {
        val transition = stateMachine.dispatch(CaptureCommand.Open(owner, camera))
        if (transition.accepted) {
            ownerId = owner
            cameraId = camera
        }
        return transition
    }

    fun attachPreview(surfaceId: String): CaptureLifecycleEffect =
        surfaceSession.attach(surfaceId).also { event ->
            if (event is PreviewSurfaceEvent.Attached) previewSurfaceId = surfaceId
        }.let(CaptureLifecycleEffect::Surface)

    fun markPreviewReady(): CaptureLifecycleEffect {
        val id = previewSurfaceId ?: return CaptureLifecycleEffect.NoOp
        return CaptureLifecycleEffect.Surface(surfaceSession.markReady(id))
    }

    fun detachPreview(): CaptureLifecycleEffect {
        val id = previewSurfaceId ?: return CaptureLifecycleEffect.NoOp
        val event = surfaceSession.detach(id)
        if (event is PreviewSurfaceEvent.Detached) previewSurfaceId = null
        return CaptureLifecycleEffect.Surface(event)
    }

    fun prepareRecording(id: String): CaptureTransition = stateMachine.dispatch(CaptureCommand.PrepareRecording(id)).also {
        if (it.accepted) recordingId = id
    }

    fun startRecording(): CaptureTransition = stateMachine.dispatch(CaptureCommand.StartRecording)

    /** Background closes preview-only capture, but never tears down an active recording. */
    fun onActivityBackground(): CaptureLifecycleEffect = when (stateMachine.state) {
        is CaptureState.Previewing -> {
            stateMachine.dispatch(CaptureCommand.RequestStop)
            stateMachine.dispatch(CaptureCommand.StopCompleted)
            CaptureLifecycleEffect.PreviewClosedInBackground
        }
        is CaptureState.Recording,
        is CaptureState.PreparingRecording,
        is CaptureState.Stopping,
        -> CaptureLifecycleEffect.RecordingContinuesWithoutPreview
        else -> CaptureLifecycleEffect.NoOp
    }

    fun onActivityForeground(): CaptureLifecycleEffect = CaptureLifecycleEffect.NoOp

    fun onCameraDisconnected(reason: String): CaptureLifecycleEffect {
        val snapshot = snapshot()
        val failure = StableFailure(
            component = "capture-lifecycle",
            code = FailureCode.CAPTURE_OPEN_FAILED,
            severity = FailureSeverity.ERROR,
            recoverability = Recoverability.RETRYABLE,
            correlationId = "service-$serviceGeneration",
            userMessage = "The camera became unavailable; recovery is required.",
            details = mapOf("reason" to reason),
        )
        stateMachine.dispatch(CaptureCommand.Fail(failure))
        return CaptureLifecycleEffect.Disconnected(failure, snapshot)
    }

    /** Reopens the same camera/owner only; graph and physical route must be reprobed by the caller. */
    fun onCameraAvailable(): CaptureLifecycleEffect {
        val owner = ownerId ?: return CaptureLifecycleEffect.NoOp
        val camera = cameraId ?: return CaptureLifecycleEffect.NoOp
        if (stateMachine.state !is CaptureState.Failed) return CaptureLifecycleEffect.NoOp
        stateMachine.dispatch(CaptureCommand.Recover(owner, camera))
        stateMachine.dispatch(CaptureCommand.RecoveryReady)
        return CaptureLifecycleEffect.RecoveryStarted(
            listOf(CaptureCommand.PreviewConfigured),
        )
    }

    /** Service recreation never silently resumes a recording; it returns a reprobe requirement. */
    fun onServiceRestarted(previous: CaptureLifecycleSnapshot): CaptureLifecycleEffect {
        serviceGeneration += 1
        return CaptureLifecycleEffect.ReprobeRequired(previous)
    }

    fun snapshot(): CaptureLifecycleSnapshot = CaptureLifecycleSnapshot(
        serviceGeneration = serviceGeneration,
        state = stateMachine.state,
        cameraId = cameraId,
        ownerId = ownerId,
        recordingId = recordingId,
        previewSurfaceId = previewSurfaceId,
    )
}
