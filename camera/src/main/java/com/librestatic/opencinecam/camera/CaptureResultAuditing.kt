/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure
import java.util.ArrayDeque

data class CaptureResultSample(
    val frameNumber: Long,
    val sensorTimestampNs: Long?,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(frameNumber >= 0)
        require(sensorTimestampNs == null || sensorTimestampNs >= 0)
        require(metadata.keys.none { it.isBlank() } && metadata.values.none { it.isBlank() })
    }
}

data class CaptureRequestRecord(
    val frameNumber: Long,
    val requestedAtNs: Long,
    val requestedValues: Map<String, String> = emptyMap(),
) {
    init {
        require(frameNumber >= 0 && requestedAtNs >= 0)
        require(requestedValues.keys.none { it.isBlank() } && requestedValues.values.none { it.isBlank() })
    }
}

enum class CaptureAuditEventType { CORRELATED, VALUE_CHANGED, STABLE_SAMPLE, CADENCE_FAILURE, CORRELATION_FAILURE, CANCELLED }
enum class CaptureAuditAction { NONE, WARNING, STOP }
/**
 * STRICT stops on any deviation; ADAPTIVE warns on cadence. DIAGNOSTIC is the production mode:
 * results need no request correlation (repeating requests), every finding is at most a WARNING
 * and never a STOP, and [CaptureResultAuditor.diagnostics] accumulates per-graph statistics.
 */
enum class CaptureAuditPolicy { STRICT, ADAPTIVE, DIAGNOSTIC }

/** Per-graph DIAGNOSTIC statistics. Frame gaps count as dropped frames; failures are HAL-reported. */
data class CaptureAuditStats(
    val results: Long = 0,
    val cadenceOutliers: Long = 0,
    val droppedFrames: Long = 0,
    val failedCaptures: Long = 0,
    val missingTimestamps: Long = 0,
    val aeAfAnomalies: Long = 0,
    val maxFrameDeltaUs: Long = 0,
)

data class CaptureEvidenceEvent(
    val id: String,
    val type: CaptureAuditEventType,
    val frameNumber: Long?,
    val sensorTimestampNs: Long?,
    val details: Map<String, String> = emptyMap(),
    val failure: StableFailure? = null,
    val action: CaptureAuditAction = CaptureAuditAction.NONE,
) {
    init {
        require(id.isNotBlank())
        require(details.keys.none { it.isBlank() } && details.values.none { it.isBlank() })
    }
}

/** Metadata keys/values the engine supplies for DIAGNOSTIC AE/AF anomaly counting. */
const val AUDIT_AE_STATE = "aeState"
const val AUDIT_AF_STATE = "afState"
const val AUDIT_AE_SEARCHING = "SEARCHING"

/** Bounded CaptureResult correlation and 1 Hz/change evidence sampling. */
class CaptureResultAuditor(
    private val policy: CaptureAuditPolicy = CaptureAuditPolicy.STRICT,
    private val queueCapacity: Int = 128,
    private val cadenceTargetUs: Long = 33_333L,
    private val cadenceToleranceUs: Long = 5_000L,
    private val samplePeriodUs: Long = 1_000_000L,
    private val correlationId: String = "capture-audit",
) : AutoCloseable {
    init {
        require(queueCapacity > 0 && cadenceTargetUs > 0 && cadenceToleranceUs >= 0 && samplePeriodUs > 0)
    }

    private val requests = LinkedHashMap<Long, CaptureRequestRecord>()
    private val events = ArrayDeque<CaptureEvidenceEvent>()
    private val lastValues = mutableMapOf<String, String>()
    private var lastSensorTimestampNs: Long? = null
    private var lastSampleUs: Long? = null
    private var sequence = 0L
    private var cancelled = false
    private var closed = false
    private var stats = CaptureAuditStats()
    private var lastFrameNumber: Long? = null
    private var aeSearchingSinceUs: Long? = null
    private var aeSearchingReported = false
    private val diagnostic get() = policy == CaptureAuditPolicy.DIAGNOSTIC

    /** DIAGNOSTIC never stops a take: any STOP becomes a WARNING. */
    private fun actionFor(action: CaptureAuditAction) =
        if (diagnostic && action == CaptureAuditAction.STOP) CaptureAuditAction.WARNING else action

    /** Snapshot of the statistics gathered since this auditor (one per graph) was created. */
    fun diagnostics(): CaptureAuditStats = stats

    /** Counts a HAL-reported capture failure (onCaptureFailed) for DIAGNOSTIC summaries. */
    fun recordCaptureFailure() {
        checkOpen()
        stats = stats.copy(failedCaptures = stats.failedCaptures + 1)
    }

    fun submitRequest(record: CaptureRequestRecord): CaptureEvidenceEvent? {
        checkOpen()
        if (cancelled) return append(event(CaptureAuditEventType.CANCELLED, record.frameNumber, null, failure(FailureCode.CANCELLATION, Recoverability.CANCELLED, "Capture auditing was cancelled."), actionFor(CaptureAuditAction.STOP)))
        if (requests.containsKey(record.frameNumber)) return append(event(CaptureAuditEventType.CORRELATION_FAILURE, record.frameNumber, null, failure(FailureCode.DUPLICATE_COMMAND, Recoverability.USER_ACTION, "A capture request frame was submitted twice."), actionFor(CaptureAuditAction.STOP)))
        if (requests.size >= queueCapacity) requests.remove(requests.keys.first())
        requests[record.frameNumber] = record
        return null
    }

    fun auditResult(result: CaptureResultSample): List<CaptureEvidenceEvent> {
        checkOpen()
        if (cancelled) return listOfNotNull(append(event(CaptureAuditEventType.CANCELLED, result.frameNumber, result.sensorTimestampNs, failure(FailureCode.CANCELLATION, Recoverability.CANCELLED, "Capture auditing was cancelled."), actionFor(CaptureAuditAction.STOP))))
        val request = requests.remove(result.frameNumber)
        if (diagnostic) observeDiagnostics(result)
        if (request == null && !diagnostic) return listOfNotNull(append(event(CaptureAuditEventType.CORRELATION_FAILURE, result.frameNumber, result.sensorTimestampNs, failure(FailureCode.STALE_EVIDENCE, Recoverability.RETRYABLE, "CaptureResult has no current request correlation."), actionFor(CaptureAuditAction.STOP))))
        val output = mutableListOf<CaptureEvidenceEvent>()
        val timestampNs = result.sensorTimestampNs
        if (timestampNs == null) {
            output += event(CaptureAuditEventType.CORRELATION_FAILURE, result.frameNumber, null, failure(FailureCode.UNKNOWN_CAPABILITY, Recoverability.USER_ACTION, "CaptureResult sensor timestamp is unavailable."), actionFor(CaptureAuditAction.STOP))
        } else {
            val timestampUs = timestampNs / 1_000L
            lastSensorTimestampNs?.let { previous ->
                val deltaUs = timestampUs - previous / 1_000L
                if (kotlin.math.abs(deltaUs - cadenceTargetUs) > cadenceToleranceUs) {
                    output += event(CaptureAuditEventType.CADENCE_FAILURE, result.frameNumber, timestampNs, failure(FailureCode.CADENCE_DISCONTINUITY, if (policy == CaptureAuditPolicy.STRICT) Recoverability.RETRYABLE else Recoverability.USER_ACTION, "Capture cadence differs from the requested interval."), actionFor(if (policy == CaptureAuditPolicy.STRICT) CaptureAuditAction.STOP else CaptureAuditAction.WARNING), mapOf("deltaUs" to deltaUs.toString(), "targetUs" to cadenceTargetUs.toString()))
                }
            }
            lastSensorTimestampNs = timestampNs
            if (lastSampleUs == null || timestampUs - lastSampleUs!! >= samplePeriodUs) {
                output += event(CaptureAuditEventType.STABLE_SAMPLE, result.frameNumber, timestampNs, null, CaptureAuditAction.NONE, result.metadata)
                lastSampleUs = timestampUs
            }
        }
        result.metadata.forEach { (key, value) ->
            if (lastValues[key] != value) {
                output += event(CaptureAuditEventType.VALUE_CHANGED, result.frameNumber, timestampNs, null, CaptureAuditAction.NONE, mapOf("key" to key, "value" to value))
                lastValues[key] = value
            }
        }
        if (output.isEmpty()) output += event(CaptureAuditEventType.CORRELATED, result.frameNumber, timestampNs, null)
        output.forEach(::append)
        return output
    }

    private fun observeDiagnostics(result: CaptureResultSample) {
        var next = stats.copy(results = stats.results + 1)
        lastFrameNumber?.let { previous ->
            if (result.frameNumber > previous + 1) next = next.copy(droppedFrames = next.droppedFrames + (result.frameNumber - previous - 1))
        }
        lastFrameNumber = maxOf(result.frameNumber, lastFrameNumber ?: result.frameNumber)
        val timestampNs = result.sensorTimestampNs
        if (timestampNs == null) {
            next = next.copy(missingTimestamps = next.missingTimestamps + 1)
        } else {
            val timestampUs = timestampNs / 1_000L
            lastSensorTimestampNs?.let { previous ->
                val deltaUs = timestampUs - previous / 1_000L
                if (kotlin.math.abs(deltaUs - cadenceTargetUs) > cadenceToleranceUs) next = next.copy(cadenceOutliers = next.cadenceOutliers + 1)
                if (deltaUs > next.maxFrameDeltaUs) next = next.copy(maxFrameDeltaUs = deltaUs)
            }
            // AE/AF anomalies: a result missing either state, or AE searching for longer than one
            // sample period without converging (counted once per streak).
            val ae = result.metadata[AUDIT_AE_STATE]
            if (ae == null || result.metadata[AUDIT_AF_STATE] == null) next = next.copy(aeAfAnomalies = next.aeAfAnomalies + 1)
            if (ae == AUDIT_AE_SEARCHING) {
                val since = aeSearchingSinceUs ?: timestampUs.also { aeSearchingSinceUs = it }
                if (!aeSearchingReported && timestampUs - since > samplePeriodUs) {
                    aeSearchingReported = true
                    next = next.copy(aeAfAnomalies = next.aeAfAnomalies + 1)
                }
            } else {
                aeSearchingSinceUs = null
                aeSearchingReported = false
            }
        }
        stats = next
    }

    fun cancel() {
        checkOpen()
        cancelled = true
        requests.clear()
    }

    fun drainEvents(): List<CaptureEvidenceEvent> = events.toList().also { events.clear() }

    override fun close() {
        if (!closed) {
            requests.clear()
            events.clear()
            closed = true
        }
    }

    private fun append(value: CaptureEvidenceEvent): CaptureEvidenceEvent {
        if (events.size >= queueCapacity) events.removeFirst()
        events.addLast(value)
        return value
    }

    private fun event(type: CaptureAuditEventType, frame: Long?, timestampNs: Long?, failure: StableFailure? = null, action: CaptureAuditAction = CaptureAuditAction.NONE, details: Map<String, String> = emptyMap()) = CaptureEvidenceEvent("$correlationId-${++sequence}", type, frame, timestampNs, details, failure, action)

    private fun failure(code: FailureCode, recoverability: Recoverability, message: String) = StableFailure(
        component = "capture-audit",
        code = code,
        severity = if (code == FailureCode.UNKNOWN_CAPABILITY) FailureSeverity.WARNING else if (code == FailureCode.CADENCE_DISCONTINUITY) FailureSeverity.CRITICAL else FailureSeverity.ERROR,
        recoverability = recoverability,
        correlationId = correlationId,
        userMessage = message,
    )

    private fun checkOpen() {
        check(!closed) { "capture auditor is closed" }
    }
}
