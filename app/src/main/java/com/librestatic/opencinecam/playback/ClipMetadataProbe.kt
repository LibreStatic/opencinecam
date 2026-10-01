/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.librestatic.opencinecam.storage.OcLogClip
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Display (rotation-applied) facts about a clip's first video track. */
data class ClipMetadata(val width: Int, val height: Int, val frameRate: Double?, val codec: String?,
    val bitDepth: Int?, val color: String?, val durationUs: Long?)

/** Blocking; call off the main thread. Null when the clip cannot be read or has no video track. */
fun probeClipMetadata(context: Context, uri: String, log: OcLogClip?): ClipMetadata? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, Uri.parse(uri), null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: return null
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)
        val width = format.intOrNull(MediaFormat.KEY_WIDTH) ?: return null
        val height = format.intOrNull(MediaFormat.KEY_HEIGHT) ?: return null
        val rotated = (format.intOrNull(MediaFormat.KEY_ROTATION) ?: 0).let { (it % 360 + 360) % 360 } in setOf(90, 270)
        val profile = format.intOrNull(MediaFormat.KEY_PROFILE)
        val bitDepth = profileBitDepth(mime, profile) ?: if (log != null) 10 else null
        val declaredFps = format.numberOrNull(MediaFormat.KEY_FRAME_RATE)?.takeIf { it > 0.0 }
        extractor.selectTrack(track)
        ClipMetadata(
            width = if (rotated) height else width,
            height = if (rotated) width else height,
            frameRate = estimateFrameRate(extractor) ?: declaredFps,
            codec = codecLabel(mime, profile, bitDepth),
            bitDepth = bitDepth,
            color = colorLabel(format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER), format.intOrNull(MediaFormat.KEY_COLOR_STANDARD), log),
            durationUs = format.longOrNull(MediaFormat.KEY_DURATION)?.takeIf { it > 0 },
        )
    } catch (_: Exception) {
        null
    } finally {
        runCatching { extractor.release() }
    }
}

private fun estimateFrameRate(extractor: MediaExtractor): Double? {
    val times = ArrayList<Long>(60)
    while (times.size < 60) {
        val time = extractor.sampleTime
        if (time < 0) break
        times += time
        if (!extractor.advance()) break
    }
    return frameRateFromSampleTimes(times)
}

/** Presentation times in decode order (B-frames allowed); null below three distinct frames. */
internal fun frameRateFromSampleTimes(times: List<Long>): Double? {
    val sorted = times.distinct().sorted()
    if (sorted.size < 3) return null
    val span = sorted.last() - sorted.first()
    return if (span > 0) (sorted.size - 1) * 1_000_000.0 / span else null
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

private fun MediaFormat.numberOrNull(key: String): Double? = if (!containsKey(key)) null else
    runCatching { getInteger(key).toDouble() }.recoverCatching { getFloat(key).toDouble() }.getOrNull()

/** "4K 3840×2160", "1080p 1920×1080", "720p 1280×720"; otherwise "W×H". Class uses the short edge. */
fun resolutionLabel(width: Int, height: Int): String {
    val size = "$width×$height"
    val short = minOf(width, height)
    val long = maxOf(width, height)
    val name = when {
        short == 4320 && long >= 7680 -> "8K"
        short == 2160 && long >= 3840 -> "4K"
        short == 1440 && long >= 2560 -> "1440p"
        short == 1080 && long >= 1920 -> "1080p"
        short == 720 && long >= 1280 -> "720p"
        else -> return size
    }
    return "$name $size"
}

/** Snaps to integer or NTSC (N/1.001) rates when within 0.05%: "23.976 fps", "24 fps", "29.97 fps". */
fun frameRateLabel(fps: Double): String {
    if (!fps.isFinite() || fps <= 0.0) return "0 fps"
    val nearest = fps.roundToInt().coerceAtLeast(1)
    val ntsc = nearest * 1000.0 / 1001.0
    val tolerance = nearest * 0.0005
    val value = when {
        abs(fps - nearest) <= tolerance && abs(fps - nearest) <= abs(fps - ntsc) -> nearest.toDouble()
        abs(fps - ntsc) <= tolerance -> ntsc
        else -> fps
    }
    val precision = if (value == ntsc) 3 else 2
    return "${trimDecimals(String.format(Locale.ROOT, "%.${precision}f", value))} fps"
}

private fun trimDecimals(text: String) = if ('.' in text) text.trimEnd('0').trimEnd('.') else text

/** Bit depth implied by a MediaFormat codec profile, or null when the profile does not fix it. */
fun profileBitDepth(mime: String?, profile: Int?): Int? = when (mime?.lowercase(Locale.ROOT)) {
    MediaFormat.MIMETYPE_VIDEO_HEVC -> when (profile) {
        CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCProfileMainStill -> 8
        CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10,
        CodecProfileLevel.HEVCProfileMain10HDR10Plus -> 10
        else -> null
    }
    MediaFormat.MIMETYPE_VIDEO_AVC -> when (profile) {
        CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCProfileConstrainedBaseline,
        CodecProfileLevel.AVCProfileMain, CodecProfileLevel.AVCProfileExtended,
        CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCProfileConstrainedHigh -> 8
        CodecProfileLevel.AVCProfileHigh10 -> 10
        else -> null
    }
    MediaFormat.MIMETYPE_VIDEO_AV1 -> when (profile) {
        CodecProfileLevel.AV1ProfileMain8 -> 8
        CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10,
        CodecProfileLevel.AV1ProfileMain10HDR10Plus -> 10
        else -> null
    }
    MediaFormat.MIMETYPE_VIDEO_VP9 -> when (profile) {
        CodecProfileLevel.VP9Profile0, CodecProfileLevel.VP9Profile1 -> 8
        CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile2HDR10Plus -> 10
        else -> null
    }
    else -> null
}

/** "HEVC 10-bit", "AVC 8-bit", "AV1 10-bit"; [bitDepth] wins over the one the profile implies. */
fun codecLabel(mime: String?, profile: Int?, bitDepth: Int?): String? {
    val normalized = mime?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
    val name = when (normalized) {
        MediaFormat.MIMETYPE_VIDEO_HEVC -> "HEVC"
        MediaFormat.MIMETYPE_VIDEO_AVC -> "AVC"
        MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
        MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
        MediaFormat.MIMETYPE_VIDEO_VP8 -> "VP8"
        MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "MPEG-4"
        MediaFormat.MIMETYPE_VIDEO_H263 -> "H.263"
        else -> normalized.substringAfter('/').uppercase(Locale.ROOT)
    }
    val depth = bitDepth ?: profileBitDepth(normalized, profile)
    return if (depth != null) "$name $depth-bit" else name
}

/** "OCLog2 · BT.2020" for sidecar-declared LOG, else "HLG · BT.2020", "PQ · BT.2020", "SDR · BT.709", … */
fun colorLabel(transfer: Int?, standard: Int?, log: OcLogClip?): String? {
    if (log != null) return "OCLog2 · BT.2020"
    val primaries = when (standard) {
        MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020"
        MediaFormat.COLOR_STANDARD_BT709 -> "BT.709"
        MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> "BT.601"
        else -> null
    }
    val curve = when (transfer) {
        MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
        MediaFormat.COLOR_TRANSFER_ST2084 -> "PQ"
        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
        else -> if (primaries == "BT.709" || primaries == "BT.601") "SDR" else null
    }
    val gamut = primaries ?: if (curve == "HLG" || curve == "PQ") "BT.2020" else null
    return listOfNotNull(curve, gamut).joinToString(" · ").ifEmpty { null }
}

/** "0:12", "12:34", "1:02:03"; rounded to the nearest second. */
fun durationLabel(us: Long): String {
    val total = ((us.coerceAtLeast(0) + 500_000) / 1_000_000)
    val hours = total / 3600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}
