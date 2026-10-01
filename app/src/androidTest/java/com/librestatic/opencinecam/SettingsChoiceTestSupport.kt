/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Settings choices live in a dialog behind a value row (SettingsChips). The row's tag follows
 * SettingsChips' default (last "-segment" of the option tag replaced by "-row") unless the screen
 * passes its own rowTag, which [choiceRowTag] mirrors.
 */
internal fun choiceRowTag(option: String): String = when {
    option.startsWith("photo-aspect-") -> "photo-aspect-selected"
    option.startsWith("video-fps-") || option.startsWith("timelapse-fps-") -> option.substringBefore("-fps-") + "-selected"
    option.startsWith("timelapse-size-") -> "timelapse-size-row"
    option.startsWith("proxy-settings-edge-") -> "proxy-settings-edge-label"
    option.startsWith("proxy-settings-bitrate-") -> "proxy-settings-bitrate-label"
    else -> option.substringBeforeLast('-') + "-row"
}

/**
 * Makes [option] reachable: when it is not on screen, dismisses any open choice dialog and taps
 * [row] to open the one that holds it.
 */
internal fun ComposeTestRule.openChoice(option: String, row: String = choiceRowTag(option)): SemanticsNodeInteraction {
    if (onAllNodesWithTag(option).fetchSemanticsNodes().isEmpty()) {
        closeChoices()
        onNodeWithTag(row).performScrollTo().performClick()
        waitForIdle()
        openedChoice = option
    }
    return onNodeWithTag(option)
}

internal fun ComposeTestRule.pickChoice(option: String, row: String = choiceRowTag(option)) {
    openChoice(option, row).performScrollTo().performClick()
    waitForIdle()
    openedChoice = null // Picking an option dismisses its dialog.
}

/** The option whose dialog [openChoice] opened; other dialogs on screen are never dismissed. */
private var openedChoice: String? = null

/** Dismisses the choice dialog [openChoice] left open, if any, through its Cancel button. */
internal fun ComposeTestRule.closeChoices() {
    val option = openedChoice ?: return
    openedChoice = null
    // An action may already have dismissed it, and opened a dialog of its own.
    if (onAllNodesWithTag(option).fetchSemanticsNodes().isEmpty()) return
    val cancel = InstrumentationRegistry.getInstrumentation().targetContext.getString(android.R.string.cancel)
    if (onAllNodesWithText(cancel).fetchSemanticsNodes().isNotEmpty()) {
        onAllNodesWithText(cancel).onLast().performClick()
        waitForIdle()
    }
}

/**
 * Node lookup for tests that address a screen by tag: [isChoice] tags (and their "-label") are
 * reached through their dialog, anything else first dismisses a dialog [openChoice] left open.
 */
internal fun ComposeTestRule.settingsNode(tag: String, isChoice: Boolean): SemanticsNodeInteraction {
    if (isChoice) openChoice(tag.removeSuffix("-label")) else closeChoices()
    return onNodeWithTag(tag, useUnmergedTree = true)
}
