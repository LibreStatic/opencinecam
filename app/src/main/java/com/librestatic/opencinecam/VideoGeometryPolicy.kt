/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.Camera2LogProfile
import com.librestatic.opencinecam.camera.Camera2VideoProfile
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath

/**
 * Pure geometry negotiation shared by the resolution and FPS dials. The UI shows one
 * resolution map plus a mode-wide FPS list; when a requested rate does not exist for the
 * selected size the service snaps to the closest supported rate and surfaces a transient
 * notice instead of hiding the choice.
 *
 * Works on plain specifications instead of android.util.Size so the negotiation rules stay
 * unit-testable on the JVM.
 */
data class VideoProfileSpec(val width: Int, val height: Int, val fps: Int)

data class LogProfileSpec(val width: Int, val height: Int, val fps: Int, val sourcePath: OpenCineLogSourcePath)

fun Camera2VideoProfile.toSpec() = VideoProfileSpec(size.width, size.height, fps)

fun Camera2LogProfile.toSpec() = LogProfileSpec(size.width, size.height, fps, sourcePath)

object VideoGeometryPolicy {
    data class LogSnap(val profile: LogProfileSpec?, val snapped: Boolean)

    fun supportedFps(profiles: List<VideoProfileSpec>, width: Int, height: Int): List<Int> =
        fpsFor(profiles.filter { it.width == width && it.height == height }.map { it.fps })

    fun supportedLogFps(profiles: List<LogProfileSpec>, width: Int, height: Int): List<Int> =
        fpsFor(profiles.filter { it.width == width && it.height == height }.map { it.fps })

    fun unionFps(profiles: List<VideoProfileSpec>): List<Int> = fpsFor(profiles.map { it.fps })

    fun unionLogFps(profiles: List<LogProfileSpec>): List<Int> = fpsFor(profiles.map { it.fps })

    fun snapVideo(
        profiles: List<VideoProfileSpec>,
        width: Int,
        height: Int,
        requestedFps: Int,
        defaultFps: Int,
    ): VideoProfileSpec? {
        val choices = profiles.filter { it.width == width && it.height == height }
        if (choices.isEmpty()) return null
        return choices.firstOrNull { it.fps == requestedFps }
            ?: choices.firstOrNull { it.fps == defaultFps }
            ?: choices.minBy { kotlin.math.abs(it.fps - requestedFps) }
    }

    fun snapLog(
        profiles: List<LogProfileSpec>,
        width: Int,
        height: Int,
        requestedFps: Int,
        defaultFps: Int,
    ): LogSnap {
        val choices = profiles.filter { it.width == width && it.height == height }
        if (choices.isEmpty()) return LogSnap(null, snapped = false)
        val selected = choices.firstOrNull { it.fps == requestedFps }
            ?: choices.firstOrNull {
                it.fps == defaultFps && it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020
            }
            ?: choices.minWithOrNull(
                compareBy<LogProfileSpec> {
                    if (it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020) 0 else 1
                }.thenBy { kotlin.math.abs(it.fps - requestedFps) },
            )
            ?: return LogSnap(null, snapped = false)
        return LogSnap(selected, snapped = selected.fps != requestedFps)
    }

    private fun fpsFor(rates: List<Int>): List<Int> = rates.distinct().sorted()
}
