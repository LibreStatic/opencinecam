/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.net.URI
import java.util.UUID

enum class WebDavArtifactRole { VIDEO, VIDEO_METADATA, AUDIO, AUDIO_METADATA }
enum class WebDavArtifactState { QUEUED, UPLOADING, UNCERTAIN, VERIFIED, CONFLICT, SOURCE_UNAVAILABLE }
enum class WebDavBundleState { AWAITING_PUBLICATION, QUEUED, ACTIVE, UNCERTAIN, ATTENTION, COMPLETE }
enum class WebDavAttemptKind { PUT, RECONCILE }
enum class WebDavPutOutcome { ACKNOWLEDGED, REMOTE_UNCERTAIN, DEFINITELY_NOT_STARTED }
enum class WebDavSourceFailureReason { MISSING, ACCESS_DENIED, READ_FAILED, CONTENT_CHANGED, LOCAL_RENAME_REQUESTED }

/** A failed source observation or explicit local-rename hold; never a successful source snapshot. */
data class WebDavSourceFailure(val sourceUri: String, val reason: WebDavSourceFailureReason) {
    init { requireMediaStoreItem(sourceUri) }
}

/** Opaque local IDs only: no endpoint URL, authorization header or credential enters the outbox. */
data class WebDavArtifactSpec(
    val id: String,
    val role: WebDavArtifactRole,
    val sourceUri: String,
    val sourceName: String,
    val sizeBytes: Long,
    val remoteName: String = sourceName,
) {
    init {
        requireOutboxUuid(id)
        requireMediaStoreItem(sourceUri)
        requireOutboxName(sourceName)
        requireOutboxName(remoteName)
        require(sizeBytes > 0)
    }
}

data class WebDavBundlePlan(
    val id: String,
    val endpointId: String,
    val endpointRevision: Long,
    val artifacts: List<WebDavArtifactSpec>,
) {
    init {
        requireOutboxUuid(id)
        requireOutboxUuid(endpointId)
        require(endpointRevision >= 0)
        requireCompleteArtifactSet(artifacts)
    }
}

/** Supplied only after the complete paired publication succeeds, never by a row observer. */
data class WebDavArtifactPublication(val artifactId: String, val source: WebDavClipSnapshot) {
    init {
        requireOutboxUuid(artifactId)
        require(source.finalized && source.modifiedSeconds >= 0)
        requireMediaStoreItem(source.identity)
        requireOutboxName(source.displayName)
        require(source.sizeBytes > 0)
    }
}

/** An admission observation, not a permission grant. The coordinator must still revoke live I/O. */
data class WebDavOutboxAdmission(
    val processToken: String,
    val policyRevision: Long,
    val policy: WebDavUploadPolicy,
) {
    init {
        requireOutboxUuid(processToken)
        require(policyRevision >= 0)
        require(policy.stopReason() == null) { "Upload policy does not admit network work" }
    }
}

data class WebDavOutboxAttempt(val id: String, val kind: WebDavAttemptKind, val processToken: String) {
    init { requireOutboxUuid(id); requireOutboxUuid(processToken) }
}

data class WebDavOutboxArtifact(
    val spec: WebDavArtifactSpec,
    val revision: Long = 0,
    val modifiedSeconds: Long? = null,
    val sha256: String? = null,
    val state: WebDavArtifactState = WebDavArtifactState.QUEUED,
    val attempt: WebDavOutboxAttempt? = null,
    val lastAdmission: WebDavOutboxAdmission? = null,
    /** Includes verified/conflicting remote objects; source recovery must never clear this latch. */
    val remoteMayExist: Boolean = false,
    val sourceFailure: WebDavSourceFailure? = null,
) {
    init {
        require(revision >= 0 && (modifiedSeconds == null || modifiedSeconds >= 0))
        sha256?.let(::requireOutboxHash)
        require(sha256 == null || modifiedSeconds != null)
        require(state in setOf(WebDavArtifactState.QUEUED, WebDavArtifactState.SOURCE_UNAVAILABLE) || sha256 != null)
        require(state in setOf(WebDavArtifactState.QUEUED, WebDavArtifactState.SOURCE_UNAVAILABLE) || lastAdmission != null)
        require(!remoteMayExist || (sha256 != null && lastAdmission != null))
        require(state != WebDavArtifactState.QUEUED || !remoteMayExist)
        require(state in setOf(WebDavArtifactState.QUEUED, WebDavArtifactState.SOURCE_UNAVAILABLE) || remoteMayExist)
        require((state == WebDavArtifactState.SOURCE_UNAVAILABLE) == (sourceFailure != null))
        require(sourceFailure == null || (sourceFailure.sourceUri == spec.sourceUri && modifiedSeconds != null && attempt == null))
        require((state == WebDavArtifactState.UPLOADING) == (attempt?.kind == WebDavAttemptKind.PUT))
        require(attempt?.kind != WebDavAttemptKind.RECONCILE || state == WebDavArtifactState.UNCERTAIN)
        require(attempt == null || lastAdmission?.processToken == attempt.processToken)
    }
}

data class WebDavOutboxBundle(
    val id: String,
    val endpointId: String,
    val endpointRevision: Long,
    val revision: Long,
    val sealed: Boolean,
    val artifacts: List<WebDavOutboxArtifact>,
) {
    init {
        requireOutboxUuid(id); requireOutboxUuid(endpointId)
        require(endpointRevision >= 0 && revision >= 0)
        requireCompleteArtifactSet(artifacts.map { it.spec })
        require(artifacts.all { it.revision <= revision })
        require(artifacts.all { (it.modifiedSeconds != null) == sealed })
        require(sealed || artifacts.all { it.revision == 0L && it.state == WebDavArtifactState.QUEUED && it.lastAdmission == null })
    }
    val state: WebDavBundleState get() = when {
        !sealed -> WebDavBundleState.AWAITING_PUBLICATION
        artifacts.all { it.state == WebDavArtifactState.VERIFIED } -> WebDavBundleState.COMPLETE
        artifacts.any { it.state == WebDavArtifactState.CONFLICT || it.state == WebDavArtifactState.SOURCE_UNAVAILABLE } -> WebDavBundleState.ATTENTION
        artifacts.any { it.attempt != null } -> WebDavBundleState.ACTIVE
        artifacts.any { it.state == WebDavArtifactState.UNCERTAIN } -> WebDavBundleState.UNCERTAIN
        else -> WebDavBundleState.QUEUED
    }
}

/** Artifact revision, process and attempt fence every completion, including late network callbacks. */
data class WebDavOutboxLease(
    val bundleId: String,
    val endpointId: String,
    val endpointRevision: Long,
    val artifactId: String,
    val artifactRevision: Long,
    val attempt: WebDavOutboxAttempt,
) {
    init {
        requireOutboxUuid(bundleId); requireOutboxUuid(endpointId); requireOutboxUuid(artifactId)
        require(endpointRevision >= 0 && artifactRevision > 0)
    }
}

/** Only a trusted network helper may report a fully-read remote body or authoritative 404. */
sealed interface WebDavRemoteObservation {
    data class CompleteBody(val sizeBytes: Long, val sha256: String) : WebDavRemoteObservation {
        init { require(sizeBytes >= 0); requireOutboxHash(sha256) }
    }
    data object NotFound404 : WebDavRemoteObservation
    data object Inconclusive : WebDavRemoteObservation
}

data class WebDavReconciliationEvidence(
    val local: WebDavArtifactPublication,
    val localSha256: String,
    val remote: WebDavRemoteObservation,
) {
    init { requireOutboxHash(localSha256) }
}

/** Pure deterministic transitions, shared by SQL persistence and JVM state-machine tests. */
internal object WebDavOutboxTransitions {
    fun stage(plan: WebDavBundlePlan) = WebDavOutboxBundle(
        plan.id, plan.endpointId, plan.endpointRevision, 0, false,
        plan.artifacts.map { WebDavOutboxArtifact(it) },
    )

    fun seal(bundle: WebDavOutboxBundle, publications: List<WebDavArtifactPublication>): WebDavOutboxBundle {
        require(!bundle.sealed)
        require(publications.size == bundle.artifacts.size && publications.map { it.artifactId }.toSet().size == publications.size)
        val byId = publications.associateBy { it.artifactId }
        val artifacts = bundle.artifacts.map { artifact ->
            val published = requireNotNull(byId[artifact.spec.id])
            require(sourceMatches(artifact.spec, published.source))
            artifact.copy(revision = increment(artifact.revision), modifiedSeconds = published.source.modifiedSeconds)
        }
        return bundle.copy(sealed = true, revision = increment(bundle.revision), artifacts = artifacts)
    }

    fun hash(bundle: WebDavOutboxBundle, publication: WebDavArtifactPublication, sha256: String): WebDavOutboxBundle {
        requireOutboxHash(sha256)
        return change(bundle, publication.artifactId) { artifact ->
            require(bundle.sealed && artifact.state == WebDavArtifactState.QUEUED && artifact.attempt == null)
            require(sourceMatches(artifact, publication.source))
            // Once registered, a different digest means different source bytes, not a new retry.
            require(artifact.sha256 == null || artifact.sha256 == sha256)
            artifact.copy(sha256 = sha256)
        }
    }

    fun claim(
        bundle: WebDavOutboxBundle,
        artifactId: String,
        attemptId: String,
        kind: WebDavAttemptKind,
        admission: WebDavOutboxAdmission,
    ): Pair<WebDavOutboxBundle, WebDavOutboxLease> {
        val attempt = WebDavOutboxAttempt(attemptId, kind, admission.processToken)
        val changed = change(bundle, artifactId) { artifact ->
            require(bundle.sealed && artifact.sha256 != null && artifact.attempt == null)
            require(artifact.state == if (kind == WebDavAttemptKind.PUT) WebDavArtifactState.QUEUED else WebDavArtifactState.UNCERTAIN)
            artifact.copy(
                state = if (kind == WebDavAttemptKind.PUT) WebDavArtifactState.UPLOADING else WebDavArtifactState.UNCERTAIN,
                attempt = attempt, lastAdmission = admission, remoteMayExist = true,
            )
        }
        return changed to WebDavOutboxLease(bundle.id, bundle.endpointId, bundle.endpointRevision,
            artifactId, changed.artifacts.single { it.spec.id == artifactId }.revision, attempt)
    }

    fun finishPut(bundle: WebDavOutboxBundle, lease: WebDavOutboxLease, outcome: WebDavPutOutcome): WebDavOutboxBundle? {
        if (!matches(bundle, lease) || lease.attempt.kind != WebDavAttemptKind.PUT) return null
        return change(bundle, lease.artifactId) { artifact ->
            artifact.copy(attempt = null, state = if (outcome == WebDavPutOutcome.DEFINITELY_NOT_STARTED)
                WebDavArtifactState.QUEUED else WebDavArtifactState.UNCERTAIN,
                remoteMayExist = outcome != WebDavPutOutcome.DEFINITELY_NOT_STARTED)
        }
    }

    fun finishReconcile(bundle: WebDavOutboxBundle, lease: WebDavOutboxLease): WebDavOutboxBundle? {
        if (!matches(bundle, lease) || lease.attempt.kind != WebDavAttemptKind.RECONCILE) return null
        return change(bundle, lease.artifactId) { it.copy(attempt = null) }
    }

    fun reconcile(bundle: WebDavOutboxBundle, lease: WebDavOutboxLease, evidence: WebDavReconciliationEvidence): WebDavOutboxBundle? {
        if (!matches(bundle, lease) || lease.attempt.kind != WebDavAttemptKind.RECONCILE) return null
        require(evidence.local.artifactId == lease.artifactId)
        return change(bundle, lease.artifactId) { artifact ->
            val state = if (!sourceMatches(artifact, evidence.local.source) || evidence.localSha256 != artifact.sha256) {
                WebDavArtifactState.SOURCE_UNAVAILABLE
            } else when (val remote = evidence.remote) {
                WebDavRemoteObservation.NotFound404 -> WebDavArtifactState.QUEUED
                WebDavRemoteObservation.Inconclusive -> WebDavArtifactState.UNCERTAIN
                is WebDavRemoteObservation.CompleteBody -> if (remote.sizeBytes == artifact.spec.sizeBytes && remote.sha256 == artifact.sha256)
                    WebDavArtifactState.VERIFIED else WebDavArtifactState.CONFLICT
            }
            artifact.copy(state = state, attempt = null,
                remoteMayExist = state != WebDavArtifactState.QUEUED,
                sourceFailure = if (state == WebDavArtifactState.SOURCE_UNAVAILABLE)
                    WebDavSourceFailure(artifact.spec.sourceUri, WebDavSourceFailureReason.CONTENT_CHANGED) else null)
        }
    }

    fun sourceUnavailable(bundle: WebDavOutboxBundle, artifactId: String, expectedRevision: Long,
        failure: WebDavSourceFailure): WebDavOutboxBundle? {
        require(expectedRevision >= 0)
        val artifact = bundle.artifacts.singleOrNull { it.spec.id == artifactId } ?: return null
        if (artifact.revision != expectedRevision || artifact.attempt != null) return null
        require(bundle.sealed && artifact.state in setOf(WebDavArtifactState.QUEUED,
            WebDavArtifactState.UNCERTAIN, WebDavArtifactState.SOURCE_UNAVAILABLE))
        return markSourceUnavailable(bundle, artifactId, failure)
    }

    fun sourceUnavailable(bundle: WebDavOutboxBundle, lease: WebDavOutboxLease,
        failure: WebDavSourceFailure): WebDavOutboxBundle? {
        if (!matches(bundle, lease)) return null
        return markSourceUnavailable(bundle, lease.artifactId, failure)
    }

    private fun markSourceUnavailable(bundle: WebDavOutboxBundle, artifactId: String,
        failure: WebDavSourceFailure): WebDavOutboxBundle = change(bundle, artifactId) { artifact ->
        require(failure.sourceUri == artifact.spec.sourceUri)
        artifact.copy(state = WebDavArtifactState.SOURCE_UNAVAILABLE, sourceFailure = failure, attempt = null)
    }

    fun sourceRecovered(bundle: WebDavOutboxBundle, expectedRevision: Long,
        publication: WebDavArtifactPublication, sha256: String): WebDavOutboxBundle? {
        require(expectedRevision >= 0); requireOutboxHash(sha256)
        val artifact = bundle.artifacts.singleOrNull { it.spec.id == publication.artifactId } ?: return null
        if (artifact.revision != expectedRevision || artifact.attempt != null) return null
        require(bundle.sealed && artifact.state == WebDavArtifactState.SOURCE_UNAVAILABLE)
        require(sourceMatches(artifact, publication.source) && (artifact.sha256 == null || artifact.sha256 == sha256))
        return change(bundle, publication.artifactId) { it.copy(sha256 = sha256, sourceFailure = null,
            state = if (it.remoteMayExist) WebDavArtifactState.UNCERTAIN else WebDavArtifactState.QUEUED) }
    }

    fun recover(bundle: WebDavOutboxBundle, deadProcessToken: String): WebDavOutboxBundle {
        requireOutboxUuid(deadProcessToken)
        var result = bundle
        for (artifact in bundle.artifacts) {
            if (artifact.attempt?.processToken == deadProcessToken) {
                result = change(result, artifact.spec.id) { it.copy(state = WebDavArtifactState.UNCERTAIN, attempt = null) }
            }
        }
        return result
    }

    private fun matches(bundle: WebDavOutboxBundle, lease: WebDavOutboxLease): Boolean =
        bundle.id == lease.bundleId && bundle.endpointId == lease.endpointId && bundle.endpointRevision == lease.endpointRevision &&
            bundle.artifacts.any { it.spec.id == lease.artifactId && it.revision == lease.artifactRevision && it.attempt == lease.attempt }

    private fun sourceMatches(spec: WebDavArtifactSpec, source: WebDavClipSnapshot) = source.finalized &&
        spec.sourceUri == source.identity && spec.sourceName == source.displayName && spec.sizeBytes == source.sizeBytes

    private fun sourceMatches(artifact: WebDavOutboxArtifact, source: WebDavClipSnapshot) =
        sourceMatches(artifact.spec, source) && artifact.modifiedSeconds == source.modifiedSeconds

    private fun change(bundle: WebDavOutboxBundle, artifactId: String, transform: (WebDavOutboxArtifact) -> WebDavOutboxArtifact): WebDavOutboxBundle {
        require(bundle.artifacts.any { it.spec.id == artifactId })
        return bundle.copy(revision = increment(bundle.revision), artifacts = bundle.artifacts.map {
            if (it.spec.id == artifactId) transform(it).copy(revision = increment(it.revision)) else it
        })
    }

    private fun increment(value: Long): Long = Math.addExact(value, 1L)
}

internal fun requireOutboxUuid(value: String) {
    require(value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false))
}

internal fun requireOutboxHash(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) }

private fun requireOutboxName(value: String) {
    require(value.isNotBlank() && value != "." && value != "..")
    require(value.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 })
    require(Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray(Charsets.UTF_8).size <= 255)
}

private fun requireCompleteArtifactSet(artifacts: List<WebDavArtifactSpec>) {
    require(artifacts.size in 1..4)
    require(artifacts.map { it.id }.toSet().size == artifacts.size)
    require(artifacts.map { it.role }.toSet().size == artifacts.size)
    require(artifacts.map { it.sourceUri }.toSet().size == artifacts.size)
    require(artifacts.map { it.remoteName }.toSet().size == artifacts.size)
    require(artifacts.any { it.role == WebDavArtifactRole.VIDEO })
    require(artifacts.none { it.role == WebDavArtifactRole.AUDIO_METADATA } || artifacts.any { it.role == WebDavArtifactRole.AUDIO })
}

private fun requireMediaStoreItem(value: String) {
    require(value.length <= 1024)
    val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Invalid media item") }
    require(uri.scheme == "content" && uri.rawAuthority == "media" && uri.rawQuery == null && uri.rawFragment == null)
    val path = uri.path ?: throw IllegalArgumentException("Invalid media item")
    require(uri.rawPath == path) // Avoid aliases such as /%31 and /1 bypassing artifact uniqueness.
    require(path.startsWith('/') && !path.endsWith('/'))
    val parts = path.substring(1).split('/')
    require(parts.size in 3..4 && parts.first().matches(Regex("[A-Za-z0-9_-]+")))
    val itemId = parts.last().toLongOrNull()
    require(itemId != null && itemId > 0L && itemId.toString() == parts.last())
    require(parts.subList(1, parts.lastIndex) in listOf(listOf("video", "media"), listOf("images", "media"),
        listOf("audio", "media"), listOf("downloads"), listOf("file")))
}
