/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.gallery.PendingPlay
import com.librestatic.opencinecam.gallery.pendingPlayStep
import com.librestatic.opencinecam.gallery.PlaybackPhase
import com.librestatic.opencinecam.gallery.playbackToggleAction
import org.junit.Assert.*
import org.junit.Test

class MediaPlaybackToggleTest {
    @Test fun shownPauseDoesNotTurnIntoPlayWhenLoopStartsSeekingBeforeDispatch() {
        var phase = PlaybackPhase.PLAYING
        val calls = mutableListOf<String>()
        val action = playbackToggleAction(phase == PlaybackPhase.PLAYING,
            { calls.add("play:$phase") }, { calls.add("pause:$phase") })
        phase = PlaybackPhase.SEEKING
        action()
        assertEquals(listOf("pause:SEEKING"), calls)
    }
    @Test fun shownPlayDoesNotBecomePauseWhenLivePhaseChangesBeforeDispatch() {
        var phase = PlaybackPhase.PAUSED
        val calls = mutableListOf<String>()
        val action = playbackToggleAction(phase == PlaybackPhase.PLAYING,
            { calls.add("play:$phase") }, { calls.add("pause:$phase") })
        phase = PlaybackPhase.PLAYING
        action()
        assertEquals(listOf("play:PLAYING"), calls)
    }

    private fun step(starting: Boolean = true, issued: Boolean = false, newer: Boolean = true, phase: PlaybackPhase,
        canPlay: Boolean = false, rawFrames: Boolean = false) = pendingPlayStep(starting, issued, newer, phase, canPlay, rawFrames)

    @Test fun aPlayPressedWhileTheReaderOpensWaitsAndThenGoesThrough() {
        assertEquals(PendingPlay.WAIT, step(phase = PlaybackPhase.LOADING))
        // Prepared but without its Surface yet, the session cannot start.
        assertEquals(PendingPlay.WAIT, step(phase = PlaybackPhase.PAUSED, canPlay = false))
        assertEquals(PendingPlay.ISSUE, step(phase = PlaybackPhase.PAUSED, canPlay = true))
        assertEquals(PendingPlay.ISSUE, step(phase = PlaybackPhase.ENDED, canPlay = true))
        // The CPU-frames session lets Play through a slow exact decode.
        assertEquals(PendingPlay.ISSUE, step(phase = PlaybackPhase.SEEKING, canPlay = true))
        assertEquals(PendingPlay.WAIT, step(phase = PlaybackPhase.LOADING, canPlay = true))
    }
    @Test fun anIssuedPlayWaitsForTheSeekToStartAndEndsWhenPlaying() {
        assertEquals(PendingPlay.WAIT, step(issued = true, phase = PlaybackPhase.SEEKING))
        assertEquals(PendingPlay.DONE, step(issued = true, phase = PlaybackPhase.PLAYING))
        assertEquals(PendingPlay.DONE, step(issued = true, phase = PlaybackPhase.ERROR))
        assertEquals(PendingPlay.DONE, step(phase = PlaybackPhase.ERROR))
    }
    @Test fun anIssuedPlayIsNeverIssuedTwiceAndEndsOnlyWhenALaterObservationRests() {
        // RAW delivers its observations later, so the one Play was issued from still reads PAUSED.
        assertEquals(PendingPlay.WAIT, step(issued = true, newer = false, phase = PlaybackPhase.PAUSED, canPlay = true))
        // The session paused it (lost audio focus, say): the press is over, not retried.
        assertEquals(PendingPlay.DONE, step(issued = true, phase = PlaybackPhase.PAUSED, canPlay = true))
        assertEquals(PendingPlay.DONE, step(issued = true, phase = PlaybackPhase.ENDED, canPlay = true))
    }
    @Test fun nothingPendingOrTheRawFrameViewerDoesNothing() {
        assertEquals(PendingPlay.IDLE, step(starting = false, phase = PlaybackPhase.PAUSED, canPlay = true))
        assertEquals(PendingPlay.DONE, step(phase = PlaybackPhase.LOADING, rawFrames = true))
    }
}
