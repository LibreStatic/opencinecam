/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.TimelapsePauseStatus
import org.junit.Assert.*
import org.junit.Test

class SubjectOverlayModelTest {
    @Test fun tallyIsRedOnlyForAConfirmedUnpausedTake() {
        assertEquals(SubjectTally.RECORDING, subjectTally(CameraUiState(phase = CameraUiPhase.RECORDING)))
        for (phase in CameraUiPhase.entries - setOf(CameraUiPhase.RECORDING, CameraUiPhase.CAPTURING)) {
            assertEquals(phase.name, SubjectTally.NONE, subjectTally(CameraUiState(phase = phase)))
        }
    }

    @Test fun preparingPausedAndSavingAreAmberNeverSaved() {
        assertEquals(SubjectTally.PENDING, subjectTally(CameraUiState(phase = CameraUiPhase.CAPTURING)))
        assertEquals(SubjectTally.PENDING, subjectTally(CameraUiState(phase = CameraUiPhase.PREVIEWING, countdownSeconds = 3)))
        assertEquals(SubjectTally.PENDING, subjectTally(CameraUiState(phase = CameraUiPhase.RECORDING, recordingFinalizing = true)))
        assertEquals(SubjectTally.PENDING, subjectTally(CameraUiState(phase = CameraUiPhase.SAVED, recordingFinalizing = true)))
        val paused = TimelapsePauseStatus(1, paused = true, finished = false, observedAtMs = 0, activeElapsedMs = 0, pausedElapsedMs = 0, events = emptyList())
        assertEquals(SubjectTally.PENDING, subjectTally(CameraUiState(phase = CameraUiPhase.RECORDING, recordingPauseStatus = paused)))
        assertEquals(SubjectTally.RECORDING, subjectTally(CameraUiState(phase = CameraUiPhase.RECORDING, recordingPauseStatus = paused.copy(paused = false))))
        assertEquals(SubjectTally.NONE, subjectTally(CameraUiState(phase = CameraUiPhase.SAVED)))
    }

    @Test fun giantCountdownReplacesTheSmallBadgeAndRespectsTheToggle() {
        val counting = CameraUiState(countdownSeconds = 5)
        assertTrue(subjectShowsGiantCountdown(counting, SubjectDisplaySettings()))
        assertFalse(subjectShowsCountdownBadge(counting, SubjectDisplaySettings()))
        val off = SubjectDisplaySettings(giantCountdown = false)
        assertFalse(subjectShowsGiantCountdown(counting, off))
        assertTrue(subjectShowsCountdownBadge(counting, off))
        assertFalse(subjectShowsGiantCountdown(CameraUiState(), SubjectDisplaySettings()))
        assertFalse(subjectShowsCountdownBadge(CameraUiState(), off))
    }

    @Test fun recordedFrameFitsTheRotatedStreamInAPortraitCover() {
        // 1920x1080 sensor-90 stream on an upright portrait surface shows a 9:16 picture.
        val frame = requireNotNull(subjectRecordedFrame(1080f, 2520f, 1920, 1080, 1f, 90, 0))
        assertEquals(0f, frame.left, 0.01f)
        assertEquals(1080f, frame.width, 0.01f)
        assertEquals(1920f, frame.height, 0.01f)
        assertEquals(300f, frame.top, 0.01f)
        // Rotated to landscape, the same stream is 16:9 and pillarboxed on a 2520x1080 surface.
        val landscape = requireNotNull(subjectRecordedFrame(2520f, 1080f, 1920, 1080, 1f, 90, 90))
        assertEquals(1080f, landscape.height, 0.01f)
        assertEquals(1920f, landscape.width, 0.01f)
        assertEquals(300f, landscape.left, 0.01f)
    }

    @Test fun recordedFrameFollowsTheSqueezeLikeTheGpuOutput() {
        // Same convention as the subject GPU calculator: the content aspect is divided by the factor.
        val frame = requireNotNull(subjectRecordedFrame(2000f, 1000f, 1600, 1200, 2f, 0, 0))
        assertEquals(1000f, frame.height, 0.01f)
        assertEquals(2000f / 3f, frame.width, 0.01f)
        assertEquals((2000f - 2000f / 3f) / 2f, frame.left, 0.01f)
    }

    @Test fun recordedFrameIsUnknownWithoutAStreamOrSurface() {
        assertNull(subjectRecordedFrame(0f, 100f, 1920, 1080, 1f, 90, 0))
        assertNull(subjectRecordedFrame(100f, 100f, 0, 1080, 1f, 90, 0))
        assertNull(subjectRecordedFrame(100f, 100f, 1920, 1080, Float.NaN, 90, 0))
    }

    @Test fun thirdsSplitTheRecordedFrameNotTheLetterbox() {
        val frame = PreviewViewport(0f, 300f, 900f, 1800f)
        val shape = subjectGuideShape(frame, SubjectPreviewGuide.THIRDS)
        assertEquals(4, shape.lines.size)
        assertTrue(shape.rects.isEmpty())
        val xs = shape.lines.filter { it.first.first == it.second.first }.map { it.first.first }.sorted()
        val ys = shape.lines.filter { it.first.second == it.second.second }.map { it.first.second }.sorted()
        assertEquals(listOf(300f, 600f), xs)
        assertEquals(900f, ys[0], 0.01f)
        assertEquals(1500f, ys[1], 0.01f)
    }

    @Test fun safeAreasAreCentredNinetyAndEightyPercent() {
        val frame = PreviewViewport(100f, 0f, 1000f, 500f)
        val rects = subjectGuideShape(frame, SubjectPreviewGuide.SAFE_AREA).rects
        assertEquals(2, rects.size)
        assertEquals(PreviewViewport(150f, 25f, 900f, 450f), rects[0])
        assertEquals(PreviewViewport(200f, 50f, 800f, 400f), rects[1])
        assertEquals(SubjectGuideShape(), subjectGuideShape(frame, SubjectPreviewGuide.NONE))
    }

    @Test fun meterMapsPeakOntoSixtyDecibels() {
        assertEquals(0f, subjectMeterFraction(null), 0f)
        assertEquals(0f, subjectMeterFraction(Float.NaN), 0f)
        assertEquals(0f, subjectMeterFraction(-120f), 0f)
        assertEquals(0.5f, subjectMeterFraction(-30f), 0.001f)
        assertEquals(1f, subjectMeterFraction(3f), 0f)
    }
}
