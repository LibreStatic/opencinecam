/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

const val APV_REASON_API_BELOW_36 = "api-level-below-36"
const val APV_REASON_MIME_UNAVAILABLE = "apv-mime-unavailable"
const val APV_REASON_SURFACE_CONFIGURATION_FAILED = "apv-surface-configuration-failed"
const val APV_REASON_HARDWARE_ENCODER_UNAVAILABLE = "apv-hardware-encoder-unavailable"

/** There is no APV recording pipeline in the app, whatever the phone offers (PLAN-051, PLAN-053). */
const val APV_REASON_NOT_INTEGRATED = "apv-not-integrated"

/** What one `video/apv` encoder showed when asked for a 1080p30 Surface-input configuration. */
data class ApvEncoderFacts(val name: String, val hardwareAccelerated: Boolean, val surfaceInput: Boolean, val configureSucceeded: Boolean)

/**
 * Why APV is unsupported, from the same ladder as `ApvCapabilityDeviceTest`: the first rung that fails
 * is the device's reason; [APV_REASON_NOT_INTEGRATED] always follows, because capture never starts APV.
 */
fun apvUnsupportedReasons(sdkInt: Int, encoders: List<ApvEncoderFacts>): List<String> {
    val surfaceCandidates = encoders.filter { it.surfaceInput && it.configureSucceeded }
    val device = when {
        sdkInt < 36 -> APV_REASON_API_BELOW_36
        encoders.isEmpty() -> APV_REASON_MIME_UNAVAILABLE
        surfaceCandidates.isEmpty() -> APV_REASON_SURFACE_CONFIGURATION_FAILED
        surfaceCandidates.none { it.hardwareAccelerated } -> APV_REASON_HARDWARE_ENCODER_UNAVAILABLE
        else -> null
    }
    return listOfNotNull(device, APV_REASON_NOT_INTEGRATED)
}

/** Before (or without) a probe only the certain reason is known. */
val APV_UNPROBED_REASONS: List<String> = listOf(APV_REASON_NOT_INTEGRATED)

/** Enumerates and configures the device's APV encoders; no file, decode or sustained claim (IO, API 36+ only). */
fun probeApvReasons(): List<String> {
    val encoders = if (Build.VERSION.SDK_INT >= 36) {
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { it.isEncoder && !it.isAlias && it.supportedTypes.any(APV_MIME::equals) }
                .map(::probeApvEncoder)
        }.getOrDefault(emptyList())
    } else emptyList()
    return apvUnsupportedReasons(Build.VERSION.SDK_INT, encoders)
}

private fun probeApvEncoder(info: MediaCodecInfo): ApvEncoderFacts {
    val capabilities = info.getCapabilitiesForType(APV_MIME)
    val surface = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in capabilities.colorFormats
    val format = MediaFormat.createVideoFormat(APV_MIME, 1920, 1080).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, 120_000_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    var configured = false
    if (surface && runCatching { capabilities.isFormatSupported(format) }.getOrDefault(false)) {
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createByCodecName(info.name)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.createInputSurface().release()
            configured = true
        } catch (_: Exception) {
            // A refused configuration is the answer, not an error: it becomes a reason.
        } finally {
            runCatching { codec?.release() }
        }
    }
    return ApvEncoderFacts(info.name, info.isHardwareAccelerated, surface, configured)
}

private const val APV_MIME = "video/apv"
