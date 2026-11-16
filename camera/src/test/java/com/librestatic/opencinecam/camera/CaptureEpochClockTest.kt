/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class CaptureEpochClockTest {
    @Test fun emptyRequestedAudioAndPrimingOnlyOutputDoNotProduceASuccessfulTake() {
        assertTrue(hasRequiredEncodedSamples(1,null,0))
        assertFalse(hasRequiredEncodedSamples(0,null,0))
        assertFalse(hasRequiredEncodedSamples(1,0,1))
        assertFalse(hasRequiredEncodedSamples(1,1024,0))
        assertTrue(hasRequiredEncodedSamples(1,1024,1))
    }
    @Test fun pcmAbsolutePositionsDoNotAccumulateFractionalRounding() {
        assertThrows(IllegalArgumentException::class.java) { PcmCaptureEpoch(0) }
        assertEquals(1_000_000_000L,pcmFrameDurationNs(44100,44100))
        assertEquals(86_400_000_000_000L,pcmFrameDurationNs(44100L*86400,44100))
        assertThrows(ArithmeticException::class.java) { pcmFrameDurationNs(Long.MAX_VALUE,1) }
    }
    @Test fun timestampFramePositionRestoresTheFirstCapturedPcmFrameNotReadReceiptTime() {
        val pcm = PcmCaptureEpoch(48000)
        assertEquals(2_000_000_000L,pcm.observe(4800,2_100_000_000).frameZeroNs)
        assertEquals(2_000_000_000L,pcm.observe(9600,2_200_030_000).frameZeroNs)
        assertEquals(30000L,pcm.current()!!.maxResidualNs)
    }
    @Test fun lateTimestampDoesNotChangeAnAlreadyChosenEstimatedOrigin() {
        val pcm = PcmCaptureEpoch(48000);pcm.estimate(1_000_000_000)
        val status=pcm.observe(4800,1_105_000_000)
        assertFalse(status.timestampBacked);assertEquals(1_000_000_000L,status.frameZeroNs)
    }
    @Test fun realTimestampIsNeverReplacedByAReadReceiptEstimate() {
        val pcm=PcmCaptureEpoch(48000);val actual=pcm.observe(4800,2_100_000_000)
        assertEquals(actual,pcm.estimate(9_000_000_000))
    }
    @Test fun captureClockRegressionAndInvalidFramesFailRatherThanShiftTheAnchor() {
        val pcm=PcmCaptureEpoch(48000);pcm.observe(4800,2_100_000_000)
        assertThrows(IllegalStateException::class.java) { pcm.observe(4799,2_110_000_000) }
        assertThrows(IllegalStateException::class.java) { pcm.observe(4900,2_000_000_000) }
        assertThrows(IllegalArgumentException::class.java) { PcmCaptureEpoch(48000).observe(4800,1) }
    }
    @Test fun twoInputAnchorsAreRequiredBeforeAComparableMuxClockIsReady() {
        val clock=CaptureEpochClock(true);assertFalse(clock.ready())
        clock.videoInput(1_250_000_000);assertFalse(clock.ready())
        assertThrows(IllegalStateException::class.java) { clock.offsetUs(true) }
        clock.audioInput(AudioCaptureEpoch(1_000_000_000,true));assertTrue(clock.ready())
        assertEquals(250000L,clock.offsetUs(true));assertEquals(0L,clock.offsetUs(false))
    }
    @Test fun audioStartingAfterVideoRetainsItsOffsetDespiteCodecRebasing() {
        val clock=CaptureEpochClock(true);clock.videoInput(26_000_000_000);clock.audioInput(AudioCaptureEpoch(26_250_000_000,true))
        val mux=MuxTimestampNormalizer(clock)
        assertEquals(250000L,mux.normalize(false,0));assertEquals(0L,mux.normalize(true,26_000_000))
        assertEquals(271333L,mux.normalize(false,21333));assertEquals(33333L,mux.normalize(true,26_033_333))
        val report=clock.report(mux.videoOriginUs,mux.audioOriginUs,null)
        assertEquals("SHARED_BOOTTIME_CAPTURE_ANCHORS",report.policy);assertFalse(report.waveformAlignmentVerified)
        assertNull(report.audioEncoderDelayFrames)
    }
    @Test fun videoStartingAfterAudioRetainsItsOffsetAndItsOwnMonotonicHistory() {
        val clock=CaptureEpochClock(true);clock.videoInput(1_300_000_000);clock.audioInput(AudioCaptureEpoch(1_000_000_000,true))
        val mux=MuxTimestampNormalizer(clock)
        assertEquals(300000L,mux.normalize(true,1000));assertEquals(0L,mux.normalize(false,-21333))
        assertEquals(333333L,mux.normalize(true,34333));assertEquals(333333L,mux.normalize(true,34332))
        assertEquals(21333L,mux.normalize(false,0))
    }
    @Test fun unknownCameraEpochIsNeverComparedToAudioBoottime() {
        val clock=CaptureEpochClock(false);assertTrue(clock.ready());clock.videoInput(0)
        clock.audioInput(AudioCaptureEpoch(1_000_000_000,true));assertEquals(0L,clock.offsetUs(true))
        val report=clock.report(null,null,null);assertEquals("INDEPENDENT_UNKNOWN_CAMERA_EPOCH",report.policy);assertNull(report.sharedOriginNs);assertEquals(0L,report.videoFrameZeroNs)
    }
    @Test fun estimatedAudioStartIsExplicitAndNeverPromotedToVerified() {
        val clock=CaptureEpochClock(true);clock.videoInput(1_300_000_000);clock.audioInput(AudioCaptureEpoch(1_000_000_000,false))
        val report=clock.report(12,0,1024);assertEquals("BOOTTIME_ESTIMATED_AUDIO_START",report.policy)
        assertFalse(report.audioTimestampBacked);assertFalse(report.waveformAlignmentVerified);assertNull(report.audioMaxResidualNs)
        assertEquals(1024,report.audioEncoderDelayFrames)
    }
    @Test fun changedAudioEpochIsRejectedAndLaterVideoFramesCannotMoveTheOrigin() {
        val clock=CaptureEpochClock(true);clock.videoInput(1_000_000_000);clock.videoInput(9_000_000_000)
        clock.audioInput(AudioCaptureEpoch(1_100_000_000,true));assertEquals(100000L,clock.offsetUs(false))
        assertThrows(IllegalStateException::class.java) { clock.audioInput(AudioCaptureEpoch(1_100_000_001,true)) }
    }
    @Test fun pauseRejectionReasonsDistinguishPendingEstimatedAndUnsupportedClocks() {
        val pending=CaptureEpochClock(true,8000);pending.audioInput(AudioCaptureEpoch(1_000_000_000,true))
        assertEquals(CapturePauseRejection.ANCHORS_PENDING,pending.requestPaused(true,1_100_000_000))
        assertEquals(CapturePauseRejection.ANCHORS_PENDING,pending.lastPauseRejection)
        val estimated=CaptureEpochClock(true,8000);estimated.mapVideoInput(1_050_000_000);estimated.audioInput(AudioCaptureEpoch(1_000_000_000,false))
        assertFalse(estimated.setPaused(true,1_100_000_000));assertEquals(CapturePauseRejection.ESTIMATED_AUDIO_ANCHOR,estimated.lastPauseRejection)
        assertEquals(CapturePauseRejection.UNSUPPORTED_CLOCK,CaptureEpochClock(false,8000).requestPaused(true,1_100_000_000))
        assertEquals(CapturePauseRejection.UNSUPPORTED_CLOCK,CaptureEpochClock(true).pauseUnavailableReason())
        pending.mapVideoInput(1_050_000_000);assertNull(pending.pauseUnavailableReason())
        assertNull(pending.requestPaused(true,1_100_000_000));assertNull(pending.lastPauseRejection)
        assertEquals(CapturePauseRejection.UNCHANGED,pending.requestPaused(true,1_200_000_000))
        pending.finishPause(1_300_000_000);assertEquals(CapturePauseRejection.STOPPED,pending.requestPaused(false,1_400_000_000))
    }
    @Test fun regressedCameraTimestampIsDroppedOnlyOnceSharedPauseOwnsTheClock() {
        val clock=CaptureEpochClock(true,8000)
        assertEquals(1_100_000_000L,clock.mapVideoInput(1_100_000_000))
        // Before shared pause exists the historical pass-through is unchanged, but the horizon keeps the maximum.
        assertEquals(1_050_000_000L,clock.mapVideoInput(1_050_000_000))
        clock.audioInput(AudioCaptureEpoch(1_000_000_000,true));assertTrue(clock.pauseAvailable())
        assertNull(clock.mapVideoInput(1_080_000_000));assertEquals(1L,clock.regressedVideoFrames())
        assertTrue(clock.setPaused(true,1_000_000_000))
        // 1_100_000_000 + 1 ns rounds up onto the 8 kHz grid: frame 801, not the regressed 1_050/1_080 ms frames.
        assertEquals(801L,clock.report(null,null,null).sharedPause!!.windows.single().startFrame)
        assertEquals(1L,clock.report(null,null,null).sharedPause!!.regressedVideoFrames)
    }
    @Test fun preStartMuxQueueCoversTheEstimatedAnchorWindowAtSixtyFps() {
        assertEquals(OpenCineLogGpuPipeline.MIN_PENDING_MUX_SAMPLES,OpenCineLogGpuPipeline.maxPendingMuxSamples(60,null,null))
        // First 80 ms read + 0.5 s estimate window + 80 ms read + 0.5 s latency: ceil(60 * 1.16) + ceil(46.875 * 1.16).
        assertEquals(70+55,OpenCineLogGpuPipeline.maxPendingMuxSamples(60,48000,3840))
        assertEquals(35+55,OpenCineLogGpuPipeline.maxPendingMuxSamples(30,48000,3840))
        val worstCaseQueued=kotlin.math.ceil(60*0.66).toInt()+kotlin.math.ceil(48000/1024.0*0.66).toInt()
        assertTrue(worstCaseQueued>=OpenCineLogGpuPipeline.MIN_PENDING_MUX_SAMPLES-2)
        assertTrue(OpenCineLogGpuPipeline.maxPendingMuxSamples(60,48000,3840)>worstCaseQueued)
    }

    @Test
    fun readEstimateKeepsTheEarliestFrameZeroOfTheFirstSecond() {
        val estimate = PcmReadEpochEstimate(48_000)
        assertEquals(null, estimate.frameZeroNs())
        estimate.observeRead(0, 5_000_000_000L)
        assertEquals(null, estimate.frameZeroNs())
        // A backlog drained late, then reads that return as their last frame arrives, one of them 4 ms late.
        estimate.observeRead(4_800, 5_180_000_000L)
        assertEquals(5_080_000_000L, estimate.frameZeroNs())
        estimate.observeRead(9_600, 5_270_000_000L)
        assertEquals(5_070_000_000L, estimate.frameZeroNs())
        estimate.observeRead(14_400, 5_374_000_000L)
        assertEquals(5_070_000_000L, estimate.frameZeroNs())
        // After the window a fast audio clock no longer pulls the estimate earlier.
        estimate.observeRead(96_000, 7_000_000_000L)
        assertEquals(5_070_000_000L, estimate.frameZeroNs())
    }

    @Test
    fun readEstimateSettlesOnlyOnceItsWindowAfterTheFirstReadHasPassed() {
        val estimate = PcmReadEpochEstimate(48_000, 500_000_000L)
        assertFalse(estimate.settled(9_000_000_000L))
        // The first read returns well after the input started; the window opens there, not at the start.
        estimate.observeRead(4_800, 5_180_000_000L)
        assertEquals(5_080_000_000L, estimate.frameZeroNs())
        assertFalse(estimate.settled(5_600_000_000L))
        // Half a second after the start but inside the window, a read still moves the estimate earlier.
        estimate.observeRead(28_800, 5_600_000_000L)
        assertEquals(5_000_000_000L, estimate.frameZeroNs())
        assertFalse(estimate.settled(5_680_000_000L))
        assertTrue(estimate.settled(5_680_000_001L))
        estimate.observeRead(33_600, 5_680_000_001L)
        assertEquals(5_000_000_000L, estimate.frameZeroNs())
    }

    @Test fun theManualSyncOffsetMovesTheAudioPlacementButNotTheMeasuredAnchor() {
        val video = 2_000_000_000L
        // Audio measured 30 ms after the video, and 50 ms before it.
        for (audioZero in listOf(video + 30_000_000L, video - 50_000_000L)) for (offsetMs in listOf(-200L, -20L, 0L, 40L, 300L)) {
            val clock = CaptureEpochClock(cameraRealtime = true, sampleRateHz = 48_000, audioSyncOffsetNs = offsetMs * 1_000_000L)
            clock.videoInput(video); clock.audioInput(AudioCaptureEpoch(audioZero, true))
            val placed = audioZero + offsetMs * 1_000_000L
            val origin = minOf(video, placed)
            assertEquals("$audioZero $offsetMs", (video - origin) / 1000, clock.offsetUs(true))
            assertEquals("$audioZero $offsetMs", (placed - origin) / 1000, clock.offsetUs(false))
            // The audio sits offsetMs later than it would without the offset, against the video.
            assertEquals(Math.subtractExact(audioZero, video) / 1000 + offsetMs * 1000, clock.offsetUs(false) - clock.offsetUs(true))
            val report = clock.report(null, null, null)
            assertEquals(audioZero, report.audioFrameZeroNs)
            assertEquals(video, report.videoFrameZeroNs)
            assertEquals(origin, report.sharedOriginNs)
            assertEquals(offsetMs * 1000, report.audioSyncOffsetUs)
            assertEquals("SHARED_BOOTTIME_CAPTURE_ANCHORS", report.policy)
        }
    }
    @Test fun theManualSyncOffsetStillMovesTheAudioWithoutComparableAnchors() {
        val later = CaptureEpochClock(cameraRealtime = false, audioSyncOffsetNs = 60_000_000L)
        assertEquals(0L, later.offsetUs(true)); assertEquals(60_000L, later.offsetUs(false))
        val earlier = CaptureEpochClock(cameraRealtime = false, audioSyncOffsetNs = -60_000_000L)
        assertEquals(60_000L, earlier.offsetUs(true)); assertEquals(0L, earlier.offsetUs(false))
        val none = CaptureEpochClock(cameraRealtime = false)
        assertEquals(0L, none.offsetUs(true)); assertEquals(0L, none.offsetUs(false)); assertEquals(0L, none.report(null, null, null).audioSyncOffsetUs)
    }
}
