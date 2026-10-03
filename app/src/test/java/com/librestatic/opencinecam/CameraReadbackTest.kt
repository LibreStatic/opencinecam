/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.Antibanding
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ImageProcessingDefaults
import com.librestatic.opencinecam.camera.ResolvedExposure
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraReadbackTest {
    private val manual = ResolvedExposure(ExposureMode.MANUAL, iso = 400, timeNs = 10_000_000L, antibanding = Antibanding.HZ50)

    @Test fun automaticExposureNeverReportsADifference() {
        val auto = ResolvedExposure(ExposureMode.AUTO)
        assertTrue(exposureReadbackDifferences(auto, ExposureMode.AUTO, 200, 10_000_000L, null).isEmpty())
    }

    @Test fun matchingManualExposureStaysQuiet() {
        // Within 1 %: sensors round exposure to whole lines. Code 1 is CONTROL_AE_ANTIBANDING_MODE_50HZ.
        assertTrue(exposureReadbackDifferences(manual, ExposureMode.MANUAL, 400, 10_050_000L, 1).isEmpty())
        assertTrue(exposureReadbackDifferences(manual, null, null, null, null).isEmpty())
    }

    @Test fun eachDisagreementIsListedOnce() {
        assertEquals(
            listOf(ReadbackDifference.Mode(ExposureMode.AUTO), ReadbackDifference.Iso(800), ReadbackDifference.ExposureTime(20_000_000L),
                ReadbackDifference.Banding(Antibanding.HZ60)),
            exposureReadbackDifferences(manual, ExposureMode.AUTO, 800, 20_000_000L, 2),
        )
        assertEquals(listOf(ReadbackDifference.Banding(null)), exposureReadbackDifferences(manual, ExposureMode.MANUAL, 400, 10_000_000L, 9))
    }

    @Test fun onlyAFixedTemperatureIsCompared() {
        assertTrue(whiteBalanceReadbackDifferences(WhiteBalanceSelection.Auto, 4000).isEmpty())
        assertTrue(whiteBalanceReadbackDifferences(WhiteBalanceSelection.Kelvin(5600), 5650).isEmpty())
        assertEquals(listOf(ReadbackDifference.Temperature(6000)), whiteBalanceReadbackDifferences(WhiteBalanceSelection.Kelvin(5600), 6000))
    }

    @Test fun imageProcessingComparesTheSubmittedRequest() {
        val reported = ImageProcessingDefaults(optical = 0, video = 0, noise = 2, edge = null)
        assertTrue(imageProcessingReadbackDifferences(null, reported).isEmpty())
        assertEquals(listOf(ReadbackDifference.Optical(0)),
            imageProcessingReadbackDifferences(ImageProcessingDefaults(optical = 1, video = 0, noise = 2, edge = 1), reported))
    }

    @Test fun capabilitiesTableKeepsEveryValueUnderItsCameraKey() {
        val lines = requestedReportedLines(CameraUiState(sensitivityIso = 200, exposureTimeNs = 10_000_000L,
            submittedImageProcessing = ImageProcessingDefaults(optical = 1), reportedOpticalStabilization = 0)).toMap()
        assertEquals("AUTO → 200", lines["android.sensor.sensitivity"])
        assertEquals("AUTO → 10000000 ns (1/100)", lines["android.sensor.exposureTime"])
        assertEquals("ON (1) → OFF (0)", lines["android.lens.opticalStabilizationMode"])
        assertEquals("— → —", lines["android.scaler.cropRegion"])
        assertEquals("AUTO → —", lines["android.control.aeMode"])
    }
}
