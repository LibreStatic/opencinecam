/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.util.UUID
import java.io.InputStream

internal enum class WebDavWorkerHold { POLICY_CHANGED, NOT_FOUND, NOT_READY, AUTHENTICATION, DESTINATION_CHANGED }
internal enum class WebDavWorkerTransition { PUT_ACKNOWLEDGED, PUT_UNCERTAIN, PUT_NOT_STARTED, RECONCILED, RECONCILE_INCONCLUSIVE, SOURCE_UNAVAILABLE }
internal sealed interface WebDavWorkerResult {
    data class Held(val reason: WebDavWorkerHold) : WebDavWorkerResult
    data class Stopped(val reason: WebDavStopReason) : WebDavWorkerResult
    data class Applied(val transition: WebDavWorkerTransition) : WebDavWorkerResult
    data object Stale : WebDavWorkerResult
    /** No cause, URL, credentials or provider text escapes the operation boundary. */
    data object Attention : WebDavWorkerResult
}

/**
 * One actual artifact step. Not a scheduler or REC permission issuer. A coordinator must maintain
 * current consent/network/REC exclusion and call cancellation from outside this blocking executor.
 * The captured control owner covers source/hash/socket/SQL cleanup, including failed persistence.
 * Borrowed stores are not closed here. No history scan, remote overwrite or retry loop occurs.
 */
internal class WebDavArtifactWorker(
    private val store: WebDavOutboxStore,
    private val sources: WebDavArtifactSourceFactory,
    private val hasher: WebDavArtifactHasher,
    private val destinations: WebDavWorkerDestinationResolver,
    private val control: WebDavUploadControl,
    private val upload: WebDavUploadTransport,
    private val reconciler: WebDavRemoteReconciler,
    private val nextAttemptId: () -> String = { UUID.randomUUID().toString() },
) {
    fun runOne(bundleId: String, artifactId: String, admission: WebDavOutboxAdmission): WebDavWorkerResult {
        requireOutboxUuid(bundleId); requireOutboxUuid(artifactId)
        val entered = control.enter()
        val attempt = entered.attempt ?: return WebDavWorkerResult.Stopped(requireNotNull(entered.reason))
        val scope = WebDavOperationScope(attempt)
        return try {
            if (entered.policy != admission.policy) WebDavWorkerResult.Held(WebDavWorkerHold.POLICY_CHANGED)
            else runOwned(bundleId, artifactId, admission, scope)
        } catch (_: WebDavUploadControl.UploadStopped) {
            WebDavWorkerResult.Stopped(requireNotNull(scope.stopReason))
        } catch (_: Exception) {
            WebDavWorkerResult.Attention
        } finally { control.leave(attempt) }
    }

    private fun runOwned(bundleId: String, artifactId: String, admission: WebDavOutboxAdmission,
        scope: WebDavOperationScope): WebDavWorkerResult {
        scope.checkRunning()
        val bundle = store.load(bundleId) ?: return WebDavWorkerResult.Held(WebDavWorkerHold.NOT_FOUND)
        scope.checkRunning()
        var artifact = bundle.artifacts.singleOrNull { it.spec.id == artifactId }
            ?: return WebDavWorkerResult.Held(WebDavWorkerHold.NOT_FOUND)
        if (!bundle.sealed || artifact.attempt != null || artifact.state !in setOf(
                WebDavArtifactState.QUEUED, WebDavArtifactState.UNCERTAIN, WebDavArtifactState.SOURCE_UNAVAILABLE)) {
            return WebDavWorkerResult.Held(WebDavWorkerHold.NOT_READY)
        }
        val resolved = destinations.resolve(bundle.endpointId, bundle.endpointRevision)
        scope.checkRunning()
        if (resolved == null) return WebDavWorkerResult.Held(WebDavWorkerHold.AUTHENTICATION)
        if (resolved.endpointId != bundle.endpointId || resolved.endpointRevision != bundle.endpointRevision) {
            return WebDavWorkerResult.Held(WebDavWorkerHold.DESTINATION_CHANGED)
        }
        val initial = hasher.hash(artifact, scope)
        scope.checkRunning()
        when (initial) {
            is WebDavHashResult.Stopped -> return WebDavWorkerResult.Stopped(initial.reason)
            is WebDavHashResult.Unavailable -> return sourceUnavailable(bundleId, artifact, initial.failure)
            is WebDavHashResult.Hashed -> {
                if (!matches(artifact, initial)) return sourceUnavailable(bundleId, artifact, changed(artifact))
                if (artifact.state == WebDavArtifactState.SOURCE_UNAVAILABLE) {
                    artifact = store.sourceRecovered(bundleId, artifact.revision, initial.publication, initial.sha256)
                        ?.artifacts?.single { it.spec.id == artifactId } ?: return WebDavWorkerResult.Stale
                }
                scope.checkRunning()
                if (artifact.state == WebDavArtifactState.QUEUED) {
                    artifact = store.recordHash(bundleId, artifact.revision, initial.publication, initial.sha256)
                        ?.artifacts?.single { it.spec.id == artifactId } ?: return WebDavWorkerResult.Stale
                }
            }
        }
        scope.checkRunning()
        val kind = if (artifact.state == WebDavArtifactState.QUEUED) WebDavAttemptKind.PUT else WebDavAttemptKind.RECONCILE
        val lease = store.claim(bundleId, artifactId, artifact.revision, nextAttemptId(), kind, admission)
            ?: return WebDavWorkerResult.Stale
        var unresolved = true
        var putDispatched = false
        fun applied(transition: WebDavWorkerTransition, commit: () -> Boolean): WebDavWorkerResult {
            // Once terminal persistence starts, a failure remains unknown; never retry it in finally.
            unresolved = false
            return if (commit()) WebDavWorkerResult.Applied(transition) else WebDavWorkerResult.Stale
        }
        try {
            scope.checkRunning()
            if (kind == WebDavAttemptKind.PUT) {
                val delegate = sources.source(artifact.spec)
                val expected = (initial as WebDavHashResult.Hashed).publication.source
                val source = object : WebDavClipSource {
                    override fun snapshot(): WebDavClipSnapshot = delegate.snapshot().also {
                        if (it != expected) throw WebDavArtifactContentChanged()
                    }
                    override fun open(): InputStream {
                        snapshot() // Do not substitute a changed fingerprint between prehash and PUT.
                        return delegate.open()
                    }
                }
                scope.checkRunning()
                putDispatched = true // An unexpected transport exception is remotely ambiguous.
                val result = upload.upload(source, resolved.destination, scope, requireNotNull(artifact.sha256), artifact.spec.remoteName)
                val outcome = when (result) {
                    is WebDavUploadResult.Uploaded -> WebDavPutOutcome.ACKNOWLEDGED
                    is WebDavUploadResult.Failed -> if (result.remoteMayExist) WebDavPutOutcome.REMOTE_UNCERTAIN else WebDavPutOutcome.DEFINITELY_NOT_STARTED
                    is WebDavUploadResult.Stopped -> if (result.remoteMayExist) WebDavPutOutcome.REMOTE_UNCERTAIN else WebDavPutOutcome.DEFINITELY_NOT_STARTED
                }
                val committed = applied(when (outcome) {
                    WebDavPutOutcome.ACKNOWLEDGED -> WebDavWorkerTransition.PUT_ACKNOWLEDGED
                    WebDavPutOutcome.REMOTE_UNCERTAIN -> WebDavWorkerTransition.PUT_UNCERTAIN
                    WebDavPutOutcome.DEFINITELY_NOT_STARTED -> WebDavWorkerTransition.PUT_NOT_STARTED
                }) { store.finishPut(lease, outcome) }
                return if (committed is WebDavWorkerResult.Applied && result is WebDavUploadResult.Stopped)
                    WebDavWorkerResult.Stopped(result.reason) else committed
            }
            val remote = reconciler.inspect(resolved.destination, artifact.spec.remoteName, artifact.spec.sizeBytes, scope)
            scope.checkRunning()
            if (remote == WebDavRemoteObservation.Inconclusive) {
                return applied(WebDavWorkerTransition.RECONCILE_INCONCLUSIVE) { store.finishReconcile(lease) }
            }
            val finalHash = hasher.hash(artifact, scope)
            scope.checkRunning()
            return when (finalHash) {
                is WebDavHashResult.Stopped -> WebDavWorkerResult.Stopped(finalHash.reason)
                is WebDavHashResult.Unavailable -> applied(WebDavWorkerTransition.SOURCE_UNAVAILABLE) { store.sourceUnavailable(lease, finalHash.failure) }
                is WebDavHashResult.Hashed -> if (!matches(artifact, finalHash)) {
                    applied(WebDavWorkerTransition.SOURCE_UNAVAILABLE) { store.sourceUnavailable(lease, changed(artifact)) }
                } else applied(WebDavWorkerTransition.RECONCILED) {
                    store.reconcile(lease, WebDavReconciliationEvidence(finalHash.publication, finalHash.sha256, remote))
                }
            }
        } finally {
            if (unresolved) {
                // A stopped/error path must not invent local evidence or impersonate process death.
                // SQL failure remains visible; the control owner still leaves only after this returns.
                if (kind == WebDavAttemptKind.RECONCILE) store.finishReconcile(lease)
                else store.finishPut(lease, if (putDispatched) WebDavPutOutcome.REMOTE_UNCERTAIN else WebDavPutOutcome.DEFINITELY_NOT_STARTED)
            }
        }
    }

    private fun sourceUnavailable(bundleId: String, artifact: WebDavOutboxArtifact, failure: WebDavSourceFailure) =
        if (store.sourceUnavailable(bundleId, artifact.spec.id, artifact.revision, failure))
            WebDavWorkerResult.Applied(WebDavWorkerTransition.SOURCE_UNAVAILABLE) else WebDavWorkerResult.Stale

    private fun changed(artifact: WebDavOutboxArtifact) = WebDavSourceFailure(artifact.spec.sourceUri, WebDavSourceFailureReason.CONTENT_CHANGED)

    private fun matches(artifact: WebDavOutboxArtifact, hash: WebDavHashResult.Hashed): Boolean {
        val source = hash.publication.source
        return hash.publication.artifactId == artifact.spec.id && source.identity == artifact.spec.sourceUri &&
            source.displayName == artifact.spec.sourceName && source.sizeBytes == artifact.spec.sizeBytes &&
            source.modifiedSeconds == artifact.modifiedSeconds && source.finalized &&
            (artifact.sha256 == null || artifact.sha256 == hash.sha256)
    }
}
