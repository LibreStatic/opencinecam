/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Knowledge
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure

data class VideoEncoderRequest(
    val requestId: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val profileLevel: String?,
    val minPerformanceScore: Int = 0,
) {
    init {
        require(requestId.isNotBlank()) { "encoder request ID must not be blank" }
        require(mime == "video/avc" || mime == "video/hevc") { "only AVC and HEVC are supported" }
        require(width > 0 && height > 0 && fps > 0 && bitrate > 0) { "encoder dimensions and rates must be positive" }
        require(minPerformanceScore >= 0) { "minimum performance score must not be negative" }
    }
}

data class VideoEncoderCapability(
    val name: String,
    val mime: String,
    val hardwareAccelerated: Knowledge<Boolean>,
    val profileLevels: Knowledge<Set<String>>,
    val colorFormats: Knowledge<Set<Int>>,
    val widthAlignment: Knowledge<Int>,
    val heightAlignment: Knowledge<Int>,
    val bitrateRange: Knowledge<LongRange>,
    val fpsRange: Knowledge<IntRange>,
    val performanceScore: Knowledge<Int>,
) {
    init {
        require(name.isNotBlank() && mime.isNotBlank()) { "codec identity must not be blank" }
        require(widthAlignment !is Knowledge.Known || widthAlignment.value > 0) {
            "width alignment must be positive"
        }
        require(heightAlignment !is Knowledge.Known || heightAlignment.value > 0) {
            "height alignment must be positive"
        }
    }
}

data class SelectedVideoEncoder(
    val request: VideoEncoderRequest,
    val capability: VideoEncoderCapability,
)

sealed interface VideoEncoderSelection {
    data class Selected(val encoder: SelectedVideoEncoder) : VideoEncoderSelection
    data class Rejected(val failure: StableFailure) : VideoEncoderSelection
}

class VideoEncoderSelector(private val correlationId: String = "video-encoder") {
    fun select(request: VideoEncoderRequest, capabilities: List<VideoEncoderCapability>): VideoEncoderSelection {
        val candidates = capabilities.asSequence()
            .filter { it.mime == request.mime }
            .filter { supports(request, it) }
            .sortedWith(
                compareByDescending<VideoEncoderCapability> { hardwareRank(it.hardwareAccelerated) }
                    .thenByDescending { performanceRank(it.performanceScore) }
                    .thenBy { it.name },
            )
            .toList()
        val selected = candidates.firstOrNull()
        return if (selected != null) {
            VideoEncoderSelection.Selected(SelectedVideoEncoder(request, selected))
        } else {
            VideoEncoderSelection.Rejected(
                StableFailure(
                    component = "video-encoder",
                    code = FailureCode.ENCODER_CONFIGURATION_FAILED,
                    severity = FailureSeverity.ERROR,
                    recoverability = Recoverability.UNSUPPORTED,
                    correlationId = correlationId,
                    userMessage = "No AVC/HEVC Surface encoder satisfies the requested configuration.",
                ),
            )
        }
    }

    private fun supports(request: VideoEncoderRequest, capability: VideoEncoderCapability): Boolean {
        if (!known(capability.colorFormats, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) return false
        if (capability.widthAlignment !is Knowledge.Known || capability.heightAlignment !is Knowledge.Known) return false
        val widthAlignment = (capability.widthAlignment as Knowledge.Known).value
        val heightAlignment = (capability.heightAlignment as Knowledge.Known).value
        if (request.width % widthAlignment != 0 || request.height % heightAlignment != 0) return false
        if (!inLongRange(capability.bitrateRange, request.bitrate.toLong())) return false
        if (!inIntRange(capability.fpsRange, request.fps)) return false
        if (!atLeast(capability.performanceScore, request.minPerformanceScore)) return false
        if (request.profileLevel != null && !contains(capability.profileLevels, request.profileLevel)) return false
        return true
    }

    private fun hardwareRank(value: Knowledge<Boolean>): Int = when (value) {
        is Knowledge.Known -> if (value.value) 1 else 0
        Knowledge.Unknown, is Knowledge.Unsupported -> -1
    }

    private fun performanceRank(value: Knowledge<Int>): Int = when (value) {
        is Knowledge.Known -> value.value
        Knowledge.Unknown, is Knowledge.Unsupported -> -1
    }

    private fun known(values: Knowledge<Set<Int>>, required: Int): Boolean =
        values is Knowledge.Known && required in values.value

    private fun inLongRange(values: Knowledge<LongRange>, required: Long): Boolean =
        values is Knowledge.Known && required in values.value

    private fun inIntRange(values: Knowledge<IntRange>, required: Int): Boolean =
        values is Knowledge.Known && required in values.value

    private fun atLeast(values: Knowledge<Int>, required: Int): Boolean =
        values is Knowledge.Known && values.value >= required

    private fun contains(values: Knowledge<Set<String>>, required: String): Boolean =
        values is Knowledge.Known && required in values.value
}

data class ConfiguredVideoEncoder(
    val codec: MediaCodec,
    val inputSurface: Surface,
    val selection: SelectedVideoEncoder,
) : AutoCloseable {
    override fun close() {
        runCatching { inputSurface.release() }
        runCatching { codec.stop() }
        codec.release()
    }
}

/** Applies the accepted Surface/VBR/one-second-keyframe configuration without fallback. */
class VideoEncoderConfigurator {
    fun configure(selection: SelectedVideoEncoder): ConfiguredVideoEncoder {
        val request = selection.request
        val codec = try {
            MediaCodec.createByCodecName(selection.capability.name)
        } catch (_: Throwable) {
            throw IllegalStateException("The selected encoder could not be created.")
        }
        return try {
            val format = MediaFormat.createVideoFormat(request.mime, request.width, request.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, request.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, request.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ConfiguredVideoEncoder(codec, codec.createInputSurface(), selection)
        } catch (error: Throwable) {
            codec.release()
            throw IllegalStateException("The selected encoder configuration failed.", error)
        }
    }
}
