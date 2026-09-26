/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DispatchedTakeAdmissionTest {
    @Test fun rejectedStartReleasesItsExactReservationOnceAndALaterStopDoesNotReleaseAgain() {
        val admission = DispatchedTakeAdmission<String>()
        val dispatch = admission.dispatch("take-1")
        assertSame(dispatch, admission.pendingStart())
        assertEquals("take-1", admission.claimFailedStart(dispatch))
        assertNull(admission.pendingStart())
        assertNull("A repeated failure receipt must not release twice", admission.claimFailedStart(dispatch))
        assertNull("A stop after a released failed start must not release a newer admission", admission.claimStopped())
    }

    @Test fun startedTakeIsReleasedOnlyByItsStopCallback() {
        val admission = DispatchedTakeAdmission<String>()
        val dispatch = admission.dispatch("take-1")
        admission.markStarted()
        assertNull("RECORDING failures stay owned by onRecordingStopped", admission.pendingStart())
        assertNull(admission.claimFailedStart(dispatch))
        assertEquals("take-1", admission.claimStopped())
        assertNull(admission.claimStopped())
    }

    @Test fun preparationStillRetiringKeepsTheAdmissionForTheStopCallback() {
        // An engine timeout returns false while native preparation still owns the output:
        // no failure receipt succeeds, so only the later stop (or engine closure) releases it.
        val admission = DispatchedTakeAdmission<String>()
        admission.dispatch("take-1")
        assertNotNull(admission.pendingStart())
        assertEquals("take-1", admission.claimStopped())
        assertNull(admission.pendingStart())
    }

    @Test fun laterRetirementObservationStillReleasesAStartWhoseFirstReceiptFailed() {
        // A failed receipt claims nothing; a later preview restart or recovery re-observes the
        // same never-started dispatch and releases it exactly once.
        val admission = DispatchedTakeAdmission<String>()
        val dispatch = admission.dispatch("take-1")
        assertSame(dispatch, admission.pendingStart())
        assertSame(dispatch, admission.pendingStart())
        assertEquals("take-1", admission.claimFailedStart(requireNotNull(admission.pendingStart())))
        assertNull(admission.claimFailedStart(dispatch))
    }

    @Test fun staleFailureReceiptNeverReleasesTheNextTakesAdmission() {
        val admission = DispatchedTakeAdmission<String>()
        val first = admission.dispatch("take-1")
        val second = admission.dispatch("take-2")
        assertNull("A superseded dispatch is terminal", admission.claimFailedStart(first))
        assertSame(second, admission.pendingStart())
        assertEquals("take-2", admission.claimFailedStart(second))
    }

    @Test fun racingFailureAndStopReleaseExactlyOnce() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(500) {
                val admission = DispatchedTakeAdmission<Any>()
                val dispatch = admission.dispatch(Any())
                val releases = AtomicInteger()
                val go = CountDownLatch(1)
                val done = CountDownLatch(2)
                pool.execute { go.await(); if (admission.claimFailedStart(dispatch) != null) releases.incrementAndGet(); done.countDown() }
                pool.execute { go.await(); if (admission.claimStopped() != null) releases.incrementAndGet(); done.countDown() }
                go.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertEquals(1, releases.get())
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
