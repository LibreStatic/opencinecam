/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2CapabilityInventory
import com.librestatic.opencinecam.camera.CameraInventory
import com.librestatic.opencinecam.camera.CharacteristicEntry
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Headline characteristics for the summary card, in reading order. */
private val SUMMARY_FACTS = listOf(
    R.string.caps_fact_hardware to "android.info.supportedHardwareLevel",
    R.string.caps_fact_sensor to "android.sensor.info.physicalSize",
    R.string.caps_fact_pixels to "android.sensor.info.pixelArraySize",
    R.string.caps_fact_focal to "android.lens.info.availableFocalLengths",
    R.string.caps_fact_aperture to "android.lens.info.availableApertures",
    R.string.caps_fact_capabilities to "android.request.availableCapabilities",
)

/** Values longer than this collapse behind a tap, so request-key lists do not swamp the page. */
private const val COLLAPSED_LINES = 6

/**
 * Every parameter each camera advertises, next to what OpenCineCam actually does with it: the
 * engine's negotiated descriptor drives the feature list, the raw characteristics the inventory.
 */
@Composable
internal fun CameraCapabilitiesScreen(
    cameras: List<Camera2CameraDescriptor>,
    activeCameraId: String?,
    onBack: () -> Unit,
    loadInventory: ((Context) -> List<CameraInventory>)? = null,
    liveState: CameraUiState? = null,
) {
    val context = LocalContext.current
    val inventory by produceState<List<CameraInventory>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                loadInventory?.invoke(context)
                    ?: Camera2CapabilityInventory(context.getSystemService(CameraManager::class.java)).read()
            }.getOrDefault(emptyList())
        }
    }
    var selectedId by rememberSaveable { mutableStateOf(activeCameraId) }
    var filter by rememberSaveable { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val loaded = inventory
    val selected = loaded?.firstOrNull { it.cameraId == selectedId } ?: loaded?.firstOrNull()
    val descriptor = selected?.let { inv -> cameras.firstOrNull { it.cameraId == inv.cameraId } }
    val findings = remember(descriptor) { descriptor?.let(::auditCameraCapabilities).orEmpty() }
    val copied = stringResource(R.string.caps_copied)
    val copyFailed = stringResource(R.string.caps_copy_failed)
    val clipLabel = stringResource(R.string.caps_title)

    // Same width rules as the settings list: capped and centred on tablets and desktops.
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    val gutter = settingsSideGutterDp(maxWidth.value)
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("capabilities-list"),
        contentPadding = PaddingValues(horizontal = gutter.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            val report = loaded?.takeIf { it.isNotEmpty() }
            SettingsTopBar(stringResource(R.string.caps_title), onBack, backTag = "capabilities-back", subtitle = deviceLine(), action = report?.let { list -> {
                TextButton(
                    onClick = {
                        val text = capabilityReportText(deviceLine(), list, cameras, context::getString, liveState)
                        val ok = runCatching {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText(clipLabel, text))
                        }.isSuccess
                        Toast.makeText(context, if (ok) copied else copyFailed, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("capabilities-copy"),
                ) { Text(stringResource(R.string.caps_copy), color = SettingsAccent) }
            } })
        }
        when {
            loaded == null -> item(key = "loading") { Text(stringResource(R.string.caps_loading), color = SettingsMuted) }
            loaded.isEmpty() -> item(key = "empty") { Text(stringResource(R.string.caps_empty), color = MaterialTheme.colorScheme.error) }
            selected != null -> {
                item(key = "intro") { Text(stringResource(R.string.caps_intro), color = SettingsMuted, fontSize = 13.sp) }
                item(key = "chips") {
                    SettingsChips(
                        title = stringResource(R.string.caps_camera),
                        choices = loaded.map { it.cameraId },
                        selected = selected.cameraId,
                        label = { id -> cameraChipLabel(loaded.first { it.cameraId == id }, active = id == activeCameraId) },
                        onSelect = { selectedId = it; filter = "" },
                        tag = { "capabilities-camera-$it" },
                    )
                }
                item(key = "summary-${selected.cameraId}") { CameraSummaryCard(selected) }
                item(key = "app-${selected.cameraId}") { AppUsageCard(selected, findings) }
                // Live values only belong to the camera in use; Settings shows a one-line summary of them.
                val live = liveState?.takeIf { it.selectedCameraId == selected.cameraId }
                if (live != null) item(key = "readback-${selected.cameraId}") { ReadbackCard(requestedReportedLines(live)) }
                item(key = "raw-header-${selected.cameraId}") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        SettingsHeading(stringResource(R.string.caps_section_raw, selected.entries.size), stringResource(R.string.caps_section_raw_summary))
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text(stringResource(R.string.caps_filter)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("capabilities-filter"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
                val visible = filterEntries(selected.entries, filter)
                if (visible.isEmpty()) item(key = "raw-none") { Text(stringResource(R.string.settings_no_results), color = SettingsMuted) }
                visible.groupBy { it.section }.forEach { (section, entries) ->
                    item(key = "section-$section") {
                        Text(section.uppercase(), color = SettingsAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                    items(entries, key = { "entry-${it.key}" }) { entry ->
                        val id = "${selected.cameraId}/${entry.key}"
                        CharacteristicRow(entry, expanded[id] == true) { expanded[id] = expanded[id] != true }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun CameraSummaryCard(camera: CameraInventory) {
    SettingsCard {
        SettingsHeading(
            stringResource(R.string.caps_camera_id, camera.cameraId),
            camera.logicalParentId?.let { stringResource(R.string.caps_physical_of, it) } ?: facingLabel(camera.lensFacing),
            CineIcon.CAMERA,
        )
        camera.error?.let { Text(stringResource(R.string.caps_read_error, it), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        SUMMARY_FACTS.forEach { (label, key) ->
            val value = camera.value(key) ?: return@forEach
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(label), color = SettingsMuted, fontSize = 13.sp, modifier = Modifier.weight(0.38f))
                Text(withoutApiCodes(value), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(0.62f))
            }
        }
    }
}

@Composable
private fun AppUsageCard(camera: CameraInventory, findings: List<AppFeatureFinding>) {
    SettingsCard {
        if (findings.isEmpty()) {
            SettingsHeading(stringResource(R.string.caps_section_app), icon = CineIcon.INFO)
            Text(
                camera.logicalParentId?.let { stringResource(R.string.caps_physical_usage, it) } ?: stringResource(R.string.caps_not_negotiated),
                color = SettingsMuted, fontSize = 13.sp,
            )
            return@SettingsCard
        }
        val counts = AppFeatureStatus.entries.associateWith { status -> findings.count { it.status == status } }
        SettingsHeading(
            stringResource(R.string.caps_section_app),
            stringResource(R.string.caps_summary_counts, counts.getValue(AppFeatureStatus.ACTIVE), counts.getValue(AppFeatureStatus.PARTIAL),
                counts.getValue(AppFeatureStatus.UNAVAILABLE)),
            CineIcon.CHECK,
        )
        findings.forEach { finding -> FeatureRow(finding) }
    }
}

@Composable
private fun ReadbackCard(lines: List<Pair<String, String>>) {
    SettingsCard(Modifier.testTag("capabilities-readback")) {
        SettingsHeading(stringResource(R.string.caps_section_readback), stringResource(R.string.caps_section_readback_summary), CineIcon.INFO)
        lines.forEach { (key, value) ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(key, color = SettingsMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                SelectionContainer { Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
private fun FeatureRow(finding: AppFeatureFinding) {
    val color = statusColor(finding.status)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SettingsSurfaceRaised).padding(10.dp)
            .testTag("capabilities-feature-${finding.feature.name}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(finding.feature.title), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(stringResource(statusLabel(finding.status)), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (finding.detail.isNotEmpty()) Text(finding.detail, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(stringResource(finding.feature.usage), color = SettingsMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CharacteristicRow(entry: CharacteristicEntry, expanded: Boolean, onToggle: () -> Unit) {
    val lines = entry.value.lines().size
    val collapsible = lines > COLLAPSED_LINES
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SettingsSurface)
            .then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(entry.key, color = SettingsMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        SelectionContainer {
            Text(
                entry.value,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = if (collapsible && !expanded) COLLAPSED_LINES else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (collapsible) Text(
            stringResource(if (expanded) R.string.caps_show_less else R.string.caps_show_all, lines),
            color = SettingsAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun cameraChipLabel(camera: CameraInventory, active: Boolean): String {
    val focal = camera.focalLengthsMm.firstOrNull()?.let { " · ${"%.1f".format(java.util.Locale.ROOT, it)} mm" }.orEmpty()
    val kind = camera.logicalParentId?.let { "⊂ $it" } ?: facingLabel(camera.lensFacing)
    return (if (active) "● " else "") + "${camera.cameraId} · $kind$focal"
}

@Composable
internal fun facingLabel(facing: Int?): String = stringResource(
    when (facing) {
        CameraCharacteristics.LENS_FACING_BACK -> R.string.caps_facing_back
        CameraCharacteristics.LENS_FACING_FRONT -> R.string.caps_facing_front
        else -> R.string.caps_facing_external
    },
)

@Composable
private fun statusColor(status: AppFeatureStatus): Color = when (status) {
    AppFeatureStatus.ACTIVE -> LocalCineColors.current.ok
    AppFeatureStatus.PARTIAL -> LocalCineColors.current.pending
    AppFeatureStatus.UNAVAILABLE -> SettingsMuted
}

private fun statusLabel(status: AppFeatureStatus): Int = when (status) {
    AppFeatureStatus.ACTIVE -> R.string.caps_status_active
    AppFeatureStatus.PARTIAL -> R.string.caps_status_partial
    AppFeatureStatus.UNAVAILABLE -> R.string.caps_status_unavailable
}

private fun deviceLine(): String =
    "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

/** Drops the "(n)" constant codes that the inventory keeps for the raw list. */
internal fun withoutApiCodes(value: String): String = value.replace(Regex(" \\(-?\\d+\\)"), "")

internal fun filterEntries(entries: List<CharacteristicEntry>, query: String): List<CharacteristicEntry> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty()) return entries
    return entries.filter { entry ->
        val haystack = "${entry.key}\n${entry.value}".lowercase()
        words.all { it in haystack }
    }
}

/** Plain-text report for bug reports and comparisons between phones. */
internal fun capabilityReportText(
    device: String,
    inventory: List<CameraInventory>,
    cameras: List<Camera2CameraDescriptor>,
    label: (Int) -> String,
    live: CameraUiState? = null,
): String = buildString {
    appendLine("OpenCineCam · ${label(R.string.caps_title)}")
    appendLine(device)
    inventory.forEach { camera ->
        appendLine()
        append("== camera ${camera.cameraId}")
        camera.logicalParentId?.let { append(" ⊂ $it") }
        appendLine(" ==")
        camera.error?.let { appendLine("! $it") }
        cameras.firstOrNull { it.cameraId == camera.cameraId }?.let(::auditCameraCapabilities)?.forEach { finding ->
            append("[${label(statusLabel(finding.status))}] ${label(finding.feature.title)}")
            if (finding.detail.isNotEmpty()) append(": ${finding.detail}")
            appendLine()
        }
        live?.takeIf { it.selectedCameraId == camera.cameraId }?.let { state ->
            appendLine("-- ${label(R.string.caps_section_readback)} --")
            requestedReportedLines(state).forEach { (key, value) -> appendLine("$key: $value") }
        }
        camera.entries.forEach { entry ->
            appendLine("${entry.key} = ${entry.value.replace("\n", "\n    ")}")
        }
    }
}
