/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Consent and Android access are independent: granting access never changes this preference. */
@Composable
internal fun GeotaggingSettingsControls(settings: CameraSettings, onSettingsChange: (CameraSettings) -> Unit) {
    val context = LocalContext.current
    val locations = remember(context) { CaptureLocations.get(context) }
    val status by locations.status.collectAsState()
    var precision by remember { mutableStateOf(locationPermission(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var requesting by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requesting = false
        precision = locationPermission(context)
        denied = precision == null
        locations.refreshAccess()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, locations, context) {
        fun refresh() {
            precision = locationPermission(context)
            if (precision != null) denied = false
            locations.refreshAccess()
        }
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    GeotaggingSettingsContent(settings, status, precision, denied, requesting, onSettingsChange,
        onOpenAppSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri()))
        }, onRequestPermission = {
            if (!requesting) {
                requesting = true
                // Both permissions in one request allow Android's approximate-location choice.
                launcher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
        })
}

@Composable
internal fun GeotaggingSettingsContent(
    settings: CameraSettings,
    status: CaptureLocationStatus,
    precision: LocationPermissionPrecision?,
    denied: Boolean,
    requesting: Boolean,
    onSettingsChange: (CameraSettings) -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.geotagging_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.geotagging_help), Modifier.fillMaxWidth().testTag("geotagging-help"))
        val label = stringResource(R.string.geotagging_enabled)
        SettingsSwitchRow(label, settings.geotaggingEnabled, { onSettingsChange(settings.copy(geotaggingEnabled = it)) },
            tag = "geotagging-enabled", labelTag = "geotagging-enabled-label")
        Text(stringResource(when (precision) {
            null -> R.string.geotagging_permission_none
            LocationPermissionPrecision.APPROXIMATE -> R.string.geotagging_permission_approximate
            LocationPermissionPrecision.PRECISE -> R.string.geotagging_permission_precise
        }), Modifier.fillMaxWidth().testTag("geotagging-permission"))
        Text(stringResource(if (!settings.geotaggingEnabled) R.string.geotagging_status_disabled
            else if (precision == null) R.string.geotagging_status_no_permission
            else when (status) {
                CaptureLocationStatus.AVAILABLE -> R.string.geotagging_status_available
                CaptureLocationStatus.NO_PERMISSION -> R.string.geotagging_status_no_permission
                CaptureLocationStatus.INACTIVE -> R.string.geotagging_status_inactive
                CaptureLocationStatus.UNAVAILABLE -> R.string.geotagging_status_unavailable
                CaptureLocationStatus.STALE -> R.string.geotagging_status_stale
            }), Modifier.fillMaxWidth().testTag("geotagging-status"))
        if (settings.geotaggingEnabled && precision == null) {
            if (denied) Text(stringResource(R.string.geotagging_denied),
                Modifier.fillMaxWidth().testTag("geotagging-denied"))
            OutlinedButton(onClick = onRequestPermission, enabled = !requesting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("geotagging-retry")) {
                Text(stringResource(if (requesting) R.string.geotagging_requesting else R.string.geotagging_request),
                    Modifier.weight(1f).testTag("geotagging-retry-label"))
            }
            // Offer system settings after any denial, including when Android suppresses further prompts.
            // This action grants nothing by itself and never changes the local recording choice.
            if (denied) OutlinedButton(onClick = onOpenAppSettings, enabled = !requesting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("geotagging-app-settings")) {
                Text(stringResource(R.string.geotagging_open_settings),
                    Modifier.weight(1f).testTag("geotagging-app-settings-label"))
            }
        }
    }
}
