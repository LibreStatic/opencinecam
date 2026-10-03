/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.annotation.StringRes
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource

/**
 * Hardware keyboard shortcuts (a Chromebook, DeX, a tablet keyboard). One map for the whole app;
 * each screen decides what an action means there and ignores the rest.
 */
internal enum class ShortcutAction(val label: String) {
    /** R: start or stop a take, or take a photo. Never repeats while the key is held. */
    RECORD("R"),

    /** F: open or close the focus panel. */
    FOCUS("F"),

    /** Z, P, G: the zebra, peaking and framing-grid overlays. */
    ZEBRA("Z"),
    PEAKING("P"),
    GRID("G"),

    /** H: show or hide the scopes. */
    SCOPES("H"),

    /** L: monitor LOG footage as Rec.709 (capture) or switch the LOG/709 view (playback). */
    LOG_VIEW("L"),

    /** Space: play or pause. */
    PLAY_PAUSE("Space"),

    /** Left and right arrows: one frame; with Shift, one second. Held keys repeat. */
    FRAME_BACK("←"),
    FRAME_FORWARD("→"),
    JUMP_BACK("Shift+←"),
    JUMP_FORWARD("Shift+→"),

    /** Esc: close the open panel, sheet or inspector. */
    DISMISS("Esc"),
}

/**
 * The action for one key press, or null when it is not a shortcut. Modified presses (Ctrl, Alt,
 * Meta) are left to the system, and a held key only repeats the frame steps, so holding R can
 * never start and stop a take over and over.
 */
internal fun shortcutActionFor(
    key: Key,
    shift: Boolean = false,
    ctrl: Boolean = false,
    alt: Boolean = false,
    meta: Boolean = false,
    repeat: Boolean = false,
): ShortcutAction? {
    if (ctrl || alt || meta) return null
    val action = when (key) {
        Key.R -> ShortcutAction.RECORD
        Key.F -> ShortcutAction.FOCUS
        Key.Z -> ShortcutAction.ZEBRA
        Key.P -> ShortcutAction.PEAKING
        Key.G -> ShortcutAction.GRID
        Key.H -> ShortcutAction.SCOPES
        Key.L -> ShortcutAction.LOG_VIEW
        Key.Spacebar -> ShortcutAction.PLAY_PAUSE
        Key.DirectionLeft -> if (shift) ShortcutAction.JUMP_BACK else ShortcutAction.FRAME_BACK
        Key.DirectionRight -> if (shift) ShortcutAction.JUMP_FORWARD else ShortcutAction.FRAME_FORWARD
        Key.Escape -> ShortcutAction.DISMISS
        else -> null
    } ?: return null
    if (shift && action != ShortcutAction.JUMP_BACK && action != ShortcutAction.JUMP_FORWARD) return null
    val repeats = action == ShortcutAction.FRAME_BACK || action == ShortcutAction.FRAME_FORWARD ||
        action == ShortcutAction.JUMP_BACK || action == ShortcutAction.JUMP_FORWARD
    return if (repeat && !repeats) null else action
}

/**
 * Routes shortcuts to the screens on display. The handler registered last is asked first, so a
 * panel opened over the capture screen gets Esc before the screen under it; a handler returns
 * true when it used the action.
 */
internal class ShortcutDispatcher {
    private val handlers = mutableListOf<(ShortcutAction) -> Boolean>()

    fun register(handler: (ShortcutAction) -> Boolean): () -> Unit {
        handlers += handler
        return { handlers -= handler }
    }

    fun dispatch(action: ShortcutAction): Boolean = handlers.toList().asReversed().any { it(action) }
}

internal val LocalShortcutDispatcher = staticCompositionLocalOf<ShortcutDispatcher?> { null }

/**
 * Handles shortcuts while this composable is on screen in the activity window. Keys a focused
 * text field or button uses never get here: the activity only sees presses nothing else took.
 */
@Composable
internal fun ShortcutHandler(enabled: Boolean = true, onAction: (ShortcutAction) -> Boolean) {
    val dispatcher = LocalShortcutDispatcher.current ?: return
    val current by rememberUpdatedState(onAction)
    DisposableEffect(dispatcher, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val unregister = dispatcher.register { current(it) }
        onDispose { unregister() }
    }
}

/**
 * Shortcuts inside a dialog window, which the activity never sees. Put it on the dialog's root:
 * it takes focus when shown and gets the presses the dialog's own controls leave.
 */
internal fun Modifier.dialogShortcuts(onAction: (ShortcutAction) -> Boolean): Modifier = composed {
    val focus = remember { FocusRequester() }
    val current by rememberUpdatedState(onAction)
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
    focusRequester(focus).onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val action = shortcutActionFor(event.key, event.isShiftPressed, event.isCtrlPressed, event.isAltPressed,
            event.isMetaPressed, event.nativeKeyEvent.repeatCount > 0) ?: return@onKeyEvent false
        current(action)
    }.focusable()
}

/** "Peaking (P)": a control's name with its shortcut, for tooltips and accessibility labels. */
internal fun withShortcut(label: String, action: ShortcutAction): String = "$label (${action.label})"

/** Same as [withShortcut] for a string resource label. */
@Composable
internal fun shortcutLabel(@StringRes label: Int, action: ShortcutAction): String =
    withShortcut(stringResource(label), action)
