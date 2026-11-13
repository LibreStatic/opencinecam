/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.coroutines.runBlocking
import com.librestatic.opencinecam.transfers.WebDavTransferRuntime

/** Called only after confirmation. Execute off the UI thread without cancellation after admission.
 * No rollback/atomicity promise: each row's observed outcome is reported and failures stop the set. */
class MediaTakeDeleter internal constructor(private val access: MediaDeleteAccess, private val drive: DriveMediaCatalog? = null) {
    constructor(context: Context) : this(MediaStoreDeleteAccess(context), LocalMediaRepository(context).drive)
    /** A take on the recording drive (PLAN-072) goes with its folder, under the same reservation as a phone take. */
    fun delete(selected: LocalMediaTake): MediaDeleteResult =
        if (!selected.onDrive) executeMediaDelete(selected, access)
        else if (drive == null) MediaDeleteResult((selected.originals + selected.metadata + selected.attachments)
            .map { MediaDeleteFileResult(it, MediaDeleteStatus.NOT_ATTEMPTED) }, "Drive deletion unavailable")
        else executeDriveDelete(selected, drive, access::reserve, access::bindDriveSource, access::beginDriveDeletion)
}

/**
 * Deletes a drive take's confirmed files one by one, after [DriveMediaCatalog.deletionSnapshot] re-read its take folder
 * and found exactly them there, sidecars first so a deletion cut short keeps the media; the first failure stops it.
 * Absence is then observed per file; a drive that stops answering leaves every attempted file UNKNOWN, never verified.
 * The folder goes only once nothing else is in it but a computer's litter: a file the operator kept there, or one
 * written after the snapshot, is never deleted unseen. The catalog's cache is dropped whatever the outcome.
 */
internal fun executeDriveDelete(
    selected: LocalMediaTake,
    drive: DriveMediaCatalog,
    reserve: () -> AutoCloseable,
    bindSource: (LocalMediaTake) -> Unit = {},
    beginDeletion: () -> Unit = {},
): MediaDeleteResult {
    val confirmed = selected.originals + selected.metadata + selected.attachments
    var results = confirmed.map { MediaDeleteFileResult(it, MediaDeleteStatus.NOT_ATTEMPTED) }
    var error: String? = null
    var reservation: AutoCloseable? = null
    var attempted = false
    // Confirmed members before this index were never tried (the first failure stops the deletion).
    var untried = confirmed.size
    try {
        reservation = reserve()
        val deletion = drive.deletionSnapshot(selected)
        // A proxy job queued for this take stops before its source goes, as for a phone take.
        bindSource(selected)
        beginDeletion()
        var problem: String? = null
        attempted = true
        var tried = 0
        for (index in confirmed.indices.reversed()) {
            tried++
            val deleted = try {
                android.provider.DocumentsContract.deleteDocument(deletion.docs.resolver, deletion.members[index])
            } catch (failure: Exception) {
                problem = failure.message ?: "The drive did not delete ${confirmed[index].name}"
                false
            }
            if (!deleted) { problem = problem ?: "The drive did not delete ${confirmed[index].name}"; break }
        }
        untried = confirmed.size - tried
        val answers = { deletion.docs.describe(deletion.docs.root) != null }
        val observed = if (!answers()) null else confirmed.mapIndexed { index, artifact ->
            if (index < untried) return@mapIndexed MediaDeleteFileResult(artifact, MediaDeleteStatus.NOT_ATTEMPTED, problem)
            val uri = deletion.members[index]
            if (deletion.docs.describe(uri) != null) MediaDeleteFileResult(artifact, MediaDeleteStatus.RETAINED, problem)
            // No answer for the file is not proof it went: a listing from the nearest folder that answers must agree.
            else if (runCatching { deletion.docs.listsNo(uri) }.getOrDefault(false)) MediaDeleteFileResult(artifact, MediaDeleteStatus.ABSENT_VERIFIED)
            else MediaDeleteFileResult(artifact, MediaDeleteStatus.UNKNOWN, problem ?: "The drive did not confirm the file is gone")
        }
        // A drive that left while the files were read back makes each look absent: it must still answer afterwards.
        results = if (observed != null && answers()) observed else {
            problem = problem ?: "The USB drive stopped answering"
            confirmed.mapIndexed { index, artifact ->
                MediaDeleteFileResult(artifact, if (index < untried) MediaDeleteStatus.NOT_ATTEMPTED else MediaDeleteStatus.UNKNOWN, problem)
            }
        }
        if (problem == null) problem = results.firstOrNull { it.status == MediaDeleteStatus.UNKNOWN }?.detail
        if (problem == null && results.any { it.status != MediaDeleteStatus.ABSENT_VERIFIED }) problem = "Conditional deletion did not remove the selected identity"
        if (problem == null) {
            // The take is gone; an emptied folder follows it. Left in place (no error) when it holds anything else.
            runCatching {
                val left = deletion.docs.children(deletion.folder)
                val onlyLitter = left.all { it.mime != android.provider.DocumentsContract.Document.MIME_TYPE_DIR && driveFolderLitter(it.name) }
                if (onlyLitter) android.provider.DocumentsContract.deleteDocument(deletion.docs.resolver, deletion.folder)
            }
        }
        error = problem
    } catch (failure: Exception) {
        if (attempted) {
            // The files tried may be gone already: what the drive did to them is unknown, not "nothing deleted".
            error = failure.message ?: "Deletion absence could not be verified"
            results = results.mapIndexed { index, it ->
                if (it.status == MediaDeleteStatus.NOT_ATTEMPTED && index >= untried) MediaDeleteFileResult(it.artifact, MediaDeleteStatus.UNKNOWN, error) else it
            }
        } else error = failure.message ?: "Deletion preflight failed; no further files were attempted"
    } finally {
        drive.invalidate()
        try { reservation?.close() } catch (failure: Exception) {
            error = listOfNotNull(error, failure.message ?: "Deletion reservation release failed").joinToString("; ")
        }
    }
    return MediaDeleteResult(results, error)
}

internal class MediaStoreDeleteAccess(context: Context) : MediaDeleteAccess {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val repository = LocalMediaRepository(this.context)
    private var sourceLease: ProxySourceMutationLease? = null
    override fun reserve(): AutoCloseable {
        val lease = runBlocking { MediaProxyQueue.get(context).acquireSourceMutation() }
        val media = try {
            WebDavTransferRuntime.get(context).reserveIdleMediaMutation()
        } catch (error: Exception) {
            try { lease.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
        sourceLease = lease
        return AutoCloseable {
            var failure: Exception? = null
            try { media.close() } catch (error: Exception) { failure = error }
            try { lease.close() } catch (error: Exception) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            } finally { sourceLease = null }
            failure?.let { throw it }
        }
    }
    override fun preflight(selected: LocalMediaTake): MediaDeletePlan = repository.deletionSnapshot(selected).also {
        sourceLease?.bind(selected)
    }
    override fun beginDeletion(plan: MediaDeletePlan) {
        sourceLease?.invalidateForDeletion()
    }
    override fun bindDriveSource(selected: LocalMediaTake) { sourceLease?.bind(selected) }
    override fun beginDriveDeletion() { sourceLease?.invalidateForDeletion() }
    override fun delete(row: MediaDeleteRow): Int {
        require(row.owner == context.packageName)
        val condition = mediaDeleteCondition(row)
        return resolver.delete(row.artifact.uri.toUri(), condition.selection, condition.arguments.toTypedArray())
    }
    override fun isAbsent(row: MediaDeleteRow): Boolean {
        requireNotNull(mediaDeleteIdentity(row.artifact.uri))
        // No name/owner/path predicate here: a changed identity must remain visible as retained,
        // not become a false absence merely because it no longer satisfies our DELETE condition.
        @Suppress("DEPRECATION") val includingPending = MediaStore.setIncludePending(row.artifact.uri.toUri())
        val query = Bundle().apply { putInt(ContentResolver.QUERY_ARG_LIMIT, 1) }
        return requireNotNull(resolver.query(includingPending, arrayOf("_id"), query, null)) {
            "Deletion absence query unavailable"
        }.use { !it.moveToFirst() }
    }
    override fun verifyEmpty(plan: MediaDeletePlan) = repository.verifyDeletionEmpty(plan)
}
