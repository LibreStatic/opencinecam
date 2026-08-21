/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingGeometryTest {
    @Test fun compatiblePortraitBakesRotationAndSwapsDimensions() {
        val result = RecordingGeometryCalculator.calculate(
            1920, 1080, 90, 0,
            CameraCharacteristics.LENS_FACING_BACK,
            RecordingGeometryMode.COMPATIBLE,
        )
        assertEquals(90, result.pixelRotationDegrees)
        assertEquals(0, result.rendererRotationDegrees)
        assertEquals(0, result.containerRotationDegrees)
        assertEquals(RecordingFrameSize(1080, 1920), result.encodedSize)
        assertEquals(RecordingFrameSize(1080, 1920), result.displaySize)
    }

    @Test fun nativePortraitKeepsRasterAndWritesRotation() {
        val result = RecordingGeometryCalculator.calculate(
            1920, 1080, 90, 0,
            CameraCharacteristics.LENS_FACING_BACK,
            RecordingGeometryMode.NATIVE_RASTER,
        )
        assertEquals(0, result.pixelRotationDegrees)
        assertEquals(270, result.rendererRotationDegrees)
        assertEquals(90, result.containerRotationDegrees)
        assertEquals(RecordingFrameSize(1920, 1080), result.encodedSize)
        assertEquals(RecordingFrameSize(1080, 1920), result.displaySize)
    }

    @Test fun frontCameraUsesPhysicalOrientationWithFrontSignForFileRotation() {
        val result = RecordingGeometryCalculator.calculate(
            1920, 1080, 90, 90,
            CameraCharacteristics.LENS_FACING_FRONT,
            RecordingGeometryMode.NATIVE_RASTER,
        )
        assertEquals(0, result.containerRotationDegrees)
    }

    @Test fun backCameraCoversEveryPhysicalQuadrant() {
        val rotations = listOf(0, 90, 180, 270).associateWith { device ->
            RecordingGeometryCalculator.calculate(
                1920, 1080, 90, device,
                CameraCharacteristics.LENS_FACING_BACK,
                RecordingGeometryMode.NATIVE_RASTER,
            ).containerRotationDegrees
        }
        assertEquals(mapOf(0 to 90, 90 to 180, 180 to 270, 270 to 0), rotations)
    }

    @Test fun compatibleLandscapeDoesNotRotateOrSwapTheBackCameraRaster() {
        val result = RecordingGeometryCalculator.calculate(
            1920, 1080, 90, 90,
            CameraCharacteristics.LENS_FACING_BACK,
            RecordingGeometryMode.COMPATIBLE,
        )
        assertEquals(180, result.contentRotationDegrees)
        assertEquals(90, result.rendererRotationDegrees)
        assertEquals(RecordingFrameSize(1920, 1080), result.encodedSize)
        assertEquals(0, result.containerRotationDegrees)
    }

    @Test fun compatibleBackCameraDerivesRendererFromPhysicalOrientationInEveryQuadrant() {
        val expected = mapOf(
            0 to Triple(90, 0, RecordingFrameSize(1080, 1920)),
            90 to Triple(180, 90, RecordingFrameSize(1920, 1080)),
            180 to Triple(270, 180, RecordingFrameSize(1080, 1920)),
            270 to Triple(0, 270, RecordingFrameSize(1920, 1080)),
        )
        expected.forEach { (device, values) ->
            val result = RecordingGeometryCalculator.calculate(
                1920, 1080, 90, device,
                CameraCharacteristics.LENS_FACING_BACK,
                RecordingGeometryMode.COMPATIBLE,
            )
            assertEquals(values.first, result.contentRotationDegrees)
            assertEquals(values.second, result.rendererRotationDegrees)
            assertEquals(values.third, result.encodedSize)
        }
    }

    @Test fun frontCameraCoversEveryPhysicalQuadrantWithoutFileMirroring() {
        val rotations = listOf(0, 90, 180, 270).associateWith { device ->
            RecordingGeometryCalculator.calculate(
                1920, 1080, 270, device,
                CameraCharacteristics.LENS_FACING_FRONT,
                RecordingGeometryMode.NATIVE_RASTER,
            ).containerRotationDegrees
        }
        assertEquals(mapOf(0 to 270, 90 to 180, 180 to 90, 270 to 0), rotations)
    }

    @Test fun nativeRasterAlwaysRemovesSurfaceTextureSensorRotation() {
        listOf(0, 90, 180, 270).forEach { device ->
            val result = RecordingGeometryCalculator.calculate(
                1920, 1080, 90, device,
                CameraCharacteristics.LENS_FACING_BACK,
                RecordingGeometryMode.NATIVE_RASTER,
            )
            assertEquals(270, result.rendererRotationDegrees)
            assertEquals(RecordingFrameSize(1920, 1080), result.encodedSize)
        }
    }
}
