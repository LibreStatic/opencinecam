/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVideoSessionTest {
    // CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES as the S24 Ultra's camera 0 reports them (PLAN-071).
    private val s24Ranges = listOf(15 to 15, 24 to 24, 30 to 30, 15 to 30, 60 to 60, 15 to 60)

    @Test fun fixedRangeWinsWhenAdvertised() {
        assertEquals(60 to 60, rawVideoFpsRange(s24Ranges, 60))
        assertEquals(30 to 30, rawVideoFpsRange(s24Ranges, 30))
        assertEquals(24 to 24, rawVideoFpsRange(s24Ranges, 24))
    }

    @Test fun otherwiseTheNarrowestContainingRange() {
        assertEquals(15 to 30, rawVideoFpsRange(s24Ranges, 25))
        assertEquals(15 to 60, rawVideoFpsRange(s24Ranges, 48))
    }

    @Test fun aTieKeepsTheHigherFloor() {
        assertEquals(20 to 40, rawVideoFpsRange(listOf(10 to 30, 20 to 40), 25))
    }

    @Test fun noContainingRangeIsNull() {
        assertNull(rawVideoFpsRange(s24Ranges, 120))
        assertNull(rawVideoFpsRange(emptyList(), 30))
    }

    // The Razr's camera 0 (blanc, CamX): RAW10 declares 33.3 ms, yet [60, 60] is an AE range.
    private val razrRanges = listOf(15 to 15, 24 to 24, 15 to 30, 30 to 30, 7 to 60, 60 to 60)
    private val declared30Ns = 33_333_333L

    @Test fun theQtiOverrideKeepsTheAeRangeWithinTheDeclaredLimitAndForcesTheFrameDuration() {
        assertEquals(
            RawVideoFrameRatePlan(30 to 30, 16_666_666L),
            rawVideoFrameRatePlan(razrRanges, 60, declared30Ns, qtiOverride = true),
        )
    }

    @Test fun withoutTheOverrideTheRazrPlanIsTodaysRange() {
        assertEquals(RawVideoFrameRatePlan(60 to 60, null), rawVideoFrameRatePlan(razrRanges, 60, declared30Ns, qtiOverride = false))
    }

    @Test fun anS24LikeCameraWithoutTheQuirkIsUnchangedDespiteItsDeclaredLimit() {
        assertEquals(RawVideoFrameRatePlan(60 to 60, null), rawVideoFrameRatePlan(s24Ranges, 60, declared30Ns, qtiOverride = false))
    }

    @Test fun aRateWithinTheDeclaredLimitNeedsNoOverride() {
        assertEquals(RawVideoFrameRatePlan(30 to 30, null), rawVideoFrameRatePlan(razrRanges, 30, declared30Ns, qtiOverride = true))
        assertEquals(RawVideoFrameRatePlan(24 to 24, null), rawVideoFrameRatePlan(razrRanges, 24, declared30Ns, qtiOverride = true))
        // 29.97 fps declared still reaches 30 within the tolerance.
        assertEquals(RawVideoFrameRatePlan(30 to 30, null), rawVideoFrameRatePlan(razrRanges, 30, 33_366_700L, qtiOverride = true))
    }

    @Test fun anUnknownDeclaredDurationKeepsTodaysPlan() {
        assertEquals(RawVideoFrameRatePlan(60 to 60, null), rawVideoFrameRatePlan(razrRanges, 60, null, qtiOverride = true))
        assertEquals(RawVideoFrameRatePlan(60 to 60, null), rawVideoFrameRatePlan(razrRanges, 60, 0L, qtiOverride = true))
    }

    @Test fun withoutAFixedRangeWithinTheLimitTheHighestReachingOneWithinItIsUsed() {
        assertEquals(
            RawVideoFrameRatePlan(15 to 30, 16_666_666L),
            rawVideoFrameRatePlan(listOf(15 to 30, 7 to 60, 60 to 60), 60, declared30Ns, qtiOverride = true),
        )
    }

    @Test fun noRangeWithinTheLimitFallsBackToTodaysPlanWithoutVendorKeys() {
        assertEquals(
            RawVideoFrameRatePlan(60 to 60, null),
            rawVideoFrameRatePlan(listOf(7 to 60, 60 to 60), 60, declared30Ns, qtiOverride = true),
        )
    }

    @Test fun graphStillAcceptsTheRazrAtSixty() {
        val decision = rawVideoGraphDecision(RawVideoSessionConfig(4000, 3000, 60), listOf(4000 to 3000), razrRanges)
        assertEquals(RawVideoGraphDecision.Accepted(60 to 60), decision)
    }

    @Test fun graphAcceptsTheFullSensorSizeAtSixty() {
        val decision = rawVideoGraphDecision(RawVideoSessionConfig(4080, 3060, 60), listOf(4080 to 3060), s24Ranges)
        assertEquals(RawVideoGraphDecision.Accepted(60 to 60), decision)
    }

    @Test fun graphRejectsAnUnadvertisedRawSize() {
        val decision = rawVideoGraphDecision(RawVideoSessionConfig(3840, 2160, 30), listOf(4080 to 3060), s24Ranges)
        assertTrue(decision is RawVideoGraphDecision.Rejected)
        assertTrue((decision as RawVideoGraphDecision.Rejected).detail.contains("4080x3060"))
    }

    @Test fun graphRejectsACameraWithoutRaw10() {
        assertTrue(rawVideoGraphDecision(RawVideoSessionConfig(4080, 3060, 30), emptyList(), s24Ranges) is RawVideoGraphDecision.Rejected)
    }

    @Test fun graphRejectsARateNoRangeContains() {
        assertTrue(rawVideoGraphDecision(RawVideoSessionConfig(4080, 3060, 120), listOf(4080 to 3060), s24Ranges) is RawVideoGraphDecision.Rejected)
    }

    @Test fun framesBeforeTheTakeBeginsAreDiscarded() {
        val take = RawVideoTakeState()
        var calls = 0
        assertEquals(RawVideoTakeState.FrameOutcome.Discarded, take.onFrame(1L) { calls++; true })
        assertEquals(0, calls)
    }

    @Test fun deliveredFramesMarkOnlyTheFirst() {
        val take = RawVideoTakeState()
        assertTrue(take.begin())
        assertEquals(RawVideoTakeState.FrameOutcome.Delivered(first = true), take.onFrame(1L) { true })
        assertEquals(RawVideoTakeState.FrameOutcome.Delivered(first = false), take.onFrame(2L) { true })
        assertEquals(2L, take.framesDelivered)
    }

    @Test fun aRefusedFrameEndsTheTakeAsRingFullWithoutAStreamFailure() {
        val take = RawVideoTakeState()
        take.begin()
        val outcome = take.onFrame(1L) { false }
        assertEquals(RawVideoTakeState.FrameOutcome.Ended(RawTakeEnding(RawVideoReasons.RING_FULL, false)), outcome)
        // The refused frame may sit in the ring, so its result is awaited.
        assertEquals(RawVideoTakeState.Phase.DRAINING, take.phase)
        // Every later frame is closed unseen, and no other path ends it again.
        assertEquals(RawVideoTakeState.FrameOutcome.Discarded, take.onFrame(2L) { error("not delivered") })
        assertNull(take.end(null, streamFailure = false))
        assertEquals(RawVideoTakeState.ResultOutcome.Forward(RawTakeEnding(RawVideoReasons.RING_FULL, false)), take.onResult(1L))
        assertTrue(take.settled)
        assertNull(take.end(RawVideoReasons.SESSION_CLOSED, streamFailure = true))
    }

    @Test fun aThrowingConsumerEndsTheTake() {
        val take = RawVideoTakeState()
        take.begin()
        val outcome = take.onFrame(1L) { throw IllegalStateException("copy failed") }
        assertEquals(RawVideoTakeState.FrameOutcome.Ended(RawTakeEnding(RawVideoReasons.CONSUMER_FAILED, false)), outcome)
        assertEquals(0L, take.framesDelivered)
    }

    @Test fun aStreamFailureIsReportedExactlyOnce() {
        val take = RawVideoTakeState()
        take.begin()
        assertEquals(RawTakeEnding(RawVideoReasons.CAMERA_DISCONNECTED, true), take.end(RawVideoReasons.CAMERA_DISCONNECTED, streamFailure = true))
        // The session close that follows a disconnect finds the take already ended.
        assertNull(take.end(RawVideoReasons.SESSION_LOST, streamFailure = true))
        assertEquals(RawTakeEnding(RawVideoReasons.CAMERA_DISCONNECTED, true), take.ending)
    }

    @Test fun theOperatorStopIsNoStreamFailure() {
        val take = RawVideoTakeState()
        take.begin()
        assertEquals(RawTakeEnding(null, false), take.end(null, streamFailure = true))
    }

    @Test fun aTakeEndedBeforeItBeganNeverBegins() {
        val take = RawVideoTakeState()
        assertEquals(RawTakeEnding(RawVideoReasons.SESSION_CLOSED, true), take.end(RawVideoReasons.SESSION_CLOSED, streamFailure = true))
        assertFalse(take.begin())
        assertEquals(RawVideoTakeState.FrameOutcome.Discarded, take.onFrame(1L) { true })
    }


    private fun takeWithFrames(vararg timestamps: Long) = RawVideoTakeState().apply {
        begin()
        timestamps.forEach { onFrame(it) { true } }
    }

    @Test fun theStopDrainsTheResultsOfDeliveredFramesUpToTheLast() {
        val take = takeWithFrames(100L, 200L, 300L)
        assertEquals(300L, take.lastFrameTimestampNs)
        assertEquals(RawVideoTakeState.ResultOutcome.Forward(null), take.onResult(100L))
        assertEquals(RawTakeEnding(null, false), take.end(null, streamFailure = false))
        assertEquals(RawVideoTakeState.Phase.DRAINING, take.phase)
        assertFalse(take.settled)
        // No frame is taken any more, but the trailing results still go to the consumer.
        assertEquals(RawVideoTakeState.FrameOutcome.Discarded, take.onFrame(400L) { error("not delivered") })
        assertEquals(RawVideoTakeState.ResultOutcome.Forward(null), take.onResult(200L))
        assertEquals(RawVideoTakeState.ResultOutcome.Discarded, take.onResult(null))
        // The last frame's result is forwarded and ends the drain.
        assertEquals(RawVideoTakeState.ResultOutcome.Forward(RawTakeEnding(null, false)), take.onResult(300L))
        assertTrue(take.settled)
        assertEquals(RawVideoTakeState.ResultOutcome.Discarded, take.onResult(400L))
        assertNull(take.drainTimedOut())
        assertNull(take.end(null, streamFailure = false))
    }

    @Test fun aLaterResultEndsTheDrainWhenTheLastOneWasLost() {
        val take = takeWithFrames(100L, 200L)
        take.end(null, streamFailure = false)
        assertEquals(RawVideoTakeState.ResultOutcome.Finished(RawTakeEnding(null, false)), take.onResult(233L))
        assertTrue(take.settled)
        assertEquals(RawVideoTakeState.ResultOutcome.Discarded, take.onResult(200L))
    }

    @Test fun theTimeoutEndsADrainExactlyOnce() {
        val take = takeWithFrames(100L)
        take.end(null, streamFailure = false)
        assertEquals(RawTakeEnding(null, false), take.drainTimedOut())
        assertTrue(take.settled)
        assertNull(take.drainTimedOut())
        assertEquals(RawVideoTakeState.ResultOutcome.Discarded, take.onResult(100L))
    }

    @Test fun aTakeWithoutFramesEndsAtOnce() {
        val take = RawVideoTakeState()
        take.begin()
        assertEquals(RawVideoTakeState.ResultOutcome.Forward(null), take.onResult(50L))
        assertEquals(RawTakeEnding(null, false), take.end(null, streamFailure = false))
        assertTrue(take.settled)
        assertNull(take.drainTimedOut())
    }

    @Test fun aStopAfterTheLastResultAlreadyCameEndsAtOnce() {
        val take = takeWithFrames(100L, 200L)
        take.onResult(200L)
        take.end(null, streamFailure = false)
        assertTrue(take.settled)
    }

    @Test fun aStreamFailureEndsAtOnceWithoutDraining() {
        val take = takeWithFrames(100L)
        assertEquals(RawTakeEnding(RawVideoReasons.CAMERA_ERROR, true), take.end(RawVideoReasons.CAMERA_ERROR, streamFailure = true))
        assertTrue(take.settled)
        assertEquals(RawVideoTakeState.ResultOutcome.Discarded, take.onResult(100L))
    }

    @Test fun aConsumerFaultEndsAtOnceWithoutDraining() {
        val take = takeWithFrames(100L)
        assertEquals(RawVideoTakeState.FrameOutcome.Ended(RawTakeEnding(RawVideoReasons.CONSUMER_FAILED, false)),
            take.onFrame(200L) { throw IllegalStateException("copy failed") })
        assertTrue(take.settled)
        assertEquals(100L, take.lastFrameTimestampNs)
    }

    @Test fun aStreamFailureCutsADrainShortWithTheFirstEnding() {
        val take = takeWithFrames(100L)
        take.end(null, streamFailure = false)
        // A second stop while draining changes nothing.
        assertNull(take.end(null, streamFailure = false))
        assertFalse(take.settled)
        assertEquals(RawTakeEnding(null, false), take.end(RawVideoReasons.SESSION_CLOSED, streamFailure = true))
        assertTrue(take.settled)
        assertNull(take.drainTimedOut())
        assertNull(take.end(RawVideoReasons.CAMERA_DISCONNECTED, streamFailure = true))
    }

    @Test fun aConsumerFaultCutsADrainShort() {
        val take = takeWithFrames(100L, 200L)
        take.end(null, streamFailure = false)
        assertEquals(RawTakeEnding(null, false), take.end(RawVideoReasons.CONSUMER_FAILED, streamFailure = false))
        assertTrue(take.settled)
    }
}
