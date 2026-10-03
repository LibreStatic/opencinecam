/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.librestatic.opencinecam.storage.OcLogClip
import com.librestatic.opencinecam.storage.PreciseLogView
import com.librestatic.opencinecam.storage.PreciseVideoFrames
import com.librestatic.opencinecam.storage.oclog2CodesToArgb
import kotlin.math.roundToInt

/**
 * [count] sync frames centred in equal slices of the clip, [heightPx] tall. Blocking and interrupt-aware
 * (stops early, keeping the interrupt flag); never throws, skipping frames that fail to decode.
 * OCLog2 clips decode through [PreciseVideoFrames] (P010, the same view math as the paused frame),
 * because the platform retriever tone-maps whatever transfer the encoder tagged (PQ on some devices)
 * and its RGB is then not OCLog2 codes. If that reader is unavailable, the platform RGB is read back
 * as approximate OCLog2 codes and run through the review [view].
 */
fun loadFilmstrip(context: Context, uri: String, durationUs: Long, count: Int, heightPx: Int,
    log: OcLogClip?, view: PreciseLogView): List<Bitmap> {
    if (count <= 0 || heightPx <= 0) return emptyList()
    if (log != null) preciseLogFilmstrip(context, uri, count, heightPx, log, view)?.let { return it }
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

/**
 * A quick still of the clip's first sync frame, at most [maxEdgePx] on its long side, shown while the exact
 * reader indexes the clip and decodes frame 0 (seconds on long or 10-bit clips). Approximate by design:
 * OCLog2 clips go through the platform retriever and the review [view], like the filmstrip fallback.
 * Blocking; null when the platform cannot decode it.
 */
fun loadPoster(context: Context, uri: String, maxEdgePx: Int, log: OcLogClip?, view: PreciseLogView): Bitmap? {
    if (maxEdgePx <= 0) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, Uri.parse(uri))
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val (shownWidth, shownHeight) = if ((rotation % 360 + 360) % 360 in setOf(90, 270)) height to width else width to height
        val scale = minOf(1.0, maxEdgePx.toDouble() / maxOf(shownWidth, shownHeight))
        val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            (shownWidth * scale).roundToInt().coerceAtLeast(1), (shownHeight * scale).roundToInt().coerceAtLeast(1)) ?: return null
        if (log == null) frame else logThumbnail(frame, view)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** Null when the exact reader cannot open the clip; frames that fail individually are skipped. */
private fun preciseLogFilmstrip(context: Context, uri: String, count: Int, heightPx: Int, log: OcLogClip, view: PreciseLogView): List<Bitmap>? {
    val reader = runCatching { PreciseVideoFrames(context, uri, log = log.signal) }.getOrNull() ?: return null
    return try {
        reader.logView = view
        // Converting the whole 10-bit frame costs seconds; about twice the strip's height is enough before scaling.
        reader.cpuPixelStep = (reader.displayHeight / (heightPx * 2)).coerceAtLeast(1)
        val timestamps = reader.timeline.timestampsUs
        if (timestamps.isEmpty()) return null
        val widthPx = (heightPx.toDouble() * reader.displayWidth / reader.displayHeight.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
        val frames = ArrayList<Bitmap>(count)
        for (slot in 0 until count) {
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                break
            }
            val nominal = ((2L * slot + 1) * timestamps.size / (2L * count)).toInt().coerceIn(0, timestamps.lastIndex)
            // The nearest sync sample decodes alone; any other frame first decodes its whole GOP prefix.
            val index = reader.syncTimestampsUs.minByOrNull { kotlin.math.abs(it - timestamps[nominal]) }
                ?.let { reader.timeline.indexAt(it) } ?: nominal
            val frame = runCatching { reader.frame(index).bitmap }.getOrNull() ?: continue
            frames += Bitmap.createScaledBitmap(frame, widthPx, heightPx, true).also { if (it !== frame) frame.recycle() }
        }
        frames.takeIf { it.isNotEmpty() }
    } finally {
        runCatching { reader.close() }
    }
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
