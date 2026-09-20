/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl

/**
 * Transfers every acquired handle to [retain] before checking its state. The caller must retain
 * ownership without throwing and release through its native-retirement path, including rejection.
 * This helper never hides a failed release behind a gain-configuration error.
 */
fun createDisabledManualAgc(
    audioSessionId: Int,
    retain: (AutomaticGainControl) -> Unit,
): AutomaticGainControl? {
    val available = AutomaticGainControl.isAvailable()
    val effect = AutomaticGainControl.create(audioSessionId)
    if (effect == null) {
        check(!available) { "The advertised AGC effect could not be controlled for manual recording gain." }
        return null // No public effect; this does not certify analog gain or hidden HAL processing.
    }
    retain(effect)
    check(effect.setEnabled(false) == AudioEffect.SUCCESS && !effect.enabled) {
        "AGC could not be disabled for manual recording gain."
    }
    return effect
}
