/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Size
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLightFpsSettingsTest {
    private fun camera(offered: Boolean) = Camera2CameraDescriptor(
        cameraId = "0", lensFacing = 1, focalLengthsMm = listOf(5f), previewSize = Size(1920, 1080),
        jpegSize = null, rawSize = null, analysisSize = null, sensorOrientation = 90, sensitivityRange = null,
        exposureTimeRangeNs = null, aeCompensationRange = null, aeCompensationStep = 0f, minimumFocusDistance = null,
        supportsRaw = false, flashAvailable = false, targetFpsRanges = emptyList(), availableFixedFps = listOf(30),
        videoProfiles = emptyList(), logProfiles = emptyList(), lowLightAutoFps = offered,
    )

    private fun state(mode: CaptureMode, offered: Boolean = true) =
        CameraUiState(cameras = listOf(camera(offered)), selectedCameraId = "0", selectedMode = mode)

    @Test fun offByDefaultAndRoundTrips() {
        assertFalse(CameraSettings().lowLightAutoFps)
        assertFalse(CameraSettingsStore(PresetPreferences()).load().lowLightAutoFps)
        val store = CameraSettingsStore(PresetPreferences())
        val settings = CameraSettings(lowLightAutoFps = true)
        store.save(settings)
        assertTrue(store.load().lowLightAutoFps)
        assertEquals(settings, store.load())
    }

    @Test fun storedUnderItsOwnKey() {
        val memory = PresetPreferences()
        CameraSettingsStore(memory).save(CameraSettings(lowLightAutoFps = true))
        assertEquals(true, memory.all["video-low-light-fps"])
    }

    @Test fun isNotPartOfAPortablePreset() {
        assertFalse("video-low-light-fps" in CameraPresetCodec.portableKeys)
        val decoded = CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name = "Dim", settings = CameraSettings(lowLightAutoFps = true))))
        assertFalse(decoded.settings.lowLightAutoFps)
    }

    @Test fun onlyRegularVideoOnACameraThatOffersItAndOnlyWhenSwitchedOn() {
        val on = CameraSettings(lowLightAutoFps = true)
        assertTrue(lowLightFpsActive(state(CaptureMode.VIDEO), on))
        assertFalse(lowLightFpsActive(state(CaptureMode.VIDEO), CameraSettings()))
        assertFalse(lowLightFpsActive(state(CaptureMode.VIDEO, offered = false), on))
        assertFalse(lowLightFpsActive(state(CaptureMode.VIDEO), on.copy(videoOffSpeed = true)))
        for (mode in CaptureMode.entries.filter { it != CaptureMode.VIDEO }) {
            assertFalse("$mode", lowLightFpsActive(state(mode), on))
        }
    }

    @Test fun applyingAPresetKeepsTheDeviceChoice() {
        val merged = CameraPresetCodec.mergeLocal(CameraSettings(), CameraSettings(lowLightAutoFps = true))
        assertTrue(merged.lowLightAutoFps)
    }
}
