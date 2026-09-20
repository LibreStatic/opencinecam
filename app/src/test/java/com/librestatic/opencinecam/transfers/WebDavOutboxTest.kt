/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import org.junit.Assert.*
import org.junit.Test

class WebDavOutboxTest {
    private val process = id(10)
    private val admission = WebDavOutboxAdmission(process, 4, WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))
    private val hash = "a".repeat(64)

    @Test fun unsealedBundleCannotHashOrClaimEvenIfItsRowsAppearPublished() {
        val staged = WebDavOutboxTransitions.stage(plan())
        assertEquals(WebDavBundleState.AWAITING_PUBLICATION, staged.state)
        assertThrows(IllegalArgumentException::class.java) { WebDavOutboxTransitions.hash(staged, publication(staged.artifacts.single().spec), hash) }
        assertThrows(IllegalArgumentException::class.java) { WebDavOutboxTransitions.claim(staged, id(2), id(20), WebDavAttemptKind.PUT, admission) }
    }

    @Test fun sealRequiresTheEntireExactDeclaredArtifactSet() {
        val staged = WebDavOutboxTransitions.stage(plan(withMetadata = true))
        val published = staged.artifacts.map { publication(it.spec) }
        assertThrows(IllegalArgumentException::class.java) { WebDavOutboxTransitions.seal(staged, published.take(1)) }
        assertThrows(IllegalArgumentException::class.java) { WebDavOutboxTransitions.seal(staged, listOf(published[0], published[0])) }
        assertThrows(IllegalArgumentException::class.java) {
            WebDavOutboxTransitions.seal(staged, published.map { it.copy(source = it.source.copy(sizeBytes = 99)) })
        }
        val sealed = WebDavOutboxTransitions.seal(staged, published)
        assertTrue(sealed.sealed)
        assertEquals(WebDavBundleState.QUEUED, sealed.state)
        assertEquals(1L, sealed.revision)
    }

    @Test fun pendingPublicationIsNotValidEvidence() {
        val spec = plan().artifacts.single()
        assertThrows(IllegalArgumentException::class.java) { publication(spec).copy(source = publication(spec).source.copy(finalized = false)) }
    }

    @Test fun sourceFingerprintAndHashAreImmutableAcrossRetry() {
        val sealed = seal(plan())
        val source = publication(sealed.artifacts.single().spec)
        assertThrows(IllegalArgumentException::class.java) { WebDavOutboxTransitions.hash(sealed, source.copy(source = source.source.copy(modifiedSeconds = 12)), hash) }
        val ready = WebDavOutboxTransitions.hash(sealed, source, hash)
        assertThrows(IllegalArgumentException::class.java) { WebDavOutboxTransitions.hash(ready, source, "b".repeat(64)) }
        assertEquals(hash, ready.artifacts.single().sha256)
    }

    @Test fun claimRecordsPolicyAndRequiresPreparedHash() {
        val sealed = seal(plan())
        assertThrows(IllegalArgumentException::class.java) { WebDavOutboxTransitions.claim(sealed, id(2), id(20), WebDavAttemptKind.PUT, admission) }
        val (claimed, lease) = claim(ready())
        assertEquals(WebDavBundleState.ACTIVE, claimed.state)
        assertEquals(admission, claimed.artifacts.single().lastAdmission)
        assertEquals(process, lease.attempt.processToken)
        assertEquals(claimed.artifacts.single().revision, lease.artifactRevision)
    }

    @Test fun serverAcknowledgementIsUncertainUntilIndependentVerification() {
        val (claimed, lease) = claim(ready())
        val finished = requireNotNull(WebDavOutboxTransitions.finishPut(claimed, lease, WebDavPutOutcome.ACKNOWLEDGED))
        assertEquals(WebDavArtifactState.UNCERTAIN, finished.artifacts.single().state)
        assertEquals(WebDavBundleState.UNCERTAIN, finished.state)
        assertNull(finished.artifacts.single().attempt)
        assertThrows(IllegalArgumentException::class.java) { claim(finished) }
    }

    @Test fun onlyDefinitelyUnsentPutCanReturnDirectlyToQueue() {
        val (claimed, lease) = claim(ready())
        assertEquals(WebDavArtifactState.QUEUED, WebDavOutboxTransitions.finishPut(claimed, lease, WebDavPutOutcome.DEFINITELY_NOT_STARTED)?.artifacts?.single()?.state)
        assertEquals(WebDavArtifactState.UNCERTAIN, WebDavOutboxTransitions.finishPut(claimed, lease, WebDavPutOutcome.REMOTE_UNCERTAIN)?.artifacts?.single()?.state)
    }

    @Test fun exactRemoteBodyAndFreshLocalHashAreBothRequiredForVerified() {
        val (claimed, lease) = reconcileClaim()
        val verified = requireNotNull(WebDavOutboxTransitions.reconcile(claimed, lease, evidence(claimed, WebDavRemoteObservation.CompleteBody(100, hash))))
        assertEquals(WebDavBundleState.COMPLETE, verified.state)
        assertEquals(WebDavArtifactState.VERIFIED, verified.artifacts.single().state)
        assertNull(WebDavOutboxTransitions.reconcile(verified, lease, evidence(verified, WebDavRemoteObservation.CompleteBody(100, hash))))
    }

    @Test fun remoteDigestOrLengthMismatchCreatesConflictWithoutRetry() {
        for (remote in listOf(WebDavRemoteObservation.CompleteBody(99, hash), WebDavRemoteObservation.CompleteBody(100, "b".repeat(64)))) {
            val (claimed, lease) = reconcileClaim()
            val conflicted = requireNotNull(WebDavOutboxTransitions.reconcile(claimed, lease, evidence(claimed, remote)))
            assertEquals(WebDavBundleState.ATTENTION, conflicted.state)
            assertEquals(WebDavArtifactState.CONFLICT, conflicted.artifacts.single().state)
            assertThrows(IllegalArgumentException::class.java) { claim(conflicted) }
        }
    }

    @Test fun remote404AllowsRetryOnlyWhenLocalFingerprintStillMatches() {
        val (claimed, lease) = reconcileClaim()
        val retry = requireNotNull(WebDavOutboxTransitions.reconcile(claimed, lease, evidence(claimed, WebDavRemoteObservation.NotFound404)))
        assertEquals(WebDavArtifactState.QUEUED, retry.artifacts.single().state)
        assertNotNull(claim(retry))
        val changed = evidence(claimed, WebDavRemoteObservation.NotFound404).copy(localSha256 = "b".repeat(64))
        assertEquals(WebDavArtifactState.SOURCE_UNAVAILABLE, WebDavOutboxTransitions.reconcile(claimed, lease, changed)?.artifacts?.single()?.state)
    }

    @Test fun inconclusiveReconciliationRemainsUncertain() {
        val (claimed, lease) = reconcileClaim()
        assertEquals(WebDavArtifactState.UNCERTAIN, WebDavOutboxTransitions.reconcile(claimed, lease, evidence(claimed, WebDavRemoteObservation.Inconclusive))?.artifacts?.single()?.state)
    }

    @Test fun retiredProcessRecoveryRevokesLatePutAndReconciliationCompletions() {
        val (uploading, uploadLease) = claim(ready())
        val recovered = WebDavOutboxTransitions.recover(uploading, process)
        assertEquals(WebDavArtifactState.UNCERTAIN, recovered.artifacts.single().state)
        assertNull(WebDavOutboxTransitions.finishPut(recovered, uploadLease, WebDavPutOutcome.ACKNOWLEDGED))
        val (reconciling, reconcileLease) = WebDavOutboxTransitions.claim(recovered, id(2), id(21), WebDavAttemptKind.RECONCILE, admission)
        val secondRecovery = WebDavOutboxTransitions.recover(reconciling, process)
        assertNull(WebDavOutboxTransitions.reconcile(secondRecovery, reconcileLease, evidence(secondRecovery, WebDavRemoteObservation.NotFound404)))
        assertEquals(secondRecovery, WebDavOutboxTransitions.recover(secondRecovery, process))
    }

    @Test fun recoveryDoesNotStealAnotherProcessAttempt() {
        val (claimed, _) = claim(ready())
        assertEquals(claimed, WebDavOutboxTransitions.recover(claimed, id(11)))
    }

    @Test fun leaseFencesRevisionEndpointProcessAndAttempt() {
        val (claimed, lease) = claim(ready())
        for (stale in listOf(
            lease.copy(artifactRevision = lease.artifactRevision + 1), lease.copy(endpointRevision = lease.endpointRevision + 1),
            lease.copy(attempt = lease.attempt.copy(processToken = id(11))), lease.copy(attempt = lease.attempt.copy(id = id(22))),
            lease.copy(bundleId = id(99)),
        )) assertNull(WebDavOutboxTransitions.finishPut(claimed, stale, WebDavPutOutcome.ACKNOWLEDGED))
    }

    @Test fun bundleCompletesOnlyAfterEveryArtifactHasRemoteProof() {
        var bundle = seal(plan(withMetadata = true))
        for (artifact in bundle.artifacts.toList()) {
            bundle = WebDavOutboxTransitions.hash(bundle, publication(artifact.spec), hash)
            val (sending, putLease) = WebDavOutboxTransitions.claim(bundle, artifact.spec.id, id(20), WebDavAttemptKind.PUT, admission)
            bundle = requireNotNull(WebDavOutboxTransitions.finishPut(sending, putLease, WebDavPutOutcome.ACKNOWLEDGED))
            assertNotEquals(WebDavBundleState.COMPLETE, bundle.state)
            val (verifying, verifyLease) = WebDavOutboxTransitions.claim(bundle, artifact.spec.id, id(21), WebDavAttemptKind.RECONCILE, admission)
            bundle = requireNotNull(WebDavOutboxTransitions.reconcile(verifying, verifyLease,
                WebDavReconciliationEvidence(publication(artifact.spec), hash, WebDavRemoteObservation.CompleteBody(artifact.spec.sizeBytes, hash))))
        }
        assertEquals(WebDavBundleState.COMPLETE, bundle.state)
    }

    @Test fun duplicateRolesIdsUrisNamesAndMissingParentAudioAreRejected() {
        val plan = plan(withMetadata = true)
        val video = plan.artifacts[0]
        val metadata = plan.artifacts[1]
        for (bad in listOf(metadata.copy(id = video.id), metadata.copy(role = video.role), metadata.copy(sourceUri = video.sourceUri), metadata.copy(remoteName = video.remoteName))) {
            assertThrows(IllegalArgumentException::class.java) { plan.copy(artifacts = listOf(video, bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(artifacts = listOf(metadata)) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(artifacts = listOf(video, metadata.copy(role = WebDavArtifactRole.AUDIO_METADATA))) }
    }

    @Test fun validationRejectsSecretsAsIdsUnsafeNamesUrisAndNoncanonicalHashes() {
        val plan = plan()
        assertThrows(IllegalArgumentException::class.java) { plan.copy(endpointId = "https://user:secret@example/") }
        for (uri in listOf("file:///private/clip.mp4", "content://media/external/video/media", "content://media/external/video/media/0", "content://media/external/video/media/1?token=secret")) {
            assertThrows(IllegalArgumentException::class.java) { plan.artifacts.single().copy(sourceUri = uri) }
        }
        for (name in listOf("../file", "a\\b", "a\r\nb", "a".repeat(256))) {
            assertThrows(IllegalArgumentException::class.java) { plan.artifacts.single().copy(remoteName = name) }
        }
        for (bad in listOf("A".repeat(64), "g".repeat(64), "a".repeat(63))) {
            assertThrows(IllegalArgumentException::class.java) { WebDavRemoteObservation.CompleteBody(1, bad) }
        }
    }

    @Test fun ineligiblePolicyCannotMintAdmissionForPutOrReconciliation() {
        for (policy in listOf(WebDavUploadPolicy(), admission.policy.copy(recording = true),
            admission.policy.copy(network = WebDavNetwork.CELLULAR), admission.policy.copy(network = WebDavNetwork.OFFLINE))) {
            assertThrows(IllegalArgumentException::class.java) { WebDavOutboxAdmission(process, 4, policy) }
        }
        assertNotNull(admission.copy(policy = admission.policy.copy(network = WebDavNetwork.CELLULAR, allowCellular = true)))
    }

    @Test fun mediaItemAliasesCannotBypassBundleSourceUniqueness() {
        val spec = plan().artifacts.single()
        for (uri in listOf(
            "content://media/external/video/media/%31", "content://media/external/video/media/01",
            "content://media/external/video/media/+1", "content://media/%65xternal/video/media/1",
            "content://media/external/video/media/1/", "content://media/external/video/media/0001",
        )) assertThrows(IllegalArgumentException::class.java) { spec.copy(sourceUri = uri) }
        assertEquals(spec, spec.copy(sourceUri = "content://media/external/video/media/1"))
    }

    @Test fun integerLimitsAreRetainedAndRevisionOverflowNeverWraps() {
        val large = plan().copy(endpointRevision = Long.MAX_VALUE, artifacts = listOf(plan().artifacts.single().copy(sizeBytes = Long.MAX_VALUE)))
        val sealed = WebDavOutboxTransitions.seal(WebDavOutboxTransitions.stage(large), large.artifacts.map { publication(it, 9_007_199_254_740_993) })
        assertEquals(Long.MAX_VALUE, sealed.artifacts.single().spec.sizeBytes)
        assertEquals(9_007_199_254_740_993, sealed.artifacts.single().modifiedSeconds)
        val exhausted = ready().copy(revision = Long.MAX_VALUE)
        assertThrows(ArithmeticException::class.java) { claim(exhausted) }
        assertThrows(IllegalArgumentException::class.java) { large.copy(endpointRevision = -1) }
        assertThrows(IllegalArgumentException::class.java) { large.artifacts.single().copy(sizeBytes = 0) }
    }

    @Test fun unavailableSourceBeforeHashKeepsPublicationWithoutFabricatedRead() {
        val sealed = seal(plan())
        val original = sealed.artifacts.single()
        val failure = WebDavSourceFailure(original.spec.sourceUri, WebDavSourceFailureReason.MISSING)
        val missing = requireNotNull(WebDavOutboxTransitions.sourceUnavailable(sealed, original.spec.id, original.revision, failure))
        val stopped = missing.artifacts.single()
        assertEquals(WebDavArtifactState.SOURCE_UNAVAILABLE, stopped.state)
        assertEquals(original.modifiedSeconds, stopped.modifiedSeconds)
        assertNull(stopped.sha256); assertNull(stopped.lastAdmission); assertFalse(stopped.remoteMayExist)
        assertEquals(failure, stopped.sourceFailure)
        val restored = requireNotNull(WebDavOutboxTransitions.sourceRecovered(missing, stopped.revision, publication(original.spec), hash))
        assertEquals(WebDavArtifactState.QUEUED, restored.artifacts.single().state)
        assertEquals(hash, restored.artifacts.single().sha256)
        assertNull(restored.artifacts.single().sourceFailure)
    }

    @Test fun activePutSourceLossPreservesRemoteUncertaintyAndRevokesLease() {
        val (sending, lease) = claim(ready())
        assertTrue(sending.artifacts.single().remoteMayExist)
        val failure = WebDavSourceFailure(sending.artifacts.single().spec.sourceUri, WebDavSourceFailureReason.READ_FAILED)
        val missing = requireNotNull(WebDavOutboxTransitions.sourceUnavailable(sending, lease, failure))
        assertTrue(missing.artifacts.single().remoteMayExist)
        assertNull(missing.artifacts.single().attempt)
        assertNull(WebDavOutboxTransitions.finishPut(missing, lease, WebDavPutOutcome.DEFINITELY_NOT_STARTED))
        val restored = requireNotNull(WebDavOutboxTransitions.sourceRecovered(missing, missing.artifacts.single().revision,
            publication(missing.artifacts.single().spec), hash))
        assertEquals(WebDavArtifactState.UNCERTAIN, restored.artifacts.single().state)
        assertThrows(IllegalArgumentException::class.java) { claim(restored) }
    }

    @Test fun staleUnclaimedSourceObservationCannotRevokeAnActiveAttempt() {
        val ready = ready()
        val (sending, lease) = claim(ready)
        val failure = WebDavSourceFailure(ready.artifacts.single().spec.sourceUri, WebDavSourceFailureReason.ACCESS_DENIED)
        assertNull(WebDavOutboxTransitions.sourceUnavailable(sending, id(2), ready.artifacts.single().revision, failure))
        assertNull(WebDavOutboxTransitions.sourceUnavailable(sending, id(2), sending.artifacts.single().revision, failure))
        assertNotNull(WebDavOutboxTransitions.finishPut(sending, lease, WebDavPutOutcome.ACKNOWLEDGED))
    }

    @Test fun sourceFailureAndRecoveryCannotBeAppliedToAnotherItemOrChangedBytes() {
        val ready = ready()
        val artifact = ready.artifacts.single()
        assertThrows(IllegalArgumentException::class.java) {
            WebDavOutboxTransitions.sourceUnavailable(ready, artifact.spec.id, artifact.revision,
                WebDavSourceFailure("content://media/external/video/media/99", WebDavSourceFailureReason.MISSING))
        }
        val missing = requireNotNull(WebDavOutboxTransitions.sourceUnavailable(ready, artifact.spec.id, artifact.revision,
            WebDavSourceFailure(artifact.spec.sourceUri, WebDavSourceFailureReason.MISSING)))
        assertThrows(IllegalArgumentException::class.java) {
            WebDavOutboxTransitions.sourceRecovered(missing, missing.artifacts.single().revision, publication(artifact.spec), "b".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebDavOutboxTransitions.sourceRecovered(missing, missing.artifacts.single().revision, publication(artifact.spec, 12), hash)
        }
        assertNull(WebDavOutboxTransitions.sourceRecovered(missing, artifact.revision, publication(artifact.spec), hash))
    }

    @Test fun onlyQualified404OrDefinitelyUnsentOutcomeClearsRemotePresenceLatch() {
        val (sending, put) = claim(ready())
        val unsent = requireNotNull(WebDavOutboxTransitions.finishPut(sending, put, WebDavPutOutcome.DEFINITELY_NOT_STARTED))
        assertFalse(unsent.artifacts.single().remoteMayExist)
        val (verifying, lease) = reconcileClaim()
        val retry = requireNotNull(WebDavOutboxTransitions.reconcile(verifying, lease, evidence(verifying, WebDavRemoteObservation.NotFound404)))
        assertFalse(retry.artifacts.single().remoteMayExist)
        val verified = requireNotNull(WebDavOutboxTransitions.reconcile(verifying, lease, evidence(verifying, WebDavRemoteObservation.CompleteBody(100, hash))))
        assertTrue(verified.artifacts.single().remoteMayExist)
        val changed = requireNotNull(WebDavOutboxTransitions.reconcile(verifying, lease,
            evidence(verifying, WebDavRemoteObservation.NotFound404).copy(localSha256 = "b".repeat(64))))
        assertTrue(changed.artifacts.single().remoteMayExist)
        assertEquals(WebDavSourceFailureReason.CONTENT_CHANGED, changed.artifacts.single().sourceFailure?.reason)
    }

    @Test fun sourceLossDuringReconciliationRevokesItsLateRemoteProof() {
        val (verifying, lease) = reconcileClaim()
        val missing = requireNotNull(WebDavOutboxTransitions.sourceUnavailable(verifying, lease,
            WebDavSourceFailure(verifying.artifacts.single().spec.sourceUri, WebDavSourceFailureReason.ACCESS_DENIED)))
        assertNull(WebDavOutboxTransitions.reconcile(missing, lease, evidence(missing, WebDavRemoteObservation.NotFound404)))
        assertNull(WebDavOutboxTransitions.sourceUnavailable(missing, lease, requireNotNull(missing.artifacts.single().sourceFailure)))
    }

    @Test fun unsealedPublicationLossCannotInventASealedSourceFailure() {
        val staged = WebDavOutboxTransitions.stage(plan())
        assertThrows(IllegalArgumentException::class.java) {
            WebDavOutboxTransitions.sourceUnavailable(staged, id(2), 0,
                WebDavSourceFailure(staged.artifacts.single().spec.sourceUri, WebDavSourceFailureReason.MISSING))
        }
        assertEquals(WebDavBundleState.AWAITING_PUBLICATION, staged.state)
    }

    private fun plan(withMetadata: Boolean = false): WebDavBundlePlan {
        val video = WebDavArtifactSpec(id(2), WebDavArtifactRole.VIDEO, "content://media/external/video/media/1", "take.mp4", 100)
        val artifacts = if (withMetadata) listOf(video, WebDavArtifactSpec(id(3), WebDavArtifactRole.VIDEO_METADATA,
            "content://media/external/downloads/2", "take.timing.json", 50)) else listOf(video)
        return WebDavBundlePlan(id(1), id(9), 0, artifacts)
    }
    private fun publication(spec: WebDavArtifactSpec, modified: Long = 11) = WebDavArtifactPublication(spec.id,
        WebDavClipSnapshot(spec.sourceUri, spec.sourceName, spec.sizeBytes, modified, true))
    private fun seal(plan: WebDavBundlePlan) = WebDavOutboxTransitions.seal(WebDavOutboxTransitions.stage(plan), plan.artifacts.map { publication(it) })
    private fun ready(): WebDavOutboxBundle = seal(plan()).let { WebDavOutboxTransitions.hash(it, publication(it.artifacts.single().spec), hash) }
    private fun claim(bundle: WebDavOutboxBundle) = WebDavOutboxTransitions.claim(bundle, id(2), id(20), WebDavAttemptKind.PUT, admission)
    private fun reconcileClaim(): Pair<WebDavOutboxBundle, WebDavOutboxLease> {
        val (sending, lease) = claim(ready())
        return WebDavOutboxTransitions.claim(requireNotNull(WebDavOutboxTransitions.finishPut(sending, lease, WebDavPutOutcome.ACKNOWLEDGED)),
            id(2), id(21), WebDavAttemptKind.RECONCILE, admission)
    }
    private fun evidence(bundle: WebDavOutboxBundle, remote: WebDavRemoteObservation) =
        WebDavReconciliationEvidence(publication(bundle.artifacts.single().spec), hash, remote)
    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
