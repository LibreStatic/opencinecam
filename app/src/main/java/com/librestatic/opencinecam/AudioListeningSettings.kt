/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.runtime.staticCompositionLocalOf

/** Playback-only preference. Neither enables a microphone nor grants a live connection. */
data class AudioListeningSettings(
    val enabled: Boolean = false,
    val volumePercent: Int = 50,
    val output: AudioListeningOutput = AudioListeningOutput.WIRED_USB,
) {
    init { require(volumePercent in 0..100) }
}

enum class AudioListeningOutput { WIRED_USB, BLUETOOTH, SPEAKER }
enum class AudioListeningPhase {
    DISABLED, NEEDS_CONNECT, WAITING_PCM, NO_OUTPUT, CONNECTING, ACTIVE, DISCONNECTED, FAILED, RETIRING,
}

/** Runtime observation, never synthesized from a persisted enabled preference. */
data class AudioListeningStatus(
    val phase: AudioListeningPhase = AudioListeningPhase.DISABLED,
    val requestedDeviceId: Int? = null,
    val effectiveDeviceId: Int? = null,
    val deviceName: String? = null,
    val droppedPackets: Long = 0,
    val acceptedFrames: Long = 0,
    val message: String? = null,
) {
    init { require(droppedPackets >= 0 && acceptedFrames >= 0) }
}
data class AudioListeningDevice(val id: Int, val name: String, val output: AudioListeningOutput)

/** Only the explicit Connect button invokes this action. Recomposition never connects audio. */
internal val LocalAudioListeningActions = staticCompositionLocalOf<() -> Unit> { {} }
