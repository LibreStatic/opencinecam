/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.os.PowerManager
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.RuntimeAction
import com.librestatic.opencinecam.core.model.StoragePressure
import com.librestatic.opencinecam.core.model.StableFailure
import com.librestatic.opencinecam.core.model.ThermalStatus
import java.util.ArrayDeque

data class AnalysisStreamConfig(
    val width: Int,
    val height: Int,
    val maxQueueFrames: Int = 4,
    val maxPayloadBytes: Int = 4 * 1024 * 1024,
) {
    init { require(width > 0 && height > 0 && maxQueueFrames > 0 && maxPayloadBytes > 0) }
}

data class AnalysisFrame(
    val id: Long,
    val timestampNs: Long,
    val payload: ByteArray,
) {
    init { require(id >= 0 && timestampNs >= 0) }
}

sealed interface AnalysisSubmit {
    data object Accepted : AnalysisSubmit
    data class Dropped(val failure: StableFailure) : AnalysisSubmit
    data class Rejected(val failure: StableFailure) : AnalysisSubmit
}

enum class AnalysisGovernorAction { KEEP, DISABLE }

data class AnalysisGovernorDecision(
    val action: AnalysisGovernorAction,
    val reason: String? = null,
    val runtimeAction: RuntimeAction = RuntimeAction.CONTINUE,
)

/** Disables analysis before bounded capture pressure can affect recording. */
class AnalysisPerformanceGovernor(
    private val queueCapacity: Int,
    private val correlationId: String = "analysis-governor",
) {
    init { require(queueCapacity > 0) }
    private var disabled = false

    fun observe(queueDepth: Int, thermal: ThermalStatus, storage: StoragePressure): AnalysisGovernorDecision {
        require(queueDepth >= 0)
        if (disabled) return AnalysisGovernorDecision(AnalysisGovernorAction.DISABLE, "Analysis was disabled after a pressure event.", RuntimeAction.DEGRADE_MONITORING)
        if (thermal == ThermalStatus.CRITICAL || storage == StoragePressure.INSUFFICIENT) {
            disabled = true
            return AnalysisGovernorDecision(AnalysisGovernorAction.DISABLE, "Analysis disabled before critical recording pressure.", RuntimeAction.DEGRADE_MONITORING)
        }
        if (thermal == ThermalStatus.SEVERE || storage == StoragePressure.LOW || queueDepth * 100 >= queueCapacity * 80) {
            disabled = true
            return AnalysisGovernorDecision(AnalysisGovernorAction.DISABLE, "Analysis disabled before queue or runtime pressure reached the recording path.", RuntimeAction.DEGRADE_MONITORING)
        }
        return AnalysisGovernorDecision(AnalysisGovernorAction.KEEP)
    }

    fun isDisabled(): Boolean = disabled
}

/** Maps `PowerManager.THERMAL_STATUS_*` to the model scale; values this build does not know stay UNKNOWN. */
fun thermalStatusFromPlatform(status: Int): ThermalStatus = when (status) {
    PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.NORMAL
    PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
    PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
    PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN ->
        ThermalStatus.CRITICAL
    else -> ThermalStatus.UNKNOWN
}

/**
 * Continuous thermal governor for the engine's scope analysis (direct YUV reader and GPU
 * pipeline readback). Unlike [AnalysisPerformanceGovernor] it never latches: it suspends at
 * SEVERE or worse and resumes only once the platform reports below MODERATE, so the scopes do
 * not flap while the status hovers around one boundary. MODERATE and unknown statuses hold the
 * current state. Not thread-safe; the engine drives it from its camera executor.
 */
class ThermalAnalysisGovernor {
    var state: AnalysisSuspension = AnalysisSuspension.NONE
        private set

    /** Feeds one `PowerManager.THERMAL_STATUS_*` value; returns the new state only when it changed. */
    fun observePlatformStatus(platformThermalStatus: Int): AnalysisSuspension? {
        val next = when (thermalStatusFromPlatform(platformThermalStatus)) {
            ThermalStatus.SEVERE, ThermalStatus.CRITICAL -> AnalysisSuspension.THERMAL
            ThermalStatus.NORMAL -> AnalysisSuspension.NONE
            else -> state
        }
        if (next == state) return null
        state = next
        return next
    }
}

class BoundedAnalysisStream(
    private val config: AnalysisStreamConfig,
    private val governor: AnalysisPerformanceGovernor = AnalysisPerformanceGovernor(config.maxQueueFrames),
    private val correlationId: String = "analysis-stream",
) : AutoCloseable {
    private val queue = ArrayDeque<AnalysisFrame>()
    private var lastId = -1L
    private var closed = false

    fun submit(frame: AnalysisFrame): AnalysisSubmit {
        check(!closed) { "analysis stream is closed" }
        if (frame.payload.size > config.maxPayloadBytes) return AnalysisSubmit.Rejected(failure(FailureCode.UNSUPPORTED_CAPABILITY, Recoverability.UNSUPPORTED, "Analysis frame exceeds the bounded payload limit."))
        if (frame.id <= lastId) return AnalysisSubmit.Rejected(failure(FailureCode.STALE_EVIDENCE, Recoverability.RETRYABLE, "Analysis frame ID is stale or duplicated."))
        lastId = frame.id
        if (governor.isDisabled()) return AnalysisSubmit.Dropped(failure(FailureCode.STORAGE_WRITE_FAILED, Recoverability.RETRYABLE, "Analysis was disabled by the performance governor."))
        if (queue.size >= config.maxQueueFrames) {
            governor.observe(queue.size, ThermalStatus.NORMAL, StoragePressure.SUFFICIENT)
            return AnalysisSubmit.Dropped(failure(FailureCode.STORAGE_WRITE_FAILED, Recoverability.RETRYABLE, "Analysis queue is full; frame dropped."))
        }
        queue.addLast(frame)
        return AnalysisSubmit.Accepted
    }

    fun poll(): AnalysisFrame? {
        check(!closed) { "analysis stream is closed" }
        return if (queue.isEmpty()) null else queue.removeFirst()
    }

    fun observePressure(thermal: ThermalStatus, storage: StoragePressure): AnalysisGovernorDecision = governor.observe(queue.size, thermal, storage)

    fun depth(): Int = queue.size

    override fun close() {
        queue.clear()
        closed = true
    }

    private fun failure(code: FailureCode, recoverability: Recoverability, message: String) = StableFailure(
        component = "analysis-stream",
        code = code,
        severity = FailureSeverity.WARNING,
        recoverability = recoverability,
        correlationId = correlationId,
        userMessage = message,
    )
}
