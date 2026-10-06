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
