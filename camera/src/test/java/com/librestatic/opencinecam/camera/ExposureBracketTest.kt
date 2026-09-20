/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class ExposureBracketTest {
    private fun plan(selection: BracketSelection = BracketSelection(), min: Int = -24, max: Int = 24,
        numerator: Int = 1, denominator: Int = 3) =
        selection.resolve(min, max, numerator, denominator) as BracketResolution.Plan
    private fun reject(expected: BracketRejection, value: BracketResolution) {
        assertEquals(BracketResolution.Rejected(expected), value)
    }
    private fun still(index: Int, kind: StillImageKind = StillImageKind.JPEG, bytes: Int = 3,
        id: Long = index + 1L, timestamp: Long = 100 + index.toLong()): CapturedStill =
        CapturedStill(id, timestamp, 90, 87,
            PhotoFlashReport(PhotoFlashSelection(), 1, 0, null, null, null, null, timestamp),
            listOf(StillImagePayload(kind, ByteArray(bytes) { index.toByte() }, 640, 480)))
    private fun frames(selection: BracketSelection = BracketSelection()): List<BracketFrame> =
        plan(selection).exposures.mapIndexed { index, exposure ->
            BracketFrame(index, exposure.ev, exposure.compensationIndex, exposure.compensationIndex,
                1_000_000L + index, 100 + index, still(index))
        }

    @Test fun defaultsAreThreeSeparateTwoEvExposures() {
        val selection = BracketSelection()
        assertEquals(3, selection.count)
        assertEquals(BracketStep.TWO_EV, selection.step)
        assertEquals(listOf(-2.0, 0.0, 2.0), plan().exposures.map { it.ev })
        assertEquals(listOf(-6, 0, 6), plan().exposures.map { it.compensationIndex })
    }
    @Test fun everyCountHasUniqueAscendingSymmetricExposures() {
        for (count in listOf(3, 5, 7, 9)) for (step in BracketStep.entries) {
            val exposures = plan(BracketSelection(count, step), min = -48, max = 48, numerator = 1, denominator = 6).exposures
            assertEquals(count, exposures.size)
            assertEquals(0, exposures[count / 2].compensationIndex)
            assertEquals(exposures.first().ev, -exposures.last().ev, 0.0)
            assertTrue(exposures.zipWithNext().all { (a, b) -> a.compensationIndex < b.compensationIndex })
        }
    }
    @Test fun unsupportedCountsAreRejectedAtConstruction() {
        for (count in listOf(-1, 0, 1, 2, 4, 6, 8, 10, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { BracketSelection(count) }
    }
    @Test fun thirdsUseTheAdvertisedRationalWithoutFloatRounding() {
        assertEquals(listOf(-1, 0, 1), plan(BracketSelection(step = BracketStep.THIRD_EV)).exposures.map { it.compensationIndex })
        assertEquals(listOf(-2, 0, 2), plan(BracketSelection(step = BracketStep.THIRD_EV), numerator = 2, denominator = 12).exposures.map { it.compensationIndex })
    }
    @Test fun halfEvCannotBeRoundedIntoAThirdEvDeviceStep() {
        reject(BracketRejection.INEXACT_STEP, BracketSelection(step = BracketStep.HALF_EV).resolve(-6, 6, 1, 3))
    }
    @Test fun smallerThanNativeStepNeverDuplicatesZero() {
        reject(BracketRejection.INEXACT_STEP, BracketSelection(step = BracketStep.THIRD_EV).resolve(-10, 10, 1, 2))
    }
    @Test fun outerFramesOutsideRangeRejectTheEntirePlanWithoutClamping() {
        reject(BracketRejection.OUT_OF_RANGE, BracketSelection().resolve(-5, 6, 1, 3))
        reject(BracketRejection.OUT_OF_RANGE, BracketSelection().resolve(-6, 5, 1, 3))
        reject(BracketRejection.OUT_OF_RANGE, BracketSelection(9).resolve(-6, 6, 1, 3))
    }
    @Test fun exactRangeEdgesAreIncluded() {
        assertEquals(listOf(-6, 0, 6), plan(min = -6, max = 6).exposures.map { it.compensationIndex })
    }
    @Test fun missingInvalidOrDisabledAeNeverProducesAPlan() {
        reject(BracketRejection.AE_UNAVAILABLE, BracketSelection().resolve(-6, 6, 1, 3, false))
        for ((min, max) in listOf(null to 6, -6 to null, 6 to -6))
            reject(BracketRejection.COMPENSATION_UNAVAILABLE, BracketSelection().resolve(min, max, 1, 3))
        for ((n, d) in listOf(0 to 1, -1 to 3, 1 to 0, 1 to -3))
            reject(BracketRejection.COMPENSATION_UNAVAILABLE, BracketSelection().resolve(-6, 6, n, d))
    }
    @Test fun largeRationalProductsDoNotWrapToAnAllowedIndex() {
        reject(BracketRejection.OUT_OF_RANGE, BracketSelection(9).resolve(Int.MIN_VALUE, Int.MAX_VALUE, 1, Int.MAX_VALUE))
        assertEquals(listOf(-2, 0, 2), plan(numerator = Int.MAX_VALUE, denominator = Int.MAX_VALUE).exposures.map { it.compensationIndex })
    }
    @Test fun resolvedPlanCannotBeMutatedThroughItsList() {
        assertThrows(UnsupportedOperationException::class.java) { (plan().exposures as MutableList<BracketExposure>).clear() }
    }
    @Test fun matchingCompensationAndConvergencePermitExactlyOneStill() {
        val gate = BracketMeteringGate(-6)
        assertTrue(gate.observe(12, -6, 2))
        assertFalse(gate.observe(13, -6, 2))
        assertFalse(gate.observe(14, -6, 4))
    }
    @Test fun flashRequiredIsMeteredButDoesNotClaimFlashFired() {
        assertTrue(BracketMeteringGate(6).observe(99, 6, 4))
    }
    @Test fun unknownSearchingLockedAndPrecaptureResultsDoNotReleaseStill() {
        val gate = BracketMeteringGate(3)
        for (state in listOf(null, 0, 1, 3, 5)) assertFalse(gate.observe(10, 3, state))
        assertTrue(gate.observe(11, 3, 2))
    }
    @Test fun oldCompensationUnknownCompensationAndInvalidFrameCannotReleaseStill() {
        val gate = BracketMeteringGate(3)
        assertFalse(gate.observe(10, 0, 2))
        assertFalse(gate.observe(10, null, 2))
        assertFalse(gate.observe(-1, 3, 2))
        assertTrue(gate.observe(11, 3, 2))
    }
    @Test fun groupRequiresEveryFrameExactlyOnceInDeclaredOrder() {
        val frames = frames()
        assertThrows(IllegalArgumentException::class.java) { CapturedBracket(1, BracketSelection(), frames.take(2)) }
        assertThrows(IllegalArgumentException::class.java) { CapturedBracket(1, BracketSelection(), frames.reversed()) }
        assertThrows(IllegalArgumentException::class.java) { CapturedBracket(1, BracketSelection(), listOf(frames[0], frames[0], frames[2])) }
        assertEquals(3, CapturedBracket(1, BracketSelection(), frames).frames.size)
    }
    @Test fun timestampsAndCaptureIdsCannotBeReusedOrReversed() {
        val frames = frames().toMutableList()
        frames[1] = frames[1].copy(capture = still(1, id = 1))
        assertThrows(IllegalArgumentException::class.java) { CapturedBracket(1, BracketSelection(), frames) }
        frames[1] = frames[1].copy(capture = still(1, timestamp = 100))
        assertThrows(IllegalArgumentException::class.java) { CapturedBracket(1, BracketSelection(), frames) }
        frames[1] = frames[1].copy(capture = still(1, timestamp = 99))
        assertThrows(IllegalArgumentException::class.java) { CapturedBracket(1, BracketSelection(), frames) }
    }
    @Test fun requestedEvMustMatchTheSelectionAndSubmittedIndicesMustDiffer() {
        val frames = frames().toMutableList()
        frames[1] = frames[1].copy(requestedEv = 0.25)
        assertThrows(IllegalArgumentException::class.java) { CapturedBracket(1, BracketSelection(), frames) }
        frames[1] = frames[1].copy(requestedEv = 0.0, submittedCompensation = -6)
        assertThrows(IllegalArgumentException::class.java) { CapturedBracket(1, BracketSelection(), frames) }
    }
    @Test fun observedSensorExposureRemainsSeparateAndUnknownStaysUnknown() {
        val source = frames()
        val frame = source[1].copy(reportedCompensation = null, exposureTimeNs = null, sensitivityIso = null)
        val group = CapturedBracket(1, BracketSelection(), listOf(source[0], frame, source[2]))
        assertEquals(0.0, group.frames[1].requestedEv, 0.0)
        assertNull(group.frames[1].reportedCompensation)
        assertNull(group.frames[1].exposureTimeNs)
        assertNull(group.frames[1].sensitivityIso)
    }
    @Test fun heicDngAndPartialPairsCannotMasqueradeAsBracketJpegs() {
        for (kind in listOf(StillImageKind.HEIC, StillImageKind.DNG))
            assertThrows(IllegalArgumentException::class.java) { frames()[0].copy(capture = still(0, kind)) }
    }
    @Test fun invalidIndicesEvAndSensorFieldsAreRejected() {
        val frame = frames()[0]
        assertThrows(IllegalArgumentException::class.java) { frame.copy(index = -1) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(index = 9) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(requestedEv = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(exposureTimeNs = 0) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(sensitivityIso = -1) }
    }
    @Test fun groupCopiesAndProtectsItsFrameCollection() {
        val source = frames().toMutableList()
        val group = CapturedBracket(1, BracketSelection(), source)
        source.clear()
        assertEquals(3, group.frames.size)
        assertThrows(UnsupportedOperationException::class.java) { (group.frames as MutableList<BracketFrame>).clear() }
    }
    @Test fun aggregateByteLimitRejectsValidIndividualImagesOverThirtyTwoMiB() {
        val source = frames().mapIndexed { index, frame -> frame.copy(capture = still(index, bytes = 11 * 1024 * 1024)) }
        assertThrows(IllegalArgumentException::class.java) { CapturedBracket(1, BracketSelection(), source) }
    }
    @Test fun aggregateByteLimitAcceptsTheExactBoundary() {
        val source = frames().mapIndexed { index, frame ->
            frame.copy(capture = still(index, bytes = if (index == 0) CapturedBracket.MAX_ENCODED_BYTES - 2 else 1))
        }
        assertEquals(3, CapturedBracket(1, BracketSelection(), source).frames.size)
    }
    @Test fun failedBurstFrameKeepsAdmissionUntilTheWholeSequenceCompletes() {
        val owner = Any()
        val admission = java.util.concurrent.atomic.AtomicReference<Any?>(owner)
        assertFalse(retireLegacyStillSequence(admission, owner, LegacyStillSequenceEvent.FRAME_FAILED))
        assertSame(owner, admission.get())
        assertFalse(admission.compareAndSet(null, Any()))
        assertFalse(retireLegacyStillSequence(admission, owner, LegacyStillSequenceEvent.FRAME_FAILED))
        assertSame(owner, admission.get())
        assertTrue(retireLegacyStillSequence(admission, owner, LegacyStillSequenceEvent.COMPLETED))
        assertNull(admission.get())
        assertTrue(admission.compareAndSet(null, Any()))
    }
    @Test fun abortedBurstIsTerminalButItsLateCallbacksNeverRetireTheSuccessor() {
        val owner = Any()
        val successor = Any()
        val admission = java.util.concurrent.atomic.AtomicReference<Any?>(owner)
        assertFalse(retireLegacyStillSequence(admission, owner, LegacyStillSequenceEvent.FRAME_FAILED))
        assertTrue(retireLegacyStillSequence(admission, owner, LegacyStillSequenceEvent.ABORTED))
        assertTrue(admission.compareAndSet(null, successor))
        for (event in LegacyStillSequenceEvent.entries) {
            assertFalse(retireLegacyStillSequence(admission, owner, event))
            assertSame(successor, admission.get())
        }
    }
    @Test fun graphCloseCanRetireAnUnfinishedBurstWithoutAnyLaterCallbackResurrectingIt() {
        val owner = Any()
        val admission = java.util.concurrent.atomic.AtomicReference<Any?>(owner)
        assertFalse(retireLegacyStillSequence(admission, owner, LegacyStillSequenceEvent.FRAME_FAILED))
        admission.set(null) // closeResources retires the graph and its reservation.
        for (event in LegacyStillSequenceEvent.entries) {
            assertFalse(retireLegacyStillSequence(admission, owner, event))
            assertNull(admission.get())
        }
    }

}
