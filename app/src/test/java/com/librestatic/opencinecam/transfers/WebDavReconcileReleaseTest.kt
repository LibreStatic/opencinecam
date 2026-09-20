/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import org.junit.Assert.*
import org.junit.Test

class WebDavReconcileReleaseTest {
    private val hash = "c".repeat(64)
    private val admission = WebDavOutboxAdmission(id(10), 9_007_199_254_740_993L,
        WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))

    @Test fun releaseChangesOnlyAttemptAndRevisionWhilePreservingAllUncertaintyEvidence() {
        val (before, lease) = active()
        val after = requireNotNull(WebDavOutboxTransitions.finishReconcile(before, lease))
        assertEquals(expectedRelease(before, lease), after)
        val artifact = after.artifacts.single { it.spec.id == lease.artifactId }
        assertEquals(WebDavArtifactState.UNCERTAIN, artifact.state)
        assertEquals(WebDavBundleState.UNCERTAIN, after.state)
        assertEquals(hash, artifact.sha256)
        assertEquals(9_007_199_254_740_993L, artifact.modifiedSeconds)
        assertTrue(artifact.remoteMayExist)
        assertEquals(admission, artifact.lastAdmission)
        assertNull(artifact.attempt)
    }

    @Test fun everyLeaseIdentityFenceRejectsWithoutMutatingTheBundle() {
        val (before, lease) = active()
        val stale = listOf(
            lease.copy(bundleId = id(90)), lease.copy(endpointId = id(91)),
            lease.copy(endpointRevision = lease.endpointRevision + 1),
            lease.copy(artifactId = id(3)), lease.copy(artifactId = id(92)),
            lease.copy(artifactRevision = lease.artifactRevision + 1),
            lease.copy(artifactRevision = lease.artifactRevision - 1),
            lease.copy(attempt = lease.attempt.copy(id = id(93))),
            lease.copy(attempt = lease.attempt.copy(processToken = id(94))),
            lease.copy(attempt = lease.attempt.copy(kind = WebDavAttemptKind.PUT)),
        )
        for (candidate in stale) assertNull(candidate.toString(), WebDavOutboxTransitions.finishReconcile(before, candidate))
        assertNotNull(WebDavOutboxTransitions.finishReconcile(before, lease))
    }

    @Test fun exactPutLeaseIsNeverAcceptedAsReconciliationRelease() {
        val (before, lease) = WebDavOutboxTransitions.claim(ready(), id(2), id(20), WebDavAttemptKind.PUT, admission)
        assertNull(WebDavOutboxTransitions.finishReconcile(before, lease))
        assertEquals(WebDavArtifactState.UPLOADING, before.artifacts.first().state)
        assertNotNull(WebDavOutboxTransitions.finishPut(before, lease, WebDavPutOutcome.REMOTE_UNCERTAIN))
    }

    @Test fun duplicateReleaseReturnsNullAndDoesNotAdvanceRevisionAgain() {
        val (before, lease) = active()
        val after = requireNotNull(WebDavOutboxTransitions.finishReconcile(before, lease))
        assertNull(WebDavOutboxTransitions.finishReconcile(after, lease))
        assertEquals(expectedRelease(before, lease), after)
    }

    @Test fun nextClaimRemainsUncertainAndOldCompletionsCannotAffectIt() {
        val (before, oldLease) = active()
        val released = requireNotNull(WebDavOutboxTransitions.finishReconcile(before, oldLease))
        assertThrows(IllegalArgumentException::class.java) {
            WebDavOutboxTransitions.claim(released, id(2), id(30), WebDavAttemptKind.PUT, admission)
        }
        val (next, nextLease) = WebDavOutboxTransitions.claim(released, id(2), id(31), WebDavAttemptKind.RECONCILE,
            admission.copy(processToken = id(11), policyRevision = admission.policyRevision + 1))
        assertEquals(WebDavArtifactState.UNCERTAIN, next.artifacts.first().state)
        assertTrue(next.artifacts.first().remoteMayExist)
        assertNull(WebDavOutboxTransitions.finishReconcile(next, oldLease))
        assertNull(WebDavOutboxTransitions.reconcile(next, oldLease, evidence(next)))
        assertNull(WebDavOutboxTransitions.finishPut(next, oldLease, WebDavPutOutcome.DEFINITELY_NOT_STARTED))
        assertEquals(expectedRelease(next, nextLease), WebDavOutboxTransitions.finishReconcile(next, nextLease))
    }

    @Test fun recoveredProcessLeaseCannotReleaseItsReplacement() {
        val (before, oldLease) = active()
        val recovered = WebDavOutboxTransitions.recover(before, admission.processToken)
        val (next, lease) = WebDavOutboxTransitions.claim(recovered, id(2), id(35), WebDavAttemptKind.RECONCILE,
            admission.copy(processToken = id(12)))
        assertNull(WebDavOutboxTransitions.finishReconcile(next, oldLease))
        assertNotNull(WebDavOutboxTransitions.finishReconcile(next, lease))
    }

    @Test fun completedRemoteEvidenceCannotBeDowngradedByLateRelease() {
        val (before, lease) = active()
        val verified = requireNotNull(WebDavOutboxTransitions.reconcile(before, lease, evidence(before)))
        assertEquals(WebDavArtifactState.VERIFIED, verified.artifacts.first().state)
        assertNull(WebDavOutboxTransitions.finishReconcile(verified, lease))
        assertEquals(WebDavArtifactState.VERIFIED, verified.artifacts.first().state)
    }

    private fun ready(): WebDavOutboxBundle {
        val plan = WebDavBundlePlan(id(1), id(9), 7L, listOf(
            WebDavArtifactSpec(id(2), WebDavArtifactRole.VIDEO, "content://media/external/video/media/1", "take.mp4", 321L),
            WebDavArtifactSpec(id(3), WebDavArtifactRole.VIDEO_METADATA, "content://media/external/downloads/2", "take.json", 123L),
        ))
        var bundle = WebDavOutboxTransitions.seal(WebDavOutboxTransitions.stage(plan), plan.artifacts.map(::publication))
        for (spec in plan.artifacts) bundle = WebDavOutboxTransitions.hash(bundle, publication(spec), hash)
        return bundle
    }

    private fun active(): Pair<WebDavOutboxBundle, WebDavOutboxLease> {
        val (put, putLease) = WebDavOutboxTransitions.claim(ready(), id(2), id(20), WebDavAttemptKind.PUT, admission)
        val uncertain = requireNotNull(WebDavOutboxTransitions.finishPut(put, putLease, WebDavPutOutcome.REMOTE_UNCERTAIN))
        return WebDavOutboxTransitions.claim(uncertain, id(2), id(21), WebDavAttemptKind.RECONCILE, admission)
    }

    private fun expectedRelease(before: WebDavOutboxBundle, lease: WebDavOutboxLease) = before.copy(
        revision = before.revision + 1, artifacts = before.artifacts.map {
            if (it.spec.id == lease.artifactId) it.copy(revision = it.revision + 1, attempt = null) else it
        })
    private fun publication(spec: WebDavArtifactSpec) = WebDavArtifactPublication(spec.id,
        WebDavClipSnapshot(spec.sourceUri, spec.sourceName, spec.sizeBytes, 9_007_199_254_740_993L, true))
    private fun evidence(bundle: WebDavOutboxBundle) = bundle.artifacts.first().spec.let {
        WebDavReconciliationEvidence(publication(it), hash, WebDavRemoteObservation.CompleteBody(it.sizeBytes, hash))
    }
    private fun id(number: Int) = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
}
