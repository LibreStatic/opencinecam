/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.librestatic.opencinecam.camera.PreviewGraphLoss
import com.librestatic.opencinecam.camera.StillPhotoFormat
import com.librestatic.opencinecam.ui.theme.LocalCineColors

/** The still [format] needs the RAW stream the session's graph dropped, so the shutter cannot take it. */
internal fun stillFormatLostRaw(format: StillPhotoFormat, loss: PreviewGraphLoss): Boolean =
    loss.raw && (format == StillPhotoFormat.DNG || format == StillPhotoFormat.RAW_JPEG)

/** At least one monitor that runs on the analysis stream is on. */
internal fun analysisMonitorsWanted(settings: CameraSettings): Boolean =
    settings.zebraEnabled || settings.peakingEnabled || settings.histogramEnabled ||
        settings.monitoring.waveformEnabled || settings.monitoring.vectorscopeEnabled || settings.monitoring.falseColorEnabled

/**
 * The notice for what the session's graph lost, or null: RAW only when the mode wants a DNG, the
 * scopes only when a monitor that needs the analysis stream is on. A lost physical still size has
 * no notice.
 */
internal fun graphFallbackNoticeText(mode: CaptureMode, settings: CameraSettings, loss: PreviewGraphLoss): Int? {
    val format = when (mode) {
        CaptureMode.RAW_PHOTO -> StillPhotoFormat.DNG
        CaptureMode.PHOTO -> settings.photoFormat
        else -> null
    }
    val raw = format != null && stillFormatLostRaw(format, loss)
    val scopes = loss.analysis && analysisMonitorsWanted(settings)
    return when {
        raw && scopes -> R.string.graph_fallback_raw_scopes
        raw -> R.string.graph_fallback_raw
        scopes -> R.string.graph_fallback_scopes
        else -> null
    }
}

/** Placed like the lens lock's crop notice, in the instrument stack under the zoom. */
@Composable
internal fun GraphFallbackNotice(text: Int, modifier: Modifier = Modifier) =
    InstrumentNotice(stringResource(text), CineIcon.WARNING, LocalCineColors.current.pending, "graph-fallback-notice", modifier)
