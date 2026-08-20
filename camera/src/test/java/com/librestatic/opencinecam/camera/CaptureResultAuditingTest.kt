/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.FailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureResultAuditingTest {
    @Test
    fun correlatesResultsAndSamplesChangesAtOneHertz() {
        val auditor = CaptureResultAuditor()
        auditor.submitRequest(CaptureRequestRecord(1, 0, mapOf("ae" to "ON")))
        val first = auditor.auditResult(CaptureResultSample(1, 1_000_000_000, mapOf("iso" to "100")))
        assertTrue(first.any { it.type == CaptureAuditEventType.STABLE_SAMPLE })
        assertTrue(first.any { it.type == CaptureAuditEventType.VALUE_CHANGED })

        auditor.submitRequest(CaptureRequestRecord(2, 1_033_333_000))
        val second = auditor.auditResult(CaptureResultSample(2, 1_033_333_000, mapOf("iso" to "100")))
        assertTrue(second.any { it.type == CaptureAuditEventType.CORRELATED })
        assertTrue(second.none { it.type == CaptureAuditEventType.STABLE_SAMPLE })

        auditor.submitRequest(CaptureRequestRecord(3, 2_050_000_000))
        val third = auditor.auditResult(CaptureResultSample(3, 2_050_000_000, mapOf("iso" to "200")))
        assertTrue(third.any { it.type == CaptureAuditEventType.STABLE_SAMPLE })
        assertTrue(third.any { it.type == CaptureAuditEventType.VALUE_CHANGED })
    }

    @Test
    fun strictStopsCadenceFailureAndAdaptiveEmitsWarning() {
        val strict = CaptureResultAuditor()
        strict.submitRequest(CaptureRequestRecord(1, 0))
        strict.auditResult(CaptureResultSample(1, 1_000_000_000))
        strict.submitRequest(CaptureRequestRecord(2, 1_100_000_000))
        val stopped = strict.auditResult(CaptureResultSample(2, 1_100_000_000))
        assertEquals(CaptureAuditAction.STOP, stopped.single { it.type == CaptureAuditEventType.CADENCE_FAILURE }.action)
        assertEquals(FailureCode.CADENCE_DISCONTINUITY, stopped.single { it.type == CaptureAuditEventType.CADENCE_FAILURE }.failure?.code)

        val adaptive = CaptureResultAuditor(CaptureAuditPolicy.ADAPTIVE)
        adaptive.submitRequest(CaptureRequestRecord(1, 0))
        adaptive.auditResult(CaptureResultSample(1, 1_000_000_000))
        adaptive.submitRequest(CaptureRequestRecord(2, 1_100_000_000))
        val warning = adaptive.auditResult(CaptureResultSample(2, 1_100_000_000)).single { it.type == CaptureAuditEventType.CADENCE_FAILURE }
        assertEquals(CaptureAuditAction.WARNING, warning.action)
    }

    @Test
    fun duplicateStaleUnknownCancellationAndBoundedCleanupAreExplicit() {
        val auditor = CaptureResultAuditor(queueCapacity = 2)
        auditor.submitRequest(CaptureRequestRecord(1, 0))
        val duplicate = auditor.submitRequest(CaptureRequestRecord(1, 1))!!
        assertEquals(FailureCode.DUPLICATE_COMMAND, duplicate.failure?.code)
        val stale = auditor.auditResult(CaptureResultSample(99, 10))
        assertEquals(FailureCode.STALE_EVIDENCE, stale.single().failure?.code)

        auditor.submitRequest(CaptureRequestRecord(2, 0))
        val unknown = auditor.auditResult(CaptureResultSample(2, null))
        assertEquals(FailureCode.UNKNOWN_CAPABILITY, unknown.single().failure?.code)

        auditor.cancel()
        val cancelled = auditor.auditResult(CaptureResultSample(3, 20))
        assertEquals(FailureCode.CANCELLATION, cancelled.single().failure?.code)
        auditor.close()
        assertTrue(auditor.drainEvents().isEmpty())
    }
}
