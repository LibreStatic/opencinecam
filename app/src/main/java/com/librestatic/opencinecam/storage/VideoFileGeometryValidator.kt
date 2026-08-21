/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.librestatic.opencinecam.camera.RecordingGeometry

data class VideoFileGeometryValidation(
    val valid: Boolean,
    val encodedWidth: Int?,
    val encodedHeight: Int?,
    val rotationDegrees: Int?,
    val sarWidth: Int?,
    val sarHeight: Int?,
    val message: String,
)

class VideoFileGeometryValidator(private val context: Context) {
    fun validate(uri: Uri, expected: RecordingGeometry): VideoFileGeometryValidation {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val format = (0 until extractor.trackCount)
                .map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return invalid("The finalized MP4 has no video track.")
            val width = format.integerOrNull(MediaFormat.KEY_WIDTH)
            val height = format.integerOrNull(MediaFormat.KEY_HEIGHT)
            val rotation = format.integerOrNull(MediaFormat.KEY_ROTATION)?.normalizeRotation() ?: 0
            val sarWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.integerOrNull(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH)
            } else null
            val sarHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.integerOrNull(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT)
            } else null
            val dimensionsMatch = width == expected.encodedSize.width && height == expected.encodedSize.height
            val rotationMatches = rotation == expected.containerRotationDegrees
            val sarMatches = (sarWidth == null && sarHeight == null) ||
                (sarWidth == expected.pixelAspectRatioWidth && sarHeight == expected.pixelAspectRatioHeight)
            VideoFileGeometryValidation(
                valid = dimensionsMatch && rotationMatches && sarMatches,
                encodedWidth = width,
                encodedHeight = height,
                rotationDegrees = rotation,
                sarWidth = sarWidth,
                sarHeight = sarHeight,
                message = when {
                    !dimensionsMatch -> "Encoded dimensions are ${width}×${height}; expected ${expected.encodedSize.width}×${expected.encodedSize.height}."
                    !rotationMatches -> "Container rotation is $rotation°; expected ${expected.containerRotationDegrees}°."
                    !sarMatches -> "Pixel aspect ratio is ${sarWidth}:${sarHeight}; expected 1:1."
                    else -> "Video geometry matches the latched recording contract."
                },
            )
        } catch (failure: Throwable) {
            invalid(failure.message ?: "Video geometry could not be inspected.")
        } finally {
            extractor.release()
        }
    }

    private fun invalid(message: String) = VideoFileGeometryValidation(
        valid = false,
        encodedWidth = null,
        encodedHeight = null,
        rotationDegrees = null,
        sarWidth = null,
        sarHeight = null,
        message = message,
    )

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun Int.normalizeRotation(): Int = ((this % 360) + 360) % 360
}
