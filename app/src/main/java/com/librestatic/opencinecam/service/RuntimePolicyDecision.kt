/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.thermalStatusFromPlatform
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.RuntimeAction
import com.librestatic.opencinecam.core.model.RuntimeHardeningDecision
import com.librestatic.opencinecam.core.model.RuntimeHardeningPolicy
import com.librestatic.opencinecam.core.model.StableFailure
import com.librestatic.opencinecam.core.model.StorageRuntimeObservation
import com.librestatic.opencinecam.media.audio.AvDiagnosticAction
import com.librestatic.opencinecam.media.audio.AvDiagnosticSnapshot
import com.librestatic.opencinecam.media.storage.calculateStorageReserve

/** What a recording-ticker pass decided: stop the take, and/or keep a persistent notice on screen. */
internal data class RuntimeVerdict(val stop: StableFailure?, val notice: RuntimeNotice?)

internal enum class RuntimeNotice { MONITORING_REDUCED, STATE_UNKNOWN, AV_DRIFT }

/**
 * One pass of the live thermal and storage policy (ADR-0016). [platformThermal] is
 * `PowerManager.currentThermalStatus` (null when it could not be read); [freeBytes] the free space
 * on the volume the take writes to. Strict stops on critical heat, on less than a minute of
 * space left, and on a state it cannot read; Adaptive keeps recording and warns.
 */
internal fun evaluateRuntimePolicy(policy: PolicyMode, platformThermal: Int?, freeBytes: Long?, projectedBytesPerMinute: Long): RuntimeVerdict {
    val projected = projectedBytesPerMinute.coerceAtLeast(1L)
    val observation = StorageRuntimeObservation(freeBytes, projected, calculateStorageReserve(projected).requiredBytes)
    val thermal = thermalStatusFromPlatform(platformThermal ?: Int.MIN_VALUE)
    return when (val decision = RuntimeHardeningPolicy(policy, "recording-ticker").evaluate(thermal, observation.pressure)) {
        is RuntimeHardeningDecision.Stopped -> RuntimeVerdict(decision.failure, null)
        is RuntimeHardeningDecision.Continue -> RuntimeVerdict(null, when (decision.action) {
            RuntimeAction.DEGRADE_MONITORING -> RuntimeNotice.MONITORING_REDUCED
            RuntimeAction.WARNING -> RuntimeNotice.STATE_UNKNOWN
            else -> null
        })
    }
}

/** Why a take was stopped, for the localized sentence the final message carries. */
internal enum class TakeStopReason { THERMAL, STORAGE, STATE_UNKNOWN, AV_DRIFT, CADENCE, AUDIO_INPUT_LOST, OTHER }

internal const val AV_DIAGNOSTICS_COMPONENT = "av-diagnostics"
internal const val AUDIO_INPUT_COMPONENT = "audio-input"

internal fun takeStopReason(failure: StableFailure): TakeStopReason = when {
    failure.component == AV_DIAGNOSTICS_COMPONENT -> TakeStopReason.AV_DRIFT
    failure.component == AUDIO_INPUT_COMPONENT -> TakeStopReason.AUDIO_INPUT_LOST
    failure.code == FailureCode.THERMAL_CRITICAL -> TakeStopReason.THERMAL
    failure.code == FailureCode.STORAGE_WRITE_FAILED -> TakeStopReason.STORAGE
    failure.code == FailureCode.UNKNOWN_CAPABILITY -> TakeStopReason.STATE_UNKNOWN
    failure.code == FailureCode.CADENCE_DISCONTINUITY -> TakeStopReason.CADENCE
    else -> TakeStopReason.OTHER
}

/** The failure the take is stopped with when its audio input disappears. */
internal fun audioInputLostFailure(): StableFailure = StableFailure(
    AUDIO_INPUT_COMPONENT, FailureCode.AUDIO_RUNTIME_FAILED, FailureSeverity.ERROR, Recoverability.USER_ACTION,
    "recording-ticker", "The audio input was disconnected; recording stopped.")

/** A sustained cadence break reported by the capture-result auditor. */
internal fun cadenceStopFailure(detail: String): StableFailure = StableFailure(
    "capture-cadence", FailureCode.CADENCE_DISCONTINUITY, FailureSeverity.CRITICAL, Recoverability.RETRYABLE,
    "recording-ticker", "Frame cadence was lost; recording stopped.", mapOf("detail" to detail))

/** The A/V drift diagnostics' verdict as a runtime verdict: Strict stops, Adaptive keeps a persistent notice. */
internal fun avDriftVerdict(snapshot: AvDiagnosticSnapshot): RuntimeVerdict = when (snapshot.action) {
    AvDiagnosticAction.STOP -> RuntimeVerdict(snapshot.failure, null)
    AvDiagnosticAction.WARNING -> RuntimeVerdict(null, RuntimeNotice.AV_DRIFT)
    AvDiagnosticAction.NONE -> RuntimeVerdict(null, null)
}
