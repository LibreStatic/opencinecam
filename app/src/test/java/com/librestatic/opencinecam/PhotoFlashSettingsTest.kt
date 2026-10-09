/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PhotoFlashSettingsTest {
    @Test fun legacyTorchDoesNotBecomePhotographicFlash() {
        val store = CameraSettingsStore(PresetPreferences(mapOf("flash-enabled" to true, "torch-strength-level" to 3)))
        val loaded = store.load()
        assertTrue(loaded.flashEnabled); assertEquals(3, loaded.torchStrengthLevel)
        assertEquals(PhotoFlashSelection(), loaded.photoFlash)
    }
    @Test fun everyModeAndLevelPersistsIndependentlyOfTorch() {
        for (selection in listOf(PhotoFlashSelection(), PhotoFlashSelection(PhotoFlashMode.AUTO),
            PhotoFlashSelection(PhotoFlashMode.ON), PhotoFlashSelection(PhotoFlashMode.ON, 4))) {
            val store = CameraSettingsStore(PresetPreferences())
            store.save(CameraSettings(photoFlash = selection, flashEnabled = true, torchStrengthLevel = 2))
            assertEquals(selection, store.load().photoFlash)
            assertTrue(store.load().flashEnabled); assertEquals(2, store.load().torchStrengthLevel)
        }
    }
    @Test fun malformedLocalValuesRestoreOffAndNoLevel() {
        val loaded = CameraSettingsStore(PresetPreferences(mapOf("photo-flash-mode" to "BOGUS", "photo-flash-strength" to -9))).load()
        assertEquals(PhotoFlashSelection(), loaded.photoFlash)
    }
    @Test fun photoPreparationAlwaysLocksStructuralAndDirectCameraControls() {
        val state = CameraUiState(phase=CameraUiPhase.CAPTURING, selectedMode=CaptureMode.PHOTO,
            effectiveSettings=CameraSettings(operation=OperatorPreferences(lockDuringTake=false)))
        assertTrue(state.structuralSettingsFrozen);assertTrue(state.captureControlsLocked)
        assertFalse(state.copy(phase=CameraUiPhase.SAVED).captureControlsLocked)
    }
    @Test fun photoIntentStaysPendingWhileRecordingWithoutChangingTorch() {
        val current = CameraSettings(flashEnabled = true)
        val next = current.copy(photoFlash = PhotoFlashSelection(PhotoFlashMode.ON, 3))
        assertEquals(current, current.withLivePreferencesFrom(next))
    }
    @Test fun currentVersionExportsAndRestoresPhotoIntent() {
        val preset = CameraPreset(name = "Flash", settings = CameraSettings(photoFlash = PhotoFlashSelection(PhotoFlashMode.ON, 3)), mode = CaptureMode.PHOTO)
        val decoded = CameraPresetCodec.decode(CameraPresetCodec.encode(preset))
        assertEquals(21, CameraPresetCodec.VERSION); assertEquals(preset.settings, decoded.settings)
        assertEquals(CaptureMode.PHOTO, decoded.mode)
    }
    @Test fun versionFourPreservesTorchAndDefaultsPhotoFlashOff() {
        val preset = CameraPreset(name = "Legacy", settings = CameraSettings(flashEnabled = true))
        val root = Json.parseToJsonElement(CameraPresetCodec.encode(preset)).jsonObject
        val legacy = JsonObject(root + mapOf("version" to JsonPrimitive(4),
            "settings" to JsonObject(root.getValue("settings").jsonObject.filterKeys { it !in PRESET_V20_KEYS && !it.startsWith("monitor-") && !it.startsWith("audio-recording-gain-") && !it.startsWith("audio-listening-") && !it.startsWith("audio-meter-") && !it.startsWith("slate-") && !it.startsWith("gallery-") && !it.startsWith("media-share-") && !it.startsWith("capture-naming-") && !it.startsWith("playback-") } - setOf("photo-aspect-enabled", "photo-aspect-width", "photo-aspect-height", "bracket-count", "bracket-step", "accumulation-mode", "accumulation-duration-ms", "accumulation-interval-ms", "accumulation-max-edge", "accumulation-stars-threshold", "photo-flash-mode", "photo-flash-strength", "photo-format", "photo-quality"))))
        assertEquals(93, legacy.getValue("settings").jsonObject.size)
        val decoded = CameraPresetCodec.decode(legacy.toString())
        assertTrue(decoded.settings.flashEnabled); assertEquals(PhotoFlashSelection(), decoded.settings.photoFlash)
    }
    @Test fun noncanonicalPresetPhotoValuesAreRejected() {
        val root = Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name = "Photo", settings = CameraSettings()))).jsonObject
        for ((key, value) in listOf("photo-flash-mode" to JsonPrimitive("BOGUS"), "photo-flash-strength" to JsonPrimitive(-1))) {
            val bad = JsonObject(root + ("settings" to JsonObject(root.getValue("settings").jsonObject + (key to value))))
            assertTrue(runCatching { CameraPresetCodec.decode(bad.toString()) }.isFailure)
        }
    }
    @Test fun photoFlashIsSearchableInCaptureCategory() {
        for (query in listOf("photo", "pulso", "precaptura"))
            assertTrue("photo-flash" in SettingsCatalog.search(query, SettingsCategory.RECORDING) { "" })
    }
}
