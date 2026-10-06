/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.annotation.StringRes
import com.librestatic.opencinecam.media.audio.AudioInputKind

/** The localized family of an audio input, as Settings and notices name it. */
@StringRes
fun audioInputTypeLabel(kind: AudioInputKind): Int = when (kind) {
    AudioInputKind.BUILT_IN -> R.string.audio_input_type_builtin
    AudioInputKind.USB -> R.string.audio_input_type_usb
    AudioInputKind.BLUETOOTH -> R.string.audio_input_type_bluetooth
    AudioInputKind.WIRED -> R.string.audio_input_type_wired
    AudioInputKind.OTHER -> R.string.audio_input_type_other
}

/**
 * Where a built-in microphone sits, from the address the HAL reports ("bottom", "back"...).
 * Phones with several built-in microphones list them all under the same type and no product name.
 */
@StringRes
fun builtInMicPositionLabel(address: String): Int? = when (address.trim().lowercase()) {
    "bottom" -> R.string.audio_input_position_bottom
    "back" -> R.string.audio_input_position_back
    "top" -> R.string.audio_input_position_top
    "front" -> R.string.audio_input_position_front
    else -> null
}

/** Numbers the labels that are still identical, in list order: "X (1)", "X (2)". Unique ones are kept. */
fun uniqueAudioInputLabels(labels: List<String>): List<String> {
    val totals = labels.groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return labels.map { label ->
        if (totals.getValue(label) < 2) label else "$label (${seen.merge(label, 1, Int::plus)})"
    }
}
