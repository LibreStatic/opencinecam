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
import java.io.OutputStream

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

class OwnedClipDescriptor(
    val uri: Uri,
    val fileDescriptor: ParcelFileDescriptor,
    private val output: OutputStream,
) : AutoCloseable {
    override fun close() {
        runCatching { output.flush() }
        runCatching { output.close() }
        runCatching { fileDescriptor.close() }
    }
}

sealed interface ClipFinalizationResult {
    data class Published(val incomplete: Boolean) : ClipFinalizationResult
    data object Deleted : ClipFinalizationResult
    data class Failed(val failure: StableFailure) : ClipFinalizationResult
}

class MediaStoreClipOwner(
    private val resolver: ContentResolver,
    private val correlationId: String = "clip-storage",
) {
    fun createPending(request: ClipOutputRequest): OwnedClipDescriptor {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, request.displayName)
            put(MediaStore.Video.Media.MIME_TYPE, request.mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, request.relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore did not create a pending clip.")
        return try {
            val output = resolver.openOutputStream(uri) ?: error("Pending clip output could not be opened.")
            val descriptor = resolver.openFileDescriptor(uri, "rw") ?: error("Pending clip descriptor could not be opened.")
            OwnedClipDescriptor(uri, descriptor, output)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    fun openSaf(uri: Uri): OwnedClipDescriptor {
        val output = resolver.openOutputStream(uri) ?: error("SAF output could not be opened.")
        val descriptor = try {
            resolver.openFileDescriptor(uri, "rw") ?: error("SAF descriptor could not be opened.")
        } catch (error: Throwable) {
            output.close()
            throw error
        }
        return OwnedClipDescriptor(uri, descriptor, output)
    }

    fun finalize(
        clipUri: Uri,
        sidecarUri: Uri?,
        muxerFinalized: Boolean,
        bytesWritten: Long,
        sidecar: ClipSidecarDocument?,
    ): ClipFinalizationResult {
        return try {
            when (decideClipRecovery(muxerFinalized, bytesWritten)) {
                ClipRecoveryDecision.DELETE -> {
                    resolver.delete(clipUri, null, null)
                    sidecarUri?.let { resolver.delete(it, null, null) }
                    ClipFinalizationResult.Deleted
                }
                ClipRecoveryDecision.PUBLISH_COMPLETE,
                ClipRecoveryDecision.PUBLISH_INCOMPLETE,
                -> {
                    if (sidecarUri != null && sidecar != null) writeSidecar(sidecarUri, sidecar)
                    resolver.update(
                        clipUri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    ClipFinalizationResult.Published(muxerFinalized.not())
                }
            }
        } catch (_: Throwable) {
            resolver.delete(clipUri, null, null)
            sidecarUri?.let { resolver.delete(it, null, null) }
            ClipFinalizationResult.Failed(
                StableFailure(
                    component = "clip-storage",
                    code = FailureCode.MUXER_FINALIZATION_FAILED,
                    severity = FailureSeverity.ERROR,
                    recoverability = Recoverability.RETRYABLE,
                    correlationId = correlationId,
                    userMessage = "The clip could not be finalized safely.",
                ),
            )
        }
    }

    private fun writeSidecar(uri: Uri, sidecar: ClipSidecarDocument) {
        val output = resolver.openOutputStream(uri) ?: error("Clip sidecar could not be opened.")
        try {
            output.write(encodeCanonical(sidecar).toByteArray())
        } finally {
            output.close()
        }
    }
}
