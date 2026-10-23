/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.LensLockOption
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
