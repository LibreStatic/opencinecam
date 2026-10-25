/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ln

class ZoomLagCompensatorTest {
    /** Feeds (requested, result) pairs and returns the visible ratio of each frame. */
    private fun visible(compensator: ZoomLagCompensator, frames: List<Pair<Float, Float>>): List<Float> =
        frames.map { (requested, result) -> result * compensator.onResult(requested, result) }

    @Test fun aSteadyZoomNeedsNoCrop() {
        val c = ZoomLagCompensator()
        var ratio = 1f
        repeat(20) {
            ratio *= 1.05f
            assertEquals(1f, c.onResult(ratio, ratio), 0f)
        }
        repeat(8) { assertEquals(1f, c.onResult(ratio, ratio), 0f) }
        assertFalse(c.moving)
    }

    @Test fun zoomingInThroughAHoldKeepsMovingAndEndsUncropped() {
        // The Razr 1x -> 3x switch: 7.5 % a frame, the HAL holds 2.967 for three frames, then the tele lands on the request.
        val requested = listOf(2.425f, 2.610f, 2.761f, 2.967f, 3.190f, 3.430f, 3.442f, 3.724f, 4.008f)
        val result = listOf(2.425f, 2.610f, 2.761f, 2.967f, 2.967f, 2.967f, 3.442f, 3.724f, 4.008f)
        val c = ZoomLagCompensator()
        val shown = visible(c, requested.zip(result))
        for (i in shown.indices) assertEquals("frame $i", requested[i], shown[i], 1e-4f)
        for (i in 1 until shown.size) assertTrue("frame $i", shown[i] / shown[i - 1] < 1.09f)
        assertEquals(1f, c.onResult(4.2f, 4.2f), 0f)
    }

    @Test fun withoutCompensationTheSameSwitchJumps() {
        val result = listOf(2.967f, 2.967f, 2.967f, 3.442f)
        assertTrue(result.last() / result[result.size - 2] > 1.15f)
    }

    @Test fun zoomingOutTheNewLensGlidesFromThePreviousFraming() {
        // The 3x -> 1x switch: the tele holds its 3.015 minimum while the request falls, then the wide lands at 2.451.
        val rest = List(8) { 2.20f }
        val requested = listOf(3.30f, 3.10f, 2.95f, 2.80f, 2.65f, 2.451f, 2.35f, 2.25f) + rest
        val result = listOf(3.30f, 3.10f, 3.015f, 3.015f, 3.015f, 2.451f, 2.35f, 2.25f) + rest
        val c = ZoomLagCompensator()
        val shown = visible(c, requested.zip(result))
        for (i in shown.indices) assertTrue("frame $i never shows wider than the HAL", shown[i] >= result[i] - 1e-4f)
        // Each step is at most the request's own step times the catch-up, never the 0.81 jump.
        for (i in 1 until shown.size) {
            val allowed = minOf(1f, requested[i] / requested[i - 1]) / 1.04f
            assertTrue("frame $i stepped ${shown[i] / shown[i - 1]}", shown[i] / shown[i - 1] >= allowed - 1e-4f)
        }
        assertTrue(shown[5] / shown[4] > 0.86f)
        assertEquals(2.20f, shown.last(), 1e-4f)
        assertFalse(c.moving)
    }

    @Test fun theCropIsCapped() {
        val c = ZoomLagCompensator(maxCrop = 2f)
        c.onResult(0.743f, 0.743f)
        var crop = 1f
        for (requested in listOf(1.0f, 1.4f, 1.9f, 2.5f)) crop = c.onResult(requested, 0.743f)
        assertEquals(2f, crop, 1e-4f)
    }

    @Test fun missingValuesReset() {
        val c = ZoomLagCompensator()
        c.onResult(2f, 2f)
        c.onResult(2.3f, 2f)
        assertTrue(c.moving)
        assertEquals(1f, c.onResult(null, 2f), 0f)
        assertFalse(c.moving)
        assertEquals(1f, c.onResult(3f, 2f), 0f)
    }

    @Test fun theVisibleRatioFollowsTheRequestInLogSpace() {
        // A long hold at constant speed: the crop grows by the request's step each frame.
        val c = ZoomLagCompensator()
        c.onResult(1f, 1f)
        var requested = 1f
        repeat(4) {
            requested *= 1.06f
            assertEquals(ln(requested), ln(c.onResult(requested, 1f)), 1e-4f)
        }
    }
}

class ZoomCropTimelineTest {
    @Test fun findsTheExactFrame() {
        val t = ZoomCropTimeline()
        t.note(100L, 1.2f, moving = true)
        t.note(200L, 1.1f, moving = true)
        assertEquals(1.2f, t.cropFor(100L, 0L), 0f)
        assertTrue(t.lastExact)
    }

    @Test fun aMissWhileStillDrawsUncroppedAtOnce() {
        val t = ZoomCropTimeline()
        t.note(100L, 1f, moving = false)
        assertEquals(1f, t.cropFor(150L, 50_000_000L), 0f)
        assertFalse(t.lastExact)
        assertTrue(t.lastWaitNs < 20_000_000L)
    }

    @Test fun aLateResultIsAwaitedWhileZooming() {
        val t = ZoomCropTimeline()
        t.note(100L, 1.05f, moving = true)
        val writer = thread { Thread.sleep(3); t.note(200L, 1.15f, moving = true) }
        assertEquals(1.15f, t.cropFor(200L, 500_000_000L), 0f)
        assertTrue(t.lastExact)
        writer.join()
    }

    @Test fun aNewerResultEndsTheWait() {
        val t = ZoomCropTimeline()
        t.note(100L, 1.05f, moving = true)
        t.note(300L, 1.1f, moving = true)
        assertEquals("keeps the crop of the result before it", 1.05f, t.cropFor(200L, 500_000_000L), 0f)
        assertFalse(t.lastExact)
        assertTrue(abs(t.lastWaitNs) < 100_000_000L)
    }

    @Test fun clearForgetsEverything() {
        val t = ZoomCropTimeline()
        t.note(100L, 1.3f, moving = true)
        t.clear()
        assertEquals(1f, t.cropFor(100L, 0L), 0f)
    }
}

class ZoomLagSettleTest {
    @Test fun aRestingRequestTheHalRoundsAwayIsNotUpscaled() {
        // The operator asked 0.574; the HAL rests at 0.552. After the settle window the crop is gone.
        val c = ZoomLagCompensator(settleFrames = 6)
        c.onResult(0.552f, 0.552f)
        var crop = c.onResult(0.574f, 0.552f)
        assertTrue(crop > 1f)
        repeat(8) { crop = c.onResult(0.574f, 0.552f) }
        assertEquals(1f, crop, 0f)
        assertFalse(c.moving)
    }

    @Test fun aLateResultKeepsThePreviousCrop() {
        val t = ZoomCropTimeline()
        t.note(100L, 1.2f, moving = true)
        t.note(116L, 1.3f, moving = true)
        assertEquals(1.3f, t.cropFor(133L, 1_000_000L), 0f)
        assertFalse(t.lastExact)
    }
}
