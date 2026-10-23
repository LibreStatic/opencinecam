/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

/**
 * A physical lens of the selected logical camera that the lens lock can pin (PLAN-070, ADR-0036).
 * [anchorRatio] is where the lens sits on the logical zoom scale, from the field of view
 * (`RawZoomCrop.fovWidth`), not the focal ratio: on the Razr Fold 0.55, 1 and 3.0.
 */
data class LensLockOption(
    val physicalId: String,
    val anchorRatio: Float,
    val focalLengthMm: Float,
)

/** How a locked lens is served. */
enum class LensLockRoute {
    /** The physical camera is opened directly; the HAL cannot switch lens. */
    DIRECT_PHYSICAL,
    /** The physical could not be opened: the logical camera with zoom pinned at the anchor and a GPU crop. */
    GPU_CROP,
}

data class LensLockUiState(
    /** The lenses the selected camera can lock to, widest first; fewer than two means no lens lock. */
    val options: List<LensLockOption> = emptyList(),
    /** The physical id the selected camera is locked to; null when unlocked. */
    val lockedPhysicalId: String? = null,
    /** How the lock is served once the session is up; null while unlocked or opening. */
    val route: LensLockRoute? = null,
    /** On the GPU-crop route the HAL switched lens anyway (e.g. its low-light fallback). */
    val lensSwitchedWarning: Boolean = false,
) {
    val available: Boolean get() = options.size >= 2
    val locked: Boolean get() = lockedPhysicalId != null
    val lockedOption: LensLockOption? get() = options.firstOrNull { it.physicalId == lockedPhysicalId }
}
