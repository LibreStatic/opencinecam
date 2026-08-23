/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {

    @Test
    fun deriveAnchorsFromSampleRearPhysicalFocals() {
        // Sample rear logical camera 0: focal 6.57mm, physicalIds [3=1.826mm, 2=6.57mm, 5=13.3mm]
        val anchors = ZoomMath.deriveAnchors(
            referenceFocalMm = 6.57f,
            physicalFocals = listOf("3" to 1.826f, "2" to 6.57f, "5" to 13.3f),
            rangeMin = 0.5f,
            rangeMax = 10f,
        )
        assertEquals(3, anchors.size)
        // Shortest physical focal => widest FoV => ratio clamped to advertised min 0.5x
        assertEquals(0.5f, anchors[0].ratio, 0.05f)
        assertEquals("3", anchors[0].physicalCameraId)
        // Reference focal matches physical 2 => 1x
        assertEquals(1f, anchors[1].ratio, 0.05f)
        // Longest physical focal => telephoto => ~2x (13.3/6.57)
        assertEquals(2.03f, anchors[2].ratio, 0.1f)
        assertEquals("5", anchors[2].physicalCameraId)
    }

    @Test
    fun deriveAnchorsReturnsOnlyUnityWhenPhysicalMetadataMissing() {
        val anchors = ZoomMath.deriveAnchors(null, emptyList())
        assertEquals(1, anchors.size)
        assertEquals(1f, anchors[0].ratio, 0.001f)
    }

    @Test
    fun nearestAnchorResolvesTiesToLowerRatio() {
        val anchors = listOf(
            ZoomAnchor(1f, 0f, null),
            ZoomAnchor(2f, 0f, null),
        )
        // Equidistant from 1.5; should pick the lower (1x)
        val nearest = ZoomMath.nearestAnchor(1.5f, anchors)
        assertEquals(1f, nearest.ratio, 0.001f)
    }

    @Test
    fun manualPresetsSectorClampsBetweenAnchorAndGeometricMean() {
        val anchors = listOf(
            ZoomAnchor(0.5f, 0f, "a"),
            ZoomAnchor(1f, 0f, null),
            ZoomAnchor(2f, 0f, "b"),
        )
        // At 1x, sector should be [1, sqrt(1*2)] = [1, ~1.414]
        val bounds = ZoomMath.sectorBounds(1f, anchors, ZoomLensSwitchMode.MANUAL_PRESETS, 0.5f, 10f)
        assertEquals(1f, bounds.start, 0.001f)
        assertEquals(ZoomMath.geometricMean(1f, 2f), bounds.endInclusive, 0.001f)
    }

    @Test
    fun automaticModeUsesFullRange() {
        val anchors = listOf(ZoomAnchor(1f, 0f, null), ZoomAnchor(2f, 0f, "b"))
        val bounds = ZoomMath.sectorBounds(1.5f, anchors, ZoomLensSwitchMode.AUTOMATIC, 0.5f, 10f)
        assertEquals(0.5f, bounds.start, 0.001f)
        assertEquals(10f, bounds.endInclusive, 0.001f)
    }

    @Test
    fun manualPresetsBeyondLastAnchorAllowsDigitalUpToRangeMax() {
        val anchors = listOf(
            ZoomAnchor(0.5f, 0f, "a"),
            ZoomAnchor(1f, 0f, null),
            ZoomAnchor(2f, 0f, "b"),
        )
        // At 3x (beyond the 2x telephoto anchor), allow digital up to rangeMax
        val bounds = ZoomMath.sectorBounds(3f, anchors, ZoomLensSwitchMode.MANUAL_PRESETS, 0.5f, 10f)
        assertEquals(2f, bounds.start, 0.001f)
        assertEquals(10f, bounds.endInclusive, 0.001f)
    }

    @Test
    fun coerceHandlesInvertedRange() {
        assertEquals(1f, ZoomMath.coerce(5f, 1f..1f), 0.001f)
        assertEquals(1.5f, ZoomMath.coerce(1.5f, 0.5f..10f), 0.001f)
        assertEquals(0.5f, ZoomMath.coerce(0.1f, 0.5f..10f), 0.001f)
        assertEquals(10f, ZoomMath.coerce(20f, 0.5f..10f), 0.001f)
    }

    @Test
    fun multiplyAppliesFactorAndCoerces() {
        assertEquals(2f, ZoomMath.multiply(1f, 2f, 0.5f..10f), 0.001f)
        assertEquals(10f, ZoomMath.multiply(8f, 2f, 0.5f..10f), 0.001f)
    }

    @Test
    fun rockerDeadZoneReturnsZero() {
        assertEquals(0f, ZoomMath.rockerSpeedOctavesPerSecond(0f), 0.001f)
        assertEquals(0f, ZoomMath.rockerSpeedOctavesPerSecond(0.05f), 0.001f)
        assertEquals(0f, ZoomMath.rockerSpeedOctavesPerSecond(-0.03f), 0.001f)
    }

    @Test
    fun rockerExtremeReachesMaxSpeed() {
        assertEquals(2f, ZoomMath.rockerSpeedOctavesPerSecond(1f), 0.001f)
        assertEquals(-2f, ZoomMath.rockerSpeedOctavesPerSecond(-1f), 0.001f)
    }

    @Test
    fun rockerMidRangeIsQuadratic() {
        // At normalized offset 0.5 (after dead zone removal): normalized = (0.5-0.06)/(1-0.06)
        // speed = 2 * normalized^2
        val speed = ZoomMath.rockerSpeedOctavesPerSecond(0.5f)
        val expected = 2f * ((0.5f - 0.06f) / (1f - 0.06f)).let { it * it }
        assertEquals(expected, speed, 0.001f)
        assertTrue(speed > 0f && speed < 2f)
    }

    @Test
    fun rockerFactorPositiveZoomsIn() {
        val factor = ZoomMath.rockerFactor(1f, 1f) // 1 octave/sec for 1 sec = e^1
        assertEquals(Math.E, factor.toDouble(), 0.001)
        assertTrue(factor > 1f)
    }

    @Test
    fun rockerFactorNegativeZoomsOut() {
        val factor = ZoomMath.rockerFactor(-1f, 1f)
        assertEquals(1f / Math.E, factor.toDouble(), 0.001)
        assertTrue(factor < 1f)
    }

    @Test
    fun cropRegionForReturnsNullAtUnityZoom() {
        val active = CropRect(0, 0, 4000, 3000)
        assertNull(ZoomMath.cropRegionForPure(active, 1f))
        assertNull(ZoomMath.cropRegionForPure(active, 0.5f))
    }

    @Test
    fun cropRegionForCentersAndScalesByZoomRatio() {
        val active = CropRect(0, 0, 4000, 3000)
        val crop = ZoomMath.cropRegionForPure(active, 2f)
        assertEquals(2000, crop!!.width)
        assertEquals(1500, crop.height)
        assertEquals(1000, crop.left)
        assertEquals(750, crop.top)
        assertEquals(3000, crop.right)
        assertEquals(2250, crop.bottom)
    }

    @Test
    fun ratioFromCropRegionRecoversZoom() {
        val active = CropRect(0, 0, 4000, 3000)
        val crop = CropRect(1000, 750, 3000, 2250)
        val ratio = ZoomMath.ratioFromCropRegionPure(active, crop)
        assertEquals(2f, ratio!!, 0.01f)
    }

    @Test
    fun ratioFromCropRegionReturnsNullForInvalidInputs() {
        assertNull(ZoomMath.ratioFromCropRegionPure(null, null))
        assertNull(ZoomMath.ratioFromCropRegionPure(CropRect(0, 0, 1, 1), null))
        assertNull(ZoomMath.ratioFromCropRegionPure(null, CropRect(0, 0, 1, 1)))
    }
}
