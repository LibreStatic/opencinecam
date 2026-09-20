/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class RecordingPreparationTest {
    @Test fun timeoutBeforeCommitPreventsLateStart() {
        val owner = RecordingPreparation()
        assertTrue(owner.cancel()); assertFalse(owner.commit())
        assertFalse(owner.awaitCommit()); assertFalse(owner.isCommitted)
    }
    @Test fun timeoutAfterCommitPreservesAcceptedOwner() {
        val owner = RecordingPreparation()
        assertTrue(owner.commit()); assertFalse(owner.cancel())
        assertTrue(owner.awaitCommit()); assertFalse(owner.isCancelled)
    }
    @Test fun cancellationIsTerminalAndIdempotent() {
        val owner = RecordingPreparation()
        repeat(3) { assertTrue(owner.cancel()); assertFalse(owner.commit()) }
    }
    @Test fun commitHasExactlyOneWinner() {
        val owner = RecordingPreparation()
        assertTrue(owner.commit()); assertFalse(owner.commit()); assertTrue(owner.isCommitted)
    }
    @Test fun preparedWorkerWaitsForTheDecisionBeforeTouchingResources() {
        val owner = RecordingPreparation(); val entered = CountDownLatch(1); val exited = CountDownLatch(1)
        val touched = AtomicBoolean(false)
        val worker = Thread { entered.countDown(); if (owner.awaitCommit()) touched.set(true); exited.countDown() }
        worker.start(); assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertFalse(exited.await(30, TimeUnit.MILLISECONDS)); assertFalse(touched.get())
        owner.commit(); assertTrue(exited.await(1, TimeUnit.SECONDS)); worker.join(); assertTrue(touched.get())
    }
    @Test fun cancelledPreparedWorkerExitsWithoutTouchingResources() {
        val owner = RecordingPreparation(); val touched = AtomicBoolean(false)
        val worker = Thread { if (owner.awaitCommit()) touched.set(true) }
        worker.start(); owner.cancel(); worker.join(1000)
        assertFalse(worker.isAlive); assertFalse(touched.get())
    }
    @Test fun interruptedPreparedWorkerStillWaitsForOwnershipDecision() {
        val owner = RecordingPreparation(); val exited = CountDownLatch(1); val interrupted = AtomicBoolean()
        val worker = Thread { owner.awaitCommit(); interrupted.set(Thread.currentThread().isInterrupted); exited.countDown() }
        worker.start(); worker.interrupt(); assertFalse(exited.await(30, TimeUnit.MILLISECONDS))
        owner.cancel(); assertTrue(exited.await(1, TimeUnit.SECONDS)); worker.join(); assertTrue(interrupted.get())
    }
    @Test fun concurrentTimeoutAndCommitAlwaysAgreeWithTheDrainDecision() {
        repeat(200) {
            val owner = RecordingPreparation(); val go = CountDownLatch(1)
            val accepted = AtomicBoolean(); val cancelled = AtomicBoolean()
            val start = Thread { go.await(); accepted.set(owner.commit()) }
            val timeout = Thread { go.await(); cancelled.set(owner.cancel()) }
            start.start(); timeout.start(); go.countDown(); start.join(1000); timeout.join(1000)
            assertFalse(start.isAlive); assertFalse(timeout.isAlive)
            assertTrue(accepted.get() xor cancelled.get()); assertEquals(accepted.get(), owner.awaitCommit())
        }
    }
    @Test fun nativeCleanupAloneDoesNotRetirePendingCallerFailureDelivery() {
        val owner = RecordingPreparation(); owner.cancel()
        assertFalse(owner.ownerFinished()); assertTrue(owner.callerFinished())
    }
    @Test fun callerTimeoutAloneDoesNotRetireStillRunningNativeOwner() {
        val owner = RecordingPreparation(); owner.cancel()
        assertFalse(owner.callerFinished()); assertTrue(owner.ownerFinished())
    }
    @Test fun concurrentOwnerAndCallerRetirementAlwaysReleaseAdmissionOnce() {
        repeat(100) {
            val owner = RecordingPreparation(); owner.cancel()
            val gate = java.util.concurrent.atomic.AtomicReference(owner)
            val releases = java.util.concurrent.atomic.AtomicInteger(); val go = CountDownLatch(1)
            val caller = Thread { go.await(); if (owner.callerFinished() && gate.compareAndSet(owner, null)) releases.incrementAndGet() }
            val native = Thread { go.await(); if (owner.ownerFinished() && gate.compareAndSet(owner, null)) releases.incrementAndGet() }
            caller.start(); native.start(); go.countDown(); caller.join(1000); native.join(1000)
            assertFalse(caller.isAlive); assertFalse(native.isAlive); assertNull(gate.get()); assertEquals(1, releases.get())
        }
    }
}
