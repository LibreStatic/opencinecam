/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.media.storage.ClipRecoveryDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class IncompleteVideoDecisionTest {
    @Test
    fun noEncodedSampleDeletesEvenAContainerHeader() {
        assertEquals(ClipRecoveryDecision.DELETE, decideIncompleteVideo(muxerFinalized = false, bytes = 4096L, encodedFrames = 0L))
        assertEquals(ClipRecoveryDecision.DELETE, decideIncompleteVideo(muxerFinalized = true, bytes = 4096L, encodedFrames = 0L))
    }

    @Test
    fun anEncodedSampleKeepsTheFileAsIncompleteUnlessTheMuxerFinalized() {
        assertEquals(ClipRecoveryDecision.PUBLISH_INCOMPLETE, decideIncompleteVideo(false, 4096L, 1L))
        assertEquals(ClipRecoveryDecision.PUBLISH_COMPLETE, decideIncompleteVideo(true, 4096L, 120L))
    }

    @Test
    fun anUnknownFrameCountFallsBackToTheByteCount() {
        assertEquals(ClipRecoveryDecision.PUBLISH_INCOMPLETE, decideIncompleteVideo(false, 4096L, null))
        assertEquals(ClipRecoveryDecision.DELETE, decideIncompleteVideo(false, 0L, null))
    }
}
