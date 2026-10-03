/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Size
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.ExposureCapabilities
import com.librestatic.opencinecam.camera.ExposureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureAvailabilityTest {
    private val ranges = ExposureCapabilities(isoRange = 100..1600, timeRangeNs = 100_000L..200_000_000L)
    private val combos = listOf(
        ExposureCapabilities(),
        ranges,
        ranges.copy(manual = true),
        ExposureCapabilities(manual = true),
        ExposureCapabilities(manual = true, isoRange = 100..1600),
        ranges.copy(priorities = setOf(ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY)),
        ExposureCapabilities(priorities = setOf(ExposureMode.ISO_PRIORITY), timeRangeNs = 100_000L..200_000_000L),
        ranges.copy(manual = true, isoRange = 0..0),
    )

    private fun camera(exposure: ExposureCapabilities) = Camera2CameraDescriptor(
        cameraId = "exp", lensFacing = 1, focalLengthsMm = listOf(5f), previewSize = Size(1920, 1080),
        jpegSize = null, rawSize = null, analysisSize = null, sensorOrientation = 90, sensitivityRange = null,
        exposureTimeRangeNs = null, aeCompensationRange = null, aeCompensationStep = 0f, minimumFocusDistance = null,
        supportsRaw = false, flashAvailable = false, targetFpsRanges = emptyList(), availableFixedFps = listOf(30),
        videoProfiles = emptyList(), logProfiles = emptyList(), exposureCapabilities = exposure,
    )

    private fun audit(caps: ExposureCapabilities, feature: AppFeature) = auditCameraCapabilities(camera(caps)).first { it.feature == feature }

    @Test fun settingsAndCapabilitiesGiveTheSameAnswerForEveryCamera() {
        for (caps in combos) {
            val available = exposureAvailability(caps)
            for (mode in ExposureMode.entries) assertEquals("$caps $mode", caps.supports(mode), available.supports(mode))
            assertEquals("$caps", available.manual, audit(caps, AppFeature.MANUAL_EXPOSURE).status == AppFeatureStatus.ACTIVE)
            assertEquals("$caps", available.isoPriority || available.shutterPriority,
                audit(caps, AppFeature.EXPOSURE_PRIORITY).status == AppFeatureStatus.ACTIVE)
        }
    }

    @Test fun rangesWithoutManualControlOfferNoSliders() {
        // The emulator case behind bug #18: ISO and time ranges, but no manual sensor and no priorities.
        val available = exposureAvailability(ranges)
        assertFalse(available.any)
        assertNull(available.isoRange)
        assertNull(available.timeRangeNs)
        assertEquals(AppFeatureStatus.UNAVAILABLE, audit(ranges, AppFeature.MANUAL_EXPOSURE).status)
    }

    @Test fun manualClaimedWithoutRangesIsNotAvailableAnywhere() {
        val caps = ExposureCapabilities(manual = true)
        assertFalse(exposureAvailability(caps).manual)
        assertEquals(AppFeatureFinding(AppFeature.MANUAL_EXPOSURE, AppFeatureStatus.UNAVAILABLE, "MANUAL_SENSOR"), audit(caps, AppFeature.MANUAL_EXPOSURE))
    }

    @Test fun priorityWithoutItsRangeIsNotListed() {
        val caps = ExposureCapabilities(priorities = setOf(ExposureMode.ISO_PRIORITY), timeRangeNs = 100_000L..200_000_000L)
        assertFalse(exposureAvailability(caps).isoPriority)
        assertEquals(AppFeatureStatus.UNAVAILABLE, audit(caps, AppFeature.EXPOSURE_PRIORITY).status)
        val shutterOnly = ranges.copy(priorities = setOf(ExposureMode.SHUTTER_PRIORITY))
        val available = exposureAvailability(shutterOnly)
        assertTrue(available.timeAdjustable)
        assertFalse(available.isoAdjustable)
        assertEquals("Shutter", audit(shutterOnly, AppFeature.EXPOSURE_PRIORITY).detail)
    }

    @Test fun constrainedHighSpeedHasNoManualControl() {
        assertEquals(ExposureAvailability(), exposureAvailability(ranges.copy(manual = true), constrainedHighSpeed = true))
    }

    @Test fun automaticExposureShowsWhatTheCameraReportsNotTheStoredRequest() {
        val manual = exposureAvailability(ranges.copy(manual = true))
        // AUTO: the camera chose ISO 200, so Settings must not claim the stored 100.
        assertEquals(ExposureReading(200, true), isoReading(manual, ExposureMode.AUTO, 100, 200))
        assertEquals(ExposureReading(100, false), isoReading(manual, ExposureMode.MANUAL, 100, 200))
        assertEquals(ExposureReading<Int>(null, true), isoReading(manual, ExposureMode.SHUTTER_PRIORITY, 100, null))
        // A stored manual request on a camera that cannot apply it is automatic too.
        assertEquals(ExposureReading(200, true), isoReading(exposureAvailability(ranges), ExposureMode.MANUAL, 100, 200))
        assertEquals(ExposureReading(10_000_000L, true), timeReading(manual, ExposureMode.ISO_PRIORITY, 16_666_667L, 10_000_000L))
        assertEquals(ExposureReading(16_666_667L, false), timeReading(manual, ExposureMode.MANUAL, 16_666_667L, 10_000_000L))
    }
}
