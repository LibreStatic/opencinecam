/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {
    private val phonePortrait = AdaptiveWindow(411f, 914f)
    private val phoneLandscape = AdaptiveWindow(914f, 411f)
    private val foldInner = AdaptiveWindow(851f, 883f)
    private val foldHalf = AdaptiveWindow(417f, 883f)
    private val tabletPortrait = AdaptiveWindow(800f, 1280f)
    private val desktop = AdaptiveWindow(1600f, 960f, hardwareKeyboard = true)

    @Test fun captureNeverShowsShellNavigation() {
        listOf(phonePortrait, phoneLandscape, foldInner, tabletPortrait, desktop).forEach {
            assertEquals(ShellNavigation.NONE, shellNavigation(it, AppSection.CAPTURE, imeVisible = false))
        }
    }

    @Test fun phonePortraitKeepsTheBottomBar() {
        assertEquals(ShellNavigation.BOTTOM_BAR, shellNavigation(phonePortrait, AppSection.MEDIA, imeVisible = false))
        assertEquals(ShellNavigation.BOTTOM_BAR, shellNavigation(phonePortrait, AppSection.SETTINGS, imeVisible = false))
    }

    @Test fun theBottomBarGivesWayToTheKeyboard() {
        assertEquals(ShellNavigation.NONE, shellNavigation(phonePortrait, AppSection.SETTINGS, imeVisible = true))
    }

    @Test fun everyWiderWindowGetsTheRail() {
        listOf(phoneLandscape, foldInner, tabletPortrait, desktop).forEach {
            assertEquals(ShellNavigation.RAIL, shellNavigation(it, AppSection.MEDIA, imeVisible = false))
        }
    }

    @Test fun theRailStaysWhileTyping() {
        assertEquals(ShellNavigation.RAIL, shellNavigation(phoneLandscape, AppSection.SETTINGS, imeVisible = true))
        assertEquals(ShellNavigation.RAIL, shellNavigation(desktop, AppSection.SETTINGS, imeVisible = true))
    }

    @Test fun aHingeHalfIsClassifiedOnItsOwn() {
        assertEquals(ShellNavigation.BOTTOM_BAR, shellNavigation(foldHalf, AppSection.SETTINGS, imeVisible = false))
    }
}
