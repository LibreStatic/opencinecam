/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class PictureControlsTest {
    /** Motorola Razr Fold camera 0: saturation [0 10 5 1], sharpness [0 6]. */
    private val saturation = parseSaturationRange(intArrayOf(0, 10, 5, 1))!!
    private val sharpness = parseSharpnessRange(intArrayOf(0, 6))!!
    private val caps = PictureControlCapabilities(saturation, sharpness)

    @Test fun saturationRangeKeepsTheAdvertisedDefaultAndStep() {
        assertEquals(PictureControlRange(0, 10, 1, 5, true), saturation)
        assertEquals(11, saturation.positions)
        assertEquals(9, saturation.sliderSteps)
    }

    @Test fun sharpnessHasNoAdvertisedDefaultSoTheMidpointIsOnlyAMarker() {
        assertEquals(PictureControlRange(0, 6, 1, 3, false), sharpness)
        assertNull(PictureControlCapabilities(sharpness = sharpness).sharpness?.resolve(null))
        assertEquals(3, sharpness.resolve(3))
    }

    @Test fun malformedRangesAreRejectedOrDegraded() {
        assertNull(parseSaturationRange(null))
        assertNull(parseSaturationRange(intArrayOf(5)))
        assertNull(parseSaturationRange(intArrayOf(10, 0, 5, 1)))
        assertNull(parseSharpnessRange(intArrayOf(6, 0)))
        assertNull(parseSharpnessRange(intArrayOf(3, 3)))
        val outOfRangeDefault = parseSaturationRange(intArrayOf(0, 10, 99, 0))!!
        assertFalse(outOfRangeDefault.defaultAdvertised)
        assertEquals(5, outOfRangeDefault.default)
        assertEquals(1, outOfRangeDefault.step)
        assertEquals(0..3, parseSaturationRange(intArrayOf(0, 3))!!.let { it.min..it.max })
    }

    @Test fun snapClampsAndFollowsTheStepGrid() {
        assertEquals(10, saturation.snap(99))
        assertEquals(0, saturation.snap(-4))
        val coarse = PictureControlRange(0, 10, 4, 4, true)
        assertEquals(listOf(0, 0, 4, 4, 8, 8, 8), listOf(0, 1, 2, 4, 6, 7, 9).map(coarse::snap).let { listOf(it[0], it[1], it[2], it[3], it[4], it[5], it[6]) })
        assertEquals(8, coarse.snap(9))
        assertEquals(8, coarse.snap(10))
        assertEquals(8, coarse.top)
        assertEquals(1, coarse.sliderSteps)
    }

    @Test fun theAdvertisedDefaultClearsTheOverrideButAnotherValueSticks() {
        assertNull(saturation.resolve(null))
        assertNull(saturation.resolve(5))
        assertEquals(7, saturation.resolve(7))
        assertEquals(10, saturation.resolve(40))
    }

    @Test fun neutralProcessingSuppressesBothControls() {
        val selection = ImageProcessingSelection(saturation = 8, sharpness = 4)
        assertEquals(ResolvedPictureControls(8, 4), selection.resolvePictureControls(caps, edgeMode = 2, neutralProcessing = false))
        assertEquals(ResolvedPictureControls(), selection.resolvePictureControls(caps, edgeMode = 2, neutralProcessing = true))
    }

    @Test fun sharpnessNeedsAnEdgeStageButSaturationDoesNot() {
        val selection = ImageProcessingSelection(saturation = 8, sharpness = 4)
        assertEquals(ResolvedPictureControls(8, null), selection.resolvePictureControls(caps, edgeMode = 0, neutralProcessing = false))
        assertEquals(ResolvedPictureControls(8, 4), selection.resolvePictureControls(caps, edgeMode = null, neutralProcessing = false))
        assertTrue(sharpnessEffective(1))
        assertFalse(sharpnessEffective(0))
    }

    @Test fun missingCapabilitiesNeverProduceValues() {
        val selection = ImageProcessingSelection(saturation = 8, sharpness = 4)
        assertEquals(ResolvedPictureControls(), selection.resolvePictureControls(PictureControlCapabilities(), 2, false))
        assertEquals(ResolvedPictureControls(8, null), selection.resolvePictureControls(PictureControlCapabilities(saturation = saturation), 2, false))
        assertFalse(PictureControlCapabilities().any)
    }
}
