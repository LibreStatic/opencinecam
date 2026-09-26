/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import com.librestatic.opencinecam.transfers.WebDavTransferRuntime
import java.io.File

internal object MediaProxyQueue {
    @Volatile private var instance: ProxyJobQueue? = null
    fun get(context: Context): ProxyJobQueue = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val repository = MediaProxyRepository(app)
            val policy = ProxyPolicyMonitor(app)
            ProxyJobQueue(ProxyJobStore(File(app.filesDir, "proxy-queue")), object : ProxyJobEngine {
                override suspend fun reconcile(job: ProxyJob) = repository.reconcile(job.take, job.id)
                override suspend fun create(job: ProxyJob) = repository.create(job.take, job.settings, job.id)
                override suspend fun recoverDeletions(committed: suspend (String, String) -> Unit) = repository.recoverDeletions(committed)
                override suspend fun deleteProxy(entry: ProxyCatalogEntry, committed: suspend (String, String) -> Unit) =
                    repository.deleteProxy(entry, committed)
                override suspend fun deleteProxy(take: LocalMediaTake, expected: MediaProxyResult, committed: suspend (String, String) -> Unit) =
                    repository.deleteProxy(take, expected, committed)
            }, policy::admission).also {
                instance = it; it.start(); policy.start(it::conditionsChanged)
                // Media waits wake on the actual release instead of the next policy poll.
                repository.addReleaseListener(it::mediaReleased)
                WebDavTransferRuntime.get(app).addMediaIdleListener(it::mediaReleased)
            }
        }
    }
}
