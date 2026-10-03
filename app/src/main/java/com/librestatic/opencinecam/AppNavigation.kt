/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class AppSection { CAPTURE, MEDIA, SETTINGS }

internal enum class SettingsPage { MAIN, ABOUT, CAPABILITIES }

/** How Media and Settings offer the way to the other sections. */
internal enum class ShellNavigation { NONE, BOTTOM_BAR, RAIL }

/**
 * Capture reaches Media and Settings from its own chrome, so it gets no shell navigation. A
 * compact portrait window keeps a bottom bar, which gives way to the keyboard so a search field
 * keeps its room; every other window has width to spare and no height to give away, so it gets a
 * rail at the start edge. [window] is the pane the shell lives in, not the whole display.
 */
internal fun shellNavigation(window: AdaptiveWindow, section: AppSection, imeVisible: Boolean): ShellNavigation = when {
    section == AppSection.CAPTURE -> ShellNavigation.NONE
    window.navigationRail -> ShellNavigation.RAIL
    imeVisible -> ShellNavigation.NONE
    else -> ShellNavigation.BOTTOM_BAR
}

private val ShellSections = listOf(
    Triple(AppSection.CAPTURE, CineIcon.CAMERA, R.string.capture_tab),
    Triple(AppSection.MEDIA, CineIcon.MEDIA, R.string.media_tab),
    Triple(AppSection.SETTINGS, CineIcon.SETTINGS, R.string.settings_tab),
)

/**
 * Frames Media and Settings: one hinge-safe pane holding the navigation and the section. The
 * content keeps its place in the composition whichever navigation shows, so rotating, unfolding
 * or opening the keyboard never resets a scroll position or drops focus from a text field.
 */
@Composable
internal fun AppShell(
    section: AppSection,
    hinge: FoldHinge?,
    onSelect: (AppSection) -> Unit,
    content: @Composable () -> Unit,
) {
    HingeSafeSettingsPane(hinge) {
        val navigation = shellNavigation(LocalAdaptiveWindow.current, section, WindowInsets.isImeVisible)
        val chrome = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val contentInsets = when (navigation) {
            ShellNavigation.RAIL -> Modifier
                .consumeWindowInsets(chrome.only(WindowInsetsSides.Start))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
            ShellNavigation.BOTTOM_BAR -> Modifier
                .consumeWindowInsets(chrome.only(WindowInsetsSides.Bottom))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            ShellNavigation.NONE -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
        }
        Row(Modifier.fillMaxSize()) {
            if (navigation == ShellNavigation.RAIL) ShellRail(section, chrome, onSelect)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth().then(contentInsets)) { content() }
                if (navigation == ShellNavigation.BOTTOM_BAR) ShellBottomBar(section, chrome, onSelect)
            }
        }
    }
}

@Composable
private fun ShellRail(selected: AppSection, insets: WindowInsets, onSelect: (AppSection) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = scheme.onPrimaryContainer,
        selectedTextColor = scheme.primary,
        indicatorColor = scheme.primaryContainer,
        unselectedIconColor = scheme.onSurfaceVariant,
        unselectedTextColor = scheme.onSurfaceVariant,
    )
    NavigationRail(
        modifier = Modifier.fillMaxHeight().testTag("shell-rail"),
        containerColor = scheme.surfaceContainer,
        contentColor = scheme.onSurface,
        windowInsets = insets.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
    ) {
        Spacer(Modifier.height(12.dp))
        ShellSections.forEach { (section, icon, label) ->
            NavigationRailItem(
                selected = selected == section,
                onClick = { onSelect(section) },
                icon = { ShellIcon(icon) },
                label = { ShellLabel(label) },
                modifier = Modifier.testTag("nav-${section.name.lowercase()}"),
                colors = colors,
            )
        }
    }
}

@Composable
private fun ShellBottomBar(selected: AppSection, insets: WindowInsets, onSelect: (AppSection) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = ShortNavigationBarItemDefaults.colors(
        selectedIconColor = scheme.onPrimaryContainer,
        selectedTextColorTopIconPosition = scheme.primary,
        selectedTextColorStartIconPosition = scheme.primary,
        selectedIndicatorColor = scheme.primaryContainer,
        unselectedIconColor = scheme.onSurfaceVariant,
        unselectedTextColor = scheme.onSurfaceVariant,
    )
    ShortNavigationBar(
        modifier = Modifier.testTag("shell-bottom-bar"),
        containerColor = scheme.surfaceContainer,
        contentColor = scheme.onSurface,
        windowInsets = insets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) {
        ShellSections.forEach { (section, icon, label) ->
            ShortNavigationBarItem(
                selected = selected == section,
                onClick = { onSelect(section) },
                icon = { ShellIcon(icon) },
                label = { ShellLabel(label) },
                modifier = Modifier.testTag("nav-${section.name.lowercase()}"),
                colors = colors,
            )
        }
    }
}

/**
 * Tinted with the item's own content colour, so it follows the selection animation. The label
 * already names the item, so the drawn glyph stays out of the semantics tree.
 */
@Composable
private fun ShellIcon(icon: CineIcon) {
    CineGlyph(icon, LocalContentColor.current, Modifier.size(24.dp))
}

@Composable
private fun ShellLabel(@StringRes label: Int) {
    Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
}
