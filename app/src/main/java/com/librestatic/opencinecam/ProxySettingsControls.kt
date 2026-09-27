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
        Text(stringResource(R.string.proxy_settings_help), Modifier.fillMaxWidth().testTag("proxy-settings-help"))
        Text(stringResource(R.string.proxy_settings_edge), Modifier.fillMaxWidth().testTag("proxy-settings-edge-label"))
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (edge in listOf(640, 1280, 1920)) ProxySettingOption(
                "edge-$edge", stringResource(R.string.proxy_settings_edge_value, edge), settings.maxLongEdge == edge,
            ) { if (settings.maxLongEdge != edge) onSettings(settings.copy(maxLongEdge = edge)) }
        }
        Text(stringResource(R.string.proxy_settings_bitrate), Modifier.fillMaxWidth().testTag("proxy-settings-bitrate-label"))
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (bitrate in listOf(1, 2, 3, 5, 8)) ProxySettingOption(
                "bitrate-$bitrate", stringResource(R.string.proxy_settings_bitrate_value, bitrate), settings.videoBitrateMbps == bitrate,
            ) { if (settings.videoBitrateMbps != bitrate) onSettings(settings.copy(videoBitrateMbps = bitrate)) }
        }
        Text(stringResource(R.string.proxy_policy_help), Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = policy.requireCharging, role = Role.Checkbox,
                onValueChange = { update { current -> current.copy(requireCharging = !current.requireCharging) } })
            .testTag("proxy-settings-charging"), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = policy.requireCharging, onCheckedChange = null)
            Text(stringResource(R.string.proxy_policy_charging), Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp)
                .testTag("proxy-settings-charging-label"))
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (level in listOf(0, 10, 20, 30, 50)) ProxySettingOption("battery-$level",
                stringResource(R.string.proxy_policy_battery, level), policy.minimumBatteryPercent == level) {
                update { it.copy(minimumBatteryPercent = level) }
            }
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (space in listOf(64, 256, 512, 1024, 2048)) ProxySettingOption("space-$space",
                stringResource(R.string.proxy_policy_space, space), policy.reserveSpaceMiB == space) {
                update { it.copy(reserveSpaceMiB = space) }
            }
        }
        error?.let { Text(it, Modifier.fillMaxWidth()) }
        Text(stringResource(R.string.proxy_settings_audio), Modifier.fillMaxWidth().testTag("proxy-settings-audio"))
    }
}

@Composable
private fun ProxySettingOption(tag: String, label: String, selected: Boolean, onSelect: () -> Unit) {
    SettingsPill(label, "proxy-settings-$tag", selected, onClick = onSelect)
}
