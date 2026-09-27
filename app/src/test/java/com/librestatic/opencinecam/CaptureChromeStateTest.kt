/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
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
        assertEquals(RecordButtonState.RECORDING, recordButtonState(video(CameraUiPhase.RECORDING)))
    }

    @Test fun aClosingFileIsNeverShownAsSaved() {
        assertEquals(RecordButtonState.FINALIZING, recordButtonState(video(CameraUiPhase.RECORDING, finalizing = true)))
        assertEquals(RecordButtonState.FINALIZING, recordButtonState(video(CameraUiPhase.SAVED, finalizing = true)))
        assertEquals(RecordButtonState.SAVED, recordButtonState(video(CameraUiPhase.SAVED)))
    }

    @Test fun errorAndStartupPhasesAreUnavailable() {
        listOf(CameraUiPhase.ERROR, CameraUiPhase.OPENING, CameraUiPhase.PREPARING, CameraUiPhase.READY).forEach {
            assertEquals(it.name, RecordButtonState.UNAVAILABLE, recordButtonState(video(it)))
        }
    }

    @Test fun operatorErrorTextDropsTheExceptionClass() {
        assertEquals(
            "No hardware video/avc Surface encoder accepts 720x1280 at 30 fps.",
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
}
