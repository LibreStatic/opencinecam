/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.view.Surface
import com.librestatic.opencinecam.camera.SubjectPreviewStatus
import kotlinx.coroutines.flow.StateFlow

/** Output-only authority. Subject content never receives record, camera selection or settings actions. */
interface SubjectPreviewPort {
    val statuses: StateFlow<SubjectPreviewStatus>
    /** [frameRate] is the subject display's target rate ([subjectPreviewFrameRate]); 0 is uncapped. */
    fun attach(surface: Surface, rotationDegrees: Int, frameRate: Float = 0f): AutoCloseable
}

/**
 * The subject display follows the operator display's refresh rate when it supports it, otherwise
 * its own highest rate. 0 (no rates known) leaves the subject output uncapped.
 */
fun subjectPreviewFrameRate(operatorHz: Float?, subjectRates: List<Float>): Float {
    val rates = subjectRates.filter { it.isFinite() && it > 0f }
    if (rates.isEmpty()) return 0f
    val operator = operatorHz?.takeIf { it.isFinite() && it > 0f }
    return operator?.let { target -> rates.firstOrNull { kotlin.math.abs(it - target) < 1f } } ?: rates.max()
}

/** Why the subject window cannot show the camera, or null when it can. */
enum class SubjectPreviewBlock { MODE }

/**
 * The subject preview rides the GPU viewfinder, which every selectable mode can drive: photo
 * modes keep their still readers, and a constrained high-speed take keeps the encoder surface
 * direct while the GPU only fans the ~30 fps preview share out to the operator and subject.
 * APV and RAW video own their own graphs and stay blocked.
 */
fun subjectPreviewBlock(mode: CaptureMode): SubjectPreviewBlock? = when (mode) {
    CaptureMode.APV, CaptureMode.RAW_VIDEO -> SubjectPreviewBlock.MODE
    else -> null
}

/** Main-thread identity/generation lease; a late destroy or callback cannot affect a newer surface. */
class SubjectSurfaceRegistry<T> {
    data class Lease<T>(val token: Long, val surface: T, val rotationDegrees: Int, val frameRate: Float = 0f)
    private var generation = 0L
    var current: Lease<T>? = null
        private set
    fun attach(surface: T, rotationDegrees: Int, frameRate: Float = 0f): Lease<T> {
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(frameRate.isFinite() && frameRate >= 0f)
        return Lease(++generation, surface, rotationDegrees, frameRate).also { current = it }
    }
    fun owns(token: Long): Boolean = current?.token == token
    fun release(token: Long): Boolean {
        if (!owns(token)) return false
        current = null
        generation++
        return true
    }
}
