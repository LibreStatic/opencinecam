/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Size
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PresetUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Memory : PresetPersistence {
        var raw: String? = null
        override fun read() = raw
        override fun write(value: String) { raw = value }
    }
    private fun state() = CameraUiState(phase = CameraUiPhase.PREVIEWING, selectedMode = CaptureMode.VIDEO, selectedCameraId = "fixture",
        cameras = listOf(Camera2CameraDescriptor(cameraId = "fixture", lensFacing = 0, focalLengthsMm = listOf(4f), previewSize = Size(640, 480),
            jpegSize = null, rawSize = null, analysisSize = null, sensorOrientation = 90, sensitivityRange = null, exposureTimeRangeNs = null,
            aeCompensationRange = null, aeCompensationStep = 0f, minimumFocusDistance = null, supportsRaw = false, flashAvailable = false,
            targetFpsRanges = emptyList(), availableFixedFps = listOf(30), videoProfiles = listOf(Camera2VideoProfile(Size(640, 480), 30, false)), logProfiles = emptyList())))
    private fun content(repository: PresetRepository, settings: CameraSettings = CameraSettings(), scale: Float = 1f) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(420.dp, 740.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(scale)) {
                    MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        PresetSettings(state(), settings, {}, repository)
                    } }
                }
            }
        }
    }
    @Test fun namedSnapshotAndSlotAreSavedAtDoubleFont() {
        val repository = PresetRepository(Memory())
        val settings = CameraSettings(zebraEnabled = true, recordingWhiteBalance = RecordingWhiteBalancePolicy.LOCK_ON_RECORD)
        content(repository, settings, 2f)
        compose.onNodeWithTag("preset-name").performScrollTo().performTextInput("Ensayo")
        compose.onNodeWithText(context.getString(R.string.presets_save_new)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil { repository.states.value.presets.size == 1 }
        assertEquals(settings, repository.states.value.presets.single().settings)
        compose.onNodeWithTag("preset-row-${repository.states.value.presets.single().id}").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("preset-action-slot-C1").performClick()
        assertEquals(repository.states.value.presets.single().id, repository.states.value.slots["C1"])
    }
    @Test fun renameDoesNotReplaceSavedSettingsWithCurrentConfiguration() {
        val repository = PresetRepository(Memory()); val p = CameraPreset(name = "Original", settings = CameraSettings(zebraEnabled = true))
        repository.save(p); content(repository, CameraSettings(zebraEnabled = false))
        compose.onNodeWithTag("preset-row-${p.id}").performScrollTo().performClick()
        compose.onNodeWithTag("preset-action-rename").performClick()
        compose.onNodeWithTag("preset-dialog-name").performTextReplacement("Renamed")
        compose.onNodeWithText(context.getString(R.string.presets_confirm_save)).performClick()
        compose.waitUntil { repository.states.value.presets.single().name == "Renamed" }
        assertTrue(repository.states.value.presets.single().settings.zebraEnabled)
        assertEquals(p.id, repository.states.value.presets.single().id)
    }
    @Test fun updateReplacesCurrentSnapshotOnlyAfterConfirmation() {
        val repository = PresetRepository(Memory()); val p = CameraPreset(name = "Original", settings = CameraSettings())
        repository.save(p); content(repository, CameraSettings(peakingEnabled = true))
        compose.onNodeWithTag("preset-row-${p.id}").performScrollTo().performClick()
        compose.onNodeWithTag("preset-action-update").performClick()
        assertFalse(repository.states.value.presets.single().settings.peakingEnabled)
        compose.onNodeWithText(context.getString(R.string.presets_confirm_save)).performClick()
        compose.waitUntil { repository.states.value.presets.single().settings.peakingEnabled }
    }
    @Test fun quickSlotAlwaysReviewsAndDoesNotStartOrApplyOnFirstTap() {
        val repository = PresetRepository(Memory()); val p = CameraPreset(name = "Slot", settings = CameraSettings(zebraEnabled = true))
        repository.save(p); repository.assign("C1", p.id)
        var applied: CameraPreset? = null
        compose.setContent { MaterialTheme { PresetQuickAccess(state(), CameraSettings(), { applied = it }, repository) } }
        compose.onNodeWithTag("preset-C1").performClick()
        assertNull(applied)
        compose.onNodeWithTag("preset-review").assertExists()
        compose.onNodeWithText(context.getString(R.string.presets_apply)).performClick()
        assertEquals(p.id, applied?.id)
    }
    @Test fun reviewShowsIncompatibilityAndDeferredRecordingChanges() {
        val state = state().copy(phase = CameraUiPhase.RECORDING)
        val p = CameraPreset(name = "Unsupported", settings = CameraSettings(videoWidth = 3840))
        compose.setContent { MaterialTheme { PresetReviewDialog(p, state, CameraSettings(), {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.presets_pending)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.presets_incompatible, p.compatibilityIssues(state).joinToString(", "))).performScrollTo().assertIsDisplayed()
    }
    @Test fun disabledChoiceTileDoesNotDispatchOrExposeEnabledSemantics() {
        var clicked = 0
        compose.setContent { MaterialTheme { ChoiceTile("Unavailable", false, enabled = false) { clicked++ } } }
        compose.onNodeWithText("Unavailable").assertIsNotEnabled().performClick()
        assertEquals(0, clicked)
    }
    @Test fun realDocumentAndPersistentLibraryReopenWithoutPrivateDeviceRouting() {
        val file = java.io.File.createTempFile("preset-roundtrip", ".json", context.cacheDir)
        val prefs = context.getSharedPreferences("preset-library-test", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val source = CameraPreset(name = "Document", settings = CameraSettings(audioInputDeviceId = 99, subjectDisplay = SubjectDisplaySettings(prompterText = "local only")))
            val uri = android.net.Uri.fromFile(file)
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(CameraPresetCodec.encode(source).toByteArray()) }
            val decoded = context.contentResolver.openInputStream(uri)!!.use { CameraPresetCodec.decode(readPresetDocument(it)) }
            val storage = object : PresetPersistence {
                override fun read() = prefs.getString("library", null)
                override fun write(value: String) { check(prefs.edit().putString("library", value).commit()) }
            }
            val repository = PresetRepository(storage); repository.save(decoded); repository.assign("C2", decoded.id)
            assertEquals(repository.states.value, PresetRepository(storage).states.value)
            assertNull(decoded.settings.audioInputDeviceId); assertEquals("", decoded.settings.subjectDisplay.prompterText)
            android.util.Log.i("PresetDocumentProbe", "version=${CameraPresetCodec.VERSION} fields=${CameraPresetCodec.portableKeys.size} bytes=${file.length()} reopened=true slot=C2 privateRouting=false")
        } finally { file.delete(); prefs.edit().clear().commit() }
    }
    @Test fun presetImportAndSlotsAreSearchable() {
        assertEquals(setOf("presets"), SettingsCatalog.search("importar C1", null) { context.getString(it) })
    }
}
