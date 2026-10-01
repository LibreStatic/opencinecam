/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.ColorSpaceProfiles
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MandatoryStreamCombination
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF

/** One advertised characteristic, already rendered as text. */
data class CharacteristicEntry(val key: String, val value: String) {
    /** `android.control.aeAvailableModes` → `control`; vendor tags keep their own namespace. */
    val section: String
        get() = if (key.startsWith("android.")) key.removePrefix("android.").substringBefore('.') else VENDOR_SECTION

    val vendor: Boolean get() = section == VENDOR_SECTION

    companion object {
        const val VENDOR_SECTION = "vendor"
    }
}

/**
 * Everything the camera service advertises for one camera id, without interpretation.
 * [logicalParentId] is set for physical members that are only reachable through a logical camera.
 */
data class CameraInventory(
    val cameraId: String,
    val logicalParentId: String?,
    val entries: List<CharacteristicEntry>,
    val error: String? = null,
    val lensFacing: Int? = null,
    val focalLengthsMm: List<Float> = emptyList(),
) {
    fun value(key: String): String? = entries.firstOrNull { it.key == key }?.value
}

/** Reads every characteristic of every camera (logical ids first, then hidden physical members). */
class Camera2CapabilityInventory(private val manager: CameraManager) {
    fun read(): List<CameraInventory> {
        val ids = runCatching { manager.cameraIdList.toList() }.getOrElse { return emptyList() }
        val listed = ids.toSet()
        val result = mutableListOf<CameraInventory>()
        val physicalSeen = mutableSetOf<String>()
        ids.sortedWith(cameraIdOrder).forEach { id ->
            val characteristics = runCatching { manager.getCameraCharacteristics(id) }
                .getOrElse { error ->
                    result += CameraInventory(id, null, emptyList(), error.javaClass.simpleName)
                    return@forEach
                }
            result += inventory(id, null, characteristics)
            runCatching { characteristics.physicalCameraIds }.getOrDefault(emptySet())
                .filter { it !in listed && physicalSeen.add(it) }
                .sortedWith(cameraIdOrder)
                .forEach { physicalId ->
                    result += runCatching { inventory(physicalId, id, manager.getCameraCharacteristics(physicalId)) }
                        .getOrElse { error -> CameraInventory(physicalId, id, emptyList(), error.javaClass.simpleName) }
                }
        }
        return result
    }

    private fun inventory(id: String, parent: String?, characteristics: CameraCharacteristics): CameraInventory {
        val entries = characteristics.keys.mapNotNull { key ->
            @Suppress("UNCHECKED_CAST")
            val value = runCatching { characteristics.get(key as CameraCharacteristics.Key<Any>) }.getOrNull()
                ?: return@mapNotNull null
            CharacteristicEntry(key.name, CharacteristicFormatter.format(key.name, value))
        }.toMutableList()
        // Which request, result and session controls exist is as much a capability as any static key.
        runCatching { characteristics.availableCaptureRequestKeys.map { it.name } }.getOrNull()
            ?.let { entries += CharacteristicEntry(REQUEST_KEYS, it.sorted().joinToString("\n")) }
        runCatching { characteristics.availableCaptureResultKeys.map { it.name } }.getOrNull()
            ?.let { entries += CharacteristicEntry(RESULT_KEYS, it.sorted().joinToString("\n")) }
        runCatching { characteristics.availableSessionKeys?.map { it.name } }.getOrNull()
            ?.let { entries += CharacteristicEntry(SESSION_KEYS, it.sorted().joinToString("\n")) }
        runCatching { characteristics.physicalCameraIds }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?.let { entries += CharacteristicEntry(PHYSICAL_IDS, it.sortedWith(cameraIdOrder).joinToString(", ")) }
        return CameraInventory(
            cameraId = id,
            logicalParentId = parent,
            entries = entries.distinctBy { it.key }.sortedBy { it.key },
            lensFacing = runCatching { characteristics.get(CameraCharacteristics.LENS_FACING) }.getOrNull(),
            focalLengthsMm = runCatching { characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() }
                .getOrNull().orEmpty(),
        )
    }

    companion object {
        const val REQUEST_KEYS = "android.request.availableCaptureRequestKeys"
        const val RESULT_KEYS = "android.request.availableCaptureResultKeys"
        const val SESSION_KEYS = "android.request.availableSessionKeys"
        const val PHYSICAL_IDS = "android.logicalMultiCamera.physicalIds"

        /** Numeric ids in numeric order ("2" before "10"), anything else after them. */
        val cameraIdOrder: Comparator<String> = compareBy<String>({ it.toIntOrNull() == null }, { it.toIntOrNull() ?: 0 }, { it })
    }
}

/** Renders characteristic values; integer enums of well-known keys are decoded to their constant names. */
object CharacteristicFormatter {
    fun format(key: String, value: Any?): String {
        val names = ENUMS[key]
        if (names != null) {
            when (value) {
                is Int -> return names.label(value)
                is Long -> return names.label(value.toInt())
                is IntArray -> return if (value.isEmpty()) EMPTY else value.joinToString(", ") { names.label(it) }
                is LongArray -> return if (value.isEmpty()) EMPTY else value.joinToString(", ") { names.label(it.toInt()) }
            }
        }
        return generic(value)
    }

    fun generic(value: Any?): String = when (value) {
        null -> EMPTY
        is IntArray -> value.joinToString(", ").ifEmpty { EMPTY }
        is LongArray -> value.joinToString(", ").ifEmpty { EMPTY }
        is FloatArray -> value.joinToString(", ") { number(it) }.ifEmpty { EMPTY }
        is DoubleArray -> value.joinToString(", ").ifEmpty { EMPTY }
        is BooleanArray -> value.joinToString(", ").ifEmpty { EMPTY }
        is ByteArray -> if (value.size > 64) "${value.size} bytes" else value.joinToString(" ") { "%02x".format(it) }.ifEmpty { EMPTY }
        is Float -> number(value)
        is Array<*> -> if (value.isEmpty()) EMPTY else value.joinToString(if (value.any { it is MandatoryStreamCombination }) "\n" else ", ") { generic(it) }
        is Collection<*> -> if (value.isEmpty()) EMPTY else value.joinToString(", ") { generic(it) }
        else -> android(value) ?: value.toString()
    }

    private fun android(value: Any): String? = when (value) {
        is Range<*> -> "${generic(value.lower)} – ${generic(value.upper)}"
        is Size -> "${value.width}×${value.height}"
        is SizeF -> "${number(value.width)} × ${number(value.height)}"
        is Rect -> "${value.left}, ${value.top}, ${value.right}, ${value.bottom} (${value.width()}×${value.height()})"
        is Rational -> "${value.numerator}/${value.denominator}"
        is StreamConfigurationMap -> streams(value)
        is MandatoryStreamCombination -> value.description.toString()
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) profiles(value) else null
    }

    private fun profiles(value: Any): String? = when {
        value is DynamicRangeProfiles -> value.supportedProfiles.sorted().joinToString(", ") { DYNAMIC_RANGE.label(it.toInt()) }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && value is ColorSpaceProfiles ->
            value.getSupportedColorSpaces(ImageFormat.UNKNOWN).sortedBy { it.name }.joinToString("\n") { space ->
                "${space.name}: " + value.getSupportedImageFormatsForColorSpace(space).sorted().joinToString(", ") { IMAGE_FORMATS.label(it) }
            }
        else -> null
    }

    /** Every output format with its size count and largest size, then the high-speed table. */
    private fun streams(map: StreamConfigurationMap): String = buildList {
        runCatching { map.outputFormats }.getOrDefault(intArrayOf()).sorted().forEach { format ->
            val sizes = runCatching { map.getOutputSizes(format)?.toList() }.getOrNull().orEmpty()
            val high = runCatching { map.getHighResolutionOutputSizes(format)?.toList() }.getOrNull().orEmpty()
            val largest = (sizes + high).maxByOrNull { it.width.toLong() * it.height }
            add("${IMAGE_FORMATS.label(format)}: ${sizes.size} sizes" +
                (largest?.let { ", max ${it.width}×${it.height}" } ?: "") +
                (if (high.isNotEmpty()) " (${high.size} high-res)" else ""))
        }
        runCatching { map.highSpeedVideoSizes.toList() }.getOrDefault(emptyList()).forEach { size ->
            val ranges = runCatching { map.getHighSpeedVideoFpsRangesFor(size).toList() }.getOrDefault(emptyList())
            add("HIGH_SPEED ${size.width}×${size.height}: " + ranges.joinToString(", ") { "${it.lower}–${it.upper}" })
        }
    }.joinToString("\n").ifEmpty { EMPTY }

    private fun number(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, value).trimEnd('0')

    private fun Map<Int, String>.label(value: Int): String = get(value)?.let { "$it ($value)" } ?: value.toString()

    private const val EMPTY = "—"

    private val CAPABILITIES = mapOf(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE to "BACKWARD_COMPATIBLE",
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR to "MANUAL_SENSOR",
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING to "MANUAL_POST_PROCESSING",
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW to "RAW",
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING to "PRIVATE_REPROCESSING",
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS to "READ_SENSOR_SETTINGS",
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE to "BURST_CAPTURE",
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING to "YUV_REPROCESSING",
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT to "DEPTH_OUTPUT",
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO to "CONSTRAINED_HIGH_SPEED_VIDEO",
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING to "MOTION_TRACKING",
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA to "LOGICAL_MULTI_CAMERA",
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME to "MONOCHROME",
        13 to "SECURE_IMAGE_DATA",
        14 to "SYSTEM_CAMERA",
        15 to "OFFLINE_PROCESSING",
        16 to "ULTRA_HIGH_RESOLUTION_SENSOR",
        17 to "REMOSAIC_REPROCESSING",
        18 to "DYNAMIC_RANGE_TEN_BIT",
        19 to "STREAM_USE_CASE",
        20 to "COLOR_SPACE_PROFILES",
    )

    private val HARDWARE_LEVEL = mapOf(
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED to "LIMITED",
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL to "FULL",
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY to "LEGACY",
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 to "LEVEL_3",
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL to "EXTERNAL",
    )

    private val LENS_FACING = mapOf(0 to "FRONT", 1 to "BACK", 2 to "EXTERNAL")
    private val AE_MODES = mapOf(0 to "OFF", 1 to "ON", 2 to "ON_AUTO_FLASH", 3 to "ON_ALWAYS_FLASH", 4 to "ON_AUTO_FLASH_REDEYE", 5 to "ON_EXTERNAL_FLASH", 6 to "ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY")
    private val AF_MODES = mapOf(0 to "OFF", 1 to "AUTO", 2 to "MACRO", 3 to "CONTINUOUS_VIDEO", 4 to "CONTINUOUS_PICTURE", 5 to "EDOF")
    private val AWB_MODES = mapOf(0 to "OFF", 1 to "AUTO", 2 to "INCANDESCENT", 3 to "FLUORESCENT", 4 to "WARM_FLUORESCENT", 5 to "DAYLIGHT", 6 to "CLOUDY_DAYLIGHT", 7 to "TWILIGHT", 8 to "SHADE")
    private val ANTIBANDING = mapOf(0 to "OFF", 1 to "50HZ", 2 to "60HZ", 3 to "AUTO")
    private val VIDEO_STABILIZATION = mapOf(0 to "OFF", 1 to "ON", 2 to "PREVIEW_STABILIZATION")
    private val OPTICAL_STABILIZATION = mapOf(0 to "OFF", 1 to "ON")
    private val NOISE_REDUCTION = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "MINIMAL", 4 to "ZERO_SHUTTER_LAG")
    private val EDGE = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "ZERO_SHUTTER_LAG")
    private val TONEMAP = mapOf(0 to "CONTRAST_CURVE", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "GAMMA_VALUE", 4 to "PRESET_CURVE")
    private val FACE_DETECT = mapOf(0 to "OFF", 1 to "SIMPLE", 2 to "FULL")
    private val CONTROL_MODES = mapOf(0 to "OFF", 1 to "AUTO", 2 to "USE_SCENE_MODE", 3 to "OFF_KEEP_STATE", 4 to "USE_EXTENDED_SCENE_MODE")
    private val SCENE_MODES = mapOf(
        0 to "DISABLED", 1 to "FACE_PRIORITY", 2 to "ACTION", 3 to "PORTRAIT", 4 to "LANDSCAPE", 5 to "NIGHT", 6 to "NIGHT_PORTRAIT",
        7 to "THEATRE", 8 to "BEACH", 9 to "SNOW", 10 to "SUNSET", 11 to "STEADYPHOTO", 12 to "FIREWORKS", 13 to "SPORTS",
        14 to "PARTY", 15 to "CANDLELIGHT", 16 to "BARCODE", 17 to "HIGH_SPEED_VIDEO", 18 to "HDR",
    )
    private val EFFECTS = mapOf(0 to "OFF", 1 to "MONO", 2 to "NEGATIVE", 3 to "SOLARIZE", 4 to "SEPIA", 5 to "POSTERIZE", 6 to "WHITEBOARD", 7 to "BLACKBOARD", 8 to "AQUA")
    private val COLOR_CORRECTION = mapOf(0 to "TRANSFORM_MATRIX", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "CCT")
    private val AE_PRIORITY = mapOf(0 to "OFF", 1 to "SENSOR_SENSITIVITY_PRIORITY", 2 to "SENSOR_EXPOSURE_TIME_PRIORITY")
    private val TIMESTAMP_SOURCE = mapOf(0 to "UNKNOWN", 1 to "REALTIME")
    private val FOCUS_CALIBRATION = mapOf(0 to "UNCALIBRATED", 1 to "APPROXIMATE", 2 to "CALIBRATED")
    private val CFA = mapOf(0 to "RGGB", 1 to "GRBG", 2 to "GBRG", 3 to "BGGR", 4 to "RGB", 5 to "MONO", 6 to "NIR")
    private val SYNC_TYPE = mapOf(0 to "APPROXIMATE", 1 to "CALIBRATED")
    private val SYNC_MAX_LATENCY = mapOf(0 to "PER_FRAME_CONTROL", -1 to "UNKNOWN")
    private val CROPPING_TYPE = mapOf(0 to "CENTER_ONLY", 1 to "FREEFORM")
    private val LENS_POSE_REFERENCE = mapOf(0 to "PRIMARY_CAMERA", 1 to "GYROSCOPE", 2 to "UNDEFINED", 3 to "AUTOMOTIVE")
    private val TEST_PATTERNS = mapOf(0 to "OFF", 1 to "SOLID_COLOR", 2 to "COLOR_BARS", 3 to "COLOR_BARS_FADE_TO_GRAY", 4 to "PN9", 5 to "BLACK", 256 to "CUSTOM1")
    private val LENS_SHADING = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY")
    private val HOT_PIXEL = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY")
    private val ABERRATION = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY")
    private val STREAM_USE_CASES = mapOf(0 to "DEFAULT", 1 to "PREVIEW", 2 to "STILL_CAPTURE", 3 to "VIDEO_RECORD", 4 to "PREVIEW_VIDEO_STILL", 5 to "VIDEO_CALL", 6 to "CROPPED_RAW")
    private val ROTATE_AND_CROP = mapOf(0 to "NONE", 1 to "90", 2 to "180", 3 to "270", 4 to "AUTO")
    private val SETTINGS_OVERRIDE = mapOf(0 to "OFF", 1 to "ZOOM")
    private val DYNAMIC_RANGE = mapOf(
        1 to "STANDARD", 2 to "HLG10", 4 to "HDR10", 8 to "HDR10_PLUS", 16 to "DOLBY_VISION_10B_HDR_REF",
        32 to "DOLBY_VISION_10B_HDR_REF_PO", 64 to "DOLBY_VISION_10B_HDR_OEM", 128 to "DOLBY_VISION_10B_HDR_OEM_PO",
        256 to "DOLBY_VISION_8B_HDR_REF", 512 to "DOLBY_VISION_8B_HDR_REF_PO", 1024 to "DOLBY_VISION_8B_HDR_OEM",
        2048 to "DOLBY_VISION_8B_HDR_OEM_PO",
    )

    internal val IMAGE_FORMATS = mapOf(
        ImageFormat.JPEG to "JPEG",
        ImageFormat.YUV_420_888 to "YUV_420_888",
        ImageFormat.RAW_SENSOR to "RAW_SENSOR",
        ImageFormat.RAW10 to "RAW10",
        ImageFormat.RAW12 to "RAW12",
        ImageFormat.RAW_PRIVATE to "RAW_PRIVATE",
        ImageFormat.PRIVATE to "PRIVATE",
        ImageFormat.HEIC to "HEIC",
        ImageFormat.DEPTH16 to "DEPTH16",
        ImageFormat.DEPTH_POINT_CLOUD to "DEPTH_POINT_CLOUD",
        ImageFormat.DEPTH_JPEG to "DEPTH_JPEG",
        ImageFormat.Y8 to "Y8",
        ImageFormat.NV21 to "NV21",
        ImageFormat.YUY2 to "YUY2",
        ImageFormat.YV12 to "YV12",
        ImageFormat.YCBCR_P010 to "YCBCR_P010",
        ImageFormat.JPEG_R to "JPEG_R",
    )

    private val ENUMS: Map<String, Map<Int, String>> = mapOf(
        "android.request.availableCapabilities" to CAPABILITIES,
        "android.info.supportedHardwareLevel" to HARDWARE_LEVEL,
        "android.lens.facing" to LENS_FACING,
        "android.control.aeAvailableModes" to AE_MODES,
        "android.control.afAvailableModes" to AF_MODES,
        "android.control.awbAvailableModes" to AWB_MODES,
        "android.control.aeAvailableAntibandingModes" to ANTIBANDING,
        "android.control.availableVideoStabilizationModes" to VIDEO_STABILIZATION,
        "android.control.availableModes" to CONTROL_MODES,
        "android.control.availableSceneModes" to SCENE_MODES,
        "android.control.availableEffects" to EFFECTS,
        "android.control.aeAvailablePriorityModes" to AE_PRIORITY,
        "android.colorCorrection.availableModes" to COLOR_CORRECTION,
        "android.colorCorrection.availableAberrationModes" to ABERRATION,
        "android.lens.info.availableOpticalStabilization" to OPTICAL_STABILIZATION,
        "android.lens.info.focusDistanceCalibration" to FOCUS_CALIBRATION,
        "android.lens.poseReference" to LENS_POSE_REFERENCE,
        "android.noiseReduction.availableNoiseReductionModes" to NOISE_REDUCTION,
        "android.edge.availableEdgeModes" to EDGE,
        "android.tonemap.availableToneMapModes" to TONEMAP,
        "android.statistics.info.availableFaceDetectModes" to FACE_DETECT,
        "android.statistics.info.availableHotPixelMapModes" to HOT_PIXEL,
        "android.shading.availableModes" to LENS_SHADING,
        "android.hotPixel.availableHotPixelModes" to HOT_PIXEL,
        "android.sensor.info.timestampSource" to TIMESTAMP_SOURCE,
        "android.sensor.info.colorFilterArrangement" to CFA,
        "android.sensor.availableTestPatternModes" to TEST_PATTERNS,
        "android.sync.maxLatency" to SYNC_MAX_LATENCY,
        "android.logicalMultiCamera.sensorSyncType" to SYNC_TYPE,
        "android.scaler.croppingType" to CROPPING_TYPE,
        "android.scaler.availableStreamUseCases" to STREAM_USE_CASES,
        "android.scaler.availableRotateAndCropModes" to ROTATE_AND_CROP,
        "android.control.availableSettingsOverrides" to SETTINGS_OVERRIDE,
        "android.request.recommendedTenBitDynamicRangeProfile" to DYNAMIC_RANGE,
    )
}
