/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStateMachineTest {
    @Test
    fun actorSerializesTheAcceptedCaptureLifecycle() {
        val actor = SerializedCaptureActor(CaptureStateMachine())
        try {
            val opened = actor.submit(CaptureCommand.Open("owner-1", "0")).get(2, TimeUnit.SECONDS)
            val preview = actor.submit(CaptureCommand.PreviewConfigured).get(2, TimeUnit.SECONDS)
            val prepared = actor.submit(CaptureCommand.PrepareRecording("recording-1")).get(2, TimeUnit.SECONDS)
            val recording = actor.submit(CaptureCommand.StartRecording).get(2, TimeUnit.SECONDS)

            assertTrue(opened.accepted)
            assertTrue(preview.accepted)
            assertTrue(prepared.accepted)
            assertEquals(CaptureState.Recording("owner-1", "0", "recording-1"), recording.current)
        } finally {
            actor.close()
        }
    }

    @Test
    fun invalidTransitionIsStableAndDoesNotChangeState() {
        val machine = CaptureStateMachine()

        val transition = machine.dispatch(CaptureCommand.StartRecording)

        assertTrue(!transition.accepted)
        assertEquals(FailureCode.INVALID_COMMAND, transition.failure?.code)
        assertEquals(CaptureState.Stopped, transition.current)
    }

    @Test
    fun ownershipRejectsDuplicateOpenAndReleasesOnCleanup() {
        val ownership = HardwareOwnership()
        val first = CaptureStateMachine(ownership, "first")
        val second = CaptureStateMachine(ownership, "second")
        first.dispatch(CaptureCommand.Open("owner-1", "0"))

        val duplicate = second.dispatch(CaptureCommand.Open("owner-2", "0"))
        assertEquals(FailureCode.DUPLICATE_COMMAND, duplicate.failure?.code)

        first.dispatch(CaptureCommand.PreviewConfigured)
        first.dispatch(CaptureCommand.RequestStop)
        first.dispatch(CaptureCommand.StopCompleted)
        assertEquals(null, ownership.currentOwner())
        assertTrue(second.dispatch(CaptureCommand.Open("owner-2", "0")).accepted)
    }

    @Test
    fun cancellationAndUnsupportedFailureAreExplicit() {
        val machine = CaptureStateMachine()
        machine.dispatch(CaptureCommand.Open("owner-1", "0"))
        machine.dispatch(CaptureCommand.PreviewConfigured)

        val stopping = machine.dispatch(CaptureCommand.RequestStop)
        assertEquals(CaptureState.Stopping("owner-1", "0", null), stopping.current)
        machine.dispatch(CaptureCommand.StopCompleted)
        val unsupported = machine.dispatch(
            CaptureCommand.Fail(
                StableFailure(
                    component = "camera",
                    code = FailureCode.UNSUPPORTED_CAPABILITY,
                    severity = FailureSeverity.WARNING,
                    recoverability = Recoverability.UNSUPPORTED,
                    correlationId = "capture-1",
                    userMessage = "Requested camera route is unsupported.",
                ),
            ),
        )
        assertEquals(FailureCode.UNSUPPORTED_CAPABILITY, (unsupported.current as CaptureState.Failed).failure.code)
    }

    @Test
    fun staleEvidenceRemainsStaleRatherThanBecomingUnsupported() {
        val oldKey = key("old")
        val currentKey = key("current")
        val evidence = CapabilityEvidence(
            id = EvidenceId("evidence-1"),
            stage = EvidenceStage.SUSTAINED,
            status = EvidenceStatus.PASS,
            source = "test",
            observedAt = Instant.EPOCH,
            protocolVersion = "1",
            graphId = oldKey.graphId,
            validityKey = oldKey,
            value = Knowledge.Known(true),
        )

        assertEquals(EvidenceStatus.STALE, evidence.staleFor(currentKey).status)
        assertTrue(evidence.staleFor(currentKey).value is Knowledge.Known)
    }

    private fun key(suffix: String) = CacheValidityKey(
        schemaMajor = 1,
        protocolVersion = "1",
        appProbeVersion = "1",
        buildFingerprint = "fingerprint-$suffix",
        characteristicsDigest = "digest-$suffix",
        cameraId = "0",
        physicalCameraId = null,
        codecName = null,
        graphId = GraphId("graph-$suffix"),
    )
}
