/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.librestatic.opencinecam.ui.theme.AppTheme
import com.librestatic.opencinecam.ui.theme.AppThemeStore
import com.librestatic.opencinecam.ui.theme.rememberAppTheme

/** The look of the app: the Cine palette or Material You colours from the wallpaper. */
@Composable
internal fun AppearanceSettings() {
    val context = LocalContext.current
    val store = remember(context) { AppThemeStore(context) }
    val theme by rememberAppTheme()
    SettingsHeading(stringResource(R.string.appearance_title), stringResource(R.string.appearance_summary), CineIcon.DISPLAYS)
    SettingsChips(
        choices = AppTheme.entries,
        selected = theme,
        label = { stringResource(if (it == AppTheme.CINE) R.string.appearance_cine else R.string.appearance_you) },
        onSelect = store::save,
        tag = { "appearance-${it.name.lowercase()}" },
    )
}
