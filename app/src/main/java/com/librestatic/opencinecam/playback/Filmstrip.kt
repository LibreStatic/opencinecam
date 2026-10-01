/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.librestatic.opencinecam.storage.OcLogClip
import com.librestatic.opencinecam.storage.PreciseLogView
import com.librestatic.opencinecam.storage.oclog2CodesToArgb
import kotlin.math.roundToInt

/**
 * [count] sync frames centred in equal slices of the clip, [heightPx] tall. Blocking and interrupt-aware
 * (stops early, keeping the interrupt flag); never throws, skipping frames that fail to decode.
 * For OCLog2 clips the platform's RGB is read back as approximate OCLog2 codes and run through the
 * review [view], so LOG thumbnails are an approximation of the shader-rendered picture.
 */
fun loadFilmstrip(context: Context, uri: String, durationUs: Long, count: Int, heightPx: Int,
    log: OcLogClip?, view: PreciseLogView): List<Bitmap> {
    if (count <= 0 || heightPx <= 0) return emptyList()
    val frames = ArrayList<Bitmap>(count)
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, Uri.parse(uri))
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val rotated = (rotation % 360 + 360) % 360 in setOf(90, 270)
        val aspect = if (width != null && height != null && width > 0 && height > 0)
            (if (rotated) height.toDouble() / width else width.toDouble() / height) else 16.0 / 9.0
        val widthPx = (heightPx * aspect).roundToInt().coerceAtLeast(1)
        val duration = durationUs.takeIf { it > 0 }
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000) ?: 0L
        for (index in 0 until count) {
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                break
            }
            val timeUs = duration * (2 * index + 1) / (2L * count)
            val frame = runCatching {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, widthPx, heightPx)
            }.getOrNull() ?: continue
            frames += if (log == null) frame else runCatching { logThumbnail(frame, view) }.getOrNull() ?: continue
        }
    } catch (_: Exception) {
    } finally {
        runCatching { retriever.release() }
    }
    return frames
}

private fun logThumbnail(frame: Bitmap, view: PreciseLogView): Bitmap {
    val source = if (frame.config == Bitmap.Config.HARDWARE) frame.copy(Bitmap.Config.ARGB_8888, false) else frame
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (i in pixels.indices) {
        val p = pixels[i]
        pixels[i] = oclog2CodesToArgb(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF, view)
    }
    val mapped = Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
    if (source !== frame) source.recycle()
    frame.recycle()
    return mapped
}
