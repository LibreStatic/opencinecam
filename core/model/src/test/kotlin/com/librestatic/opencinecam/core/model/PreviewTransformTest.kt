/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTransformTest {
    @Test
    fun fitAndCropKeepOverlayGeometryIndependent() {
        val fit = calculatePreviewTransform(1920, 1080, 1080, 1920, PreviewScaleMode.FIT, 90, 0, false)
        val crop = calculatePreviewTransform(1920, 1080, 1080, 1920, PreviewScaleMode.CROP, 90, 0, true)

        assertEquals(90, fit.rotationDegrees)
        assertTrue(fit.scaleX <= 1f && fit.scaleY <= 1f)
        assertTrue(crop.scaleX >= 1f && crop.scaleY >= 1f)
        assertTrue(crop.mirrorX)
        val overlay = PreviewOverlay("histogram", "Histogram overlay", 0f, 0f, 1f, 1f)
        assertEquals(0f, overlay.normalizedLeft)
        assertEquals(1f, overlay.normalizedRight)
    }

    @Test
    fun surfaceSessionHandlesAttachReadyLossAndCleanup() {
        val session = PreviewSurfaceSession()

        assertEquals(PreviewSurfaceEvent.Attached("surface-1"), session.attach("surface-1"))
        assertEquals(FailureCode.DUPLICATE_COMMAND, (session.attach("surface-2") as PreviewSurfaceEvent.Rejected).failure.code)
        assertEquals(PreviewSurfaceEvent.Ready("surface-1"), session.markReady("surface-1"))
        assertTrue(session.isReady("surface-1"))
        assertEquals(PreviewSurfaceEvent.Detached("surface-1"), session.markLost("surface-1"))
        assertTrue(!session.isReady("surface-1"))
        assertEquals(PreviewSurfaceEvent.Ready("surface-1"), session.markReady("surface-1"))
        assertEquals(PreviewSurfaceEvent.Detached("surface-1"), session.detach("surface-1"))
        assertEquals(FailureCode.STALE_EVIDENCE, (session.detach("surface-1") as PreviewSurfaceEvent.Rejected).failure.code)
    }

    @Test
    fun staleSurfaceEventDoesNotBecomeUnsupported() {
        val session = PreviewSurfaceSession()
        session.attach("surface-1")

        val stale = session.markReady("surface-old") as PreviewSurfaceEvent.Rejected

        assertEquals(FailureCode.STALE_EVIDENCE, stale.failure.code)
        assertEquals(Recoverability.RETRYABLE, stale.failure.recoverability)
    }
}
