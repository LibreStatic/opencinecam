/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import java.text.DateFormat
import java.util.Date

/**
 * The lens calibration page: what the run needs, the calibration saved for this camera with its
 * numbers, the three switches that apply it, and the button that starts a run on the viewfinder.
 * Stateless, so Compose Driver can render it with fake state.
 */
@Composable
internal fun LensCalibrationContent(
    state: LensCalibrationUiState,
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val saved = state.savedCreatedAtMs
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val gutter = settingsSideGutterDp(maxWidth.value)
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("lens-calibration-page"),
            contentPadding = PaddingValues(horizontal = gutter.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") { SettingsTopBar(stringResource(R.string.lens_cal_title), onBack, backTag = "lens-calibration-back") }
            item(key = "intro") { Text(stringResource(R.string.lens_cal_intro), color = SettingsMuted, fontSize = 13.sp) }
            item(key = "scene") {
                SettingsCard {
                    SettingsHeading(stringResource(R.string.lens_cal_scene_title), icon = CineIcon.INFO)
                    Text(stringResource(R.string.lens_cal_scene_guide), color = SettingsMuted, fontSize = 13.sp)
                    Button(onStart, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lens-calibration-start")) {
                        Text(stringResource(R.string.lens_cal_start))
                    }
                }
            }
            item(key = "saved") {
                SettingsCard(Modifier.testTag("lens-calibration-saved")) {
                    SettingsHeading(
                        stringResource(R.string.lens_cal_saved_title),
                        saved?.let { stringResource(R.string.lens_cal_saved_date, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) },
                        CineIcon.CHECK.takeIf { saved != null },
                    )
                    if (saved == null) {
                        Text(stringResource(R.string.lens_cal_none), color = SettingsMuted, fontSize = 13.sp, modifier = Modifier.testTag("lens-calibration-empty"))
                    } else {
                        state.savedLenses.forEach { LensCalibrationLensLine(it, state, running = false, muted = SettingsMuted) }
                        if (state.illuminantMismatch) Text(stringResource(R.string.lens_cal_illuminant_warning), color = LocalCineColors.current.pending,
                            fontSize = 13.sp, modifier = Modifier.testTag("lens-calibration-illuminant"))
                        OutlinedButton(
                            { confirmDelete = true },
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lens-calibration-delete"),
                        ) { Text(stringResource(R.string.lens_cal_delete)) }
                    }
                }
            }
            item(key = "toggles") {
                SettingsCard {
                    SettingsHeading(stringResource(R.string.lens_cal_corrections_title), if (saved == null) stringResource(R.string.lens_cal_toggles_hint) else null)
                    SettingsSwitchRow(stringResource(R.string.lens_cal_toggle_video), settings.lensCorrectionVideo,
                        { onSettingsChange(settings.copy(lensCorrectionVideo = it)) }, "lens-calibration-toggle-video")
                    SettingsSwitchRow(stringResource(R.string.lens_cal_toggle_photos), settings.lensCorrectionPhotos,
                        { onSettingsChange(settings.copy(lensCorrectionPhotos = it)) }, "lens-calibration-toggle-photos")
                    Text(stringResource(R.string.lens_cal_photos_note), color = SettingsMuted, fontSize = 12.sp)
                    SettingsSwitchRow(stringResource(R.string.lens_cal_toggle_alignment), settings.lensCorrectionAlignment,
                        { onSettingsChange(settings.copy(lensCorrectionAlignment = it)) }, "lens-calibration-toggle-alignment")
                    Text(stringResource(R.string.lens_cal_alignment_note), color = SettingsMuted, fontSize = 12.sp)
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.lens_cal_delete_title)) },
        text = { Text(stringResource(R.string.lens_cal_delete_body)) },
        confirmButton = {
            TextButton({ confirmDelete = false; onDelete() }, Modifier.testTag("lens-calibration-delete-confirm")) {
                Text(stringResource(R.string.lens_cal_delete_confirm))
            }
        },
        dismissButton = {
            TextButton({ confirmDelete = false }, Modifier.testTag("lens-calibration-delete-cancel")) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
