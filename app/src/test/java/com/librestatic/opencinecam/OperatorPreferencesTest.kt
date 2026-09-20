/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OperatorPreferencesTest {
    @Test fun defaultsRetainSystemVolumeAndNeverRestoreTorchAutomatically() {
        val settings = CameraSettings(flashEnabled = true)
        assertEquals(3, settings.operation.buttons.size)
        assertEquals(OperatorAction.SYSTEM_VOLUME, settings.operation.volumeUp)
        assertEquals(OperatorAction.SYSTEM_VOLUME, settings.operation.volumeDown)
        assertFalse(settings.forOperatorStartup().flashEnabled)
        assertEquals(CaptureMode.PHOTO, settings.operation.startupCaptureMode(CaptureMode.LOG, CaptureMode.entries.toSet()))
    }
    @Test fun everyOperationPreferenceSurvivesCanonicalPersistence() {
        val op = OperatorPreferences(OperatorAction.FOCUS_A, OperatorAction.PRESET_C2, OperatorAction.EXTERIOR,
            OperatorAction.CAPTURE, OperatorAction.NONE, StartupMode.LAST, false, true, true)
        val settings = CameraSettings(operation = op, zebraEnabled = true)
        val memory = PresetPreferences(); CameraSettingsStore(memory).save(settings)
        assertEquals(settings, CameraSettingsStore(memory).load())
        assertThrows(IllegalArgumentException::class.java) { op.copy(button1 = OperatorAction.SYSTEM_VOLUME) }
    }
    @Test fun startupRestorationResetsOnlySelectedCameraIntent() {
        val original = CameraSettings(flashEnabled = true, torchStrengthLevel = 3,
            exposure = ExposureSelection(mode = ExposureMode.MANUAL, iso = 800), whiteBalance = WhiteBalanceSelection.Kelvin(4000, 3), zebraEnabled = true)
        assertEquals(original.copy(flashEnabled = false), original.forOperatorStartup())
        val all = original.copy(operation = original.operation.copy(restoreTorch = true))
        assertEquals(all, all.forOperatorStartup())
        val reset = all.copy(operation = all.operation.copy(restoreExposureWhiteBalance = false)).forOperatorStartup()
        assertEquals(ExposureSelection(), reset.exposure); assertEquals(WhiteBalanceSelection.Auto, reset.whiteBalance)
        assertTrue(reset.flashEnabled); assertEquals(3, reset.torchStrengthLevel); assertTrue(reset.zebraEnabled)
    }
    @Test fun lastModeRequiresAnAvailableGateAndNeverStartsCapture() {
        val op = OperatorPreferences(startupMode = StartupMode.LAST)
        assertEquals(CaptureMode.VIDEO, op.startupCaptureMode(CaptureMode.VIDEO, setOf(CaptureMode.PHOTO, CaptureMode.VIDEO)))
        assertEquals(CaptureMode.PHOTO, op.startupCaptureMode(CaptureMode.LOG, setOf(CaptureMode.PHOTO)))
        assertEquals(CaptureMode.PHOTO, op.startupCaptureMode(null, setOf(CaptureMode.PHOTO)))
    }
    @Test fun lockedTakeDefersCameraIntentButKeepsMonitoringAndUnlockLive() {
        val old = CameraSettings(operation = OperatorPreferences(lockDuringTake = true))
        val requested = old.copy(exposure = old.exposure.copy(iso = 800), whiteBalance = WhiteBalanceSelection.Kelvin(4200, 0), flashEnabled = true,
            torchStrengthLevel = 2, zebraEnabled = true, videoFps = 60)
        val applied = old.withLivePreferencesFrom(requested)
        assertEquals(old.exposure, applied.exposure); assertEquals(old.whiteBalance, applied.whiteBalance)
        assertFalse(applied.flashEnabled); assertNull(applied.torchStrengthLevel); assertTrue(applied.zebraEnabled); assertEquals(30, applied.videoFps)
        val unlocked = applied.withLivePreferencesFrom(requested.copy(operation = requested.operation.copy(lockDuringTake = false)))
        assertEquals(requested.exposure, unlocked.exposure); assertTrue(unlocked.flashEnabled); assertEquals(30, unlocked.videoFps)
    }
    @Test fun lockGatesDirectCameraCommandsNotStopOrMonitoring() {
        val settings = CameraSettings(operation = OperatorPreferences(lockDuringTake = true))
        for (phase in listOf(CameraUiPhase.CAPTURING, CameraUiPhase.RECORDING)) {
            val state = CameraUiState(phase = phase, selectedMode = CaptureMode.VIDEO, effectiveSettings = settings)
            assertTrue(state.captureControlsLocked)
            for (action in listOf(OperatorAction.TORCH, OperatorAction.AUTO_FOCUS, OperatorAction.FOCUS_A, OperatorAction.PRESET_C1)) assertFalse(operatorActionAvailable(action, state))
            assertTrue(operatorActionAvailable(OperatorAction.CONTROL_LOCK, state)); assertTrue(operatorActionAvailable(OperatorAction.ZEBRA, state))
            if (phase == CameraUiPhase.RECORDING) assertTrue(operatorActionAvailable(OperatorAction.CAPTURE, state))
        }
        assertFalse(CameraUiState(phase = CameraUiPhase.PREVIEWING, effectiveSettings = settings).captureControlsLocked)
    }
    @Test fun volumeTriggersOnceAndConsumesItsPairedUpDespiteMappingChanges() {
        val latch = OperatorKeyLatch(); val actions = mutableListOf<OperatorAction>()
        assertTrue(latch.dispatch(24, true, 0, false, true, OperatorAction.CAPTURE, actions::add))
        repeat(10) { assertTrue(latch.dispatch(24, true, it + 1, false, true, OperatorAction.TORCH, actions::add)) }
        assertTrue(latch.dispatch(24, false, 0, false, false, OperatorAction.SYSTEM_VOLUME, actions::add))
        assertEquals(listOf(OperatorAction.CAPTURE), actions)
        assertTrue(latch.dispatch(24, true, 0, false, true, OperatorAction.TORCH, actions::add)); assertEquals(2, actions.size)
    }
    @Test fun systemVolumeCancelledEventsInactiveWindowsAndOrphanRepeatsNeverActivate() {
        val latch = OperatorKeyLatch(); var calls = 0
        assertFalse(latch.dispatch(24, true, 0, false, true, OperatorAction.SYSTEM_VOLUME) { calls++ })
        assertFalse(latch.dispatch(24, true, 0, false, false, OperatorAction.CAPTURE) { calls++ })
        assertFalse(latch.dispatch(24, true, 1, false, true, OperatorAction.CAPTURE) { calls++ })
        assertFalse(latch.dispatch(24, true, 0, true, true, OperatorAction.CAPTURE) { calls++ })
        assertTrue(latch.dispatch(24, true, 0, false, true, OperatorAction.NONE) { calls++ })
        latch.clear(); assertFalse(latch.dispatch(24, false, 0, false, true, OperatorAction.CAPTURE) { calls++ })
        assertEquals(0, calls)
    }
    @Test fun volumeKeysHaveIndependentLatches() {
        val latch = OperatorKeyLatch(); var calls = 0
        for (key in listOf(24, 25)) assertTrue(latch.dispatch(key, true, 0, false, true, OperatorAction.ZEBRA) { calls++ })
        assertEquals(2, calls); latch.clear()
        assertFalse(latch.dispatch(24, true, 3, false, true, OperatorAction.ZEBRA) { calls++ }); assertEquals(2, calls)
    }
    @Test fun operationsRoundTripAndMigrateExactVersionTwoSchema() {
        val preset = CameraPreset(name = "Controls", settings = CameraSettings(operation = OperatorPreferences(volumeUp = OperatorAction.CAPTURE, startupMode = StartupMode.LAST)))
        val encoded = CameraPresetCodec.encode(preset)
        assertEquals(preset.settings, CameraPresetCodec.decode(encoded).settings)
        val root = Json.parseToJsonElement(encoded).jsonObject
        val legacy = JsonObject(root + mapOf("version" to JsonPrimitive(2), "settings" to JsonObject(root.getValue("settings").jsonObject.filterKeys { !it.startsWith("operator-") && it !in setOf("photo-aspect-enabled", "photo-aspect-width", "photo-aspect-height", "bracket-count", "bracket-step", "accumulation-mode", "accumulation-duration-ms", "accumulation-interval-ms", "accumulation-max-edge", "accumulation-stars-threshold", "photo-flash-mode", "photo-flash-strength", "photo-format", "photo-quality", "timelapse-project-denominator", "video-off-speed", "video-project-numerator", "video-project-denominator") })))
        assertEquals(OperatorPreferences(), CameraPresetCodec.decode(legacy.toString()).settings.operation)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("version" to JsonPrimitive(2))).toString()) }
        val invalid = JsonObject(root + ("settings" to JsonObject(root.getValue("settings").jsonObject + ("operator-button-1" to JsonPrimitive("SYSTEM_VOLUME")))))
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(invalid.toString()) }
    }
}
