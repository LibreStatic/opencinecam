/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** Width of the categories column when Settings shows two panes. */
internal const val SETTINGS_CATEGORIES_WIDTH_DP = 264f
/** A tablet in portrait: one column of cards, wide enough for side-by-side rows, never edge to edge. */
internal const val SETTINGS_MEDIUM_MAX_WIDTH_DP = 720f
/** Desktop and tablet landscape: beyond this the eye loses the row between label and control. */
internal const val SETTINGS_WIDE_MAX_WIDTH_DP = 1100f
internal const val SETTINGS_CONTROL_MIN_DP = 260f
internal const val SETTINGS_CONTROL_MAX_DP = 360f
internal const val SETTINGS_DESCRIPTION_MIN_DP = 240f
internal const val SETTINGS_DESCRIPTION_MAX_DP = 560f
internal const val SETTINGS_SLIDER_MAX_DP = 480f
internal const val SETTINGS_ROW_GAP_DP = 24f
internal const val SETTINGS_GUTTER_DP = 16f
internal const val SETTINGS_CARD_PADDING_DP = 16f
private const val SETTINGS_TWO_PANE_MAX_FONT_SCALE = 1.3f

/**
 * Categories beside the page from the expanded class up, measured on the hub's own width (the
 * hinge-safe pane, not the window). Very large text keeps one pane so labels are not squeezed.
 */
internal fun settingsTwoPane(widthDp: Float, fontScale: Float): Boolean =
    windowWidthClass(widthDp) >= WindowWidthClass.EXPANDED && fontScale <= SETTINGS_TWO_PANE_MAX_FONT_SCALE

/** The widest the column of cards may get inside a pane [paneWidthDp] wide; compact panes use all of it. */
internal fun settingsContentMaxWidthDp(paneWidthDp: Float): Float = when (windowWidthClass(paneWidthDp)) {
    WindowWidthClass.COMPACT -> Float.POSITIVE_INFINITY
    WindowWidthClass.MEDIUM -> SETTINGS_MEDIUM_MAX_WIDTH_DP
    WindowWidthClass.EXPANDED, WindowWidthClass.LARGE -> SETTINGS_WIDE_MAX_WIDTH_DP
}

/** Side padding that centres the capped column; never less than the normal gutter. */
internal fun settingsSideGutterDp(paneWidthDp: Float, maxContentDp: Float = settingsContentMaxWidthDp(paneWidthDp)): Float =
    max(SETTINGS_GUTTER_DP, (paneWidthDp - maxContentDp) / 2f)

/** Width left for a row inside a card once the gutters and the card's own padding are taken out. */
internal fun settingsRowWidthDp(paneWidthDp: Float): Float =
    paneWidthDp - 2 * settingsSideGutterDp(paneWidthDp) - 2 * SETTINGS_CARD_PADDING_DP

/**
 * How a setting row lays out: label and description on the left with the control in a fixed
 * column on the right, or stacked when the row cannot hold both at a readable width.
 */
internal data class SettingsRowLayout(val sideBySide: Boolean, val controlWidthDp: Float = 0f)

internal fun settingsRowLayout(rowWidthDp: Float): SettingsRowLayout {
    val control = (rowWidthDp * 0.4f).coerceIn(SETTINGS_CONTROL_MIN_DP, SETTINGS_CONTROL_MAX_DP)
    return if (rowWidthDp - control - SETTINGS_ROW_GAP_DP >= SETTINGS_DESCRIPTION_MIN_DP) SettingsRowLayout(true, control)
    else SettingsRowLayout(false)
}

/** One column on a phone and beside the page; two columns of tiles on a tablet in portrait. */
internal fun settingsCategoryColumns(hubWidthDp: Float, twoPane: Boolean): Int =
    if (!twoPane && windowWidthClass(hubWidthDp) == WindowWidthClass.MEDIUM) 2 else 1

/**
 * What the categories list marks. [open] is the page on screen; while searching there is none,
 * and [matches] counts the results each category holds, so the list never points at a page the
 * results are not from.
 */
internal data class SettingsCategoryMarks(val open: SettingsCategory?, val matches: Map<SettingsCategory, Int>)

internal fun settingsCategoryMarks(selected: SettingsCategory?, query: String, results: Set<String>, twoPane: Boolean): SettingsCategoryMarks {
    if (query.isNotBlank()) {
        val owner = SettingsCatalog.entries.associate { it.id to it.category }
        return SettingsCategoryMarks(null, results.mapNotNull(owner::get).groupingBy { it }.eachCount())
    }
    return SettingsCategoryMarks(selected ?: SettingsCategory.CAPTURE.takeIf { twoPane }, emptyMap())
}

/** Provided by the settings list; dialogs and screens outside it keep the stacked layout. */
internal val LocalSettingsRowLayout = staticCompositionLocalOf { SettingsRowLayout(false) }

/**
 * A setting as one row: [label] (title and description) on the left, [control] in the fixed
 * control column on the right, so every control in the list starts and ends on the same edges.
 * Stacked rows put the control under the label.
 */
@Composable
internal fun SettingsRow(
    modifier: Modifier = Modifier,
    controlAlignment: Alignment.Horizontal = Alignment.End,
    label: @Composable () -> Unit,
    control: @Composable () -> Unit,
) {
    val layout = LocalSettingsRowLayout.current
    if (layout.sideBySide) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SETTINGS_ROW_GAP_DP.dp)) {
            Box(Modifier.weight(1f)) { Box(Modifier.widthIn(max = SETTINGS_DESCRIPTION_MAX_DP.dp)) { label() } }
            Column(Modifier.width(layout.controlWidthDp.dp), horizontalAlignment = controlAlignment) { control() }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            label()
            control()
        }
    }
}
