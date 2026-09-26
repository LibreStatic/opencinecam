/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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

/** Whether a production Surface pick may fall back to a software codec (timelapse AVC only). */
enum class EncoderHardwarePolicy { REQUIRE_HARDWARE, ALLOW_SOFTWARE }

/** Profile rule of a production Surface pick; both reproduce the OpenCineLog GPU pipeline exactly. */
enum class EncoderProfileRule {
    /** First advertised HEVC Main10, Main10 HDR10 or Main10 HDR10+ profile, in advertised order. No level is reported. */
    HEVC_MAIN10_HDR,
    /** AVC High when advertised, else the numerically highest advertised profile; reports that profile's highest level. */
    AVC_HIGH_ELSE_HIGHEST,
}

data class SurfaceEncoderPolicy(
    val mime: String,
    val hardware: EncoderHardwarePolicy,
    val profile: EncoderProfileRule,
) {
    companion object {
        /** LOG/HEVC recording: hardware Main10 only, so an emulator's software HEVC is never accepted. */
        val HEVC_MAIN10_HARDWARE = SurfaceEncoderPolicy(
            MediaFormat.MIMETYPE_VIDEO_HEVC, EncoderHardwarePolicy.REQUIRE_HARDWARE, EncoderProfileRule.HEVC_MAIN10_HDR,
        )

        /** SDR/AVC recording; [allowSoftware] is true only for timelapse. */
        fun avcHigh(allowSoftware: Boolean) = SurfaceEncoderPolicy(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            if (allowSoftware) EncoderHardwarePolicy.ALLOW_SOFTWARE else EncoderHardwarePolicy.REQUIRE_HARDWARE,
            EncoderProfileRule.AVC_HIGH_ELSE_HIGHEST,
        )
    }
}

data class EncoderProfileLevel(val profile: Int, val level: Int)

/** Platform-free view of one type's CodecCapabilities; a null [sizeAndRateSupported] means no video capabilities. */
class SurfaceEncoderCaps(
    val colorFormats: List<Int>,
    val profileLevels: List<EncoderProfileLevel>,
    val sizeAndRateSupported: ((width: Int, height: Int, fps: Double) -> Boolean)?,
)

/** Platform-free view of one MediaCodecInfo; [capabilities] may throw, like getCapabilitiesForType. */
class SurfaceEncoderInfo(
    val name: String,
    val isEncoder: Boolean,
    val isAlias: Boolean,
    val hardwareAccelerated: Boolean,
    val supportedTypes: List<String>,
    val capabilities: (String) -> SurfaceEncoderCaps,
)

data class SurfaceEncoderPick(
    val codecName: String,
    val profile: Int,
    val hardwareAccelerated: Boolean,
    val level: Int?,
)

fun MediaCodecInfo.asSurfaceEncoderInfo(): SurfaceEncoderInfo = SurfaceEncoderInfo(
    name = name,
    isEncoder = isEncoder,
    isAlias = isAlias,
    hardwareAccelerated = isHardwareAccelerated,
    supportedTypes = supportedTypes.toList(),
    capabilities = { type ->
        val caps = getCapabilitiesForType(type)
        val video = caps.videoCapabilities
        SurfaceEncoderCaps(
            colorFormats = caps.colorFormats.toList(),
            profileLevels = caps.profileLevels.map { EncoderProfileLevel(it.profile, it.level) },
            sizeAndRateSupported = video?.let { v -> { w, h, fps -> v.areSizeAndRateSupported(w, h, fps) } },
        )
    },
)

/** Every codec the platform advertises (ALL_CODECS), lazily adapted. */
fun platformSurfaceEncoders(): Sequence<SurfaceEncoderInfo> =
    MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.asSequence().map { it.asSurfaceEncoderInfo() }

class VideoEncoderSelector(private val correlationId: String = "video-encoder") {
    /**
     * Production Surface-encoder pick. Candidates are ordered hardware first, then by codec name;
     * the first one whose capabilities advertise a Surface input, a profile allowed by
     * [SurfaceEncoderPolicy.profile] and `areSizeAndRateSupported(width, height, fps)` wins.
     * There is no bitrate gate: the pipeline clamps its own bitrate.
     */
    fun selectSurfaceEncoder(
        policy: SurfaceEncoderPolicy,
        width: Int,
        height: Int,
        fps: Int,
        codecs: Sequence<SurfaceEncoderInfo>,
    ): SurfaceEncoderPick? = codecs
        .filter { it.isEncoder && !it.isAlias && (policy.hardware == EncoderHardwarePolicy.ALLOW_SOFTWARE || it.hardwareAccelerated) }
        .filter { it.supportedTypes.any { type -> type.equals(policy.mime, true) } }
        .sortedWith(compareByDescending<SurfaceEncoderInfo> { it.hardwareAccelerated }.thenBy { it.name })
        .mapNotNull { info ->
            val caps = runCatching { info.capabilities(policy.mime) }.getOrNull() ?: return@mapNotNull null
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) return@mapNotNull null
            val profiles = caps.profileLevels.map { it.profile }
            val profile = when (policy.profile) {
                EncoderProfileRule.HEVC_MAIN10_HDR -> profiles.firstOrNull {
                    it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                }
                EncoderProfileRule.AVC_HIGH_ELSE_HIGHEST ->
                    profiles.firstOrNull { it == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh } ?: profiles.maxOrNull()
            } ?: return@mapNotNull null
            if (caps.sizeAndRateSupported?.invoke(width, height, fps.toDouble()) != true) return@mapNotNull null
            SurfaceEncoderPick(
                info.name, profile, info.hardwareAccelerated,
                if (policy.profile == EncoderProfileRule.AVC_HIGH_ELSE_HIGHEST) {
                    caps.profileLevels.filter { it.profile == profile }.maxOf { it.level }
                } else null,
            )
        }
        .firstOrNull()

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
