/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGeometryTest {

    @Test
    fun `refresh rate callback does not invalidate preview attachment`() {
        assertFalse(previewDisplayRotationChanged(previousRotation = 0, currentRotation = 0))
    }

    @Test
    fun `rotation callback invalidates preview attachment`() {
        assertTrue(previewDisplayRotationChanged(previousRotation = 0, currentRotation = 1))
        assertTrue(previewDisplayRotationChanged(previousRotation = null, currentRotation = 0))
    }
    @Test fun foldableOuterPortraitUsesCompactPortrait() {
        assertEquals(CaptureWindowProfile.COMPACT_PORTRAIT, captureWindowProfile(1080f / 2.625f, 2520f / 2.625f))
    }

    @Test fun foldableOuterLandscapeUsesCompactLandscape() {
        assertEquals(CaptureWindowProfile.COMPACT_LANDSCAPE, captureWindowProfile(2520f / 2.625f, 1080f / 2.625f))
    }

    @Test fun foldableInnerUsesExpandedProfileInBothRotations() {
        assertEquals(CaptureWindowProfile.EXPANDED, captureWindowProfile(2232f / 2.625f, 2484f / 2.625f))
        assertEquals(CaptureWindowProfile.EXPANDED, captureWindowProfile(2484f / 2.625f, 2232f / 2.625f))
    }

    @Test fun fittedViewportCentersLandscapeStream() {
        val viewport = fittedPreviewViewport(2520f, 1080f, 16f / 9f)
        assertEquals(300f, viewport.left, 0.01f)
        assertEquals(0f, viewport.top, 0.01f)
        assertEquals(1920f, viewport.width, 0.01f)
        assertEquals(1080f, viewport.height, 0.01f)
    }

    @Test fun fittedViewportCentersPortraitStream() {
        val viewport = fittedPreviewViewport(1080f, 2520f, 9f / 16f)
        assertEquals(0f, viewport.left, 0.01f)
        assertEquals(300f, viewport.top, 0.01f)
        assertEquals(1080f, viewport.width, 0.01f)
        assertEquals(1920f, viewport.height, 0.01f)
    }

    @Test fun anamorphicRatioIsSharedAcrossRotations() {
        // Phone sensor (90) on a portrait-native display: landscape at 90, portrait at 0.
        assertEquals(4f / 3f, previewDisplayRatio(1920, 1080, 4f / 3f, 90, 90), 0.001f)
        assertEquals(3f / 4f, previewDisplayRatio(1920, 1080, 4f / 3f, 90, 0), 0.001f)
    }

    @Test fun phoneBackSensorFollowsDisplayRotation() {
        assertEquals(9f / 16f, previewDisplayRatio(1920, 1080, 1f, 90, 0), 0.001f)
        assertEquals(16f / 9f, previewDisplayRatio(1920, 1080, 1f, 90, 90), 0.001f)
        assertEquals(9f / 16f, previewDisplayRatio(1920, 1080, 1f, 90, 180), 0.001f)
        assertEquals(16f / 9f, previewDisplayRatio(1920, 1080, 1f, 90, 270), 0.001f)
    }

    @Test fun phoneFrontSensorFollowsDisplayRotation() {
        assertEquals(9f / 16f, previewDisplayRatio(1920, 1080, 1f, 270, 0), 0.001f)
        assertEquals(16f / 9f, previewDisplayRatio(1920, 1080, 1f, 270, 90), 0.001f)
        assertEquals(16f / 9f, previewDisplayRatio(1920, 1080, 1f, 270, 270), 0.001f)
    }

    @Test fun tabletSensorZeroOnLandscapeNativeDisplay() {
        // Natural (rotation 0) is landscape: the stream is shown unrotated.
        assertEquals(16f / 9f, previewDisplayRatio(1920, 1080, 1f, 0, 0), 0.001f)
        assertEquals(16f / 9f, previewDisplayRatio(1920, 1080, 1f, 0, 180), 0.001f)
        // Portrait rotations turn the stream a quarter.
        assertEquals(9f / 16f, previewDisplayRatio(1920, 1080, 1f, 0, 90), 0.001f)
        assertEquals(9f / 16f, previewDisplayRatio(1920, 1080, 1f, 0, 270), 0.001f)
        assertEquals(3f / 4f, previewDisplayRatio(1920, 1080, 4f / 3f, 0, 90), 0.001f)
    }

    @Test fun otherSensorMountsUseRelativeRotation() {
        // Emulator tablets report the phone-style 90 sensor on a landscape-native display: the
        // upright image is portrait in the natural landscape window, not window-shaped.
        assertEquals(9f / 16f, previewDisplayRatio(1920, 1080, 1f, 90, 0), 0.001f)
        assertEquals(16f / 9f, previewDisplayRatio(1920, 1080, 1f, 90, 270), 0.001f)
        assertEquals(9f / 16f, previewDisplayRatio(1920, 1080, 1f, 180, 90), 0.001f)
        assertEquals(16f / 9f, previewDisplayRatio(1920, 1080, 1f, 180, 0), 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun previewRatioRejectsNonRightAngles() {
        previewDisplayRatio(1920, 1080, 1f, 45, 0)
    }

    @Test
    fun `filled viewport covers a wide container and crops top and bottom`() {
        val viewport = filledPreviewViewport(2000f, 1000f, 4f / 3f)
        assertEquals(2000f, viewport.width, 0.01f)
        assertEquals(1500f, viewport.height, 0.01f)
        assertEquals(0f, viewport.left, 0.01f)
        assertEquals(-250f, viewport.top, 0.01f)
    }

    @Test
    fun `filled viewport covers a tall container and crops the sides`() {
        val viewport = filledPreviewViewport(1000f, 2000f, 3f / 4f)
        assertEquals(2000f, viewport.height, 0.01f)
        assertEquals(1500f, viewport.width, 0.01f)
        assertEquals(-250f, viewport.left, 0.01f)
        assertEquals(0f, viewport.top, 0.01f)
    }

    @Test
    fun `filled viewport without a ratio keeps the container`() {
        assertEquals(PreviewViewport(0f, 0f, 800f, 600f), filledPreviewViewport(800f, 600f, null))
    }

    @Test
    fun `overlay viewport follows the chosen scale`() {
        assertEquals(fittedPreviewViewport(2000f, 1000f, 4f / 3f), overlayPreviewViewport(2000f, 1000f, 4f / 3f, ViewfinderScale.FIT))
        assertEquals(filledPreviewViewport(2000f, 1000f, 4f / 3f), overlayPreviewViewport(2000f, 1000f, 4f / 3f, ViewfinderScale.FILL))
    }
}
