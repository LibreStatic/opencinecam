/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.view.Surface
import com.librestatic.opencinecam.camera.SubjectPreviewStatus
import kotlinx.coroutines.flow.StateFlow

/** Output-only authority. Subject content never receives record, camera selection or settings actions. */
interface SubjectPreviewPort {
    val statuses: StateFlow<SubjectPreviewStatus>
    fun attach(surface: Surface, rotationDegrees: Int): AutoCloseable
}

/** Why the subject window cannot show the camera, or null when it can. */
enum class SubjectPreviewBlock { MODE, HIGH_SPEED }

/**
 * The subject preview rides the GPU viewfinder. A constrained high-speed VIDEO take must reach the
 * encoder directly (the GPU texture only gets the ~30 fps preview share), so the subject preview
 * never switches that graph to the GPU: it reports HIGH_SPEED instead of degrading the file.
 */
fun subjectPreviewBlock(mode: CaptureMode, videoConstrainedHighSpeed: Boolean): SubjectPreviewBlock? = when {
    mode != CaptureMode.VIDEO && mode != CaptureMode.LOG -> SubjectPreviewBlock.MODE
    mode == CaptureMode.VIDEO && videoConstrainedHighSpeed -> SubjectPreviewBlock.HIGH_SPEED
    else -> null
}

/** Main-thread identity/generation lease; a late destroy or callback cannot affect a newer surface. */
class SubjectSurfaceRegistry<T> {
    data class Lease<T>(val token: Long, val surface: T, val rotationDegrees: Int)
    private var generation = 0L
    var current: Lease<T>? = null
        private set
    fun attach(surface: T, rotationDegrees: Int): Lease<T> {
        require(rotationDegrees in setOf(0, 90, 180, 270))
        return Lease(++generation, surface, rotationDegrees).also { current = it }
    }
    fun owns(token: Long): Boolean = current?.token == token
    fun release(token: Long): Boolean {
        if (!owns(token)) return false
        current = null
        generation++
        return true
    }
}
