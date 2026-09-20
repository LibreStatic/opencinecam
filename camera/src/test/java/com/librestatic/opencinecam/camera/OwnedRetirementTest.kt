/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class OwnedRetirementTest {
    @Test fun completedOwnerDoesNotSignalSlowRetirement() {
        awaitOwnedCompletion(CompletableFuture.completedFuture(Unit), 10) { fail("slow") }
    }
    private fun delayedOwner(throwFromNotice: Boolean) {
        val completion = CompletableFuture<Unit>(); val slow = CountDownLatch(1); val released = AtomicBoolean(false)
        val waiter = Thread { awaitOwnedCompletion(completion, 25) { slow.countDown(); if (throwFromNotice) error("observer") }; released.set(true) }
        waiter.start()
        try {
            assertTrue(slow.await(2, TimeUnit.SECONDS)); assertFalse(released.get())
            waiter.interrupt(); assertFalse(released.get())
        } finally { completion.complete(Unit) }
        waiter.join(2000); assertFalse(waiter.isAlive); assertTrue(released.get())
    }
    @Test fun observationTimeoutAndInterruptDoNotReleaseLiveOwner() = delayedOwner(false)
    @Test fun failedSlowObserverDoesNotReleaseLiveOwner() = delayedOwner(true)
    @Test fun completedOwnerFailureIsReported() {
        val completion = CompletableFuture<Unit>(); val failure = IllegalStateException("GL detach")
        completion.completeExceptionally(failure)
        assertSame(failure, assertThrows(ExecutionException::class.java) { awaitOwnedCompletion(completion, 10) {} }.cause)
    }
    @Test fun interruptedJoinStillWaitsForActualWorkerExit() {
        val resume = CountDownLatch(1); val exited = AtomicBoolean(false); val released = AtomicBoolean(false)
        val worker = Thread { resume.await(); exited.set(true) }.apply { start() }
        val waiter = Thread { joinOwnedWorker(worker); assertTrue(exited.get()); released.set(true) }.apply { start() }
        try { waiter.interrupt(); assertFalse(released.get()) } finally { resume.countDown() }
        waiter.join(2000); assertTrue(released.get())
    }
    @Test fun workerCannotJoinItself() {
        assertThrows(IllegalStateException::class.java) { joinOwnedWorker(Thread.currentThread()) }
    }
    @Test fun eosDeadlineDoesNotRenewOnRetry() {
        var now = 0L; val deadline = CodecStopDeadline(100) { now }
        deadline.begin(); now = 99; deadline.begin(); deadline.check(); now = 100
        assertThrows(IllegalStateException::class.java) { deadline.check() }
    }
    @Test fun eosDeadlineIsInactiveBeforeStop() {
        var now = 0L; val deadline = CodecStopDeadline(100) { now }; now = 1000; deadline.check()
    }
}
