/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.activity.BackEventCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class PredictiveBackTest {
    private val left = BackEventCompat.EDGE_LEFT
    private val right = BackEventCompat.EDGE_RIGHT

    @Test fun zeroProgressIsIdentity() {
        val m = predictiveBackMotion(0f, left, 1080f, 2.625f)
        assertEquals(1f, m.scale, 0f)
        assertEquals(0f, m.translationX, 0f)
        assertEquals(0f, m.cornerRadius, 0f)
    }

    @Test fun fullProgressFromLeftEdgeMovesRight() {
        val m = predictiveBackMotion(1f, left, 1080f, 2f)
        assertEquals(0.9f, m.scale, 1e-6f)
        assertEquals(1080f / 20f - 16f, m.translationX, 1e-4f)
        assertEquals(56f, m.cornerRadius, 1e-4f)
    }

    @Test fun rightEdgeMovesLeft() {
        assertEquals(-(1080f / 20f - 16f), predictiveBackMotion(1f, right, 1080f, 2f).translationX, 1e-4f)
    }

    @Test fun halfProgressInterpolatesLinearly() {
        val m = predictiveBackMotion(0.5f, left, 1000f, 1f)
        assertEquals(0.95f, m.scale, 1e-6f)
        assertEquals(21f, m.translationX, 1e-4f)
        assertEquals(14f, m.cornerRadius, 1e-4f)
    }

    @Test fun tinyWidthClampsShiftAtZero() {
        assertEquals(0f, predictiveBackMotion(1f, left, 100f, 3f).translationX, 0f)
        assertEquals(0f, predictiveBackMotion(1f, right, 100f, 3f).translationX, 0f)
    }

    @Test fun progressIsClamped() {
        assertEquals(predictiveBackMotion(1f, left, 1080f, 2f), predictiveBackMotion(3f, left, 1080f, 2f))
        assertEquals(predictiveBackMotion(0f, left, 1080f, 2f), predictiveBackMotion(-1f, left, 1080f, 2f))
    }
}
