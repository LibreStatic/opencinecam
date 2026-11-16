/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.media.audio.PcmInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputNoticeUiTest {
    private val both = CameraUiState(selectedMode = CaptureMode.VIDEO, audioInputOverloaded = true, audioInputFallback = true)
    private val sound = CameraSettings(audioEnabled = true)

    @Test fun bothShowOverloadFirstInEveryModeThatRecordsSound() {
        listOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG, CaptureMode.RAW_VIDEO).forEach { mode ->
            assertEquals(mode.name, listOf(AudioInputNotice.OVERLOAD, AudioInputNotice.FALLBACK),
                audioInputNotices(both.copy(selectedMode = mode), sound))
        }
    }

    @Test fun eachShowsAlone() {
        assertEquals(listOf(AudioInputNotice.OVERLOAD), audioInputNotices(both.copy(audioInputFallback = false), sound))
        assertEquals(listOf(AudioInputNotice.FALLBACK), audioInputNotices(both.copy(audioInputOverloaded = false), sound))
        assertEquals(emptyList<AudioInputNotice>(), audioInputNotices(both.copy(audioInputOverloaded = false, audioInputFallback = false), sound))
    }

    @Test fun noneWithoutSoundOrInAStillMode() {
        assertEquals(emptyList<AudioInputNotice>(), audioInputNotices(both, sound.copy(audioEnabled = false)))
        listOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO).forEach { mode ->
            assertEquals(mode.name, emptyList<AudioInputNotice>(), audioInputNotices(both.copy(selectedMode = mode), sound))
        }
    }

    @Test fun anExternalInputIsNotAFallback() {
        assertFalse(audioInputFallbackShown(null))
        assertFalse(audioInputFallbackShown(PcmInputs.FALLBACK_EXTERNAL_INPUT))
        assertTrue(audioInputFallbackShown("performance-mode"))
    }
}
