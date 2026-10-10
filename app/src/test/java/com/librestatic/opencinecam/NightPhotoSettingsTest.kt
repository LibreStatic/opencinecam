/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Size
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.NightExtensionCapability
import com.librestatic.opencinecam.camera.NightPhotoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightPhotoSettingsTest {
    private val capability = NightExtensionCapability(listOf(1920 to 1080), listOf(4080 to 3072))

    private fun camera(night: NightExtensionCapability?) = Camera2CameraDescriptor(
        cameraId = "0", lensFacing = 1, focalLengthsMm = listOf(5f), previewSize = Size(1920, 1080),
        jpegSize = null, rawSize = null, analysisSize = null, sensorOrientation = 90, sensitivityRange = null,
        exposureTimeRangeNs = null, aeCompensationRange = null, aeCompensationStep = 0f, minimumFocusDistance = null,
        supportsRaw = false, flashAvailable = false, targetFpsRanges = emptyList(), availableFixedFps = listOf(30),
        videoProfiles = emptyList(), logProfiles = emptyList(), nightExtension = night,
    )

    private val supported = CameraUiState(cameras = listOf(camera(capability)), selectedCameraId = "0", selectedMode = CaptureMode.PHOTO)
    private val unsupported = CameraUiState(cameras = listOf(camera(null)), selectedCameraId = "0", selectedMode = CaptureMode.PHOTO)

    @Test fun nightIsAutoByDefaultAndEveryModePersists() {
        assertEquals(NightPhotoMode.AUTO, CameraSettingsStore(PresetPreferences()).load().nightPhotoMode)
        for (mode in NightPhotoMode.entries) {
            val settings = CameraSettings(nightPhotoMode = mode)
            val store = CameraSettingsStore(PresetPreferences())
            store.save(settings)
            assertEquals(mode, store.load().nightPhotoMode)
            assertEquals(settings, store.load())
        }
    }

    @Test fun theLegacySwitchMigratesOnLoad() {
        val on = PresetPreferences().also { it.edit().putBoolean("night-photo", true).apply() }
        assertEquals(NightPhotoMode.ON, CameraSettingsStore(on).load().nightPhotoMode)
        val off = PresetPreferences().also { it.edit().putBoolean("night-photo", false).apply() }
        assertEquals(NightPhotoMode.AUTO, CameraSettingsStore(off).load().nightPhotoMode)
        // The new key wins over a leftover legacy switch.
        val both = PresetPreferences().also {
            it.edit().putBoolean("night-photo", true).putString("night-photo-mode", "OFF").apply()
        }
        assertEquals(NightPhotoMode.OFF, CameraSettingsStore(both).load().nightPhotoMode)
    }

    @Test fun nightIsNotPartOfAPortablePreset() {
        val text = CameraPresetCodec.encode(CameraPreset(name = "Night", settings = CameraSettings(nightPhotoMode = NightPhotoMode.ON)))
        assertFalse(text.contains("night-photo"))
        assertEquals(NightPhotoMode.AUTO, CameraPresetCodec.decode(text).settings.nightPhotoMode)
        // Applying a preset keeps this device's choice.
        val merged = CameraPresetCodec.mergeLocal(CameraPresetCodec.decode(text).settings, CameraSettings(nightPhotoMode = NightPhotoMode.OFF))
        assertEquals(NightPhotoMode.OFF, merged.nightPhotoMode)
    }

    @Test fun engagedNeedsTheChoiceAndACameraThatAdvertisesTheExtension() {
        val always = CameraSettings(nightPhotoMode = NightPhotoMode.ON)
        assertTrue(nightPhotoSelected(supported, always, sdkInt = 34))
        assertFalse(nightPhotoSelected(supported, always, sdkInt = 30))
        assertFalse(nightPhotoSelected(unsupported, always, sdkInt = 34))
        assertFalse(nightPhotoSelected(CameraUiState(), always, sdkInt = 34))
        assertFalse(nightPhotoSelected(supported, CameraSettings(nightPhotoMode = NightPhotoMode.OFF), sdkInt = 34))
        // Auto engages only through the quick toggle; Off ignores it.
        val auto = CameraSettings(nightPhotoMode = NightPhotoMode.AUTO)
        assertFalse(nightPhotoSelected(supported, auto, sdkInt = 34))
        assertTrue(nightPhotoSelected(supported.copy(nightTransientEngaged = true), auto, sdkInt = 34))
        assertFalse(nightPhotoSelected(supported.copy(nightTransientEngaged = true), CameraSettings(nightPhotoMode = NightPhotoMode.OFF), sdkInt = 34))
    }

    @Test fun theQuickToggleShowsWhenForcedOrWhenAutoSeesADarkSceneOrIsEngaged() {
        val off = CameraSettings(nightPhotoMode = NightPhotoMode.OFF)
        val auto = CameraSettings(nightPhotoMode = NightPhotoMode.AUTO)
        val always = CameraSettings(nightPhotoMode = NightPhotoMode.ON)
        val dark = supported.copy(nightSuggested = true)
        assertFalse(nightToggleVisible(dark, off, 34))
        assertFalse(nightToggleAvailable(dark, off, 34))
        assertFalse(nightToggleVisible(supported, auto, 34))
        assertTrue(nightToggleAvailable(supported, auto, 34))
        assertTrue(nightToggleVisible(dark, auto, 34))
        // Still there after the scene turned bright, so it can be switched off.
        assertTrue(nightToggleVisible(supported.copy(nightTransientEngaged = true), auto, 34))
        assertTrue(nightToggleVisible(supported, always, 34))
        // Not on other modes, other cameras or older platforms.
        assertFalse(nightToggleVisible(dark.copy(selectedMode = CaptureMode.VIDEO), always, 34))
        assertFalse(nightToggleVisible(unsupported.copy(nightSuggested = true), auto, 34))
        assertFalse(nightToggleVisible(dark, auto, 30))
    }

    @Test fun tappingTheToggleFlipsTheSessionStateInAutoAndReleasesAlways() {
        val auto = CameraSettings(nightPhotoMode = NightPhotoMode.AUTO)
        assertEquals(NightToggleAction.SetEngaged(true), nightToggleAction(supported, auto))
        assertEquals(NightToggleAction.SetEngaged(false), nightToggleAction(supported.copy(nightTransientEngaged = true), auto))
        assertEquals(NightToggleAction.ReleaseForced, nightToggleAction(supported, CameraSettings(nightPhotoMode = NightPhotoMode.ON)))
    }

    @Test fun everySlotIsHeldInNightPhotoWithAReason() {
        CaptureSlot.entries.forEach { slot ->
            assertEquals("$slot", SlotUnavailableReason.NIGHT_PHOTO,
                captureSlotUnavailableReason(slot, true, false, true, true, true, 4, nightPhoto = true))
            assertNotEquals("$slot", SlotUnavailableReason.NIGHT_PHOTO,
                captureSlotUnavailableReason(slot, true, false, true, true, true, 4))
        }
        // A camera that is not ready still says so first.
        assertEquals(SlotUnavailableReason.NOT_READY,
            captureSlotUnavailableReason(CaptureSlot.ISO, false, false, true, true, true, 4, nightPhoto = true))
    }

    @Test fun theBusyCardNamesNightProcessing() {
        val night = CameraUiState(selectedMode = CaptureMode.PHOTO, stillCapturePending = true, nightExtensionRunning = true)
        assertEquals(CaptureHold.BUSY, night.captureHold)
    }
}
