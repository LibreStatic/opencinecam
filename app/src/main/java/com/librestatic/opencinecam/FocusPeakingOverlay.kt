/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.librestatic.opencinecam.camera.FocusPeakingMask
import com.librestatic.opencinecam.camera.MonitoringSignalDomain
import com.librestatic.opencinecam.camera.NormalizedFrame
import com.librestatic.opencinecam.camera.monitoringDisplayPoint
import com.librestatic.opencinecam.camera.outputFrameInStream

/**
 * A normalised point of the monitored signal ([domain] raster) in overlay pixels: the rotation and
 * mirroring of [monitoringDisplayPoint], then the GPU letterbox ([scaleX], [scaleY]) about the centre.
 */
internal fun monitoringOverlayPoint(x: Float, y: Float, domain: MonitoringSignalDomain, sensorOrientation: Int,
    displayDegrees: Int, frontFacing: Boolean, overlayWidth: Float, overlayHeight: Float, scaleX: Float, scaleY: Float): Offset {
    val p = monitoringDisplayPoint(x, y, domain, sensorOrientation, displayDegrees, frontFacing)
    return Offset((.5f + (p.first - .5f) * scaleX) * overlayWidth, (.5f + (p.second - .5f) * scaleY) * overlayHeight)
}

/** The affine map from mask pixels to overlay pixels: where pixel (0, 0) lands and one step along each mask axis. */
internal data class FocusPeakingPlacement(val origin: Offset, val xStep: Offset, val yStep: Offset) {
    fun map(x: Float, y: Float): Offset = origin + xStep * x + yStep * y

    fun matrix(): Matrix = Matrix().apply {
        values[Matrix.ScaleX] = xStep.x; values[Matrix.SkewY] = xStep.y
        values[Matrix.SkewX] = yStep.x; values[Matrix.ScaleY] = yStep.y
        values[Matrix.TranslateX] = origin.x; values[Matrix.TranslateY] = origin.y
    }
}

/**
 * Places a [maskWidth] × [maskHeight] mask over the preview overlay. An ISP mask comes from the
 * YUV analysis stream, which the HAL crops from the [cropWidth] × [cropHeight] region to its own
 * aspect, so it is first fitted into the [streamWidth] × [streamHeight] preview stream; a GPU mask
 * is a readback of exactly that stream. [edgeShift] (in mask pixels) centres each mark on the pixel
 * boundary its bit describes; 0 registers the raster itself.
 */
internal fun focusPeakingPlacement(
    maskWidth: Int, maskHeight: Int, domain: MonitoringSignalDomain,
    streamWidth: Int, streamHeight: Int, cropWidth: Int, cropHeight: Int,
    sensorOrientation: Int, displayDegrees: Int, frontFacing: Boolean,
    overlayWidth: Float, overlayHeight: Float, scaleX: Float, scaleY: Float,
    edgeShift: Float = .5f,
): FocusPeakingPlacement {
    val frame = if (domain == MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR)
        outputFrameInStream(maskWidth, maskHeight, streamWidth, streamHeight, cropWidth, cropHeight)
        else NormalizedFrame(0f, 0f, 1f, 1f)
    val du = frame.width / maskWidth
    val dv = frame.height / maskHeight
    fun point(u: Float, v: Float) = monitoringOverlayPoint(u, v, domain, sensorOrientation, displayDegrees, frontFacing,
        overlayWidth, overlayHeight, scaleX, scaleY)
    val u0 = frame.left + edgeShift * du
    val v0 = frame.top + edgeShift * dv
    val origin = point(u0, v0)
    return FocusPeakingPlacement(origin, point(u0 + du, v0) - origin, point(u0, v0 + dv) - origin)
}

/** The mask as a [color] bitmap, rebuilt only when the edges or the colour change; null when nothing peaks. */
@Composable
internal fun rememberFocusPeakingImage(mask: FocusPeakingMask?, color: Color): ImageBitmap? = remember(mask, color) {
    mask?.takeIf { it.edgeCount > 0 }?.let {
        Bitmap.createBitmap(it.toArgb(color.toArgb()), it.width, it.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

/** Draws [image] through [placement], filtered so a one-pixel edge stays a thin line at any scale, clipped to [clip]. */
internal fun DrawScope.drawFocusPeaking(image: ImageBitmap, placement: FocusPeakingPlacement, clip: Rect, alpha: Float) {
    clipRect(clip.left, clip.top, clip.right, clip.bottom) {
        withTransform({ transform(placement.matrix()) }) {
            drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
                dstSize = IntSize(image.width, image.height), alpha = alpha, filterQuality = FilterQuality.Low)
        }
    }
}
