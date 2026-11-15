/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.media.audio.OpenedPcmInput
import com.librestatic.opencinecam.media.audio.PcmInputPath

/**
 * What a take's microphone path actually was (PLAN-074 decision 11), for the take record: the source
 * asked for, the API that delivered the PCM, why it is not the one asked for, the bit depth the
 * microphone path delivered, the equaliser applied (null when flat) and the profile it came from.
 */
data class PcmInputEvidence(
    val source: String,
    /** A [com.librestatic.opencinecam.media.audio.PcmInputPath] name. */
    val inputPath: String,
    val inputFallbackReason: String?,
    val captureBitDepth: Int,
    val equaliser: EqCurve?,
    /** The active [EqProfile]'s id when [equaliser] is applied; null for a custom unsaved curve or no equaliser. */
    val equaliserProfileId: String? = null,
)

/**
 * The curve a read loop applies on the input it opened. A bundled profile corrects the raw microphones only the
 * low-latency input delivers, so on an `AudioRecord` path, which the vendor already equalises (another source, an
 * external input, or the low-latency input's fallback), it is left out. A curve of the user's own applies on any path.
 */
fun appliedEqCurve(curve: EqCurve, profileId: String?, path: PcmInputPath): EqCurve =
    if (isBundledEqProfileId(profileId) && path != PcmInputPath.AAUDIO_LOW_LATENCY) EqCurve() else curve

/**
 * [source] is an [AudioSourceSelection] name, or `SOURCE_<n>` when only the platform source is known. [profileId]
 * names the profile [curve] came from; it is dropped with the curve when the curve is flat or not applied on the
 * opened path ([appliedEqCurve]). [PcmInputEvidence.equaliser] is the curve the read loop must run.
 */
fun pcmInputEvidence(source: String, opened: OpenedPcmInput, curve: EqCurve, profileId: String? = null): PcmInputEvidence {
    val applied = appliedEqCurve(curve, profileId, opened.input.path).takeUnless { it.isFlat }
    return PcmInputEvidence(source, opened.input.path.name, opened.fallbackReason, opened.captureBits, applied, profileId.takeIf { applied != null })
}

/** The equaliser for the curve [PcmInputEvidence.equaliser] records, or null when none applies. */
fun PcmInputEvidence.inputEqualizer(sampleRateHz: Int, channels: Int): InputEqualizer? =
    equaliser?.let { inputEqualizerFor(it, sampleRateHz, channels) }

/**
 * The equaliser a read loop runs for [curve], or null when the curve is flat, so the loop skips the
 * call and stays bit-exact without touching the buffer.
 */
fun inputEqualizerFor(curve: EqCurve, sampleRateHz: Int, channels: Int): InputEqualizer? =
    if (curve.isFlat) null else InputEqualizer(sampleRateHz, channels).apply { setCurve(curve) }
