/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.CineGlyph
import com.librestatic.opencinecam.CineIcon
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.SettingsAccent
import com.librestatic.opencinecam.SettingsBorder
import com.librestatic.opencinecam.SettingsMuted
import com.librestatic.opencinecam.SettingsSurface
import com.librestatic.opencinecam.SettingsSurfaceRaised
import com.librestatic.opencinecam.ui.theme.LocalCineColors

private val Disabled: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

/** One fact about the clip ("23.976 fps", "HEVC 10-bit") as a small outlined chip. */
@Composable
internal fun MetadataChip(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        modifier
            .clip(shape)
            .background(if (emphasized) SettingsAccent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (emphasized) SettingsAccent else SettingsBorder, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = if (emphasized) SettingsAccent else SettingsMuted,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Mutually exclusive choices as one pill split into segments, styled like SettingsPill. Each
 * segment is a radio button tagged "[tag]-index" with its label tagged "[tag]-index-label".
 */
@Composable
internal fun <T> SegmentedToggle(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, tag: String,
    modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .clip(shape)
            .background(SettingsSurfaceRaised)
            .border(1.dp, SettingsBorder, shape)
            .selectableGroup()
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 56.dp)
                    .clip(shape)
                    .background(if (on) SettingsAccent else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(value) }
                    .testTag("$tag-$index")
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                    modifier = Modifier.testTag("$tag-$index-label"))
            }
        }
    }
}

/** A filled luma histogram of [values] (0..1, any bin count) in a thin frame; no data draws only the frame. */
@Composable
internal fun HistogramThumb(values: FloatArray?, modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    val frame = SettingsBorder
    val backdrop = Color.Black.copy(alpha = .35f)
    Canvas(modifier.clip(RoundedCornerShape(4.dp))) {
        drawRect(backdrop)
        if (values != null && values.isNotEmpty()) {
            val step = size.width / (values.size - 1).coerceAtLeast(1)
            val path = Path().apply {
                moveTo(0f, size.height)
                values.forEachIndexed { i, v ->
                    val x = if (values.size == 1) 0f else i * step
                    lineTo(x, size.height * (1f - v.coerceIn(0f, 1f)))
                }
                if (values.size == 1) lineTo(size.width, size.height * (1f - values[0].coerceIn(0f, 1f)))
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path, fill)
        }
        val inset = 0.5.dp.toPx()
        drawRect(frame, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(1.dp.toPx()))
    }
}

/**
 * A recoverable failure explained in plain words, with the technical [detail] one tap away. The
 * card carries [tag], the toggle "[tag]-toggle" and the detail "[tag]-detail" (zero-height while collapsed).
 */
@Composable
internal fun CollapsibleErrorCard(title: String, message: String, detail: String?, tag: String, modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}) {
    var open by rememberSaveable(tag) { mutableStateOf(false) }
    val warning = LocalCineColors.current.pending
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SettingsSurface)
            .border(1.dp, warning.copy(alpha = 0.55f), shape)
            .padding(12.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CineGlyph(CineIcon.WARNING, warning, Modifier.padding(top = 2.dp).size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(message, color = SettingsMuted, fontSize = 13.sp)
            }
        }
        if (!detail.isNullOrBlank()) {
            val toggle = stringResource(if (open) R.string.playback_detail_hide else R.string.playback_detail_show)
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = toggle, role = Role.Button) { open = !open }
                    .semantics { contentDescription = toggle }
                    .padding(horizontal = 4.dp)
                    .testTag("$tag-toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(toggle, color = LocalCineColors.current.verified, fontSize = 13.sp)
                CineGlyph(if (open) CineIcon.COLLAPSE else CineIcon.EXPAND, LocalCineColors.current.verified, Modifier.size(16.dp))
            }
            // Collapsed, the detail stays in the semantics tree at zero height so its text remains queryable.
            if (open) SelectionContainer {
                Text(detail, Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .35f))
                    .padding(8.dp).testTag("$tag-detail"), color = SettingsMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            } else Text(detail, Modifier.fillMaxWidth().heightIn(max = 0.dp).clipToBounds().testTag("$tag-detail"), fontSize = 11.sp)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions()
        }
    }
}

/**
 * A round transport key; the [primary] one (play/pause) is larger and filled with the accent. [primarySize]
 * lets compact layouts keep play at 56 dp while large windows use 64 dp.
 */
@Composable
internal fun TransportButton(tag: String, icon: CineIcon, label: String, enabled: Boolean, primary: Boolean = false,
    modifier: Modifier = Modifier, primarySize: Dp = 64.dp, onClick: () -> Unit) {
    val glyph = when {
        !enabled -> if (primary) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f) else Disabled
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier
            .size(if (primary) primarySize.coerceAtLeast(56.dp) else 48.dp)
            .clip(CircleShape)
            .background(when {
                primary && enabled -> SettingsAccent
                primary -> SettingsAccent.copy(alpha = 0.38f)
                else -> SettingsSurfaceRaised
            })
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { CineGlyph(icon, glyph, Modifier.size(if (primary) 28.dp else 22.dp)) }
}
