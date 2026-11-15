/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqProfile
import com.librestatic.opencinecam.camera.isBundledEqProfileId
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities

/** The audio input a phone starts with (PLAN-074 decision 1): the source and its equaliser. */
data class AudioInputDefault(val source: AudioSourceSelection, val eqProfileId: String?, val eqCurve: EqCurve)

/**
 * With the `audio-default-low-latency-input` quirk and the native library, the low-latency input
 * with the device's bundled profile (a flat curve if none ships); otherwise Camcorder, flat.
 */
fun audioInputDefault(lowLatencyDefault: Boolean, lowLatencyAvailable: Boolean, bundledProfile: EqProfile?): AudioInputDefault =
    if (lowLatencyDefault && lowLatencyAvailable) {
        AudioInputDefault(AudioSourceSelection.LOW_LATENCY, bundledProfile?.id, bundledProfile?.curve ?: EqCurve())
    } else {
        AudioInputDefault(AudioSourceSelection.CAMCORDER, null, EqCurve())
    }

/**
 * What this phone's device profile says about the audio input, resolved off the main thread
 * ([AudioInputPolicies]). [NONE] is a phone without a profile.
 */
data class AudioInputPolicy(
    /** The `audio-default-low-latency-input` quirk. */
    val lowLatencyDefault: Boolean = false,
    /** The `audio-unprocessed-source-clips` quirk. */
    val unprocessedClips: Boolean = false,
    /** The native low-latency library loads. */
    val lowLatencyAvailable: Boolean = false,
    /** The bundled equaliser profile for this device, if one ships. */
    val bundledProfile: EqProfile? = null,
) {
    val default: AudioInputDefault get() = audioInputDefault(lowLatencyDefault, lowLatencyAvailable, bundledProfile)

    companion object {
        val NONE = AudioInputPolicy()
    }
}

/** The audio keys as stored before the one-time migration; null is a key that is absent or unreadable. */
data class StoredAudioInput(val source: AudioSourceSelection?, val channels: Int?, val bitrateKbps: Int?)

/** The outcome of the one-time audio input migration. */
sealed interface MigratedAudioInput {
    /** A choice the user made: nothing changes. */
    data object Kept : MigratedAudioInput

    /** Never touched: the input moves to the phone's default. [bitrateKbps] null keeps the stored rate. */
    data class Moved(
        val source: AudioSourceSelection,
        val channels: Int,
        val bitrateKbps: Int?,
        val eqProfileId: String?,
        val eqCurve: EqCurve,
    ) : MigratedAudioInput
}

/** The AAC rate of 1.0.0 and the one that replaces it. */
private const val OLD_DEFAULT_AAC_KBPS = 128
private const val DEFAULT_AAC_KBPS = 320

/**
 * Settings are stored whole, so an install that never touched audio holds 1.0.0's defaults:
 * `UNPROCESSED` and mono (an absent channel count reads the same). That one, and a fresh install
 * with no stored source, moves to [default] in stereo, and AAC moves from 128 to 320 kb/s, the highest rate offered, if it was
 * 128 or absent. Any other stored combination is a choice and is [MigratedAudioInput.Kept].
 */
fun migrateAudioInput(stored: StoredAudioInput, default: AudioInputDefault): MigratedAudioInput {
    val untouched = stored.source == null ||
        (stored.source == AudioSourceSelection.UNPROCESSED && (stored.channels == null || stored.channels == 1))
    if (!untouched) return MigratedAudioInput.Kept
    val bitrate = DEFAULT_AAC_KBPS.takeIf { stored.bitrateKbps == null || stored.bitrateKbps == OLD_DEFAULT_AAC_KBPS }
    return MigratedAudioInput.Moved(default.source, 2, bitrate, default.eqProfileId, default.eqCurve)
}

fun CameraSettings.withMigratedAudioInput(migrated: MigratedAudioInput): CameraSettings = when (migrated) {
    MigratedAudioInput.Kept -> this
    is MigratedAudioInput.Moved -> copy(
        audioSource = migrated.source,
        audioChannels = migrated.channels,
        audioBitrateKbps = migrated.bitrateKbps ?: audioBitrateKbps,
        audioEqProfileId = migrated.eqProfileId,
        audioEqCurve = migrated.eqCurve,
    )
}

/**
 * On a phone whose vendor `UNPROCESSED` path clips before the app sees it, a stored `UNPROCESSED`
 * loads as the low-latency input, or as Camcorder without the native library. Any other source stays.
 */
fun remappedAudioSource(source: AudioSourceSelection, unprocessedClips: Boolean, lowLatencyAvailable: Boolean): AudioSourceSelection =
    when {
        source != AudioSourceSelection.UNPROCESSED || !unprocessedClips -> source
        lowLatencyAvailable -> AudioSourceSelection.LOW_LATENCY
        else -> AudioSourceSelection.CAMCORDER
    }

/**
 * The curve the read loops apply. A bundled profile corrects the raw microphones the low-latency input delivers, so
 * with any other source, which the vendor already equalises, it is left out and comes back with the low-latency
 * input. A curve of the user's own applies with every source. Once an input is open, the read loops narrow it to
 * the path that opened ([com.librestatic.opencinecam.camera.appliedEqCurve]): the low-latency input's `AudioRecord`
 * fallback, and an external input, leave a bundled profile out too.
 */
val CameraSettings.appliedAudioEqCurve: EqCurve
    get() = if (isBundledEqProfileId(audioEqProfileId) && audioSource != AudioSourceSelection.LOW_LATENCY) EqCurve() else audioEqCurve

/** The profile [appliedAudioEqCurve] came from, for the take record; null when that curve is flat or has no profile. */
val CameraSettings.appliedAudioEqProfileId: String?
    get() = audioEqProfileId?.takeUnless { appliedAudioEqCurve.isFlat }

/**
 * The audio input as it loads on this phone: the one-time migration when [stored] is not null
 * (it has not run yet), then the `UNPROCESSED` remap, which applies on every load.
 */
fun CameraSettings.settledAudioInput(stored: StoredAudioInput?, policy: AudioInputPolicy): CameraSettings {
    val migrated = stored?.let { withMigratedAudioInput(migrateAudioInput(it, policy.default)) } ?: this
    val source = remappedAudioSource(migrated.audioSource, policy.unprocessedClips, policy.lowLatencyAvailable)
    if (source == migrated.audioSource) return migrated.withCurrentBundledCurve(policy)
    // The low-latency input lacks the vendor's equalisation, so a remap onto it brings the phone's
    // default curve along unless the user already has one.
    val default = policy.default
    val bringCurve = source == default.source && migrated.audioEqProfileId == null && migrated.audioEqCurve == EqCurve()
    return migrated.copy(
        audioSource = source,
        audioEqProfileId = if (bringCurve) default.eqProfileId else migrated.audioEqProfileId,
        audioEqCurve = if (bringCurve) default.eqCurve else migrated.audioEqCurve,
    ).withCurrentBundledCurve(policy)
}

/**
 * A bundled profile is read-only, so the stored copy of its curve follows the one this build ships (a profile can
 * be measured again between releases); only the copy's bypass is the user's.
 */
private fun CameraSettings.withCurrentBundledCurve(policy: AudioInputPolicy): CameraSettings {
    val bundled = policy.bundledProfile?.takeIf { it.id == audioEqProfileId } ?: return this
    val current = bundled.curve.copy(enabled = audioEqCurve.enabled)
    return if (current == audioEqCurve) this else copy(audioEqCurve = current)
}

/**
 * The probed capabilities as this phone offers them: `UNPROCESSED` leaves the source list where it
 * clips, so neither the settings nor [normalizedFor] can pick it, and a source that is not offered
 * falls back to the low-latency input where the profile makes it the default.
 */
fun ProfessionalAudioCapabilities.forDevice(policy: AudioInputPolicy): ProfessionalAudioCapabilities = copy(
    sources = if (policy.unprocessedClips) sources - AudioSourceSelection.UNPROCESSED else sources,
    preferredSource = AudioSourceSelection.LOW_LATENCY.takeIf { policy.lowLatencyDefault },
)
