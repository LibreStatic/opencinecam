/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BracketSettingsTest {
    @Test fun legacyAndMalformedPreferencesUseThreeTwoEvExposures() {
        for (values in listOf(emptyMap(),mapOf("bracket-count" to 4,"bracket-step" to "BOGUS")))
            assertEquals(BracketSelection(),CameraSettingsStore(PresetPreferences(values)).load().bracket)
    }
    @Test fun everyCountAndStepRoundTripsWithoutChangingPhotoFormatQualityOrFlash() {
        for (count in listOf(3,5,7,9)) for(step in BracketStep.entries) {
            val settings=CameraSettings(bracket=BracketSelection(count,step),photoFormat=StillPhotoFormat.HEIC,photoQuality=73,photoFlash=PhotoFlashSelection(PhotoFlashMode.AUTO))
            val store=CameraSettingsStore(PresetPreferences());store.save(settings)
            assertEquals(settings,store.load())
            assertEquals(settings,CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name="Bracket",settings=settings,mode=CaptureMode.BRACKET))).settings)
        }
    }
    @Test fun versionSixKeepsExact97FieldsAndMigratesBracketDefaults() {
        val settings=CameraSettings(photoFormat=StillPhotoFormat.RAW_JPEG,photoQuality=73)
        val current=Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name="V6",settings=settings))).jsonObject
        val fields=current.getValue("settings").jsonObject.filterKeys { !it.startsWith("monitor-") && !it.startsWith("audio-recording-gain-") && !it.startsWith("audio-listening-") && !it.startsWith("audio-meter-") && !it.startsWith("slate-") && !it.startsWith("gallery-") && !it.startsWith("media-share-") && !it.startsWith("capture-naming-") && !it.startsWith("playback-") }-setOf("photo-aspect-enabled", "photo-aspect-width", "photo-aspect-height", "bracket-count","bracket-step","accumulation-mode","accumulation-duration-ms","accumulation-interval-ms","accumulation-max-edge","accumulation-stars-threshold")
        assertEquals(97,fields.size);assertEquals(161,CameraPresetCodec.portableKeys.size)
        val old=JsonObject(current+mapOf("version" to JsonPrimitive(6),"settings" to JsonObject(fields)))
        assertEquals(settings,CameraPresetCodec.decode(old.toString()).settings)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(current+("version" to JsonPrimitive(6))).toString()) }
    }
    @Test fun noncanonicalPresetsRejectInsteadOfClampingCountOrStep() {
        val current=Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name="Invalid",settings=CameraSettings()))).jsonObject
        for((key,value) in listOf("bracket-count" to JsonPrimitive(4),"bracket-count" to JsonPrimitive(10),"bracket-count" to JsonPrimitive("3"),"bracket-step" to JsonPrimitive("HDR"))) {
            val bad=JsonObject(current+("settings" to JsonObject(current.getValue("settings").jsonObject+(key to value))))
            assertThrows(Exception::class.java) {CameraPresetCodec.decode(bad.toString())}
        }
    }
    @Test fun bracketFreezesCameraIntentUntilAllFilesArePublished() {
        val current=CameraSettings(bracket=BracketSelection(5,BracketStep.ONE_EV),operation=OperatorPreferences(lockDuringTake=false))
        val changed=current.copy(bracket=BracketSelection(9,BracketStep.THIRD_EV),photoQuality=1)
        assertEquals(current,current.withLivePreferencesFrom(changed))
        for(phase in listOf(CameraUiPhase.CAPTURING,CameraUiPhase.PREVIEWING)) {
            val state=CameraUiState(selectedMode=CaptureMode.BRACKET,phase=phase,stillCapturePending=true,effectiveSettings=current)
            assertTrue(state.captureControlsLocked);assertTrue(state.structuralSettingsFrozen)
        }
        assertFalse(CameraUiState(selectedMode=CaptureMode.BRACKET,phase=CameraUiPhase.SAVED).structuralSettingsFrozen)
    }
    @Test fun searchFindsCountStepAndSeparateExposurePolicy() {
        for(query in listOf("horquillado","bracket","paso","exposiciones","hdr"))
            assertTrue("bracket" in SettingsCatalog.search(query,SettingsCategory.RECORDING) {""})
    }
}
