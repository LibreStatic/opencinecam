/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.CapturePublicationObserver
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CapturePublicationOutboxBridgeTest {
    @Test fun unenrolledCaptureGetsJournalButNoOutboxOrProbeCalls() {
        val f = Fixture(enrolled = false)
        f.bridge.onPrepared(f.artifacts)
        f.bridge.onPublished(f.artifacts)
        assertEquals(CapturePublicationState.COMMITTED, f.journal.value?.state)
        assertEquals(CaptureOutboxRegistrationStatus.NOT_ENROLLED, f.bridge.lastRegistration?.status)
        assertEquals(0, f.store.stageCalls); assertEquals(0, f.probeCalls)
        assertEquals(0, f.enrollments.enrollCalls)
    }

    @Test fun absenceAtPreparationDoesNotBecomeConsentMidTake() {
        val f = Fixture(enrolled = false)
        f.bridge.onPrepared(f.artifacts)
        f.enrollments.value = enrollment()
        f.bridge.onPublished(f.artifacts)
        assertNull(f.store.value)
        assertEquals(CaptureOutboxRegistrationStatus.NOT_ENROLLED, f.bridge.lastRegistration?.status)
    }

    @Test fun preparesCompleteBundleWithDescriptorFactsAndDeterministicArtifactIds() {
        val f = Fixture()
        f.bridge.onPrepared(f.artifacts.reversed())
        val staged = requireNotNull(f.store.value)
        assertFalse(staged.sealed)
        assertEquals(2, staged.artifacts.size)
        assertEquals(setOf(100L, 50L), staged.artifacts.map { it.spec.sizeBytes }.toSet())
        assertEquals(enrollment().endpointId, staged.endpointId)
        assertTrue(f.pendingRequests.all { it })
        assertEquals(staged.artifacts.map { it.spec.id }.toSet(), f.artifacts.map { CapturePublicationOutboxBridge.artifactId(id(1), it.role) }.toSet())
    }

    @Test fun finalizedCallbackSealsAllArtifactsFromFreshPublishedSnapshots() {
        val f = Fixture()
        f.bridge.onPrepared(f.artifacts)
        f.bridge.onPublished(f.artifacts.reversed())
        assertTrue(requireNotNull(f.store.value).sealed)
        assertEquals(CaptureOutboxRegistrationStatus.REGISTERED, f.bridge.lastRegistration?.status)
        assertTrue(requireNotNull(f.store.value).artifacts.all { it.modifiedSeconds == 10L && it.sha256 == null })
        assertEquals(listOf(true, true, false, false), f.pendingRequests)
        assertEquals(1, f.store.sealCalls)
    }

    @Test fun journalPrepareFailureDoesNotPreventStageOrLiveFinalizerSeal() {
        val f = Fixture()
        f.journal.failPrepare = true
        val first = assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPrepared(f.artifacts) }
        assertEquals(CaptureOutboxBridgeOperation.JOURNAL_PREPARE, first.problems.single().operation)
        assertNotNull(f.store.value)
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPublished(f.artifacts) }
        assertTrue(requireNotNull(f.store.value).sealed)
        assertNull(f.journal.value) // No COMMITTED receipt was invented to justify the live callback.
        assertEquals(CaptureOutboxRegistrationStatus.REGISTERED, f.bridge.lastRegistration?.status)
    }

    @Test fun stageFailureDoesNotPreventJournalCommitAndCanRetryOnlyAfterCommit() {
        val f = Fixture()
        f.store.failStage = true
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPrepared(f.artifacts) }
        assertEquals(CapturePublicationState.PREPARED, f.journal.value?.state)
        f.store.failStage = false
        f.bridge.onPublished(f.artifacts)
        assertTrue(requireNotNull(f.store.value).sealed)
        assertEquals(CapturePublicationState.COMMITTED, f.journal.value?.state)
        assertEquals(CaptureOutboxBridgeOperation.OUTBOX_PREPARE, f.bridge.issues.single().operation)
        assertEquals(2, f.store.stageCalls)
    }

    @Test fun missingStageAndUncommittedJournalCannotBeRecreatedFromPendingFlags() {
        val f = Fixture()
        f.store.failStage = true
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPrepared(f.artifacts) }
        f.store.failStage = false
        f.journal.ignorePublish = true
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPublished(f.artifacts) }
        assertNull(f.store.value)
        assertEquals(CapturePublicationState.PREPARED, f.journal.value?.state)
    }

    @Test fun independentFailuresAreAggregatedAndHistoryIsBoundedByStage() {
        val f = Fixture()
        f.store.failStage = true
        f.journal.failPrepare = true
        repeat(20) {
            val error = assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPrepared(f.artifacts) }
            assertEquals(2, error.problems.size)
            assertEquals(2, error.suppressed.size)
        }
        assertEquals(2, f.bridge.issues.size)
        assertEquals(20, f.store.stageCalls)
    }

    @Test fun publicationMustMatchAnActuallyPreparedSetBeforeEitherCommitCallback() {
        val f = Fixture()
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPublished(f.artifacts) }
        assertEquals(0, f.journal.publishCalls)
        f.bridge.onPrepared(f.artifacts)
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPublished(f.artifacts.take(1)) }
        assertEquals(0, f.journal.publishCalls); assertEquals(0, f.store.sealCalls)
        assertFalse(requireNotNull(f.store.value).sealed)
    }

    @Test fun completeProbePassMustSucceedBeforeAnyNewStage() {
        val f = Fixture()
        f.failProbeRole = CaptureArtifactRole.VIDEO_METADATA
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPrepared(f.artifacts) }
        assertEquals(0, f.store.stageCalls)
        assertEquals(CapturePublicationState.PREPARED, f.journal.value?.state)
    }

    @Test fun wrongSourceIdentitySizeNameAndPendingTruthBlockWithoutSeal() {
        for (transform in listOf<(WebDavClipSnapshot) -> WebDavClipSnapshot>(
            { it.copy(identity = "content://media/external/video/media/99") }, { it.copy(sizeBytes = 0) },
            { it.copy(displayName = "other.mp4") }, { it.copy(finalized = false) }, { it.copy(sizeBytes = it.sizeBytes + 1) },
        )) {
            val f = Fixture()
            f.bridge.onPrepared(f.artifacts)
            val staged = f.store.value
            f.publishedTransform = transform
            assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPublished(f.artifacts) }
            assertEquals(staged, f.store.value)
            assertEquals(CapturePublicationState.COMMITTED, f.journal.value?.state)
        }
    }

    @Test fun committedRecoveryRequiresEnrollmentWithoutCallingEnroll() {
        val f = Fixture(enrolled = false)
        f.journal.value = CapturePublicationReceipt(id(1), CapturePublicationState.COMMITTED, f.artifacts)
        assertEquals(CaptureOutboxRegistrationStatus.NOT_ENROLLED, f.reopen().registerCommitted().status)
        assertEquals(0, f.enrollments.enrollCalls); assertEquals(0, f.store.stageCalls); assertEquals(0, f.probeCalls)
    }

    @Test fun preparedAbortedAndMissingReceiptsNeverAutoSealInRecovery() {
        for (state in listOf(null, CapturePublicationState.PREPARED, CapturePublicationState.ABORTED)) {
            val f = Fixture()
            f.journal.value = state?.let { CapturePublicationReceipt(id(1), it, f.artifacts) }
            val result = f.reopen().registerCommitted()
            assertEquals(CaptureOutboxRegistrationStatus.BLOCKED, result.status)
            assertEquals(CaptureOutboxBlockReason.JOURNAL_NOT_COMMITTED, result.reason)
            assertEquals(0, f.store.stageCalls); assertEquals(0, f.probeCalls)
        }
    }

    @Test fun committedRecoveryCreatesExactStageAndSealAfterEarlierSqlFailure() {
        val f = Fixture()
        f.store.failStage = true
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPrepared(f.artifacts) }
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPublished(f.artifacts) }
        assertEquals(CapturePublicationState.COMMITTED, f.journal.value?.state)
        f.store.failStage = false
        assertEquals(CaptureOutboxRegistrationStatus.REGISTERED, f.reopen().registerCommitted().status)
        val first = f.store.value
        assertEquals(CaptureOutboxRegistrationStatus.ALREADY_REGISTERED, f.reopen().registerCommitted().status)
        assertEquals(first, f.store.value)
        assertEquals(1, f.store.sealCalls)
    }

    @Test fun recoveryNeverRebindsAnExistingEndpointOrPreparedArtifactName() {
        val f = Fixture()
        f.bridge.onPrepared(f.artifacts)
        f.journal.onPublished(f.artifacts)
        val retained = f.store.value
        f.enrollments.value = enrollment().copy(endpointRevision = 5)
        val result = f.reopen().registerCommitted()
        assertEquals(CaptureOutboxBlockReason.BINDING_MISMATCH, result.reason)
        assertEquals(retained, f.store.value)
        assertEquals(0, f.store.sealCalls)
    }

    @Test fun repeatedRegistrationPreservesAnActiveLeaseAndRecordedHash() {
        val f = Fixture()
        f.bridge.onPrepared(f.artifacts)
        f.bridge.onPublished(f.artifacts)
        var bundle = requireNotNull(f.store.value)
        val artifact = bundle.artifacts.first()
        val source = WebDavArtifactPublication(artifact.spec.id,
            WebDavClipSnapshot(artifact.spec.sourceUri, artifact.spec.sourceName, artifact.spec.sizeBytes, 10, true))
        bundle = WebDavOutboxTransitions.hash(bundle, source, "a".repeat(64))
        val (active, lease) = WebDavOutboxTransitions.claim(bundle, artifact.spec.id, id(90), WebDavAttemptKind.PUT,
            WebDavOutboxAdmission(id(91), 0, WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI)))
        f.store.value = active
        assertEquals(CaptureOutboxRegistrationStatus.ALREADY_REGISTERED, f.reopen().registerCommitted().status)
        assertEquals(active, f.store.value)
        assertEquals(lease.attempt, f.store.value?.artifacts?.first()?.attempt)
        assertFalse(f.store.closed)
    }

    @Test fun changedPublishedTimestampCannotRewriteAnExistingSealedFingerprint() {
        val f = Fixture()
        f.bridge.onPrepared(f.artifacts); f.bridge.onPublished(f.artifacts)
        val sealed = f.store.value
        f.publishedTransform = { it.copy(modifiedSeconds = 11) }
        val result = f.reopen().registerCommitted()
        assertEquals(CaptureOutboxBlockReason.BINDING_MISMATCH, result.reason)
        assertEquals(sealed, f.store.value)
    }

    @Test fun staleSealCasDoesNotLoopOrResetNewerState() {
        val f = Fixture()
        f.bridge.onPrepared(f.artifacts)
        f.store.rejectSealCas = true
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPublished(f.artifacts) }
        assertEquals(1, f.store.sealCalls)
        assertFalse(requireNotNull(f.store.value).sealed)
        assertEquals(CapturePublicationState.COMMITTED, f.journal.value?.state)
    }

    @Test fun concurrentIdenticalSealIsAcceptedWithoutSecondWrite() {
        val f = Fixture()
        f.bridge.onPrepared(f.artifacts)
        f.store.competingSeal = true
        f.bridge.onPublished(f.artifacts)
        assertEquals(CaptureOutboxRegistrationStatus.ALREADY_REGISTERED, f.bridge.lastRegistration?.status)
        assertEquals(1, f.store.sealCalls)
    }

    @Test fun abortPreservesJournalTombstoneAndUnsealedOutboxWithoutDeletingAnything() {
        val f = Fixture()
        f.bridge.onPrepared(f.artifacts)
        val staged = f.store.value
        f.bridge.onAborted()
        assertEquals(CapturePublicationState.ABORTED, f.journal.value?.state)
        assertEquals(staged, f.store.value)
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPublished(f.artifacts) }
        assertEquals(CaptureOutboxRegistrationStatus.BLOCKED, f.reopen().registerCommitted().status)
        assertFalse(f.store.closed)
    }

    @Test fun corruptEnrollmentOrReceiptIsPreservedAsBlockedInsteadOfAbsentConsent() {
        val f = Fixture()
        f.journal.value = CapturePublicationReceipt(id(1), CapturePublicationState.COMMITTED, f.artifacts)
        f.enrollments.failLoad = true
        val bridge = f.reopen()
        assertEquals(CaptureOutboxRegistrationStatus.BLOCKED, bridge.registerCommitted().status)
        assertTrue(bridge.issues.single().failure is CaptureTransferEnrollmentCorruptData)
        assertEquals(0, f.store.stageCalls)
        f.enrollments.failLoad = false
        f.journal.failLoad = true
        assertEquals(CaptureOutboxRegistrationStatus.BLOCKED, f.reopen().registerCommitted().status)
        assertEquals(0, f.store.stageCalls)
    }

    @Test fun journalReadFailureDoesNotPreventIndependentLiveStageAndSeal() {
        val f = Fixture()
        f.journal.failLoad = true
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPrepared(f.artifacts) }
        assertNotNull(f.store.value)
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPublished(f.artifacts) }
        assertTrue(requireNotNull(f.store.value).sealed)
        assertEquals(CaptureOutboxRegistrationStatus.REGISTERED, f.bridge.lastRegistration?.status)
        assertEquals(CaptureOutboxBridgeOperation.JOURNAL_READ, f.bridge.issues.single().operation)
    }

    @Test fun lateAbortCannotRevokeSuccessfulPublicationOrItsReceipt() {
        val f = Fixture()
        f.bridge.onPrepared(f.artifacts); f.bridge.onPublished(f.artifacts)
        val saved = f.store.value
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onAborted() }
        assertEquals(CapturePublicationState.COMMITTED, f.journal.value?.state)
        assertEquals(saved, f.store.value)
        assertEquals(CaptureOutboxRegistrationStatus.ALREADY_REGISTERED, f.bridge.registerCommitted().status)
    }

    @Test fun noReopenedBridgeCanStageAnObservedAbortTombstone() {
        val f = Fixture()
        f.journal.onAborted()
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPrepared(f.artifacts) }
        assertEquals(0, f.store.stageCalls)
        assertEquals(CapturePublicationState.ABORTED, f.journal.value?.state)
    }

    @Test fun allFourVideoAudioAndMetadataRolesAreStagedAndSealedTogether() {
        val f = Fixture()
        val complete = f.artifacts + listOf(
            PreparedCaptureArtifact(CaptureArtifactRole.AUDIO, "content://media/external/audio/media/3", "take.wav"),
            PreparedCaptureArtifact(CaptureArtifactRole.AUDIO_METADATA, "content://media/external/downloads/4", "take.wav.json"),
        )
        f.bridge.onPrepared(complete)
        assertEquals(4, f.store.value?.artifacts?.size)
        assertFalse(requireNotNull(f.store.value).sealed)
        f.bridge.onPublished(complete.reversed())
        assertTrue(requireNotNull(f.store.value).sealed)
        assertEquals(WebDavArtifactRole.entries.toSet(), requireNotNull(f.store.value).artifacts.map { it.spec.role }.toSet())
        assertEquals(8, f.probeCalls)
    }

    @Test fun enrollmentForDifferentBundleCannotEnrollCurrentCapture() {
        val f = Fixture()
        f.enrollments.value = enrollment().copy(bundleId = id(99))
        assertThrows(CapturePublicationOutboxFailure::class.java) { f.bridge.onPrepared(f.artifacts) }
        assertEquals(0, f.store.stageCalls)
        assertEquals(CapturePublicationState.PREPARED, f.journal.value?.state)
    }

    private class Fixture(enrolled: Boolean = true) {
        val artifacts = listOf(
            PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, "content://media/external/video/media/1", "take.mp4"),
            PreparedCaptureArtifact(CaptureArtifactRole.VIDEO_METADATA, "content://media/external/downloads/2", "take.timing.json"),
        )
        val journal = Journal()
        val store = Outbox()
        val enrollments = Enrollments(if (enrolled) enrollment() else null)
        var probeCalls = 0
        val pendingRequests = mutableListOf<Boolean>()
        var failProbeRole: CaptureArtifactRole? = null
        var publishedTransform: (WebDavClipSnapshot) -> WebDavClipSnapshot = { it }
        val bridge = reopen()
        fun reopen() = CapturePublicationOutboxBridge(id(1), journal, { if (journal.failLoad) throw CapturePublicationJournalCorruptData() else journal.value }, enrollments, store,
            PreparedArtifactProbe { artifact, pending ->
                probeCalls++; pendingRequests.add(pending)
                if (artifact.role == failProbeRole) throw IOException("Fixture source missing")
                WebDavClipSnapshot(artifact.uri, artifact.displayName, if (artifact.role == CaptureArtifactRole.VIDEO) 100 else 50, 10, !pending)
                    .let { if (pending) it else publishedTransform(it) }
            })
    }

    private class Enrollments(var value: CaptureTransferEnrollment?) : CaptureTransferEnrollmentStore {
        var failLoad = false
        var enrollCalls = 0
        override fun enroll(value: CaptureTransferEnrollment): CaptureTransferEnrollment { enrollCalls++; error("Bridge must never enroll") }
        override fun load(bundleId: String): CaptureTransferEnrollment? { if (failLoad) throw CaptureTransferEnrollmentCorruptData(); return value }
    }

    private class Journal : CapturePublicationObserver {
        var value: CapturePublicationReceipt? = null
        var failPrepare = false
        var failLoad = false
        var ignorePublish = false
        var publishCalls = 0
        override fun onPrepared(artifacts: List<PreparedCaptureArtifact>) {
            if (failPrepare) throw IOException("Fixture journal unavailable")
            value = CapturePublicationTransitions.prepared(id(1), value, artifacts)
        }
        override fun onPublished(artifacts: List<PreparedCaptureArtifact>) {
            publishCalls++
            if (!ignorePublish) value = CapturePublicationTransitions.published(id(1), value, artifacts)
        }
        override fun onAborted() { value = CapturePublicationTransitions.aborted(id(1), value) }
    }

    private class Outbox : WebDavOutboxStore {
        var value: WebDavOutboxBundle? = null
        var failStage = false
        var rejectSealCas = false
        var competingSeal = false
        var stageCalls = 0
        var sealCalls = 0
        var closed = false
        override fun stage(plan: WebDavBundlePlan): WebDavOutboxBundle {
            stageCalls++
            if (failStage) throw IOException("Fixture SQL full")
            value?.let { require(it.id == plan.id && it.endpointId == plan.endpointId && it.endpointRevision == plan.endpointRevision && it.artifacts.map { row -> row.spec }.toSet() == plan.artifacts.toSet()); return it }
            return WebDavOutboxTransitions.stage(plan).also { value = it }
        }
        override fun load(bundleId: String): WebDavOutboxBundle? = value?.takeIf { it.id == bundleId }
        override fun list(limit: Int): List<WebDavOutboxBundle> = listOfNotNull(value).take(limit)
        override fun seal(bundleId: String, expectedRevision: Long, publications: List<WebDavArtifactPublication>): WebDavOutboxBundle? {
            sealCalls++
            if (rejectSealCas) return null
            val current = value ?: return null
            if (current.revision != expectedRevision || current.sealed) return null
            val next = WebDavOutboxTransitions.seal(current, publications)
            value = next
            return if (competingSeal) null else next
        }
        override fun recordHash(bundleId: String, expectedArtifactRevision: Long, publication: WebDavArtifactPublication, sha256: String): WebDavOutboxBundle? = error("Unexpected hash mutation")
        override fun claim(bundleId: String, artifactId: String, expectedArtifactRevision: Long, attemptId: String, kind: WebDavAttemptKind, admission: WebDavOutboxAdmission): WebDavOutboxLease? = error("Unexpected network claim")
        override fun finishPut(lease: WebDavOutboxLease, outcome: WebDavPutOutcome): Boolean = error("Unexpected upload completion")
        override fun finishReconcile(lease: WebDavOutboxLease): Boolean = error("Unexpected reconciliation release")
        override fun reconcile(lease: WebDavOutboxLease, evidence: WebDavReconciliationEvidence): Boolean = error("Unexpected reconciliation")
        override fun sourceUnavailable(bundleId: String, artifactId: String, expectedArtifactRevision: Long, failure: WebDavSourceFailure): Boolean = error("Unexpected source mutation")
        override fun sourceUnavailable(lease: WebDavOutboxLease, failure: WebDavSourceFailure): Boolean = error("Unexpected source mutation")
        override fun sourceRecovered(bundleId: String, expectedArtifactRevision: Long, publication: WebDavArtifactPublication, sha256: String): WebDavOutboxBundle? = error("Unexpected source mutation")
        override fun recoverProcess(deadProcessToken: String): Int = error("Unexpected lease recovery")
        override fun close() { closed = true }
    }

    companion object {
        private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
        private fun enrollment() = CaptureTransferEnrollment(id(1), id(9), 4, 7)
    }
}
