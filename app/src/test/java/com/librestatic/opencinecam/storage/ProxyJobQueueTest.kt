/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.ProxySettings
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProxyJobQueueTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun take(index: Int = 1): LocalMediaTake {
        val primary = LocalMediaArtifact("content://media/external_primary/video/media/$index", "$index.mp4", "video/mp4", 999, 123)
        return LocalMediaTake("legacy:${primary.uri}", primary, listOf(primary), emptyList(), LocalMediaKind.VIDEO, null, LocalMediaRelationStatus.LEGACY)
    }
    private fun result(job: ProxyJob) = MediaProxyResult(job.take.primary.uri, "proxy:${job.id}", "relation:${job.id}", "original", "proxy", 999, 123, 128, 96, 12, 3400000, 1000000, job.id)
    private suspend fun terminal(queue: ProxyJobQueue, id: String): ProxyJob = withTimeout(10_000) {
        queue.states.first { state -> state.jobs.any { it.id == id && it.status in setOf(ProxyJobStatus.SUCCEEDED, ProxyJobStatus.FAILED, ProxyJobStatus.CANCELLED) } }.jobs.single { it.id == id }
    }
    @Test fun interruptedOwnerResumesSameRequestAndSnapshotExactlyOnce() = runBlocking<Unit> {
        val dir = temporary.newFolder(); val store = ProxyJobStore(dir)
        val started = CompletableDeferred<ProxyJob>(); val retired = CompletableDeferred<Unit>()
        val first = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult {
                started.complete(job)
                try { awaitCancellation() } finally { retired.complete(Unit) }
            }
        })
        val id = first.enqueue(take(), ProxySettings(640, 1)); started.await()
        first.shutdown(); assertTrue(retired.isCompleted)
        assertEquals(ProxyJobStatus.RUNNING, ProxyJobStore(dir).read().single().status)
        val count = AtomicInteger()
        val second = ProxyJobQueue(ProxyJobStore(dir), object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult {
                assertEquals(id, job.id); assertEquals(ProxySettings(640, 1), job.settings)
                count.incrementAndGet(); return result(job)
            }
        })
        try {
            second.start(); val done = terminal(second, id)
            assertEquals(ProxyJobStatus.SUCCEEDED, done.status); assertEquals(2, done.attempts)
            assertEquals(id, second.enqueue(take(), ProxySettings(1920, 8)))
            assertEquals(1, count.get()); assertEquals(done, ProxyJobStore(dir).read().single())
        } finally { second.shutdown() }
    }
    @Test fun cancellationWaitsForRetirementAndCommitWinsTheRace() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder()); val started = CompletableDeferred<Unit>()
        val retiring = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var committed: MediaProxyResult? = null
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob) = committed
            override suspend fun create(job: ProxyJob): MediaProxyResult {
                started.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) {
                    retiring.complete(Unit); release.await(); committed = result(job)
                } }
            }
        })
        try {
            val id = queue.enqueue(take(), ProxySettings()); started.await(); queue.cancel(id); retiring.await()
            assertEquals(ProxyJobStatus.CANCELLING, store.read().single().status)
            try { queue.retry(id); fail("Retry admitted before retirement") } catch (_: IllegalStateException) { }
            release.complete(Unit)
            assertEquals(ProxyJobStatus.SUCCEEDED, terminal(queue, id).status)
        } finally { release.complete(Unit); queue.shutdown() }
    }
    @Test fun cancelledJobRetiresBeforeRetryAndUsesSameIdentity() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder()); val started = CompletableDeferred<Unit>(); val count = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult {
                if (count.incrementAndGet() == 1) { started.complete(Unit); awaitCancellation() }
                return result(job)
            }
        })
        try {
            val id = queue.enqueue(take(), ProxySettings(640, 2)); started.await(); queue.cancel(id)
            assertEquals(ProxyJobStatus.CANCELLED, terminal(queue, id).status)
            queue.retry(id); val final = terminal(queue, id)
            assertEquals(ProxyJobStatus.SUCCEEDED, final.status); assertEquals(2, final.attempts)
            assertEquals(ProxySettings(640, 2), final.settings); assertEquals(2, count.get())
        } finally { queue.shutdown() }
    }
    @Test fun failureIsDurableAndExplicitRetryReusesIdentity() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder()); val count = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult {
                if (count.incrementAndGet() == 1) error("source unavailable".repeat(1000))
                return result(job)
            }
        })
        try {
            val id = queue.enqueue(take(), ProxySettings()); assertEquals("source unavailable".repeat(1000).take(4096), terminal(queue, id).error)
            assertEquals(ProxyJobStatus.FAILED, store.read().single().status)
            queue.retry(id); assertEquals(ProxyJobStatus.SUCCEEDED, terminal(queue, id).status)
            assertEquals(2, count.get())
        } finally { queue.shutdown() }
    }
    @Test fun durableCancellationRecoversWithoutStartingEncoder() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder()); val id = UUID.randomUUID().toString()
        store.write(listOf(ProxyJob(id, take(), ProxySettings(), ProxyJobStatus.CANCELLING, 1)))
        val recovered = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? { recovered.incrementAndGet(); return null }
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("Cancelled request must not encode")
        })
        try { queue.start(); assertEquals(ProxyJobStatus.CANCELLED, terminal(queue, id).status); assertEquals(1, recovered.get()) }
        finally { queue.shutdown() }
    }
    @Test fun committedReceiptPreventsDuplicateExportAfterInterruptedStateWrite() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder()); val id = UUID.randomUUID().toString()
        store.write(listOf(ProxyJob(id, take(), ProxySettings(), ProxyJobStatus.RUNNING, 1)))
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob) = result(job)
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("Committed export must not duplicate")
        })
        try { queue.start(); assertEquals(ProxyJobStatus.SUCCEEDED, terminal(queue, id).status) } finally { queue.shutdown() }
    }
    @Test fun policyWaitAllowsCancellationAndChangeWithoutLosingIdentity() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder()); var blocked = true; val count = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult { count.incrementAndGet(); return result(job) }
        }, { if (blocked) com.librestatic.opencinecam.ProxyWaitReason.STORAGE else null })
        try {
            val id = queue.enqueue(take(), ProxySettings())
            withTimeout(10_000) { queue.states.first { id in it.waiting } }
            assertEquals(ProxyJobStatus.QUEUED, store.read().single().status)
            assertEquals(0, store.read().single().attempts); assertEquals(0, count.get())
            blocked = false; queue.conditionsChanged()
            assertEquals(ProxyJobStatus.SUCCEEDED, terminal(queue, id).status); assertEquals(1, count.get())
        } finally { queue.shutdown() }
    }
    @Test fun recoveredCommitBypassesBatteryPolicy() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder()); val id = UUID.randomUUID().toString()
        store.write(listOf(ProxyJob(id, take(), ProxySettings(), ProxyJobStatus.RUNNING, 1)))
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob) = result(job)
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("Duplicate")
        }, { error("Committed receipt must bypass admission") })
        try { queue.start(); assertEquals(ProxyJobStatus.SUCCEEDED, terminal(queue, id).status) }
        finally { queue.shutdown() }
    }

    @Test fun cancellingWaitingRequestBypassesPolicyAndNeverEncodes() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("Waiting job encoded")
        }, { com.librestatic.opencinecam.ProxyWaitReason.BATTERY })
        try {
            val id = queue.enqueue(take(), ProxySettings())
            withTimeout(10_000) { queue.states.first { id in it.waiting } }
            queue.cancel(id)
            assertEquals(ProxyJobStatus.CANCELLED, terminal(queue, id).status)
            assertEquals(0, store.read().single().attempts)
        } finally { queue.shutdown() }
    }
    @Test fun slowAdmissionIsCancellableAndRetiresBeforeTerminalState() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val retiring = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val queue = ProxyJobQueue(ProxyJobStore(temporary.newFolder()), object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("Blocked admission encoded")
        }, {
            started.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { retiring.complete(Unit); release.await() } }
        })
        try {
            val id = queue.enqueue(take(), ProxySettings()); started.await()
            withTimeout(5_000) { queue.cancel(id) }; retiring.await()
            assertEquals(ProxyJobStatus.CANCELLING, queue.states.value.jobs.single().status)
            release.complete(Unit); assertEquals(ProxyJobStatus.CANCELLED, terminal(queue, id).status)
        } finally { release.complete(Unit); queue.shutdown() }
    }
    @Test fun recoveredCandidateReconcilesBeforeStorageWaitAndDoesNotCountAsEncode() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder()); val id = UUID.randomUUID().toString(); val cleaned = AtomicInteger()
        store.write(listOf(ProxyJob(id, take(), ProxySettings(), ProxyJobStatus.RUNNING, 1)))
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? { cleaned.incrementAndGet(); return null }
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("Low storage encoded")
        }, { assertEquals(1, cleaned.get()); com.librestatic.opencinecam.ProxyWaitReason.STORAGE })
        try {
            queue.start(); withTimeout(10_000) { queue.states.first { id in it.waiting } }
            assertEquals(ProxyJobStatus.QUEUED, store.read().single().status)
            assertEquals(1, store.read().single().attempts); assertEquals(1, cleaned.get())
        } finally { queue.shutdown() }
    }

    @Test fun corruptStoreHaltsWithoutEngineIo() = runBlocking<Unit> {
        val dir = temporary.newFolder(); File(dir, "jobs.json").writeText("broken")
        val queue = ProxyJobQueue(ProxyJobStore(dir), object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = error("No IO")
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("No IO")
        })
        try {
            queue.start(); withTimeout(10_000) { queue.states.first { it.error != null } }
            try { queue.enqueue(take(), ProxySettings()); fail("Corruption hidden") } catch (_: IllegalStateException) { }
            assertEquals("broken", File(dir, "jobs.json").readText())
        } finally { queue.shutdown() }
    }

    @Test fun deleteRejectsQueuedRunningAndCancellingUntilActualRetirement() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val blocked = java.util.concurrent.atomic.AtomicBoolean(true)
        val started = CompletableDeferred<Unit>(); val retiring = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val deletes = AtomicInteger(); val creates = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult {
                creates.incrementAndGet(); started.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { retiring.complete(Unit); release.await() } }
            }
            override suspend fun deleteProxy(take: LocalMediaTake, expected: MediaProxyResult, committed: suspend (String, String) -> Unit) {
                deletes.incrementAndGet(); committed(expected.proxyId, take.id)
            }
        }, { if (blocked.get()) com.librestatic.opencinecam.ProxyWaitReason.STORAGE else null })
        try {
            val id = queue.enqueue(take(), ProxySettings())
            withTimeout(10_000) { queue.states.first { id in it.waiting } }
            val expected = result(store.read().single())
            suspend fun reject(status: ProxyJobStatus) {
                assertEquals(status, store.read().single().status)
                try { queue.deleteProxy(take(), expected); fail("Deletion admitted while$status") }
                catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("not retired")) }
                assertEquals(0, deletes.get()); assertEquals(id, store.read().single().id)
            }
            reject(ProxyJobStatus.QUEUED)
            blocked.set(false); queue.conditionsChanged(); withTimeout(10_000) { started.await() }
            reject(ProxyJobStatus.RUNNING)
            queue.cancel(id); withTimeout(10_000) { retiring.await() }
            reject(ProxyJobStatus.CANCELLING)
            release.complete(Unit); assertEquals(ProxyJobStatus.CANCELLED, terminal(queue, id).status)
            queue.deleteProxy(take(), expected)
            assertEquals(1, deletes.get()); assertEquals(1, creates.get())
            assertTrue(store.read().isEmpty()); assertTrue(queue.states.value.jobs.isEmpty())
        } finally { release.complete(Unit); queue.shutdown() }
    }

    @Test fun terminalDeletionRemovesOnlyCommittedJobAndExplicitEnqueueUsesNewIdentityAndSettings() = runBlocking<Unit> {
        for (status in listOf(ProxyJobStatus.SUCCEEDED, ProxyJobStatus.FAILED, ProxyJobStatus.CANCELLED)) {
            val store = ProxyJobStore(temporary.newFolder())
            val old = ProxyJob(UUID.randomUUID().toString(), take(), ProxySettings(640, 1), status, 2)
            val unrelated = ProxyJob(UUID.randomUUID().toString(), take(2), ProxySettings(), ProxyJobStatus.SUCCEEDED, 1)
            store.write(listOf(old, unrelated))
            val creates = AtomicInteger(); val deletes = AtomicInteger()
            val entered = CompletableDeferred<Unit>(); val commit = CompletableDeferred<Unit>()
            val queue = ProxyJobQueue(store, object : ProxyJobEngine {
                override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
                override suspend fun create(job: ProxyJob): MediaProxyResult {
                    creates.incrementAndGet(); assertNotEquals(old.id, job.id)
                    assertEquals(ProxySettings(1920, 8), job.settings); return result(job)
                }
                override suspend fun deleteProxy(take: LocalMediaTake, expected: MediaProxyResult, committed: suspend (String, String) -> Unit) {
                    deletes.incrementAndGet(); entered.complete(Unit); commit.await()
                    committed(expected.proxyId, take.id)
                }
            })
            val deletion = async { queue.deleteProxy(old.take, result(old)) }
            try {
                withTimeout(10_000) { entered.await() }
                assertEquals(listOf(old, unrelated), store.read())
                assertEquals(0, creates.get())
                commit.complete(Unit); withTimeout(10_000) { deletion.await() }
                assertEquals(listOf(unrelated), store.read()); assertEquals(listOf(unrelated), queue.states.value.jobs)
                assertEquals(1, deletes.get()); assertEquals(0, creates.get())
                val id = queue.enqueue(old.take, ProxySettings(1920, 8))
                assertNotEquals(old.id, id)
                val next = terminal(queue, id)
                assertEquals(ProxyJobStatus.SUCCEEDED, next.status)
                assertEquals(ProxySettings(1920, 8), next.settings); assertEquals(1, next.attempts)
                assertEquals(1, creates.get()); assertEquals(listOf(unrelated, next), store.read())
            } finally { commit.complete(Unit); deletion.cancelAndJoin(); queue.shutdown() }
        }
    }

    @Test fun startupDeletionRecoveryCommitsBeforeAnyQueuedEncoderCanStart() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val deleted = ProxyJob(UUID.randomUUID().toString(), take(), ProxySettings(), ProxyJobStatus.SUCCEEDED, 1)
        val queued = ProxyJob(UUID.randomUUID().toString(), take(2), ProxySettings(640, 2), ProxyJobStatus.QUEUED)
        store.write(listOf(deleted, queued))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val recovered = java.util.concurrent.atomic.AtomicBoolean(false); val creates = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun recoverDeletions(committed: suspend (String, String) -> Unit) {
                entered.complete(Unit); release.await()
                committed(deleted.id, deleted.take.id)
                assertEquals(listOf(queued), store.read()); recovered.set(true)
            }
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? {
                assertTrue(recovered.get()); assertEquals(queued.id, job.id); return null
            }
            override suspend fun create(job: ProxyJob): MediaProxyResult {
                assertTrue(recovered.get()); assertEquals(queued.id, job.id)
                assertFalse(store.read().any { it.id == deleted.id }); creates.incrementAndGet(); return result(job)
            }
        })
        try {
            queue.start(); withTimeout(10_000) { entered.await() }
            assertEquals(0, creates.get()); assertEquals(listOf(deleted, queued), store.read())
            release.complete(Unit)
            assertEquals(ProxyJobStatus.SUCCEEDED, terminal(queue, queued.id).status)
            assertEquals(1, creates.get()); assertEquals(listOf(queued.id), store.read().map { it.id })
        } finally { release.complete(Unit); queue.shutdown() }
    }

    @Test fun mismatchedDeletionCallbackCannotRemoveTerminalJob() = runBlocking<Unit> {
        for (wrongId in listOf(false, true)) {
            val store = ProxyJobStore(temporary.newFolder())
            val old = ProxyJob(UUID.randomUUID().toString(), take(), ProxySettings(), ProxyJobStatus.SUCCEEDED, 1)
            store.write(listOf(old)); val deletes = AtomicInteger()
            val queue = ProxyJobQueue(store, object : ProxyJobEngine {
                override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = error("Terminal job reconciled")
                override suspend fun create(job: ProxyJob): MediaProxyResult = error("Deletion must not encode")
                override suspend fun deleteProxy(take: LocalMediaTake, expected: MediaProxyResult, committed: suspend (String, String) -> Unit) {
                    deletes.incrementAndGet()
                    committed(if (wrongId) UUID.randomUUID().toString() else old.id,
                        if (wrongId) old.take.id else this@ProxyJobQueueTest.take(2).id)
                }
            })
            try {
                try { queue.deleteProxy(old.take, result(old)); fail("Mismatched callback accepted") }
                catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("identity differs")) }
                assertEquals(1, deletes.get()); assertEquals(listOf(old), store.read())
                assertEquals(listOf(old), queue.states.value.jobs)
            } finally { queue.shutdown() }
        }
    }

    @Test fun startupMismatchedDeletionRecoveryHaltsBeforeAnyCreateAndPreservesJobs() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val old = ProxyJob(UUID.randomUUID().toString(), take(), ProxySettings(), ProxyJobStatus.SUCCEEDED, 1)
        val pending = ProxyJob(UUID.randomUUID().toString(), take(2), ProxySettings(), ProxyJobStatus.QUEUED)
        store.write(listOf(old, pending)); val engineCalls = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun recoverDeletions(committed: suspend (String, String) -> Unit) { committed(old.id, pending.take.id) }
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? { engineCalls.incrementAndGet(); error("Invalid recovery proceeded") }
            override suspend fun create(job: ProxyJob): MediaProxyResult { engineCalls.incrementAndGet(); error("Invalid recovery encoded") }
        })
        try {
            queue.start()
            val state = withTimeout(10_000) { queue.states.first { it.error != null } }
            assertTrue(state.error.orEmpty().contains("identity differs"))
            assertEquals(listOf(old, pending), store.read()); assertEquals(listOf(old, pending), state.jobs)
            assertEquals(0, engineCalls.get())
        } finally { queue.shutdown() }
    }

    @Test fun transientBusyRecoveryWaitsForConditionsChangeAndRetirementBeforeEncoding() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val deleted = ProxyJob(UUID.randomUUID().toString(), take(), ProxySettings(), ProxyJobStatus.SUCCEEDED, 1)
        val pending = ProxyJob(UUID.randomUUID().toString(), take(2), ProxySettings(640, 2), ProxyJobStatus.QUEUED)
        store.write(listOf(deleted, pending))
        val busy = CompletableDeferred<Unit>(); val secondEntered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val recoveries = AtomicInteger(); val reconciles = AtomicInteger(); val creates = AtomicInteger()
        val retired = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var queue: ProxyJobQueue
        queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun recoverDeletions(committed: suspend (String, String) -> Unit) {
                if (recoveries.incrementAndGet() == 1) {
                    busy.complete(Unit)
                    throw ProxyRecoveryBusyException()
                }
                assertEquals(2, recoveries.get())
                // This second invocation holds the gate after the first busy catch completed.
                assertFalse(queue.states.value.loaded); assertNull(queue.states.value.error)
                assertEquals(0, reconciles.get()); assertEquals(0, creates.get())
                committed(deleted.id, deleted.take.id)
                secondEntered.complete(Unit)
                release.await(); retired.set(true)
            }
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? {
                assertTrue(retired.get()); assertTrue(queue.states.value.loaded)
                assertEquals(pending.id, job.id); reconciles.incrementAndGet(); return null
            }
            override suspend fun create(job: ProxyJob): MediaProxyResult {
                assertTrue(retired.get()); assertEquals(pending.id, job.id)
                creates.incrementAndGet(); return result(job)
            }
        })
        try {
            queue.start(); withTimeout(10_000) { busy.await() }
            assertFalse(queue.states.value.loaded); assertNull(queue.states.value.error)
            assertEquals(0, creates.get()); assertEquals(listOf(deleted, pending), store.read())
            queue.conditionsChanged()
            withTimeout(10_000) { secondEntered.await() }
            assertFalse(queue.states.value.loaded); assertNull(queue.states.value.error)
            assertEquals(listOf(pending), store.read()); assertEquals(listOf(pending), queue.states.value.jobs)
            assertEquals(0, reconciles.get()); assertEquals(0, creates.get())
            release.complete(Unit)
            val done = terminal(queue, pending.id)
            assertEquals(ProxyJobStatus.SUCCEEDED, done.status); assertEquals(1, done.attempts)
            assertTrue(queue.states.value.loaded); assertNull(queue.states.value.error)
            assertEquals(2, recoveries.get()); assertEquals(1, reconciles.get()); assertEquals(1, creates.get())
            assertEquals(listOf(done), store.read())
        } finally { release.complete(Unit); queue.shutdown() }
    }

    @Test fun sourceMutationPreservesWaitingJobAndNextEngineReceivesRenamedSnapshot() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val blocked = java.util.concurrent.atomic.AtomicBoolean(true)
        val encoded = CompletableDeferred<ProxyJob>()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult { encoded.complete(job); return result(job) }
        }, { if (blocked.get()) com.librestatic.opencinecam.ProxyWaitReason.STORAGE else null })
        try {
            val id = queue.enqueue(take(), ProxySettings(1920, 8))
            val waiting = withTimeout(10_000) { queue.states.first { id in it.waiting } }
            val before = waiting.jobs.single()
            val renamedArtifact = before.take.primary.copy(name = "Renamed.mp4", modifiedSeconds = 456)
            val renamed = before.take.copy(primary = renamedArtifact, originals = listOf(renamedArtifact))
            val lease = queue.acquireSourceMutation()
            try {
                lease.bind(before.take); lease.begin(); lease.update(renamed)
                assertEquals(listOf(before.copy(take = renamed)), store.read())
                assertEquals(waiting.copy(jobs = listOf(before.copy(take = renamed))), queue.states.value)
                assertFalse(encoded.isCompleted)
            } finally { lease.close(); lease.close() }
            // Closing a lease does not discard the stored policy wait or request settings.
            assertEquals(waiting.waiting, queue.states.value.waiting)
            blocked.set(false); queue.conditionsChanged()
            val admitted = withTimeout(10_000) { encoded.await() }
            assertEquals(renamed, admitted.take); assertEquals(id, admitted.id)
            assertEquals(before.settings, admitted.settings); assertEquals(before.attempts + 1, admitted.attempts)
            assertEquals(ProxyJobStatus.SUCCEEDED, terminal(queue, id).status)
        } finally { queue.shutdown() }
    }

    @Test fun sourceMutationRejectsAlreadySelectedReconciliationBeforeEncoderStarts() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val creates = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? {
                entered.complete(Unit); release.await(); return null
            }
            override suspend fun create(job: ProxyJob): MediaProxyResult { creates.incrementAndGet(); return result(job) }
        })
        try {
            val id = queue.enqueue(take(), ProxySettings())
            withTimeout(10_000) { entered.await() }
            assertEquals(0, creates.get())
            try { queue.acquireSourceMutation().use { fail("Selected reconciliation was stolen") } }
            catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("not retired")) }
            // Rejection must unlock the gate, not prevent normal completion.
            release.complete(Unit)
            assertEquals(ProxyJobStatus.SUCCEEDED, terminal(queue, id).status)
            assertEquals(1, creates.get())
        } finally { release.complete(Unit); queue.shutdown() }
    }

    @Test fun sourceMutationWithoutJobNeverCreatesRequestAndClosedLeaseRejectsReuse() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = error("Unrequested reconciliation")
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("Unrequested encoding")
        })
        try {
            val selected = take()
            val artifact = selected.primary.copy(name = "Only source.mp4", modifiedSeconds = 456)
            val observed = selected.copy(primary = artifact, originals = listOf(artifact))
            val lease = queue.acquireSourceMutation()
            try {
                try { lease.update(observed); fail("Unbound lease updated") } catch (_: IllegalStateException) { }
                lease.bind(selected); lease.begin(); lease.update(observed); lease.update(selected)
                assertTrue(store.read().isEmpty()); assertTrue(queue.states.value.jobs.isEmpty())
                try { lease.bind(selected); fail("Lease rebound") } catch (_: IllegalStateException) { }
            } finally { lease.close(); lease.close() }
            try { lease.update(observed); fail("Closed lease updated") } catch (_: IllegalStateException) { }
            try { lease.bind(selected); fail("Closed lease bound") } catch (_: IllegalStateException) { }
            queue.acquireSourceMutation().close() // Idempotent close released exactly once.
            assertTrue(store.read().isEmpty())
        } finally { queue.shutdown() }
    }

    @Test fun sourceMutationRejectsStaleSelectionAndLogicalIdentityChangesWithoutPersisting() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val selected = take()
        val before = ProxyJob(UUID.randomUUID().toString(), selected, ProxySettings(), ProxyJobStatus.FAILED, 3, "Prior failure")
        store.write(listOf(before))
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = error("Terminal reconciliation")
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("Terminal encoding")
        })
        try {
            val lease = queue.acquireSourceMutation()
            try {
                val changed = selected.primary.copy(name = "Stale.mp4")
                try { lease.bind(selected.copy(primary = changed, originals = listOf(changed))); fail("Stale selection bound") }
                catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("snapshot differs")) }
                lease.bind(selected)
                val alien = take(2).primary
                val invalid = listOf(
                    selected.copy(id = take(2).id), selected.copy(kind = LocalMediaKind.AUDIO),
                    selected.copy(relationStatus = LocalMediaRelationStatus.DECLARED),
                    selected.copy(slate = com.librestatic.opencinecam.ProductionSlateSettings()),
                    selected.copy(primary = alien, originals = listOf(alien)),
                    selected.copy(originals = listOf(selected.primary, alien)),
                    selected.copy(metadata = listOf(alien)),
                    selected.copy(originals = listOf(selected.primary, selected.primary)),
                    selected.primary.copy(mimeType = "audio/mp4").let { selected.copy(primary = it, originals = listOf(it)) },
                    selected.primary.copy(sizeBytes = 1000).let { selected.copy(primary = it, originals = listOf(it)) },
                    selected.primary.copy(modifiedSeconds = -1).let { selected.copy(primary = it, originals = listOf(it)) }
                )
                for (observed in invalid) {
                    try { lease.update(observed); fail("Different logical identity accepted: $observed") }
                    catch (_: IllegalStateException) { }
                    assertEquals(listOf(before), store.read()); assertEquals(listOf(before), queue.states.value.jobs)
                    assertNull(queue.states.value.error)
                }
            } finally { lease.close() }
        } finally { queue.shutdown() }
    }

    @Test fun sourceMutationUpdatesMetadataThenCompensationWithoutChangingTerminalEnvelope() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val metadata = LocalMediaArtifact("content://media/external_primary/downloads/9", "Original.json", "application/json", 50, 123)
        val selected = take().copy(id = "take:${UUID.randomUUID()}", metadata = listOf(metadata), relationStatus = LocalMediaRelationStatus.DECLARED)
        val before = ProxyJob(UUID.randomUUID().toString(), selected, ProxySettings(640, 5), ProxyJobStatus.FAILED, 4, "Keep this diagnostic")
        store.write(listOf(before))
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = error("Terminal reconciliation")
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("Terminal encoding")
        })
        try {
            val renamed = selected.primary.copy(name = "Renamed.mp4", modifiedSeconds = 456)
            val observed = selected.copy(primary = renamed, originals = listOf(renamed),
                metadata = listOf(metadata.copy(name = "Renamed.json", sizeBytes = 80, modifiedSeconds = 456)))
            val restored = selected.primary.copy(modifiedSeconds = 789)
            val compensated = selected.copy(primary = restored, originals = listOf(restored),
                metadata = listOf(metadata.copy(modifiedSeconds = 789)))
            queue.acquireSourceMutation().use { lease ->
                lease.bind(selected); lease.begin(); lease.update(observed)
                assertEquals(listOf(before.copy(take = observed)), store.read())
                lease.begin(); lease.update(compensated)
                assertEquals(listOf(before.copy(take = compensated)), store.read())
                assertEquals(listOf(before.copy(take = compensated)), queue.states.value.jobs)
                assertNull(queue.states.value.error)
            }
        } finally { queue.shutdown() }
    }

    @Test fun sourceLeaseHoldsSelectionAcrossStartAndConditionsChangeUntilReleased() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val pending = ProxyJob(UUID.randomUUID().toString(), take(), ProxySettings(640, 2), ProxyJobStatus.QUEUED)
        store.write(listOf(pending))
        val entered = CompletableDeferred<ProxyJob>()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? { entered.complete(job); return null }
            override suspend fun create(job: ProxyJob): MediaProxyResult = result(job)
        })
        val first = queue.acquireSourceMutation()
        // Undispatched direct acquisition runs through gate.lock and suspends behind the first lease.
        val second = async(start = CoroutineStart.UNDISPATCHED) { queue.acquireSourceMutation() }
        var next: ProxySourceMutationLease? = null
        try {
            queue.start(); queue.conditionsChanged()
            assertFalse(second.isCompleted); assertFalse(entered.isCompleted)
            assertEquals(listOf(pending), store.read())
            val renamed = pending.take.primary.copy(name = "Before selection.mp4", modifiedSeconds = 456)
            val observed = pending.take.copy(primary = renamed, originals = listOf(renamed))
            first.bind(pending.take); first.begin(); first.update(observed); first.close()
            next = withTimeout(10_000) { second.await() }
            assertFalse(entered.isCompleted)
            assertEquals(listOf(pending.copy(take = observed)), store.read())
            next.close()
            val admitted = withTimeout(10_000) { entered.await() }
            assertEquals(observed, admitted.take); assertEquals(pending.id, admitted.id)
            assertEquals(ProxyJobStatus.SUCCEEDED, terminal(queue, pending.id).status)
        } finally {
            first.close()
            withContext(NonCancellable) { (next ?: second.await()).close(); queue.shutdown() }
        }
    }

    @Test fun unverifiedSourceMutationFailsWaitingJobWithoutAutomaticEncodingOrIdentityLoss() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val creates = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult { creates.incrementAndGet(); return result(job) }
        }, { com.librestatic.opencinecam.ProxyWaitReason.BATTERY })
        try {
            val id = queue.enqueue(take(), ProxySettings(1920, 5))
            val waiting = withTimeout(10_000) { queue.states.first { id in it.waiting } }
            val before = waiting.jobs.single()
            // A rejected preflight never starts mutation and must leave waiting intent untouched.
            queue.acquireSourceMutation().use { it.bind(before.take) }
            assertEquals(waiting, queue.states.value); assertEquals(listOf(before), store.read())
            val lease = queue.acquireSourceMutation()
            lease.bind(before.take); lease.begin()
            try {
                lease.update(before.take.copy(relationStatus = LocalMediaRelationStatus.INCOMPLETE))
                fail("Unverified changed relationships accepted")
            } catch (_: IllegalStateException) { }
            finally { lease.close(); lease.close() }
            val failed = store.read().single()
            assertEquals(ProxyJobStatus.FAILED, failed.status)
            assertTrue(failed.error.orEmpty().contains("unverified"))
            assertEquals(before, failed.copy(status = before.status, error = before.error))
            assertEquals(listOf(failed), queue.states.value.jobs)
            assertTrue(queue.states.value.waiting.isEmpty()); assertNull(queue.states.value.error)
            assertEquals(0, creates.get())
            // Closing an unverified no-job mutation must not fabricate a failed request either.
            queue.acquireSourceMutation().use { it.bind(take(2)); it.begin() }
            assertEquals(listOf(failed), store.read()); assertEquals(0, creates.get())
        } finally { queue.shutdown() }
    }

    @Test fun unverifiedSourceCloseStorageFailureBlocksQueueAndStillReleasesGate() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val store = ProxyJobStore(directory)
        val pending = ProxyJob(UUID.randomUUID().toString(), take(), ProxySettings(), ProxyJobStatus.QUEUED)
        store.write(listOf(pending))
        val calls = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? { calls.incrementAndGet(); return null }
            override suspend fun create(job: ProxyJob): MediaProxyResult { calls.incrementAndGet(); return result(job) }
        })
        val staging = File(directory, "jobs.json.tmp")
        try {
            val lease = queue.acquireSourceMutation()
            try {
                lease.bind(pending.take); lease.begin()
                assertTrue(staging.mkdir()) // A real filesystem write failure, without replacing committed bytes.
            } finally {
                try { lease.close(); fail("Persistence failure hidden") }
                catch (_: IllegalStateException) { }
                finally { lease.close() }
            }
            assertNotNull(queue.states.value.error); assertEquals(listOf(pending), queue.states.value.jobs)
            assertTrue(staging.delete()); assertEquals(listOf(pending), store.read())
            withTimeout(10_000) {
                try { queue.acquireSourceMutation().use { fail("Errored queue admitted mutation") } }
                catch (_: IllegalStateException) { }
            }
            assertEquals(0, calls.get())
        } finally { if (staging.exists()) check(staging.delete()); queue.shutdown() }
    }

    @Test fun sourceDeletionInvalidatesEveryUncommittedStatusBeforeCloseAndSurvivesReopen() = runBlocking<Unit> {
        for (status in ProxyJobStatus.entries.filter { it != ProxyJobStatus.SUCCEEDED }) {
            val store = ProxyJobStore(temporary.newFolder())
            val before = ProxyJob(UUID.randomUUID().toString(), take(), ProxySettings(1920, 5), status, 3, "Old diagnostic")
            store.write(listOf(before))
            val calls = AtomicInteger()
            val engine = object : ProxyJobEngine {
                override suspend fun reconcile(job: ProxyJob): MediaProxyResult? { calls.incrementAndGet(); return null }
                override suspend fun create(job: ProxyJob): MediaProxyResult { calls.incrementAndGet(); return result(job) }
            }
            val queue = ProxyJobQueue(store, engine)
            val lease = queue.acquireSourceMutation()
            try {
                lease.bind(before.take); lease.invalidateForDeletion()
                val stopped = store.read().single() // Durable BEFORE the caller can delete anything or release admission.
                assertEquals(ProxyJobStatus.FAILED, stopped.status)
                assertTrue(stopped.error.orEmpty().contains("Source deletion requested"))
                assertTrue(stopped.error.orEmpty().contains("automatic generation stopped"))
                assertEquals(before, stopped.copy(status = before.status, error = before.error))
                assertEquals(listOf(stopped), queue.states.value.jobs); assertNull(queue.states.value.error)
                queue.start(); queue.conditionsChanged()
                assertEquals(0, calls.get())
                lease.invalidateForDeletion(); assertEquals(listOf(stopped), store.read())
            } finally { lease.close(); lease.close(); queue.shutdown() }
            val reopened = ProxyJobQueue(store, engine)
            try {
                reopened.start()
                withTimeout(10_000) { reopened.states.first { it.loaded || it.error != null } }
                reopened.acquireSourceMutation().use { assertEquals(store.read(), reopened.states.value.jobs) }
                assertNull(reopened.states.value.error)
                assertEquals(ProxyJobStatus.FAILED, reopened.states.value.jobs.single().status)
                assertEquals(0, calls.get())
            } finally { reopened.shutdown() }
        }
    }

    @Test fun sourceDeletionPreservesSucceededHistoryAndItsCommittedSnapshotBytes() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val store = ProxyJobStore(directory)
        val before = ProxyJob(UUID.randomUUID().toString(), take(), ProxySettings(640, 2), ProxyJobStatus.SUCCEEDED, 4)
        store.write(listOf(before))
        val bytes = File(directory, "jobs.json").readBytes()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = error("Committed history must not reconcile")
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("Committed history must not regenerate")
        })
        try {
            queue.acquireSourceMutation().use { lease ->
                lease.bind(before.take); lease.invalidateForDeletion()
                assertEquals(listOf(before), store.read())
                assertArrayEquals(bytes, File(directory, "jobs.json").readBytes())
            }
            assertEquals(listOf(before), queue.states.value.jobs)
            assertArrayEquals(bytes, File(directory, "jobs.json").readBytes())
            assertNull(queue.states.value.error)
        } finally { queue.shutdown() }
    }

    @Test fun sourceDeletionWithoutJobNeverCreatesOneAndRejectsMixedLeaseLifecycle() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val store = ProxyJobStore(directory)
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = error("No job requested")
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("No job requested")
        })
        try {
            val selected = take()
            val lease = queue.acquireSourceMutation()
            try {
                try { lease.invalidateForDeletion(); fail("Unbound deletion accepted") } catch (_: IllegalStateException) { }
                lease.bind(selected); lease.begin()
                try { lease.invalidateForDeletion(); fail("Pending rename accepted as deletion") } catch (_: IllegalStateException) { }
                lease.update(selected) // A verified compensation retires the pending rename first.
                lease.invalidateForDeletion(); lease.invalidateForDeletion()
                try { lease.begin(); fail("Deletion lease began rename") } catch (_: IllegalStateException) { }
                try { lease.update(selected); fail("Deletion lease restored old request") } catch (_: IllegalStateException) { }
                assertTrue(store.read().isEmpty()); assertTrue(queue.states.value.jobs.isEmpty())
                assertFalse(File(directory, "jobs.json").exists())
            } finally { lease.close(); lease.close() }
            try { lease.invalidateForDeletion(); fail("Closed deletion lease reused") } catch (_: IllegalStateException) { }
            assertTrue(store.read().isEmpty()); assertFalse(File(directory, "jobs.json").exists())
        } finally { queue.shutdown() }
    }

    @Test fun sourceDeletionInvalidationStorageFailurePreventsAdmissionAndLeavesPriorSnapshot() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val store = ProxyJobStore(directory)
        val before = ProxyJob(UUID.randomUUID().toString(), take(), ProxySettings(640, 5), ProxyJobStatus.QUEUED, 2)
        store.write(listOf(before))
        val bytes = File(directory, "jobs.json").readBytes()
        val calls = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? { calls.incrementAndGet(); return null }
            override suspend fun create(job: ProxyJob): MediaProxyResult { calls.incrementAndGet(); return result(job) }
        })
        val staging = File(directory, "jobs.json.tmp")
        try {
            val lease = queue.acquireSourceMutation()
            try {
                lease.bind(before.take); assertTrue(staging.mkdir())
                try { lease.invalidateForDeletion(); fail("Deletion admitted despite storage failure") }
                catch (_: IllegalStateException) { }
                assertNotNull(queue.states.value.error)
                assertEquals(listOf(before), queue.states.value.jobs)
                assertArrayEquals(bytes, File(directory, "jobs.json").readBytes())
                try { lease.invalidateForDeletion(); fail("Repeated failed invalidation reported success") }
                catch (_: IllegalStateException) { }
                try { lease.update(before.take); fail("Failed deletion lease reset queue error") }
                catch (_: IllegalStateException) { }
                queue.start(); queue.conditionsChanged()
            } finally { lease.close() }
            assertTrue(staging.delete()); assertEquals(listOf(before), store.read())
            withTimeout(10_000) {
                try { queue.acquireSourceMutation().use { fail("Queue ignored persistence error") } }
                catch (_: IllegalStateException) { }
            }
            assertEquals(0, calls.get()); assertNotNull(queue.states.value.error)
        } finally { if (staging.exists()) check(staging.delete()); queue.shutdown() }
    }

    @Test fun mediaOwnershipBusyWaitsQueuedAndResumesWhenReleased() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val busy = java.util.concurrent.atomic.AtomicReference<com.librestatic.opencinecam.ProxyWaitReason?>(
            com.librestatic.opencinecam.ProxyWaitReason.CAPTURE_ACTIVE)
        val reconciles = AtomicInteger(); val creates = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? {
                reconciles.incrementAndGet()
                busy.get()?.takeIf { it == com.librestatic.opencinecam.ProxyWaitReason.CAPTURE_ACTIVE }?.let {
                    throw ProxyMediaBusyException(it, "Local media changes require idle capture and transfer ownership", IllegalStateException())
                }
                return null
            }
            override suspend fun create(job: ProxyJob): MediaProxyResult {
                busy.get()?.let { throw ProxyMediaBusyException(it, "Another proxy operation is active") }
                creates.incrementAndGet(); return result(job)
            }
        })
        try {
            val id = queue.enqueue(take(), ProxySettings())
            val waiting = withTimeout(10_000) { queue.states.first { id in it.waiting } }
            assertEquals(com.librestatic.opencinecam.ProxyWaitReason.CAPTURE_ACTIVE, waiting.waiting[id])
            assertEquals(ProxyJobStatus.QUEUED, store.read().single().status)
            assertEquals(0, store.read().single().attempts); assertNull(store.read().single().error)
            assertNull(waiting.error)
            // Another proxy action now holds the media lock instead of REC: still a wait, never FAILED.
            busy.set(com.librestatic.opencinecam.ProxyWaitReason.MEDIA_BUSY); queue.mediaReleased()
            withTimeout(10_000) { queue.states.first { it.waiting[id] == com.librestatic.opencinecam.ProxyWaitReason.MEDIA_BUSY } }
            assertEquals(ProxyJobStatus.QUEUED, store.read().single().status)
            busy.set(null); queue.conditionsChanged()
            val done = terminal(queue, id)
            assertEquals(ProxyJobStatus.SUCCEEDED, done.status); assertEquals(1, done.attempts)
            assertEquals(1, creates.get()); assertTrue(queue.states.value.waiting.isEmpty())
        } finally { queue.shutdown() }
    }

    @Test fun mediaReleaseWakesOnlyMediaWaitsNotPolicyWaits() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder()); val admissions = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("Battery wait encoded")
        }, { admissions.incrementAndGet(); com.librestatic.opencinecam.ProxyWaitReason.BATTERY })
        try {
            val id = queue.enqueue(take(), ProxySettings())
            withTimeout(10_000) { queue.states.first { id in it.waiting } }
            queue.mediaReleased(); queue.mediaReleased(); delay(200)
            // Queue work is serialized on the gate; a later enqueue runs after the released wake-ups.
            assertEquals(id, queue.enqueue(take(), ProxySettings()))
            assertEquals(1, admissions.get())
            assertEquals(com.librestatic.opencinecam.ProxyWaitReason.BATTERY, queue.states.value.waiting[id])
        } finally { queue.shutdown() }
    }

    @Test fun cancellingRequestWaitsForMediaOwnershipBeforeCleanup() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder()); val id = UUID.randomUUID().toString()
        store.write(listOf(ProxyJob(id, take(), ProxySettings(), ProxyJobStatus.CANCELLING, 1)))
        val blocked = java.util.concurrent.atomic.AtomicBoolean(true); val cleaned = AtomicInteger()
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? {
                if (blocked.get()) throw ProxyMediaBusyException(com.librestatic.opencinecam.ProxyWaitReason.TRANSFER_ACTIVE, "busy")
                cleaned.incrementAndGet(); return null
            }
            override suspend fun create(job: ProxyJob): MediaProxyResult = error("Cancelled request must not encode")
        })
        try {
            queue.start()
            withTimeout(10_000) { queue.states.first { id in it.waiting } }
            assertEquals(ProxyJobStatus.CANCELLING, store.read().single().status); assertEquals(1, store.read().single().attempts)
            blocked.set(false); queue.mediaReleased()
            val done = terminal(queue, id)
            assertEquals(ProxyJobStatus.CANCELLED, done.status); assertEquals(1, done.attempts); assertEquals(1, cleaned.get())
        } finally { queue.shutdown() }
    }

    @Test fun unrepresentableTakeIsRejectedPerCallAndQueueStaysUsable() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult = result(job)
        })
        try {
            val base = take(1)
            val metadata = (1..5).map { LocalMediaArtifact("content://media/external_primary/file/$it", "$it.json", "application/json", 1, 1) }
            try { queue.enqueue(base.copy(metadata = metadata), ProxySettings()); fail("Unrepresentable take queued") }
            catch (rejected: ProxyJobRejectedException) { assertTrue(rejected.message.orEmpty().startsWith("Proxy request rejected")) }
            assertNull(queue.states.value.error); assertEquals(emptyList<ProxyJob>(), store.read())
            val id = queue.enqueue(take(2), ProxySettings())
            assertEquals(ProxyJobStatus.SUCCEEDED, terminal(queue, id).status)
            assertNull(queue.states.value.error)
        } finally { queue.shutdown() }
    }

    @Test fun terminalHistoryIsPrunedOldestFirstOnEnqueue() = runBlocking<Unit> {
        val store = ProxyJobStore(temporary.newFolder())
        val history = (1..300).map { index ->
            ProxyJob(UUID.randomUUID().toString(), take(index), ProxySettings(),
                if (index % 2 == 0) ProxyJobStatus.SUCCEEDED else ProxyJobStatus.FAILED, 1, if (index % 2 == 0) null else "failed")
        }
        store.write(history)
        val queue = ProxyJobQueue(store, object : ProxyJobEngine {
            override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = null
            override suspend fun create(job: ProxyJob): MediaProxyResult = result(job)
        })
        try {
            val id = queue.enqueue(take(1000), ProxySettings())
            val kept = store.read()
            assertEquals(256, kept.size)
            assertEquals(history.takeLast(255), kept.take(255)); assertEquals(id, kept.last().id)
            assertEquals(ProxyJobStatus.SUCCEEDED, terminal(queue, id).status)
            assertNull(queue.states.value.error)
        } finally { queue.shutdown() }
    }
}
