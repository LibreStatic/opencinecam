/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingLayoutTest {
    private fun layout(width: Float, height: Float) = onboardingLayout(AdaptiveWindow(width, height))

    @Test fun phoneLandscapePutsTheButtonsBesideTheStep() {
        assertEquals(OnboardingLayout.TWO_PANE, layout(914f, 411f))
        assertEquals(OnboardingLayout.TWO_PANE, layout(640f, 360f))
    }

    @Test fun phonePortraitStacks() {
        assertEquals(OnboardingLayout.SINGLE_COLUMN, layout(411f, 914f))
    }

    @Test fun foldInnerScreenSplitsInEitherPosture() {
        assertEquals(OnboardingLayout.TWO_PANE, layout(851f, 883f))
        assertEquals(OnboardingLayout.TWO_PANE, layout(861f, 830f))
    }

    @Test fun tabletPortraitStacksAndLandscapeSplits() {
        assertEquals(OnboardingLayout.SINGLE_COLUMN, layout(800f, 1280f))
        assertEquals(OnboardingLayout.SINGLE_COLUMN, layout(673f, 841f))
        assertEquals(OnboardingLayout.TWO_PANE, layout(1280f, 800f))
    }

    @Test fun desktopSplits() {
        assertEquals(OnboardingLayout.TWO_PANE, layout(1600f, 960f))
    }

    @Test fun aWindowTooNarrowForTwoPanesStacksEvenWhenShort() {
        assertEquals(OnboardingLayout.SINGLE_COLUMN, layout(380f, 360f))
    }

    @Test fun cardsPairUpOnceThePaneLeavesCompactWidth() {
        assertEquals(1, onboardingCardColumns(484f))
        assertEquals(1, onboardingCardColumns(599f))
        assertEquals(2, onboardingCardColumns(600f))
        assertEquals(2, onboardingCardColumns(603f))
    }
}
