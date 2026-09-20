/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

/**
 * Durable state only, not a network verifier or scheduler. Callers register a full publication
 * plan before publishing rows and seal only after the paired finalizer has actually succeeded.
 * No unsealed bundle is eligible. Ambiguous publication recovery requires a separate qualified
 * publisher; this store never infers whole-take success from individual IS_PENDING values.
 *
 * A null CAS result means a missing/stale revision or already-owned attempt, not success.
 * Semantic misuse and invalid/corrupt data throw; implementations never reset the queue silently.
 */
interface WebDavOutboxStore : AutoCloseable {
    /** Idempotent only for the identical plan; never modifies an existing endpoint or artifact set. */
    fun stage(plan: WebDavBundlePlan): WebDavOutboxBundle
    fun load(bundleId: String): WebDavOutboxBundle?
    fun list(limit: Int = 100): List<WebDavOutboxBundle>
    fun seal(bundleId: String, expectedRevision: Long, publications: List<WebDavArtifactPublication>): WebDavOutboxBundle?
    fun recordHash(bundleId: String, expectedArtifactRevision: Long, publication: WebDavArtifactPublication, sha256: String): WebDavOutboxBundle?
    /** Persist the lease before opening any socket. Admission reflects the caller's current policy. */
    fun claim(bundleId: String, artifactId: String, expectedArtifactRevision: Long, attemptId: String,
        kind: WebDavAttemptKind, admission: WebDavOutboxAdmission): WebDavOutboxLease?
    fun finishPut(lease: WebDavOutboxLease, outcome: WebDavPutOutcome): Boolean
    /** Release this exact reconciliation lease without manufacturing local/remote evidence. */
    fun finishReconcile(lease: WebDavOutboxLease): Boolean
    /** Requires a reconciliation lease acquired under eligible policy and fresh matching local bytes. */
    fun reconcile(lease: WebDavOutboxLease, evidence: WebDavReconciliationEvidence): Boolean
    /** No active attempt may be revoked by an unclaimed/hash observation, even with its revision. */
    fun sourceUnavailable(bundleId: String, artifactId: String, expectedArtifactRevision: Long, failure: WebDavSourceFailure): Boolean
    /** Invalidate this exact active attempt; the caller must also retire its live I/O owner. */
    fun sourceUnavailable(lease: WebDavOutboxLease, failure: WebDavSourceFailure): Boolean
    /** Fresh matching bytes only. A remotely ambiguous item returns to reconciliation, never PUT. */
    fun sourceRecovered(bundleId: String, expectedArtifactRevision: Long, publication: WebDavArtifactPublication, sha256: String): WebDavOutboxBundle?
    /** Only call for a process token authoritatively known to have retired; never steal a live worker. */
    fun recoverProcess(deadProcessToken: String): Int
}

class WebDavOutboxCorruptData : IllegalStateException("WebDAV outbox data requires recovery")
