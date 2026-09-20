/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class MediaPlaybackGeometryTest {
    @Test fun portraitLandscapeAndResizeKeepFitAspectWithoutCropping() {
        for ((video,view) in listOf((64 to 96) to (400 to 220), (96 to 64) to (220 to 400), (96 to 64) to (480 to 320))) {
            val scale=playbackFitScale(video.first,video.second,view.first,view.second)
            assertTrue(scale.first>0f && scale.first<=1f);assertTrue(scale.second>0f && scale.second<=1f)
            assertEquals(video.first.toDouble()/video.second,view.first*scale.first.toDouble()/(view.second*scale.second),0.00001)
            assertTrue(scale.first==1f || scale.second==1f)
        }
    }
    @Test fun unlaidOutSurfaceUsesIdentityUntilDimensionsArrive() {
        assertEquals(1f to 1f,playbackFitScale(0,64,400,220))
        assertEquals(1f to 1f,playbackFitScale(96,64,0,0))
    }
    @Test fun exactFrameRetainsFractionalSarGeometryAgainstLatePlayerCallback() {
        val exact = PlaybackObservation(phase = PlaybackPhase.PAUSED, frameIndex = 1, videoWidth = 380, videoHeight = 192)
        assertSame(exact, exact.withPlayerGeometry(126, 64))
        val pending = exact.copy(phase = PlaybackPhase.SEEKING)
        assertSame(pending, pending.withPlayerGeometry(126, 64))
        val stillPreparing = exact.copy(phase = PlaybackPhase.LOADING)
        assertSame(stillPreparing, stillPreparing.withPlayerGeometry(126, 64))
    }
    @Test fun continuousAndUnindexedFramesUseNativeGeometryWithoutApplyingSarTwice() {
        val unindexed = PlaybackObservation().withPlayerGeometry(192, 62)
        assertEquals(192, unindexed.videoWidth); assertEquals(62, unindexed.videoHeight)
        val playing = PlaybackObservation(phase = PlaybackPhase.PLAYING, videoWidth = 380, videoHeight = 192)
            .withPlayerGeometry(126, 64)
        assertEquals(126, playing.videoWidth); assertEquals(64, playing.videoHeight)
        assertSame(playing, playing.withPlayerGeometry(0, 0))
    }

}
