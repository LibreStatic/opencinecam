/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveWindowTest {

    @Test fun phonePortraitIsCompactWithBottomBarAndSheets() {
        val phone = AdaptiveWindow(411f, 914f)
        assertEquals(WindowWidthClass.COMPACT, phone.widthClass)
        assertEquals(WindowHeightClass.EXPANDED, phone.heightClass)
        assertFalse(phone.navigationRail)
        assertEquals(ContextPanePlacement.BOTTOM_SHEET, phone.contextPane)
        assertFalse(phone.persistentInspector)
    }

    @Test fun phoneLandscapeHasCompactHeightAndSideNavigation() {
        val phone = AdaptiveWindow(914f, 411f)
        assertEquals(WindowWidthClass.EXPANDED, phone.widthClass)
        assertEquals(WindowHeightClass.COMPACT, phone.heightClass)
        assertTrue(phone.navigationRail)
        assertEquals(ContextPanePlacement.SIDE, phone.contextPane)
        assertFalse(phone.persistentInspector)
    }

    @Test fun foldableInnerScreenOpensPanelsAtTheSide() {
        // Razr Fold inner screen, 2076 x 2152 px at 390 dpi.
        val fold = AdaptiveWindow(2076f / 2.4375f, 2152f / 2.4375f)
        assertEquals(WindowWidthClass.EXPANDED, fold.widthClass)
        assertTrue(fold.navigationRail)
        assertEquals(ContextPanePlacement.SIDE, fold.contextPane)
        assertFalse(fold.persistentInspector)
    }

    @Test fun tabletPortraitIsMediumWithARail() {
        val tablet = AdaptiveWindow(800f, 1280f)
        assertEquals(WindowWidthClass.MEDIUM, tablet.widthClass)
        assertTrue(tablet.navigationRail)
        assertEquals(ContextPanePlacement.SIDE, tablet.contextPane)
        assertFalse(tablet.persistentInspector)
    }

    @Test fun tabletLandscapeAndDesktopKeepTheInspectorOpen() {
        assertEquals(WindowWidthClass.LARGE, AdaptiveWindow(1280f, 800f).widthClass)
        assertTrue(AdaptiveWindow(1280f, 800f).persistentInspector)
        assertTrue(AdaptiveWindow(1920f, 1080f).persistentInspector)
    }

    @Test fun breakpointsAreInclusiveAtTheirLowerBound() {
        assertEquals(WindowWidthClass.COMPACT, windowWidthClass(599.9f))
        assertEquals(WindowWidthClass.MEDIUM, windowWidthClass(600f))
        assertEquals(WindowWidthClass.EXPANDED, windowWidthClass(840f))
        assertEquals(WindowWidthClass.LARGE, windowWidthClass(1200f))
        assertEquals(WindowHeightClass.COMPACT, windowHeightClass(479.9f))
        assertEquals(WindowHeightClass.MEDIUM, windowHeightClass(480f))
        assertEquals(WindowHeightClass.EXPANDED, windowHeightClass(900f))
    }

    @Test fun unmeasuredWindowFallsBackToCompact() {
        assertEquals(WindowWidthClass.COMPACT, windowWidthClass(Float.NaN))
        assertEquals(WindowHeightClass.COMPACT, windowHeightClass(0f))
    }
}
