/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.runtime.ReadOnlyComposable
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.max

private val AboutGraphite: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
private val AboutCard: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh
private val AboutAmber: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
private val AboutCyan: Color @Composable @ReadOnlyComposable get() = LocalCineColors.current.verified
private val AboutMuted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

private const val PROJECT_WEBSITE = "https://librestatic.com/opencinecam"
private const val PROJECT_SOURCE = "https://github.com/librestatic/opencinecam"
private const val LICENSE_CATALOG_ASSET = "third_party_licenses.json"
private const val APP_LICENSE_ASSET = "licenses/Apache-2.0.txt"
private const val ABOUT_MAX_WIDTH_DP = 720f

internal data class ThirdPartyComponent(
    val group: String,
    val name: String,
    val version: String,
    val licenseId: String,
    val licenseName: String,
    val licenseTextAsset: String,
) {
    val coordinate: String = "$group:$name:$version"
}

internal fun loadThirdPartyComponents(context: Context): List<ThirdPartyComponent> {
    val document = context.assets.open(LICENSE_CATALOG_ASSET).bufferedReader().use { JSONObject(it.readText()) }
    require(document.getInt("schemaVersion") == 1) { "Unsupported third-party license catalog schema" }
    val components = document.getJSONArray("components")
    return buildList(components.length()) {
        repeat(components.length()) { index ->
            val component = components.getJSONObject(index)
            add(
                ThirdPartyComponent(
                    group = component.getString("group"),
                    name = component.getString("name"),
                    version = component.getString("version"),
                    licenseId = component.getString("licenseId"),
                    licenseName = component.getString("licenseName"),
                    licenseTextAsset = component.getString("licenseTextAsset"),
                ),
            )
        }
    }
}

private fun readAsset(context: Context, path: String): String =
    context.assets.open(path).bufferedReader().use { it.readText() }

@Suppress("DEPRECATION")
private fun appVersion(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"

@Composable
internal fun AboutScreen(
    onBack: () -> Unit,
    onOpenUri: ((String) -> Unit)? = null,
    onReplayTour: (() -> Unit)? = null,
    backLabel: String? = null,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val openUri = onOpenUri ?: uriHandler::openUri
    val componentsResult = remember(context) { runCatching { loadThirdPartyComponents(context) } }
    val components = componentsResult.getOrDefault(emptyList())
    var expandedCoordinate by remember { mutableStateOf<String?>(null) }
    var expandedLicenseText by remember { mutableStateOf<String?>(null) }
    var appLicenseExpanded by remember { mutableStateOf(false) }
    val appLicenseText = remember(context) { runCatching { readAsset(context, APP_LICENSE_ASSET) }.getOrDefault("") }
    // A reading column: on a tablet or desktop it stays centred at a comfortable line length.
    BoxWithConstraints(Modifier.fillMaxSize().background(AboutGraphite)) {
    val gutter = max(24f, (maxWidth.value - ABOUT_MAX_WIDTH_DP) / 2f)
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("about-list"),
        contentPadding = PaddingValues(horizontal = gutter.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SettingsTopBar(stringResource(R.string.about_title), onBack, backTag = "about-back", backLabel = backLabel) }
        item {
            Column(
                modifier = Modifier.fillMaxWidth().background(AboutCard, RoundedCornerShape(12.dp)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(AboutGraphite)
                        .border(1.dp, AboutAmber.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(92.dp),
                    )
                }
                Text(stringResource(R.string.app_name), color = MaterialTheme.colorScheme.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.about_version, appVersion(context)), color = AboutCyan, fontSize = 12.sp)
                Text(stringResource(R.string.about_description), color = AboutMuted, fontSize = 12.sp)
                Text(stringResource(R.string.about_developed_by), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.about_privacy), color = AboutMuted, fontSize = 11.sp)
            }
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth().background(AboutCard, RoundedCornerShape(12.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { openUri(PROJECT_WEBSITE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.about_website), color = AboutAmber)
                }
                TextButton(onClick = { openUri(PROJECT_SOURCE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.about_source_code), color = AboutAmber)
                }
                if (onReplayTour != null) {
                    TextButton(onClick = onReplayTour, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("about-replay-tour")) {
                        Text(stringResource(R.string.onb_replay), color = AboutAmber)
                    }
                }
            }
        }
        item {
            LicenseCard(
                title = stringResource(R.string.about_app_license),
                subtitle = "Apache-2.0",
                expanded = appLicenseExpanded,
                licenseText = appLicenseText,
                onClick = { appLicenseExpanded = !appLicenseExpanded },
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.about_third_party), color = AboutAmber, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.about_third_party_count, components.size), color = AboutMuted, fontSize = 11.sp)
                if (componentsResult.isFailure) {
                    Text(stringResource(R.string.about_license_error), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
            }
        }
        items(components, key = { it.coordinate }) { component ->
            LicenseCard(
                title = component.name,
                subtitle = "${component.coordinate}\n${component.licenseName} (${component.licenseId})",
                expanded = expandedCoordinate == component.coordinate,
                licenseText = if (expandedCoordinate == component.coordinate) expandedLicenseText.orEmpty() else "",
                onClick = {
                    if (expandedCoordinate == component.coordinate) {
                        expandedCoordinate = null
                        expandedLicenseText = null
                    } else {
                        expandedCoordinate = component.coordinate
                        expandedLicenseText = runCatching { readAsset(context, component.licenseTextAsset) }.getOrDefault("")
                    }
                },
            )
        }
    }
    }
}

@Composable
private fun LicenseCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    licenseText: String,
    onClick: () -> Unit,
) {
    val actionDescription = stringResource(
        if (expanded) R.string.about_collapse_license else R.string.about_expand_license,
        title,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AboutCard)
            .clickable(onClick = onClick)
            .semantics { contentDescription = actionDescription }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The expander sits right after its label, not across the card from it.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
            Text(if (expanded) "−" else "+", color = AboutAmber, fontSize = 18.sp)
        }
        Text(subtitle, color = AboutMuted, fontSize = 10.sp)
        if (expanded) {
            Text(licenseText, color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}
