/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */
package com.librestatic.opencinecam.service

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedAudioSuffixTest {
    private val spanish = Locale.forLanguageTag("es")

    @Test fun silentTakeHasNoSuffix() {
        assertEquals("", savedAudioSuffix(null, "no audio", Locale.US))
    }

    @Test fun aacTakeNamesTheTrack() {
        assertEquals(" + AAC 48 kHz stereo", savedAudioSuffix(null, "AAC 48 kHz stereo", Locale.US))
    }

    @Test fun sidecarWinsOverTheAacLabel() {
        assertEquals(" + 24-bit WAV 48.0 kHz", savedAudioSuffix(SavedSidecarAudio(24, "WAV", 48_000), "AAC 48 kHz stereo", Locale.US))
    }

    @Test fun sidecarRateFollowsTheLocaleNumberFormat() {
        assertEquals(" + 24-bit WAV 48,0 kHz", savedAudioSuffix(SavedSidecarAudio(24, "WAV", 48_000), "", spanish))
        assertEquals("44,1", kiloHertzText(44_100, spanish))
    }

    @Test fun nonAacLabelAddsNothing() {
        assertEquals("", savedAudioSuffix(null, "WAV sidecar", Locale.US))
    }

    @Test fun ratioKeepsOneToTwoDecimalsInTheLocaleFormat() {
        assertEquals("2.0", ratioText(2f, Locale.US))
        assertEquals("2,25", ratioText(2.25f, spanish))
        assertEquals("1.33", ratioText(1.3333f, Locale.US))
    }
}
