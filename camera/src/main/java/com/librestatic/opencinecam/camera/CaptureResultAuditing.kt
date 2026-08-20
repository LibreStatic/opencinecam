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
enum class CaptureAuditPolicy { STRICT, ADAPTIVE }

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

    fun submitRequest(record: CaptureRequestRecord): CaptureEvidenceEvent? {
        checkOpen()
        if (cancelled) return append(event(CaptureAuditEventType.CANCELLED, record.frameNumber, null, failure(FailureCode.CANCELLATION, Recoverability.CANCELLED, "Capture auditing was cancelled."), CaptureAuditAction.STOP))
        if (requests.containsKey(record.frameNumber)) return append(event(CaptureAuditEventType.CORRELATION_FAILURE, record.frameNumber, null, failure(FailureCode.DUPLICATE_COMMAND, Recoverability.USER_ACTION, "A capture request frame was submitted twice."), CaptureAuditAction.STOP))
        if (requests.size >= queueCapacity) requests.remove(requests.keys.first())
        requests[record.frameNumber] = record
        return null
    }

    fun auditResult(result: CaptureResultSample): List<CaptureEvidenceEvent> {
        checkOpen()
        if (cancelled) return listOfNotNull(append(event(CaptureAuditEventType.CANCELLED, result.frameNumber, result.sensorTimestampNs, failure(FailureCode.CANCELLATION, Recoverability.CANCELLED, "Capture auditing was cancelled."), CaptureAuditAction.STOP)))
        val request = requests.remove(result.frameNumber)
        if (request == null) return listOfNotNull(append(event(CaptureAuditEventType.CORRELATION_FAILURE, result.frameNumber, result.sensorTimestampNs, failure(FailureCode.STALE_EVIDENCE, Recoverability.RETRYABLE, "CaptureResult has no current request correlation."), CaptureAuditAction.STOP)))
        val output = mutableListOf<CaptureEvidenceEvent>()
        val timestampNs = result.sensorTimestampNs
        if (timestampNs == null) {
            output += event(CaptureAuditEventType.CORRELATION_FAILURE, result.frameNumber, null, failure(FailureCode.UNKNOWN_CAPABILITY, Recoverability.USER_ACTION, "CaptureResult sensor timestamp is unavailable."), CaptureAuditAction.STOP)
        } else {
            val timestampUs = timestampNs / 1_000L
            lastSensorTimestampNs?.let { previous ->
                val deltaUs = timestampUs - previous / 1_000L
                if (kotlin.math.abs(deltaUs - cadenceTargetUs) > cadenceToleranceUs) {
                    output += event(CaptureAuditEventType.CADENCE_FAILURE, result.frameNumber, timestampNs, failure(FailureCode.CADENCE_DISCONTINUITY, if (policy == CaptureAuditPolicy.STRICT) Recoverability.RETRYABLE else Recoverability.USER_ACTION, "Capture cadence differs from the requested interval."), if (policy == CaptureAuditPolicy.STRICT) CaptureAuditAction.STOP else CaptureAuditAction.WARNING, mapOf("deltaUs" to deltaUs.toString(), "targetUs" to cadenceTargetUs.toString()))
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
