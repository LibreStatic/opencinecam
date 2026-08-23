/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Test

class AnamorphicGeometryTest {

    @Test fun squeezedLandscapeKeepsRasterAndDeclaresSar() {
        val result = RecordingGeometryCalculator.calculate(
            1920, 1080, 90, 90,
            CameraCharacteristics.LENS_FACING_BACK,
            RecordingGeometryMode.COMPATIBLE,
            AnamorphicSqueeze.SQUEEZE_1_33X,
            AnamorphicOutputMode.SQUEEZED,
        )
        assertEquals(RecordingFrameSize(1920, 1080), result.encodedSize)
        assertEquals(4, result.pixelAspectRatioWidth)
        assertEquals(3, result.pixelAspectRatioHeight)
    }

    @Test fun squeezedPortraitInvertsSar() {
        val result = RecordingGeometryCalculator.calculate(
            1920, 1080, 90, 0,
            CameraCharacteristics.LENS_FACING_BACK,
            RecordingGeometryMode.COMPATIBLE,
            AnamorphicSqueeze.SQUEEZE_2X,
            AnamorphicOutputMode.SQUEEZED,
        )
        assertEquals(RecordingFrameSize(1080, 1920), result.encodedSize)
        assertEquals(1, result.pixelAspectRatioWidth)
        assertEquals(2, result.pixelAspectRatioHeight)
    }

    @Test fun desqueezedLandscapeExpandsWidth() {
        val result = RecordingGeometryCalculator.calculate(
            1920, 1080, 90, 90,
            CameraCharacteristics.LENS_FACING_BACK,
            RecordingGeometryMode.COMPATIBLE,
            AnamorphicSqueeze.SQUEEZE_2X,
            AnamorphicOutputMode.DESQUEEZED,
        )
        assertEquals(3840, result.encodedSize.width)
        assertEquals(1080, result.encodedSize.height)
        assertEquals(1, result.pixelAspectRatioWidth)
        assertEquals(1, result.pixelAspectRatioHeight)
    }

    @Test fun desqueezedPortraitExpandsHeight() {
        val result = RecordingGeometryCalculator.calculate(
            1920, 1080, 90, 0,
            CameraCharacteristics.LENS_FACING_BACK,
            RecordingGeometryMode.COMPATIBLE,
            AnamorphicSqueeze.SQUEEZE_1_5X,
            AnamorphicOutputMode.DESQUEEZED,
        )
        assertEquals(1080, result.encodedSize.width)
        assertEquals(2880, result.encodedSize.height)
        assertEquals(1, result.pixelAspectRatioWidth)
        assertEquals(1, result.pixelAspectRatioHeight)
    }

    @Test fun noneSqueezeProducesSquarePixels() {
        val result = RecordingGeometryCalculator.calculate(
            1920, 1080, 90, 90,
            CameraCharacteristics.LENS_FACING_BACK,
            RecordingGeometryMode.COMPATIBLE,
            AnamorphicSqueeze.NONE,
            AnamorphicOutputMode.SQUEEZED,
        )
        assertEquals(RecordingFrameSize(1920, 1080), result.encodedSize)
        assertEquals(1, result.pixelAspectRatioWidth)
        assertEquals(1, result.pixelAspectRatioHeight)
    }
}
