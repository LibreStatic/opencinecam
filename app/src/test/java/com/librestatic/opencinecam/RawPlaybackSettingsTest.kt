/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RawPlaybackSettingsTest {
    @Test fun rawPlaybackModePersistsLocallyButStaysOutOfPresets() {
        assertEquals(RawPlaybackMode.AUTOMATIC, PlaybackSettings().rawPlayback)
        val original = CameraSettings(playback = PlaybackSettings(loop = true, rawPlayback = RawPlaybackMode.FRAME_VIEWER))
        val memory = PresetPreferences(); val store = CameraSettingsStore(memory); store.save(original)
        assertEquals(original.playback, store.load().playback)
        assertEquals("FRAME_VIEWER", memory.all["review-raw-playback"])
        assertFalse("review-raw-playback" in CameraPresetCodec.portableKeys)
        val encoded = CameraPresetCodec.encode(CameraPreset(name = "Raw", settings = original))
        assertFalse(encoded.contains("review-raw-playback"))
        assertFalse("review-raw-playback" in Json.parseToJsonElement(encoded).jsonObject.getValue("settings").jsonObject)
        assertEquals(RawPlaybackMode.AUTOMATIC, CameraPresetCodec.decode(encoded).settings.playback.rawPlayback)
        // Applying a preset keeps this device's choice either way.
        assertEquals(RawPlaybackMode.FRAME_VIEWER, CameraPresetCodec.mergeLocal(CameraSettings(), original).playback.rawPlayback)
        assertEquals(RawPlaybackMode.AUTOMATIC, CameraPresetCodec.mergeLocal(original, CameraSettings()).playback.rawPlayback)
    }

    @Test fun anUnknownStoredModeReadsAsAutomatic() {
        val stored = CameraSettingsStore(PresetPreferences(mapOf("review-raw-playback" to "SLOW_MOTION", "playback-loop" to true))).load()
        assertEquals(RawPlaybackMode.AUTOMATIC, stored.playback.rawPlayback)
        assertEquals(true, stored.playback.loop)
    }
}
