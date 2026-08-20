/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

enum class FailureCode {
    INVALID_COMMAND,
    UNSUPPORTED_CAPABILITY,
    UNKNOWN_CAPABILITY,
    CAPTURE_OPEN_FAILED,
    GRAPH_NEGOTIATION_FAILED,
    SESSION_CONFIGURATION_FAILED,
    FIRST_FRAME_TIMEOUT,
    ENCODER_CONFIGURATION_FAILED,
    AUDIO_INITIALIZATION_FAILED,
    AUDIO_RUNTIME_FAILED,
    MUXER_FINALIZATION_FAILED,
    STORAGE_WRITE_FAILED,
    THERMAL_CRITICAL,
    CADENCE_DISCONTINUITY,
    ROUTE_MISMATCH,
    INTEGRITY_FAILURE,
    CANCELLATION,
    DUPLICATE_COMMAND,
    STALE_EVIDENCE,
}

enum class FailureSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL,
}

enum class Recoverability {
    RETRYABLE,
    USER_ACTION,
    UNSUPPORTED,
    FATAL,
    CANCELLED,
}

data class StableFailure(
    val component: String,
    val code: FailureCode,
    val severity: FailureSeverity,
    val recoverability: Recoverability,
    val correlationId: String,
    val userMessage: String,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(component.isNotBlank()) { "failure component must not be blank" }
        require(correlationId.isNotBlank()) { "failure correlation ID must not be blank" }
        require(userMessage.isNotBlank() && !userMessage.contains('\n')) {
            "failure user message must be a safe single line"
        }
        require(details.keys.none { it.isBlank() }) { "failure detail keys must not be blank" }
    }
}

enum class PolicyMode {
    STRICT,
    ADAPTIVE,
}

enum class ProtectedInvariant {
    CAMERA,
    RAW,
    HLG,
    BIT_DEPTH,
    DYNAMIC_RANGE,
}

data class CaptureGraph(
    val cameraId: String,
    val codecFamily: String,
    val codecName: String,
    val bitDepth: Int,
    val bitrate: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val raw: Boolean,
    val hlg: Boolean,
    val dynamicRange: String,
) {
    init {
        require(cameraId.isNotBlank()) { "camera ID must not be blank" }
        require(codecFamily.isNotBlank()) { "codec family must not be blank" }
        require(codecName.isNotBlank()) { "codec name must not be blank" }
        require(bitDepth > 0 && bitrate > 0 && width > 0 && height > 0 && fps > 0) {
            "capture graph dimensions and rates must be positive"
        }
        require(dynamicRange.isNotBlank()) { "dynamic range must not be blank" }
    }

    fun protectedSignature(): List<Any> = listOf(cameraId, raw, hlg, bitDepth, dynamicRange)
}

enum class AdaptiveStep(val order: Int) {
    SAME_CODEC_ENCODER(0),
    BITRATE(1),
    RESOLUTION(2),
    FPS(3),
    CODEC_FAMILY_CHANGE(4),
}

data class AdaptiveCandidate(
    val step: AdaptiveStep,
    val graph: CaptureGraph,
    val disclosure: String,
) {
    init {
        require(disclosure.isNotBlank()) { "adaptive disclosure must not be blank" }
    }
}

data class PreflightContext(
    val requested: CaptureGraph,
    val candidates: List<AdaptiveCandidate>,
    val codecFamilyChangeConfirmed: Boolean = false,
)

sealed interface PreflightDecision {
    data class Rejected(val failure: StableFailure) : PreflightDecision
    data class Transition(val candidate: AdaptiveCandidate) : PreflightDecision
}

sealed interface RuntimeDecision {
    data class Stopped(val failure: StableFailure) : RuntimeDecision
    data class ContinueWithWarning(val failure: StableFailure, val disclosure: String) : RuntimeDecision
}

/** Encodes the ADR-0015 table without allowing protected signal-path changes. */
class PolicyEngine(private val mode: PolicyMode = PolicyMode.STRICT) {
    fun decidePreflight(failure: StableFailure, context: PreflightContext): PreflightDecision {
        if (mode == PolicyMode.STRICT || failure.code in protectedFailureCodes) {
            return PreflightDecision.Rejected(failure)
        }
        val candidate = context.candidates
            .asSequence()
            .sortedWith(compareBy<AdaptiveCandidate> { it.step.order }.thenBy { it.graph.codecName })
            .firstOrNull { candidate ->
                candidate.graph.protectedSignature() == context.requested.protectedSignature() &&
                    (candidate.step != AdaptiveStep.CODEC_FAMILY_CHANGE ||
                        (context.codecFamilyChangeConfirmed && candidate.graph.codecFamily != context.requested.codecFamily))
            }
        return candidate?.let(PreflightDecision::Transition) ?: PreflightDecision.Rejected(failure)
    }

    fun decideRuntime(failure: StableFailure): RuntimeDecision = when {
        mode == PolicyMode.ADAPTIVE && failure.code == FailureCode.AUDIO_RUNTIME_FAILED ->
            RuntimeDecision.ContinueWithWarning(failure, "Audio stopped; video continues without audio.")
        mode == PolicyMode.ADAPTIVE && failure.code == FailureCode.STORAGE_WRITE_FAILED ->
            RuntimeDecision.Stopped(failure)
        mode == PolicyMode.ADAPTIVE && failure.code == FailureCode.CADENCE_DISCONTINUITY ->
            RuntimeDecision.Stopped(failure)
        mode == PolicyMode.ADAPTIVE && failure.severity == FailureSeverity.WARNING ->
            RuntimeDecision.ContinueWithWarning(failure, "Monitoring was reduced; recording format is unchanged.")
        else -> RuntimeDecision.Stopped(failure)
    }

    private companion object {
        val protectedFailureCodes = setOf(
            FailureCode.ROUTE_MISMATCH,
            FailureCode.INTEGRITY_FAILURE,
            FailureCode.THERMAL_CRITICAL,
            FailureCode.CADENCE_DISCONTINUITY,
        )
    }
}
