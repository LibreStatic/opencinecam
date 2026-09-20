/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    Column(Modifier.fillMaxSize().background(Color(0xFF101417))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings_tab), modifier = Modifier.weight(1f).padding(vertical = 16.dp), fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
            if (category != null || query.isNotEmpty()) TextButton(onClick = { query = ""; selectedName = null }) {
                Text(stringResource(R.string.settings_categories))
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.settings_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("settings-search"),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
        )
        if (state.settingsPending) Text(
            stringResource(R.string.settings_recording_pending), color = Color(0xFFF4BA55), fontSize = 14.sp,
            modifier = Modifier.padding(16.dp),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 840.dp && LocalDensity.current.fontScale <= 1.3f
            Row(Modifier.fillMaxSize()) {
                if (wide || (category == null && query.isBlank())) {
                    LazyColumn(
                        modifier = if (wide) Modifier.width(260.dp).fillMaxHeight().testTag("settings-categories") else Modifier.fillMaxSize().testTag("settings-categories"),
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(SettingsCategory.entries, key = { it.name }) { item ->
                            Column(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .background(if (item == category) Color(0xFF39434A) else Color(0xFF1A1F21), RoundedCornerShape(8.dp))
                                .clickable { selectedName = item.name; query = "" }
                                .testTag("settings-category-${item.name}").padding(16.dp)) {
                                Text(stringResource(item.title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (wide || category != null || query.isNotBlank()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (query.isNotBlank()) stringResource(R.string.settings_results) else stringResource((category ?: SettingsCategory.CAPTURE).title),
                            color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(16.dp),
                        )
                        val visible = if (wide && category == null && query.isBlank()) SettingsCatalog.search("", SettingsCategory.CAPTURE, context::getString) else ids
                        if (visible.isEmpty()) Text(stringResource(R.string.settings_no_results), color = Color.White, modifier = Modifier.padding(16.dp))
                        listStates.SaveableStateProvider(if (query.isNotBlank()) "search" else (category ?: SettingsCategory.CAPTURE).name) {
                            SettingsContent(state, settings, audioPermissionGranted, onRequestAudioPermission, onOpenAbout, onSettingsChange, visible, onApplyPreset)
                        }
                    }
                }
            }
        }
    }
    }
}
