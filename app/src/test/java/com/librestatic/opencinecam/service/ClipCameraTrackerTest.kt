/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import android.util.Size
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import com.librestatic.opencinecam.storage.ClipCameraSettings
import com.librestatic.opencinecam.storage.ClipSampledRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipCameraTrackerTest {
    private val descriptor = Camera2CameraDescriptor(
        cameraId = "0", lensFacing = 1, focalLengthsMm = listOf(6.3f), previewSize = Size(1920, 1080), jpegSize = null, rawSize = null,
        analysisSize = null, sensorOrientation = 90, sensitivityRange = null, exposureTimeRangeNs = null, aeCompensationRange = null,
        aeCompensationStep = 0f, minimumFocusDistance = null, supportsRaw = false, flashAvailable = false, targetFpsRanges = emptyList(),
        availableFixedFps = emptyList(), videoProfiles = emptyList(), logProfiles = emptyList(),
        sensorSizeMm = 9.6f to 7.2f, aperturesFNumber = listOf(1.7f),
    )
    private val base = CameraUiState(
        cameras = listOf(descriptor), selectedCameraId = "0", requestedExposureMode = ExposureMode.AUTO,
        requestedWhiteBalance = WhiteBalanceSelection.Kelvin(5600, tint = 3),
    )

    @Test fun foldsStartEndAndExtremesOfTheSampledValues() {
        val tracker = ClipCameraTracker()
        tracker.sample(base.copy(sensitivityIso = 400, exposureTimeNs = 20_833_333L, zoomRatio = 1f, reportedColorTemperatureK = 5400))
        tracker.sample(base.copy(sensitivityIso = 1250, exposureTimeNs = 33_333_333L, zoomRatio = 1.5f, zoomEffectiveRatio = 1.6f))
        tracker.sample(base.copy(sensitivityIso = 320, exposureTimeNs = 10_000_000L, zoomRatio = 2f, reportedColorTemperatureK = 5450))
        tracker.sample(base.copy(sensitivityIso = 800, exposureTimeNs = 20_833_333L, zoomRatio = 2f))
        val result = tracker.result()
        assertEquals(ClipSampledRange(400, 800, 320, 1250), result.iso)
        assertEquals(ClipSampledRange(20_833_333L, 20_833_333L, 10_000_000L, 33_333_333L), result.exposureTimeNs)
        // The effective ratio wins over the requested one when the camera reports it.
        assertEquals(ClipSampledRange(1f, 2f, 1f, 2f), result.zoom)
        assertEquals(5400, result.reportedCctStartK)
        assertEquals(5450, result.reportedCctEndK)
    }

    @Test fun nullReadingsAreSkippedAndNeverBecomeZero() {
        val tracker = ClipCameraTracker()
        tracker.sample(base)
        tracker.sample(base.copy(sensitivityIso = 400))
        tracker.sample(base.copy(sensitivityIso = null, reportedNoiseReduction = 1))
        tracker.sample(base)
        val result = tracker.result()
        assertEquals(ClipSampledRange(400, 400, 400, 400), result.iso)
        assertNull(result.exposureTimeNs)
        assertNull(result.reportedCctStartK)
        assertNull(result.reportedExposureMode)
        assertEquals("FAST", result.noiseReduction)
    }

    @Test fun requestsComeFromTheFirstSampleAndReportsFromTheLastThatCarriesThem() {
        val tracker = ClipCameraTracker()
        tracker.sample(base.copy(reportedVideoStabilization = 0, reportedAfMode = 4, focusDistanceDiopters = 0.2f))
        tracker.sample(base.copy(requestedExposureMode = ExposureMode.MANUAL, requestedWhiteBalance = WhiteBalanceSelection.Auto,
            reportedExposureMode = ExposureMode.AUTO, reportedVideoStabilization = 2, reportedOpticalStabilization = 1,
            reportedAfMode = 3, focusDistanceDiopters = 0.5f, reportedEdgeEnhancement = 3, reportedToneMapMode = 9))
        val result = tracker.result()
        assertEquals("0", result.cameraId)
        assertEquals(6.3f, result.focalLengthMm)
        assertEquals(1.7f, result.aperture)
        assertEquals(descriptor.equivalentFocalLengthMm, result.focalLength35mm)
        assertEquals("AUTO", result.requestedExposureMode)
        assertEquals("AUTO", result.reportedExposureMode)
        assertEquals("KELVIN", result.whiteBalanceMode)
        assertEquals(5600, result.whiteBalanceKelvin)
        assertEquals(3, result.whiteBalanceTint)
        assertEquals("CONTINUOUS_VIDEO", result.afMode)
        assertEquals(0.5f, result.focusDistanceDiopters)
        assertEquals("ON", result.opticalStabilization)
        assertEquals("PREVIEW_STABILIZATION", result.videoStabilization)
        assertEquals("ZERO_SHUTTER_LAG", result.edge)
        // A value with no known name is kept as its number.
        assertEquals("9", result.toneMap)
    }

    @Test fun presetWhiteBalanceIsNamedAndTheTintIsTheReportedOne() {
        val tracker = ClipCameraTracker()
        tracker.sample(base.copy(requestedWhiteBalance = WhiteBalanceSelection.Preset(5), reportedColorTint = -2))
        val result = tracker.result()
        assertEquals("DAYLIGHT", result.whiteBalanceMode)
        assertNull(result.whiteBalanceKelvin)
        assertEquals(-2, result.whiteBalanceTint)
    }

    @Test fun noSamplesLeaveEveryFieldNull() {
        assertEquals(ClipCameraSettings(), ClipCameraTracker().result())
    }
}
