/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure
import com.librestatic.opencinecam.media.mux.MuxerTrack

data class AacEncodingRequest(
    val sampleRate: Int = 48_000,
    val channels: Int,
    val bitrate: Int,
    val maxInputBytes: Int = 16 * 1024,
) {
    init {
        require(sampleRate > 0 && channels in 1..2 && bitrate > 0 && maxInputBytes > 0)
    }
}

sealed interface AacEncodingConfiguration {
    data class Configured(val request: AacEncodingRequest) : AacEncodingConfiguration
    data class Rejected(val failure: StableFailure) : AacEncodingConfiguration
}

fun configureAac(request: AacEncodingRequest, correlationId: String = "aac"): AacEncodingConfiguration {
    val expectedBitrate = if (request.channels == 2) 192_000 else 96_000
    if (request.sampleRate != 48_000 || request.bitrate != expectedBitrate) {
        return AacEncodingConfiguration.Rejected(
            StableFailure(
                component = "aac-encoder",
                code = FailureCode.ENCODER_CONFIGURATION_FAILED,
                severity = FailureSeverity.ERROR,
                recoverability = Recoverability.UNSUPPORTED,
                correlationId = correlationId,
                userMessage = "AAC-LC requires 48 kHz PCM and the channel-specific target bitrate.",
            ),
        )
    }
    return AacEncodingConfiguration.Configured(request)
}

class AacEncoderConfigurator {
    fun create(configuration: AacEncodingConfiguration.Configured): MediaCodec {
        val request = configuration.request
        val codec = try {
            MediaCodec.createEncoderByType("audio/mp4a-latm")
        } catch (error: Throwable) {
            throw IllegalStateException("AAC encoder could not be created.", error)
        }
        return try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", request.sampleRate, request.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, request.bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, request.maxInputBytes)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec
        } catch (error: Throwable) {
            codec.release()
            throw IllegalStateException("AAC encoder configuration failed.", error)
        }
    }
}

data class AudioTrackPlan(val enabled: Boolean) {
    val microphoneRequired: Boolean get() = enabled
    val expectedMuxerTracks: Set<MuxerTrack>
        get() = if (enabled) setOf(MuxerTrack.VIDEO, MuxerTrack.AUDIO) else setOf(MuxerTrack.VIDEO)

    fun submitAudioEos(submit: (MuxerTrack) -> Boolean): Boolean = !enabled || submit(MuxerTrack.AUDIO)
}
