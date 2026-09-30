/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CameraPresetTest {
    private fun preset() = CameraPreset(name = "Noche", settings = CameraSettings(
        timecodeRememberPosition = false, timecodeResetRevision = 7,
        exposure = ExposureSelection(ExposureMode.MANUAL, 800, shutterUnit = ShutterUnit.ANGLE, angleTenths = 900),
        whiteBalance = WhiteBalanceSelection.Kelvin(4300, 17), recordingWhiteBalance = RecordingWhiteBalancePolicy.LOCK_ON_RECORD,
        imageProcessing = ImageProcessingSelection(StabilizationMode.VIDEO, IspMode.HIGH_QUALITY, IspMode.OFF),
        audioInputDeviceId = 12345, subjectDisplay = SubjectDisplaySettings(prompterText = "private script", operatorCue = "private cue", brightness = 0.4f),
        modeSelectorStyle = ModeSelectorStyle.BUTTONS, videoFps = 60), focusDiopters = 2.5f, zoomRatio = 2f)
    private fun document(change: (MutableMap<String, JsonElement>) -> Unit): String {
        val map = Json.parseToJsonElement(CameraPresetCodec.encode(preset())).jsonObject.toMutableMap(); change(map); return JsonObject(map).toString()
    }
    private fun invalid(text: String) { assertThrows(Exception::class.java) { CameraPresetCodec.decode(text) } }
    @Test fun completePortableSettingsRoundTripAndLocalValuesAreExcluded() {
        val original = preset(); val text = CameraPresetCodec.encode(original); val decoded = CameraPresetCodec.decode(text)
        assertEquals(original.settings, CameraPresetCodec.mergeLocal(decoded.settings, original.settings))
        assertNull(decoded.settings.audioInputDeviceId)
        assertEquals("", decoded.settings.subjectDisplay.prompterText)
        for (secret in listOf(original.id, "12345", "private script", "private cue", "audio-input-device-id")) assertFalse(text.contains(secret))
        assertEquals(original.mode, decoded.mode); assertEquals(original.focusDiopters, decoded.focusDiopters); assertEquals(original.zoomRatio, decoded.zoomRatio)
        assertFalse(text.contains("timecode-remember-position")); assertFalse(text.contains("timecode-reset-revision"))
        assertEquals(164, CameraPresetCodec.portableKeys.size)
        assertFalse(text.contains("proxy-max-long-edge")); assertFalse(text.contains("proxy-video-bitrate-mbps"))
        val memory = PresetPreferences(); CameraSettingsStore(memory).save(CameraSettings())
        assertEquals(memory.all.keys - setOf("audio-input-device-id", "audio-listening-output-device-id", "subject-script", "subject-cue", "audio-aac-log-migrated-v1", "mode-selector-carousel-migrated-v1", "timecode-remember-position", "timecode-reset-revision", "geotagging-enabled", "proxy-max-long-edge", "proxy-video-bitrate-mbps"), CameraPresetCodec.portableKeys)
        println("PRESET_V20_KEYS=" + CameraPresetCodec.portableKeys.size)
    }
    @Test fun versionOneMigratesMissingSettingsAndCaptureIntentWithoutAppPreferenceMigrations() {
        val text = """{"format":"OpenCineCamPreset","version":1,"name":"Draft","settings":{"zebra-enabled":true,"audio-output-format":"WAV_PCM","mode-selector-style":"BUTTONS"}}"""
        val decoded = CameraPresetCodec.decode(text)
        assertTrue(decoded.settings.zebraEnabled)
        assertEquals(com.librestatic.opencinecam.media.audio.AudioOutputFormat.WAV_PCM, decoded.settings.audioOutputFormat)
        assertEquals(ModeSelectorStyle.BUTTONS, decoded.settings.modeSelectorStyle)
        assertEquals(RecordingWhiteBalancePolicy.CONTINUOUS, decoded.settings.recordingWhiteBalance)
        assertEquals(CaptureMode.VIDEO, decoded.mode); assertEquals(1f, decoded.zoomRatio); assertNull(decoded.focusDiopters)
    }
    @Test fun unknownVersionFieldsAndPhysicalRoutingAreRejected() {
        invalid(document { it["version"] = JsonPrimitive(999) })
        invalid(document { it["version"] = JsonPrimitive("2") })
        invalid(document { it["credential"] = JsonPrimitive("token") })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("audio-input-device-id" to JsonPrimitive(7))) })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject - "video-geometry-fps") })
    }
    @Test fun invalidTypesRangesEnumsAndPartialCanonicalFieldsAreRejected() {
        for ((key,value) in listOf("video-geometry-fps" to JsonPrimitive(-1), "video-geometry-width" to JsonPrimitive(999999),
            "exposure-iso" to JsonPrimitive("800"), "image-edge-enhancement" to JsonPrimitive("MAGIC"), "subject-brightness" to JsonPrimitive(2))) {
            invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + (key to value)) })
        }
        invalid(document { it["name"] = JsonPrimitive(" ") })
        invalid(document { it["name"] = JsonPrimitive("a".repeat(65)) })
        invalid(document { it["zoomRatio"] = JsonPrimitive(-1) })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + mapOf("timecode-nominalfps" to JsonPrimitive(24), "timecode-dropframe" to JsonPrimitive(true))) })
        invalid(document { it["settings"] = JsonObject(it.getValue("settings").jsonObject + ("timecode-startframes" to JsonPrimitive(59))) })
    }
    @Test fun duplicateKeysIncludingEscapedNamesAndNestedArrayBombAreRejected() {
        val canonical = CameraPresetCodec.encode(preset())
        val version = "\"version\":${CameraPresetCodec.VERSION}"
        assertTrue("Duplicate-key fixture must find the actual version field", canonical.contains(version))
        val duplicate = canonical.replace(version, "$version,\"version\":1")
        assertNotEquals(canonical, duplicate)
        invalid(duplicate)
        invalid(CameraPresetCodec.encode(preset()).replace("\"name\":", "\"na\\u006de\":\"Duplicate\",\"name\":"))
        invalid("[".repeat(10000) + "]".repeat(10000))
        invalid(" ".repeat(CameraPresetCodec.MAX_BYTES + 1))
    }
    @Test fun boundedInputRejectsTooMuchDataAndInvalidUtf8() {
        assertThrows(Exception::class.java) { readPresetDocument(java.io.ByteArrayInputStream(ByteArray(CameraPresetCodec.MAX_BYTES + 1))) }
        assertThrows(Exception::class.java) { readPresetDocument(java.io.ByteArrayInputStream(byteArrayOf(0xC3.toByte()))) }
        assertEquals("{}", readPresetDocument(java.io.ByteArrayInputStream("\uFEFF{}".toByteArray())))
    }
    @Test fun differencesAreExplicitAndPrivateValuesNeverBecomePresetChanges() {
        val changes = CameraPresetCodec.differences(CameraSettings(), preset().settings)
        assertTrue(changes.any { it.startsWith("exposure-iso:") && it.contains("800") })
        assertFalse(changes.any { it.contains("script") || it.contains("device-id") })
        assertEquals(listOf("camera"), preset().compatibilityIssues(CameraUiState()))
    }
    private class Memory(var raw: String? = null) : PresetPersistence {
        var failWrite = false
        override fun read() = raw
        override fun write(value: String) { check(!failWrite); raw = value }
    }
    @Test fun librarySaveUpdateRenameDeleteAndSlotsSurviveRecreation() {
        val memory = Memory(); val repository = PresetRepository(memory); val p = preset()
        repository.save(p); repository.assign("C1", p.id); repository.assign("C2", p.id)
        repository.save(p.copy(name = "Otra"))
        assertEquals(repository.states.value, PresetRepository(memory).states.value)
        repository.delete(p.id)
        assertTrue(repository.states.value.presets.isEmpty()); assertTrue(repository.states.value.slots.isEmpty())
    }
    @Test fun duplicateNamesLimitsAndFailedWritesDoNotMutateLibrary() {
        val memory = Memory(); val repository = PresetRepository(memory); val p = preset(); repository.save(p)
        assertThrows(Exception::class.java) { repository.save(p.copy(id = "different", name = "NOCHE")) }
        val before = repository.states.value
        memory.failWrite = true
        assertThrows(Exception::class.java) { repository.delete(p.id) }
        assertEquals(before, repository.states.value)
        memory.failWrite = false
        repeat(31) { repository.save(CameraPreset(name = "Preset $it", settings = CameraSettings())) }
        assertThrows(Exception::class.java) { repository.save(CameraPreset(name = "Overflow", settings = CameraSettings())) }
    }
    @Test fun unreadableLibraryIsRetainedUntilExplicitReset() {
        val memory = Memory("broken"); val repository = PresetRepository(memory)
        assertNotNull(repository.states.value.error)
        assertThrows(Exception::class.java) { repository.save(preset()) }
        assertEquals("broken", memory.raw)
        repository.reset(); assertNull(PresetRepository(memory).states.value.error)
    }
    @Test fun structuralPresetValuesStayPendingDuringRecording() {
        val current = CameraSettings(recordingWhiteBalance = RecordingWhiteBalancePolicy.LOCK_ON_RECORD)
        val preset = preset().settings.copy(zebraEnabled = true, videoWidth = 3840)
        val effective = current.withLivePreferencesFrom(preset)
        assertEquals(current.videoWidth, effective.videoWidth)
        assertEquals(current.whiteBalance, effective.whiteBalance)
        assertEquals(current.imageProcessing, effective.imageProcessing)
        assertEquals(preset.exposure, effective.exposure); assertTrue(effective.zebraEnabled)
    }
}

/** Keys added in preset v20; historical fixtures strip them to rebuild older payloads. */
internal val PRESET_V20_KEYS = setOf("translucent-chrome", "viewfinder-scale", "chrome-opacity")
