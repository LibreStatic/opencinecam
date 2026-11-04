// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaDngExportStatesTest {
    private val exported = CinemaDngExportOutcome.Exported("Documents/OpenCineCam/CinemaDNG/A", 5, 2, true, false, 100, true)

    @Test fun oneExportAtATime() {
        val states = CinemaDngExportStates()
        assertEquals(CinemaDngExportState.Idle, states.states.value)
        assertTrue(states.begin("content://1", "A"))
        assertFalse(states.begin("content://2", "B"))
        assertEquals(CinemaDngExportState.Running("content://1", "A"), states.states.value)
    }

    @Test fun progressKeepsTheCancelMark() {
        val states = CinemaDngExportStates()
        assertFalse(states.cancelling())
        states.begin("content://1", "A")
        val progress = CinemaDngExportProgress(3, 7, 1, 1_000)
        states.progress("content://1", "A", progress)
        assertTrue(states.cancelling())
        states.progress("content://1", "A", progress.copy(done = 4))
        assertEquals(CinemaDngExportState.Running("content://1", "A", progress.copy(done = 4), cancelling = true), states.states.value)
    }

    @Test fun finishedStaysUntilAcknowledgedAndFreesTheSlot() {
        val states = CinemaDngExportStates()
        states.begin("content://1", "A")
        states.acknowledge()
        assertTrue("a running export ignores the acknowledgement", states.states.value is CinemaDngExportState.Running)
        states.finish("content://1", "A", exported)
        assertEquals(CinemaDngExportState.Finished("content://1", "A", exported), states.states.value)
        assertTrue(states.begin("content://2", "B"))
        states.finish("content://2", "B", exported)
        states.acknowledge()
        assertEquals(CinemaDngExportState.Idle, states.states.value)
    }
}
