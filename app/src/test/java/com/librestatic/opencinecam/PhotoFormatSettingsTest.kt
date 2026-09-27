/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PhotoFormatSettingsTest {
    @Test fun defaultsAndMalformedLocalValuesSelectJpeg95() {
        for (values in listOf(emptyMap(), mapOf("photo-format" to "UNKNOWN", "photo-quality" to 0),
            mapOf("photo-format" to "DNG", "photo-quality" to 101))) {
            val loaded = CameraSettingsStore(PresetPreferences(values)).load()
            assertEquals(StillPhotoFormat.JPEG, loaded.photoFormat); assertEquals(95, loaded.photoQuality)
        }
    }
    @Test fun everyFormatAndQualityBoundaryPersistsWithoutChangingFlash() {
        for (format in listOf(StillPhotoFormat.JPEG, StillPhotoFormat.RAW_JPEG, StillPhotoFormat.HEIC)) for (quality in listOf(1, 73, 100)) {
            val settings = CameraSettings(photoFormat=format, photoQuality=quality, photoFlash=PhotoFlashSelection(PhotoFlashMode.AUTO), flashEnabled=true)
            val store = CameraSettingsStore(PresetPreferences()); store.save(settings)
            assertEquals(settings, store.load())
            assertEquals(settings, CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name="Format",settings=settings))).settings)
        }
    }
    @Test fun versionFiveMigratesToJpegWithoutLosingFlashOrTorch() {
        val settings = CameraSettings(photoFlash=PhotoFlashSelection(PhotoFlashMode.ON, 2), flashEnabled=true)
        val current = Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name="Old",settings=settings))).jsonObject
        val fields = current.getValue("settings").jsonObject.filterKeys { !it.startsWith("monitor-") && !it.startsWith("audio-recording-gain-") && !it.startsWith("audio-listening-") && !it.startsWith("audio-meter-") && !it.startsWith("slate-") && !it.startsWith("gallery-") && !it.startsWith("media-share-") && !it.startsWith("capture-naming-") && !it.startsWith("playback-") } - setOf("photo-aspect-enabled", "photo-aspect-width", "photo-aspect-height", "photo-format", "photo-quality", "bracket-count", "bracket-step", "accumulation-mode", "accumulation-duration-ms", "accumulation-interval-ms", "accumulation-max-edge", "accumulation-stars-threshold")
        assertEquals(95, fields.size)
        val legacy = JsonObject(current + mapOf("version" to JsonPrimitive(5), "settings" to JsonObject(fields)))
        assertEquals(settings, CameraPresetCodec.decode(legacy.toString()).settings)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(current+("version" to JsonPrimitive(5))).toString()) }
    }
    @Test fun invalidOrDngOnlyPhotoPresetsAreRejectedRatherThanNormalized() {
        val current = Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name="Bad",settings=CameraSettings()))).jsonObject
        for ((key,value) in listOf("photo-format" to JsonPrimitive("DNG"), "photo-format" to JsonPrimitive("RAW_HEIC"),
            "photo-quality" to JsonPrimitive(0), "photo-quality" to JsonPrimitive(101), "photo-quality" to JsonPrimitive("95"))) {
            val bad = JsonObject(current + ("settings" to JsonObject(current.getValue("settings").jsonObject + (key to value))))
            assertThrows(Exception::class.java) { CameraPresetCodec.decode(bad.toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { CameraSettings(photoFormat=StillPhotoFormat.DNG) }
        assertThrows(IllegalArgumentException::class.java) { CameraSettings(photoQuality=0) }
        assertThrows(IllegalArgumentException::class.java) { CameraSettings(photoQuality=101) }
    }
    @Test fun rawAndCompressedCapturesFreezeFormatQualityAndDirectControls() {
        val current = CameraSettings(photoFormat=StillPhotoFormat.RAW_JPEG, photoQuality=73, operation=OperatorPreferences(lockDuringTake=false))
        val changed = current.copy(photoFormat=StillPhotoFormat.HEIC,photoQuality=1)
        assertEquals(current,current.withLivePreferencesFrom(changed))
        for (mode in listOf(CaptureMode.PHOTO,CaptureMode.RAW_PHOTO)) {
            val state=CameraUiState(phase=CameraUiPhase.CAPTURING,selectedMode=mode,effectiveSettings=current)
            assertTrue(state.structuralSettingsFrozen);assertTrue(state.captureControlsLocked)
            assertFalse(state.copy(phase=CameraUiPhase.SAVED).structuralSettingsFrozen)
        }
    }
    @Test fun formatAndQualityAreSearchable() {
        for(query in listOf("heic","jpeg","calidad","pareja","dng"))
            assertTrue("photo-format" in SettingsCatalog.search(query,SettingsCategory.RECORDING) { "" })
    }
}
