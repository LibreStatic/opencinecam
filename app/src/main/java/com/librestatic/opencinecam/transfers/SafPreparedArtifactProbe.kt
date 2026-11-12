/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */
package com.librestatic.opencinecam.transfers

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.core.net.toUri
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.CloseOnce
import com.librestatic.opencinecam.storage.DriveDescriptors
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import com.librestatic.opencinecam.storage.SafDocuments
import com.librestatic.opencinecam.storage.SafRecordingRows
import com.librestatic.opencinecam.storage.canonicalDriveDocumentUri
import com.librestatic.opencinecam.storage.driveFileRole
import com.librestatic.opencinecam.storage.driveMime
import com.librestatic.opencinecam.storage.externalStorageDocumentId
import com.librestatic.opencinecam.storage.isDriveUri
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * The USB drive holding a drive take's source does not answer (unplugged, or its volume not mounted yet). It is
 * transient: the hasher reports [WebDavHashResult.Waiting], the artifact keeps its state and the bundle's automatic
 * upload retries later. It never becomes SOURCE_UNAVAILABLE, which waits for the user.
 */
internal class WebDavSourceDriveAbsent(cause: Throwable? = null) : IOException("The USB drive holding the source is not connected", cause)

/**
 * How a drive source's descriptor checks end: [failure] (null when they passed), unless `DriveDescriptors` had already
 * [released] the descriptor. Then the drive left, whatever the checks met on the closed descriptor (a closed or
 * changed descriptor, EBADF, an unanswered query) is that, and the result is the transient [WebDavSourceDriveAbsent].
 */
internal fun driveReleasedOr(failure: Throwable?, released: Boolean): Throwable? =
    if (!released || failure is WebDavSourceDriveAbsent) failure else WebDavSourceDriveAbsent(failure)

/** What the drive lists for a prepared artifact: its document's name, size, last change and the MIME it is read with. */
internal data class SafPreparedObservation(val displayName: String, val sizeBytes: Long, val modifiedMillis: Long, val mimeType: String)

/**
 * PLAN-072: the probe for an artifact of a take recorded to the USB drive. The take folder's hidden marker
 * ([SafRecordingRows.MARKER]) is the drive's IS_PENDING: present until every member is published, deleted by the
 * group commit before the publication callback. So `pending` must equal "marker present", and a finalized artifact
 * is one whose folder holds no marker and whose document exists with the size its opened descriptor has. The
 * identity is the canonical document URI ([canonicalDriveDocumentUri]); the descriptor checks are those of
 * [MediaStorePreparedArtifactProbe], on the descriptor actually opened, which is registered with `DriveDescriptors` while it
 * is open (ADR-0037). An unreachable drive is [WebDavSourceDriveAbsent].
 */
internal class SafPreparedArtifactProbe(private val resolver: ContentResolver) : PreparedArtifactProbe {
    override fun inspect(artifact: PreparedCaptureArtifact, pending: Boolean): WebDavClipSnapshot {
        val before = safPreparedObservation(resolver, artifact, pending)
        val uri = artifact.uri.toUri()
        val descriptor = try {
            // Null is a provider that died: missing only when the drive still answers, like a refused open.
            resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Prepared artifact descriptor is unavailable")
        } catch (missing: FileNotFoundException) {
            throw absentOr(resolver, uri, missing)
        }
        // Held like useOnDrive, but a failure met on a descriptor DriveDescriptors released is the drive leaving.
        val closer = CloseOnce(descriptor)
        val handle = DriveDescriptors.registerDocument(uri, closer)
        try {
            return inspectOpened(descriptor, artifact, pending, before)
        } catch (failure: Throwable) {
            throw driveReleasedOr(failure, closer.closed) ?: failure
        } finally {
            try { closer.close() } finally { handle.unregister() }
        }
    }

    private fun inspectOpened(descriptor: ParcelFileDescriptor, artifact: PreparedCaptureArtifact, pending: Boolean,
        before: SafPreparedObservation): WebDavClipSnapshot = descriptor.let {
            requireReadOnlyPreparedDescriptor(it)
            val firstStat = Os.fstat(it.fileDescriptor)
            val firstSize = it.statSize
            check(OsConstants.S_ISREG(firstStat.st_mode) && firstSize > 0 && firstSize == firstStat.st_size) {
                "Prepared artifact has no complete regular-file bytes"
            }
            val after = safPreparedObservation(resolver, artifact, pending)
            val lastStat = Os.fstat(it.fileDescriptor)
            check(before == after && sameFileState(firstStat, lastStat) && firstSize == it.statSize) { "Prepared artifact changed during inspection" }
            check(pending || before.sizeBytes == firstSize) { "Published artifact size differs from its descriptor" }
            WebDavClipSnapshot(
                identity = artifact.uri,
                displayName = before.displayName,
                sizeBytes = firstSize,
                modifiedSeconds = Math.floorDiv(before.modifiedMillis, 1000L),
                finalized = !pending,
                mimeType = before.mimeType,
            )
        }

    private fun sameFileState(first: StructStat, last: StructStat): Boolean =
        first.st_dev == last.st_dev && first.st_ino == last.st_ino && first.st_mode == last.st_mode && first.st_nlink == last.st_nlink &&
            first.st_size == last.st_size && first.st_mtim.tv_sec == last.st_mtim.tv_sec && first.st_mtim.tv_nsec == last.st_mtim.tv_nsec &&
            first.st_ctim.tv_sec == last.st_ctim.tv_sec && first.st_ctim.tv_nsec == last.st_ctim.tv_nsec
}

/**
 * One listing of the artifact's take folder: the document must be there, a plain file named [PreparedCaptureArtifact.displayName],
 * with a MIME its role allows, and the marker present exactly when [pending]. No descriptor is opened.
 */
internal fun safPreparedObservation(resolver: ContentResolver, artifact: PreparedCaptureArtifact, pending: Boolean): SafPreparedObservation {
    val name = artifact.displayName
    require(name.isNotBlank() && name != "." && name != ".." && !name.startsWith('.')) { "Prepared artifact name is invalid" }
    require(name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) { "Prepared artifact name is invalid" }
    require(Charsets.UTF_8.newEncoder().canEncode(name) && name.toByteArray(Charsets.UTF_8).size <= 255) { "Prepared artifact name is invalid" }
    require(canonicalDriveDocumentUri(artifact.uri)) { "Prepared artifact URI is not a canonical drive document" }
    val documentId = requireNotNull(externalStorageDocumentId(artifact.uri))
    require(documentId.substringAfterLast('/') == name) { "Prepared artifact name does not match its document" }
    val folderId = documentId.substringBeforeLast('/')
    val uri = artifact.uri.toUri()
    val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
    val docs = SafDocuments(resolver, tree)
    val children = try {
        docs.children(DocumentsContract.buildDocumentUriUsingTree(tree, folderId))
    } catch (failure: Exception) {
        if (failure is CancellationException || failure is SecurityException) throw failure
        throw absentOr(resolver, uri, FileNotFoundException("Prepared artifact folder is unavailable").apply { initCause(failure) })
    }
    check(children.any { it.name == SafRecordingRows.MARKER } == pending) { "Prepared artifact publication state changed" }
    val document = children.singleOrNull { DocumentsContract.getDocumentId(it.uri) == documentId }
        ?: throw absentOr(resolver, uri, FileNotFoundException("Prepared artifact document is missing"))
    check(document.mime != DocumentsContract.Document.MIME_TYPE_DIR && document.name == name) { "Prepared artifact name changed" }
    check(document.size >= 0 && document.modifiedMillis >= 0) { "Prepared artifact metadata is invalid" }
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val allowed = when (artifact.role) {
        CaptureArtifactRole.VIDEO -> if (extension == "mp4") setOf("video/mp4") else emptySet()
        CaptureArtifactRole.AUDIO -> when (extension) {
            "wav" -> setOf("audio/wav", "audio/x-wav", "audio/vnd.wave")
            "flac" -> setOf("audio/flac", "audio/x-flac")
            else -> emptySet()
        }
        CaptureArtifactRole.VIDEO_METADATA, CaptureArtifactRole.AUDIO_METADATA, CaptureArtifactRole.CLIP_METADATA ->
            if (extension == "json") setOf("application/json") else emptySet()
    }
    val mime = driveMime(name, driveFileRole(name, recording = true), document.mime)
    check(mime.lowercase(Locale.ROOT) in allowed) { "Prepared artifact MIME does not match its role and name" }
    return SafPreparedObservation(name, document.size, document.modifiedMillis, mime)
}

/** [failure] when the drive answers (the document is truly gone, or the read truly failed), else [WebDavSourceDriveAbsent]. */
internal fun absentOr(resolver: ContentResolver, document: Uri, failure: IOException): IOException {
    val reachable = runCatching {
        val docs = SafDocuments(resolver, DocumentsContract.buildTreeDocumentUri(document.authority, DocumentsContract.getTreeDocumentId(document)))
        docs.describe(docs.root) != null
    }.getOrDefault(false)
    return if (reachable) failure else WebDavSourceDriveAbsent(failure)
}

/**
 * The probe for any capture artifact: a drive document ([isDriveUri]) through [SafPreparedArtifactProbe], anything
 * else through [MediaStorePreparedArtifactProbe], which keeps refusing what is not a canonical MediaStore item.
 */
internal class CaptureArtifactProbe(resolver: ContentResolver) : PreparedArtifactProbe {
    private val mediaStore = MediaStorePreparedArtifactProbe(resolver)
    private val drive = SafPreparedArtifactProbe(resolver)
    override fun inspect(artifact: PreparedCaptureArtifact, pending: Boolean): WebDavClipSnapshot =
        (if (isDriveUri(artifact.uri)) drive else mediaStore).inspect(artifact, pending)
}
