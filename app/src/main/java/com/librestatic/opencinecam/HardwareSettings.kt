/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.ui.theme.LocalCineColors

/**
 * What the camera in use offers, in plain words: the preview and photo sizes, RAW, and how far
 * OCLog2 reaches. The per-key detail is on the camera capabilities page.
 */
@Composable
internal fun HardwareSettingsSummary(descriptor: Camera2CameraDescriptor?) {
    SettingsSectionTitle(stringResource(R.string.hardware_truth))
    val colors = MaterialTheme.colorScheme
    Text(stringResource(R.string.hardware_camera_line, descriptor?.cameraId ?: "—", descriptor?.previewSize?.width ?: 0, descriptor?.previewSize?.height ?: 0),
        color = colors.onSurface, fontSize = 14.sp)
    Text(stringResource(R.string.hardware_still_line, descriptor?.jpegSize?.width ?: 0, descriptor?.jpegSize?.height ?: 0,
        stringResource(if (descriptor?.supportsRaw == true) R.string.hardware_supported else R.string.hardware_not_supported)),
        color = SettingsMuted, fontSize = 14.sp)
    val profiles = descriptor?.logProfiles.orEmpty().takeIf { descriptor?.supportsOpenCineLog == true }.orEmpty()
    if (profiles.isEmpty()) {
        Text(stringResource(R.string.hardware_log_unsupported), Modifier.testTag("hardware-log"), color = SettingsMuted, fontSize = 14.sp)
        return
    }
    val verified = profiles.count { it.isVerified }
    val qualification = if (verified == profiles.size) stringResource(R.string.hardware_log_all_verified)
        else stringResource(R.string.hardware_log_profiles_verified, verified, profiles.size)
    Text(stringResource(R.string.hardware_log_status, qualification), Modifier.testTag("hardware-log"),
        color = if (verified == profiles.size) LocalCineColors.current.verified else LocalCineColors.current.pending, fontSize = 14.sp)
    val hdr = profiles.filter { it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020 }
    if (hdr.isNotEmpty()) {
        Text(stringResource(R.string.hardware_log_hdr, hdr.maxOf { it.size.width }, hdr.maxOf { it.size.height }, hdr.maxOf { it.fps }),
            color = SettingsMuted, fontSize = 14.sp)
    }
    val highSpeed = profiles.filter { it.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP }
    if (highSpeed.isNotEmpty()) {
        Text(stringResource(R.string.hardware_log_high_speed, highSpeed.maxOf { it.fps }), color = SettingsMuted, fontSize = 14.sp)
    }
}
