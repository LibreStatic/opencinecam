/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.playback

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.librestatic.opencinecam.camera.Main10FileSignal
import java.util.Locale

/** What a published clip's first video track says about itself; any missing fact stays null (UNKNOWN, never a pass). */
internal fun main10SignalOf(mime: String?, profile: Int?, colorStandard: Int?, colorTransfer: Int?): Main10FileSignal = Main10FileSignal(
    mime = mime?.lowercase(Locale.ROOT),
    profile = when (mime?.lowercase(Locale.ROOT) to profile) {
        MediaFormat.MIMETYPE_VIDEO_HEVC to CodecProfileLevel.HEVCProfileMain10,
        MediaFormat.MIMETYPE_VIDEO_HEVC to CodecProfileLevel.HEVCProfileMain10HDR10,
        MediaFormat.MIMETYPE_VIDEO_HEVC to CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main10"
        MediaFormat.MIMETYPE_VIDEO_HEVC to CodecProfileLevel.HEVCProfileMain -> "Main"
        else -> profile?.let { "Profile $it" }
    },
    colorStandard = when (colorStandard) {
        MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020"
        MediaFormat.COLOR_STANDARD_BT709 -> "BT.709"
        MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> "BT.601"
        else -> null
    },
    colorTransfer = when (colorTransfer) {
        MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
        MediaFormat.COLOR_TRANSFER_ST2084 -> "PQ"
        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
        else -> null
    },
    bitDepth = profileBitDepth(mime, profile),
)

/** Blocking read of the container's own tags; call off the main and camera threads. Null when the clip cannot be read. */
internal fun probeMain10FileSignal(context: Context, uri: String): Main10FileSignal? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, Uri.parse(uri), null)
        val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
        fun int(key: String) = if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull() else null
        main10SignalOf(format.getString(MediaFormat.KEY_MIME), int(MediaFormat.KEY_PROFILE),
            int(MediaFormat.KEY_COLOR_STANDARD), int(MediaFormat.KEY_COLOR_TRANSFER))
    } catch (_: Exception) {
        null
    } finally {
        runCatching { extractor.release() }
    }
}
