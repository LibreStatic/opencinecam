/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityContinuityTest {
    @Test
    fun recreationAndBackgroundKeepRecordingAndNeverSwitchCamera() {
        val coordinator = ActivityContinuityCoordinator()
        coordinator.dispatch(ActivityContinuityCommand.SelectCamera("2"))
        coordinator.dispatch(ActivityContinuityCommand.StartRecording)
        val effects = coordinator.dispatch(ActivityContinuityCommand.ActivityRecreated(900, 600, foldSeparating = true))
        assertEquals("2", coordinator.state.selectedCameraId)
        assertEquals(RecordingContinuity.RECORDING, coordinator.state.recording)
        assertEquals(ProbeLayoutMode.FOLDABLE, coordinator.state.layout)
        assertTrue(effects.any { it.action == ActivityContinuityAction.KEEP_RECORDING && it.cameraId == "2" })
        assertTrue(coordinator.dispatch(ActivityContinuityCommand.Backgrounded).single().action == ActivityContinuityAction.KEEP_RECORDING)
    }

    @Test
    fun surfaceRecreationReattachesAndStaleSurfaceCannotDetachCurrentPreview() {
        val coordinator = ActivityContinuityCoordinator(ActivityContinuityState(selectedCameraId = "0"))
        coordinator.dispatch(ActivityContinuityCommand.SurfaceCreated("surface-a"))
        val stale = coordinator.dispatch(ActivityContinuityCommand.SurfaceDestroyed("surface-old")).single()
        assertEquals(ActivityContinuityAction.IGNORE_STALE_SURFACE, stale.action)
        assertEquals("surface-a", coordinator.state.previewSurfaceId)
        val detached = coordinator.dispatch(ActivityContinuityCommand.SurfaceDestroyed("surface-a")).single()
        assertEquals(ActivityContinuityAction.DETACH_PREVIEW, detached.action)
        val attached = coordinator.dispatch(ActivityContinuityCommand.SurfaceCreated("surface-b")).single()
        assertEquals(ActivityContinuityAction.REATTACH_PREVIEW, attached.action)
        assertEquals("surface-b", coordinator.state.previewSurfaceId)
    }
}
