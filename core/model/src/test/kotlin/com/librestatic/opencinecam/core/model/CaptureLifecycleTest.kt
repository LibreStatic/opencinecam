/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLifecycleTest {
    @Test
    fun backgroundClosesPreviewOnlyAndRecordingSurvives() {
        val coordinator = CaptureLifecycleCoordinator()
        coordinator.open("owner", "0")
        coordinator.attachPreview("surface-1")
        coordinator.stateMachine.dispatch(CaptureCommand.PreviewConfigured)

        assertEquals(CaptureLifecycleEffect.PreviewClosedInBackground, coordinator.onActivityBackground())
        assertEquals(CaptureState.Stopped, coordinator.stateMachine.state)

        coordinator.open("owner", "0")
        coordinator.stateMachine.dispatch(CaptureCommand.PreviewConfigured)
        coordinator.prepareRecording("recording")
        coordinator.startRecording()
        assertEquals(
            CaptureLifecycleEffect.RecordingContinuesWithoutPreview,
            coordinator.onActivityBackground(),
        )
        assertTrue(coordinator.stateMachine.state is CaptureState.Recording)
    }

    @Test
    fun disconnectAndAvailabilityFollowDeterministicRecovery() {
        val coordinator = CaptureLifecycleCoordinator()
        coordinator.open("owner", "0")
        coordinator.stateMachine.dispatch(CaptureCommand.PreviewConfigured)
        coordinator.prepareRecording("recording")
        coordinator.startRecording()

        val disconnected = coordinator.onCameraDisconnected("device removed") as CaptureLifecycleEffect.Disconnected
        assertEquals(FailureCode.CAPTURE_OPEN_FAILED, disconnected.failure.code)
        assertTrue(coordinator.stateMachine.state is CaptureState.Failed)

        val recovery = coordinator.onCameraAvailable() as CaptureLifecycleEffect.RecoveryStarted
        assertEquals(listOf(CaptureCommand.PreviewConfigured), recovery.commands)
        assertTrue(coordinator.stateMachine.state is CaptureState.Opening)
    }

    @Test
    fun surfaceRecreationRejectsStaleIDsAndServiceRestartRequiresReprobe() {
        val coordinator = CaptureLifecycleCoordinator()
        assertTrue(coordinator.attachPreview("surface-1") is CaptureLifecycleEffect.Surface)
        val stale = coordinator.surfaceSession.markReady("surface-old") as PreviewSurfaceEvent.Rejected
        assertEquals(FailureCode.STALE_EVIDENCE, stale.failure.code)
        val snapshot = coordinator.snapshot()

        val effect = coordinator.onServiceRestarted(snapshot) as CaptureLifecycleEffect.ReprobeRequired
        assertEquals(snapshot, effect.snapshot)
        assertEquals(2L, coordinator.snapshot().serviceGeneration)
    }

    @Test
    fun unsupportedAndCleanupBranchesRemainExplicit() {
        val coordinator = CaptureLifecycleCoordinator()
        val rejected = coordinator.stateMachine.dispatch(CaptureCommand.StartRecording)
        assertEquals(FailureCode.INVALID_COMMAND, rejected.failure?.code)
        coordinator.attachPreview("surface-1")
        val detached = coordinator.detachPreview() as CaptureLifecycleEffect.Surface
        assertEquals(PreviewSurfaceEvent.Detached("surface-1"), detached.event)
    }
}
