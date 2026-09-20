/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

enum class AudioEffectState { UNKNOWN, UNAVAILABLE, ENABLED, DISABLED, FAILED }
enum class AudioEffectImplementation { NONE, PLATFORM, SOFTWARE }

/** Requested is the original producer preference; observed state is never inferred from it. */
data class AudioEffectObservation(
    val requested: Boolean,
    val state: AudioEffectState,
    val implementation: AudioEffectImplementation,
    val hasControl: Boolean? = null,
    val configurationFailed: Boolean = false,
)

data class AudioEffectsSnapshot(
    val noiseSuppressor: AudioEffectObservation,
    val automaticGainControl: AudioEffectObservation,
    val acousticEchoCanceler: AudioEffectObservation,
)

/** Pure reader core; nullable accessors mean that no public platform handle was acquired. */
internal fun readAudioEffectObservation(
    requested: Boolean,
    available: Boolean?,
    configurationFailed: Boolean,
    softwareEnabled: Boolean,
    enabled: (() -> Boolean)?,
    hasControl: (() -> Boolean)?,
): AudioEffectObservation {
    val setupFailed = configurationFailed || enabled == null && available == true
    if (enabled == null) return AudioEffectObservation(requested,
        when {
            softwareEnabled -> AudioEffectState.ENABLED
            setupFailed -> AudioEffectState.FAILED
            available == false -> AudioEffectState.UNAVAILABLE
            else -> AudioEffectState.UNKNOWN
        }, if (softwareEnabled) AudioEffectImplementation.SOFTWARE else AudioEffectImplementation.NONE,
        configurationFailed = setupFailed)
    var readFailed = false
    val actualEnabled = try { enabled() } catch (_: Throwable) { readFailed = true; null }
    val actualControl = try { hasControl?.invoke() } catch (_: Throwable) { readFailed = true; null }
    if (softwareEnabled && !readFailed && actualEnabled == false) return AudioEffectObservation(
        requested, AudioEffectState.ENABLED, AudioEffectImplementation.SOFTWARE,
        configurationFailed = setupFailed)
    return AudioEffectObservation(requested,
        when {
            readFailed || actualEnabled == null || softwareEnabled -> AudioEffectState.FAILED
            actualEnabled == true -> AudioEffectState.ENABLED
            else -> AudioEffectState.DISABLED
        }, AudioEffectImplementation.PLATFORM, actualControl,
        configurationFailed = setupFailed || softwareEnabled)
}

/** Rechecked before PCM gain, not inferred from a prior successful disable request. */
fun requireExclusiveAgcObservation(
    observation: AudioEffectObservation,
    hasHardwareHandle: Boolean,
    manualGainEnabled: Boolean,
    softwareAgcEnabled: Boolean,
) {
    check(!hasHardwareHandle || !(manualGainEnabled || softwareAgcEnabled) || observation.state == AudioEffectState.DISABLED) {
        "Platform AGC is no longer confirmed disabled for exclusive digital gain"
    }
}
