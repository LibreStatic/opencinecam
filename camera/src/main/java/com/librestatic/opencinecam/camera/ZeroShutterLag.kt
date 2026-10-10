/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** What the still pipeline knows about one capture when it decides on CONTROL_ENABLE_ZSL. */
internal data class ZeroShutterLagContext(
    val requested: Boolean,
    /** CONTROL_ENABLE_ZSL is in the camera's availableCaptureRequestKeys. */
    val supported: Boolean,
    /** A single PHOTO-mode still; bursts, brackets and accumulation groups are excluded. */
    val singleStill: Boolean,
    /** Any RAW/DNG output is part of the capture (DNG or RAW+JPEG). */
    val rawOutput: Boolean,
    /** Submitted AE is not plain auto (manual, ISO or shutter priority, or legacy ISO+shutter). */
    val manualExposure: Boolean,
    /** The flash plan fires a pulse or holds the torch on. */
    val flashActive: Boolean,
    val needsPrecapture: Boolean,
)

/** ZSL replays an already exposed buffer, so anything that changes the exposure or lights the scene must bypass it. */
internal fun zeroShutterLagEligible(context: ZeroShutterLagContext): Boolean = with(context) {
    requested && supported && singleStill && !rawOutput && !manualExposure && !flashActive && !needsPrecapture
}

/** True when the plan's flash mode is anything but OFF (SINGLE pulse or TORCH). */
internal fun photoFlashPlanActive(plan: PhotoFlashResolution.Plan): Boolean = plan.flashMode != 0

/** Shutter press (engine admission) to the HAL's onCaptureStarted, for comparing ZSL on and off. */
data class StillShutterLatency(
    val zslApplied: Boolean,
    val latencyMs: Long,
)

internal fun measureStillShutterLatency(zslApplied: Boolean, pressedAtNs: Long, startedAtNs: Long): StillShutterLatency? =
    (startedAtNs - pressedAtNs).takeIf { it >= 0 }?.let { StillShutterLatency(zslApplied, it / 1_000_000L) }
