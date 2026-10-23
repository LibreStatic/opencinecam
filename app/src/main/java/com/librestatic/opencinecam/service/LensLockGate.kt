/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.LensLockOption
import com.librestatic.opencinecam.LensLockRoute
import com.librestatic.opencinecam.camera.LensLockBinding
import com.librestatic.opencinecam.usesLogGraph

/**
 * Whether a start of [logicalId] opens a locked lens (PLAN-070, ADR-0036). The lens lock needs at
 * least two lock options and a saved [requestedPhysicalId] among them. A calibration run measures
 * the logical camera, and constrained high speed and the night extension stay on the logical camera,
 * so all three open it unlocked without the engine having to refuse (and announce) the lock.
 */
internal fun lensLockBindingFor(
    logicalId: String?,
    options: List<LensLockOption>,
    requestedPhysicalId: String?,
    calibrating: Boolean,
    highSpeed: Boolean,
    night: Boolean = false,
): LensLockBinding? {
    if (logicalId == null || requestedPhysicalId == null || calibrating || highSpeed || night || options.size < 2) return null
    val option = options.firstOrNull { it.physicalId == requestedPhysicalId } ?: return null
    return LensLockBinding(logicalId, option.physicalId, option.anchorRatio)
}

/** Slow motion, or a constrained high-speed profile in the mode that uses it. */
internal fun lensLockHighSpeed(mode: CaptureMode, videoProfileHighSpeed: Boolean, logProfileHighSpeed: Boolean): Boolean =
    mode == CaptureMode.SLOW_MOTION ||
        (mode in CameraUiState.videoProfileModes && videoProfileHighSpeed) ||
        (mode.usesLogGraph && logProfileHighSpeed)

/**
 * The lens lock's GPU-crop route zooms only on the GPU viewfinder, so a photo preview mode needs it
 * forced on (PLAN-070 slice 4). Video modes already preview through the GPU.
 */
internal fun lensLockCropNeedsGpuViewfinder(photoPreviewMode: Boolean, route: LensLockRoute?): Boolean =
    photoPreviewMode && route == LensLockRoute.GPU_CROP

/**
 * A served lens lock never rebuilds the graph when zoom crosses the physical-still window: the
 * direct route has no physical still routing and the GPU-crop route keeps its HAL zoom pinned.
 */
internal fun lensLockSkipsStillZoomRebuild(route: LensLockRoute?): Boolean = route != null

/** The switched-lens warning shows only while the GPU-crop route is served; any other route drops it. */
internal fun lensLockSwitchedWarning(switched: Boolean, route: LensLockRoute?): Boolean =
    switched && route == LensLockRoute.GPU_CROP
