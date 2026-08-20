/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.media.video.AsyncCodecDrain
import com.librestatic.opencinecam.media.video.DrainEvent
import com.librestatic.opencinecam.media.video.DrainSource
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuxerActorTest {
    @Test
    fun waitsForTracksNormalizesPtsAndFinalizesOnBothEos() {
        val sink = FakeSink()
        val muxer = MuxerActor(expectedTracks = setOf(MuxerTrack.VIDEO, MuxerTrack.AUDIO), sink = sink)
        try {
            assertTrue(muxer.submitSample(EncodedSample(MuxerTrack.VIDEO, 1_000, 0, byteArrayOf(1))))
            assertTrue(muxer.submitFormat(MuxerTrackFormat(MuxerTrack.VIDEO, "video/avc")))
            assertTrue(muxer.submitFormat(MuxerTrackFormat(MuxerTrack.AUDIO, "audio/aac")))
            assertTrue(muxer.submitSample(EncodedSample(MuxerTrack.AUDIO, 1_500, 0, byteArrayOf(2))))
            assertTrue(muxer.submitEos(MuxerTrack.VIDEO))
            assertTrue(muxer.submitEos(MuxerTrack.AUDIO))

            assertEquals(MuxerState.EOS, muxer.awaitCompletion(2, TimeUnit.SECONDS).state)
            assertEquals(listOf(0L, 500L), sink.normalizedPts)
            assertTrue(sink.started)
            assertTrue(sink.stopped)
        } finally {
            muxer.close()
        }
    }

    @Test
    fun backwardsPtsFailsAndReleasesOwnedSink() {
        val sink = FakeSink()
        val muxer = MuxerActor(expectedTracks = setOf(MuxerTrack.VIDEO), sink = sink)
        try {
            muxer.submitFormat(MuxerTrackFormat(MuxerTrack.VIDEO, "video/avc"))
            muxer.submitSample(EncodedSample(MuxerTrack.VIDEO, 2_000, 0, byteArrayOf(1)))
            muxer.submitSample(EncodedSample(MuxerTrack.VIDEO, 1_000, 0, byteArrayOf(2)))

            val result = muxer.awaitCompletion(2, TimeUnit.SECONDS)
            assertEquals(MuxerState.FAILED, result.state)
            assertEquals(FailureCode.CADENCE_DISCONTINUITY, result.failure?.code)
            assertTrue(sink.released)
        } finally {
            muxer.close()
        }
    }

    @Test
    fun duplicateFormatsAndUnknownTracksFailExplicitly() {
        val sink = FakeSink()
        val muxer = MuxerActor(expectedTracks = setOf(MuxerTrack.VIDEO), sink = sink)
        try {
            muxer.submitFormat(MuxerTrackFormat(MuxerTrack.VIDEO, "video/avc"))
            muxer.submitFormat(MuxerTrackFormat(MuxerTrack.VIDEO, "video/avc"))
            val result = muxer.awaitCompletion(2, TimeUnit.SECONDS)
            assertEquals(FailureCode.DUPLICATE_COMMAND, result.failure?.code)
        } finally {
            muxer.close()
        }
    }

    @Test
    fun asyncDrainForwardsSamplesAndEosExactlyOnce() {
        val sink = FakeSink()
        val muxer = MuxerActor(expectedTracks = setOf(MuxerTrack.VIDEO), sink = sink)
        val events = ArrayDeque<DrainEvent>(listOf(
            DrainEvent.Sample(EncodedSample(MuxerTrack.VIDEO, 100, 0, byteArrayOf(1))),
            DrainEvent.EndOfStream,
        ))
        val drain = AsyncCodecDrain(DrainSource { events.removeFirstOrNull() }, MuxerTrack.VIDEO, muxer, "drain-1")
        try {
            muxer.submitFormat(MuxerTrackFormat(MuxerTrack.VIDEO, "video/avc"))
            assertTrue(drain.start())
            assertFalse(drain.start())
            assertEquals(MuxerState.EOS, muxer.awaitCompletion(2, TimeUnit.SECONDS).state)
            assertEquals(listOf(0L), sink.normalizedPts)
        } finally {
            drain.close()
            muxer.close()
        }
    }

    private class FakeSink : MuxerSink {
        val normalizedPts = mutableListOf<Long>()
        var started = false
        var stopped = false
        var released = false

        override fun addTrack(format: MuxerTrackFormat): Int = format.track.ordinal
        override fun start() { started = true }
        override fun writeSample(trackIndex: Int, sample: EncodedSample, normalizedPtsUs: Long) {
            normalizedPts += normalizedPtsUs
        }
        override fun stop() { stopped = true }
        override fun release() { released = true }
        override fun close() { release() }
    }
}
