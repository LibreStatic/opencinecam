/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A native stop/read/codec call may outlive the caller's deadline. Keep its owner strongly held. */
internal object AudioRetirementGate {
    private val retiring = mutableSetOf<Thread>()
    private var idle = CompletableFuture.completedFuture(Unit)
    @Synchronized fun requireIdle() { check(retiring.isEmpty()) { "Previous audio workers are still retiring" } }
    @Synchronized fun retain(thread: Thread) {
        if (retiring.isEmpty()) idle = CompletableFuture()
        retiring.add(thread)
    }
    fun release(thread: Thread) {
        val completed = synchronized(this) {
            if (retiring.remove(thread) && retiring.isEmpty()) idle else null
        }
        // A completion can invoke arbitrary dependent callbacks synchronously. Never do so
        // while holding the gate needed by another native owner or a reentrant observer.
        completed?.complete(Unit)
    }

    /** Receipt of an idle transition, not permission to admit future capture/transfer work. */
    @Synchronized fun whenIdle(): CompletableFuture<Unit> =
        if (retiring.isEmpty()) CompletableFuture.completedFuture(Unit) else idle.thenApply { it }
}

internal data class AudioRetirementResult(val completed: Boolean, val failure: Throwable?)

/**
 * Stop and native release never execute on the waiting caller. Release follows every worker exit.
 * Timeout transfers file cleanup to the retained coordinator, rather than closing under a worker.
 */
internal fun retireAudioWorkers(
    workers: List<Thread>,
    timeoutMs: Long,
    stop: () -> Unit,
    release: () -> Unit,
    abandonedCleanup: () -> Unit,
    onAbandonedFailure: (Throwable) -> Unit = {},
): AudioRetirementResult {
    require(timeoutMs in 1..60_000)
    val lock = Any()
    val done = CountDownLatch(1)
    var completed = false
    var abandoned = false
    var failure: Throwable? = null
    val coordinator = Thread({
        fun attempt(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                val first = failure
                if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
            }
        }
        attempt(stop)
        // No timeout-driven release: a native worker retains its buffers until it actually exits.
        workers.forEach { worker -> while (worker.isAlive) {
            try { worker.join() } catch (_: InterruptedException) { /* Ownership remains here. */ }
        } }
        attempt(release)
        val completedNormally = synchronized(lock) {
            if (!abandoned) {
                completed = true
                true
            } else false
        }
        if (completedNormally) {
            AudioRetirementGate.release(Thread.currentThread())
            done.countDown()
        } else {
            try {
                attempt(abandonedCleanup)
                failure?.let(onAbandonedFailure)
            } finally {
                AudioRetirementGate.release(Thread.currentThread())
                done.countDown()
            }
        }
    }, "OpenCineCamAudioRetirement").apply { isDaemon = true }
    AudioRetirementGate.retain(coordinator)
    try { coordinator.start() } catch (problem: Throwable) {
        // Retain the owner if scheduling itself fails; do not free resources under live workers.
        return AudioRetirementResult(false, problem)
    }
    var interrupted: InterruptedException? = null
    try { done.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (problem: InterruptedException) {
        interrupted = problem
        Thread.currentThread().interrupt()
    }
    return synchronized(lock) {
        if (completed) AudioRetirementResult(true, failure ?: interrupted)
        else {
            abandoned = true
            AudioRetirementResult(false, interrupted ?: IllegalStateException("Audio workers did not retire within $timeoutMs ms"))
        }
    }
}

/** Shared stop epoch for feeder EOS and output drain; retries cannot restart the budget. */
internal class AudioStopDeadline(timeoutMs: Long, private val nowNs: () -> Long = System::nanoTime) {
    init { require(timeoutMs in 1..60_000) }
    private val durationNs = timeoutMs * 1_000_000L
    private val startedAt = AtomicReference<Long?>(null)
    fun begin() { startedAt.compareAndSet(null, nowNs()) }
    fun check() {
        val start = startedAt.get() ?: return
        check(nowNs() - start < durationNs) { "Audio codec EOS deadline exceeded" }
    }
}

/** Attempt every owned handle; a failed effect release must not become successful retirement. */
internal fun releaseAudioResources(actions: List<() -> Unit>) {
    var failure: Throwable? = null
    actions.forEach { action ->
        try { action() } catch (problem: Throwable) {
            val first = failure
            if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
        }
    }
    failure?.let { throw it }
}
