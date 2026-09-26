/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.librestatic.opencinecam.core.model.ClipSidecarDocument
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure
import com.librestatic.opencinecam.core.model.encodeCanonical

data class ClipOutputRequest(
    val displayName: String,
    val mimeType: String = "video/mp4",
    val relativePath: String = "Movies/OpenCineCam",
) {
    init {
        require(displayName.isNotBlank() && !displayName.contains('/')) { "display name must be a file name" }
        require(mimeType.isNotBlank() && relativePath.isNotBlank())
    }
}

/** One owned "rw" descriptor per clip; the muxer writes through it, nothing else opens the URI. */
class OwnedClipDescriptor(
    val uri: Uri,
    val fileDescriptor: ParcelFileDescriptor,
) : AutoCloseable {
    override fun close() {
        runCatching { fileDescriptor.close() }
    }
}

sealed interface ClipFinalizationResult {
    data class Published(val incomplete: Boolean) : ClipFinalizationResult
    data object Deleted : ClipFinalizationResult
    /**
     * [cause] is the original failure. When [clipRetained] is true the muxed clip was kept as a
     * pending row (never deleted), so recovery can still publish it.
     */
    data class Failed(
        val failure: StableFailure,
        val cause: Throwable? = null,
        val clipRetained: Boolean = false,
    ) : ClipFinalizationResult
}

/** Row operations [finalizeClip] needs; the production implementation is backed by a ContentResolver. */
internal interface ClipRows<U> {
    fun delete(uri: U)
    fun publish(uri: U)
    fun writeSidecar(uri: U, bytes: ByteArray)
}

/** Opens exactly one descriptor for a newly inserted row, deleting the row if that open fails. */
internal fun <U, D> openInsertedOnce(insert: () -> U, open: (U) -> D?, delete: (U) -> Unit): Pair<U, D> {
    val uri = insert()
    return try {
        uri to (open(uri) ?: error("Pending clip descriptor could not be opened."))
    } catch (error: Throwable) {
        runCatching { delete(uri) }.exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }
}

internal fun <U> finalizeClip(
    rows: ClipRows<U>,
    clipUri: U,
    sidecarUri: U?,
    muxerFinalized: Boolean,
    bytesWritten: Long,
    sidecarBytes: (() -> ByteArray)?,
    correlationId: String,
): ClipFinalizationResult {
    val decision = decideClipRecovery(muxerFinalized, bytesWritten)
    return try {
        when (decision) {
            ClipRecoveryDecision.DELETE -> {
                rows.delete(clipUri)
                sidecarUri?.let(rows::delete)
                ClipFinalizationResult.Deleted
            }
            ClipRecoveryDecision.PUBLISH_COMPLETE,
            ClipRecoveryDecision.PUBLISH_INCOMPLETE,
            -> {
                if (sidecarUri != null && sidecarBytes != null) rows.writeSidecar(sidecarUri, sidecarBytes())
                rows.publish(clipUri)
                ClipFinalizationResult.Published(muxerFinalized.not())
            }
        }
    } catch (cause: Throwable) {
        // A clip that holds muxed bytes is user media: keep its pending row for recovery instead of
        // deleting it because metadata failed. Only an empty clip (DELETE) is removed.
        val retainClip = decision != ClipRecoveryDecision.DELETE
        if (!retainClip) runCatching { rows.delete(clipUri) }.exceptionOrNull()?.let(cause::addSuppressed)
        sidecarUri?.let { uri -> runCatching { rows.delete(uri) }.exceptionOrNull()?.let(cause::addSuppressed) }
        ClipFinalizationResult.Failed(
            StableFailure(
                component = "clip-storage",
                code = FailureCode.MUXER_FINALIZATION_FAILED,
                severity = FailureSeverity.ERROR,
                recoverability = Recoverability.RETRYABLE,
                correlationId = correlationId,
                userMessage = "The clip could not be finalized safely.",
                details = mapOf("cause" to (cause::class.java.name + ": " + cause.message.orEmpty()).replace('\n', ' ')),
            ),
            cause = cause,
            clipRetained = retainClip,
        )
    }
}

class MediaStoreClipOwner(
    private val resolver: ContentResolver,
    private val correlationId: String = "clip-storage",
) {
    private val rows = object : ClipRows<Uri> {
        override fun delete(uri: Uri) { resolver.delete(uri, null, null) }
        override fun publish(uri: Uri) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        override fun writeSidecar(uri: Uri, bytes: ByteArray) {
            (resolver.openOutputStream(uri) ?: error("Clip sidecar could not be opened.")).use { it.write(bytes) }
        }
    }

    fun createPending(request: ClipOutputRequest): OwnedClipDescriptor {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, request.displayName)
            put(MediaStore.Video.Media.MIME_TYPE, request.mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, request.relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val (uri, descriptor) = openInsertedOnce(
            insert = { resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore did not create a pending clip.") },
            open = { resolver.openFileDescriptor(it, "rw") },
            delete = rows::delete,
        )
        return OwnedClipDescriptor(uri, descriptor)
    }

    fun openSaf(uri: Uri): OwnedClipDescriptor =
        OwnedClipDescriptor(uri, resolver.openFileDescriptor(uri, "rw") ?: error("SAF descriptor could not be opened."))

    fun finalize(
        clipUri: Uri,
        sidecarUri: Uri?,
        muxerFinalized: Boolean,
        bytesWritten: Long,
        sidecar: ClipSidecarDocument?,
    ): ClipFinalizationResult = finalizeClip(
        rows, clipUri, sidecarUri, muxerFinalized, bytesWritten,
        sidecar?.let { document -> { encodeCanonical(document).toByteArray() } }, correlationId,
    )
}
