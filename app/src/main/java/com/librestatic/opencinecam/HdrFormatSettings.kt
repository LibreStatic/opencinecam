/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.HdrVideoFormat
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath

internal const val HDR_FORMAT_SETTING_ID = "hdr-format"

/** Whether any HLG profile of the camera can record HDR10: the camera advertises it and a hardware encoder takes the profile. */
internal val Camera2CameraDescriptor?.offersHdr10Video: Boolean
    get() = this?.logProfiles.orEmpty().any { it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020 && it.hdr10 }

/** HLG-mode transfer choice; shown only where [offersHdr10Video]. */
@Composable
internal fun HdrFormatSettings(settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    SettingsOptionRow(
        stringResource(R.string.hdr_format),
        stringResource(if (settings.hdrVideoFormat == HdrVideoFormat.HDR10) R.string.hdr_format_hdr10_summary else R.string.hdr_format_hlg_summary),
        HdrVideoFormat.entries, settings.hdrVideoFormat,
        label = { stringResource(if (it == HdrVideoFormat.HDR10) R.string.hdr_format_hdr10 else R.string.hdr_format_hlg) },
        tag = { "hdr-format-$it" }, onSelect = { onChange(settings.copy(hdrVideoFormat = it)) },
    )
}
