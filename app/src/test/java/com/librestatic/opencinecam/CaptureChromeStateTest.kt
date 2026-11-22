/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.capture.operatorErrorText
import com.librestatic.opencinecam.capture.RecordButtonState
import com.librestatic.opencinecam.capture.recordButtonState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureChromeStateTest {
    private fun video(phase: CameraUiPhase, finalizing: Boolean = false) =
        CameraUiState(phase = phase, selectedMode = CaptureMode.VIDEO, recordingFinalizing = finalizing)

    @Test fun stillModesHaveNoRecordingLifecycle() {
        assertNull(recordButtonState(CameraUiState(phase = CameraUiPhase.RECORDING, selectedMode = CaptureMode.PHOTO)))
    }

    @Test fun recordReadsRecOnlyOnceTheServiceConfirmsRecording() {
        assertEquals(RecordButtonState.READY, recordButtonState(video(CameraUiPhase.PREVIEWING)))
        assertEquals(RecordButtonState.PREPARING, recordButtonState(video(CameraUiPhase.CAPTURING)))
        val pause = com.librestatic.opencinecam.camera.TimelapsePauseStatus(1, paused = true, finished = false, observedAtMs = 0,
            activeElapsedMs = 0, pausedElapsedMs = 0, events = emptyList())
        assertEquals(RecordButtonState.PAUSED, recordButtonState(video(CameraUiPhase.RECORDING).copy(recordingPauseStatus = pause)))
        assertEquals(RecordButtonState.RECORDING, recordButtonState(video(CameraUiPhase.RECORDING).copy(recordingPauseStatus = pause.copy(paused = false))))
        assertEquals(RecordButtonState.RECORDING, recordButtonState(video(CameraUiPhase.RECORDING)))
    }

    @Test fun aClosingFileIsNeverShownAsSaved() {
        assertEquals(RecordButtonState.FINALIZING, recordButtonState(video(CameraUiPhase.RECORDING, finalizing = true)))
        assertEquals(RecordButtonState.FINALIZING, recordButtonState(video(CameraUiPhase.SAVED, finalizing = true)))
        assertEquals(RecordButtonState.SAVED, recordButtonState(video(CameraUiPhase.SAVED)))
    }

    @Test fun aTakeWaitingForItsDriveIsPendingNotSaved() {
        val pending = video(CameraUiPhase.SAVED).copy(takePendingRecovery = true)
        assertEquals(RecordButtonState.PENDING, recordButtonState(pending))
        // The flag only speaks for a saved phase: a new take is READY/RECORDING whatever it still says.
        assertEquals(RecordButtonState.READY, recordButtonState(pending.copy(phase = CameraUiPhase.PREVIEWING)))
        assertEquals(RecordButtonState.RECORDING, recordButtonState(pending.copy(phase = CameraUiPhase.RECORDING)))
    }

    @Test fun errorAndStartupPhasesAreUnavailable() {
        listOf(CameraUiPhase.ERROR, CameraUiPhase.OPENING, CameraUiPhase.PREPARING, CameraUiPhase.READY).forEach {
            assertEquals(it.name, RecordButtonState.UNAVAILABLE, recordButtonState(video(it)))
        }
    }

    @Test fun operatorErrorTextDropsTheExceptionClass() {
        assertEquals(
            "No hardware video/avc Surface encoder accepts 1280×720 at 30 fps.",
            operatorErrorText("java.lang.IllegalStateException: No hardware video/avc Surface encoder accepts 720x1280 at 30 fps."),
        )
        assertEquals("Camera disconnected", operatorErrorText("android.hardware.camera2.CameraAccessException: Camera disconnected"))
        // A bare class name is all there is to show; never reduce it to an empty sheet.
        assertEquals("kotlin.NotImplementedError", operatorErrorText("kotlin.NotImplementedError"))
        assertEquals("java.lang.IllegalStateException:", operatorErrorText("java.lang.IllegalStateException:"))
    }

    @Test fun operatorErrorTextKeepsPlainSentences() {
        assertEquals("Storage is full: free space to continue.", operatorErrorText("Storage is full: free space to continue."))
        assertEquals("", operatorErrorText(""))
    }

    @Test fun onlyAvailableModesAreSelectable() {
        // The selectors used to offer candidates the service then refused, leaving taps unanswered.
        assertTrue(CameraUiState.isModeSelectable(ModeGateState.AVAILABLE))
        assertFalse(CameraUiState.isModeSelectable(ModeGateState.CANDIDATE))
        listOf(ModeGateState.UNSUPPORTED, ModeGateState.FAILED, null).forEach {
            assertFalse(CameraUiState.isModeSelectable(it))
        }
    }

    @Test fun theOisNoticeNeedsMotionDataAVideoModeAndTheLensStabilising() {
        val on = com.librestatic.opencinecam.settings.CameraSettings(gyroflowLogEnabled = true)
        val video = CameraUiState(selectedMode = CaptureMode.VIDEO, reportedOpticalStabilization = 1)
        assertEquals(true, com.librestatic.opencinecam.capture.motionOisNoticeShown(video, on))
        assertEquals(false, com.librestatic.opencinecam.capture.motionOisNoticeShown(video, on.copy(gyroflowLogEnabled = false)))
        assertEquals(false, com.librestatic.opencinecam.capture.motionOisNoticeShown(video.copy(reportedOpticalStabilization = 0), on))
        assertEquals(false, com.librestatic.opencinecam.capture.motionOisNoticeShown(video.copy(reportedOpticalStabilization = null), on))
        assertEquals(false, com.librestatic.opencinecam.capture.motionOisNoticeShown(video.copy(selectedMode = CaptureMode.PHOTO), on))
        assertEquals(true, com.librestatic.opencinecam.settings.motionOisWarningShown(on))
        assertEquals(false, com.librestatic.opencinecam.settings.motionOisWarningShown(on.copy(
            imageProcessing = on.imageProcessing.copy(stabilization = com.librestatic.opencinecam.camera.StabilizationMode.OFF))))
    }
}
