/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import android.media.MediaFormat

/** The operator's codec choice for normal SDR video; LOG and HLG keep their own HEVC Main10 pipeline. */
enum class VideoCodecPreference {
    /** HEVC when a hardware encoder supports the size and rate, otherwise H.264. */
    AUTO,
    H264,
    HEVC,
}

/** The codec an SDR take is actually encoded with. */
enum class VideoCodec(val mime: String, val label: String) {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264"),
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "HEVC"),
}

/** HEVC is only ever used when [hevcSupported]; otherwise every preference silently resolves to H.264. */
fun resolveVideoCodec(preference: VideoCodecPreference, hevcSupported: Boolean): VideoCodec = when (preference) {
    VideoCodecPreference.H264 -> VideoCodec.H264
    VideoCodecPreference.AUTO, VideoCodecPreference.HEVC -> if (hevcSupported) VideoCodec.HEVC else VideoCodec.H264
}

/** True when a hardware HEVC Main Surface encoder supports [width]x[height] at [fps]. */
fun hevcSdrSupported(width: Int, height: Int, fps: Int): Boolean =
    runCatching {
        VideoEncoderSelector().selectSurfaceEncoder(
            SurfaceEncoderPolicy.HEVC_MAIN_HARDWARE, width, height, fps, platformSurfaceEncoders(),
        ) != null
    }.getOrDefault(false)
