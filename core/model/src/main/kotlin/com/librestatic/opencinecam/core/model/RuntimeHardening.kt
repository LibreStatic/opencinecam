/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

enum class ThermalStatus { UNKNOWN, NORMAL, MODERATE, SEVERE, CRITICAL }
enum class StoragePressure { UNKNOWN, SUFFICIENT, LOW, INSUFFICIENT }
enum class RuntimeAction { CONTINUE, WARNING, DEGRADE_MONITORING, STOP }

enum class DegradationStep(val order: Int) {
    UI_ANIMATION(0),
    VECTORSCOPE_WAVEFORM(1),
    FALSE_COLOR_PEAKING(2),
    HISTOGRAM_ZEBRA(3),
    ANALYSIS_STREAM(4),
    PREVIEW_RESOLUTION_FPS(5),
}

data class StorageRuntimeObservation(
    val freeBytes: Long?,
    val projectedBytes: Long,
    val reserveBytes: Long,
) {
    init { require(projectedBytes > 0 && reserveBytes > 0 && (freeBytes == null || freeBytes >= 0)) }

    val pressure: StoragePressure
        get() = freeBytes?.let { available ->
            when {
                available < projectedBytes -> StoragePressure.INSUFFICIENT
                available < projectedBytes + reserveBytes -> StoragePressure.LOW
                else -> StoragePressure.SUFFICIENT
            }
        } ?: StoragePressure.UNKNOWN
}

data class SoakTelemetrySample(
    val monotonicTimeUs: Long,
    val thermal: ThermalStatus,
    val storage: StoragePressure,
    val queueDepth: Int,
    val droppedFrames: Long,
    val writeErrors: Long,
) {
    init { require(monotonicTimeUs >= 0 && queueDepth >= 0 && droppedFrames >= 0 && writeErrors >= 0) }
}

data class RuntimeFault(
    val id: String,
    val code: FailureCode,
    val atMonotonicUs: Long,
    val details: String = "injected",
) {
    init { require(id.isNotBlank() && atMonotonicUs >= 0 && details.isNotBlank()) }
}

sealed interface RuntimeHardeningDecision {
    data class Continue(val action: RuntimeAction, val disclosure: String? = null) : RuntimeHardeningDecision
    data class Stopped(val failure: StableFailure) : RuntimeHardeningDecision
}

/** Runtime pressure/thermal policy with a deterministic, immutable degradation order. */
class RuntimeHardeningPolicy(
    private val mode: PolicyMode = PolicyMode.STRICT,
    private val correlationId: String = "runtime-hardening",
) {
    fun evaluate(thermal: ThermalStatus, storage: StoragePressure, fault: RuntimeFault? = null): RuntimeHardeningDecision {
        fault?.let {
            val failure = failure(it.code, if (it.code == FailureCode.THERMAL_CRITICAL) FailureSeverity.CRITICAL else FailureSeverity.ERROR, Recoverability.RETRYABLE, "Injected runtime fault ${it.id}.")
            return if (it.code == FailureCode.AUDIO_RUNTIME_FAILED && mode == PolicyMode.ADAPTIVE) RuntimeHardeningDecision.Continue(RuntimeAction.WARNING, "Audio stopped; video continues without audio.") else RuntimeHardeningDecision.Stopped(failure)
        }
        if (thermal == ThermalStatus.CRITICAL) return RuntimeHardeningDecision.Stopped(failure(FailureCode.THERMAL_CRITICAL, FailureSeverity.CRITICAL, Recoverability.RETRYABLE, "Thermal state is critical; recording stopped."))
        if (storage == StoragePressure.INSUFFICIENT) return RuntimeHardeningDecision.Stopped(failure(FailureCode.STORAGE_WRITE_FAILED, FailureSeverity.CRITICAL, Recoverability.RETRYABLE, "Free storage is insufficient; recording stopped."))
        if (thermal == ThermalStatus.UNKNOWN || storage == StoragePressure.UNKNOWN) {
            return if (mode == PolicyMode.STRICT) RuntimeHardeningDecision.Stopped(failure(FailureCode.UNKNOWN_CAPABILITY, FailureSeverity.WARNING, Recoverability.USER_ACTION, "Runtime thermal or storage state is unknown."))
            else RuntimeHardeningDecision.Continue(RuntimeAction.WARNING, "Runtime thermal or storage state is unknown.")
        }
        if (thermal == ThermalStatus.SEVERE || storage == StoragePressure.LOW) {
            return RuntimeHardeningDecision.Continue(RuntimeAction.DEGRADE_MONITORING, "Monitoring reduced in documented order; recording format is unchanged.")
        }
        return RuntimeHardeningDecision.Continue(RuntimeAction.CONTINUE)
    }

    fun nextDegradation(current: DegradationStep?): DegradationStep? = DegradationStep.entries.firstOrNull { current == null || it.order > current.order }

    private fun failure(code: FailureCode, severity: FailureSeverity, recoverability: Recoverability, message: String) = StableFailure("runtime-hardening", code, severity, recoverability, correlationId, message)
}

/** Deterministic fault source used by host soak tests; production code supplies no faults. */
class RuntimeFaultInjector(private val capacity: Int = 32) {
    init { require(capacity > 0) }
    private val faults = ArrayDeque<RuntimeFault>()

    fun inject(fault: RuntimeFault): Boolean {
        if (faults.size >= capacity) return false
        faults.addLast(fault)
        return true
    }

    fun poll(): RuntimeFault? = faults.removeFirstOrNull()
    fun clear() = faults.clear()
}

class SoakTelemetryRecorder(private val capacity: Int = 300) : AutoCloseable {
    init { require(capacity > 0) }
    private val samples = ArrayDeque<SoakTelemetrySample>()
    private var lastTimeUs = -1L
    private var closed = false

    fun record(sample: SoakTelemetrySample): Boolean {
        check(!closed) { "telemetry recorder is closed" }
        if (sample.monotonicTimeUs < lastTimeUs) return false
        lastTimeUs = sample.monotonicTimeUs
        if (samples.size >= capacity) samples.removeFirst()
        samples.addLast(sample)
        return true
    }

    fun snapshot(): List<SoakTelemetrySample> {
        check(!closed) { "telemetry recorder is closed" }
        return samples.toList()
    }

    override fun close() {
        samples.clear()
        closed = true
    }
}
