/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import com.librestatic.opencinecam.AdaptiveWindow
import com.librestatic.opencinecam.ShortcutAction
import java.util.Locale

/** How the review arranges the picture, its timeline and the take facts. */
internal enum class PlaybackLayout {
    /** Portrait windows: picture on top, then position, scrubber and transport, with the take facts in a sheet. */
    STACKED,

    /** Landscape phones and mid-size windows: picture and timeline on the left, facts and views in a column on the right. */
    SIDE_COLUMN,

    /** Tablets in landscape and desktop windows: a large picture with the take facts always open beside it. */
    INSPECTOR,
}

internal fun playbackLayoutFor(window: AdaptiveWindow): PlaybackLayout = when {
    window.persistentInspector -> PlaybackLayout.INSPECTOR
    window.landscape -> PlaybackLayout.SIDE_COLUMN
    else -> PlaybackLayout.STACKED
}

/** Width over height of a picture, or null while either side is unknown. */
internal fun pictureAspect(width: Int, height: Int): Float? =
    if (width > 0 && height > 0) width.toFloat() / height else null

/**
 * The largest rectangle of [aspect] that fits [boxWidth] x [boxHeight], centred by the caller. While the
 * aspect is unknown the picture gets the whole box. The stage is sized by this, so a 4:3 photo never sits
 * in a 16:9 black box and a 16:9 clip fills a 16:9 screen edge to edge.
 */
internal fun fitInside(aspect: Float?, boxWidth: Float, boxHeight: Float): Pair<Float, Float> {
    val width = boxWidth.coerceAtLeast(0f)
    val height = boxHeight.coerceAtLeast(0f)
    if (aspect == null || !aspect.isFinite() || aspect <= 0f || width == 0f || height == 0f) return width to height
    return if (width / height > aspect) height * aspect to height else width to width / aspect
}

/** One second either way, kept inside the clip. */
internal const val PLAYBACK_JUMP_US = 1_000_000L

internal fun playbackJumpTarget(positionUs: Long, deltaUs: Long, maximumUs: Long): Long =
    (positionUs + deltaUs).coerceIn(0L, maximumUs.coerceAtLeast(0L))

/** Seconds with three decimals for the take facts, e.g. "1.100". */
internal fun playbackSeconds(positionUs: Long, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%.3f", positionUs.coerceAtLeast(0L) / 1_000_000.0)

/** What a keyboard shortcut does in the review. */
internal enum class PlaybackCommand { TOGGLE_PLAY, FRAME_BACK, FRAME_FORWARD, JUMP_BACK, JUMP_FORWARD, TOGGLE_LOG_VIEW,
    CLOSE_DETAILS, EXIT_FULLSCREEN, CLOSE }

/** What the review can do right now, mirroring which on-screen controls are enabled. */
internal data class PlaybackKeyState(
    val canTogglePlay: Boolean = false,
    val canStepBack: Boolean = false,
    val canStepForward: Boolean = false,
    val seekable: Boolean = false,
    val logView: Boolean = false,
    val detailsOpen: Boolean = false,
    val fullscreen: Boolean = false,
)

/**
 * The command for [action], or null to leave the key alone. Keys never do more than the matching button:
 * a disabled control's shortcut is not consumed. Esc closes the innermost thing first: the open take
 * facts, then full screen, then the review itself.
 */
internal fun playbackCommandFor(action: ShortcutAction, state: PlaybackKeyState): PlaybackCommand? = when (action) {
    ShortcutAction.PLAY_PAUSE -> PlaybackCommand.TOGGLE_PLAY.takeIf { state.canTogglePlay }
    ShortcutAction.FRAME_BACK -> PlaybackCommand.FRAME_BACK.takeIf { state.canStepBack }
    ShortcutAction.FRAME_FORWARD -> PlaybackCommand.FRAME_FORWARD.takeIf { state.canStepForward }
    ShortcutAction.JUMP_BACK -> PlaybackCommand.JUMP_BACK.takeIf { state.seekable }
    ShortcutAction.JUMP_FORWARD -> PlaybackCommand.JUMP_FORWARD.takeIf { state.seekable }
    ShortcutAction.LOG_VIEW -> PlaybackCommand.TOGGLE_LOG_VIEW.takeIf { state.logView }
    ShortcutAction.DISMISS -> when {
        state.detailsOpen -> PlaybackCommand.CLOSE_DETAILS
        state.fullscreen -> PlaybackCommand.EXIT_FULLSCREEN
        else -> PlaybackCommand.CLOSE
    }
    else -> null
}
