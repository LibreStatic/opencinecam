/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class SettingsRepositoryTest {
    private class MemoryStore(var saved: CameraSettings = CameraSettings()) : SettingsPersistence {
        var writes = 0
        override fun load() = saved
        override fun save(settings: CameraSettings) { saved = settings; writes++ }
    }

    @Test fun heldWhiteBalanceAndPolicyStayPendingButMonitoringRemainsLive() {
        val held = CameraSettings(recordingWhiteBalance = com.librestatic.opencinecam.camera.RecordingWhiteBalancePolicy.LOCK_ON_RECORD)
        val requested = held.copy(recordingWhiteBalance = com.librestatic.opencinecam.camera.RecordingWhiteBalancePolicy.CONTINUOUS,
            whiteBalance = com.librestatic.opencinecam.camera.WhiteBalanceSelection.Kelvin(4300), zebraEnabled = true)
        val effective = held.withLivePreferencesFrom(requested)
        assertEquals(held.recordingWhiteBalance, effective.recordingWhiteBalance)
        assertEquals(held.whiteBalance, effective.whiteBalance)
        assertTrue(effective.zebraEnabled)
        val continuous = CameraSettings().withLivePreferencesFrom(requested.copy(recordingWhiteBalance = held.recordingWhiteBalance))
        assertEquals(requested.whiteBalance, continuous.whiteBalance)
        assertEquals(com.librestatic.opencinecam.camera.RecordingWhiteBalancePolicy.CONTINUOUS, continuous.recordingWhiteBalance)
    }

    @Test fun restoresTorchIntentAndLevel() {
        val store = MemoryStore(CameraSettings(flashEnabled = true, torchStrengthLevel = 3))
        val repository = SettingsRepository(store)
        assertTrue(repository.states.value.flashEnabled)
        assertEquals(3, repository.states.value.torchStrengthLevel)
        repository.update { it.copy(torchStrengthLevel = 4) }
        assertEquals(4, SettingsRepository(store).states.value.torchStrengthLevel)
    }

    @Test fun updatesComposeWithoutLosingOtherPreferences() {
        val repository = SettingsRepository(MemoryStore())
        repository.update { it.copy(flashEnabled = true) }
        repository.update { it.copy(histogramEnabled = false) }
        assertTrue(repository.states.value.flashEnabled)
        assertFalse(repository.states.value.histogramEnabled)
    }

    @Test fun professionalControlsApplyLiveButKeepRecordingGeometryDeferred() {
        val original = CameraSettings()
        val requested = original.copy(exposure = original.exposure.copy(mode = com.librestatic.opencinecam.camera.ExposureMode.MANUAL, shutterUnit = com.librestatic.opencinecam.camera.ShutterUnit.ANGLE, angleTenths = 900),
            whiteBalance = com.librestatic.opencinecam.camera.WhiteBalanceSelection.Kelvin(4300, 17), videoFps = 60)
        val effective = original.withLivePreferencesFrom(requested)
        assertEquals(requested.exposure, effective.exposure)
        assertEquals(requested.whiteBalance, effective.whiteBalance)
        assertEquals(30, effective.videoFps)
    }

    @Test fun ispPreferencesRemainPendingWhileExposureAndMonitoringChangeLive() {
        val original = CameraSettings()
        val requested = original.copy(imageProcessing = com.librestatic.opencinecam.camera.ImageProcessingSelection(com.librestatic.opencinecam.camera.StabilizationMode.VIDEO,
            com.librestatic.opencinecam.camera.IspMode.HIGH_QUALITY, com.librestatic.opencinecam.camera.IspMode.OFF), histogramEnabled = false)
        val effective = original.withLivePreferencesFrom(requested)
        assertEquals(original.imageProcessing, effective.imageProcessing)
        assertFalse(effective.histogramEnabled)
        assertNotEquals(requested.imageProcessing, effective.imageProcessing)
    }
    @Test fun preparationAndFinalizationFreezeStructuralSettingsButErrorReleasesThem() {
        val state = CameraUiState(selectedMode = CaptureMode.VIDEO)
        assertTrue(state.copy(phase = CameraUiPhase.CAPTURING).structuralSettingsFrozen)
        assertTrue(state.copy(phase = CameraUiPhase.RECORDING).structuralSettingsFrozen)
        assertTrue(state.copy(phase = CameraUiPhase.SAVED, recordingFinalizing = true).structuralSettingsFrozen)
        assertFalse(state.copy(phase = CameraUiPhase.ERROR).structuralSettingsFrozen)
        assertFalse(state.copy(phase = CameraUiPhase.SAVED).structuralSettingsFrozen)
        // Owned still captures have frozen structure since H4; an idle error releases it.
        for (mode in listOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL)) {
            assertTrue(state.copy(selectedMode = mode, phase = CameraUiPhase.CAPTURING).structuralSettingsFrozen)
            assertTrue(state.copy(selectedMode = mode, phase = CameraUiPhase.ERROR, stillCapturePending = true).structuralSettingsFrozen)
            assertFalse(state.copy(selectedMode = mode, phase = CameraUiPhase.ERROR).structuralSettingsFrozen)
        }
    }

    @Test fun unchangedValueDoesNotWriteAgain() {
        val store = MemoryStore()
        SettingsRepository(store).update { it }
        assertEquals(0, store.writes)
    }
}
