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
        // 0.5 s estimate delay + 80 ms read + 0.5 s latency: ceil(60 * 1.08) + ceil(46.875 * 1.08).
        assertEquals(65+51,OpenCineLogGpuPipeline.maxPendingMuxSamples(60,48000,3840))
        assertEquals(33+51,OpenCineLogGpuPipeline.maxPendingMuxSamples(30,48000,3840))
        val worstCaseQueued=kotlin.math.ceil(60*0.58).toInt()+kotlin.math.ceil(48000/1024.0*0.58).toInt()
        assertTrue(worstCaseQueued>=OpenCineLogGpuPipeline.MIN_PENDING_MUX_SAMPLES-2)
        assertTrue(OpenCineLogGpuPipeline.maxPendingMuxSamples(60,48000,3840)>worstCaseQueued)
    }
}
