/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import com.librestatic.opencinecam.core.model.AdaptiveCandidate
import com.librestatic.opencinecam.core.model.CaptureGraph
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.PolicyEngine
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.core.model.PreflightContext
import com.librestatic.opencinecam.core.model.PreflightDecision
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure

data class RecordingOrientation(
    val rotationDegrees: Int,
    val mirroredPreview: Boolean,
    val mirroredRecording: Boolean = false,
) {
    init {
        require(rotationDegrees in 0..359 && rotationDegrees % 90 == 0) {
            "recording rotation must be a normalized right angle"
        }
    }
}

data class RecorderStartRequest(
    val sessionId: String,
    val graph: CaptureGraph,
    val encoder: VideoEncoderRequest,
    val orientation: RecordingOrientation,
    val adaptiveCandidates: List<AdaptiveCandidate> = emptyList(),
    val preflightFailure: StableFailure? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "session ID must not be blank" }
    }
}

interface RecorderResources : AutoCloseable {
    fun start()
    fun stop()
}

fun interface RecorderResourceFactory {
    fun create(graph: CaptureGraph, encoder: SelectedVideoEncoder): RecorderResources
}

data class RecordedFileValidation(
    val valid: Boolean,
    val bytesWritten: Long,
    val videoTrackPresent: Boolean,
    val audioTrackPresent: Boolean,
    val message: String,
)

fun interface RecordedFileValidator {
    fun validate(): RecordedFileValidation
}

sealed interface RecorderStartResult {
    data class Started(
        val sessionId: String,
        val graph: CaptureGraph,
        val encoder: SelectedVideoEncoder,
        val orientation: RecordingOrientation,
        val disclosure: String? = null,
    ) : RecorderStartResult

    data class Rejected(val failure: StableFailure) : RecorderStartResult
    data class Cancelled(val failure: StableFailure) : RecorderStartResult
}

sealed interface RecorderStopResult {
    data class Validated(val validation: RecordedFileValidation) : RecorderStopResult
    data class Failed(val failure: StableFailure) : RecorderStopResult
}

fun interface RecorderCancellation {
    fun isCancelled(): Boolean
}

object RecorderNeverCancelled : RecorderCancellation {
    override fun isCancelled(): Boolean = false
}

/** Integrates policy, deterministic encoder selection, resource ownership, and final validation. */
class RecorderIntegration(
    private val selector: VideoEncoderSelector = VideoEncoderSelector(),
    private val policy: PolicyMode = PolicyMode.STRICT,
    private val correlationId: String = "recorder",
) {
    private var active: ActiveRecording? = null

    @Synchronized
    fun start(
        request: RecorderStartRequest,
        capabilities: List<VideoEncoderCapability>,
        resources: RecorderResourceFactory,
        cancellation: RecorderCancellation = RecorderNeverCancelled,
    ): RecorderStartResult {
        if (active != null) return RecorderStartResult.Rejected(failure(
            FailureCode.DUPLICATE_COMMAND,
            Recoverability.USER_ACTION,
            "A recording is already active.",
        ))
        if (cancellation.isCancelled()) return RecorderStartResult.Cancelled(failure(
            FailureCode.CANCELLATION,
            Recoverability.CANCELLED,
            "Recording start was cancelled.",
        ))
        val graphDecision = request.preflightFailure?.let { preflightFailure ->
            when (val decision = PolicyEngine(policy).decidePreflight(
                preflightFailure,
                PreflightContext(request.graph, request.adaptiveCandidates),
            )) {
                is PreflightDecision.Rejected -> return RecorderStartResult.Rejected(decision.failure)
                is PreflightDecision.Transition -> decision.candidate
            }
        }
        val graph = graphDecision?.graph ?: request.graph
        val disclosure = graphDecision?.disclosure
        val selection = when (val selected = selector.select(request.encoder, capabilities)) {
            is VideoEncoderSelection.Rejected -> return RecorderStartResult.Rejected(selected.failure)
            is VideoEncoderSelection.Selected -> selected.encoder
        }
        if (cancellation.isCancelled()) return RecorderStartResult.Cancelled(failure(
            FailureCode.CANCELLATION,
            Recoverability.CANCELLED,
            "Recording start was cancelled.",
        ))
        var candidateResources: RecorderResources? = null
        val owned = try {
            candidateResources = resources.create(graph, selection)
            candidateResources!!.start()
            candidateResources!!
        } catch (_: Throwable) {
            runCatching { candidateResources?.close() }
            return RecorderStartResult.Rejected(failure(
                FailureCode.SESSION_CONFIGURATION_FAILED,
                Recoverability.RETRYABLE,
                "Recording resources could not be started.",
            ))
        }
        active = ActiveRecording(request.sessionId, owned)
        return RecorderStartResult.Started(request.sessionId, graph, selection, request.orientation, disclosure)
    }

    @Synchronized
    fun stop(sessionId: String, validator: RecordedFileValidator): RecorderStopResult {
        val current = active ?: return RecorderStopResult.Failed(failure(
            FailureCode.STALE_EVIDENCE,
            Recoverability.USER_ACTION,
            "Recording session is no longer active.",
        ))
        if (current.sessionId != sessionId) return RecorderStopResult.Failed(failure(
            FailureCode.STALE_EVIDENCE,
            Recoverability.USER_ACTION,
            "Recording session ID is stale.",
        ))
        return try {
            current.resources.stop()
            val validation = validator.validate()
            if (!validation.valid) {
                RecorderStopResult.Failed(failure(
                    FailureCode.INTEGRITY_FAILURE,
                    Recoverability.RETRYABLE,
                    "Recorded file validation failed.",
                ))
            } else {
                RecorderStopResult.Validated(validation)
            }
        } catch (_: Throwable) {
            RecorderStopResult.Failed(failure(
                FailureCode.MUXER_FINALIZATION_FAILED,
                Recoverability.RETRYABLE,
                "Recording could not be finalized.",
            ))
        } finally {
            runCatching { current.resources.close() }
            active = null
        }
    }

    private data class ActiveRecording(val sessionId: String, val resources: RecorderResources)

    private fun failure(code: FailureCode, recoverability: Recoverability, message: String) = StableFailure(
        component = "recorder",
        code = code,
        severity = FailureSeverity.ERROR,
        recoverability = recoverability,
        correlationId = correlationId,
        userMessage = message,
    )
}
