/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class FocusPullSessionTest {
    private val plan = FocusPullPlan(0f, 10f, 1000, FocusPullEasing.LINEAR)
    @Test fun transitionCompletesExactlyOnceAtItsTarget() {
        val session = FocusPullSession(); val token = session.start(plan, 100)
        assertEquals(FocusPullStep(5f, false), session.tick(token, 600))
        assertEquals(FocusPullStep(10f, true), session.tick(token, 1100))
        assertNull(session.tick(token, 1200)); assertFalse(session.isCurrent(token))
    }
    @Test fun queuedTicksCannotRunAfterCancellationOrRestart() {
        val session = FocusPullSession(); val old = session.start(plan, 0)
        assertTrue(session.cancel()); assertFalse(session.cancel())
        val next = session.start(plan.copy(toDiopters = 2f), 100)
        assertNull(session.tick(old, 600)); assertTrue(session.isCurrent(next))
        assertEquals(FocusPullStep(1f, false), session.tick(next, 600))
    }
    @Test fun replacingActiveTransitionRevokesItsToken() {
        val session = FocusPullSession(); val old = session.start(plan, 0)
        val next = session.start(plan.copy(fromDiopters = 5f, toDiopters = 7f), 500)
        assertNull(session.tick(old, 1000))
        assertEquals(FocusPullStep(6f, false), session.tick(next, 1000))
    }
    @Test fun invalidPlanNeverReplacesAValidTransition() {
        val session = FocusPullSession(); val token = session.start(plan, 0)
        for (invalid in listOf(plan.copy(fromDiopters = Float.NaN), plan.copy(toDiopters = Float.POSITIVE_INFINITY),
            plan.copy(fromDiopters = -1f), plan.copy(durationMs = 0))) {
            assertThrows(IllegalArgumentException::class.java) { session.start(invalid, 100) }
            assertTrue(session.isCurrent(token))
        }
    }
    @Test fun delayedExecutorTickCatchesUpWithoutQueuingIntermediatePositions() {
        val session = FocusPullSession(); val token = session.start(plan, 0)
        assertEquals(FocusPullStep(10f, true), session.tick(token, 5000))
        assertNull(session.tick(token, 5001))
    }
    @Test fun cameraCloseCancellationRevokesEvenACompletedTransitionToken() {
        val session = FocusPullSession(); val old = session.start(plan, 0)
        session.tick(old, 1000); assertFalse(session.cancel())
        val next = session.start(plan, 2000)
        assertNotEquals(old, next); assertNull(session.tick(old, 2500))
        assertEquals(FocusPullStep(5f, false), session.tick(next, 2500))
    }
}
