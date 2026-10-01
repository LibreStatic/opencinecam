/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Settings and the manual proxy dialog share the same caller-owned local preferences. */
@Composable
internal fun ProxySettingsControls(settings: ProxySettings, onSettings: (ProxySettings) -> Unit) {
    val app = LocalContext.current.applicationContext
    val policies = remember(app) { ProxyPolicies.get(app) }
    val policy by policies.states.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    fun update(transform: (ProxyPolicy) -> ProxyPolicy) { scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try { policies.update(transform); error = null } catch (failure: Exception) { error = failure.message }
    } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.proxy_settings_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        SettingsHelp(stringResource(R.string.proxy_settings_help), tag = "proxy-settings-help")
        SettingsChips(stringResource(R.string.proxy_settings_edge), listOf(640, 1280, 1920), settings.maxLongEdge,
            label = { stringResource(R.string.proxy_settings_edge_value, it) },
            onSelect = { if (settings.maxLongEdge != it) onSettings(settings.copy(maxLongEdge = it)) },
            tag = { "proxy-settings-edge-$it" }, rowTag = "proxy-settings-edge-label")
        SettingsChips(stringResource(R.string.proxy_settings_bitrate), listOf(1, 2, 3, 5, 8), settings.videoBitrateMbps,
            label = { stringResource(R.string.proxy_settings_bitrate_value, it) },
            onSelect = { if (settings.videoBitrateMbps != it) onSettings(settings.copy(videoBitrateMbps = it)) },
            tag = { "proxy-settings-bitrate-$it" }, rowTag = "proxy-settings-bitrate-label")
        SettingsHelp(stringResource(R.string.proxy_policy_help))
        SettingsSwitchRow(stringResource(R.string.proxy_policy_charging), policy.requireCharging,
            { update { current -> current.copy(requireCharging = !current.requireCharging) } },
            tag = "proxy-settings-charging", labelTag = "proxy-settings-charging-label")
        SettingsChips(stringResource(R.string.proxy_policy_battery_title), listOf(0, 10, 20, 30, 50), policy.minimumBatteryPercent,
            label = { stringResource(R.string.proxy_policy_battery, it) },
            onSelect = { level -> update { it.copy(minimumBatteryPercent = level) } }, tag = { "proxy-settings-battery-$it" })
        SettingsChips(stringResource(R.string.proxy_policy_space_title), listOf(64, 256, 512, 1024, 2048), policy.reserveSpaceMiB,
            label = { stringResource(R.string.proxy_policy_space, it) },
            onSelect = { space -> update { it.copy(reserveSpaceMiB = space) } }, tag = { "proxy-settings-space-$it" })
        error?.let { Text(it, Modifier.fillMaxWidth()) }
        Text(stringResource(R.string.proxy_settings_audio), Modifier.fillMaxWidth().testTag("proxy-settings-audio"))
    }
}

