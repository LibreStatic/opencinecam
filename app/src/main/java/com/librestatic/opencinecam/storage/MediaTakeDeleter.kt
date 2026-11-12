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
        else executeDriveDelete(selected, drive, access::reserve)
}

/**
 * Deletes a drive take's folder in one provider call, after [DriveMediaCatalog.deletionSnapshot] re-read it and found
 * exactly the confirmed files in a take folder of the drive. Absence is then observed per file; a drive that stops
 * answering leaves every file UNKNOWN, never verified. The catalog's cache is dropped whatever the outcome.
 */
internal fun executeDriveDelete(selected: LocalMediaTake, drive: DriveMediaCatalog, reserve: () -> AutoCloseable): MediaDeleteResult {
    val confirmed = selected.originals + selected.metadata + selected.attachments
    var results = confirmed.map { MediaDeleteFileResult(it, MediaDeleteStatus.NOT_ATTEMPTED) }
    var error: String? = null
    var reservation: AutoCloseable? = null
    var attempted = false
    try {
        reservation = reserve()
        val deletion = drive.deletionSnapshot(selected)
        var problem: String? = null
        attempted = true
        try { android.provider.DocumentsContract.deleteDocument(deletion.docs.resolver, deletion.folder) }
        catch (failure: Exception) { problem = failure.message ?: "The drive did not delete the take folder" }
        val reachable = deletion.docs.describe(deletion.docs.root) != null
        results = if (!reachable) {
            problem = problem ?: "The USB drive stopped answering"
            confirmed.map { MediaDeleteFileResult(it, MediaDeleteStatus.UNKNOWN, problem) }
        } else confirmed.map { artifact ->
            if (deletion.docs.describe(artifact.uri.toUri()) == null) MediaDeleteFileResult(artifact, MediaDeleteStatus.ABSENT_VERIFIED)
            else MediaDeleteFileResult(artifact, MediaDeleteStatus.RETAINED, problem)
        }
        if (problem == null && results.any { it.status != MediaDeleteStatus.ABSENT_VERIFIED }) problem = "Conditional deletion did not remove the selected identity"
        if (problem == null && deletion.docs.describe(deletion.folder) != null) problem = "The take folder remains on the drive"
        error = problem
    } catch (failure: Exception) {
        if (attempted) {
            // The folder may be gone already: what the drive did is unknown, not "nothing deleted".
            error = failure.message ?: "Deletion absence could not be verified"
            results = results.map {
                if (it.status == MediaDeleteStatus.NOT_ATTEMPTED) MediaDeleteFileResult(it.artifact, MediaDeleteStatus.UNKNOWN, error) else it
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
