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
        assertEquals(4f / 3f, previewDisplayRatio(1920, 1080, 4f / 3f, true), 0.001f)
        assertEquals(3f / 4f, previewDisplayRatio(1920, 1080, 4f / 3f, false), 0.001f)
    }
}
