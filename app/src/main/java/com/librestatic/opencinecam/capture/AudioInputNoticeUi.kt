/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.capture

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.media.audio.PcmInputs
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.ui.CineIcon
import com.librestatic.opencinecam.ui.theme.LocalCineColors

/** The capture notices about the microphone input (PLAN-074 decisions 4 and 14), in the order they stack. */
internal enum class AudioInputNotice { OVERLOAD, FALLBACK }

/**
 * True when a PCM input opened with [fallbackReason] should say the low-latency input was not used. An external
 * input keeps `AudioRecord` by design (decision 2), so that one is not a fallback the operator can do anything about.
 */
internal fun audioInputFallbackShown(fallbackReason: String?): Boolean =
    fallbackReason != null && fallbackReason != PcmInputs.FALLBACK_EXTERNAL_INPUT

/** The notices due in a mode that records sound: the overload first, then the fallback; none with the sound off. */
internal fun audioInputNotices(state: CameraUiState, settings: CameraSettings): List<AudioInputNotice> {
    if (!settings.audioEnabled || state.selectedMode !in AUDIO_NOTICE_MODES) return emptyList()
    return buildList {
        if (state.audioInputOverloaded) add(AudioInputNotice.OVERLOAD)
        if (state.audioInputFallback) add(AudioInputNotice.FALLBACK)
    }
}

private val AUDIO_NOTICE_MODES = setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG, CaptureMode.RAW_VIDEO)

/** Placed like the graph fallback notice, in the instrument stack under the zoom. */
@Composable
internal fun AudioInputNoticeView(notice: AudioInputNotice, modifier: Modifier = Modifier) = when (notice) {
    AudioInputNotice.OVERLOAD -> InstrumentNotice(stringResource(R.string.audio_input_overload), CineIcon.WARNING,
        LocalCineColors.current.pending, "audio-input-overload", modifier)
    AudioInputNotice.FALLBACK -> InstrumentNotice(stringResource(R.string.audio_input_fallback), CineIcon.WARNING,
        LocalCineColors.current.pending, "audio-input-fallback", modifier)
}
