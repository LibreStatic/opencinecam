/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

/** One preview microphone's worker and retirement, without native calls under the state lock. */
internal class PreviewAudioLifecycle(
    private val startNative: () -> Unit,
    private val readLoop: () -> Unit,
    private val stopNative: () -> Unit,
    private val releaseNative: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val retainRetirement: (Thread) -> Unit,
    private val releaseRetirement: (Thread) -> Unit,
) {
    private val lock = Any()
    @Volatile private var closing = false
    @Volatile private var nativeStartAttempted = false
    private var startRequested = false
    private var workerLaunched = false
    private val startFinished = CountDownLatch(1)
    private val retired = CompletableFuture<Unit>()
    private val worker = Thread(::runWorker, "OpenCineCamAudioMonitor")
    private val coordinator = object : Thread("OpenCineCamPreviewAudioRetirement") {
        // Thread clears its Runnable on exit. Keep an explicit owner while a failed release
        // leaves this coordinator retained by the gate.
        private val retainedOwner = this@PreviewAudioLifecycle
        override fun run() = retainedOwner.retire()
    }.apply { isDaemon = true }

    val isRunning: Boolean get() = !closing

    /** Native start belongs to the worker, never to the caller or state lock. */
    fun start() {
        var launchFailure: Throwable? = null
        synchronized(lock) {
            check(!startRequested && !closing) { "Preview audio monitor is already started or closing." }
            startRequested = true
            try {
                worker.start()
                workerLaunched = true
            } catch (failure: Throwable) {
                startFinished.countDown()
                launchFailure = failure
            }
        }
        launchFailure?.let { failure -> closeAsync(); throw failure }
    }

    /** No caller receives the completable owner, including repeated and cancelled observations. */
    fun closeAsync(): CompletableFuture<Unit> {
        var schedulingFailure: Throwable? = null
        synchronized(lock) {
            if (!closing) {
                closing = true
                if (!workerLaunched) startFinished.countDown()
                try {
                    // Retention precedes every return, including concurrent callers of closeAsync.
                    retainRetirement(coordinator)
                    coordinator.start()
                } catch (failure: Throwable) {
                    // Scheduling failure is not permission to release beneath a live native call.
                    schedulingFailure = failure
                }
            }
        }
        schedulingFailure?.let(retired::completeExceptionally)
        return retired.thenApply { it }
    }

    private fun runWorker() {
        var failure: Throwable? = null
        try {
            if (!closing) {
                nativeStartAttempted = true
                startNative()
            }
        } catch (problem: Throwable) {
            failure = problem
        } finally {
            startFinished.countDown()
        }
        if (failure == null && !closing) {
            try { readLoop() } catch (problem: Throwable) { failure = problem }
        }
        val notifyFailure = failure != null && !closing
        // Capture/read errors are content failures; successful subsequent retirement stays valid.
        closeAsync()
        if (notifyFailure) onFailure(requireNotNull(failure))
    }

    private fun retire() {
        awaitUninterruptibly(startFinished)
        var failure: Throwable? = null
        fun attempt(action: () -> Unit): Boolean = try { action(); true } catch (problem: Throwable) {
            val first = failure
            if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
            false
        }
        if (nativeStartAttempted) attempt(stopNative)
        // Stop is allowed to unblock read; release is allowed only after both really return.
        while (worker.isAlive) {
            try { worker.join() } catch (_: InterruptedException) { /* Ownership remains here. */ }
        }
        val resourcesReleased = attempt(releaseNative)
        // A failed stop remains observable, but successful release proves resources are retired.
        // Failed release deliberately retains the gate, even though the coordinator terminates.
        if (resourcesReleased) attempt { releaseRetirement(coordinator) }
        val result = failure
        if (result == null) retired.complete(Unit) else retired.completeExceptionally(result)
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (true) {
            try { latch.await(); return } catch (_: InterruptedException) { /* No false retirement. */ }
        }
    }
}
