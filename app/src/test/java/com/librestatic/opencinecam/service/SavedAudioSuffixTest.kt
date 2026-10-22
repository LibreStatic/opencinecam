/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */
package com.librestatic.opencinecam.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedAudioSuffixTest {
    @Test fun silentTakeHasNoSuffix() {
        assertEquals("", savedAudioSuffix(null, "no audio"))
    }

    @Test fun aacTakeNamesTheTrack() {
        assertEquals(" + AAC 48 kHz stereo", savedAudioSuffix(null, "AAC 48 kHz stereo"))
    }

    @Test fun sidecarWinsOverTheAacLabel() {
        assertEquals(" + 24-bit WAV 48.0 kHz", savedAudioSuffix(SavedSidecarAudio(24, "WAV", 48_000), "AAC 48 kHz stereo"))
    }

    @Test fun nonAacLabelAddsNothing() {
        assertEquals("", savedAudioSuffix(null, "WAV sidecar"))
    }
}
