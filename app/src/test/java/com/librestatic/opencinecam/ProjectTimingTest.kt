/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam
import com.librestatic.opencinecam.camera.CaptureFrameRate
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class ProjectTimingTest {
    @Test fun rationalPreferencesPersistWithoutOverwritingSensorFps() {
        val settings=CameraSettings(timelapseFps=30000,timelapseFpsDenominator=1001,videoOffSpeed=true,videoProjectNumerator=24000,videoProjectDenominator=1001)
        val memory=PresetPreferences();CameraSettingsStore(memory).save(settings)
        assertEquals(settings,CameraSettingsStore(memory).load());assertEquals(30,settings.videoFps)
        val preset=CameraPreset(name="Project",settings=settings)
        assertEquals(settings,CameraPresetCodec.decode(CameraPresetCodec.encode(preset)).settings)
    }
    @Test fun invalidPairsRestoreOneCoherentDefaultAndNoncanonicalImportsAreRejected() {
        val memory=PresetPreferences(mapOf("timelapse-geometry-fps" to 30000,"timelapse-project-denominator" to 0))
        assertEquals(CaptureFrameRate(30),CameraSettingsStore(memory).load().timelapseProjectRate)
        assertThrows(IllegalArgumentException::class.java) { CameraSettings(timelapseFps=30000) }
        assertThrows(IllegalArgumentException::class.java) { CameraSettings(videoProjectNumerator=60000,videoProjectDenominator=1000) }
        val root=Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name="Bad",settings=CameraSettings()))).jsonObject
        val changed=JsonObject(root+("settings" to JsonObject(root.getValue("settings").jsonObject+mapOf("timelapse-geometry-fps" to JsonPrimitive(30000),"timelapse-project-denominator" to JsonPrimitive(0)))))
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(changed.toString()) }
    }
    @Test fun versionThreeMigrationKeepsExactOldSchemaAndDefaultsToRealtime() {
        val encoded=Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name="Old",settings=CameraSettings()))).jsonObject
        val fields=encoded.getValue("settings").jsonObject.filterKeys { it !in setOf("photo-aspect-enabled", "photo-aspect-width", "photo-aspect-height", "bracket-count", "bracket-step", "accumulation-mode", "accumulation-duration-ms", "accumulation-interval-ms", "accumulation-max-edge", "accumulation-stars-threshold", "photo-flash-mode", "photo-flash-strength", "photo-format", "photo-quality", "timelapse-project-denominator","video-off-speed","video-project-numerator","video-project-denominator") }
        assertEquals(89,fields.size)
        val old=JsonObject(encoded+mapOf("version" to JsonPrimitive(3),"settings" to JsonObject(fields)))
        val migrated=CameraPresetCodec.decode(old.toString()).settings
        assertFalse(migrated.videoOffSpeed);assertEquals(CaptureFrameRate(30),migrated.timelapseProjectRate)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(encoded+("version" to JsonPrimitive(3))).toString()) }
    }
    @Test fun offSpeedMutesOnlyVideoAndNeverChangesTheRememberedAudioPreference() {
        val settings=CameraSettings(audioEnabled=true,videoOffSpeed=true)
        assertTrue(settings.audioEnabled);assertFalse(settings.captureWantsAudio(CaptureMode.VIDEO))
        assertTrue(settings.captureWantsAudio(CaptureMode.LOG));assertFalse(settings.captureWantsAudio(CaptureMode.TIME_LAPSE))
        assertTrue(settings.copy(videoOffSpeed=false).captureWantsAudio(CaptureMode.VIDEO))
    }
    @Test fun timingChangesAreStructuralAndDoNotRetimeAnActiveTake() {
        val old=CameraSettings();val next=old.copy(videoOffSpeed=true,videoProjectNumerator=25,timelapseFps=30000,timelapseFpsDenominator=1001)
        assertEquals(old,old.withLivePreferencesFrom(next))
    }
}
