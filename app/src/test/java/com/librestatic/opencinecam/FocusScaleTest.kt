/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusScaleTest {
    @Test fun theScaleCoversWhatTheLensReportsBeyondItsDeclaredLimit() {
        // The emulator declares 0.1 D and reports 2.0 D: the scale must reach 2.0 D, not stop at 0.1 D.
        assertEquals(2f, focusScaleSpan(0.1f, listOf(2f, null)), 0f)
        assertEquals(0.1f, focusScaleSpan(0.1f, emptyList()), 0f)
        assertEquals(10f, focusScaleSpan(10f, listOf(Float.NaN, 2f)), 0f)
        // A lens that declares 0 D never yields a zero span to divide by.
        assertEquals(0.01f, focusScaleSpan(0f, listOf(null)), 0f)
    }

    @Test fun sliderPositionMapsDioptersOntoTheScale() {
        assertEquals(1f, focusSliderPosition(2f, 2f), 0f)
        assertEquals(0.25f, focusSliderPosition(0.5f, 2f), 0f)
        assertEquals(1f, focusSliderPosition(3f, 2f), 0f)
        assertEquals(0f, focusSliderPosition(-1f, 2f), 0f)
        assertEquals(0f, focusSliderPosition(Float.NaN, 2f), 0f)
        assertEquals(0f, focusSliderPosition(1f, 0f), 0f)
    }

    @Test fun sliderRequestsNeverPassTheDeclaredNearLimit() {
        assertEquals(0.5f, focusFromSliderPosition(0.25f, 2f, 10f), 1e-6f)
        assertEquals(5f, focusFromSliderPosition(0.5f, 10f, 10f), 1e-6f)
        // The thumb at the near end asks for what the lens declares, which the engine accepts.
        assertEquals(0.1f, focusFromSliderPosition(1f, 2f, 0.1f), 0f)
        assertEquals(0f, focusFromSliderPosition(-0.5f, 2f, 0.1f), 0f)
    }

    @Test fun ticksUseRoundStepsFromInfinityToTheSpan() {
        assertEquals(listOf(0f, 0.5f, 1f, 1.5f, 2f), focusScaleTicks(2f, 5))
        assertEquals(listOf(0f, 1f, 2f), focusScaleTicks(2f, 3))
        assertEquals(listOf(0f, 0.05f, 0.1f), focusScaleTicks(0.1f, 3))
        // A tick closer than half a step to the end is dropped so its label never collides.
        assertEquals(listOf(0f, 0.2f, 0.4f, 0.7f), focusScaleTicks(0.7f, 4))
        assertEquals(listOf(0f, 2f), focusScaleTicks(2f, 1))
    }

    @Test fun tickCountFollowsTheWidth() {
        assertEquals(7, focusTickCount(411f))
        assertEquals(3, focusTickCount(200f))
        assertEquals(2, focusTickCount(100f))
        assertEquals(7, focusTickCount(1200f))
    }

    @Test fun readingsShowDioptersAndDistance() {
        assertEquals("2.0 D · 0.50 m", formatFocusReading(2f))
        assertEquals("0 D · ∞", formatFocusReading(0f))
        assertEquals("0.25 D", formatDiopters(0.25f))
        assertEquals("0.1 D", formatDiopters(0.1f))
        assertEquals("0.5 D", formatDiopters(0.5f))
        assertEquals("10.0 D", formatDiopters(10f))
        assertEquals("2.5 m", formatFocusMetres(0.4f))
        assertEquals("20 m", formatFocusMetres(0.05f))
        assertEquals("∞", formatFocusMetres(-1f))
    }

    @Test fun tickLabelsAreCompact() {
        assertEquals("0", formatTickDiopters(0f))
        assertEquals("0.5", formatTickDiopters(0.5f))
        assertEquals("2", formatTickDiopters(2f))
        assertEquals("2.5", formatTickDiopters(2.5f))
        assertEquals("0.05", formatTickDiopters(0.05f))
        assertEquals("0.1", formatTickDiopters(0.1f))
    }

    @Test fun readbackOnlyShowsWhenTheLensIsElsewhere() {
        assertFalse(focusReadbackDiffers(2f, 2f))
        assertFalse(focusReadbackDiffers(2f, 2.05f))
        assertFalse(focusReadbackDiffers(null, 2f))
        assertTrue(focusReadbackDiffers(0.1f, 2f))
        assertTrue(focusReadbackDiffers(1f, 1.2f))
    }

    @Test fun pullDurationReadsInSeconds() {
        assertEquals("2.0 s", formatPullSeconds(2_000L))
        assertEquals("0.5 s", formatPullSeconds(500L))
        assertEquals("9.5 s", formatPullSeconds(9_500L))
        assertEquals("10 s", formatPullSeconds(10_000L))
    }
}
