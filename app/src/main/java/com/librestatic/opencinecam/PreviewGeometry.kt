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

internal fun previewDisplayRatio(
    streamWidth: Int,
    streamHeight: Int,
    squeezeFactor: Float,
    windowLandscape: Boolean,
): Float {
    require(streamWidth > 0 && streamHeight > 0)
    require(squeezeFactor > 0f)
    val landscapeRatio = streamWidth.toFloat() / streamHeight / squeezeFactor
    return if (windowLandscape) landscapeRatio else 1f / landscapeRatio
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

/** Refresh-rate and brightness changes also dispatch DisplayListener callbacks. */
internal fun previewDisplayRotationChanged(previousRotation: Int?, currentRotation: Int?): Boolean =
    currentRotation != null && previousRotation != currentRotation
