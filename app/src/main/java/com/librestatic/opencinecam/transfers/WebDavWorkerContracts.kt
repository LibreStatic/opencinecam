/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

/** A new qualified source per operation; never a cached observation or open descriptor. */
internal fun interface WebDavArtifactSourceFactory {
    fun source(spec: WebDavArtifactSpec): WebDavClipSource
}

internal fun interface WebDavArtifactHasher {
    fun hash(artifact: WebDavOutboxArtifact, scope: WebDavOperationScope): WebDavHashResult
}

internal sealed interface WebDavHashResult {
    data class Hashed(val publication: WebDavArtifactPublication, val sha256: String) : WebDavHashResult {
        init { requireOutboxHash(sha256) }
    }
    data class Unavailable(val failure: WebDavSourceFailure) : WebDavHashResult
    data class Stopped(val reason: WebDavStopReason) : WebDavHashResult
}

/** Explicit immutable destination binding; credentials stay only in the in-memory destination. */
internal class WebDavResolvedDestination(
    val endpointId: String,
    val endpointRevision: Long,
    val destination: WebDavDestination,
) {
    init { requireOutboxUuid(endpointId); require(endpointRevision >= 0) }
    override fun toString() = "WebDavResolvedDestination(redacted)"
}

internal fun interface WebDavWorkerDestinationResolver {
    /** Missing/unavailable authentication is an explicit hold, never anonymous fallback. */
    fun resolve(endpointId: String, endpointRevision: Long): WebDavResolvedDestination?
}
