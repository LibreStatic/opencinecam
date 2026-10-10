/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Size
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.NightExtensionCapability
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

    @Test fun nightIsOffByDefaultAndPersists() {
        assertFalse(CameraSettingsStore(PresetPreferences()).load().nightPhoto)
        val settings = CameraSettings(nightPhoto = true)
        val store = CameraSettingsStore(PresetPreferences())
        store.save(settings)
        assertTrue(store.load().nightPhoto)
        assertEquals(settings, store.load())
    }

    @Test fun nightIsNotPartOfAPortablePreset() {
        val decoded = CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name = "Night", settings = CameraSettings(nightPhoto = true))))
        assertFalse(decoded.settings.nightPhoto)
    }

    @Test fun theOptionNeedsTheSettingAndACameraThatAdvertisesTheExtension() {
        val supported = CameraUiState(cameras = listOf(camera(capability)), selectedCameraId = "0")
        val unsupported = CameraUiState(cameras = listOf(camera(null)), selectedCameraId = "0")
        assertTrue(nightPhotoSelected(supported, CameraSettings(nightPhoto = true), sdkInt = 34))
        assertFalse(nightPhotoSelected(supported, CameraSettings(nightPhoto = true), sdkInt = 30))
        assertFalse(nightPhotoSelected(supported, CameraSettings(nightPhoto = false), sdkInt = 34))
        assertFalse(nightPhotoSelected(unsupported, CameraSettings(nightPhoto = true), sdkInt = 34))
        assertFalse(nightPhotoSelected(CameraUiState(), CameraSettings(nightPhoto = true), sdkInt = 34))
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
