/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AccumulationSettingsTest {
    private val keys = setOf("accumulation-mode", "accumulation-duration-ms", "accumulation-interval-ms", "accumulation-max-edge", "accumulation-stars-threshold")
    @Test fun defaultsAndMalformedLocalValuesRecoverWithoutTouchingPhotoSelection() {
        for (values in listOf(emptyMap(), mapOf("accumulation-duration-ms" to 0L),
            mapOf("accumulation-duration-ms" to 1000L, "accumulation-interval-ms" to 1000L),
            mapOf("accumulation-mode" to "BOGUS"), mapOf("accumulation-stars-threshold" to 256))) {
            assertEquals(AccumulationSelection(),CameraSettingsStore(PresetPreferences(values)).load().accumulation)
        }
    }
    @Test fun everyModeAndEdgePersistAndRoundTripPresetWithoutChangingExistingControls() {
        for(mode in AccumulationMode.entries) for(edge in listOf(720,1080,2048)) {
            val value=CameraSettings(accumulation=AccumulationSelection(mode,300000L,10000L,edge,255),
                bracket=BracketSelection(5,BracketStep.ONE_EV),photoFormat=StillPhotoFormat.HEIC,photoQuality=73,
                photoFlash=PhotoFlashSelection(PhotoFlashMode.ON))
            val store=CameraSettingsStore(PresetPreferences());store.save(value);assertEquals(value,store.load())
            assertEquals(value,CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name="Accumulation",settings=value,mode=CaptureMode.LIGHT_TRAIL))).settings)
        }
    }
    @Test fun versionSevenKeepsExact99KeysAndMigratesOnlyTheFiveNewFields() {
        val original=CameraSettings(bracket=BracketSelection(7,BracketStep.THIRD_EV))
        val root=Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name="V7",settings=original))).jsonObject
        val old=root.getValue("settings").jsonObject.filterKeys { it !in PRESET_V20_KEYS && !it.startsWith("monitor-") && !it.startsWith("audio-recording-gain-") && !it.startsWith("audio-listening-") && !it.startsWith("audio-meter-") && !it.startsWith("slate-") && !it.startsWith("gallery-") && !it.startsWith("media-share-") && !it.startsWith("capture-naming-") && !it.startsWith("playback-") }-keys-setOf("photo-aspect-enabled","photo-aspect-width","photo-aspect-height")
        assertEquals(99,old.size);assertEquals(164, CameraPresetCodec.portableKeys.size)
        assertEquals(original,CameraPresetCodec.decode(JsonObject(root+mapOf("version" to JsonPrimitive(7),"settings" to JsonObject(old))).toString()).settings)
        assertThrows(Exception::class.java) {CameraPresetCodec.decode(JsonObject(root+("version" to JsonPrimitive(7))).toString())}
    }
    @Test fun malformedPresetsRejectInsteadOfNormalizingUnknownOrCrossFieldValues() {
        val root=Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name="Bad",settings=CameraSettings()))).jsonObject
        for((key,value) in listOf("accumulation-mode" to JsonPrimitive("HDR"),"accumulation-duration-ms" to JsonPrimitive(0),
            "accumulation-duration-ms" to JsonPrimitive("1000"),"accumulation-duration-ms" to JsonPrimitive(300001),
            "accumulation-interval-ms" to JsonPrimitive(10000),"accumulation-max-edge" to JsonPrimitive(4000),
            "accumulation-stars-threshold" to JsonPrimitive(-1))) {
            assertThrows(Exception::class.java) {CameraPresetCodec.decode(JsonObject(root+("settings" to JsonObject(root.getValue("settings").jsonObject+(key to value)))).toString())}
        }
    }
    @Test fun accumulationIntentIsFrozenEvenWhenOptionalRecordingLockIsOff() {
        val before=CameraSettings(operation=OperatorPreferences(lockDuringTake=false))
        val after=before.copy(accumulation=AccumulationSelection(AccumulationMode.BULB,30000,500,720,32),photoQuality=1)
        assertEquals(before,before.withLivePreferencesFrom(after))
        for(phase in listOf(CameraUiPhase.CAPTURING,CameraUiPhase.PREVIEWING)) {
            val state=CameraUiState(selectedMode=CaptureMode.LIGHT_TRAIL,phase=phase,stillCapturePending=true,effectiveSettings=before)
            assertTrue(state.captureControlsLocked);assertTrue(state.structuralSettingsFrozen)
        }
        assertFalse(CameraUiState(selectedMode=CaptureMode.LIGHT_TRAIL,phase=CameraUiPhase.SAVED).structuralSettingsFrozen)
    }
    @Test fun allNamedModesAndDurationAreSearchable() {
        for(query in listOf("light","water","stars","bulb","acumulacion","duracion","intervalo"))
            assertTrue("accumulation" in SettingsCatalog.search(query,SettingsCategory.RECORDING) {""})
    }
}
