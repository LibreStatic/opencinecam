/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam.capture

import androidx.compose.runtime.ReadOnlyComposable
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import com.librestatic.opencinecam.ui.viewfinder.chromePanel

internal val Graphite: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
internal val Panel: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f).chromePanel()
internal val Amber: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
internal val VerifiedCyan: Color @Composable @ReadOnlyComposable get() = LocalCineColors.current.verified
internal val RecordRed: Color @Composable @ReadOnlyComposable get() = LocalCineColors.current.record
internal val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val OkGreen: Color @Composable @ReadOnlyComposable get() = LocalCineColors.current.ok
