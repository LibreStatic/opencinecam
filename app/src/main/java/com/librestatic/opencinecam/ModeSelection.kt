/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.runtime.compositionLocalOf

/**
 * Slow motion is the video pipeline recording off-speed: a high-speed capture rate conformed to the
 * project rate, without audio. The dial shows it as its own mode, but it drives the same tested
 * VIDEO path the off-speed setting already uses, so there is one recorder, not two.
 */
internal fun displayedCaptureMode(selected: CaptureMode, videoOffSpeed: Boolean): CaptureMode =
    if (selected == CaptureMode.VIDEO && videoOffSpeed) CaptureMode.SLOW_MOTION else selected

/**
 * Modes worth putting on the dial: those the camera can run now or may after probing. Modes this
 * device cannot run, or that the app has not integrated, belong in diagnostics, not in the path of
 * an operator looking for a mode. The displayed mode always stays, so the dial never loses its place.
 */
internal fun visibleCaptureModes(gates: Map<CaptureMode, ModeGateState>, displayed: CaptureMode): List<CaptureMode> =
    CaptureMode.entries.filter { mode ->
        mode == displayed || gates[mode] !in setOf(ModeGateState.UNSUPPORTED, ModeGateState.FAILED)
    }

/** Rates above this need a high-speed session on every Camera2 device probed so far. */
internal const val SLOW_MOTION_MIN_FPS = 100

internal fun supportsSlowMotion(profiles: List<VideoProfileSpec>): Boolean = profiles.any { it.fps >= SLOW_MOTION_MIN_FPS }

/**
 * The high-speed profile slow motion opens with: the fastest rate at the current resolution, or
 * the fastest the camera offers at all (largest frame first) when that resolution has none.
 * Choosing size and rate together lets the camera open once, straight into the high-speed session.
 */
internal fun slowMotionProfile(profiles: List<VideoProfileSpec>, width: Int, height: Int): VideoProfileSpec? {
    val fast = profiles.filter { it.fps >= SLOW_MOTION_MIN_FPS }
    val candidates = fast.filter { it.width == width && it.height == height }.takeIf { it.isNotEmpty() } ?: fast
    return candidates.maxWithOrNull(compareBy<VideoProfileSpec> { it.fps }.thenBy { it.width.toLong() * it.height })
}

/** The mode shown as selected and the action that selects one, provided by the capture chrome. */
internal class ModeSelection(val displayed: CaptureMode, val select: (CaptureMode) -> Unit)

internal val LocalModeSelection = compositionLocalOf<ModeSelection?> { null }
