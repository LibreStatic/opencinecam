/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureShortcutsTest {

    private fun command(
        action: ShortcutAction,
        paneOpen: Boolean = false,
        logView: Boolean = false,
        scopes: Boolean = true,
    ) = captureShortcutCommand(action, paneOpen, logView, scopes)

    @Test fun keysMapToTheControlsTheyName() {
        assertEquals(CaptureShortcutCommand.CAPTURE, command(ShortcutAction.RECORD))
        assertEquals(CaptureShortcutCommand.TOGGLE_FOCUS, command(ShortcutAction.FOCUS))
        assertEquals(CaptureShortcutCommand.ZEBRA, command(ShortcutAction.ZEBRA))
        assertEquals(CaptureShortcutCommand.PEAKING, command(ShortcutAction.PEAKING))
        assertEquals(CaptureShortcutCommand.GRID, command(ShortcutAction.GRID))
    }

    @Test fun recordWorksWithAPanelOpen() {
        assertEquals(CaptureShortcutCommand.CAPTURE, command(ShortcutAction.RECORD, paneOpen = true))
    }

    @Test fun scopesToggleOrSwitchTheWaveformOn() {
        assertEquals(CaptureShortcutCommand.TOGGLE_SCOPES, command(ShortcutAction.SCOPES, scopes = true))
        assertEquals(CaptureShortcutCommand.ENABLE_WAVEFORM, command(ShortcutAction.SCOPES, scopes = false))
    }

    @Test fun logViewOnlyWhereItApplies() {
        assertEquals(CaptureShortcutCommand.VIEW_ASSIST, command(ShortcutAction.LOG_VIEW, logView = true))
        assertNull(command(ShortcutAction.LOG_VIEW, logView = false))
    }

    @Test fun escapeClosesAPanelAndOtherwiseIsSwallowed() {
        assertEquals(CaptureShortcutCommand.CLOSE_PANE, command(ShortcutAction.DISMISS, paneOpen = true))
        assertEquals(CaptureShortcutCommand.CONSUME, command(ShortcutAction.DISMISS, paneOpen = false))
    }

    @Test fun playbackKeysAreLeftToOtherScreens() {
        listOf(
            ShortcutAction.PLAY_PAUSE, ShortcutAction.FRAME_BACK, ShortcutAction.FRAME_FORWARD,
            ShortcutAction.JUMP_BACK, ShortcutAction.JUMP_FORWARD,
        ).forEach { assertNull("$it", command(it, paneOpen = true, logView = true)) }
    }
}
