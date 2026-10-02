/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectFramingTest {
    private val off = 0
    private val simple = 1
    private val full = 2

    @Test
    fun capabilityPrefersSimpleThenFullAndNeverHighSpeed() {
        assertEquals(simple, FaceDetectCapability.select(setOf(off, simple, full), FaceDetectGraph.PREVIEW))
        assertEquals(full, FaceDetectCapability.select(setOf(off, full), FaceDetectGraph.VIDEO))
        assertEquals(simple, FaceDetectCapability.select(setOf(simple), FaceDetectGraph.LOG))
        assertNull(FaceDetectCapability.select(setOf(off), FaceDetectGraph.PREVIEW))
        assertNull(FaceDetectCapability.select(emptySet(), FaceDetectGraph.VIDEO))
        // Constrained high-speed requests reject most extras: excluded even when advertised.
        assertNull(FaceDetectCapability.select(setOf(off, simple, full), FaceDetectGraph.HIGH_SPEED))
    }

    @Test
    fun recordedAreaCentreCropsTheCropRegionToTheStreamAspect() {
        // 4:3 active array, 16:9 stream: full width, letterboxed height.
        val array = SensorRect(0, 0, 4000, 3000)
        assertEquals(SensorRect(0, 375, 4000, 2625), SubjectFramingGeometry.recordedArea(array, 1920, 1080))
        // Same aspect: unchanged.
        assertEquals(array, SubjectFramingGeometry.recordedArea(array, 640, 480))
        // Narrower stream (1:1): pillarboxed width.
        assertEquals(SensorRect(500, 0, 3500, 3000), SubjectFramingGeometry.recordedArea(array, 1080, 1080))
        // Zoomed crop region keeps its offset.
        val crop = SensorRect(1000, 750, 3000, 2250)
        assertEquals(SensorRect(1000, 937, 3000, 2062), SubjectFramingGeometry.recordedArea(crop, 1920, 1080))
    }

    @Test
    fun insideRequiresMostOfTheFaceWithinTheRecordedArea() {
        val recorded = SensorRect(0, 375, 4000, 2625)
        assertTrue(SubjectFramingGeometry.isInside(SensorRect(1800, 1200, 2200, 1600), recorded))
        // In the 4:3 sensor area but above the 16:9 recorded band.
        assertFalse(SubjectFramingGeometry.isInside(SensorRect(1800, 0, 2200, 300), recorded))
        // Half outside the top edge: 50 % < 60 %.
        assertFalse(SubjectFramingGeometry.isInside(SensorRect(1800, 175, 2200, 575), recorded))
        // 75 % inside.
        assertTrue(SubjectFramingGeometry.isInside(SensorRect(1800, 275, 2200, 675), recorded))
        assertFalse(SubjectFramingGeometry.isInside(SensorRect(10, 10, 10, 50), recorded))
    }

    @Test
    fun sensorEdgeNamesTheSideBeyondOrTheExitBand() {
        val recorded = SensorRect(0, 0, 1000, 1000)
        assertEquals(FramingEdge.NONE, SubjectFramingGeometry.sensorEdge(SensorRect(450, 450, 550, 550), recorded))
        assertEquals(FramingEdge.LEFT, SubjectFramingGeometry.sensorEdge(SensorRect(-200, 450, -100, 550), recorded))
        assertEquals(FramingEdge.RIGHT, SubjectFramingGeometry.sensorEdge(SensorRect(850, 450, 950, 550), recorded))
        assertEquals(FramingEdge.TOP, SubjectFramingGeometry.sensorEdge(SensorRect(450, 20, 550, 120), recorded))
        assertEquals(FramingEdge.BOTTOM, SubjectFramingGeometry.sensorEdge(SensorRect(400, 1100, 500, 1200), recorded))
        // Corner: the deeper overshoot wins.
        assertEquals(FramingEdge.LEFT, SubjectFramingGeometry.sensorEdge(SensorRect(-400, 900, -300, 950), recorded))
    }

    @Test
    fun contentEdgeRotatesClockwiseWithTheSensorOrientation() {
        assertEquals(FramingEdge.LEFT, SubjectFramingGeometry.toContentEdge(FramingEdge.LEFT, 0))
        assertEquals(FramingEdge.TOP, SubjectFramingGeometry.toContentEdge(FramingEdge.LEFT, 90))
        assertEquals(FramingEdge.RIGHT, SubjectFramingGeometry.toContentEdge(FramingEdge.TOP, 90))
        assertEquals(FramingEdge.LEFT, SubjectFramingGeometry.toContentEdge(FramingEdge.BOTTOM, 90))
        assertEquals(FramingEdge.RIGHT, SubjectFramingGeometry.toContentEdge(FramingEdge.LEFT, 180))
        assertEquals(FramingEdge.BOTTOM, SubjectFramingGeometry.toContentEdge(FramingEdge.LEFT, 270))
        assertEquals(FramingEdge.NONE, SubjectFramingGeometry.toContentEdge(FramingEdge.NONE, 90))
    }

    @Test
    fun gateRejectsAGraphThatIgnoresTheKeyAndRemembersItPerCameraAndGraph() {
        val gate = FaceDetectGraphGate(rejectAfterMismatches = 3)
        gate.arm("0", FaceDetectGraph.VIDEO)
        assertFalse(gate.onResult(simple, off))
        assertFalse(gate.onResult(simple, off))
        assertTrue(gate.onResult(simple, off))
        assertTrue(gate.isRejected("0", FaceDetectGraph.VIDEO))
        assertFalse(gate.isRejected("0", FaceDetectGraph.PREVIEW))
        assertFalse(gate.isRejected("1", FaceDetectGraph.VIDEO))
        // Disarmed after rejection: later observations do nothing.
        assertFalse(gate.onResult(simple, off))
    }

    @Test
    fun gateRejectsEarlyFailuresButNotFailuresAfterConfirmation() {
        val gate = FaceDetectGraphGate(rejectAfterFailures = 2)
        gate.arm("0", FaceDetectGraph.LOG)
        assertFalse(gate.onCaptureFailed())
        assertTrue(gate.onCaptureFailed())
        assertTrue(gate.isRejected("0", FaceDetectGraph.LOG))

        val confirmed = FaceDetectGraphGate(rejectAfterFailures = 2)
        confirmed.arm("0", FaceDetectGraph.PREVIEW)
        assertFalse(confirmed.onResult(simple, simple))
        repeat(5) { assertFalse(confirmed.onCaptureFailed()) }
        assertFalse(confirmed.isRejected("0", FaceDetectGraph.PREVIEW))
        confirmed.rejectActive()
        assertTrue(confirmed.isRejected("0", FaceDetectGraph.PREVIEW))
    }

    @Test
    fun trackerPublishesOnlyTransitionsAndKeepsNoGeometry() {
        val tracker = SubjectFramingTracker()
        // The first evaluation is always published, even when unsupported.
        assertEquals(SubjectFramingStatus(evaluated = true), tracker.configure(supported = false, detecting = false, nowNanos = 1))
        assertNull(tracker.configure(supported = false, detecting = false, nowNanos = 1))
        val supported = tracker.configure(supported = true, detecting = false, nowNanos = 2)
        assertEquals(SubjectFramingStatus(supported = true, evaluated = true), supported)
        // Frames are ignored until detection is on.
        assertNull(tracker.onFrame(inFrame = true, edgeHint = FramingEdge.NONE, anyFace = true, nowNanos = 3))
        val started = tracker.configure(supported = true, detecting = true, nowNanos = 10)
        assertEquals(SubjectFramingStatus(true, true, false, 10L, FramingEdge.NONE, evaluated = true), started)
        val present = tracker.onFrame(true, FramingEdge.NONE, true, 20)
        assertEquals(true, present?.facePresentInFrame)
        assertEquals(20L, present?.lastSeenMonotonicNanos)
        // Still in frame, moving into the right exit band: not a transition.
        assertNull(tracker.onFrame(true, FramingEdge.RIGHT, true, 30))
        // Leaves entirely: absent, last seen at the last in-frame frame, exit edge from the last hint.
        val gone = tracker.onFrame(false, FramingEdge.NONE, false, 40)
        assertEquals(SubjectFramingStatus(true, true, false, 30L, FramingEdge.RIGHT, evaluated = true), gone)
        assertNull(tracker.onFrame(false, FramingEdge.NONE, false, 50))
        // Stopping detection clears the derived state.
        assertEquals(SubjectFramingStatus(supported = true, evaluated = true), tracker.configure(true, false, 60))
    }

    @Test
    fun warningPolicyUsesTheMonotonicDelay() {
        val absent = SubjectFramingStatus(supported = true, detecting = true, facePresentInFrame = false, lastSeenMonotonicNanos = 1_000_000_000L)
        assertFalse(OutOfFrameWarningPolicy.shouldWarn(absent, enabled = true, delaySeconds = 2, nowNanos = 3_000_000_000L))
        assertTrue(OutOfFrameWarningPolicy.shouldWarn(absent, enabled = true, delaySeconds = 2, nowNanos = 3_000_000_001L))
        assertEquals(1L, OutOfFrameWarningPolicy.nanosUntilWarning(absent, true, 2, 3_000_000_000L))
        assertEquals(0L, OutOfFrameWarningPolicy.nanosUntilWarning(absent, true, 2, 9_000_000_000L))
        assertFalse(OutOfFrameWarningPolicy.shouldWarn(absent, enabled = false, delaySeconds = 2, nowNanos = 9_000_000_000L))
        assertFalse(OutOfFrameWarningPolicy.shouldWarn(absent.copy(facePresentInFrame = true), true, 2, 9_000_000_000L))
        assertFalse(OutOfFrameWarningPolicy.shouldWarn(absent.copy(supported = false), true, 2, 9_000_000_000L))
        assertFalse(OutOfFrameWarningPolicy.shouldWarn(absent.copy(detecting = false), true, 2, 9_000_000_000L))
        assertNull(OutOfFrameWarningPolicy.nanosUntilWarning(absent.copy(facePresentInFrame = true), true, 2, 0))
        // Out-of-range delays are clamped to the 1..10 s preference bounds.
        assertTrue(OutOfFrameWarningPolicy.shouldWarn(absent, true, 99, 11_000_000_001L))
        assertNotNull(OutOfFrameWarningPolicy.nanosUntilWarning(absent, true, 0, 0))
    }
}
