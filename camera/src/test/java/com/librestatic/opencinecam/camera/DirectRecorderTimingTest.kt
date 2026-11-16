/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRecorderTimingTest {
    private val start = 50_000_000_000L
    // A 10 s take at 240 fps whose first frame came 20 ms after the recorder started.
    private val firstFrame = start + 20_000_000L
    private val lastSampleUs = 10_000_000L
    private val drained = DirectRecorderTiming(start, firstFrame + 4_000_000L, firstFrame + lastSampleUs * 1_000L, 2_400, drained = true)
    private fun backed(zeroNs: Long) = AudioCaptureEpoch(zeroNs, timestampBacked = true)

    @Test fun theLastFrameAndItsPresentationTimeGiveTheFilesZero() {
        val placed = placeAppendedAudio(drained, sensorRealtime = true, lastSampleUs, backed(firstFrame + 35_000_000L), start + 30_000_000L)
        assertTrue(placed.measured)
        assertEquals(AppendedAudioPlacement.SHARED_ANCHORS, placed.policy)
        assertEquals(firstFrame, placed.videoFrameZeroNs)
        assertEquals(35_000L, placed.startOffsetUs)
    }

    @Test fun audioThatStartedBeforeTheFirstFrameGetsANegativeOffset() {
        val placed = placeAppendedAudio(drained, sensorRealtime = true, lastSampleUs, backed(firstFrame - 12_000_000L), start)
        assertTrue(placed.measured)
        assertEquals(-12_000L, placed.startOffsetUs)
    }

    @Test fun aSessionThatDidNotDrainFallsBackToTheFirstFrameAfterTheStart() {
        val placed = placeAppendedAudio(drained.copy(drained = false), sensorRealtime = true, lastSampleUs, backed(firstFrame + 35_000_000L), start)
        assertFalse(placed.measured)
        assertEquals(AppendedAudioPlacement.ESTIMATED_VIDEO_START, placed.policy)
        assertEquals(firstFrame + 4_000_000L, placed.videoFrameZeroNs)
        assertEquals(31_000L, placed.startOffsetUs)
    }

    @Test fun aSensorClockThatIsNotBootTimeUsesTheRecordersStart() {
        // Sensor timestamps on another clock: small numbers that mean nothing against boot time.
        val foreign = DirectRecorderTiming(start, 1_000_000L, 10_001_000_000L, 2_400, drained = true)
        val placed = placeAppendedAudio(foreign, sensorRealtime = false, lastSampleUs, backed(start + 40_000_000L), start)
        assertFalse(placed.measured)
        assertEquals(start, placed.videoFrameZeroNs)
        assertEquals(40_000L, placed.startOffsetUs)
    }

    @Test fun anAudioEpochWithoutTimestampsIsAnEstimateFromItsStartReceipt() {
        val receipt = start + 25_000_000L
        val unbacked = placeAppendedAudio(drained, sensorRealtime = true, lastSampleUs, AudioCaptureEpoch(receipt, timestampBacked = false), receipt)
        val missing = placeAppendedAudio(drained, sensorRealtime = true, lastSampleUs, null, receipt)
        for (placed in listOf(unbacked, missing)) {
            assertFalse(placed.measured)
            assertEquals(receipt, placed.audioFrameZeroNs)
            assertEquals((receipt - (firstFrame + 4_000_000L)) / 1_000L, placed.startOffsetUs)
        }
    }

    @Test fun aMeasuredZeroFarFromTheRecordersStartIsNotBelieved() {
        // The file lost its tail: the last sample is two seconds earlier than the last frame captured.
        val early = placeAppendedAudio(drained, sensorRealtime = true, lastSampleUs - 2_000_000L, backed(firstFrame), start)
        assertFalse(early.measured)
        // Or frames from before the recorder started: a zero half a second before the start.
        val late = placeAppendedAudio(drained, sensorRealtime = true, lastSampleUs + 520_000L, backed(firstFrame), start)
        assertFalse(late.measured)
        assertEquals(firstFrame + 4_000_000L, late.videoFrameZeroNs)
    }

    @Test fun withNoFrameSeenTheRecordersStartIsTheZero() {
        val blind = DirectRecorderTiming(start, null, null, 0, drained = false)
        val placed = placeAppendedAudio(blind, sensorRealtime = true, lastSampleUs, backed(start + 5_000_000L), start)
        assertFalse(placed.measured)
        assertEquals(start, placed.videoFrameZeroNs)
        assertEquals(5_000L, placed.startOffsetUs)
    }

    @Test fun theManualSyncOffsetMovesOnlyWhereTheAudioStarts() {
        val audio = backed(firstFrame + 35_000_000L)
        val plain = placeAppendedAudio(drained, sensorRealtime = true, lastSampleUs, audio, start)
        val later = placeAppendedAudio(drained, sensorRealtime = true, lastSampleUs, audio, start, audioSyncOffsetNs = 120_000_000L)
        val earlier = placeAppendedAudio(drained, sensorRealtime = true, lastSampleUs, audio, start, audioSyncOffsetNs = -80_000_000L)
        assertEquals(0L, plain.audioSyncOffsetUs)
        assertEquals(155_000L, later.startOffsetUs)
        assertEquals(120_000L, later.audioSyncOffsetUs)
        assertEquals(-45_000L, earlier.startOffsetUs)
        assertEquals(-80_000L, earlier.audioSyncOffsetUs)
        for (placed in listOf(later, earlier)) {
            assertEquals(plain.policy, placed.policy)
            assertEquals(plain.videoFrameZeroNs, placed.videoFrameZeroNs)
            assertEquals(firstFrame + 35_000_000L, placed.audioFrameZeroNs)
        }
    }
}
