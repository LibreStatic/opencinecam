/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Size
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
        // Derived from the frozen v2 set, which predates the operator keys, so this keeps testing the
        // migration after later versions add keys instead of silently smuggling them into the payload.
        assertEquals(80, CameraPresetCodec.portableKeysFor(2).size)
        assertTrue(CameraPresetCodec.portableKeysFor(2).none { it.startsWith("operator-") })
        val legacy = JsonObject(root + mapOf("version" to JsonPrimitive(2), "settings" to JsonObject(root.getValue("settings").jsonObject.filterKeys { it in CameraPresetCodec.portableKeysFor(2) })))
        assertEquals(OperatorPreferences(), CameraPresetCodec.decode(legacy.toString()).settings.operation)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("version" to JsonPrimitive(2))).toString()) }
        val invalid = JsonObject(root + ("settings" to JsonObject(root.getValue("settings").jsonObject + ("operator-button-1" to JsonPrimitive("SYSTEM_VOLUME")))))
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(invalid.toString()) }
    }
    @Test fun viewAssistIsOfferedOnlyWhereTheOcLogShaderAppliesIt() {
        assertTrue(operatorActionAvailable(OperatorAction.VIEW_ASSIST, CameraUiState(selectedMode = CaptureMode.LOG)))
        assertTrue(operatorActionAvailable(OperatorAction.VIEW_ASSIST, CameraUiState(selectedMode = CaptureMode.LOG, phase = CameraUiPhase.RECORDING)))
        // Every non-LOG GPU viewfinder is SDR passthrough; its shader ignores the view-assist output mode.
        for (mode in listOf(CaptureMode.VIDEO, CaptureMode.TIME_LAPSE, CaptureMode.PHOTO)) {
            assertFalse(mode.name, operatorActionAvailable(OperatorAction.VIEW_ASSIST, CameraUiState(selectedMode = mode, gpuViewfinder = true)))
            assertFalse(mode.name, operatorActionAvailable(OperatorAction.VIEW_ASSIST, CameraUiState(selectedMode = mode)))
        }
    }
    @Test fun everyToggleActionReportsItsLatchedStateAndMomentaryActionsReportNone() {
        val toggles = setOf(OperatorAction.TORCH, OperatorAction.PEAKING, OperatorAction.ZEBRA, OperatorAction.HISTOGRAM,
            OperatorAction.VIEW_ASSIST, OperatorAction.CONTROL_LOCK)
        val on = CameraSettings(flashEnabled = true, peakingEnabled = true, zebraEnabled = true, histogramEnabled = true,
            logViewAssistEnabled = true, operation = OperatorPreferences(lockDuringTake = true))
        val off = CameraSettings(flashEnabled = false, peakingEnabled = false, zebraEnabled = false, histogramEnabled = false,
            logViewAssistEnabled = false, operation = OperatorPreferences(lockDuringTake = false))
        val state = torchCamera(CaptureMode.LOG)
        for (action in OperatorAction.entries) {
            if (action in toggles) {
                assertEquals(action.name, true, operatorActionToggleState(action, on, state))
                assertEquals(action.name, false, operatorActionToggleState(action, off, state))
            } else {
                // EXTERIOR latches on a display session only the UI observes; it is resolved there.
                assertNull(action.name, operatorActionToggleState(action, on, state))
                assertNull(action.name, operatorActionToggleState(action, off, state))
            }
        }
    }
    @Test fun torchAndViewAssistPillsReportWhatTheEngineDoesNotWhatWasRequested() {
        val requested = CameraSettings(flashEnabled = true, logViewAssistEnabled = true, operation = OperatorPreferences(lockDuringTake = true))
        val video = torchCamera(CaptureMode.VIDEO)
        assertEquals(true, operatorActionToggleState(OperatorAction.TORCH, requested, video))
        // A locked take keeps the torch it started with even after Settings flips the request.
        val locked = video.copy(phase = CameraUiPhase.RECORDING, effectiveSettings = requested.copy(flashEnabled = false))
        assertTrue(locked.captureControlsLocked)
        assertEquals(false, operatorActionToggleState(OperatorAction.TORCH, requested, locked))
        // A camera without a torch, or no camera yet, never lights.
        assertEquals(false, operatorActionToggleState(OperatorAction.TORCH, requested, torchCamera(CaptureMode.VIDEO, torch = false)))
        assertEquals(false, operatorActionToggleState(OperatorAction.TORCH, requested, CameraUiState(selectedMode = CaptureMode.VIDEO)))
        // The constrained high-speed branch shares operatorHighSpeed() with the availability gate; it needs a
        // real android.util.Size (not mocked on the host), so it is not re-exercised here.
        // View assist is requested but only the LOG pipeline applies it.
        assertEquals(false, operatorActionToggleState(OperatorAction.VIEW_ASSIST, requested, video.copy(gpuViewfinder = true)))
        assertEquals(true, operatorActionToggleState(OperatorAction.VIEW_ASSIST, requested, torchCamera(CaptureMode.LOG)))
    }
    private fun torchCamera(mode: CaptureMode, torch: Boolean = true): CameraUiState {
        val descriptor = Camera2CameraDescriptor(
            cameraId = "operator-torch", lensFacing = 0, focalLengthsMm = listOf(4f), previewSize = Size(640, 480),
            jpegSize = null, rawSize = null, analysisSize = null, sensorOrientation = 90, sensitivityRange = null,
            exposureTimeRangeNs = null, aeCompensationRange = null, aeCompensationStep = 0f, minimumFocusDistance = null,
            supportsRaw = false, flashAvailable = torch, targetFpsRanges = emptyList(), availableFixedFps = listOf(30),
            videoProfiles = emptyList(), logProfiles = emptyList(), torchCapabilities = TorchCapabilities(torch, 3, 2),
        )
        return CameraUiState(phase = CameraUiPhase.PREVIEWING, selectedMode = mode, cameras = listOf(descriptor), selectedCameraId = descriptor.cameraId)
    }
}
