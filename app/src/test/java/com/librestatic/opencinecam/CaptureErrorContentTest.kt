/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureErrorContentTest {
    @Test fun frameSizesAlwaysReadLongSideFirst() {
        assertEquals("1280×720", formatFrameSize(720, 1280))
        assertEquals("1920×1080", formatFrameSize(1920, 1080))
        assertEquals("1080×1080", formatFrameSize(1080, 1080))
    }

    @Test fun sizesInsideTextAreRewrittenTheSameWay() {
        assertEquals("Failed at 1280×960 and 1280×960", normalizeFrameSizes("Failed at 960x1280 and 1280 × 960"))
        // Squeeze factors, codec names and single digits are not sizes.
        assertEquals("1.33x squeeze at 3840×2160", normalizeFrameSizes("1.33x squeeze at 3840x2160"))
        assertEquals("H.264 2x zoom", normalizeFrameSizes("H.264 2x zoom"))
    }

    @Test fun encoderRejectionKeepsOnlySizeAndRate() {
        assertEquals(
            EncoderRejection("1280×720", 30),
            encoderRejection("java.lang.IllegalStateException: No hardware video/avc Surface encoder accepts 720x1280 at 30 fps."),
        )
        assertNull(encoderRejection("Camera disconnected"))
        assertNull(encoderRejection(null))
    }

    @Test fun errorCodesMapToWhatFailed() {
        assertEquals(CameraErrorKind.DISCONNECTED, cameraErrorKind("camera-disconnected"))
        assertEquals(CameraErrorKind.PERMISSION, cameraErrorKind("microphone-permission-required"))
        assertEquals(CameraErrorKind.SAVE, cameraErrorKind("still-save-failed"))
        assertEquals(CameraErrorKind.SAVE, cameraErrorKind("save-busy"))
        assertEquals(CameraErrorKind.RECORDING, cameraErrorKind("video-start-failed"))
        assertEquals(CameraErrorKind.RECORDING, cameraErrorKind("video-gpu-prepare-failed"))
        assertEquals(CameraErrorKind.RECORDING, cameraErrorKind("recording-foreground-failed"))
        assertEquals(CameraErrorKind.PREVIEW, cameraErrorKind("preview-session-failed"))
        assertEquals(CameraErrorKind.PREVIEW, cameraErrorKind("video-preview-gpu-init-failed"))
        assertEquals(CameraErrorKind.PREVIEW, cameraErrorKind("high-speed-video-session-failed"))
        // Photo failures share suffixes with recording ones but are not recordings.
        assertEquals(CameraErrorKind.OTHER, cameraErrorKind("photo-flash-prepare-failed"))
        assertEquals(CameraErrorKind.OTHER, cameraErrorKind("still-sequence-recording-busy"))
        assertEquals(CameraErrorKind.OTHER, cameraErrorKind(null))
    }
}
