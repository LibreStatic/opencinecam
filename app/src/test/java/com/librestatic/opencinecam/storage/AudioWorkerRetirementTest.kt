/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AudioWorkerRetirementTest {
    private fun awaitIdle() {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (runCatching { AudioRetirementGate.requireIdle() }.isFailure && System.nanoTime() < limit) Thread.sleep(5)
        AudioRetirementGate.requireIdle()
    }
    @Test fun successfulStopJoinsWorkerBeforeRelease() {
        val stop = CountDownLatch(1); val exited = AtomicBoolean(false)
        val worker = Thread { stop.await(); exited.set(true) }.apply { start() }
        val result = retireAudioWorkers(listOf(worker), 1000, { stop.countDown() },
            { assertTrue(exited.get()); assertFalse(worker.isAlive) }, { fail("abandoned") })
        assertTrue(result.completed); assertNull(result.failure); awaitIdle()
    }
    @Test fun stalledWorkerRetainsResourcesAndBlocksAnotherOwnerUntilItExits() {
        val resume = CountDownLatch(1); val entered = CountDownLatch(1)
        val released = AtomicBoolean(false); val cleaned = CountDownLatch(1)
        val worker = Thread { entered.countDown(); resume.await() }.apply { start() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            val result = retireAudioWorkers(listOf(worker), 25, {}, { released.set(true) }, { cleaned.countDown() })
            assertFalse(result.completed); assertNotNull(result.failure); assertFalse(released.get())
            assertEquals(1, cleaned.count)
            assertThrows(IllegalStateException::class.java) { AudioRetirementGate.requireIdle() }
        } finally { resume.countDown() }
        assertTrue(cleaned.await(5, TimeUnit.SECONDS)); assertTrue(released.get()); awaitIdle()
    }
    @Test fun blockingNativeStopDoesNotBlockCallerOrReleaseTheFileEarly() {
        val resume = CountDownLatch(1); val cleaned = CountDownLatch(1); val released = AtomicBoolean(false)
        try {
            val start = System.nanoTime()
            val result = retireAudioWorkers(emptyList(), 25, { resume.await() }, { released.set(true) }, { cleaned.countDown() })
            assertFalse(result.completed); assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
            assertFalse(released.get()); assertEquals(1, cleaned.count)
        } finally { resume.countDown() }
        assertTrue(cleaned.await(5, TimeUnit.SECONDS)); awaitIdle()
    }
    @Test fun blockingNativeReleaseAlsoTransfersFileCleanup() {
        val resume = CountDownLatch(1); val cleaned = CountDownLatch(1)
        try {
            val result = retireAudioWorkers(emptyList(), 25, {}, { resume.await() }, { cleaned.countDown() })
            assertFalse(result.completed); assertEquals(1, cleaned.count)
        } finally { resume.countDown() }
        assertTrue(cleaned.await(5, TimeUnit.SECONDS)); awaitIdle()
    }
    @Test fun stopFailureStillReleasesAndPreservesLaterFailure() {
        val first = IllegalStateException("stop"); val second = IllegalStateException("release")
        val result = retireAudioWorkers(emptyList(), 1000, { throw first }, { throw second }, { fail("abandoned") })
        assertTrue(result.completed); assertSame(first, result.failure); assertArrayEquals(arrayOf(second), first.suppressed); awaitIdle()
    }
    @Test fun interruptedCallerTransfersOwnershipAndRetainsInterruptStatus() {
        val resume = CountDownLatch(1); val entered = CountDownLatch(1); val cleaned = CountDownLatch(1)
        val returned = AtomicReference<AudioRetirementResult?>(); val interrupted = AtomicBoolean(false)
        val caller = Thread {
            returned.set(retireAudioWorkers(emptyList(), 5000, { entered.countDown(); resume.await() }, {}, { cleaned.countDown() }))
            interrupted.set(Thread.currentThread().isInterrupted)
        }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS)); caller.interrupt(); caller.join(2000)
            assertFalse(caller.isAlive); assertFalse(requireNotNull(returned.get()).completed); assertTrue(interrupted.get())
            assertEquals(1, cleaned.count)
        } finally { resume.countDown() }
        assertTrue(cleaned.await(5, TimeUnit.SECONDS)); awaitIdle()
    }
    @Test fun abandonedCleanupFailureStillReleasesTheRetirementGate() {
        val resume = CountDownLatch(1); val cleaned = CountDownLatch(1)
        val failure = IllegalStateException("close"); val reported = AtomicReference<Throwable?>()
        try {
            assertFalse(retireAudioWorkers(emptyList(), 25, { resume.await() }, {}, { throw failure },
                { reported.set(it); cleaned.countDown() }).completed)
        } finally { resume.countDown() }
        assertTrue(cleaned.await(5, TimeUnit.SECONDS)); assertSame(failure, reported.get()); awaitIdle()
    }
    @Test fun stopDeadlineDoesNotExpireDuringOrdinaryRecording() {
        var now = 0L; val deadline = AudioStopDeadline(1) { now }
        now = 10_000_000; deadline.check()
    }
    @Test fun repeatedEosRetriesCannotRestartTheDeadline() {
        var now = 0L; val deadline = AudioStopDeadline(1) { now }
        deadline.begin(); now = 999999; deadline.begin(); deadline.check(); now++
        assertThrows(IllegalStateException::class.java) { deadline.check() }
    }
    @Test fun monotonicNanoTimeWrapKeepsTheSameBound() {
        var now = Long.MAX_VALUE - 500000; val deadline = AudioStopDeadline(1) { now }
        deadline.begin(); now += 1000000
        assertThrows(IllegalStateException::class.java) { deadline.check() }
    }
    @Test fun failedEffectReleaseStillAttemptsAudioAndCodecAndNeverReportsSuccess() {
        val failure = IllegalStateException("effect release")
        val calls = mutableListOf<String>()
        var retired = false
        val thrown = assertThrows(IllegalStateException::class.java) {
            releaseAudioResources(listOf(
                { calls += "effect"; throw failure },
                { calls += "audio" },
                { calls += "codec" },
            ))
            retired = true
        }
        assertSame(failure, thrown)
        assertEquals(listOf("effect", "audio", "codec"), calls)
        assertFalse(retired)
    }

    @Test fun nativeReleaseAggregatesDistinctFailuresAndAvoidsSelfSuppression() {
        val first = IllegalStateException("effect")
        val second = IllegalArgumentException("audio")
        val actual = assertThrows(IllegalStateException::class.java) {
            releaseAudioResources(listOf({ throw first }, { throw second }, { throw first }))
        }
        assertSame(first, actual)
        assertEquals(listOf(second), actual.suppressed.toList())
        releaseAudioResources(emptyList())
    }

}
