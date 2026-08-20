/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributionWorkflowTest {
    private val scope = DeviceProfileScope("fp", "0", null, "hevc", "p1", "digest")

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    @Test
    fun contributionPassesChecksRequiresReviewAndSupportsRedactedExport() {
        val fixture = "fixture-v1".toByteArray()
        val draft = ContributionDraft(
            contributionId = "c1",
            scope = scope,
            protocolVersion = "p1",
            fixtureId = "fixture-1",
            fixtureDigestHex = digest(fixture),
            fixturePassed = true,
            evidence = mapOf("codec" to "hevc", "result" to "pass"),
        )
        val workflow = ContributionWorkflow()
        assertEquals(ContributionAction.CREATED, workflow.create(draft))
        assertEquals(ContributionAction.DUPLICATE, workflow.create(draft))
        assertEquals(ContributionCheckStatus.PASS, workflow.regression("c1", fixture).status)
        assertTrue(workflow.validate("c1").all { it.status == ContributionCheckStatus.PASS })
        assertEquals(ContributionAction.SUBMITTED, workflow.submit("c1", ExportConsent.REDACTED_EXPLICIT))
        assertEquals(ContributionAction.INVALID_STATE, workflow.review("c1", ""))
        assertEquals(ContributionAction.REVIEWED, workflow.review("c1", "reviewer"))
        assertEquals(ContributionAction.SIGNED, workflow.sign("c1", "community-key"))
        assertNotNull(workflow.get("c1")?.signature)
        val redacted = workflow.export("c1", ExportConsent.REDACTED_EXPLICIT)!!
        assertFalse(redacted.sensitiveIncluded)
        assertNotEquals(scope.buildFingerprint, redacted.scope.buildFingerprint)
        val full = workflow.export("c1", ExportConsent.FULL_EXPLICIT)!!
        assertTrue(full.sensitiveIncluded)
        assertEquals(scope.buildFingerprint, full.scope.buildFingerprint)
        assertEquals(ContributionAction.REVOKED, workflow.revoke("c1", "fixture superseded"))
        assertEquals(ContributionState.REVOKED, workflow.get("c1")?.state)
    }

    @Test
    fun privateEvidenceAndFailedFixturesAreRejectedAndCleanupIsBounded() {
        val workflow = ContributionWorkflow(maxRecords = 1)
        val draft = ContributionDraft(
            contributionId = "private",
            scope = scope,
            protocolVersion = "p1",
            fixtureId = "fixture-2",
            fixtureDigestHex = "0".repeat(64),
            fixturePassed = false,
            evidence = mapOf("raw-frame-path" to "/private/path", "serial" to "secret"),
        )
        assertEquals(ContributionAction.CREATED, workflow.create(draft))
        assertEquals(ContributionAction.PRIVACY_REJECTED, workflow.submit("private", ExportConsent.REDACTED_EXPLICIT))
        assertEquals(ContributionState.REJECTED, workflow.get("private")?.state)
        assertEquals(ContributionAction.CAPACITY_EXCEEDED, workflow.create(draft.copy(contributionId = "second")))
        assertEquals(ContributionAction.INVALID_STATE, workflow.revoke("private", ""))
        workflow.clear()
        assertEquals(0, workflow.size())
        assertEquals(ContributionAction.NOT_FOUND, workflow.review("private", "reviewer"))
    }
}
