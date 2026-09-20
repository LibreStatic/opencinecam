/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.CapturePublicationObserver
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import java.util.UUID

enum class CaptureOutboxRegistrationStatus { STAGED, REGISTERED, ALREADY_REGISTERED, NOT_ENROLLED, BLOCKED }
enum class CaptureOutboxBlockReason { JOURNAL_NOT_COMMITTED, BINDING_MISMATCH, STATE_CHANGED, OPERATION_FAILED }
data class CaptureOutboxRegistration(val status: CaptureOutboxRegistrationStatus, val reason: CaptureOutboxBlockReason? = null)
enum class CaptureOutboxBridgeOperation { VALIDATE, JOURNAL_PREPARE, JOURNAL_READ, OUTBOX_PREPARE, JOURNAL_PUBLISH, OUTBOX_PUBLISH, JOURNAL_ABORT, RECOVERY }
data class CaptureOutboxBridgeProblem(val operation: CaptureOutboxBridgeOperation, val failure: Exception)

/** Finalizers catch this optional-bookkeeping error; it must never trigger capture compensation. */
class CapturePublicationOutboxFailure(val problems: List<CaptureOutboxBridgeProblem>) :
    IllegalStateException("Capture transfer registration requires attention") {
    init { problems.forEach { addSuppressed(it.failure) } }
}

/**
 * Publication bookkeeping only: never enrolls, uploads, changes live consent, repairs pending
 * rows, deletes captures, or closes injected stores. The caller owns their lifecycle.
 *
 * Live callbacks carry finalizer authority. Recovery instead requires a durable COMMITTED
 * receipt and an enrollment already written at REC admission; current preferences cannot enroll
 * historical captures. Callback errors are independent and retained even after a successful retry.
 */
class CapturePublicationOutboxBridge(
    val bundleId: String,
    private val journal: CapturePublicationObserver,
    private val loadJournal: (String) -> CapturePublicationReceipt?,
    private val enrollments: CaptureTransferEnrollmentStore,
    private val outbox: WebDavOutboxStore,
    private val probe: PreparedArtifactProbe,
) : CapturePublicationObserver {
    init { requirePublicationUuid(bundleId) }
    private var prepared: List<PreparedCaptureArtifact>? = null
    private var aborted = false
    private var publicationObserved = false
    private var enrollmentLoaded = false
    private var enrollment: CaptureTransferEnrollment? = null
    private val history = linkedMapOf<CaptureOutboxBridgeOperation, CaptureOutboxBridgeProblem>()
    val issues: List<CaptureOutboxBridgeProblem> @Synchronized get() = history.values.toList()
    @Volatile var lastRegistration: CaptureOutboxRegistration? = null
        private set

    @Synchronized override fun onPrepared(artifacts: List<PreparedCaptureArtifact>) {
        val errors = mutableListOf<CaptureOutboxBridgeProblem>()
        val bound = attempt(CaptureOutboxBridgeOperation.VALIDATE, errors) {
            check(!aborted)
            val candidate = CapturePublicationReceipt(bundleId, CapturePublicationState.PREPARED, artifacts).artifacts
            require(prepared == null || prepared == candidate)
            prepared = candidate
            candidate
        } ?: throw CapturePublicationOutboxFailure(errors)
        attempt(CaptureOutboxBridgeOperation.JOURNAL_PREPARE, errors) { journal.onPrepared(bound) }
        val receipt = attempt(CaptureOutboxBridgeOperation.JOURNAL_READ, errors) { checkedReceipt() }
        attempt(CaptureOutboxBridgeOperation.OUTBOX_PREPARE, errors) {
            val selected = enrollmentForCallbacks()
            if (selected == null) {
                lastRegistration = CaptureOutboxRegistration(CaptureOutboxRegistrationStatus.NOT_ENROLLED)
            } else {
                require(receipt?.state != CapturePublicationState.ABORTED)
                receipt?.let { require(it.artifacts == bound) }
                val existing = outbox.load(bundleId)
                if (existing?.sealed == true) {
                    require(receipt?.state == CapturePublicationState.COMMITTED)
                    register(bound, selected, allowCreate = false)
                } else {
                    val facts = inspect(bound, pending = true)
                    val plan = plan(selected, bound, facts)
                    val staged = outbox.stage(plan).also { requirePlan(it, plan) }
                    if (staged.sealed) register(bound, selected, allowCreate = false)
                    else lastRegistration = CaptureOutboxRegistration(CaptureOutboxRegistrationStatus.STAGED)
                }
            }
        }
        failIfAny(errors)
    }

    @Synchronized override fun onPublished(artifacts: List<PreparedCaptureArtifact>) {
        val errors = mutableListOf<CaptureOutboxBridgeProblem>()
        val bound = attempt(CaptureOutboxBridgeOperation.VALIDATE, errors) {
            check(!aborted)
            val candidate = CapturePublicationReceipt(bundleId, CapturePublicationState.COMMITTED, artifacts).artifacts
            val previous = prepared ?: checkedReceipt()?.takeIf { it.state != CapturePublicationState.ABORTED }?.artifacts
            require(previous != null && previous == candidate) { "No matching prepared publication" }
            publicationObserved = true
            candidate
        } ?: throw CapturePublicationOutboxFailure(errors)
        attempt(CaptureOutboxBridgeOperation.JOURNAL_PUBLISH, errors) { journal.onPublished(bound) }
        val receipt = attempt(CaptureOutboxBridgeOperation.JOURNAL_READ, errors) { checkedReceipt() }
        attempt(CaptureOutboxBridgeOperation.OUTBOX_PUBLISH, errors) {
            val selected = enrollmentForCallbacks()
            if (selected == null) {
                lastRegistration = CaptureOutboxRegistration(CaptureOutboxRegistrationStatus.NOT_ENROLLED)
            } else {
                require(receipt?.state != CapturePublicationState.ABORTED)
                receipt?.let { require(it.artifacts == bound) }
                // Existing stage + a real finalizer callback survives an independent journal write
                // failure. Recreating a missing stage additionally requires durable COMMITTED.
                register(bound, selected, allowCreate = receipt?.state == CapturePublicationState.COMMITTED)
            }
        }
        failIfAny(errors)
    }

    @Synchronized override fun onAborted() {
        val errors = mutableListOf<CaptureOutboxBridgeProblem>()
        attempt(CaptureOutboxBridgeOperation.VALIDATE, errors) { check(!publicationObserved) }
        failIfAny(errors)
        val receipt = attempt(CaptureOutboxBridgeOperation.JOURNAL_READ, errors) { checkedReceipt() }
        if (receipt?.state == CapturePublicationState.COMMITTED) {
            attempt(CaptureOutboxBridgeOperation.VALIDATE, errors) { error("Committed publication cannot be aborted") }
            failIfAny(errors)
        }
        aborted = true
        attempt(CaptureOutboxBridgeOperation.JOURNAL_ABORT, errors) { journal.onAborted() }
        // Any staged outbox stays unsealed. Never delete an output or infer a successful pair.
        failIfAny(errors)
    }

    /** Reopening is explicit and read-gated. No current settings or historical enrollment scan. */
    @Synchronized fun registerCommitted(bundleId: String = this.bundleId): CaptureOutboxRegistration {
        require(bundleId == this.bundleId)
        return try {
            if (aborted) throw Blocked(CaptureOutboxBlockReason.JOURNAL_NOT_COMMITTED)
            val receipt = checkedReceipt()
            if (receipt?.state != CapturePublicationState.COMMITTED) throw Blocked(CaptureOutboxBlockReason.JOURNAL_NOT_COMMITTED)
            val selected = enrollmentForCallbacks()
            if (selected == null) CaptureOutboxRegistration(CaptureOutboxRegistrationStatus.NOT_ENROLLED)
            else register(receipt.artifacts, selected, allowCreate = true)
        } catch (failure: Exception) {
            remember(CaptureOutboxBridgeOperation.RECOVERY, failure)
            CaptureOutboxRegistration(CaptureOutboxRegistrationStatus.BLOCKED,
                (failure as? Blocked)?.reason ?: CaptureOutboxBlockReason.OPERATION_FAILED)
        }.also { lastRegistration = it }
    }

    private fun enrollmentForCallbacks(): CaptureTransferEnrollment? {
        if (!enrollmentLoaded) {
            val loaded = enrollments.load(bundleId)
            require(loaded == null || loaded.bundleId == bundleId)
            enrollment = loaded
            enrollmentLoaded = true // A successful absence is frozen too; failures remain retryable.
        }
        return enrollment
    }

    private fun checkedReceipt(): CapturePublicationReceipt? = loadJournal(bundleId).also {
        require(it == null || it.bundleId == bundleId)
    }

    private fun inspect(artifacts: List<PreparedCaptureArtifact>, pending: Boolean): List<WebDavClipSnapshot> = artifacts.map { artifact ->
        probe.inspect(artifact, pending).also { source ->
            require(source.identity == artifact.uri && source.displayName == artifact.displayName)
            require(source.sizeBytes > 0 && source.modifiedSeconds >= 0 && source.finalized == !pending)
        }
    }

    private fun plan(enrollment: CaptureTransferEnrollment, artifacts: List<PreparedCaptureArtifact>, facts: List<WebDavClipSnapshot>) =
        WebDavBundlePlan(bundleId, enrollment.endpointId, enrollment.endpointRevision, artifacts.zip(facts).map { (artifact, source) ->
            WebDavArtifactSpec(artifactId(bundleId, artifact.role), WebDavArtifactRole.valueOf(artifact.role.name),
                artifact.uri, artifact.displayName, source.sizeBytes)
        })

    private fun register(artifacts: List<PreparedCaptureArtifact>, enrollment: CaptureTransferEnrollment, allowCreate: Boolean): CaptureOutboxRegistration {
        val facts = inspect(artifacts, pending = false) // Obtain the complete set before any SQL mutation.
        val expected = plan(enrollment, artifacts, facts)
        var current = outbox.load(bundleId)
        if (current == null) {
            if (!allowCreate) throw Blocked(CaptureOutboxBlockReason.JOURNAL_NOT_COMMITTED)
            current = outbox.stage(expected)
        }
        requirePlan(current, expected)
        val publications = artifacts.zip(facts).map { (artifact, source) -> WebDavArtifactPublication(artifactId(bundleId, artifact.role), source) }
        if (current.sealed) {
            requirePublicationTimes(current, publications)
            return CaptureOutboxRegistration(CaptureOutboxRegistrationStatus.ALREADY_REGISTERED).also { lastRegistration = it; publicationObserved = true }
        }
        val sealed = outbox.seal(bundleId, current.revision, publications)
        if (sealed != null) {
            requirePlan(sealed, expected)
            require(sealed.sealed)
            requirePublicationTimes(sealed, publications)
            return CaptureOutboxRegistration(CaptureOutboxRegistrationStatus.REGISTERED).also { lastRegistration = it; publicationObserved = true }
        }
        // A competing identical seal may win; never retry/rewrite a newer artifact lease or state.
        val raced = outbox.load(bundleId) ?: throw Blocked(CaptureOutboxBlockReason.STATE_CHANGED)
        requirePlan(raced, expected)
        if (!raced.sealed) throw Blocked(CaptureOutboxBlockReason.STATE_CHANGED)
        requirePublicationTimes(raced, publications)
        return CaptureOutboxRegistration(CaptureOutboxRegistrationStatus.ALREADY_REGISTERED).also { lastRegistration = it; publicationObserved = true }
    }

    private fun requirePlan(actual: WebDavOutboxBundle, expected: WebDavBundlePlan) {
        if (actual.id != expected.id || actual.endpointId != expected.endpointId || actual.endpointRevision != expected.endpointRevision ||
            actual.artifacts.map { it.spec }.toSet() != expected.artifacts.toSet()) throw Blocked(CaptureOutboxBlockReason.BINDING_MISMATCH)
    }

    private fun requirePublicationTimes(bundle: WebDavOutboxBundle, publications: List<WebDavArtifactPublication>) {
        if (bundle.artifacts.any { artifact -> artifact.modifiedSeconds != publications.single { it.artifactId == artifact.spec.id }.source.modifiedSeconds })
            throw Blocked(CaptureOutboxBlockReason.BINDING_MISMATCH)
    }

    private fun <T> attempt(operation: CaptureOutboxBridgeOperation, errors: MutableList<CaptureOutboxBridgeProblem>, action: () -> T): T? = try {
        action()
    } catch (failure: Exception) {
        errors.add(CaptureOutboxBridgeProblem(operation, failure))
        remember(operation, failure)
        if (operation in setOf(CaptureOutboxBridgeOperation.VALIDATE, CaptureOutboxBridgeOperation.OUTBOX_PREPARE, CaptureOutboxBridgeOperation.OUTBOX_PUBLISH)) {
            lastRegistration = CaptureOutboxRegistration(CaptureOutboxRegistrationStatus.BLOCKED,
                (failure as? Blocked)?.reason ?: CaptureOutboxBlockReason.OPERATION_FAILED)
        }
        null
    }

    private fun remember(operation: CaptureOutboxBridgeOperation, failure: Exception) {
        if (operation !in history) history[operation] = CaptureOutboxBridgeProblem(operation, failure)
    }

    private fun failIfAny(errors: List<CaptureOutboxBridgeProblem>) {
        if (errors.isNotEmpty()) throw CapturePublicationOutboxFailure(errors.toList())
    }

    private class Blocked(val reason: CaptureOutboxBlockReason) : IllegalStateException("Capture publication registration is blocked")

    companion object {
        /** Deterministic local identity, not authentication or a content-integrity primitive. */
        fun artifactId(bundleId: String, role: CaptureArtifactRole): String {
            requirePublicationUuid(bundleId)
            return UUID.nameUUIDFromBytes("opencinecam.webdav.artifact.v1/$bundleId/${role.name}".toByteArray(Charsets.UTF_8)).toString()
        }
    }
}
