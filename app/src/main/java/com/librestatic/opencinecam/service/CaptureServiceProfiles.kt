/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import android.util.Size
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.logProfilesFor
import com.librestatic.opencinecam.usesLogGraph
import com.librestatic.opencinecam.capture.toSpec
import com.librestatic.opencinecam.capture.VideoGeometryPolicy
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.RecordingGeometryCalculator
import com.librestatic.opencinecam.camera.RecordingFrameSize

// Part of CaptureService: normalising the requested frame rate and video/LOG profile to what the camera and encoder can do.
// These are extensions so the service's state stays in one object; they keep the threading rules of its members.

/**
 * True when at least one hardware encoder can raster the given encoded frame size at 30 fps.
 * Used by [RecordingGeometryCalculator.calculate] to decide whether a DESQUEEZED
 * anamorphic output fits the SoC codec limits or must degrade to SQUEEZED + SAR.
 * Uses the same predicate as profile enumeration; AVC because the VIDEO branch
 * goes through the passthrough-SDR GPU pipeline, which encodes H.264.
 */
internal fun CaptureService.encoderSupportsRaster(size: RecordingFrameSize): Boolean =
    previewEngine.encoderCanReachFps(Size(size.width, size.height))

/**
 * Same as [encoderSupportsRaster] but validates against HEVC surface-encoder caps,
 * because the LOG pipeline ([OpenCineLogGpuPipeline] with passthroughSdr=false)
 * encodes Main10/HEVC, not AVC.
 */
internal fun CaptureService.hevcEncoderSupportsRaster(size: RecordingFrameSize): Boolean =
    previewEngine.hevcEncoderCanReachFps(Size(size.width, size.height))

internal fun CaptureService.normalizedTargetFps(
    descriptor: Camera2CameraDescriptor,
    mode: CaptureMode,
    requested: Int,
    videoWidth: Int = 1920,
    videoHeight: Int = 1080,
): Int {
    val supported = when {
        mode.usesLogGraph -> VideoGeometryPolicy.supportedLogFps(descriptor.logProfilesFor(mode).map { it.toSpec() }, videoWidth, videoHeight)
        mode in CameraUiState.videoProfileModes -> VideoGeometryPolicy.supportedFps(descriptor.videoProfiles.map { it.toSpec() }, videoWidth, videoHeight)
        else -> descriptor.availableFixedFps
    }
    return when {
        requested in supported -> requested
        Camera2PreviewEngine.DEFAULT_TARGET_FPS in supported -> Camera2PreviewEngine.DEFAULT_TARGET_FPS
        supported.isNotEmpty() -> supported.minBy { kotlin.math.abs(it - requested) }
        else -> Camera2PreviewEngine.DEFAULT_TARGET_FPS
    }
}

internal fun CaptureService.normalizedVideoProfile(
    descriptor: Camera2CameraDescriptor,
    width: Int,
    height: Int,
    fps: Int,
) = descriptor.videoProfiles.firstOrNull {
    it.size.width == width && it.size.height == height && it.fps == fps
} ?: descriptor.videoProfiles.firstOrNull {
    it.size.width == width && it.size.height == height && it.fps == Camera2PreviewEngine.DEFAULT_TARGET_FPS
} ?: descriptor.videoProfiles.firstOrNull {
    it.size.width == 1920 && it.size.height == 1080 && it.fps == Camera2PreviewEngine.DEFAULT_TARGET_FPS
} ?: descriptor.videoProfiles.minWithOrNull(
    compareBy<com.librestatic.opencinecam.camera.Camera2VideoProfile> {
        kotlin.math.abs(it.size.width.toLong() * it.size.height - width.toLong() * height)
    }.thenBy { kotlin.math.abs(it.fps - fps) },
)

internal fun CaptureService.normalizedLogProfile(
    descriptor: Camera2CameraDescriptor,
    mode: CaptureMode,
    width: Int,
    height: Int,
    fps: Int,
): com.librestatic.opencinecam.camera.Camera2LogProfile? = descriptor.logProfilesFor(mode).normalizedLogProfile(width, height, fps)
