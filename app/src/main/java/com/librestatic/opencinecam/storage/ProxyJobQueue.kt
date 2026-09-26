/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.ProxySettings
import com.librestatic.opencinecam.ProxyWaitReason
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface ProxyJobEngine {
    suspend fun deleteProxy(entry: ProxyCatalogEntry, committed: suspend (String, String) -> Unit) {
        error("Proxy catalog deletion unavailable")
    }
    suspend fun reconcile(job: ProxyJob): MediaProxyResult?
    suspend fun create(job: ProxyJob): MediaProxyResult
    suspend fun recoverDeletions(committed: suspend (String, String) -> Unit) {}
    suspend fun deleteProxy(take: LocalMediaTake, expected: MediaProxyResult, committed: suspend (String, String) -> Unit) {
        error("Proxy deletion unavailable")
    }
}
/** Caller binds preflight under its media reservation, then updates only fully verified observations.
 * Close after releasing that reservation; the lease prevents queue selection until then. */
internal interface ProxySourceMutationLease : AutoCloseable {
    fun bind(selected: LocalMediaTake)
    fun begin()
    fun invalidateForDeletion()
    fun update(observed: LocalMediaTake)
}
internal data class ProxyQueueState(val jobs: List<ProxyJob> = emptyList(), val loaded: Boolean = false, val error: String? = null, val waiting: Map<String, ProxyWaitReason> = emptyMap())

/** One process owner, one durable request per take. Disk state precedes observable admission.
 * Jobs survive UI disposal; interrupted attempts reconcile before restarting from the original.
 * This is not a background-service guarantee: queued work resumes when the app next starts. */
internal class ProxyJobQueue(private val store: ProxyJobStore, private val engine: ProxyJobEngine,
    private val admission: suspend (ProxyJob) -> ProxyWaitReason? = { null }) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val mutable = MutableStateFlow(ProxyQueueState())
    val states = mutable.asStateFlow()
    private var drain: Job? = null
    private var operation: Deferred<MediaProxyResult?>? = null
    private var activeId: String? = null
    private var closed = false
    private var recovering = false

    fun start() { scope.launch { gate.withLock { load(); kick() } } }
    fun conditionsChanged() { scope.launch { gate.withLock {
        mutable.value = mutable.value.copy(waiting = emptyMap()); load(); kick()
    } } }
    /** REC, a transfer or another proxy action released media ownership. Policy waits stay put. */
    fun mediaReleased() { scope.launch { gate.withLock {
        mutable.value = mutable.value.copy(waiting = mutable.value.waiting.filterValues { it !in MEDIA_WAITS }); load(); kick()
    } } }
    /** A take the store cannot represent (or a full queue) is rejected for this call only. */
    suspend fun enqueue(take: LocalMediaTake, settings: ProxySettings): String = withContext(Dispatchers.IO) {
        gate.withLock {
            ready()
            mutable.value.jobs.firstOrNull { it.take.id == take.id }?.let { return@withLock it.id }
            val job = ProxyJob(UUID.randomUUID().toString(), take, settings, ProxyJobStatus.QUEUED)
            persist(pruneHistory(mutable.value.jobs) + job); kick(); job.id
        }
    }
    suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            ready()
            val job = mutable.value.jobs.single { it.id == id }
            check(job.status in setOf(ProxyJobStatus.FAILED, ProxyJobStatus.CANCELLED))
            replace(job.copy(status = ProxyJobStatus.QUEUED, error = null)); kick()
        }
    }
    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            ready()
            val job = mutable.value.jobs.single { it.id == id }
            if (job.status in TERMINAL) return@withLock
            // A recovered QUEUED request may still have leftovers from an interrupted attempt.
            replace(job.copy(status = ProxyJobStatus.CANCELLING, error = null))
            if (activeId == id) operation?.cancel()
            kick()
        }
    }
    /** Retains the queue gate across external source mutation, without taking media ownership here.
     * An already selected operation is rejected even before it acquires its media reservation. */
    suspend fun acquireSourceMutation(): ProxySourceMutationLease {
        val token = Any()
        gate.lock(token)
        try {
            ready()
            check(activeId == null && operation == null) { "Proxy source operation has not retired" }
            return object : ProxySourceMutationLease {
                private var released = false
                private var selected: LocalMediaTake? = null
                private var boundJob: ProxyJob? = null
                private var pendingVerification = false
                private var deletingSource = false

                @Synchronized override fun bind(selected: LocalMediaTake) {
                    check(!released && this.selected == null) { "Proxy source lease is closed or already bound" }
                    val job = mutable.value.jobs.firstOrNull { it.take.id == selected.id }
                    check(job == null || job.take == selected) { "Proxy source snapshot differs from selected take" }
                    this.selected = selected
                    boundJob = job
                }

                @Synchronized override fun begin() {
                    check(!released && selected != null && !deletingSource) { "Proxy source lease is closed, not bound, or deleting" }
                    pendingVerification = true
                }

                /** Persist the stop before the first source deletion, not a claim it already happened.
                 * A committed derivative remains discoverable through its unchanged SUCCEEDED history. */
                @Synchronized override fun invalidateForDeletion() {
                    check(!released && selected != null && !pendingVerification) {
                        "Proxy source lease is closed, not bound, or has pending rename verification"
                    }
                    check(mutable.value.error == null) { "Proxy queue persistence unavailable" }
                    if (deletingSource) return
                    deletingSource = true
                    boundJob?.let { bound ->
                        val current = mutable.value.jobs.single { it.id == bound.id }
                        if (current.status != ProxyJobStatus.SUCCEEDED) replace(current.copy(status = ProxyJobStatus.FAILED,
                            error = "Source deletion requested; original may be unavailable; automatic generation stopped"))
                    }
                }

                @Synchronized override fun update(observed: LocalMediaTake) {
                    check(!released && !deletingSource) { "Proxy source lease is closed or deleting" }
                    val before = checkNotNull(selected) { "Proxy source lease is not bound" }
                    checkSourceIdentity(before, observed)
                    // The original job envelope stays fixed, including terminal errors and attempts.
                    // A take without a queued/history entry never gains a request through rename.
                    boundJob?.let { replace(it.copy(take = observed)) }
                    pendingVerification = false
                }

                @Synchronized override fun close() {
                    if (released) return
                    released = true
                    try {
                        if (pendingVerification) boundJob?.let { bound ->
                            val current = mutable.value.jobs.single { it.id == bound.id }
                            replace(current.copy(status = ProxyJobStatus.FAILED,
                                error = "Source mutation incomplete or unverified; refresh the take before retry"))
                        }
                        kick()
                    } finally { gate.unlock(token) }
                }
            }
        } catch (failure: Throwable) {
            gate.unlock(token)
            throw failure
        }
    }

    private fun checkSourceIdentity(before: LocalMediaTake, after: LocalMediaTake) {
        fun members(take: LocalMediaTake): Map<String, LocalMediaArtifact> {
            val all = take.originals + take.metadata
            check(all.map { it.uri }.distinct().size == all.size && take.primary in take.originals) {
                "Proxy source membership is ambiguous"
            }
            check(all.all { it.sizeBytes >= 0 && it.modifiedSeconds >= 0 }) { "Proxy source observation is invalid" }
            return all.associateBy { it.uri }
        }
        val oldMembers = members(before)
        val newMembers = members(after)
        check(before.id == after.id && before.kind == after.kind && before.slate == after.slate &&
            before.relationStatus == after.relationStatus && before.primary.uri == after.primary.uri &&
            before.originals.map { it.uri }.toSet() == after.originals.map { it.uri }.toSet() &&
            before.metadata.map { it.uri }.toSet() == after.metadata.map { it.uri }.toSet() &&
            oldMembers.all { (uri, old) -> newMembers[uri]?.mimeType == old.mimeType } &&
            before.originals.all { old -> newMembers[old.uri]?.sizeBytes == old.sizeBytes }) {
            "Proxy source logical identity differs"
        }
    }

    /** Holds admission while durable deletion and its terminal job removal retire together. */
    suspend fun deleteProxy(take: LocalMediaTake, expected: MediaProxyResult) = withContext(Dispatchers.IO) {
        gate.withLock {
            ready()
            val job = mutable.value.jobs.firstOrNull { it.take.id == take.id }
            check(job == null || job.id == expected.proxyId && job.status in TERMINAL && activeId != job.id) {
                "Proxy job has not retired"
            }
            engine.deleteProxy(take, expected, ::commitDeletion)
        }
    }
    suspend fun deleteProxy(entry: ProxyCatalogEntry) = withContext(Dispatchers.IO) {
        gate.withLock {
            ready()
            val job = mutable.value.jobs.firstOrNull { it.take.id == entry.takeId || it.id == entry.result.proxyId }
            check(job == null || job.take.id == entry.takeId && job.id == entry.result.proxyId &&
                job.status in TERMINAL && activeId != job.id) { "Proxy catalog job has not retired or identity differs" }
            engine.deleteProxy(entry, ::commitDeletion)
        }
    }

    private suspend fun commitDeletion(id: String, takeId: String) {
        val job = mutable.value.jobs.firstOrNull { it.id == id || it.take.id == takeId }
        check(job == null || job.id == id && job.take.id == takeId && job.status in TERMINAL && activeId != id) {
            "Proxy deletion queue identity differs"
        }
        if (job != null) persist(mutable.value.jobs.filterNot { it.id == id })
    }
    private suspend fun load() {
        if (mutable.value.loaded || mutable.value.error != null || closed) return
        try {
            recovering = true
            mutable.value = ProxyQueueState(store.read(), loaded = false)
            engine.recoverDeletions(::commitDeletion)
            mutable.value = mutable.value.copy(loaded = true)
        }
        catch (_: ProxyRecoveryBusyException) { mutable.value = mutable.value.copy(loaded = false) }
        catch (failure: Exception) { mutable.value = mutable.value.copy(loaded = false, error = failure.message ?: failure.javaClass.simpleName) }
        finally { recovering = false }
    }
    private suspend fun ready() { check(!closed); load(); check(mutable.value.loaded && mutable.value.error == null) { mutable.value.error ?: "Proxy queue unavailable" } }
    private fun persist(jobs: List<ProxyJob>) {
        try {
            store.write(jobs)
            // Cancellation bypasses policy waits; only media ownership can still hold a CANCELLING cleanup.
            mutable.value = ProxyQueueState(jobs, loaded = !recovering, waiting = mutable.value.waiting.filter { (id, reason) ->
                jobs.any { it.id == id && (it.status == ProxyJobStatus.QUEUED || it.status == ProxyJobStatus.CANCELLING && reason in MEDIA_WAITS) } })
        }
        catch (rejected: ProxyJobRejectedException) { throw rejected } // Nothing written; the queue stays usable.
        catch (failure: Exception) {
            mutable.value = mutable.value.copy(error = failure.message ?: failure.javaClass.simpleName)
            throw failure
        }
    }
    /** Terminal jobs are history; committed proxies stay listed through their receipts. */
    private fun pruneHistory(jobs: List<ProxyJob>): List<ProxyJob> {
        var excess = jobs.count { it.status in TERMINAL } - (HISTORY_LIMIT - 1)
        if (excess <= 0) return jobs
        return jobs.filterNot { it.status in TERMINAL && it.id != activeId && excess-- > 0 }
    }
    private fun replace(job: ProxyJob) = persist(mutable.value.jobs.map { if (it.id == job.id) job else it })
    private fun kick() {
        if (closed || !mutable.value.loaded || mutable.value.error != null || drain?.isActive == true || mutable.value.jobs.none { it.status !in TERMINAL && it.id !in mutable.value.waiting }) return
        drain = scope.launch(start = CoroutineStart.LAZY) { runQueue() }.also { it.start() }
    }
    private suspend fun runQueue() {
        try {
            while (true) {
                val selected = gate.withLock {
                    if (closed || mutable.value.error != null) return
                    val next = mutable.value.jobs.firstOrNull { it.status !in TERMINAL && it.id !in mutable.value.waiting } ?: return
                    val admitted = if (next.status == ProxyJobStatus.CANCELLING) next else
                        next.copy(status = ProxyJobStatus.RUNNING, attempts = next.attempts + 1, error = null)
                    replace(admitted)
                    activeId = admitted.id
                    operation = scope.async(start = CoroutineStart.LAZY) {
                        try {
                            val existing = engine.reconcile(admitted)
                            if (existing != null || admitted.status == ProxyJobStatus.CANCELLING) existing else {
                                admission(admitted)?.let { throw AdmissionWait(it) }
                                engine.create(admitted)
                            }
                        } catch (busy: ProxyMediaBusyException) {
                            // Refused before any media IO: REC, a transfer or another proxy action owns media.
                            throw AdmissionWait(busy.reason)
                        }
                    }.also { it.start() }
                    admitted to requireNotNull(operation)
                }
                var result: MediaProxyResult? = null
                var failure: Throwable? = null
                try { result = selected.second.await() }
                catch (caught: Exception) { failure = caught }
                // Await cancellation retirement before reconciliation. A publication commit may win.
                withContext(NonCancellable) {
                    selected.second.join()
                    val shuttingDown = gate.withLock { closed }
                    if (!shuttingDown && failure != null && failure !is AdmissionWait) {
                        try { result = engine.reconcile(selected.first) }
                        catch (recovery: Exception) { failure = recovery }
                    }
                    gate.withLock {
                        operation = null; activeId = null
                        if (!closed) {
                            val current = mutable.value.jobs.single { it.id == selected.first.id }
                            val wait = failure as? AdmissionWait
                            val status = when {
                                result != null -> ProxyJobStatus.SUCCEEDED
                                wait != null && current.status != ProxyJobStatus.CANCELLING -> ProxyJobStatus.QUEUED
                                // Cancellation cleanup still needs its reconciliation once media ownership frees.
                                wait != null && wait.reason in MEDIA_WAITS -> ProxyJobStatus.CANCELLING
                                current.status == ProxyJobStatus.CANCELLING && (failure == null || failure is CancellationException || wait != null) -> ProxyJobStatus.CANCELLED
                                else -> ProxyJobStatus.FAILED
                            }
                            val admittedAttempt = selected.first.status == ProxyJobStatus.RUNNING
                            replace(current.copy(status = status, attempts = if (wait != null && admittedAttempt) (current.attempts - 1).coerceAtLeast(0) else current.attempts, error = if (status == ProxyJobStatus.FAILED)
                                (failure?.message ?: failure?.javaClass?.simpleName ?: "Proxy produced no committed result").take(4096) else null))
                            if (status in WAITABLE && wait != null) {
                                mutable.value = mutable.value.copy(waiting = mutable.value.waiting + (current.id to wait.reason))
                            }
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            if (failure !is CancellationException) gate.withLock {
                mutable.value = mutable.value.copy(error = failure.message ?: failure.javaClass.simpleName)
            }
        } finally {
            withContext(NonCancellable) { gate.withLock { drain = null; kick() } }
        }
    }
    /** Retires actual work without rewriting durable intent, modelling process-owner replacement. */
    suspend fun shutdown() {
        val work = gate.withLock { closed = true; operation?.cancel(); drain }
        work?.join(); scope.cancel()
    }
    private class AdmissionWait(val reason: ProxyWaitReason) : Exception()
    private companion object {
        val TERMINAL = setOf(ProxyJobStatus.SUCCEEDED, ProxyJobStatus.FAILED, ProxyJobStatus.CANCELLED)
        val WAITABLE = setOf(ProxyJobStatus.QUEUED, ProxyJobStatus.CANCELLING)
        val MEDIA_WAITS = setOf(ProxyWaitReason.CAPTURE_ACTIVE, ProxyWaitReason.TRANSFER_ACTIVE, ProxyWaitReason.MEDIA_BUSY)
        /** Terminal history retained before the oldest entries are pruned on enqueue. */
        const val HISTORY_LIMIT = 256
    }
}
