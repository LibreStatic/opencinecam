/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class TimelapsePauseClockTest {
    @Test fun multiplePausesExcludeOnlyAcknowledgedIntervalsAndSealBeforeFinalization() {
        var now = 100L
        val clock = TimelapsePauseClock(7) { now }
        now = 300; assertTrue(clock.setPaused(true, 3))
        now = 1300; assertEquals(200L, clock.snapshot().activeElapsedAt(9999))
        assertTrue(clock.setPaused(false, 3))
        now = 1500; assertTrue(clock.setPaused(true, 5))
        now = 2500; assertTrue(clock.setPaused(false, 5))
        now = 2700; val stopped = clock.finish()
        assertEquals(600L, stopped.activeElapsedMs); assertEquals(2000L, stopped.pausedElapsedMs)
        assertEquals(listOf(200L,1200L,1400L,2400L), stopped.events.map { it.elapsedMs })
        assertEquals(listOf(3L,3L,5L,5L), stopped.events.map { it.frameIndex })
        now = 9900; assertEquals(stopped, clock.finish()); assertEquals(600L, stopped.activeElapsedAt(now))
        assertFalse(clock.setPaused(true, 6))
    }
    @Test fun stopWhilePausedIncludesItsOpenIntervalWithoutInventingResume() {
        var now = 100L; val clock = TimelapsePauseClock(8) { now }
        now = 200; clock.setPaused(true, 1); now = 5200
        val status = clock.finish()
        assertTrue(status.finished); assertTrue(status.paused)
        assertEquals(100L, status.activeElapsedMs); assertEquals(5000L, status.pausedElapsedMs)
        assertEquals(1, status.events.size)
    }
    @Test fun duplicateRequestsDoNotResetPauseOriginsOrCreateEvents() {
        var now = 0L; val clock = TimelapsePauseClock(1) { now }
        assertFalse(clock.setPaused(false, 0)); now = 10; assertTrue(clock.setPaused(true, 0))
        now = 200; assertFalse(clock.setPaused(true, 0)); now = 300
        assertEquals(290L, clock.snapshot().pausedElapsedMs); assertEquals(1, clock.snapshot().events.size)
    }
    @Test fun snapshotsAreImmutableAndActiveExtrapolationRejectsBackwardTime() {
        var now = 0L; val clock = TimelapsePauseClock(1) { now }
        now = 50; val first = clock.snapshot()
        assertEquals(80L, first.activeElapsedAt(80)); assertEquals(50L, first.activeElapsedAt(20))
        now = 40; clock.setPaused(true, 0)
        assertEquals(50L, clock.snapshot().activeElapsedMs); assertTrue(first.events.isEmpty())
    }
    @Test fun independentTakesHaveIndependentEpochs() {
        var now = 100L; val first = TimelapsePauseClock(1) { now }; first.setPaused(true, 0)
        now = 900L; val next = TimelapsePauseClock(2) { now }
        assertFalse(next.snapshot().paused); assertEquals(0L,next.snapshot().activeElapsedMs)
        assertEquals(2L,next.snapshot().takeId)
    }
}
