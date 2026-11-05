/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.graphics.ImageFormat
import android.graphics.ColorSpace
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.MAX_SELECTABLE_FPS
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.MIME_AVC
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.MIME_HEVC
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.MIN_SELECTABLE_FPS
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.advertisedStreamUseCases

// Part of Camera2PreviewEngine: camera descriptors: what each camera offers and which sizes and rates reach the encoder.
// These are extensions so the engine's state stays in one object; they run on the engine's executors like its members.

/** Selects the physical member behind a logical camera that truly owns the HFR sensor mode. */
internal fun Camera2PreviewEngine.preferredHighSpeedPhysicalId(
    descriptor: Camera2CameraDescriptor,
    profile: Camera2VideoProfile,
): String? {
    val logical = runCatching { manager.getCameraCharacteristics(descriptor.cameraId) }.getOrNull()
        ?: return null
    val candidates = logical.physicalCameraIds.mapNotNull { id ->
        val physical = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            ?: return@mapNotNull null
        val map = physical.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return@mapNotNull null
        val supportsProfile = runCatching {
            map.highSpeedVideoSizes.any { it == profile.size } &&
                map.getHighSpeedVideoFpsRangesFor(profile.size).any {
                    it.lower == profile.fps && it.upper == profile.fps
                }
        }.getOrDefault(false)
        if (!supportsProfile) return@mapNotNull null
        PhysicalHighSpeedCandidate(
            id,
            physical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
        )
    }
    return MotorolaHighSpeed.closestFocalPhysicalId(candidates, descriptor.focalLengthsMm.firstOrNull())
}

internal fun Camera2PreviewEngine.descriptor(cameraId: String, targetWidth: Int, targetHeight: Int): Camera2CameraDescriptor? =
    runCatching {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val previews = runCatching { map.getOutputSizes(SurfaceHolder::class.java)?.toList() }.getOrNull()
            .orEmpty()
            .ifEmpty { map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty() }
        if (previews.isEmpty()) return null
        val previewSize = choosePreviewSize(previews, targetWidth, targetHeight)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val targetFpsRanges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.distinctBy { it.lower to it.upper }
            ?.sortedWith(compareBy<Range<Int>> { it.lower }.thenBy { it.upper })
            .orEmpty()
        val fixedFps = targetFpsRanges
            .filter { it.lower == it.upper && it.lower in MIN_SELECTABLE_FPS..MAX_SELECTABLE_FPS }
            .map { it.lower }
            .distinct()
            .sorted()
            .filter { streamCanReachFps(map, previewSize, it) }
        val videoSizes = runCatching { map.getOutputSizes(MediaRecorder::class.java)?.toList() }
            .getOrNull().orEmpty().ifEmpty { previews }
            .distinctBy { it.width to it.height }
            .filter { candidate -> previews.any { it.width == candidate.width && it.height == candidate.height } }
        val regularProfiles = videoSizes.flatMap { size ->
            fixedFps.mapNotNull { fps ->
                Camera2VideoProfile(size, fps, false).takeIf {
                    streamCanReachFps(map, size, fps) && encoderCanReachFps(size, fps)
                }
            }
        }
        val highSpeedProfiles = if (
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in capabilities
        ) {
            runCatching { map.highSpeedVideoSizes.toList() }.getOrDefault(emptyList()).flatMap { size ->
                runCatching { map.getHighSpeedVideoFpsRangesFor(size).toList() }.getOrDefault(emptyList())
                    .filter { it.lower == it.upper && it.upper > MAX_SELECTABLE_FPS }
                    .mapNotNull { range ->
                        Camera2VideoProfile(size, range.upper, true)
                            .takeIf { encoderCanReachFps(size, range.upper) }
                    }
            }
        } else emptyList()
        val videoProfiles = (regularProfiles + highSpeedProfiles)
            .distinctBy { Triple(it.size.width, it.size.height, it.fps) }
            .sortedWith(
                compareByDescending<Camera2VideoProfile> { it.size.width.toLong() * it.size.height }
                    .thenBy { it.size.width }
                    .thenBy { it.fps },
            )
        val hlgProfiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        } else null
        val hasHlg10 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            hlgProfiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
        val hasBt2020Hlg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasHlg10) {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
                ?.getSupportedColorSpacesForDynamicRange(ImageFormat.PRIVATE, DynamicRangeProfiles.HLG10)
                ?.contains(ColorSpace.Named.BT2020_HLG) == true
        } else false
        val hasHdr10 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            hlgProfiles?.supportedProfiles?.contains(DynamicRangeProfiles.HDR10) == true
        val regularLogProfiles = if (hasHlg10 && hasBt2020Hlg) {
            videoSizes.flatMap { size ->
                fixedFps.mapNotNull { fps ->
                    Camera2LogProfile(
                        size = size,
                        fps = fps,
                        sourcePath = OpenCineLogSourcePath.HLG10_BT2020,
                        constrainedHighSpeed = false,
                        hdr10 = Hdr10Video.isOffered(
                            Build.VERSION.SDK_INT, hasHdr10, hasHdr10 && OpenCineLogGpuPipeline.findHdr10Encoder(size, fps) != null,
                        ),
                    ).takeIf {
                        streamCanReachFps(map, size, fps) &&
                            OpenCineLogGpuPipeline.findEncoder(size, fps) != null
                    }
                }
            }
        } else emptyList()
        val highSpeedLogProfiles = highSpeedProfiles.mapNotNull { profile ->
            Camera2LogProfile(
                size = profile.size,
                fps = profile.fps,
                sourcePath = OpenCineLogSourcePath.SDR_BT709_ISP,
                constrainedHighSpeed = true,
            ).takeIf { OpenCineLogGpuPipeline.findEncoder(profile.size, profile.fps) != null }
        }
        val logProfiles = (regularLogProfiles + highSpeedLogProfiles)
            .distinctBy { Triple(it.size.width, it.size.height, it.fps) }
            .sortedWith(
                compareByDescending<Camera2LogProfile> { it.size.width.toLong() * it.size.height }
                    .thenBy { it.size.width }
                    .thenBy { it.fps },
            )
        val zoomRatioRangeArr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else null
        val supportsZoomRatioApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            zoomRatioRangeArr != null &&
            zoomRatioRangeArr.lower < zoomRatioRangeArr.upper
        val zoomRange: ClosedFloatingPointRange<Float>? = if (supportsZoomRatioApi) {
            zoomRatioRangeArr!!.lower..zoomRatioRangeArr.upper
        } else {
            // API 29 fallback: digital zoom only (>= 1x) via SCALER_CROP_REGION.
            val maxDigitalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?.takeIf { it > 1f }
            if (maxDigitalZoom != null) 1f..maxDigitalZoom else null
        }
        val logicalFocal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
        val physicalFocals = runCatching { characteristics.physicalCameraIds }.getOrDefault(emptySet())
            .filter { it != cameraId }
            .mapNotNull { id ->
                runCatching {
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.firstOrNull()
                }.getOrNull()?.let { focal -> id to focal }
            }
        // The lens lock's anchors (field of view), so the bar matches where the HAL switches lens.
        val fovRatios = lensLockCandidates(cameraId).associate { it.physicalId to it.anchorRatio }
        val anchors = ZoomMath.deriveAnchors(
            logicalFocal, physicalFocals,
            rangeMin = zoomRange?.start ?: Float.NEGATIVE_INFINITY,
            rangeMax = zoomRange?.endInclusive ?: Float.POSITIVE_INFINITY,
            fovRatios = fovRatios,
        )
        // HFR zoom is only safe when the camera uses the CONTROL_ZOOM_RATIO API (API 30+)
        // and the key is present in availableCaptureRequestKeys. Constrained sessions reject
        // unknown keys, so we gate this conservatively.
        val hfrZoomKeys = if (supportsZoomRatioApi) {
            runCatching { characteristics.availableCaptureRequestKeys }.getOrDefault(emptyList())
        } else emptyList()
        val supportsHfrZoom = supportsZoomRatioApi &&
            hfrZoomKeys.any { it.name == "zoomRatio" }
        // Direct Kelvin (COLOR_CORRECTION_MODE_CCT) requires API 36+, the CCT mode advertised
        // in available color-correction modes, the temperature key present in the request
        // keys, and a non-empty COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE with lower < upper.
        val kelvinRange: IntRange? = if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                val cctSupported = characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)
                    ?.contains(CaptureRequest.COLOR_CORRECTION_MODE_CCT) == true
                val tempKeyPresent = characteristics.availableCaptureRequestKeys
                    .any { it == CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE }
                val range = characteristics.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE)
                val valid = range != null && range.lower < range.upper
                if (cctSupported && tempKeyPresent && valid) range!!.lower..range.upper else null
            }.getOrNull()
        } else null
        Camera2CameraDescriptor(
            cameraId = cameraId,
            timestampSourceRealtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_EXTERNAL,
            focalLengthsMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
            sensorSizeMm = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { it.width to it.height },
            previewSize = previewSize,
            jpegSize = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height },
            heicSize = if (ImageFormat.HEIC in map.outputFormats)
                map.getOutputSizes(ImageFormat.HEIC)?.maxByOrNull { it.width.toLong() * it.height } else null,
            jpegSizes = map.getOutputSizes(ImageFormat.JPEG)?.sortedByDescending { it.width.toLong() * it.height }.orEmpty(),
            heicSizes = if (ImageFormat.HEIC in map.outputFormats)
                map.getOutputSizes(ImageFormat.HEIC)?.sortedByDescending { it.width.toLong() * it.height }.orEmpty() else emptyList(),
            physicalJpegSizes = physicalExtraStillSizes(unityZoomPhysicalIds(logicalFocal, physicalFocals), ImageFormat.JPEG, map),
            physicalHeicSizes = if (ImageFormat.HEIC in map.outputFormats)
                physicalExtraStillSizes(unityZoomPhysicalIds(logicalFocal, physicalFocals), ImageFormat.HEIC, map) else emptyMap(),
            rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height },
            analysisSize = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty().let { sizes ->
                sizes.getOrNull(analysisSizeIndex(sizes.map { it.width to it.height }, previewSize.width, previewSize.height))
            },
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRangeNs = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            aeCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            aeCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
            aeCompensationStepNumerator = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.numerator ?: 0,
            aeCompensationStepDenominator = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.denominator ?: 1,
            minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            supportsRaw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities,
            streamUseCases = advertisedStreamUseCases(characteristics, capabilities),
            flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            targetFpsRanges = targetFpsRanges,
            availableFixedFps = fixedFps,
            videoProfiles = videoProfiles,
            logProfiles = logProfiles,
            sensorActiveArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            availableAfModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty(),
            maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
            aeLockSupported = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
            lowLightBoostSupported = lowLightBoostSupported(Build.VERSION.SDK_INT,
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toSet().orEmpty()),
            afLockSupported = runCatching {
                val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
                CaptureRequest.CONTROL_AF_MODE_OFF in afModes && CaptureRequest.CONTROL_AF_MODE_AUTO in afModes
            }.getOrDefault(false),
            zoomRatioRange = zoomRange,
            supportsZoomRatioApi = supportsZoomRatioApi,
            supportsZoomSettingsOverride = Build.VERSION.SDK_INT >= 34 && supportsZoomRatioApi && runCatching {
                CaptureRequest.CONTROL_SETTINGS_OVERRIDE in characteristics.availableCaptureRequestKeys &&
                    characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SETTINGS_OVERRIDES)
                        ?.contains(CaptureRequest.CONTROL_SETTINGS_OVERRIDE_ZOOM) == true
            }.getOrDefault(false),
            digitalZoomMaxRatio = if (!supportsZoomRatioApi) zoomRange?.endInclusive else null,
            opticalAnchors = anchors,
            supportsHfrZoom = supportsHfrZoom,
            kelvinRange = kelvinRange,
            tintSupported = Build.VERSION.SDK_INT >= 36 && kelvinRange != null && characteristics.availableCaptureRequestKeys.contains(CaptureRequest.COLOR_CORRECTION_COLOR_TINT),
            awbLockSupported = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true &&
                CaptureRequest.CONTROL_AWB_LOCK in characteristics.availableCaptureRequestKeys,
            availableAwbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty(),
            zslSupported = runCatching { CaptureRequest.CONTROL_ENABLE_ZSL in characteristics.availableCaptureRequestKeys }.getOrDefault(false),
            faceDetectModes = if (CaptureRequest.STATISTICS_FACE_DETECT_MODE in characteristics.availableCaptureRequestKeys)
                characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.toSet().orEmpty() else emptySet(),
            nightExtension = nightExtensionFor(cameraId),
            lowLightAutoFps = dynamicFpsKeys(characteristics, cameraId).offered,
            imageProcessingCapabilities = ImageProcessingCapabilities(
                opticalModes = if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE in characteristics.availableCaptureRequestKeys)
                    characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toSet().orEmpty() else emptySet(),
                videoModes = if (CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE in characteristics.availableCaptureRequestKeys)
                    characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet().orEmpty()
                        .let { modes -> if (Build.VERSION.SDK_INT >= 33) modes else modes - PREVIEW_STABILIZATION } else emptySet(),
                noiseModes = if (CaptureRequest.NOISE_REDUCTION_MODE in characteristics.availableCaptureRequestKeys)
                    characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet().orEmpty() else emptySet(),
                edgeModes = if (CaptureRequest.EDGE_MODE in characteristics.availableCaptureRequestKeys)
                    characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toSet().orEmpty() else emptySet(),
                sessionControls = characteristics.availableSessionKeys.orEmpty().mapNotNull { key -> when (key) {
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE -> ImageProcessingControl.STABILIZATION
                    CaptureRequest.NOISE_REDUCTION_MODE -> ImageProcessingControl.NOISE_REDUCTION
                    CaptureRequest.EDGE_MODE -> ImageProcessingControl.EDGE
                    else -> null
                } }.toSet(),
                toneMapModes = if (CaptureRequest.TONEMAP_MODE in characteristics.availableCaptureRequestKeys &&
                    CaptureRequest.TONEMAP_CURVE in characteristics.availableCaptureRequestKeys)
                    characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty() else emptySet(),
                maxCurvePoints = characteristics.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0,
                pictureControls = characteristics.pictureControlCapabilities(),
            ),
            exposureCapabilities = ExposureCapabilities(
                manual = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true,
                isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper },
                timeRangeNs = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper },
                priorities = if (Build.VERSION.SDK_INT >= 36 && characteristics.availableCaptureRequestKeys.contains(CaptureRequest.CONTROL_AE_PRIORITY_MODE)) {
                    characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)?.toList()?.mapNotNull { when (it) {
                        CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY -> ExposureMode.ISO_PRIORITY.takeIf { characteristics.availableCaptureRequestKeys.contains(CaptureRequest.SENSOR_SENSITIVITY) }
                        CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY -> ExposureMode.SHUTTER_PRIORITY.takeIf { characteristics.availableCaptureRequestKeys.contains(CaptureRequest.SENSOR_EXPOSURE_TIME) }
                        else -> null
                    } }?.toSet().orEmpty()
                } else emptySet(),
                postRawBoostRange = characteristics.takeIf { CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST in it.availableCaptureRequestKeys }
                    ?.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)?.let { it.lower..it.upper },
                antibanding = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.toList()?.mapNotNull { when (it) {
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO -> Antibanding.AUTO
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF -> Antibanding.OFF
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ -> Antibanding.HZ50
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ -> Antibanding.HZ60
                    else -> null
                } }?.toSet().orEmpty(),
                meteringModes = vendorMeteringModes(characteristics),
                qcomExposureRangeNs = QcomExposurePriority.parseRange(VendorKeys.staticLongs(characteristics, QcomExposurePriority.RANGE_KEY)),
            ),
            photoFlashCapabilities = run {
                val available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val modes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toSet().orEmpty()
                val hasStrength = Build.VERSION.SDK_INT >= 35 &&
                    CaptureRequest.FLASH_STRENGTH_LEVEL in characteristics.availableCaptureRequestKeys
                val max = if (Build.VERSION.SDK_INT >= 35 && hasStrength) (characteristics.get(CameraCharacteristics.FLASH_SINGLE_STRENGTH_MAX_LEVEL) ?: 1).coerceAtLeast(1) else 1
                val default = if (Build.VERSION.SDK_INT >= 35 && hasStrength) (characteristics.get(CameraCharacteristics.FLASH_SINGLE_STRENGTH_DEFAULT_LEVEL) ?: 1).coerceIn(1, max) else 1
                PhotoFlashCapabilities(available, modes, max, default)
            },
            torchCapabilities = if (Build.VERSION.SDK_INT >= 35) {
                val available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val hasKey = characteristics.availableCaptureRequestKeys.contains(CaptureRequest.FLASH_STRENGTH_LEVEL)
                val max = if (hasKey) (characteristics.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1).coerceAtLeast(1) else 1
                val default = (characteristics.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_DEFAULT_LEVEL) ?: 1).coerceIn(1, max)
                TorchCapabilities(available, max, default)
            } else TorchCapabilities(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true),
        )
    }.getOrNull()

internal fun Camera2PreviewEngine.choosePreviewSize(sizes: List<Size>, targetWidth: Int, targetHeight: Int): Size =
    sizes[previewSizeIndex(sizes.map { it.width to it.height })]

internal fun Camera2PreviewEngine.streamCanReachFps(
    map: android.hardware.camera2.params.StreamConfigurationMap,
    size: Size,
    fps: Int,
): Boolean {
    val minimumDuration = runCatching {
        map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
    }.getOrDefault(0L)
    return minimumDuration <= 0L || minimumDuration <= 1_000_000_000L / fps.coerceAtLeast(1)
}

internal fun Camera2PreviewEngine.encoderCanReachFps(size: Size, fps: Int, mime: String = MIME_AVC): Boolean {
    val caps = when (mime) {
        MIME_HEVC -> hevcSurfaceEncoderCapabilities
        else -> avcSurfaceEncoderCapabilities
    }
    return caps.any {
        it.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
    }
}

internal fun Camera2PreviewEngine.lensOrder(lensFacing: Int): Int = when (lensFacing) {
    CameraCharacteristics.LENS_FACING_BACK -> 0
    CameraCharacteristics.LENS_FACING_FRONT -> 1
    else -> 2
}
