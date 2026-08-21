/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPullAnimatorTest {

    @Test
    fun startActivatesAnimator() {
        val animator = FocusPullAnimator()
        assertFalse(animator.isActive)
        animator.start(FocusPullPlan(0f, 10f, 1000L, FocusPullEasing.LINEAR), 0L)
        assertTrue(animator.isActive)
    }

    @Test
    fun tickAtStartReturnsFromValue() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(0f, 10f, 1000L, FocusPullEasing.LINEAR), 1000L)
        val value = animator.tick(1000L)
        assertEquals(0f, value!!, 0.01f)
    }

    @Test
    fun tickAtHalfwayReturnsMidpoint() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(0f, 10f, 1000L, FocusPullEasing.LINEAR), 0L)
        val value = animator.tick(500L)
        assertEquals(5f, value!!, 0.01f)
    }

    @Test
    fun tickAtEndReturnsToValue() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(0f, 10f, 1000L, FocusPullEasing.LINEAR), 0L)
        val value = animator.tick(1000L)
        assertEquals(10f, value!!, 0.01f)
    }

    @Test
    fun tickAfterEndReturnsToValue() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(0f, 10f, 1000L, FocusPullEasing.LINEAR), 0L)
        val value = animator.tick(1500L)
        assertEquals(10f, value!!, 0.01f)
    }

    @Test
    fun isCompleteAfterDuration() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(0f, 10f, 1000L, FocusPullEasing.LINEAR), 0L)
        assertFalse(animator.isComplete(500L))
        assertTrue(animator.isComplete(1000L))
        assertTrue(animator.isComplete(1500L))
    }

    @Test
    fun completeClearsState() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(0f, 10f, 1000L, FocusPullEasing.LINEAR), 0L)
        animator.complete()
        assertFalse(animator.isActive)
        assertNull(animator.tick(500L))
    }

    @Test
    fun cancelStopsAnimator() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(0f, 10f, 1000L, FocusPullEasing.LINEAR), 0L)
        animator.cancel()
        assertFalse(animator.isActive)
        assertNull(animator.tick(500L))
    }

    @Test
    fun easeInProducesConcaveCurve() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(0f, 10f, 1000L, FocusPullEasing.EASE_IN), 0L)
        val atQuarter = animator.tick(250L)!!
        val atHalf = animator.tick(500L)!!
        assertEquals(0.625f, atQuarter, 0.01f)
        assertEquals(2.5f, atHalf, 0.01f)
    }

    @Test
    fun easeOutProducesConvexCurve() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(0f, 10f, 1000L, FocusPullEasing.EASE_OUT), 0L)
        val atQuarter = animator.tick(250L)!!
        val atHalf = animator.tick(500L)!!
        assertEquals(4.375f, atQuarter, 0.01f)
        assertEquals(7.5f, atHalf, 0.01f)
    }

    @Test
    fun easeInOutIsSymmetric() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(0f, 10f, 1000L, FocusPullEasing.EASE_IN_OUT), 0L)
        val atQuarter = animator.tick(250L)!!
        val atThreeQuarters = animator.tick(750L)!!
        assertEquals(10f, atQuarter + atThreeQuarters, 0.1f)
    }

    @Test
    fun linearIsMonotonic() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(2f, 8f, 1000L, FocusPullEasing.LINEAR), 0L)
        var prev = animator.tick(0L)!!
        for (t in 100L..1000L step 100L) {
            val current = animator.tick(t)!!
            assertTrue(current >= prev)
            prev = current
        }
        assertEquals(8f, prev, 0.01f)
    }

    @Test
    fun zeroDurationImmediatelyComplete() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(0f, 10f, 0L, FocusPullEasing.LINEAR), 0L)
        assertTrue(animator.isComplete(0L))
        assertEquals(10f, animator.tick(0L)!!, 0.01f)
    }

    @Test
    fun tickBeforeStartReturnsNull() {
        val animator = FocusPullAnimator()
        assertNull(animator.tick(0L))
    }

    @Test
    fun reverseDirectionWorks() {
        val animator = FocusPullAnimator()
        animator.start(FocusPullPlan(10f, 0f, 1000L, FocusPullEasing.LINEAR), 0L)
        assertEquals(10f, animator.tick(0L)!!, 0.01f)
        assertEquals(5f, animator.tick(500L)!!, 0.01f)
        assertEquals(0f, animator.tick(1000L)!!, 0.01f)
    }
}
