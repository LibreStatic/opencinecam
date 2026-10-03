/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.playback.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class PlaybackLayoutTest {
    @Test fun eachWindowGetsTheMonitorLayoutItsSizeAllows() {
        assertEquals(PlaybackLayout.STACKED, playbackLayoutFor(AdaptiveWindow(411f, 914f)))
        assertEquals(PlaybackLayout.STACKED, playbackLayoutFor(AdaptiveWindow(360f, 640f)))
        // A tablet or an unfolded phone in portrait keeps the stacked monitor with a wider picture.
        assertEquals(PlaybackLayout.STACKED, playbackLayoutFor(AdaptiveWindow(800f, 1280f)))
        assertEquals(PlaybackLayout.SIDE_COLUMN, playbackLayoutFor(AdaptiveWindow(914f, 411f)))
        assertEquals(PlaybackLayout.SIDE_COLUMN, playbackLayoutFor(AdaptiveWindow(841f, 673f)))
        assertEquals(PlaybackLayout.INSPECTOR, playbackLayoutFor(AdaptiveWindow(1280f, 800f)))
        assertEquals(PlaybackLayout.INSPECTOR, playbackLayoutFor(AdaptiveWindow(1543f, 868f, hardwareKeyboard = true)))
        // A very wide but short window has no height for a permanent inspector.
        assertEquals(PlaybackLayout.SIDE_COLUMN, playbackLayoutFor(AdaptiveWindow(1300f, 420f)))
    }

    @Test fun pictureFitsItsOwnAspectWithoutLetterboxWhenTheBoxMatches() {
        val clip = fitInside(16f / 9f, 1600f, 900f)
        assertEquals(1600f, clip.first, 0.01f); assertEquals(900f, clip.second, 0.01f)
        val photo = fitInside(4f / 3f, 1600f, 900f)
        assertEquals(1200f, photo.first, 0.01f); assertEquals(900f, photo.second, 0.01f)
        val portrait = fitInside(16f / 9f, 411f, 600f)
        assertEquals(411f, portrait.first, 0.01f); assertEquals(231.19f, portrait.second, 0.01f)
        // The 1.63:1 frame from the field report stays 1.63:1 instead of being stretched to the box.
        val report = fitInside(1.63f, 744f, 600f)
        assertEquals(1.63f, report.first / report.second, 0.001f)
    }

    @Test fun unknownAspectOrEmptyBoxNeverInventsAShape() {
        assertEquals(320f to 200f, fitInside(null, 320f, 200f))
        assertEquals(320f to 200f, fitInside(Float.NaN, 320f, 200f))
        assertEquals(320f to 200f, fitInside(0f, 320f, 200f))
        assertEquals(0f to 200f, fitInside(16f / 9f, 0f, 200f))
        assertEquals(0f to 0f, fitInside(16f / 9f, -5f, -1f))
        assertNull(pictureAspect(0, 960)); assertNull(pictureAspect(1280, -1))
        assertEquals(4f / 3f, pictureAspect(1280, 960)!!, 0.0001f)
    }

    @Test fun oneSecondJumpsStayInsideTheClip() {
        assertEquals(1_500_000L, playbackJumpTarget(500_000L, PLAYBACK_JUMP_US, 4_000_000L))
        assertEquals(0L, playbackJumpTarget(400_000L, -PLAYBACK_JUMP_US, 4_000_000L))
        assertEquals(4_000_000L, playbackJumpTarget(3_600_000L, PLAYBACK_JUMP_US, 4_000_000L))
        assertEquals(0L, playbackJumpTarget(0L, PLAYBACK_JUMP_US, -1L))
    }

    @Test fun secondsReadAsPlainDecimals() {
        assertEquals("0.400", playbackSeconds(400_000L, Locale.ROOT))
        assertEquals("1,700", playbackSeconds(1_700_000L, Locale.GERMANY))
        assertEquals("0.000", playbackSeconds(-3L, Locale.ROOT))
    }

    @Test fun shortcutsOnlyDoWhatTheEnabledControlsDo() {
        val idle = PlaybackKeyState()
        for (action in listOf(ShortcutAction.PLAY_PAUSE, ShortcutAction.FRAME_BACK, ShortcutAction.FRAME_FORWARD,
            ShortcutAction.JUMP_BACK, ShortcutAction.JUMP_FORWARD, ShortcutAction.LOG_VIEW)) assertNull(playbackCommandFor(action, idle))
        val paused = PlaybackKeyState(canTogglePlay = true, canStepBack = true, canStepForward = true, seekable = true, logView = true)
        assertEquals(PlaybackCommand.TOGGLE_PLAY, playbackCommandFor(ShortcutAction.PLAY_PAUSE, paused))
        assertEquals(PlaybackCommand.FRAME_BACK, playbackCommandFor(ShortcutAction.FRAME_BACK, paused))
        assertEquals(PlaybackCommand.FRAME_FORWARD, playbackCommandFor(ShortcutAction.FRAME_FORWARD, paused))
        assertEquals(PlaybackCommand.JUMP_BACK, playbackCommandFor(ShortcutAction.JUMP_BACK, paused))
        assertEquals(PlaybackCommand.JUMP_FORWARD, playbackCommandFor(ShortcutAction.JUMP_FORWARD, paused))
        assertEquals(PlaybackCommand.TOGGLE_LOG_VIEW, playbackCommandFor(ShortcutAction.LOG_VIEW, paused))
        // At the first frame the back step is disabled, so its key is left alone.
        assertNull(playbackCommandFor(ShortcutAction.FRAME_BACK, paused.copy(canStepBack = false)))
        // Keys the review does not use are never consumed.
        assertNull(playbackCommandFor(ShortcutAction.RECORD, paused))
        assertNull(playbackCommandFor(ShortcutAction.PEAKING, paused))
    }

    @Test fun escapeClosesTheInnermostThingFirst() {
        val all = PlaybackKeyState(detailsOpen = true, fullscreen = true)
        assertEquals(PlaybackCommand.CLOSE_DETAILS, playbackCommandFor(ShortcutAction.DISMISS, all))
        assertEquals(PlaybackCommand.EXIT_FULLSCREEN, playbackCommandFor(ShortcutAction.DISMISS, all.copy(detailsOpen = false)))
        assertEquals(PlaybackCommand.CLOSE, playbackCommandFor(ShortcutAction.DISMISS, PlaybackKeyState()))
    }
}
