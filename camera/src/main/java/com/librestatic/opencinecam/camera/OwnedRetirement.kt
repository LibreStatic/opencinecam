/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A missed observation deadline is not permission to release resources beneath the owner. */
internal fun awaitOwnedCompletion(completion: CompletableFuture<Unit>, timeoutMs: Long, onSlow: () -> Unit) {
    require(timeoutMs > 0)
    try { completion.get(timeoutMs, TimeUnit.MILLISECONDS); return }
    catch (_: TimeoutException) { runCatching(onSlow) }
    catch (_: InterruptedException) { runCatching(onSlow) }
    while (true) {
        try { completion.get(); return } catch (_: InterruptedException) { /* Keep ownership. */ }
    }
}

internal fun joinOwnedWorker(worker: Thread?) {
    if (worker == null) return
    check(worker !== Thread.currentThread()) { "A worker cannot retire itself by joining" }
    while (worker.isAlive) {
        try { worker.join() } catch (_: InterruptedException) { /* Not proof of worker exit. */ }
    }
}

internal class CodecStopDeadline(private val durationNs: Long, private val clock: () -> Long = System::nanoTime) {
    private val origin = java.util.concurrent.atomic.AtomicReference<Long?>(null)
    fun begin() { origin.compareAndSet(null, clock()) }
    fun check() { origin.get()?.let { check(clock() - it < durationNs) { "Embedded AAC EOS deadline exceeded" } } }
}
