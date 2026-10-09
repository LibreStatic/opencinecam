/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PhotoAspectSettingsTest {
    private val keys = setOf("photo-aspect-enabled", "photo-aspect-width", "photo-aspect-height")
    @Test fun defaultDisabledAndInvalidLocalRatiosRecoverAsOneSelection() {
        assertEquals(PhotoAspectSelection(), CameraSettings().photoAspect)
        for (values in listOf(mapOf("photo-aspect-enabled" to true, "photo-aspect-width" to 0),
            mapOf("photo-aspect-enabled" to true, "photo-aspect-width" to 10001),
            mapOf("photo-aspect-width" to 8, "photo-aspect-height" to 6),
            mapOf("photo-aspect-height" to -1))) {
            assertEquals(PhotoAspectSelection(), CameraSettingsStore(PresetPreferences(values)).load().photoAspect)
        }
    }
    @Test fun customAndPortraitRatiosPersistWithoutTouchingOtherCaptureControls() {
        for (aspect in listOf(PhotoAspectSelection(true, 239, 100), PhotoAspectSelection(true, 9, 16),
            PhotoAspectSelection(false, 17, 11), PhotoAspectSelection(true, 1, 1))) {
            val settings = CameraSettings(photoAspect = aspect, photoQuality = 73, photoFormat = StillPhotoFormat.RAW_JPEG)
            val store = CameraSettingsStore(PresetPreferences()); store.save(settings)
            assertEquals(settings, store.load())
            assertEquals(settings, CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name = "Ratio", settings = settings, mode = CaptureMode.PHOTO))).settings)
        }
    }
    @Test fun versionEightRetainsExact104FieldsAndDefaultsOnlyTheNewThree() {
        val oldSettings = CameraSettings(accumulation = AccumulationSelection(AccumulationMode.WATER), photoQuality = 81)
        val root = Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name = "V8", settings = oldSettings))).jsonObject
        val old = root.getValue("settings").jsonObject.filterKeys { it !in PRESET_V20_KEYS && !it.startsWith("monitor-") && !it.startsWith("audio-recording-gain-") && !it.startsWith("audio-listening-") && !it.startsWith("audio-meter-") && !it.startsWith("slate-") && !it.startsWith("gallery-") && !it.startsWith("media-share-") && !it.startsWith("capture-naming-") && !it.startsWith("playback-") } - keys
        assertEquals(104, old.size); assertEquals(166, CameraPresetCodec.portableKeys.size)
        assertEquals(21, CameraPresetCodec.VERSION)
        assertEquals(oldSettings, CameraPresetCodec.decode(JsonObject(root + mapOf("version" to JsonPrimitive(8), "settings" to JsonObject(old))).toString()).settings)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("version" to JsonPrimitive(8))).toString()) }
    }
    @Test fun malformedPresetRatiosRejectRatherThanSilentlyNormalize() {
        val root = Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name = "Bad", settings = CameraSettings()))).jsonObject
        val settings = root.getValue("settings").jsonObject
        for (mutation in listOf(mapOf("photo-aspect-enabled" to JsonPrimitive("true")),
            mapOf("photo-aspect-width" to JsonPrimitive(0)), mapOf("photo-aspect-height" to JsonPrimitive(10001)),
            mapOf("photo-aspect-width" to JsonPrimitive(8), "photo-aspect-height" to JsonPrimitive(6)),
            mapOf("photo-aspect-width" to JsonPrimitive(4.5)), mapOf("photo-aspect-height" to JsonPrimitive("3")))) {
            assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("settings" to JsonObject(settings + mutation))).toString()) }
        }
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("settings" to JsonObject(settings - "photo-aspect-height"))).toString()) }
    }
    @Test fun pendingAspectDoesNotChangeAnAcceptedCapture() {
        val initial = CameraSettings(operation = OperatorPreferences(lockDuringTake = false))
        val requested = initial.copy(photoAspect = PhotoAspectSelection(true, 16, 9))
        assertEquals(initial, initial.withLivePreferencesFrom(requested))
        for (mode in listOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL)) {
            assertTrue(CameraUiState(selectedMode = mode, stillCapturePending = true, effectiveSettings = initial).structuralSettingsFrozen)
        }
    }
    @Test fun heicProcessingNoticeDoesNotChangeFormatOrStoredCrop() {
        val settings = CameraSettings(photoAspect = PhotoAspectSelection(true), photoFormat = StillPhotoFormat.HEIC)
        assertTrue(photoAspectHeicProcessing(CaptureMode.PHOTO, settings))
        assertFalse(photoAspectHeicProcessing(CaptureMode.BURST, settings))
        assertFalse(photoAspectHeicProcessing(CaptureMode.RAW_PHOTO, settings))
        assertFalse(photoAspectHeicProcessing(CaptureMode.BRACKET, settings))
        assertFalse(photoAspectHeicProcessing(CaptureMode.LIGHT_TRAIL, settings))
        assertFalse(photoAspectHeicProcessing(CaptureMode.VIDEO, settings))
        for (mode in CaptureMode.entries) assertFalse(photoAspectHeicProcessing(mode, CameraSettings()))
    }
    @Test fun searchableSeparatelyFromDisplayGuides() {
        for (query in listOf("aspecto", "crop", "custom", "proporcion", "ancho", "alto"))
            assertTrue("photo-aspect" in SettingsCatalog.search(query, SettingsCategory.RECORDING) { "" })
    }
}
