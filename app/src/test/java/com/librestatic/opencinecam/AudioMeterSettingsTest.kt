/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.AudioChannelLevel
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AudioMeterSettingsTest {
    private val keys = setOf("audio-meter-visible", "audio-meter-mode", "audio-meter-vu-reference", "audio-meter-peak-hold-ms", "audio-meter-show-values")
    private fun document(settings: CameraSettings = CameraSettings()) = Json.parseToJsonElement(
        CameraPresetCodec.encode(CameraPreset(name = "Meter", settings = settings))).jsonObject

    @Test fun defaultsAndEveryReferenceRoundTripWithAllModesAndBoundaryHoldTimes() {
        assertEquals(AudioMeterSettings(), CameraSettingsStore(PresetPreferences()).load().audioMeter)
        for (mode in AudioMeterMode.entries) for (reference in -24..-6) for (hold in listOf(0, 1, 1500, 2999, 3000)) {
            val value = CameraSettings(audioMeter = AudioMeterSettings(false, mode, reference, hold, true),
                audioRecordingGain = DigitalRecordingGain(true, -9), audioInputKey = com.librestatic.opencinecam.media.audio.AudioInputKey(22, "Lav", "card=12"))
            val store = CameraSettingsStore(PresetPreferences()); store.save(value)
            assertEquals(value, store.load())
            assertEquals(value.audioMeter, CameraPresetCodec.decode(document(value).toString()).settings.audioMeter)
        }
    }
    @Test fun malformedLocalGroupUsesDefaultsAndConstructorsRejectOutsideRanges() {
        for (value in listOf(-25, -5)) assertThrows(IllegalArgumentException::class.java) { AudioMeterSettings(vuReferenceDbfs = value) }
        for (value in listOf(-1, 3001)) assertThrows(IllegalArgumentException::class.java) { AudioMeterSettings(peakHoldMs = value) }
        for (values in listOf(mapOf("audio-meter-mode" to "MAGIC"), mapOf("audio-meter-vu-reference" to -25),
            mapOf("audio-meter-peak-hold-ms" to 3001), mapOf("audio-meter-visible" to "true"))) {
            assertEquals(AudioMeterSettings(), CameraSettingsStore(PresetPreferences(values)).load().audioMeter)
        }
    }
    @Test fun versionEighteenHas160ExactPortableKeysAndVersionTwelveMigratesFiveDefaults() {
        val source = CameraSettings(audioListening = AudioListeningSettings(true, 36))
        val root = document(source)
        assertEquals(20, CameraPresetCodec.VERSION); assertEquals(164, CameraPresetCodec.portableKeys.size)
        assertTrue(CameraPresetCodec.portableKeys.containsAll(keys))
        val historical = root.getValue("settings").jsonObject.filterKeys { it !in PRESET_V20_KEYS && !it.startsWith("slate-") && !it.startsWith("gallery-") && !it.startsWith("media-share-") && !it.startsWith("capture-naming-") && !it.startsWith("playback-") } - keys
        assertEquals(132, historical.size)
        val legacy = JsonObject(root + mapOf("version" to JsonPrimitive(12), "settings" to JsonObject(historical)))
        assertEquals(source, CameraPresetCodec.decode(legacy.toString()).settings)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("version" to JsonPrimitive(12))).toString()) }
    }
    @Test fun portableTypesUnknownModesBoundsAndMissingKeysAreRejectedWithoutCoercion() {
        val root = document()
        for ((key, value) in listOf("audio-meter-mode" to JsonPrimitive("MAGIC"), "audio-meter-visible" to JsonPrimitive("true"),
            "audio-meter-vu-reference" to JsonPrimitive(-25), "audio-meter-vu-reference" to JsonPrimitive(-5),
            "audio-meter-peak-hold-ms" to JsonPrimitive(3001), "audio-meter-peak-hold-ms" to JsonPrimitive(1.5),
            "audio-meter-show-values" to JsonPrimitive(1))) {
            assertThrows(key, Exception::class.java) { CameraPresetCodec.decode(JsonObject(root +
                ("settings" to JsonObject(root.getValue("settings").jsonObject + (key to value)))).toString()) }
        }
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root +
            ("settings" to JsonObject(root.getValue("settings").jsonObject - "audio-meter-mode"))).toString()) }
    }
    @Test fun meterIsLiveWithoutChangingFrozenInputGainOrListeningAndFailedSaveIsNotPublished() {
        val current = CameraSettings(audioRecordingGain = DigitalRecordingGain(true, -6), audioInputDeviceId = 123)
        val next = current.copy(audioMeter = AudioMeterSettings(false, AudioMeterMode.VU, -12, 0, true),
            audioRecordingGain = DigitalRecordingGain(true, 18), audioInputDeviceId = 456)
        val effective = current.withLivePreferencesFrom(next)
        assertEquals(next.audioMeter, effective.audioMeter)
        assertEquals(current.audioRecordingGain, effective.audioRecordingGain)
        assertEquals(current.audioInputDeviceId, effective.audioInputDeviceId)
        assertEquals(current.audioListening, effective.audioListening)
        val persistence = object : SettingsPersistence {
            override fun load() = current
            override fun save(settings: CameraSettings) { error("write failure") }
        }
        val repository = SettingsRepository(persistence)
        assertThrows(IllegalStateException::class.java) { repository.update { next } }
        assertEquals(current, repository.states.value)
        assertTrue("audio-format" in SettingsCatalog.search("PPM", SettingsCategory.AUDIO) { "" })
    }
    @Test fun currentPcmRequiresActiveValidTimeAndBoundedChannels() {
        val sample = AudioLevelSnapshot(listOf(AudioChannelLevel(-12f, -15f)), false, 1000L)
        assertSame(sample, currentAudioMeterSnapshot(sample, true, 1500))
        assertNull(currentAudioMeterSnapshot(sample, true, 1501))
        assertNull(currentAudioMeterSnapshot(sample, true, 999))
        assertNull(currentAudioMeterSnapshot(sample, false, 1000))
        assertNull(currentAudioMeterSnapshot(sample.copy(capturedAtElapsedRealtimeMs = -1), true, 0))
        assertNull(currentAudioMeterSnapshot(sample.copy(channels = emptyList()), true, 1000))
        assertNull(currentAudioMeterSnapshot(sample.copy(channels = List(3) { sample.channels.single() }), true, 1000))
    }
    @Test fun vuAndPpmNeverBorrowInstantaneousRmsAndReferenceOnlyChangesVu() {
        val source = AudioChannelLevel(-4f, -7f, vuDbfs = -18f, ppmDbfs = -10f)
        assertEquals(-4f, AudioMeterSettings().displayDb(source)!!, 0f)
        assertEquals(0f, AudioMeterSettings(mode = AudioMeterMode.VU).displayDb(source)!!, 0f)
        assertEquals(-6f, AudioMeterSettings(mode = AudioMeterMode.VU, vuReferenceDbfs = -12).displayDb(source)!!, 0f)
        assertEquals(-10f, AudioMeterSettings(mode = AudioMeterMode.PPM).displayDb(source)!!, 0f)
        assertNull(AudioMeterSettings(mode = AudioMeterMode.VU).displayDb(AudioChannelLevel(-4f, -7f)))
        assertNull(AudioMeterSettings(mode = AudioMeterMode.PPM).displayDb(source.copy(ppmDbfs = Float.NaN)))
    }
    @Test fun holdIsIndependentPerChannelAndDoesNotRefreshOnUiRedrawOrSurviveReset() {
        val hold = AudioMeterPeakHold()
        assertEquals(listOf(-3f, -24f), hold.observe(100, 100, listOf(-3f, -24f), 1000))
        assertEquals(listOf(-3f, -12f), hold.observe(200, 200, listOf(-30f, -12f), 1000))
        assertEquals(listOf(-3f, -12f), hold.observe(200, 1099, listOf(-30f, -12f), 1000))
        assertEquals(listOf(-30f, -12f), hold.observe(200, 1100, listOf(-30f, -12f), 1000))
        assertEquals(listOf(-50f, -51f), hold.observe(300, 1101, listOf(-50f, -51f), 0))
        hold.clear()
        assertEquals(listOf(-60f), hold.observe(400, 1102, listOf(-60f), 1500))
        assertEquals(listOf(null), hold.observe(401, 1103, listOf(null), 1500))
    }
    @Test fun holdHandlesTimestampRegressionAndLongBoundaryWithoutWrap() {
        val hold = AudioMeterPeakHold()
        hold.observe(1000, 1000, listOf(0f), 3000)
        assertEquals(listOf(-20f), hold.observe(999, 999, listOf(-20f), 3000))
        hold.clear()
        assertEquals(listOf(-5f), hold.observe(Long.MAX_VALUE - 10, Long.MAX_VALUE - 10, listOf(-5f), 3000))
        assertEquals(listOf(-5f), hold.observe(Long.MAX_VALUE - 9, Long.MAX_VALUE - 9, listOf(-30f), 3000))
    }
}
