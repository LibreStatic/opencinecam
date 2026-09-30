/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

/** Window profiles used by the capture chrome. Expanded windows use the horizontal deck. */
internal enum class CaptureWindowProfile { COMPACT_PORTRAIT, COMPACT_LANDSCAPE, EXPANDED }

internal fun captureWindowProfile(widthDp: Float, heightDp: Float): CaptureWindowProfile = when {
    minOf(widthDp, heightDp) >= 600f -> CaptureWindowProfile.EXPANDED
    widthDp > heightDp -> CaptureWindowProfile.COMPACT_LANDSCAPE
    else -> CaptureWindowProfile.COMPACT_PORTRAIT
}

/** Pixel bounds shared by the SurfaceView, monitoring overlay and tap-to-focus mapping. */
internal data class PreviewViewport(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * Width / height of the upright viewfinder image on the current display.
 *
 * Camera2 rotates a SurfaceView buffer by the sensor orientation relative to the current
 * display rotation, so the stream's long edge follows the screen only when that relative turn
 * is 0 or 180 degrees. Deriving this from the window shape assumes a phone (sensor 90,
 * portrait-native display) and stretches tablets and any other sensor/display combination.
 * Front cameras rotate by sensor + display, which has the same quarter-turn parity.
 */
internal fun previewDisplayRatio(
    streamWidth: Int,
    streamHeight: Int,
    squeezeFactor: Float,
    sensorOrientationDegrees: Int,
    displayRotationDegrees: Int,
): Float {
    require(streamWidth > 0 && streamHeight > 0)
    require(squeezeFactor > 0f)
    val relativeRotation = ((sensorOrientationDegrees - displayRotationDegrees) % 360 + 360) % 360
    require(relativeRotation % 90 == 0) { "Preview rotations must be multiples of 90 degrees." }
    val landscapeRatio = streamWidth.toFloat() / streamHeight / squeezeFactor
    return if (relativeRotation == 90 || relativeRotation == 270) 1f / landscapeRatio else landscapeRatio
}

internal fun fittedPreviewViewport(containerWidth: Float, containerHeight: Float, ratio: Float?): PreviewViewport {
    if (containerWidth <= 0f || containerHeight <= 0f || ratio == null || !ratio.isFinite() || ratio <= 0f) {
        return PreviewViewport(0f, 0f, containerWidth.coerceAtLeast(0f), containerHeight.coerceAtLeast(0f))
    }
    val width: Float
    val height: Float
    if (containerWidth / containerHeight > ratio) {
        height = containerHeight
        width = height * ratio
    } else {
        width = containerWidth
        height = width / ratio
    }
    return PreviewViewport(
        left = (containerWidth - width) / 2f,
        top = (containerHeight - height) / 2f,
        width = width,
        height = height,
    )
}

/** Centred viewport that covers the whole container; the overflow (negative left/top) is cropped. */
internal fun filledPreviewViewport(containerWidth: Float, containerHeight: Float, ratio: Float?): PreviewViewport {
    if (containerWidth <= 0f || containerHeight <= 0f || ratio == null || !ratio.isFinite() || ratio <= 0f) {
        return PreviewViewport(0f, 0f, containerWidth.coerceAtLeast(0f), containerHeight.coerceAtLeast(0f))
    }
    val width: Float
    val height: Float
    if (containerWidth / containerHeight > ratio) {
        width = containerWidth
        height = width / ratio
    } else {
        height = containerHeight
        width = height * ratio
    }
    return PreviewViewport(
        left = (containerWidth - width) / 2f,
        top = (containerHeight - height) / 2f,
        width = width,
        height = height,
    )
}

/** Viewport of a viewfinder that spans the whole window under translucent chrome. */
internal fun overlayPreviewViewport(
    containerWidth: Float,
    containerHeight: Float,
    ratio: Float?,
    scale: ViewfinderScale,
): PreviewViewport = when (scale) {
    ViewfinderScale.FIT -> fittedPreviewViewport(containerWidth, containerHeight, ratio)
    ViewfinderScale.FILL -> filledPreviewViewport(containerWidth, containerHeight, ratio)
}

/** Refresh-rate and brightness changes also dispatch DisplayListener callbacks. */
internal fun previewDisplayRotationChanged(previousRotation: Int?, currentRotation: Int?): Boolean =
    currentRotation != null && previousRotation != currentRotation
