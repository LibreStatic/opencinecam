/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Range
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.camera.*
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VolumeKeyPairsTest {
    private val third = 2.0.pow(1.0 / 3.0).toFloat()

    private fun camera(
        exposure: ExposureCapabilities = ExposureCapabilities(manual = true, isoRange = 50..6400, timeRangeNs = 100_000L..500_000_000L,
            priorities = setOf(ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY)),
        ev: Range<Int>? = Range(-6, 6),
        near: Float? = 10f,
    ): CameraUiState {
        val descriptor = Camera2CameraDescriptor(
            cameraId = "keys", lensFacing = 1, focalLengthsMm = listOf(5f), previewSize = Size(640, 480),
            jpegSize = null, rawSize = null, analysisSize = null, sensorOrientation = 90, sensitivityRange = Range(50, 6400),
            exposureTimeRangeNs = Range(100_000L, 500_000_000L), aeCompensationRange = ev, aeCompensationStep = 1f / 3f,
            minimumFocusDistance = near, supportsRaw = false, flashAvailable = false, targetFpsRanges = emptyList(),
            availableFixedFps = listOf(30), videoProfiles = emptyList(), logProfiles = emptyList(), exposureCapabilities = exposure,
        )
        return CameraUiState(phase = CameraUiPhase.PREVIEWING, selectedMode = CaptureMode.VIDEO, cameras = listOf(descriptor),
            selectedCameraId = descriptor.cameraId, zoomSupported = true, zoomMinRatio = 0.5f, zoomMaxRatio = 10f)
    }

    @Test fun zoomPressMovesAThirdOfAStopAndStopsOnTheAnchorItCrosses() {
        val anchors = listOf(0.5f, 1f, 3f)
        val plain = volumeZoomTap(1.5f, 1, 0.5f, 10f, anchors)
        assertEquals(1.5f * third, plain.value, 1e-4f); assertFalse(plain.anchor); assertFalse(plain.limit)
        // 2.7 × 1.26 = 3.4 crosses the tele: the press lands on it.
        val snapped = volumeZoomTap(2.7f, 1, 0.5f, 10f, anchors)
        assertEquals(3f, snapped.value, 0f); assertTrue(snapped.anchor)
        // Standing on an anchor, the next press leaves it.
        assertEquals(3f * third, volumeZoomTap(3f, 1, 0.5f, 10f, anchors).value, 1e-4f)
        assertEquals(1f, volumeZoomTap(1.2f, -1, 0.5f, 10f, anchors).value, 0f)
        assertEquals(1f / third, volumeZoomTap(1f, -1, 0.5f, 10f, anchors).value, 1e-4f)
    }
    @Test fun zoomIsClampedAndReportsTheLimit() {
        val top = volumeZoomTap(9f, 1, 0.5f, 10f, emptyList())
        assertEquals(10f, top.value, 0f); assertTrue(top.limit)
        val bottom = volumeZoomTap(0.5f, -1, 0.5f, 10f, listOf(0.5f, 1f))
        assertEquals(0.5f, bottom.value, 0f); assertTrue(bottom.limit); assertFalse(bottom.anchor)
    }
    @Test fun heldZoomFollowsTheRockerCurveAndFlagsOnlyTheMomentItReachesTheEnd() {
        val speed = ZoomMath.rockerSpeedOctavesPerSecond(VolumeZoomHoldOffset)
        val step = volumeZoomHold(2f, 1, 0.016f, 0.5f, 10f, emptyList())
        assertEquals(2f * ZoomMath.rockerFactor(speed, 0.016f), step.value, 1e-5f)
        assertTrue(volumeZoomHold(1.99f, 1, 0.05f, 0.5f, 10f, listOf(2f)).anchor)
        assertFalse(volumeZoomHold(2f, 1, 0.05f, 0.5f, 10f, listOf(2f)).anchor)
        assertTrue(volumeZoomHold(9.999f, 1, 0.05f, 0.5f, 10f, emptyList()).limit)
        assertFalse(volumeZoomHold(10f, 1, 0.05f, 0.5f, 10f, emptyList()).limit)
        assertEquals(0.5f, volumeZoomHold(0.5f, -1, 0.05f, 0.5f, 10f, emptyList()).value, 0f)
    }
    @Test fun evStepsOneIndexWithinTheRange() {
        val state = camera()
        assertEquals(PairStep(1, false), volumeEvStep(state, 1))
        assertEquals(PairStep(6, true), volumeEvStep(state.copy(requestedAeCompensationIndex = 6), 1))
        assertEquals(PairStep(-6, true), volumeEvStep(state.copy(requestedAeCompensationIndex = -5), -1))
        assertNull(volumeEvStep(camera(ev = null), 1))
    }
    @Test fun isoFromAutoSeedsFromTheReportedValues() {
        val state = camera().copy(sensitivityIso = 400, exposureTimeNs = 10_000_000L)
        val step = volumeIsoStep(state, CameraSettings(), 1)!!
        // A camera with manual exposure goes manual, keeping the reported shutter; one with only the priority takes it.
        assertEquals(ExposureMode.MANUAL, step.value.mode)
        val priorityOnly = camera(ExposureCapabilities(isoRange = 50..6400, priorities = setOf(ExposureMode.ISO_PRIORITY))).copy(sensitivityIso = 400)
        assertEquals(ExposureMode.ISO_PRIORITY, volumeIsoStep(priorityOnly, CameraSettings(), 1)!!.value.mode)
        assertEquals(504, step.value.iso)
        // Leaving full auto keeps the shutter the camera had chosen, exactly as the slider does.
        assertEquals(10_000_000L, step.value.timeNs)
        assertEquals(exposureWithIso(CameraSettings(), state, true, 504), step.value)
        val down = volumeIsoStep(state, CameraSettings(), -1)!!
        assertEquals(317, down.value.iso)
    }
    @Test fun isoInManualStaysManualAndClamps() {
        val settings = CameraSettings(exposure = ExposureSelection(ExposureMode.MANUAL, iso = 6000, timeNs = 20_000_000L))
        val state = camera().copy(requestedIso = 6000, requestedExposureTimeNs = 20_000_000L, sensitivityIso = 5000)
        val step = volumeIsoStep(state, settings, 1)!!
        assertEquals(ExposureMode.MANUAL, step.value.mode); assertEquals(6400, step.value.iso); assertTrue(step.limit)
        assertEquals(20_000_000L, step.value.timeNs)
        val bottom = volumeIsoStep(state.copy(requestedIso = 50), settings, -1)!!
        assertEquals(50, bottom.value.iso); assertTrue(bottom.limit)
    }
    @Test fun shutterFromAutoSeedsFromTheReportedTimeAndAnglesStepByThirds() {
        val state = camera().copy(sensitivityIso = 400, exposureTimeNs = 10_000_000L)
        val step = volumeShutterStep(state, CameraSettings(), 1)!!
        assertEquals(ExposureMode.MANUAL, step.value.mode)
        assertEquals((10_000_000L * 2.0.pow(1.0 / 3.0)).toLong(), step.value.timeNs)
        assertEquals(400, step.value.iso)
        val angle = CameraSettings(exposure = ExposureSelection(ExposureMode.SHUTTER_PRIORITY, shutterUnit = ShutterUnit.ANGLE, angleTenths = 1800))
        assertEquals(2268, volumeShutterStep(state, angle, 1)!!.value.angleTenths)
        assertEquals(1429, volumeShutterStep(state, angle, -1)!!.value.angleTenths)
        val wide = angle.copy(exposure = angle.exposure.copy(angleTenths = 3500))
        assertEquals(3600, volumeShutterStep(state, wide, 1)!!.value.angleTenths); assertTrue(volumeShutterStep(state, wide, 1)!!.limit)
        // The smallest angle still moves on a press.
        assertEquals(2, volumeShutterStep(state, angle.copy(exposure = angle.exposure.copy(angleTenths = 1)), 1)!!.value.angleTenths)
        val longest = volumeShutterStep(state.copy(exposureTimeNs = 480_000_000L), CameraSettings(), 1)!!
        assertEquals(500_000_000L, longest.value.timeNs); assertTrue(longest.limit)
    }
    @Test fun shutterWithOnlyManualGoesManual() {
        val manualOnly = camera(ExposureCapabilities(manual = true, isoRange = 50..6400, timeRangeNs = 100_000L..500_000_000L))
        val settings = CameraSettings(exposure = ExposureSelection(ExposureMode.MANUAL, iso = 200, timeNs = 20_000_000L))
        val step = volumeShutterStep(manualOnly.copy(requestedExposureTimeNs = 20_000_000L), settings, -1)!!
        assertEquals(ExposureMode.MANUAL, step.value.mode); assertEquals(200, step.value.iso)
    }
    @Test fun focusStepsFromTheReportedDistanceAndClamps() {
        val af = camera().copy(focusDistanceDiopters = 2f)
        assertEquals(1.75f, volumeFocusStep(af, 1)!!.value, 1e-6f) // farther: fewer diopters
        assertEquals(2.25f, volumeFocusStep(af, -1)!!.value, 1e-6f)
        val manual = af.copy(requestedFocusDiopters = 0.1f)
        val far = volumeFocusStep(manual, 1)!!
        assertEquals(0f, far.value, 0f); assertTrue(far.limit)
        val near = volumeFocusStep(af.copy(requestedFocusDiopters = 10f), -1)!!
        assertEquals(10f, near.value, 0f); assertTrue(near.limit)
        assertEquals(0.25f, volumeFocusStep(camera(), -1)!!.value, 1e-6f)
        assertNull(volumeFocusStep(camera(near = null), 1))
    }
    @Test fun eachPairNamesWhatBlocksIt() {
        val ready = camera()
        for (pair in VolumeKeyPair.entries - VolumeKeyPair.NONE) assertNull(pair.name, volumePairUnavailableReason(pair, ready))
        assertEquals(OperatorUnavailableReason.NOT_ASSIGNABLE, volumePairUnavailableReason(VolumeKeyPair.NONE, ready))
        assertEquals(OperatorUnavailableReason.NO_ZOOM, volumePairUnavailableReason(VolumeKeyPair.ZOOM, ready.copy(zoomSupported = false)))
        assertEquals(OperatorUnavailableReason.NO_ZOOM, volumePairUnavailableReason(VolumeKeyPair.ZOOM, ready.copy(zoomMaxRatio = 0.5f)))
        assertEquals(OperatorUnavailableReason.NO_EV, volumePairUnavailableReason(VolumeKeyPair.EXPOSURE_COMPENSATION, camera(ev = Range(0, 0))))
        val auto = camera(ExposureCapabilities())
        assertEquals(OperatorUnavailableReason.NO_MANUAL_EXPOSURE, volumePairUnavailableReason(VolumeKeyPair.ISO, auto))
        assertEquals(OperatorUnavailableReason.NO_MANUAL_EXPOSURE, volumePairUnavailableReason(VolumeKeyPair.SHUTTER, auto))
        val isoOnly = camera(ExposureCapabilities(isoRange = 50..6400, priorities = setOf(ExposureMode.ISO_PRIORITY)))
        assertNull(volumePairUnavailableReason(VolumeKeyPair.ISO, isoOnly))
        assertEquals(OperatorUnavailableReason.NO_MANUAL_EXPOSURE, volumePairUnavailableReason(VolumeKeyPair.SHUTTER, isoOnly))
        assertEquals(OperatorUnavailableReason.NO_MANUAL_FOCUS, volumePairUnavailableReason(VolumeKeyPair.FOCUS, camera(near = 0f)))
        assertEquals(OperatorUnavailableReason.LOCKED, volumePairUnavailableReason(VolumeKeyPair.ZOOM, ready.copy(stillCapturePending = true)))
        assertEquals(OperatorUnavailableReason.NOT_READY, volumePairUnavailableReason(VolumeKeyPair.ZOOM, ready.copy(phase = CameraUiPhase.OPENING)))
        assertEquals(OperatorUnavailableReason.NOT_READY, volumePairUnavailableReason(VolumeKeyPair.ISO, CameraUiState(phase = CameraUiPhase.PREVIEWING)))
    }
}
