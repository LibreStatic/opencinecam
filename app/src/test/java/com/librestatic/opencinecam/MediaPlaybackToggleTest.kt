/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

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
}
