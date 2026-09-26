/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine's production auditor mode: statistics only, never a STOP. */
class CaptureAuditDiagnosticTest {
    private fun sample(frame: Long, timestampUs: Long?, ae: String? = "2", af: String? = "2") = CaptureResultSample(
        frame, timestampUs?.times(1_000L),
        buildMap { ae?.let { put(AUDIT_AE_STATE, it) }; af?.let { put(AUDIT_AF_STATE, it) } },
    )

    @Test
    fun diagnosticModeNeverStopsEvenOnEveryStrictFailure() {
        val auditor = CaptureResultAuditor(CaptureAuditPolicy.DIAGNOSTIC, cadenceTargetUs = 33_333L, cadenceToleranceUs = 8_333L)
        val events = mutableListOf<CaptureEvidenceEvent>()
        // Uncorrelated repeating results, a missing timestamp, cadence jumps and a duplicate request.
        events += auditor.auditResult(sample(0, 0))
        events += auditor.auditResult(sample(1, 33_333))
        events += auditor.auditResult(sample(2, null))
        events += auditor.auditResult(sample(5, 300_000))
        events += auditor.auditResult(sample(6, 310_000, ae = null))
        auditor.submitRequest(CaptureRequestRecord(7, 1))
        auditor.submitRequest(CaptureRequestRecord(7, 2))?.let(events::add)
        auditor.recordCaptureFailure()
        auditor.cancel()
        events += auditor.auditResult(sample(8, 400_000))
        events += auditor.drainEvents()

        assertTrue(events.none { it.action == CaptureAuditAction.STOP })
        assertTrue(events.any { it.type == CaptureAuditEventType.CADENCE_FAILURE && it.action == CaptureAuditAction.WARNING })

        val stats = auditor.diagnostics()
        assertEquals(5L, stats.results)
        assertEquals(2L, stats.droppedFrames) // frames 3 and 4
        assertEquals(1L, stats.missingTimestamps)
        assertEquals(1L, stats.failedCaptures)
        assertEquals(1L, stats.aeAfAnomalies) // frame 6 lacks AE state
        assertTrue(stats.cadenceOutliers >= 2)
        assertEquals(266_667L, stats.maxFrameDeltaUs)
    }

    @Test
    fun steadyCadenceProducesNoOutliersAndLongAeSearchCountsOnce() {
        val auditor = CaptureResultAuditor(CaptureAuditPolicy.DIAGNOSTIC, cadenceTargetUs = 33_333L, cadenceToleranceUs = 8_333L)
        for (frame in 0L until 90L) {
            auditor.auditResult(sample(frame, frame * 33_333L, ae = AUDIT_AE_SEARCHING))
        }
        val stats = auditor.diagnostics()
        assertEquals(90L, stats.results)
        assertEquals(0L, stats.cadenceOutliers)
        assertEquals(0L, stats.droppedFrames)
        // ~3 s of continuous SEARCHING exceeds the 1 s sample period once, not per frame.
        assertEquals(1L, stats.aeAfAnomalies)
    }

    @Test
    fun strictModeStillStopsSoExistingSemanticsAreUnchanged() {
        val strict = CaptureResultAuditor(CaptureAuditPolicy.STRICT)
        val events = strict.auditResult(sample(0, 0))
        assertEquals(CaptureAuditAction.STOP, events.single().action)
    }
}
