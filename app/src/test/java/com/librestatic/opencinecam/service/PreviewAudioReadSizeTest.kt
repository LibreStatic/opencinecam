/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.AUDIO_METER_FRESHNESS_MS
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewAudioReadSizeTest {
    private fun readMs(rate: Int, channels: Int, depth: AudioBitDepth, bytesPerSample: Int): Double =
        previewReadBufferBytes(rate, channels, depth) / (channels * bytesPerSample).toDouble() * 1_000.0 / rate

    @Test fun everyLayoutReadsWellInsideTheMeterFreshnessWindow() {
        // Mono 16-bit at 48 kHz used to block ~683 ms per read and made the meter blink.
        for (rate in listOf(16_000, 44_100, 48_000, 96_000)) for (channels in 1..2) {
            listOf(AudioBitDepth.PCM_16 to 2, AudioBitDepth.PCM_24 to 3, AudioBitDepth.PCM_FLOAT to 4).forEach { (depth, size) ->
                val ms = readMs(rate, channels, depth, size)
                assertTrue("$rate Hz x$channels $depth reads $ms ms", ms <= AUDIO_METER_FRESHNESS_MS / 4)
            }
        }
    }

    @Test fun readsHoldWholeFrames() {
        assertEquals(0, previewReadBufferBytes(48_000, 2, AudioBitDepth.PCM_24) % (2 * 3))
        assertEquals(1_920 * 2, previewReadBufferBytes(48_000, 1, AudioBitDepth.PCM_16))
    }
}
