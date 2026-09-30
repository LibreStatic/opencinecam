/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.ui.viewfinder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Panel opacity of capture chrome floating over a full-screen viewfinder, or null when the chrome
 * sits beside the viewfinder and keeps its own panel colours.
 */
val LocalChromeOpacity = staticCompositionLocalOf<Float?> { null }

/** Scales a chrome panel background to the floating opacity; text and icons must not use this. */
@Composable
@ReadOnlyComposable
fun Color.chromePanel(): Color {
    val opacity = LocalChromeOpacity.current ?: return this
    return copy(alpha = opacity)
}
