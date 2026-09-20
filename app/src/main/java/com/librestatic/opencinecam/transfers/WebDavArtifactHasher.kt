/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** An observed mismatch, not an I/O failure or an authorization to replace the fixed source. */
internal class WebDavArtifactContentChanged : IOException("WebDAV artifact content changed")

/**
 * One bounded-memory hash within a borrowed operation. The caller owns admission/retirement.
 * Neither metadata equality nor a prehash promises that a later PUT stream remains immutable.
 */
internal class DefaultWebDavArtifactHasher(
    private val sourceFactory: WebDavArtifactSourceFactory,
) : WebDavArtifactHasher {
    override fun hash(artifact: WebDavOutboxArtifact, scope: WebDavOperationScope): WebDavHashResult {
        val spec = artifact.spec
        return try {
            scope.checkRunning()
            if (artifact.modifiedSeconds == null) throw WebDavArtifactContentChanged()
            val source = sourceFactory.source(spec)
            scope.checkRunning()
            val before = source.snapshot()
            requireHashSnapshot(spec, before)
            if (before.modifiedSeconds != artifact.modifiedSeconds) throw WebDavArtifactContentChanged()
            scope.checkRunning()
            val digest = MessageDigest.getInstance("SHA-256")
            source.open().use { input ->
                scope.checkRunning()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (total < spec.sizeBytes) {
                    scope.checkRunning()
                    val requested = minOf(buffer.size.toLong(), spec.sizeBytes - total).toInt()
                    val count = input.read(buffer, 0, requested)
                    scope.checkRunning()
                    if (count < 0) throw WebDavArtifactContentChanged()
                    if (count == 0 || count > requested) throw IOException("Artifact reader made invalid progress")
                    total = Math.addExact(total, count.toLong())
                    digest.update(buffer, 0, count)
                }
                scope.checkRunning()
                if (input.read() != -1) throw WebDavArtifactContentChanged()
                scope.checkRunning()
            } // Descriptor validation and close must both finish before any Hashed result.
            scope.checkRunning()
            val after = source.snapshot()
            requireHashSnapshot(spec, after)
            if (before != after) throw WebDavArtifactContentChanged()
            scope.checkRunning()
            val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (artifact.sha256 != null && artifact.sha256 != hex) throw WebDavArtifactContentChanged()
            scope.checkRunning()
            WebDavHashResult.Hashed(WebDavArtifactPublication(spec.id, after), hex)
        } catch (failure: Exception) {
            // Cancellation wins even if an in-flight read or its close also failed. It is not
            // evidence of damaged media and must never persist SOURCE_UNAVAILABLE.
            scope.stopReason?.let { return WebDavHashResult.Stopped(it) }
            val reason = when (failure) {
                is SecurityException -> WebDavSourceFailureReason.ACCESS_DENIED
                is FileNotFoundException -> WebDavSourceFailureReason.MISSING
                is WebDavArtifactContentChanged, is IllegalArgumentException, is IllegalStateException -> WebDavSourceFailureReason.CONTENT_CHANGED
                else -> WebDavSourceFailureReason.READ_FAILED
            }
            WebDavHashResult.Unavailable(WebDavSourceFailure(spec.sourceUri, reason))
        }
    }
}

/** Shared by the hasher and strict Android source; all comparisons refer to the already-bound row. */
internal fun requireHashSnapshot(spec: WebDavArtifactSpec, snapshot: WebDavClipSnapshot) {
    if (!snapshot.finalized || snapshot.identity != spec.sourceUri || snapshot.displayName != spec.sourceName ||
        snapshot.sizeBytes != spec.sizeBytes || snapshot.modifiedSeconds < 0) throw WebDavArtifactContentChanged()
    val extension = spec.sourceName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val allowedMime = when (spec.role) {
        WebDavArtifactRole.VIDEO -> if (extension == "mp4") setOf("video/mp4") else emptySet()
        WebDavArtifactRole.AUDIO -> when (extension) {
            "wav" -> setOf("audio/wav", "audio/x-wav", "audio/vnd.wave")
            "flac" -> setOf("audio/flac", "audio/x-flac")
            else -> emptySet()
        }
        WebDavArtifactRole.VIDEO_METADATA, WebDavArtifactRole.AUDIO_METADATA ->
            if (extension == "json") setOf("application/json") else emptySet()
    }
    if (snapshot.mimeType.lowercase(Locale.ROOT) !in allowedMime) throw WebDavArtifactContentChanged()
}
