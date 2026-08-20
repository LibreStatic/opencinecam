/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.StoragePressure
import com.librestatic.opencinecam.core.model.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisStreamTest {
    @Test
    fun boundedStreamAcceptsAndDropsWithoutBlockingCapture() {
        val stream = BoundedAnalysisStream(AnalysisStreamConfig(320, 180, maxQueueFrames = 2, maxPayloadBytes = 8))
        assertTrue(stream.submit(AnalysisFrame(1, 1, ByteArray(4))) is AnalysisSubmit.Accepted)
        assertTrue(stream.submit(AnalysisFrame(2, 2, ByteArray(4))) is AnalysisSubmit.Accepted)
        assertTrue(stream.submit(AnalysisFrame(3, 3, ByteArray(4))) is AnalysisSubmit.Dropped)
        assertEquals(1L, stream.poll()!!.id)
        assertTrue(stream.submit(AnalysisFrame(4, 4, ByteArray(4))) is AnalysisSubmit.Dropped)
    }

    @Test
    fun governorDisablesBeforeThermalStorageOrQueuePressureAffectsRecording() {
        val governor = AnalysisPerformanceGovernor(4)
        assertEquals(AnalysisGovernorAction.KEEP, governor.observe(1, ThermalStatus.NORMAL, StoragePressure.SUFFICIENT).action)
        val low = governor.observe(1, ThermalStatus.SEVERE, StoragePressure.SUFFICIENT)
        assertEquals(AnalysisGovernorAction.DISABLE, low.action)
        assertEquals(AnalysisGovernorAction.DISABLE, governor.observe(0, ThermalStatus.NORMAL, StoragePressure.SUFFICIENT).action)

        val storage = AnalysisPerformanceGovernor(4).observe(0, ThermalStatus.NORMAL, StoragePressure.INSUFFICIENT)
        assertEquals(AnalysisGovernorAction.DISABLE, storage.action)
    }

    @Test
    fun staleAndOversizedFramesAreRejectedAndCloseIsDeterministic() {
        val stream = BoundedAnalysisStream(AnalysisStreamConfig(320, 180, maxQueueFrames = 2, maxPayloadBytes = 2))
        assertTrue(stream.submit(AnalysisFrame(2, 2, ByteArray(3))) is AnalysisSubmit.Rejected)
        assertTrue(stream.submit(AnalysisFrame(1, 1, ByteArray(1))) is AnalysisSubmit.Accepted)
        assertTrue(stream.submit(AnalysisFrame(1, 2, ByteArray(1))) is AnalysisSubmit.Rejected)
        stream.close()
    }
}
