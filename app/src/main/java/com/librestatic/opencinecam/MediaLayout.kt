/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

/**
 * Grid columns for the gallery. The classes describe the whole gallery area; [gridWidthDp] is what
 * is left for the grid once a side inspector has taken its share. A phone in landscape (short
 * height) keeps three columns so a card still shows its picture and name together.
 */
internal fun galleryColumns(gridWidthDp: Float, widthClass: WindowWidthClass, heightClass: WindowHeightClass): Int = when {
    widthClass == WindowWidthClass.COMPACT -> if (gridWidthDp < 280f) 1 else 2
    heightClass == WindowHeightClass.COMPACT -> 3
    widthClass == WindowWidthClass.MEDIUM -> 3
    widthClass == WindowWidthClass.EXPANDED -> (gridWidthDp / 200f).toInt().coerceIn(3, 4)
    else -> (gridWidthDp / 220f).toInt().coerceIn(4, 6)
}

/**
 * List-detail: a large window, or an expanded one in landscape that is not phone-short, keeps the
 * take inspector open beside the grid. Everywhere else a tap plays and details open on demand.
 */
internal fun mediaInspectorSide(widthClass: WindowWidthClass, heightClass: WindowHeightClass, landscape: Boolean): Boolean =
    widthClass == WindowWidthClass.LARGE ||
        widthClass == WindowWidthClass.EXPANDED && landscape && heightClass != WindowHeightClass.COMPACT

/** The inspector takes about two fifths of the area, between 360 and 480 dp. */
internal fun mediaInspectorWidthDp(areaWidthDp: Float): Float = (areaWidthDp * 0.42f).coerceIn(360f, 480f)

/** Take details open as a bottom sheet in a compact portrait area and as a side sheet otherwise. */
internal fun mediaDetailsAsBottomSheet(widthClass: WindowWidthClass, landscape: Boolean): Boolean =
    widthClass == WindowWidthClass.COMPACT && !landscape

/** Dialogs with a long form (share) open as a side sheet once the window is wide. */
internal fun mediaDialogAtSide(widthClass: WindowWidthClass): Boolean =
    widthClass == WindowWidthClass.EXPANDED || widthClass == WindowWidthClass.LARGE
