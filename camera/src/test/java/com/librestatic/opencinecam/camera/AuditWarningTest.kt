/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuditWarningTest {
    @Test fun cadenceFindingsBecomeOneStableWarningText() {
        val auditor = CaptureResultAuditor(CaptureAuditPolicy.DIAGNOSTIC, cadenceTargetUs = 33_333L, cadenceToleranceUs = 1_000L)
        fun sample(frame: Long, ts: Long) = CaptureResultSample(frame, ts, mapOf(AUDIT_AE_STATE to "2", AUDIT_AF_STATE to "2"))
        auditor.auditResult(sample(1, 1_000_000_000L))
        val texts = auditor.auditResult(sample(2, 1_200_000_000L)).mapNotNull(::auditWarningText)
        assertEquals(1, texts.size)
        assertEquals(texts, auditor.auditResult(sample(3, 1_400_000_000L)).mapNotNull(::auditWarningText))
        assertNull(auditWarningText(CaptureEvidenceEvent("e", CaptureAuditEventType.CORRELATED, 1, null)))
    }
}
