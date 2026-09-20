/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CaptureOwnerAdmissionTest {
    @Test fun firstOwnerIsImmediatelyReadyButSecondWaitsForActualRetirement() {
        val queue = CaptureOwnerAdmission(); val first = queue.acquire(); val second = queue.acquire()
        assertEquals(Unit, first.ready().get(2, TimeUnit.SECONDS)); assertFalse(second.ready().isDone)
        val native = CompletableFuture<Unit>(); val released = first.releaseAfter(native)
        assertFalse(released.isDone); assertFalse(second.ready().isDone)
        native.complete(Unit)
        assertEquals(Unit, released.get(2, TimeUnit.SECONDS)); assertEquals(Unit, second.ready().get(2, TimeUnit.SECONDS))
    }

    @Test fun closingWaitingOwnerDoesNotLetThirdOvertakeLiveFirstOwner() {
        val queue = CaptureOwnerAdmission(); val first = queue.acquire(); val second = queue.acquire()
        val third = queue.acquire(); val firstNative = CompletableFuture<Unit>()
        first.releaseAfter(firstNative)
        val secondClosed = second.releaseAfter(CompletableFuture.completedFuture(Unit))
        assertFalse(secondClosed.isDone); assertFalse(third.ready().isDone)
        firstNative.complete(Unit)
        assertEquals(Unit, secondClosed.get(2, TimeUnit.SECONDS)); assertEquals(Unit, third.ready().get(2, TimeUnit.SECONDS))
    }

    @Test fun completedPredecessorDoesNotReplaceWaitingOwnersOwnRetirement() {
        val queue = CaptureOwnerAdmission(); val first = queue.acquire(); val second = queue.acquire()
        val third = queue.acquire(); val secondNative = CompletableFuture<Unit>()
        second.releaseAfter(secondNative)
        first.releaseAfter(CompletableFuture.completedFuture(Unit))
        assertTrue(second.ready().isDone); assertFalse(third.ready().isDone)
        secondNative.complete(Unit); assertEquals(Unit, third.ready().get(2, TimeUnit.SECONDS))
    }

    @Test fun forgedAndCancelledReadinessViewsDoNotReleaseResources() {
        val queue = CaptureOwnerAdmission(); val first = queue.acquire(); val second = queue.acquire()
        assertTrue(second.ready().complete(Unit)); assertTrue(second.ready().cancel(true))
        assertTrue(second.ready().completeExceptionally(IllegalStateException("observer")))
        assertFalse(second.ready().isDone)
        first.releaseAfter(CompletableFuture.completedFuture(Unit))
        assertEquals(Unit, second.ready().get(2, TimeUnit.SECONDS))
    }

    @Test fun forgedAndCancelledReleaseViewsDoNotAdmitSuccessor() {
        val queue = CaptureOwnerAdmission(); val first = queue.acquire(); val second = queue.acquire()
        val native = CompletableFuture<Unit>(); val observer = first.releaseAfter(native)
        assertTrue(observer.complete(Unit)); assertFalse(second.ready().isDone)
        assertTrue(first.releaseAfter(native).cancel(true)); assertFalse(second.ready().isDone)
        native.complete(Unit); assertEquals(Unit, second.ready().get(2, TimeUnit.SECONDS))
    }

    @Test fun duplicateReleaseCannotSubstituteAnAlreadyCompletedRetirement() {
        val queue = CaptureOwnerAdmission(); val first = queue.acquire(); val second = queue.acquire()
        val native = CompletableFuture<Unit>(); first.releaseAfter(native)
        val duplicate = first.releaseAfter(CompletableFuture.completedFuture(Unit))
        assertFalse(duplicate.isDone); assertFalse(second.ready().isDone)
        native.complete(Unit); assertEquals(Unit, duplicate.get(2, TimeUnit.SECONDS)); assertTrue(second.ready().isDone)
    }

    @Test fun failedNativeRetirementIsNotPermissionForAnotherOwner() {
        val queue = CaptureOwnerAdmission(); val first = queue.acquire(); val second = queue.acquire()
        val native = CompletableFuture<Unit>(); first.releaseAfter(native)
        val failure = IllegalStateException("EGL window still owned")
        native.completeExceptionally(failure)
        assertSame(failure, failureCause(second.ready()))
        second.releaseAfter(CompletableFuture.completedFuture(Unit))
        assertSame(failure, failureCause(queue.acquire().ready()))
    }

    @Test fun cancelledNativeRetirementDoesNotProveNativeExit() {
        val queue = CaptureOwnerAdmission(); val first = queue.acquire(); val second = queue.acquire()
        val native = CompletableFuture<Unit>(); first.releaseAfter(native); native.cancel(true)
        assertTrue(second.ready().isCompletedExceptionally)
        assertTrue(failureCause(second.ready()) is java.util.concurrent.CancellationException)
    }

    @Test fun independentQueuesDoNotWaitForEachOther() {
        val firstQueue = CaptureOwnerAdmission(); firstQueue.acquire()
        val firstWaiting = firstQueue.acquire()
        assertEquals(Unit, CaptureOwnerAdmission().acquire().ready().get(2, TimeUnit.SECONDS))
        assertFalse(firstWaiting.ready().isDone)
    }

    @Test fun readyCallbackMayAcquireTheFollowingOwnerWithoutDeadlockOrOvertaking() {
        val queue = CaptureOwnerAdmission(); val first = queue.acquire(); val second = queue.acquire()
        var third: CaptureOwnerAdmission.Lease? = null
        second.ready().thenRun { third = queue.acquire() }
        first.releaseAfter(CompletableFuture.completedFuture(Unit))
        assertNotNull(third); assertFalse(requireNotNull(third).ready().isDone)
        second.releaseAfter(CompletableFuture.completedFuture(Unit))
        assertEquals(Unit, requireNotNull(third).ready().get(2, TimeUnit.SECONDS))
    }

    @Test fun concurrentClosedWaitersPreserveTheOutstandingOwnerFence() {
        val queue = CaptureOwnerAdmission(); val first = queue.acquire()
        val native = CompletableFuture<Unit>(); first.releaseAfter(native)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val results = (1..64).map {
                pool.submit<CompletableFuture<Unit>> {
                    queue.acquire().releaseAfter(CompletableFuture.completedFuture(Unit))
                }
            }.map { it.get(2, TimeUnit.SECONDS) }
            val last = queue.acquire()
            assertTrue(results.all { !it.isDone }); assertFalse(last.ready().isDone)
            native.complete(Unit)
            results.forEach { assertEquals(Unit, it.get(2, TimeUnit.SECONDS)) }
            assertEquals(Unit, last.ready().get(2, TimeUnit.SECONDS))
        } finally { native.complete(Unit); pool.shutdownNow() }
    }

    @Test fun concurrentDuplicateReleaseCannotReplaceTheFirstNativeSignal() {
        val queue = CaptureOwnerAdmission(); val first = queue.acquire(); val next = queue.acquire()
        val native = CompletableFuture<Unit>(); first.releaseAfter(native)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val results = (1..32).map {
                pool.submit<CompletableFuture<Unit>> {
                    first.releaseAfter(CompletableFuture.completedFuture(Unit))
                }
            }.map { it.get(2, TimeUnit.SECONDS) }
            assertTrue(results.all { !it.isDone }); assertFalse(next.ready().isDone)
            native.complete(Unit)
            results.forEach { assertEquals(Unit, it.get(2, TimeUnit.SECONDS)) }
            assertEquals(Unit, next.ready().get(2, TimeUnit.SECONDS))
        } finally { native.complete(Unit); pool.shutdownNow() }
    }

    private fun failureCause(future: CompletableFuture<Unit>): Throwable {
        var failure: Throwable = assertThrows(ExecutionException::class.java) { future.get(2, TimeUnit.SECONDS) }.cause!!
        while (failure is CompletionException && failure.cause != null) failure = failure.cause!!
        return failure
    }
}
