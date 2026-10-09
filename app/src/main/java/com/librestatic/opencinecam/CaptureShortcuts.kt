/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** What a shortcut does on the capture screen. */
internal enum class CaptureShortcutCommand {
    /** Start or stop a take, or take a photo: the same action as REC. */
    CAPTURE,

    /** Open the focus panel, or close it when it is open. */
    TOGGLE_FOCUS,
    ZEBRA,
    PEAKING,
    GRID,

    /** Show or hide the scopes that are switched on. */
    TOGGLE_SCOPES,

    /** No scope is switched on: switch the waveform on, so H always shows something. */
    ENABLE_WAVEFORM,

    /** Monitor LOG as Rec.709. */
    VIEW_ASSIST,

    /** Close the open panel or sheet. */
    CLOSE_PANE,

    /** Nothing to close: swallow Esc so it does not leave the app. */
    CONSUME,
}

/**
 * The capture screen's reading of a shortcut, or null when it has none (playback keys, or L where
 * the view assist does not apply). Esc always ends here: with nothing open it is consumed, since
 * an operator reaching for Esc mid-take never means "leave the camera".
 */
internal fun captureShortcutCommand(
    action: ShortcutAction,
    paneOpen: Boolean,
    logViewAvailable: Boolean,
    scopesEnabled: Boolean,
): CaptureShortcutCommand? = when (action) {
    ShortcutAction.RECORD -> CaptureShortcutCommand.CAPTURE
    ShortcutAction.FOCUS -> CaptureShortcutCommand.TOGGLE_FOCUS
    ShortcutAction.ZEBRA -> CaptureShortcutCommand.ZEBRA
    ShortcutAction.PEAKING -> CaptureShortcutCommand.PEAKING
    ShortcutAction.GRID -> CaptureShortcutCommand.GRID
    ShortcutAction.SCOPES -> if (scopesEnabled) CaptureShortcutCommand.TOGGLE_SCOPES else CaptureShortcutCommand.ENABLE_WAVEFORM
    ShortcutAction.LOG_VIEW -> if (logViewAvailable) CaptureShortcutCommand.VIEW_ASSIST else null
    ShortcutAction.DISMISS -> if (paneOpen) CaptureShortcutCommand.CLOSE_PANE else CaptureShortcutCommand.CONSUME
    else -> null
}

/**
 * A control's name with its shortcut, "Peaking (P)", when a hardware keyboard is attached; just
 * the name otherwise, since a key hint means nothing on a touch-only phone.
 */
@Composable
internal fun keyHint(label: String, action: ShortcutAction?): String =
    if (action != null && LocalAdaptiveWindow.current.hardwareKeyboard) withShortcut(label, action) else label

/**
 * A tooltip over a capture control: a plain one with [title], or a rich one when [text] adds an
 * explanation. It opens on long press (the caller calls [state].show()) and on hover with a mouse.
 * [modifier] sizes the control (a weight, a fixed height); the tooltip box itself wraps the anchor
 * in a box of its own, so [content] must fill what it is given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureTooltip(
    title: String,
    text: String?,
    modifier: Modifier = Modifier,
    state: TooltipState = rememberTooltipState(),
    content: @Composable () -> Unit,
) {
    // The tooltip's popup takes no focus, so Back would pass it by and leave the app from the
    // capture screen; while it shows, Back only puts it away.
    BackHandler(enabled = state.isVisible) { state.dismiss() }
    Box(modifier, propagateMinConstraints = true) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = {
                if (text == null) PlainTooltip { Text(title) }
                else RichTooltip(title = { Text(title) }) { Text(text) }
            },
            state = state,
            content = content,
        )
    }
}
