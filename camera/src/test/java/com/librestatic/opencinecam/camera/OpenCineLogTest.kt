/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCineLogTest {
    private val gate = OpenCineLogGateEvaluator.evaluate(
        OpenCineLogGateInput(true, LogEvidenceState.VERIFIED, LogEvidenceState.UNSUPPORTED,
            LogEvidenceState.UNKNOWN),
    )

    @Test
    fun logVectorsAndRoundTripAreNumericallyStable() {
        assertTrue(OpenCineLogVectors.validate(OpenCineLogVectors.default()))
        assertTrue(LogNumericalEditor.roundTrip(doubleArrayOf(0.0, 0.18, 0.5, 1.0), 1e-6))
        val gpu = OpenCineLogCurve.encodeGpuReference(doubleArrayOf(0.18))
        assertEquals(TransformStatus.PASS, gpu.status)
        assertEquals("reference-gpu", gpu.metadata.implementation)
    }

    @Test
    fun provenanceGateRejectsUnverifiedBranchesAndUnknownIsDistinct() {
        assertEquals(LogGateStatus.READY, gate.status)
        assertTrue(LogProvenance.RAW_DERIVED in gate.acceptedBranches)
        val raw = LogInputIntegrator.integrate(gate, LogProvenance.RAW_DERIVED, doubleArrayOf(0.18))
        assertEquals(TransformStatus.PASS, raw.status)
        assertNotNull(raw.frame)
        val p010 = LogInputIntegrator.integrate(gate, LogProvenance.P010_DERIVED, doubleArrayOf(0.18))
        assertEquals(TransformStatus.UNSUPPORTED, p010.status)
        val unknownGate = OpenCineLogGateEvaluator.evaluate(
            OpenCineLogGateInput(true, LogEvidenceState.UNKNOWN, LogEvidenceState.UNKNOWN, LogEvidenceState.UNKNOWN),
        )
        assertEquals(LogGateStatus.UNKNOWN, unknownGate.status)
    }

    @Test
    fun editorClippingAndLutAreExplicit() {
        val clipped = LogNumericalEditor.applyGainOffset(doubleArrayOf(0.8), 2.0, 0.0)
        assertEquals(EditorStatus.CLIPPED, clipped.status)
        assertEquals(1.0, clipped.values?.single())
        val lut = Lut1D(doubleArrayOf(0.0, 0.5, 1.0), LogTransformMetadata("lut", "1", "hash", 1e-6))
        assertEquals(0.75, lut.lookup(0.75), 1e-9)
        val invalid = OpenCineLogCurve.encodeCpu(doubleArrayOf(-0.1))
        assertEquals(TransformStatus.INVALID, invalid.status)
        assertFalse(OpenCineLogVectors.validate(listOf(LogNumericVector(0.5, 0.0, 1e-9))))
    }

    @Test
    fun qualificationLifecycleExposesCancellationDuplicateAndCleanup() {
        val session = LogQualificationSession()
        assertEquals(LogQualificationStatus.CANCELLED, session.cancel("cancel"))
        assertEquals(LogQualificationStatus.CANCELLED,
            session.run("cancel", doubleArrayOf(0.2), 1e-6))
        assertEquals(LogQualificationStatus.STARTED,
            session.run("one", doubleArrayOf(0.2), 1e-6))
        assertEquals(LogQualificationStatus.DUPLICATE,
            session.run("one", doubleArrayOf(0.2), 1e-6))
        assertTrue(session.result("one") == true)
        session.close()
        assertEquals(LogQualificationStatus.CLOSED,
            session.run("two", doubleArrayOf(0.2), 1e-6))
    }
}
