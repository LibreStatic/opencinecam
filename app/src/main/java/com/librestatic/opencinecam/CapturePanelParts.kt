/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Pieces every capture panel (EV, white balance, focus, frame rate, resolution, interval) is built
 * from. A panel fills the width its host gives it, has no card of its own and scrolls inside when
 * it is taller than the space, so the same content works in a side column and in a phone sheet.
 */

/** False when the host (the capture context pane) draws its own title row and close button. */
internal val LocalPanelHeaderVisible = compositionLocalOf { true }

/** Columns for tiles at least [minTileDp] wide across [widthDp] with [gapDp] between, capped at [maxColumns]. */
fun panelGridColumns(widthDp: Float, minTileDp: Float, maxColumns: Int, gapDp: Float = 8f): Int =
    if (!(widthDp > 0f) || minTileDp <= 0f) 1
    else ((widthDp + gapDp) / (minTileDp + gapDp)).toInt().coerceIn(1, maxColumns.coerceAtLeast(1))

/**
 * A column count up to [columns] that leaves no row of [itemCount] tiles half empty (four tiles go
 * 4 or 2 across, never 3 and 1). It gives up at half the fitting width and keeps [columns].
 */
fun balancedGridColumns(columns: Int, itemCount: Int): Int {
    val fit = columns.coerceAtLeast(1)
    return when {
        itemCount <= 0 -> fit
        itemCount <= fit -> itemCount
        else -> (fit downTo (fit + 1) / 2).firstOrNull { itemCount % it == 0 } ?: fit
    }
}

/** Panel body: fills the given width and scrolls inside. The host must bound its height and not scroll it again. */
@Composable
internal fun PanelColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
internal fun PanelHeader(title: String, onClose: () -> Unit) {
    if (!LocalPanelHeaderVisible.current) return
    val closeDescription = stringResource(R.string.close_panel, title).let {
        if (LocalAdaptiveWindow.current.hardwareKeyboard) withShortcut(it, ShortcutAction.DISMISS) else it
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(48.dp)
                .semantics { contentDescription = closeDescription }
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("×", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp) }
    }
}

/** A small caption over a group of controls ("MARKS", "CURVE"). */
@Composable
internal fun PanelSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp, modifier = modifier)
}

/** Plain-words explanation inside a panel: what a control cannot do here, or what a choice means. */
@Composable
internal fun PanelNote(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 17.sp, modifier = modifier)
}

/** Tiles laid out in as many equal columns as fit, each at least [minTileWidth] wide; a row's tiles share one height. */
@Composable
internal fun <T> ChoiceGrid(
    items: List<T>,
    minTileWidth: Dp,
    maxColumns: Int,
    modifier: Modifier = Modifier,
    balanced: Boolean = false,
    tile: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fit = panelGridColumns(maxWidth.value, minTileWidth.value, maxColumns)
        val columns = if (balanced) balancedGridColumns(fit, items.size) else fit
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { tile(it, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun ChoiceTile(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    detail: String? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val textColor = when {
        selected -> colors.onPrimary
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        else -> colors.onSurface
    }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> colors.primary
                    !enabled -> colors.surfaceContainerLow
                    else -> colors.surfaceContainerHighest
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                color = textColor,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // A second, quieter line under the name: the pixels under "4K".
            detail?.let {
                Text(it, color = textColor.copy(alpha = textColor.alpha * 0.8f), fontSize = 12.sp, lineHeight = 14.sp,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A frame rate in the FPS panel: [offered] at the current size, [highSpeed] a constrained high-speed session. */
data class FpsOption(val fps: Int, val offered: Boolean, val highSpeed: Boolean, val ispHighRate: Boolean = false)

/** The 30 fps viewfinder note only matters when a high-speed rate can be picked at this size. */
fun fpsHighSpeedNoteApplies(options: List<FpsOption>): Boolean = options.any { it.offered && it.highSpeed }
