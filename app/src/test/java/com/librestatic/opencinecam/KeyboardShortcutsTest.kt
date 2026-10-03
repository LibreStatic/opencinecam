/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardShortcutsTest {

    @Test fun lettersMapToTheirActions() {
        assertEquals(ShortcutAction.RECORD, shortcutActionFor(Key.R))
        assertEquals(ShortcutAction.FOCUS, shortcutActionFor(Key.F))
        assertEquals(ShortcutAction.ZEBRA, shortcutActionFor(Key.Z))
        assertEquals(ShortcutAction.PEAKING, shortcutActionFor(Key.P))
        assertEquals(ShortcutAction.GRID, shortcutActionFor(Key.G))
        assertEquals(ShortcutAction.SCOPES, shortcutActionFor(Key.H))
        assertEquals(ShortcutAction.LOG_VIEW, shortcutActionFor(Key.L))
        assertEquals(ShortcutAction.PLAY_PAUSE, shortcutActionFor(Key.Spacebar))
        assertEquals(ShortcutAction.DISMISS, shortcutActionFor(Key.Escape))
        assertNull(shortcutActionFor(Key.Q))
    }

    @Test fun arrowsStepFramesAndShiftJumps() {
        assertEquals(ShortcutAction.FRAME_BACK, shortcutActionFor(Key.DirectionLeft))
        assertEquals(ShortcutAction.FRAME_FORWARD, shortcutActionFor(Key.DirectionRight))
        assertEquals(ShortcutAction.JUMP_BACK, shortcutActionFor(Key.DirectionLeft, shift = true))
        assertEquals(ShortcutAction.JUMP_FORWARD, shortcutActionFor(Key.DirectionRight, shift = true))
    }

    @Test fun modifiedPressesAreLeftToTheSystem() {
        assertNull(shortcutActionFor(Key.R, ctrl = true))
        assertNull(shortcutActionFor(Key.R, alt = true))
        assertNull(shortcutActionFor(Key.R, meta = true))
        assertNull(shortcutActionFor(Key.R, shift = true))
    }

    @Test fun onlyFrameStepsRepeatWhileHeld() {
        assertNull(shortcutActionFor(Key.R, repeat = true))
        assertNull(shortcutActionFor(Key.Spacebar, repeat = true))
        assertEquals(ShortcutAction.FRAME_FORWARD, shortcutActionFor(Key.DirectionRight, repeat = true))
        assertEquals(ShortcutAction.JUMP_BACK, shortcutActionFor(Key.DirectionLeft, shift = true, repeat = true))
    }

    @Test fun latestHandlerIsAskedFirstAndCanPass() {
        val dispatcher = ShortcutDispatcher()
        val seen = mutableListOf<String>()
        dispatcher.register { seen += "screen"; true }
        val unregisterPanel = dispatcher.register { seen += "panel"; it == ShortcutAction.DISMISS }
        assertTrue(dispatcher.dispatch(ShortcutAction.DISMISS))
        assertEquals(listOf("panel"), seen)
        assertTrue(dispatcher.dispatch(ShortcutAction.RECORD))
        assertEquals(listOf("panel", "panel", "screen"), seen)
        unregisterPanel()
        seen.clear()
        dispatcher.dispatch(ShortcutAction.DISMISS)
        assertEquals(listOf("screen"), seen)
    }

    @Test fun nothingRegisteredLeavesTheKeyUnused() {
        assertFalse(ShortcutDispatcher().dispatch(ShortcutAction.RECORD))
    }

    @Test fun handlerMayUnregisterWhileDispatching() {
        val dispatcher = ShortcutDispatcher()
        var unregister: () -> Unit = {}
        unregister = dispatcher.register { unregister(); true }
        assertTrue(dispatcher.dispatch(ShortcutAction.DISMISS))
        assertFalse(dispatcher.dispatch(ShortcutAction.DISMISS))
    }

    @Test fun labelsCarryTheShortcut() {
        assertEquals("Peaking (P)", withShortcut("Peaking", ShortcutAction.PEAKING))
        assertEquals("Next frame (→)", withShortcut("Next frame", ShortcutAction.FRAME_FORWARD))
    }
}
