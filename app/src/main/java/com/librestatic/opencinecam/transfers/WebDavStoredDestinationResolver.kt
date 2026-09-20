/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.net.URI

/** Device-local lookup by the bundle's original identity, never the currently selected endpoint. */
internal class WebDavStoredDestinationResolver(
    private val settings: WebDavQueueSettings,
    private val credentials: WebDavCredentialStore,
) : WebDavWorkerDestinationResolver {
    override fun resolve(endpointId: String, endpointRevision: Long): WebDavResolvedDestination? {
        requireOutboxUuid(endpointId)
        // Profile URLs are immutable in schema 1; revision zero is the only qualified binding.
        require(endpointRevision == 0L)
        val state = settings.snapshotForAdmission()
        check(!state.storageFailed)
        if (!state.preferences.enabled) return null
        val profile = state.preferences.endpoints.singleOrNull { it.id == endpointId } ?: return null
        // Missing authentication is a hold. Key loss/corruption throws without anonymous fallback.
        val secret = credentials.load(endpointId) ?: return null
        // Legacy non-collection URLs remain preserved; this validation never rewrites their binding.
        return WebDavResolvedDestination(endpointId, endpointRevision, WebDavDestination(URI(profile.url), secret, ignoreTlsErrors = profile.ignoreTlsErrors))
    }
}
