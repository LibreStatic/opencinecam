/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsScreen(
    state: CameraUiState,
    settings: CameraSettings,
    audioPermissionGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
    onOpenAbout: () -> Unit,
    onSettingsChange: (CameraSettings) -> Unit,
    onApplyPreset: ((CameraPreset) -> Unit)? = null,
    onOpenCapabilities: (() -> Unit)? = null,
) {
    val foldCoordinator = LocalFoldDisplayCoordinator.current
    val foldFallback = remember { kotlinx.coroutines.flow.MutableStateFlow(FoldDisplayState()) }
    val fold by (foldCoordinator?.states ?: foldFallback).collectAsState()
    val listStates = rememberSaveableStateHolder()
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val category = selectedName?.let { SettingsCategory.valueOf(it) }
    val context = LocalContext.current
    val ids = SettingsCatalog.search(query, if (query.isBlank()) category else null, context::getString)
    BackHandler(enabled = category != null || query.isNotEmpty()) {
        if (query.isNotEmpty()) query = "" else selectedName = null
    }
    HingeSafeSettingsPane(fold.hinge) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    val wide = maxWidth >= 840.dp && LocalDensity.current.fontScale <= 1.3f
    Column(Modifier.fillMaxSize()) {
        // Inside a page, the search names the page it searches, so one row carries both.
        val inPage = !wide && (category != null || query.isNotEmpty())
        val searchLabel = if (inPage && query.isEmpty() && category != null)
            stringResource(R.string.settings_search_in, stringResource(category.title)) else stringResource(R.string.settings_search)
        val search: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(searchLabel, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = modifier.testTag("settings-search"),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface),
            )
        }
        if (wide) {
            // Wide panes put the title and the search on one line instead of spending two rows on them.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.settings_tab), modifier = Modifier.padding(vertical = 16.dp),
                    fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                search(Modifier.weight(1f).widthIn(max = 520.dp))
            }
        } else {
            if (!inPage) Text(stringResource(R.string.settings_tab), modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            // The field keeps its place in the tree when a page opens, so typing never loses focus.
            Row(Modifier.fillMaxWidth().padding(start = if (inPage) 4.dp else 16.dp, end = 16.dp, top = if (inPage) 8.dp else 0.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (inPage) {
                    val back = stringResource(R.string.settings_categories)
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = back) { query = ""; selectedName = null }
                        .semantics { contentDescription = back }
                        .testTag("settings-back"), contentAlignment = Alignment.Center) {
                        CineGlyph(CineIcon.BACK, MaterialTheme.colorScheme.onSurface, Modifier.size(22.dp))
                    }
                }
                search(Modifier.weight(1f))
            }
        }
        if (state.settingsPending) Text(
            stringResource(R.string.settings_recording_pending), color = LocalCineColors.current.pending, fontSize = 14.sp,
            modifier = Modifier.padding(16.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize()) {
                if (wide || (category == null && query.isBlank())) {
                    LazyColumn(
                        modifier = if (wide) Modifier.width(272.dp).fillMaxHeight().testTag("settings-categories") else Modifier.fillMaxSize().testTag("settings-categories"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(SettingsCategory.entries, key = { it.name }) { item ->
                            val selected = item == category || (wide && category == null && query.isBlank() && item == SettingsCategory.CAPTURE)
                            // A rail row, not a card: title plus what lives inside, with an amber edge
                            // marking the open page, so the list reads at a glance and costs little width.
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                                .clickable { selectedName = item.name; query = "" }
                                .testTag("settings-category-${item.name}"),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(3.dp).height(32.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent))
                                CineGlyph(item.icon, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    Modifier.padding(start = 12.dp).size(22.dp))
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(stringResource(item.title), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(item.summary), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (wide || category != null || query.isNotBlank()) {
                    Column(Modifier.weight(1f)) {
                        // Compact panes already name the page in the search row.
                        if (wide) Text(
                            if (query.isNotBlank()) stringResource(R.string.settings_results) else stringResource((category ?: SettingsCategory.CAPTURE).title),
                            color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, modifier = Modifier.padding(16.dp),
                        )
                        val visible = if (wide && category == null && query.isBlank()) SettingsCatalog.search("", SettingsCategory.CAPTURE, context::getString) else ids
                        if (visible.isEmpty()) Text(stringResource(R.string.settings_no_results), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(16.dp))
                        listStates.SaveableStateProvider(if (query.isNotBlank()) "search" else (category ?: SettingsCategory.CAPTURE).name) {
                            SettingsContent(state, settings, audioPermissionGranted, onRequestAudioPermission, onOpenAbout, onSettingsChange, visible, onApplyPreset, onOpenCapabilities)
                        }
                    }
                }
            }
        }
    }
    }
    }
}

/** The rail symbol for each category, so the list can be scanned by shape as well as by name. */
private val SettingsCategory.icon: CineIcon
    get() = when (this) {
        SettingsCategory.CAPTURE -> CineIcon.CAMERA
        SettingsCategory.RECORDING -> CineIcon.VIDEO
        SettingsCategory.MONITORING -> CineIcon.MONITORING
        SettingsCategory.AUDIO -> CineIcon.AUDIO
        SettingsCategory.MEDIA -> CineIcon.MEDIA
        SettingsCategory.CONTROLS -> CineIcon.CONTROLS
        SettingsCategory.DISPLAYS -> CineIcon.DISPLAYS
        SettingsCategory.TRANSFERS -> CineIcon.CLOUD
        SettingsCategory.DIAGNOSTICS -> CineIcon.INFO
    }
