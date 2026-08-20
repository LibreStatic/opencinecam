/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed interface CaptureState {
    data object Stopped : CaptureState
    data class Opening(val ownerId: String, val cameraId: String) : CaptureState
    data class Previewing(val ownerId: String, val cameraId: String) : CaptureState
    data class PreparingRecording(val ownerId: String, val cameraId: String, val recordingId: String) : CaptureState
    data class Recording(val ownerId: String, val cameraId: String, val recordingId: String) : CaptureState
    data class Stopping(val ownerId: String, val cameraId: String, val recordingId: String?) : CaptureState
    data class Recovering(val ownerId: String, val cameraId: String) : CaptureState
    data class Failed(val failure: StableFailure) : CaptureState
}

sealed interface CaptureCommand {
    data class Open(val ownerId: String, val cameraId: String) : CaptureCommand
    data object PreviewConfigured : CaptureCommand
    data class PrepareRecording(val recordingId: String) : CaptureCommand
    data object StartRecording : CaptureCommand
    data object RequestStop : CaptureCommand
    data object StopCompleted : CaptureCommand
    data class Fail(val failure: StableFailure) : CaptureCommand
    data class Recover(val ownerId: String, val cameraId: String) : CaptureCommand
    data object RecoveryReady : CaptureCommand
}

data class CaptureTransition(
    val previous: CaptureState,
    val current: CaptureState,
    val failure: StableFailure? = null,
) {
    val accepted: Boolean get() = failure == null
}

/** Single owner for camera/session/media resources; ownership is never implicit. */
class HardwareOwnership {
    private var ownerId: String? = null

    @Synchronized
    fun acquire(requestedOwnerId: String): Boolean {
        require(requestedOwnerId.isNotBlank()) { "owner ID must not be blank" }
        if (ownerId != null) return false
        ownerId = requestedOwnerId
        return true
    }

    @Synchronized
    fun release(releasingOwnerId: String) {
        if (ownerId == releasingOwnerId) ownerId = null
    }

    @Synchronized
    fun currentOwner(): String? = ownerId
}

/** Pure transition table used by the Android service and host tests. */
class CaptureStateMachine(
    private val ownership: HardwareOwnership = HardwareOwnership(),
    private val correlationId: String = "capture-service",
) {
    var state: CaptureState = CaptureState.Stopped
        private set

    @Synchronized
    fun dispatch(command: CaptureCommand): CaptureTransition {
        val previous = state
        val transition = when (command) {
            is CaptureCommand.Open -> open(command)
            CaptureCommand.PreviewConfigured -> whenState<CaptureState.Opening> { opening ->
                CaptureState.Previewing(opening.ownerId, opening.cameraId)
            }
            is CaptureCommand.PrepareRecording -> whenState<CaptureState.Previewing> { preview ->
                requireNonBlank(command.recordingId, "recording ID")
                CaptureState.PreparingRecording(preview.ownerId, preview.cameraId, command.recordingId)
            }
            CaptureCommand.StartRecording -> whenState<CaptureState.PreparingRecording> { preparing ->
                CaptureState.Recording(preparing.ownerId, preparing.cameraId, preparing.recordingId)
            }
            CaptureCommand.RequestStop -> when (val current = state) {
                is CaptureState.Previewing -> accepted(CaptureState.Stopping(current.ownerId, current.cameraId, null))
                is CaptureState.PreparingRecording -> accepted(
                    CaptureState.Stopping(current.ownerId, current.cameraId, current.recordingId),
                )
                is CaptureState.Recording -> accepted(
                    CaptureState.Stopping(current.ownerId, current.cameraId, current.recordingId),
                )
                else -> invalid("Stop is only valid after preview or recording has opened.")
            }
            CaptureCommand.StopCompleted -> whenState<CaptureState.Stopping> { stopping ->
                ownership.release(stopping.ownerId)
                CaptureState.Stopped
            }
            is CaptureCommand.Fail -> {
                require(command.failure.correlationId.isNotBlank())
                releaseActiveOwner()
                accepted(CaptureState.Failed(command.failure))
            }
            is CaptureCommand.Recover -> recover(command)
            CaptureCommand.RecoveryReady -> whenState<CaptureState.Recovering> { recovering ->
                CaptureState.Opening(recovering.ownerId, recovering.cameraId)
            }
        }
        state = transition.current
        return transition.copy(previous = previous)
    }

    private fun open(command: CaptureCommand.Open): CaptureTransition {
        requireNonBlank(command.ownerId, "owner ID")
        requireNonBlank(command.cameraId, "camera ID")
        if (state != CaptureState.Stopped) return invalid("Capture is already active.")
        if (!ownership.acquire(command.ownerId)) {
            return rejected(
                FailureCode.DUPLICATE_COMMAND,
                Recoverability.USER_ACTION,
                "Another capture owner already holds the camera.",
            )
        }
        return accepted(CaptureState.Opening(command.ownerId, command.cameraId))
    }

    private fun recover(command: CaptureCommand.Recover): CaptureTransition {
        requireNonBlank(command.ownerId, "owner ID")
        requireNonBlank(command.cameraId, "camera ID")
        if (state !is CaptureState.Failed) return invalid("Recovery is only valid after a failure.")
        if (!ownership.acquire(command.ownerId)) {
            return rejected(
                FailureCode.DUPLICATE_COMMAND,
                Recoverability.USER_ACTION,
                "Another capture owner already holds the camera.",
            )
        }
        return accepted(CaptureState.Recovering(command.ownerId, command.cameraId))
    }

    private inline fun <reified T : CaptureState> whenState(next: (T) -> CaptureState): CaptureTransition {
        val current = state
        return if (current is T) {
            accepted(next(current))
        } else {
            invalid("Command is not valid in the current capture state.")
        }
    }

    private fun releaseActiveOwner() {
        when (val current = state) {
            is CaptureState.Opening -> ownership.release(current.ownerId)
            is CaptureState.Previewing -> ownership.release(current.ownerId)
            is CaptureState.PreparingRecording -> ownership.release(current.ownerId)
            is CaptureState.Recording -> ownership.release(current.ownerId)
            is CaptureState.Stopping -> ownership.release(current.ownerId)
            is CaptureState.Recovering -> ownership.release(current.ownerId)
            CaptureState.Stopped, is CaptureState.Failed -> Unit
        }
    }

    private fun accepted(next: CaptureState) = CaptureTransition(state, next)

    private fun invalid(message: String) = rejected(
        FailureCode.INVALID_COMMAND,
        Recoverability.USER_ACTION,
        message,
    )

    private fun rejected(code: FailureCode, recoverability: Recoverability, message: String) = CaptureTransition(
        previous = state,
        current = state,
        failure = StableFailure(
            component = "capture-service",
            code = code,
            severity = FailureSeverity.ERROR,
            recoverability = recoverability,
            correlationId = correlationId,
            userMessage = message,
        ),
    )

    private fun requireNonBlank(value: String, label: String) {
        require(value.isNotBlank()) { "$label must not be blank" }
    }
}

/** Serialized command actor. The executor is the sole caller of the transition table. */
class SerializedCaptureActor(
    private val machine: CaptureStateMachine,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : AutoCloseable {
    fun submit(command: CaptureCommand): CompletableFuture<CaptureTransition> =
        CompletableFuture.supplyAsync({ machine.dispatch(command) }, executor)

    override fun close() {
        executor.shutdownNow()
    }
}
