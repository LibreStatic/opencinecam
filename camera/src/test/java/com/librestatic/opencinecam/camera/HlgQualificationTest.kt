/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HlgQualificationTest {
    private val supported = HlgCapabilityReport(
        apiLevel = 34,
        cameraHlg10 = HlgCapabilityState.SUPPORTED,
        cameraBt2020 = HlgCapabilityState.SUPPORTED,
        hevcMain10Surface = HlgCapabilityState.SUPPORTED,
        evidenceId = "fixture-supported",
    )

    @Test
    fun candidateRequiresEveryCapabilityAndUsesImmutableGraph() {
        val decision = HlgDecisionTable.evaluate(supported)
        assertEquals(HlgDecisionStatus.CANDIDATE, decision.status)
        assertNotNull(decision.graph)
        assertNull(decision.reason)
        assertEquals("HLG10", decision.graph?.dynamicRange)
        assertEquals("Main10", decision.graph?.codecProfile)
    }

    @Test
    fun unsupportedAndUnknownReasonsStayDistinct() {
        val unsupported = HlgDecisionTable.evaluate(
            supported.copy(apiLevel = 32),
        )
        assertEquals(HlgDecisionStatus.UNSUPPORTED, unsupported.status)
        assertEquals("api-level-below-33", unsupported.reason?.code)
        val unknown = HlgDecisionTable.evaluate(
            supported.copy(cameraHlg10 = HlgCapabilityState.UNKNOWN),
        )
        assertEquals(HlgDecisionStatus.UNKNOWN, unknown.status)
        assertEquals("camera-hlg10-unknown", unknown.reason?.code)
    }

    @Test
    fun commandLifecycleExposesDuplicateCancellationStaleAndCleanup() {
        val session = HlgDecisionSession(maxCommands = 2)
        assertEquals(HlgCommandStatus.CANCELLED, session.cancel("cancelled"))
        assertEquals(HlgCommandStatus.CANCELLED, session.evaluate("cancelled", supported))
        assertEquals(HlgCommandStatus.STARTED, session.evaluate("one", supported))
        assertEquals(HlgCommandStatus.DUPLICATE, session.evaluate("one", supported))
        assertEquals(HlgCommandStatus.STARTED, session.evaluate("two", supported))
        assertEquals(HlgCommandStatus.STALE, session.evaluate("three", supported))
        session.close()
        assertEquals(HlgCommandStatus.CLOSED, session.evaluate("after-close", supported))
    }
}
