/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class EvidenceModelTest {
    private val key = CacheValidityKey(
        schemaMajor = 1,
        protocolVersion = "probe-1",
        appProbeVersion = "0.1.0",
        buildFingerprint = "test/fingerprint",
        characteristicsDigest = "sha256:characteristics",
        cameraId = "0",
        physicalCameraId = null,
        codecName = "c2.android.avc.encoder",
        graphId = GraphId("graph-001"),
    )

    private fun evidence(stage: EvidenceStage, at: String, status: EvidenceStatus = EvidenceStatus.PASS) =
        CapabilityEvidence(
            id = EvidenceId("evidence-${stage.name}"),
            stage = stage,
            status = status,
            source = "unit-test",
            observedAt = Instant.parse(at),
            protocolVersion = "probe-1",
            graphId = GraphId("graph-001"),
            validityKey = key,
            value = Knowledge.Known(true),
        )

    @Test
    fun unknownIsNotUnsupported() {
        assertNotSame(Knowledge.Unknown, Knowledge.Unsupported("not exposed"))
    }

    @Test
    fun trailRejectsBackwardsPromotionAndRetainsStaleEntries() {
        val first = evidence(EvidenceStage.RECORDED, "2026-08-20T00:00:00Z")
        val trail = EvidenceTrail<Boolean>().append(first)
        assertThrows(IllegalArgumentException::class.java) {
            trail.append(evidence(EvidenceStage.CANDIDATE, "2026-08-20T00:00:01Z"))
        }
        val changed = key.copy(buildFingerprint = "changed/fingerprint")
        val stale = first.copy(validityKey = changed).staleFor(key)
        assertEquals(EvidenceStatus.STALE, stale.status)
        assertEquals(1, trail.entries.size)
    }

    @Test
    fun trailIsImmutableAndOrdersEvents() {
        val first = evidence(EvidenceStage.CANDIDATE, "2026-08-20T00:00:00Z")
        val second = evidence(EvidenceStage.SESSION_CREATED, "2026-08-20T00:00:01Z")
        val trail = EvidenceTrail<Boolean>().append(first)
        val updated = trail.append(second)
        assertEquals(1, trail.entries.size)
        assertEquals(2, updated.entries.size)
    }
}
