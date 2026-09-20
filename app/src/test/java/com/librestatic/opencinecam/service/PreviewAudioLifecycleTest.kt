/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PreviewAudioLifecycleTest {
    @Test fun closeBeforeStartRetiresExactlyOnceAndRejectsLaterStart() {
        val releases = AtomicInteger()
        val starts = AtomicInteger()
        val gate = LocalGate()
        val owner = lifecycle(gate, start = { starts.incrementAndGet() }, release = { releases.incrementAndGet() })
        owner.closeAsync().get(5, TimeUnit.SECONDS)
        owner.closeAsync().get(5, TimeUnit.SECONDS)
        assertEquals(0, starts.get())
        assertEquals(1, releases.get())
        assertTrue(gate.owners.isEmpty())
        try { owner.start(); fail("Closed owner must not start") } catch (_: IllegalStateException) { }
    }

    @Test fun heldNativeStartDoesNotBlockCallerAndClosingWaitsBeforeStopAndRelease() {
        val entered = CountDownLatch(1)
        val finishStart = CountDownLatch(1)
        val stops = AtomicInteger()
        val reads = AtomicInteger()
        val releases = AtomicInteger()
        val gate = LocalGate()
        val owner = lifecycle(gate,
            start = { entered.countDown(); await(finishStart) },
            read = { reads.incrementAndGet() }, stop = { stops.incrementAndGet() },
            release = { releases.incrementAndGet() })
        try {
            owner.start()
            await(entered)
            val receipt = owner.closeAsync()
            assertFalse(receipt.isDone)
            assertEquals(1, gate.owners.size)
            assertEquals(0, stops.get())
            assertEquals(0, releases.get())
            finishStart.countDown()
            receipt.get(5, TimeUnit.SECONDS)
            assertEquals(0, reads.get())
            assertEquals(1, stops.get())
            assertEquals(1, releases.get())
        } finally { finishStart.countDown(); owner.closeAsync().get(5, TimeUnit.SECONDS) }
    }

    @Test fun heldStopAndReadBothRetainResourcesDespiteCoordinatorInterruption() {
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val stopping = CountDownLatch(1)
        val finishStop = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val releases = AtomicInteger()
        val gate = LocalGate()
        val owner = lifecycle(gate,
            read = { reading.countDown(); await(finishRead) },
            stop = { stopping.countDown(); awaitIgnoringInterrupt(finishStop); stopped.countDown() },
            release = { releases.incrementAndGet() })
        try {
            owner.start(); await(reading)
            val receipt = owner.closeAsync(); await(stopping)
            gate.owners.single().interrupt()
            assertFalse(receipt.isDone)
            assertEquals(0, releases.get())
            finishStop.countDown(); await(stopped)
            assertFalse(receipt.isDone)
            assertEquals(0, releases.get())
            assertEquals(1, gate.owners.size)
            finishRead.countDown()
            receipt.get(5, TimeUnit.SECONDS)
            assertEquals(1, releases.get())
            assertTrue(gate.owners.isEmpty())
        } finally {
            finishStop.countDown(); finishRead.countDown()
            owner.closeAsync().get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun cancelledAndForgedReceiptNeverCompletesOrCancelsNativeRetirement() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val gate = LocalGate()
        val owner = lifecycle(gate, start = { entered.countDown(); await(finish) })
        try {
            owner.start(); await(entered)
            val cancelled = owner.closeAsync()
            val forged = owner.closeAsync()
            val real = owner.closeAsync()
            assertTrue(cancelled.cancel(true))
            assertTrue(forged.complete(Unit))
            assertFalse(real.isDone)
            assertEquals(1, gate.owners.size)
            finish.countDown()
            real.get(5, TimeUnit.SECONDS)
            assertTrue(cancelled.isCancelled)
            assertTrue(gate.owners.isEmpty())
        } finally { finish.countDown(); owner.closeAsync().get(5, TimeUnit.SECONDS) }
    }

    @Test fun nativeStartFailureStartsRetirementBeforeFailureCallbackAndReleasesExactlyOnce() {
        val problem = IllegalStateException("start rejected")
        val observed = AtomicReference<Throwable>()
        val callback = CountDownLatch(1)
        val releases = AtomicInteger()
        val reads = AtomicInteger()
        val gate = LocalGate()
        val owner = lifecycle(gate, start = { throw problem }, read = { reads.incrementAndGet() },
            release = { releases.incrementAndGet() }, failure = {
                observed.set(it)
                assertEquals(1, gate.owners.size) // Coordinator cannot release until this worker exits.
                callback.countDown()
            })
        owner.start(); await(callback)
        owner.closeAsync().get(5, TimeUnit.SECONDS)
        assertSame(problem, observed.get())
        assertEquals(0, reads.get())
        assertEquals(1, releases.get())
        assertTrue(gate.owners.isEmpty())
    }

    @Test fun nativeReadFailureAlsoRetiresBeforeReportingAndDoesNotBecomeReleaseFailure() {
        val callback = CountDownLatch(1)
        val problem = IllegalArgumentException("read failed")
        val gate = LocalGate()
        val observed = AtomicReference<Throwable>()
        val owner = lifecycle(gate, read = { throw problem }, failure = {
            observed.set(it); assertEquals(1, gate.owners.size); callback.countDown()
        })
        owner.start(); await(callback)
        owner.closeAsync().get(5, TimeUnit.SECONDS)
        assertSame(problem, observed.get())
        assertTrue(gate.owners.isEmpty())
    }

    @Test fun failedStopIsObservableButSuccessfulResourceReleaseFreesGate() {
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val problem = IllegalStateException("stop failed")
        val releases = AtomicInteger()
        val gate = LocalGate()
        val owner = lifecycle(gate, read = { reading.countDown(); await(finishRead) },
            stop = { finishRead.countDown(); throw problem }, release = { releases.incrementAndGet() })
        try {
            owner.start(); await(reading)
            assertSame(problem, failureOf(owner.closeAsync()))
            assertEquals(1, releases.get())
            assertTrue(gate.owners.isEmpty())
        } finally { finishRead.countDown() }
    }

    @Test fun releaseFailureRetainsOnlyLocalGateAndIsStableAcrossDefensiveViews() {
        val problem = IllegalStateException("release failed")
        val gate = LocalGate()
        val releases = AtomicInteger()
        val owner = lifecycle(gate, release = { releases.incrementAndGet(); throw problem })
        assertSame(problem, failureOf(owner.closeAsync()))
        assertSame(problem, failureOf(owner.closeAsync()))
        assertEquals(1, releases.get())
        assertEquals(1, gate.owners.size)
        // Tests intentionally never poison the production AudioRetirementGate singleton.
        gate.owners.clear()
    }

    @Test fun stopAndReleaseErrorsAreAggregatedWithoutCertifyingRetirement() {
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val stopFailure = IllegalStateException("stop")
        val releaseFailure = IllegalStateException("release")
        val gate = LocalGate()
        val owner = lifecycle(gate, read = { reading.countDown(); await(finishRead) },
            stop = { finishRead.countDown(); throw stopFailure }, release = { throw releaseFailure })
        try {
            owner.start(); await(reading)
            assertSame(stopFailure, failureOf(owner.closeAsync()))
            assertArrayEquals(arrayOf(releaseFailure), stopFailure.suppressed)
            assertEquals(1, gate.owners.size)
        } finally { finishRead.countDown(); gate.owners.clear() }
    }

    @Test fun readFailureAfterCloseRequestDoesNotPublishLateCallback() {
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val failures = AtomicInteger()
        val gate = LocalGate()
        val owner = lifecycle(gate,
            read = { reading.countDown(); await(finishRead); error("read interrupted by stop") },
            stop = { finishRead.countDown() }, failure = { failures.incrementAndGet() })
        try {
            owner.start(); await(reading)
            owner.closeAsync().get(5, TimeUnit.SECONDS)
            assertEquals(0, failures.get())
            assertTrue(gate.owners.isEmpty())
        } finally { finishRead.countDown() }
    }

    @Test fun failedPreparationExceptionExposesOnlyDefensiveRetirementViews() {
        val native = CompletableFuture<Unit>()
        val error = PreviewAudioMonitorCreationFailure(IllegalStateException("prepare"), native)
        val cancelled = error.retirement
        val forged = error.retirement
        assertTrue(cancelled.cancel(true))
        assertTrue(forged.complete(Unit))
        assertFalse(native.isDone)
        assertFalse(error.retirement.isDone)
        native.complete(Unit)
        error.retirement.get(5, TimeUnit.SECONDS)
        assertTrue(cancelled.isCancelled)
    }

    private class LocalGate {
        val owners: MutableSet<Thread> = ConcurrentHashMap.newKeySet()
    }
    private fun lifecycle(gate: LocalGate, start: () -> Unit = {}, read: () -> Unit = {},
        stop: () -> Unit = {}, release: () -> Unit = {}, failure: (Throwable) -> Unit = {}) =
        PreviewAudioLifecycle(start, read, stop, release, failure,
            retainRetirement = { check(gate.owners.add(it)) },
            releaseRetirement = { check(gate.owners.remove(it)) })

    private fun await(latch: CountDownLatch) { check(latch.await(5, TimeUnit.SECONDS)) { "Fixture latch timed out" } }
    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            try { check(latch.await((end - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)); return }
            catch (_: InterruptedException) { }
        }
    }
    private fun failureOf(future: CompletableFuture<Unit>): Throwable = try {
        future.get(5, TimeUnit.SECONDS); error("Expected retirement failure")
    } catch (failure: ExecutionException) { requireNotNull(failure.cause) }
}
