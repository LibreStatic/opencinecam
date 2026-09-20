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
class MediaTakeDeleter internal constructor(private val access: MediaDeleteAccess) {
    constructor(context: Context) : this(MediaStoreDeleteAccess(context))
    fun delete(selected: LocalMediaTake): MediaDeleteResult = executeMediaDelete(selected, access)
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
