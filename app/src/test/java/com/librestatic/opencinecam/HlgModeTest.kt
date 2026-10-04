/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.util.Size
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2LogProfile
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlgModeTest {
    private val hlg30 = profile(30, OpenCineLogSourcePath.HLG10_BT2020)
    private val hfr120 = profile(120, OpenCineLogSourcePath.SDR_BT709_ISP, highSpeed = true)

    @Test
    fun logAndHlgShareTheGraphButNothingElseDoes() {
        assertEquals(setOf(CaptureMode.LOG, CaptureMode.HLG), CaptureMode.entries.filter { it.usesLogGraph }.toSet())
    }

    @Test
    fun hlgOnlyOffersTheCamerasHlg10Profiles() {
        val descriptor = descriptorWith(listOf(hlg30, hfr120))
        assertEquals(listOf(hlg30, hfr120), descriptor.logProfilesFor(CaptureMode.LOG))
        assertEquals(listOf(hlg30), descriptor.logProfilesFor(CaptureMode.HLG))
        assertTrue(descriptorWith(listOf(hfr120)).logProfilesFor(CaptureMode.HLG).isEmpty())
    }

    @Test
    fun hlgIsNotOnTheDialUntilTheCameraQualifiesIt() {
        assertFalse(CameraUiState.defaultModeGates[CaptureMode.HLG] == ModeGateState.AVAILABLE)
    }

    private fun profile(fps: Int, path: OpenCineLogSourcePath, highSpeed: Boolean = false) = Camera2LogProfile(
        size = Size(1920, 1080),
        fps = fps,
        sourcePath = path,
        constrainedHighSpeed = highSpeed,
    )

    private fun descriptorWith(logProfiles: List<Camera2LogProfile>) = Camera2CameraDescriptor(
        cameraId = "0",
        lensFacing = 1,
        focalLengthsMm = emptyList(),
        previewSize = Size(1920, 1080),
        jpegSize = null,
        rawSize = null,
        analysisSize = null,
        sensorOrientation = 90,
        sensitivityRange = null,
        exposureTimeRangeNs = null,
        aeCompensationRange = null,
        aeCompensationStep = 0f,
        minimumFocusDistance = null,
        supportsRaw = false,
        flashAvailable = false,
        targetFpsRanges = emptyList(),
        availableFixedFps = emptyList(),
        videoProfiles = emptyList(),
        logProfiles = logProfiles,
    )
}
