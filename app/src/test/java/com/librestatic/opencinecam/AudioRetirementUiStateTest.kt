/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.RecordingWhiteBalanceStatus
import org.junit.Assert.*
import org.junit.Test

class AudioRetirementUiStateTest {
    @Test fun audioRetirementAloneIsCancelableAndStructuralIntentRemainsFrozen() {
        for (mode in listOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.TIME_LAPSE)) {
            val state = CameraUiState(phase = CameraUiPhase.CAPTURING, selectedMode = mode,
                audioRetirementPending = true, effectiveSettings = CameraSettings(operation = OperatorPreferences(lockDuringTake = false)))
            assertTrue(state.capturePreparationCancelable)
            assertTrue(state.structuralSettingsFrozen)
            assertFalse(state.transferRetirementPending)
            assertFalse(state.whiteBalancePreparing)
        }
    }
    @Test fun aPendingAudioFlagOutsidePreparationDoesNotBecomeCancelPermission() {
        for (phase in CameraUiPhase.entries.filter { it != CameraUiPhase.CAPTURING })
            assertFalse(CameraUiState(phase = phase, audioRetirementPending = true).capturePreparationCancelable)
        assertFalse(CameraUiState(phase = CameraUiPhase.CAPTURING).capturePreparationCancelable)
    }
    @Test fun clearingAudioWaitPreservesIndependentTransferAndWhiteBalanceGates() {
        val state = CameraUiState(phase = CameraUiPhase.CAPTURING, selectedMode = CaptureMode.VIDEO,
            audioRetirementPending = true, transferRetirementPending = true)
        assertTrue(state.copy(audioRetirementPending = false).capturePreparationCancelable)
        val whiteBalance = state.copy(transferRetirementPending = false,
            recordingWhiteBalanceStatus = RecordingWhiteBalanceStatus.CONVERGING)
        assertTrue(whiteBalance.copy(audioRetirementPending = false).capturePreparationCancelable)
        assertFalse(state.copy(audioRetirementPending = false, transferRetirementPending = false).capturePreparationCancelable)
    }
}
