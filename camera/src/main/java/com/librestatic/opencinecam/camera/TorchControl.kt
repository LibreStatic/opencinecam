/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Capture-session strength, not CameraManager's closed-camera flashlight capability. */
data class TorchCapabilities(val available: Boolean, val maxLevel: Int = 1, val defaultLevel: Int = 1) {
    init {
        require(maxLevel >= 1)
        require(defaultLevel in 1..maxLevel)
    }

    val adjustable: Boolean get() = available && maxLevel > 1

    fun resolve(enabled: Boolean, requestedLevel: Int?, highSpeed: Boolean): TorchRequest {
        val active = available && enabled && !highSpeed
        return TorchRequest(active, if (active && adjustable) (requestedLevel ?: defaultLevel).coerceIn(1, maxLevel) else null)
    }
}

data class TorchRequest(val enabled: Boolean, val strengthLevel: Int?)
