/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.media.audiofx.AudioEffect

/**
 * Non-owning observation of a retained handle. Call only while the producer owns the session,
 * before its release. No setters, fallback decisions, or cleanup happen during observation.
 * Android reports enabled state independently of control ownership; either getter may fail:
 * https://developer.android.com/reference/android/media/audiofx/AudioEffect#hasControl()
 */
class AudioEffectObservationReader(
    private val requested: Boolean,
    private val effect: AudioEffect?,
    private val available: Boolean?,
    private val configurationFailed: Boolean = false,
    private val softwareEnabled: Boolean = false,
) {
    fun read(): AudioEffectObservation = readAudioEffectObservation(
        requested, available, configurationFailed, softwareEnabled,
        effect?.let { { it.enabled } }, effect?.let { { it.hasControl() } },
    )
}
