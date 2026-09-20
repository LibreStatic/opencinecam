/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

class SettingsPersistenceTest {
    // The runner executes with the target UID. Use isolated target-owned files, never the
    // instrumentation APK's private directory (its in-memory preferences hide failed disk writes).
    private val preferencesName="settings-persistence-${java.util.UUID.randomUUID()}-camera-settings"
    private val target=InstrumentationRegistry.getInstrumentation().targetContext
    private val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences {
            check(name=="camera-settings")
            return super.getSharedPreferences(preferencesName,mode)
        }
    }
    private val preferences get() = context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE)

    @Before fun reset() { assertTrue(preferences.edit().clear().commit()) }
    @After fun cleanup() {
        assertTrue(preferences.edit().clear().commit())
        assertTrue(target.deleteSharedPreferences(preferencesName))
    }

    @Test fun playbackOptionsSurviveDiskAndRepositoryRecreationWithoutChangingCapture() {
        val repository = SettingsRepository(CameraSettingsStore(context))
        val before = repository.states.value
        val options = PlaybackSettings(muted = true, loop = true, showFramePosition = false)
        repository.update { it.copy(playback = options) }
        assertTrue(preferences.edit().commit())
        val xml = java.io.File(target.applicationInfo.dataDir, "shared_prefs/$preferencesName.xml").readText()
        for (key in listOf("playback-muted", "playback-loop", "playback-show-frame-position")) assertTrue(xml.contains(key))
        assertEquals(before.copy(playback = options), SettingsRepository(CameraSettingsStore(context)).states.value)
        assertTrue(preferences.getBoolean("playback-muted", false))
        assertTrue(preferences.getBoolean("playback-loop", false))
        assertFalse(preferences.getBoolean("playback-show-frame-position", true))
    }

    @Test fun captureNamingSurvivesRepositoryRecreationAndRejectsCorruptTemplatesAsAGroup() {
        val repository=SettingsRepository(CameraSettingsStore(context))
        val naming=CaptureNamingSettings(true,"{camera}_{scene}_T{take}_{date}")
        repository.update { it.copy(captureNaming=naming,productionSlate=ProductionSlateSettings(scene="Named",takeNumber=19)) }
        assertEquals(repository.states.value,SettingsRepository(CameraSettingsStore(context)).states.value)
        assertTrue("Flush async preference writes before checking disk",preferences.edit().commit())
        val bytes=java.io.File(target.applicationInfo.dataDir,"shared_prefs/$preferencesName.xml").readText()
        assertTrue(bytes.contains("capture-naming-template") && bytes.contains("{camera}_{scene}_T{take}_{date}"))
        assertEquals(naming.template,preferences.getString("capture-naming-template",null))
        assertTrue(preferences.getBoolean("capture-naming-enabled",false))
        assertTrue(preferences.edit().putString("capture-naming-template","../{unknown}").commit())
        val loaded=SettingsRepository(CameraSettingsStore(context)).states.value
        assertEquals(CaptureNamingSettings(),loaded.captureNaming)
        assertEquals(repository.states.value.productionSlate,loaded.productionSlate)
    }

    @Test fun recordingWhiteBalancePolicyRoundTripsAndUnknownValuesUseContinuous() {
        val repository = SettingsRepository(CameraSettingsStore(context))
        repository.update { it.copy(recordingWhiteBalance = com.librestatic.opencinecam.camera.RecordingWhiteBalancePolicy.LOCK_ON_RECORD) }
        assertEquals(repository.states.value, SettingsRepository(CameraSettingsStore(context)).states.value)
        preferences.edit().putString("recording-white-balance", "invalid").commit()
        assertEquals(com.librestatic.opencinecam.camera.RecordingWhiteBalancePolicy.CONTINUOUS, CameraSettingsStore(context).load().recordingWhiteBalance)
    }

    @Test fun professionalControlsSurviveRecreationAndInvalidValuesAreBounded() {
        val repository = SettingsRepository(CameraSettingsStore(context))
        repository.update { it.copy(exposure = com.librestatic.opencinecam.camera.ExposureSelection(
            com.librestatic.opencinecam.camera.ExposureMode.SHUTTER_PRIORITY, 800, 10_000_000L,
            com.librestatic.opencinecam.camera.ShutterUnit.ANGLE, 1728, com.librestatic.opencinecam.camera.Antibanding.HZ50),
            whiteBalance = com.librestatic.opencinecam.camera.WhiteBalanceSelection.Kelvin(4300, -17)) }
        assertEquals(repository.states.value, SettingsRepository(CameraSettingsStore(context)).states.value)
        preferences.edit().putString("exposure-mode", "corrupt").putInt("shutter-angle-tenths", -1).putInt("white-balance-tint", 900).commit()
        val restored = CameraSettingsStore(context).load()
        assertEquals(com.librestatic.opencinecam.camera.ExposureMode.AUTO, restored.exposure.mode)
        assertEquals(1, restored.exposure.angleTenths)
        assertEquals(50, (restored.whiteBalance as com.librestatic.opencinecam.camera.WhiteBalanceSelection.Kelvin).tint)
    }

    @Test fun imageProcessingPreferencesSurviveRecreationWithoutChangingAudioEffects() {
        val repository = SettingsRepository(CameraSettingsStore(context))
        repository.update { it.copy(imageProcessing = com.librestatic.opencinecam.camera.ImageProcessingSelection(
            com.librestatic.opencinecam.camera.StabilizationMode.OPTICAL, com.librestatic.opencinecam.camera.IspMode.HIGH_QUALITY,
            com.librestatic.opencinecam.camera.IspMode.OFF), noiseSuppressorEnabled = false) }
        val restored = SettingsRepository(CameraSettingsStore(context)).states.value
        assertEquals(repository.states.value, restored)
        assertFalse(restored.noiseSuppressorEnabled)
        preferences.edit().putString("image-stabilization", "corrupt").putString("image-edge-enhancement", "corrupt").commit()
        val normalized = CameraSettingsStore(context).load()
        assertNull(normalized.imageProcessing.stabilization)
        assertEquals(com.librestatic.opencinecam.camera.IspMode.DEFAULT, normalized.imageProcessing.edge)
        assertEquals(com.librestatic.opencinecam.camera.IspMode.HIGH_QUALITY, normalized.imageProcessing.noiseReduction)
    }

    @Test fun legacyTorchPreferenceKeepsOnStateAndUsesCameraDefaultLevel() {
        preferences.edit().putBoolean("flash-enabled", true).commit()
        val settings = CameraSettingsStore(context).load()
        assertTrue(settings.flashEnabled)
        assertNull(settings.torchStrengthLevel)
    }

    @Test fun preferencesSurviveAStoreAndRepositoryRecreation() {
        val first = SettingsRepository(CameraSettingsStore(context))
        first.update { it.copy(flashEnabled = true, torchStrengthLevel = 4, zebraEnabled = true,
            peakingEnabled = true, timecodeEnabled = true, timecodeNominalFps = 25, timecodeStartHours = 12) }
        val reopened = SettingsRepository(CameraSettingsStore(context))
        assertEquals(first.states.value, reopened.states.value)
    }

    @Test fun subjectPreferencesSurviveRecreationWithoutStartingASession() {
        val repository = SettingsRepository(CameraSettingsStore(context))
        repository.update { it.copy(subjectDisplay = SubjectDisplaySettings(mode = SubjectDisplayMode.TELEPROMPTER,
            prompterText = "Script", operatorCue = "Action", brightness = 0.5f, continueRecordingOnFold = false, swapPanes = true)) }
        assertEquals(repository.states.value, SettingsRepository(CameraSettingsStore(context)).states.value)
    }

    @Test fun invalidStrengthIsReadAsDefaultRatherThanApplied() {
        preferences.edit().putInt("torch-strength-level", -1).commit()
        assertNull(CameraSettingsStore(context).load().torchStrengthLevel)
    }

    @Test fun exteriorPreviewModeMirrorAndColorSurviveRecreation() {
        val repository = SettingsRepository(CameraSettingsStore(context))
        repository.update { it.copy(subjectDisplay = it.subjectDisplay.copy(mode = SubjectDisplayMode.PREVIEW, previewMirror = false, previewViewAssist = false)) }
        assertEquals(repository.states.value, SettingsRepository(CameraSettingsStore(context)).states.value)
    }

    @Test fun selfTimerAndMinimalControlsSurviveRecreation() {
        val repository = SettingsRepository(CameraSettingsStore(context))
        repository.update { it.copy(subjectDisplay = it.subjectDisplay.copy(selfTimerSeconds = 5, selfMinimalControls = false)) }
        assertEquals(repository.states.value, SettingsRepository(CameraSettingsStore(context)).states.value)
        preferences.edit().putInt("self-timer-seconds", 123).commit()
        assertEquals(0, CameraSettingsStore(context).load().subjectDisplay.selfTimerSeconds)
    }
}
