/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class AudioRetirementIdleTest {
    @Test fun idleReceiptIsImmediatelyCompleteAndIndependentForEachCaller() {
        AudioRetirementGate.requireIdle()
        val first = AudioRetirementGate.whenIdle()
        val second = AudioRetirementGate.whenIdle()
        assertNotSame(first, second)
        assertEquals(Unit, first.get(1, TimeUnit.SECONDS))
        assertEquals(Unit, second.get(1, TimeUnit.SECONDS))
    }

    @Test fun oneOwnerRemainsHeldUntilExplicitReleaseEvenIfItsThreadNeverStarted() {
        val owner = Thread()
        AudioRetirementGate.retain(owner)
        try {
            val receipt = AudioRetirementGate.whenIdle()
            assertFalse(owner.isAlive)
            assertFalse(receipt.isDone)
            assertThrows(IllegalStateException::class.java) { AudioRetirementGate.requireIdle() }
            AudioRetirementGate.release(owner)
            assertEquals(Unit, receipt.get(1, TimeUnit.SECONDS))
            AudioRetirementGate.requireIdle()
        } finally { AudioRetirementGate.release(owner) }
    }

    @Test fun twoOwnersAndDuplicateOrUnknownReleaseCannotAdvanceIdleEarly() {
        val first = Thread(); val second = Thread()
        AudioRetirementGate.retain(first); AudioRetirementGate.retain(second)
        try {
            val receipt = AudioRetirementGate.whenIdle()
            AudioRetirementGate.retain(first) // Existing set ownership remains idempotent.
            AudioRetirementGate.release(Thread())
            AudioRetirementGate.release(first)
            AudioRetirementGate.release(first)
            assertFalse(receipt.isDone)
            assertThrows(IllegalStateException::class.java) { AudioRetirementGate.requireIdle() }
            AudioRetirementGate.release(second)
            assertEquals(Unit, receipt.get(1, TimeUnit.SECONDS))
        } finally { AudioRetirementGate.release(first); AudioRetirementGate.release(second) }
    }

    @Test fun callerCompletionCancellationAndExceptionalCompletionNeverReleaseTheOwner() {
        val owner = Thread()
        AudioRetirementGate.retain(owner)
        try {
            val completedByCaller = AudioRetirementGate.whenIdle()
            val cancelledByCaller = AudioRetirementGate.whenIdle()
            val failedByCaller = AudioRetirementGate.whenIdle()
            val protectedObserver = AudioRetirementGate.whenIdle()
            assertTrue(completedByCaller.complete(Unit))
            assertTrue(cancelledByCaller.cancel(false))
            assertTrue(failedByCaller.completeExceptionally(IllegalStateException("caller only")))
            assertFalse(protectedObserver.isDone)
            assertFalse(AudioRetirementGate.whenIdle().isDone)
            assertThrows(IllegalStateException::class.java) { AudioRetirementGate.requireIdle() }
            AudioRetirementGate.release(owner)
            assertEquals(Unit, protectedObserver.get(1, TimeUnit.SECONDS))
            assertTrue(cancelledByCaller.isCancelled)
            assertTrue(failedByCaller.isCompletedExceptionally)
        } finally { AudioRetirementGate.release(owner) }
    }

    @Test fun completionRunsOutsideGateLockAndCanReenterThroughAnotherThread() {
        val first = Thread(); val next = Thread()
        val executor = Executors.newSingleThreadExecutor()
        AudioRetirementGate.retain(first)
        try {
            val callback = AudioRetirementGate.whenIdle().thenRun {
                assertFalse("Receipt callbacks must not hold the retirement gate", Thread.holdsLock(AudioRetirementGate))
                executor.submit {
                    AudioRetirementGate.requireIdle()
                    AudioRetirementGate.retain(next)
                    assertFalse(AudioRetirementGate.whenIdle().isDone)
                }.get(2, TimeUnit.SECONDS)
            }
            AudioRetirementGate.release(first)
            callback.get(3, TimeUnit.SECONDS)
            val nextReceipt = AudioRetirementGate.whenIdle()
            assertFalse(nextReceipt.isDone)
            AudioRetirementGate.release(next)
            assertEquals(Unit, nextReceipt.get(1, TimeUnit.SECONDS))
        } finally {
            AudioRetirementGate.release(first); AudioRetirementGate.release(next)
            executor.shutdown(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun previousIdleReceiptDoesNotCompleteTheNextRetirementEpoch() {
        val owner = Thread()
        AudioRetirementGate.retain(owner)
        try {
            val previous = AudioRetirementGate.whenIdle()
            AudioRetirementGate.release(owner)
            assertTrue(previous.isDone)
            AudioRetirementGate.retain(owner)
            val next = AudioRetirementGate.whenIdle()
            assertFalse(next.isDone)
            previous.complete(Unit)
            assertFalse(next.isDone)
            AudioRetirementGate.release(owner)
            assertEquals(Unit, next.get(1, TimeUnit.SECONDS))
        } finally { AudioRetirementGate.release(owner) }
    }

    @Test fun abandonedCleanupMustActuallyReturnBeforeTheGateReceiptRetires() {
        val stopEntered = CountDownLatch(1); val releaseStop = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1); val releaseCleanup = CountDownLatch(1)
        val nativeReleased = AtomicBoolean(); val cleanupReturned = AtomicBoolean()
        try {
            val result = retireAudioWorkers(emptyList(), timeoutMs = 1,
                stop = { stopEntered.countDown(); check(releaseStop.await(5, TimeUnit.SECONDS)) },
                release = { nativeReleased.set(true) },
                abandonedCleanup = {
                    cleanupEntered.countDown()
                    check(releaseCleanup.await(5, TimeUnit.SECONDS))
                    cleanupReturned.set(true)
                })
            assertFalse(result.completed)
            assertNotNull(result.failure)
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
            val receipt = AudioRetirementGate.whenIdle()
            assertFalse(receipt.isDone)
            releaseStop.countDown()
            assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
            assertTrue(nativeReleased.get())
            assertFalse(cleanupReturned.get())
            assertFalse("Native release alone does not retire deferred file cleanup", receipt.isDone)
            releaseCleanup.countDown()
            receipt.get(2, TimeUnit.SECONDS)
            assertTrue(cleanupReturned.get())
            AudioRetirementGate.requireIdle()
        } finally {
            releaseStop.countDown(); releaseCleanup.countDown()
            AudioRetirementGate.whenIdle().get(5, TimeUnit.SECONDS)
        }
    }
}
