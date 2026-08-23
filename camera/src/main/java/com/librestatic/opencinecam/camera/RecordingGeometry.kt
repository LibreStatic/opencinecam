/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Size

enum class RecordingGeometryMode {
    COMPATIBLE,
    NATIVE_RASTER,
}

data class RecordingFrameSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    fun swapped(): RecordingFrameSize = RecordingFrameSize(height, width)
}

data class RecordingGeometry(
    val mode: RecordingGeometryMode,
    val deviceOrientationDegrees: Int,
    val contentRotationDegrees: Int,
    /** Rotation baked into the displayed pixel geometry in compatible mode. */
    val pixelRotationDegrees: Int,
    /**
     * Rotation applied to the GLES position quad. This is deliberately distinct from
     * [pixelRotationDegrees]: SurfaceTexture's matrix already owns the sensor rotation on the
     * physical camera stream, so feeding the file rotation directly to GLES double-rotates it.
     */
    val rendererRotationDegrees: Int,
    val containerRotationDegrees: Int,
    val sourceSize: RecordingFrameSize,
    val encodedSize: RecordingFrameSize,
    val displaySize: RecordingFrameSize,
    val pixelAspectRatioWidth: Int = 1,
    val pixelAspectRatioHeight: Int = 1,
) {
    init {
        listOf(
            deviceOrientationDegrees,
            contentRotationDegrees,
            pixelRotationDegrees,
            rendererRotationDegrees,
            containerRotationDegrees,
        )
            .forEach { require(it in 0..359 && it % 90 == 0) }
        require(sourceSize.width > 0 && sourceSize.height > 0)
        require(encodedSize.width > 0 && encodedSize.height > 0)
        require(displaySize.width > 0 && displaySize.height > 0)
        require(pixelAspectRatioWidth > 0 && pixelAspectRatioHeight > 0)
    }
}

/** Resolves file geometry from physical device orientation, never from a Display rotation. */
object RecordingGeometryCalculator {
    fun calculate(
        sourceSize: Size,
        sensorOrientationDegrees: Int,
        deviceOrientationDegrees: Int,
        lensFacing: Int,
        mode: RecordingGeometryMode,
        anamorphicSqueeze: AnamorphicSqueeze = AnamorphicSqueeze.NONE,
        anamorphicOutputMode: AnamorphicOutputMode = AnamorphicOutputMode.SQUEEZED,
        supportsEncodedSize: (RecordingFrameSize) -> Boolean = { true },
    ): RecordingGeometry = calculate(
        sourceWidth = sourceSize.width,
        sourceHeight = sourceSize.height,
        sensorOrientationDegrees = sensorOrientationDegrees,
        deviceOrientationDegrees = deviceOrientationDegrees,
        lensFacing = lensFacing,
        mode = mode,
        anamorphicSqueeze = anamorphicSqueeze,
        anamorphicOutputMode = anamorphicOutputMode,
        supportsEncodedSize = supportsEncodedSize,
    )

    fun calculate(
        sourceWidth: Int,
        sourceHeight: Int,
        sensorOrientationDegrees: Int,
        deviceOrientationDegrees: Int,
        lensFacing: Int,
        mode: RecordingGeometryMode,
        anamorphicSqueeze: AnamorphicSqueeze = AnamorphicSqueeze.NONE,
        anamorphicOutputMode: AnamorphicOutputMode = AnamorphicOutputMode.SQUEEZED,
        supportsEncodedSize: (RecordingFrameSize) -> Boolean = { true },
    ): RecordingGeometry {
        val sourceSize = RecordingFrameSize(sourceWidth, sourceHeight)
        val sensor = normalize(sensorOrientationDegrees)
        val device = normalize(deviceOrientationDegrees)
        // OrientationEventListener reports the physical chassis angle with the opposite sign
        // convention from Display.getRotation. Keep this formula deliberately separate from the
        // preview transform: substituting the display formula here swaps the two landscape
        // orientations and makes text appear upside-down in one of them.
        val frontFacing = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        val contentRotation = if (frontFacing) {
            (sensor - device + 360) % 360
        } else {
            (sensor + device) % 360
        }
        val pixelRotation = if (mode == RecordingGeometryMode.COMPATIBLE) contentRotation else 0
        val containerRotation = if (mode == RecordingGeometryMode.NATIVE_RASTER) contentRotation else 0
        // SurfaceTexture already contributes the sensor transform. Compatible output therefore
        // applies contentRotation - sensor, while native-raster output removes the sensor
        // transform and delegates the final display orientation to the MP4 matrix.
        val rendererRotation = when (mode) {
            RecordingGeometryMode.COMPATIBLE -> (contentRotation - sensor + 360) % 360
            RecordingGeometryMode.NATIVE_RASTER -> (360 - sensor) % 360
        }
        val baseEncodedSize = if (pixelRotation.isQuarterTurn()) sourceSize.swapped() else sourceSize
        val quarterTurn = pixelRotation.isQuarterTurn()
        val squeeze = anamorphicSqueeze
        var sarW: Int
        var sarH: Int
        val encodedSize: RecordingFrameSize
        val displaySize: RecordingFrameSize
        if (!squeeze.isActive) {
            sarW = 1
            sarH = 1
            encodedSize = baseEncodedSize
            displaySize = if (containerRotation.isQuarterTurn()) encodedSize.swapped() else encodedSize
        } else if (anamorphicOutputMode == AnamorphicOutputMode.DESQUEEZED) {
            // Expand the raster along the horizontal display axis. After a quarter-turn pixel
            // rotation the horizontal axis maps to the source height, so we expand height.
            // Integer SAR math keeps common factors exact (1080 -> 1440 at 1.33x, not 1436).
            sarW = 1
            sarH = 1
            encodedSize = if (quarterTurn) {
                RecordingFrameSize(
                    baseEncodedSize.width,
                    baseEncodedSize.height * squeeze.sarWidth / squeeze.sarHeight,
                )
            } else {
                RecordingFrameSize(
                    baseEncodedSize.width * squeeze.sarWidth / squeeze.sarHeight,
                    baseEncodedSize.height,
                )
            }
            displaySize = if (containerRotation.isQuarterTurn()) encodedSize.swapped() else encodedSize
        } else {
            // SQUEEZED: keep raster, declare SAR. Invert SAR when pixels are rotated 90/270.
            if (quarterTurn) {
                sarW = squeeze.sarHeight
                sarH = squeeze.sarWidth
            } else {
                sarW = squeeze.sarWidth
                sarH = squeeze.sarHeight
            }
            encodedSize = baseEncodedSize
            displaySize = if (containerRotation.isQuarterTurn()) encodedSize.swapped() else encodedSize
        }
        // Desqueeze that exceeds the hardware encoder's raster limits degrades to SQUEEZED:
        // the file keeps the sensor raster and declares the stretch as pixel aspect ratio,
        // so players still show correct geometry instead of failing to configure a codec.
        if (squeeze.isActive && anamorphicOutputMode == AnamorphicOutputMode.DESQUEEZED) {
            val desqueezed = if (quarterTurn) {
                RecordingFrameSize(
                    baseEncodedSize.width,
                    baseEncodedSize.height * squeeze.sarWidth / squeeze.sarHeight,
                )
            } else {
                RecordingFrameSize(
                    baseEncodedSize.width * squeeze.sarWidth / squeeze.sarHeight,
                    baseEncodedSize.height,
                )
            }
            if (!supportsEncodedSize(desqueezed)) {
                // Fall back to the SQUEEZED representation: keep the sensor raster and
                // declare the stretch as pixel aspect ratio so players still show the
                // correct display geometry instead of failing to configure a codec.
                sarW = squeeze.sarWidth
                sarH = squeeze.sarHeight
            }
        }
        return RecordingGeometry(
            mode = mode,
            deviceOrientationDegrees = device,
            contentRotationDegrees = contentRotation,
            pixelRotationDegrees = pixelRotation,
            rendererRotationDegrees = rendererRotation,
            containerRotationDegrees = containerRotation,
            sourceSize = sourceSize,
            encodedSize = encodedSize,
            displaySize = displaySize,
            pixelAspectRatioWidth = sarW,
            pixelAspectRatioHeight = sarH,
        )
    }

    private fun normalize(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "Recording orientation must be a multiple of 90 degrees." }
        return normalized
    }

    private fun Int.isQuarterTurn(): Boolean = this == 90 || this == 270
}
