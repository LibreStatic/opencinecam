/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCineLogPreviewGeometryTest {
    @Test
    fun backCameraPortraitUsesNegotiatedTextureRotationAndPreservesAspect() {
        val geometry = geometry(displayRotation = 0, targetWidth = 1080, targetHeight = 1920)

        assertEquals(0, geometry.positionRotationDegrees)
        assertEquals(1f, geometry.scaleX, 0.0001f)
        assertEquals(1f, geometry.scaleY, 0.0001f)
        assertFalse(geometry.mirrorHorizontally)
    }

    @Test
    fun backCameraLandscapeCompensatesOnlyDisplayRotationWithoutStretching() {
        val geometry = geometry(displayRotation = 90, targetWidth = 1920, targetHeight = 1080)

        assertEquals(270, geometry.positionRotationDegrees)
        assertEquals(1f, geometry.scaleX, 0.0001f)
        assertEquals(1f, geometry.scaleY, 0.0001f)
    }

    @Test
    fun mismatchedWindowIsLetterboxedInsteadOfStretched() {
        val geometry = geometry(displayRotation = 90, targetWidth = 2000, targetHeight = 1600)

        assertEquals(270, geometry.positionRotationDegrees)
        assertEquals(1f, geometry.scaleX, 0.0001f)
        assertTrue(geometry.scaleY < 1f)
        assertEquals(0.703125f, geometry.scaleY, 0.0001f)
    }

    @Test
    fun reverseLandscapeCompensatesInTheOppositeDirection() {
        val geometry = geometry(displayRotation = 270, targetWidth = 1920, targetHeight = 1080)

        assertEquals(90, geometry.positionRotationDegrees)
    }

    @Test
    fun backPipelineUsesAbsoluteDisplayCompensation() {
        val geometry = geometry(displayRotation = 180, targetWidth = 1080, targetHeight = 1920)

        assertEquals(180, geometry.positionRotationDegrees)
    }

    @Test
    fun frontCameraUsesOppositeDeltaAndMirrorsOnlyTheViewfinder() {
        val geometry = OpenCineLogPreviewGeometryCalculator.calculate(
            sourceWidth = 1920,
            sourceHeight = 1080,
            targetWidth = 1080,
            targetHeight = 1920,
            sensorOrientationDegrees = 270,
            displayRotationDegrees = 90,
            frontFacing = true,
        )

        assertEquals(90, geometry.positionRotationDegrees)
        assertTrue(geometry.mirrorHorizontally)
    }

    private fun geometry(
        displayRotation: Int,
        targetWidth: Int,
        targetHeight: Int,
    ) =
        OpenCineLogPreviewGeometryCalculator.calculate(
            sourceWidth = 1920,
            sourceHeight = 1080,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = displayRotation,
            frontFacing = false,
        )
}
