/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.AudioEffectObservation
import com.librestatic.opencinecam.camera.AudioEffectsSnapshot
import org.json.JSONObject

/** Last public-effect observation on captured PCM, not a promise of uniform processing or hidden HAL state. */
internal fun audioEffectsJson(effects: AudioEffectsSnapshot, observedAtElapsedRealtimeMs: Long): JSONObject {
    fun observation(value: AudioEffectObservation) = JSONObject()
        .put("requested", value.requested).put("state", value.state.name)
        .put("implementation", value.implementation.name)
        .put("hasControl", value.hasControl ?: JSONObject.NULL)
        .put("configurationFailed", value.configurationFailed)
    return JSONObject().put("schema", "opencinecam.audio-effects.v1")
        .put("observation", "LAST_CAPTURED_PCM_BEFORE_RETIREMENT")
        .put("observedAtElapsedRealtimeMs", observedAtElapsedRealtimeMs)
        .put("noiseSuppressor", observation(effects.noiseSuppressor))
        .put("automaticGainControl", observation(effects.automaticGainControl))
        .put("acousticEchoCanceler", observation(effects.acousticEchoCanceler))
        .put("legacyEnabledFlags", "TRUE_ONLY_WHEN_OBSERVED_ENABLED; consult state for unknown/unavailable/failed")
        .put("hiddenHalProcessing", JSONObject.NULL)
}
