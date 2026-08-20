/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Size
import com.librestatic.opencinecam.core.model.Knowledge

data class StreamSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "stream dimensions must be positive" }
    }
}

data class StreamCapabilityMetadata(
    val cameraId: String,
    val previewSizes: List<StreamSize>?,
    val rawSizes: List<StreamSize>?,
    val highSpeedSizes: List<StreamSize>?,
    val highSpeedFpsRanges: Map<StreamSize, List<IntRange>>?,
    val targetFpsRanges: List<IntRange>?,
    val manualSensor: Boolean?,
    val manualPostProcessing: Boolean?,
    val dynamicRangeProfiles: Set<String>?,
    val colorSpaceProfiles: Set<String>?,
)

fun interface StreamMetadataSource {
    fun metadata(cameraId: String): StreamCapabilityMetadata
}

class Camera2StreamMetadataSource(private val manager: CameraManager) : StreamMetadataSource {
    override fun metadata(cameraId: String): StreamCapabilityMetadata {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val capabilities = (characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()).toSet()
        val manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
        val manualPost = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities
        return StreamCapabilityMetadata(
            cameraId = cameraId,
            previewSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.map(Size::toStreamSize),
            rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.map(Size::toStreamSize),
            highSpeedSizes = map?.highSpeedVideoSizes?.map(Size::toStreamSize),
            highSpeedFpsRanges = map?.highSpeedVideoSizes?.associate { size ->
                size.toStreamSize() to map.getHighSpeedVideoFpsRangesFor(size).map { it.toIntRange() }
            },
            targetFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { it.toIntRange() },
            manualSensor = manualSensor,
            manualPostProcessing = manualPost,
            dynamicRangeProfiles = dynamicRangeProfiles(characteristics),
            colorSpaceProfiles = colorSpaceProfiles(characteristics),
        )
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun dynamicRangeProfiles(characteristics: CameraCharacteristics): Set<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val profiles = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            ?: return emptySet()
        return profiles.supportedProfiles.map(::dynamicRangeName).toSortedSet()
    }

    @android.annotation.TargetApi(34)
    private fun colorSpaceProfiles(characteristics: CameraCharacteristics): Set<String>? {
        if (Build.VERSION.SDK_INT < 34) return null
        val profiles = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
            ?: return emptySet()
        return profiles.getSupportedColorSpaces(ImageFormat.YUV_420_888).map { it.toString() }.toSortedSet()
    }
}

data class StreamCapabilityReport(
    val cameraId: String,
    val previewSizes: Knowledge<List<StreamSize>>,
    val rawSizes: Knowledge<List<StreamSize>>,
    val highSpeedSizes: Knowledge<List<StreamSize>>,
    val highSpeedFpsRanges: Knowledge<Map<StreamSize, List<IntRange>>>,
    val targetFpsRanges: Knowledge<List<IntRange>>,
    val manualSensor: Knowledge<Boolean>,
    val manualPostProcessing: Knowledge<Boolean>,
    val dynamicRangeProfiles: Knowledge<Set<String>>,
    val colorSpaceProfiles: Knowledge<Set<String>>,
)

class StreamCapabilityProbe(private val source: StreamMetadataSource) {
    fun probe(cameraId: String): StreamCapabilityReport {
        val metadata = source.metadata(cameraId)
        return StreamCapabilityReport(
            cameraId = cameraId,
            previewSizes = metadata.previewSizes.knownSorted(),
            rawSizes = metadata.rawSizes.knownSorted(),
            highSpeedSizes = metadata.highSpeedSizes.knownSorted(),
            highSpeedFpsRanges = metadata.highSpeedFpsRanges.knownSortedMap(),
            targetFpsRanges = metadata.targetFpsRanges.knownRanges(),
            manualSensor = metadata.manualSensor.knownValue(),
            manualPostProcessing = metadata.manualPostProcessing.knownValue(),
            dynamicRangeProfiles = metadata.dynamicRangeProfiles.knownSet(),
            colorSpaceProfiles = metadata.colorSpaceProfiles.knownSet(),
        )
    }
}

private fun Size.toStreamSize() = StreamSize(width, height)

private fun Range<Int>.toIntRange() = lower..upper

private fun <T : Comparable<T>> List<T>.sortedDistinct() = distinct().sorted()

private fun List<StreamSize>?.knownSorted(): Knowledge<List<StreamSize>> =
    this?.let { Knowledge.Known(it.distinct().sortedWith(compareBy({ it.width }, { it.height }))) } ?: Knowledge.Unknown

private fun Map<StreamSize, List<IntRange>>?.knownSortedMap(): Knowledge<Map<StreamSize, List<IntRange>>> =
    this?.let { Knowledge.Known(it.toSortedMap(compareBy<StreamSize> { it.width }.thenBy { it.height })) } ?: Knowledge.Unknown

private fun List<IntRange>?.knownRanges(): Knowledge<List<IntRange>> =
    this?.let { Knowledge.Known(it.distinct().sortedWith(compareBy({ it.first }, { it.last }))) } ?: Knowledge.Unknown

private fun Boolean?.knownValue(): Knowledge<Boolean> = this?.let { Knowledge.Known(it) } ?: Knowledge.Unknown

private fun Set<String>?.knownSet(): Knowledge<Set<String>> =
    this?.let { Knowledge.Known(it.filter(String::isNotBlank).toSortedSet()) } ?: Knowledge.Unknown

private fun dynamicRangeName(profile: Long): String = when (profile) {
    DynamicRangeProfiles.STANDARD -> "SDR"
    DynamicRangeProfiles.HLG10 -> "HLG10"
    DynamicRangeProfiles.HDR10 -> "HDR10"
    DynamicRangeProfiles.HDR10_PLUS -> "HDR10_PLUS"
    DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF -> "DOLBY_VISION_10B_HDR_REF"
    DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF -> "DOLBY_VISION_8B_HDR_REF"
    DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO -> "DOLBY_VISION_10B_HDR_REF_PO"
    DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO -> "DOLBY_VISION_8B_HDR_REF_PO"
    else -> "unknown-$profile"
}
