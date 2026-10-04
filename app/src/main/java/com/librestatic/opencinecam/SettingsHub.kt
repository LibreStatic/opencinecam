/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
    audioPermissionBlocked: Boolean = false,
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
    val searching = query.isNotBlank()
    BackHandler(enabled = category != null || query.isNotEmpty()) {
        if (query.isNotEmpty()) query = "" else selectedName = null
    }
    val open: (SettingsCategory) -> Unit = { selectedName = it.name; query = "" }
    HingeSafeSettingsPane(fold.hinge) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    val hubWidth = maxWidth.value
    val twoPane = settingsTwoPane(hubWidth, LocalDensity.current.fontScale)
    val marks = settingsCategoryMarks(category, query, ids, twoPane)
    // Inside a page, the search names the page it searches, so one row carries both.
    val inPage = !twoPane && (category != null || query.isNotEmpty())
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
    val pending: @Composable () -> Unit = {
        if (state.settingsPending) Text(stringResource(R.string.settings_recording_pending), color = LocalCineColors.current.pending, fontSize = 14.sp,
            modifier = Modifier.padding(16.dp))
    }
    val page: @Composable (Modifier) -> Unit = { modifier ->
        // The page title and the empty notice share the cards' gutter so their left edges line up.
        BoxWithConstraints(modifier) {
            val gutter = settingsSideGutterDp(maxWidth.value).dp
            Column(Modifier.fillMaxSize()) {
                if (twoPane) Text(
                    if (searching) stringResource(R.string.settings_results) else stringResource((category ?: SettingsCategory.CAPTURE).title),
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = gutter, end = gutter, top = 20.dp, bottom = 4.dp),
                )
                val visible = if (twoPane && category == null && !searching) SettingsCatalog.search("", SettingsCategory.CAPTURE, context::getString) else ids
                if (visible.isEmpty()) Text(stringResource(R.string.settings_no_results), color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = gutter, vertical = 16.dp))
                listStates.SaveableStateProvider(if (searching) "search" else (category ?: SettingsCategory.CAPTURE).name) {
                    SettingsContent(state, settings, audioPermissionGranted, onRequestAudioPermission, onOpenAbout, onSettingsChange, visible, onApplyPreset, onOpenCapabilities, audioPermissionBlocked = audioPermissionBlocked)
                }
            }
        }
    }
    if (twoPane) {
        // The search sits over the categories it filters; the page owns the rest of the width.
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(SETTINGS_CATEGORIES_WIDTH_DP.dp).fillMaxHeight()) {
                SettingsHubTitle(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
                search(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                pending()
                SettingsCategoryList(marks, searching, columns = 1, onOpen = open, modifier = Modifier.weight(1f).fillMaxWidth())
            }
            page(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        val cap = settingsContentMaxWidthDp(hubWidth)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.CenterHorizontally).settingsCap(cap).fillMaxWidth()) {
                if (!inPage) SettingsHubTitle(Modifier.padding(16.dp))
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
                pending()
            }
            if (inPage) page(Modifier.weight(1f).fillMaxWidth())
            else SettingsCategoryList(marks, searching, columns = settingsCategoryColumns(hubWidth, twoPane = false), onOpen = open,
                modifier = Modifier.weight(1f).align(Alignment.CenterHorizontally).settingsCap(cap).fillMaxWidth())
        }
    }
    }
    }
}

@Composable
private fun SettingsHubTitle(modifier: Modifier) {
    Text(stringResource(R.string.settings_tab), modifier = modifier, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
}

/** Caps a column at [maxDp]; compact panes pass infinity and keep their full width. */
internal fun Modifier.settingsCap(maxDp: Float): Modifier = if (maxDp.isFinite()) widthIn(max = maxDp.dp) else this

/**
 * The categories as rail rows (one column) or as tiles (two columns on a tablet in portrait, so the
 * list fills the width instead of leaving most of it empty).
 */
@Composable
private fun SettingsCategoryList(marks: SettingsCategoryMarks, searching: Boolean, columns: Int, onOpen: (SettingsCategory) -> Unit, modifier: Modifier = Modifier) {
    val tiles = columns > 1
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.testTag("settings-categories"),
        contentPadding = PaddingValues(horizontal = if (tiles) 16.dp else 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (tiles) 12.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(SettingsCategory.entries, key = { it.name }) { item ->
            SettingsCategoryItem(item, open = item == marks.open, matches = marks.matches[item] ?: 0, searching = searching, tile = tiles) { onOpen(item) }
        }
    }
}

/**
 * A rail row, not a card: title plus what lives inside, with an amber edge marking the open page.
 * While searching no page is open; categories holding results show how many, the rest fade.
 * The summary wraps instead of being cut, so the list says what each page holds in every language.
 */
@Composable
private fun SettingsCategoryItem(item: SettingsCategory, open: Boolean, matches: Int, searching: Boolean, tile: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val faded = searching && matches == 0
    val background = when {
        open -> colors.surfaceContainerHigh
        tile -> colors.surfaceContainer
        else -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().heightIn(min = if (tile) 72.dp else 56.dp)
        .clip(RoundedCornerShape(if (tile) 12.dp else 8.dp))
        .background(background)
        .clickable(onClick = onClick)
        .semantics { selected = open }
        .testTag("settings-category-${item.name}"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(32.dp).background(if (open) colors.primary else Color.Transparent))
        CineGlyph(item.icon, when {
            open || matches > 0 -> colors.primary
            faded -> colors.onSurfaceVariant.copy(alpha = 0.5f)
            else -> colors.onSurfaceVariant
        }, Modifier.padding(start = 12.dp).size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(stringResource(item.title), color = if (faded) colors.onSurface.copy(alpha = 0.5f) else colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(item.summary), color = if (faded) colors.onSurfaceVariant.copy(alpha = 0.5f) else colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (matches > 0) {
            val description = pluralStringResource(R.plurals.settings_category_matches, matches, matches)
            Text(matches.toString(), color = colors.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 12.dp).clip(RoundedCornerShape(10.dp)).background(colors.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp).semantics { contentDescription = description }
                    .testTag("settings-category-${item.name}-matches"))
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
