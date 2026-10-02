/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Size
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.FaceDetectGraph
import com.librestatic.opencinecam.camera.FramingEdge
import com.librestatic.opencinecam.camera.SubjectFramingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectOutOfFrameTest {
    private fun state(mode: CaptureMode, faceModes: Set<Int>, framing: SubjectFramingStatus = SubjectFramingStatus()): CameraUiState {
        val descriptor = Camera2CameraDescriptor(
            cameraId = "face", lensFacing = 1, focalLengthsMm = listOf(4f), previewSize = Size(640, 480),
            jpegSize = null, rawSize = null, analysisSize = null, sensorOrientation = 90, sensitivityRange = null,
            exposureTimeRangeNs = null, aeCompensationRange = null, aeCompensationStep = 0f, minimumFocusDistance = null,
            supportsRaw = false, flashAvailable = false, targetFpsRanges = emptyList(), availableFixedFps = listOf(30),
            videoProfiles = emptyList(), logProfiles = emptyList(), faceDetectModes = faceModes,
        )
        return CameraUiState(phase = CameraUiPhase.PREVIEWING, selectedMode = mode, cameras = listOf(descriptor),
            selectedCameraId = descriptor.cameraId, subjectFraming = framing)
    }

    @Test
    fun capabilityFollowsTheCameraAndGraph() {
        assertTrue(outOfFrameCapable(state(CaptureMode.VIDEO, setOf(0, 1))))
        assertTrue(outOfFrameCapable(state(CaptureMode.PHOTO, setOf(0, 2))))
        assertFalse(outOfFrameCapable(state(CaptureMode.VIDEO, setOf(0))))
        assertFalse(outOfFrameCapable(state(CaptureMode.VIDEO, emptySet())))
        assertFalse(outOfFrameCapable(CameraUiState()))
        assertEquals(FaceDetectGraph.HIGH_SPEED, faceDetectGraphFor(state(CaptureMode.SLOW_MOTION, setOf(1))))
        assertFalse(outOfFrameCapable(state(CaptureMode.SLOW_MOTION, setOf(0, 1, 2))))
        assertEquals(FaceDetectGraph.LOG, faceDetectGraphFor(state(CaptureMode.LOG, setOf(1))))
    }

    @Test
    fun aGraphRejectionReportedByTheEngineDisablesTheOption() {
        // Not evaluated yet: the static capability decides.
        assertTrue(outOfFrameCapable(state(CaptureMode.VIDEO, setOf(1), SubjectFramingStatus())))
        assertTrue(outOfFrameCapable(state(CaptureMode.VIDEO, setOf(1), SubjectFramingStatus(supported = true, evaluated = true))))
        // The engine evaluated the graph and found it rejects face statistics.
        assertFalse(outOfFrameCapable(state(CaptureMode.VIDEO, setOf(1), SubjectFramingStatus(supported = false, evaluated = true))))
    }

    @Test
    fun arrowsPointWhereTheSubjectShouldMoveAndWhereTheOperatorLostThem() {
        assertEquals("←", subjectReturnArrow(FramingEdge.LEFT))
        assertEquals("↓", subjectReturnArrow(FramingEdge.TOP))
        assertEquals("↑", subjectReturnArrow(FramingEdge.BOTTOM))
        assertEquals("", subjectReturnArrow(FramingEdge.NONE))
        assertEquals("↑", operatorExitArrow(FramingEdge.TOP))
        assertEquals("→", operatorExitArrow(FramingEdge.RIGHT))
    }
}
