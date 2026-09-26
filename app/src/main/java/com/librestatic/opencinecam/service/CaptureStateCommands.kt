/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.core.model.CaptureCommand
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Maps the service's real preview and take events onto [CaptureCommand]s for the serialized
 * capture actor, so `LocalBinder.states` reports the recording lifecycle and not only preview.
 *
 * Each event yields the whole command sequence it may need; the machine rejects the commands
 * that are illegal in its current state without changing it. A take is named at dispatch and
 * every take-scoped stop names it, so a late callback never ends a newer take or the preview.
 */
internal class CaptureStateCommands(private val ownerId: String) {
    private val takes = AtomicLong()
    private val activeTake = AtomicReference<String?>(null)

    /** A preview open; after an engine failure it goes through the machine's recovery path. */
    fun previewOpening(cameraId: String): List<CaptureCommand> = listOf(
        CaptureCommand.Recover(ownerId, cameraId),
        CaptureCommand.RecoveryReady,
        CaptureCommand.Open(ownerId, cameraId),
    )

    fun previewStarted(): List<CaptureCommand> = listOf(CaptureCommand.PreviewConfigured)

    /** The preview session was closed (not a take stop). */
    fun previewStopped(): List<CaptureCommand> = listOf(CaptureCommand.RequestStop, CaptureCommand.StopCompleted)

    /** A VIDEO/LOG/TIME_LAPSE take was handed to the engine. */
    fun takeDispatched(): List<CaptureCommand> {
        val id = "take-${takes.incrementAndGet()}"
        activeTake.set(id)
        return listOf(CaptureCommand.PrepareRecording(id))
    }

    fun takeStarted(): List<CaptureCommand> = listOf(CaptureCommand.StartRecording)

    /** The engine accepted a stop request for the current take. */
    fun takeStopRequested(): List<CaptureCommand> =
        activeTake.get()?.let { listOf(CaptureCommand.StopRecording(it)) }.orEmpty()

    /** The take finalized, or its start was rejected before RECORDING; the preview continues. */
    fun takeEnded(): List<CaptureCommand> =
        activeTake.getAndSet(null)?.let { listOf(CaptureCommand.RecordingStopped(it)) }.orEmpty()

    /** An engine failure; the service reports ERROR and needs a preview recovery. */
    fun engineFailed(code: String, message: String, recoverable: Boolean): List<CaptureCommand> {
        val take = activeTake.getAndSet(null)
        return listOf(
            CaptureCommand.Fail(
                StableFailure(
                    component = "camera-engine",
                    code = FailureCode.SESSION_CONFIGURATION_FAILED,
                    severity = FailureSeverity.ERROR,
                    recoverability = if (recoverable) Recoverability.RETRYABLE else Recoverability.USER_ACTION,
                    correlationId = take ?: ownerId,
                    userMessage = message.replace(Regex("\\s*\\R\\s*"), " ").trim().ifBlank { code.ifBlank { "engine-failure" } },
                    details = mapOf("engineCode" to code),
                ),
            ),
        )
    }
}
