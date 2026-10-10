/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Motion data needs a gyroscope; without one the setting is hidden and nothing is ever recorded. */
internal fun deviceHasGyroscope(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
}.getOrDefault(false)

/** Local consent, like geotagging: never part of a portable preset. */
@Composable
internal fun GyroflowSettingsControls(settings: CameraSettings, onSettingsChange: (CameraSettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle(stringResource(R.string.gyroflow_log_title), help = stringResource(R.string.gyroflow_log_help), helpTag = "gyroflow-log-help")
        SettingsSwitchRow(stringResource(R.string.gyroflow_log_enabled), settings.gyroflowLogEnabled,
            { onSettingsChange(settings.copy(gyroflowLogEnabled = it)) },
            tag = "gyroflow-log-enabled", labelTag = "gyroflow-log-enabled-label")
    }
}
