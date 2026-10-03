/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Width classes shared by every screen, so capture, Media, Settings and playback change layout at
 * the same breakpoints instead of each choosing its own cut-off.
 *
 * - [COMPACT], below 600 dp: a phone in portrait, a foldable's cover screen.
 * - [MEDIUM], 600 to 840 dp: a tablet in portrait.
 * - [EXPANDED], 840 to 1200 dp: a foldable's inner screen, a phone in landscape, a small window.
 * - [LARGE], from 1200 dp: a tablet in landscape, a desktop window or external monitor.
 */
internal enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED, LARGE }

/**
 * Height classes. [COMPACT], below 480 dp, is a phone in landscape: chrome there has to come out
 * of the width, never the height. [EXPANDED] starts at 900 dp.
 */
internal enum class WindowHeightClass { COMPACT, MEDIUM, EXPANDED }

/** Where a contextual panel (white balance, focus, scopes, a take's details) opens. */
internal enum class ContextPanePlacement {
    /** A sheet docked over the bottom edge, leaving the top of the content in view. */
    BOTTOM_SHEET,

    /** A column at the end of the window; the primary content gives up that width. */
    SIDE,
}

internal const val WINDOW_MEDIUM_MIN_WIDTH_DP = 600f
internal const val WINDOW_EXPANDED_MIN_WIDTH_DP = 840f
internal const val WINDOW_LARGE_MIN_WIDTH_DP = 1200f
internal const val WINDOW_MEDIUM_MIN_HEIGHT_DP = 480f
internal const val WINDOW_EXPANDED_MIN_HEIGHT_DP = 900f

/**
 * The app window as the layouts see it, measured in dp before any inset is taken out.
 * [hardwareKeyboard] is true when a physical keyboard is attached, which is when screens show
 * their keyboard shortcuts in tooltips and labels.
 */
internal data class AdaptiveWindow(
    val widthDp: Float,
    val heightDp: Float,
    val hardwareKeyboard: Boolean = false,
) {
    val widthClass: WindowWidthClass = windowWidthClass(widthDp)
    val heightClass: WindowHeightClass = windowHeightClass(heightDp)
    val landscape: Boolean get() = widthDp > heightDp

    /**
     * Side navigation instead of a bottom bar. A compact portrait window keeps the bottom bar;
     * every other window has width to spare and no height to give away.
     */
    val navigationRail: Boolean get() = !(widthClass == WindowWidthClass.COMPACT && !landscape)

    /** A compact portrait window opens contextual panels as bottom sheets; the rest at the side. */
    val contextPane: ContextPanePlacement
        get() = if (widthClass == WindowWidthClass.COMPACT && !landscape) ContextPanePlacement.BOTTOM_SHEET else ContextPanePlacement.SIDE

    /**
     * Wide and tall enough to keep a side inspector open all the time instead of only while a
     * panel is in use: a tablet in landscape or a desktop window.
     */
    val persistentInspector: Boolean get() = widthClass == WindowWidthClass.LARGE && heightClass != WindowHeightClass.COMPACT
}

internal fun windowWidthClass(widthDp: Float): WindowWidthClass = when {
    !(widthDp >= WINDOW_MEDIUM_MIN_WIDTH_DP) -> WindowWidthClass.COMPACT
    widthDp < WINDOW_EXPANDED_MIN_WIDTH_DP -> WindowWidthClass.MEDIUM
    widthDp < WINDOW_LARGE_MIN_WIDTH_DP -> WindowWidthClass.EXPANDED
    else -> WindowWidthClass.LARGE
}

internal fun windowHeightClass(heightDp: Float): WindowHeightClass = when {
    !(heightDp >= WINDOW_MEDIUM_MIN_HEIGHT_DP) -> WindowHeightClass.COMPACT
    heightDp < WINDOW_EXPANDED_MIN_HEIGHT_DP -> WindowHeightClass.MEDIUM
    else -> WindowHeightClass.EXPANDED
}

/** A phone-sized default, so a screen composed outside [ProvideAdaptiveWindow] keeps its compact layout. */
internal val LocalAdaptiveWindow = staticCompositionLocalOf { AdaptiveWindow(widthDp = 411f, heightDp = 914f) }

/** Measures the whole window once at the root and provides it to every screen below. */
@Composable
internal fun ProvideAdaptiveWindow(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val keyboard = configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
        configuration.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val window = remember(maxWidth, maxHeight, keyboard) { AdaptiveWindow(maxWidth.value, maxHeight.value, keyboard) }
        CompositionLocalProvider(LocalAdaptiveWindow provides window) { content() }
    }
}
