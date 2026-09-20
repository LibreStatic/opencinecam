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
