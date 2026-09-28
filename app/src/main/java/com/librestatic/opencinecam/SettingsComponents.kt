/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val SettingsSurface = Color(0xFF12171A)
internal val SettingsSurfaceRaised = Color(0xFF1B2226)
internal val SettingsBorder = Color(0xFF263036)
internal val SettingsAccent = Color(0xFFFFB000)
internal val SettingsMuted = Color(0xFFAAB4BA)

/**
 * One settings card in the adaptive grid. Small sections share a row with a neighbour on wide
 * panes; [fullLine] sections (long forms, libraries, lists) always take the whole row.
 */
internal fun LazyStaggeredGridScope.settingsCard(key: String, fullLine: Boolean = false, content: @Composable () -> Unit) {
    item(key = key, span = if (fullLine) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane) {
        SettingsCard(content = content)
    }
}

@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SettingsSurface)
            .border(1.dp, SettingsBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/** A card or group heading: optional symbol, title and a one-line description. */
@Composable
internal fun SettingsHeading(title: String, description: String? = null, icon: CineIcon? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon?.let { CineGlyph(it, SettingsAccent, Modifier.padding(end = 10.dp).size(20.dp)) }
        Column {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            description?.let { Text(it, color = SettingsMuted, fontSize = 13.sp) }
        }
    }
}

/**
 * Mutually exclusive choices as one row of pills that wraps only when the pane is too narrow,
 * instead of a column of full-width buttons.
 */
@Composable
internal fun <T> SettingsChips(
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tag: ((T) -> String)? = null,
    enabled: (T) -> Boolean = { true },
) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            val on = choice == selected
            val available = enabled(choice)
            androidx.compose.foundation.layout.Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 56.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (on) SettingsAccent else SettingsSurfaceRaised)
                    .border(1.dp, if (on) SettingsAccent else SettingsBorder, RoundedCornerShape(24.dp))
                    .selectable(selected = on, enabled = available, role = Role.RadioButton) { onSelect(choice) }
                    .then(tag?.let { Modifier.testTag(it(choice)) } ?: Modifier)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(choice),
                    color = when { !available -> Color(0xFF69747A); on -> Color.Black; else -> Color.White },
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(IntrinsicSize.Max).then(tag?.let { Modifier.testTag(it(choice) + "-label") } ?: Modifier),
                )
            }
        }
    }
}

/**
 * One choice or action as a pill sized to its label. Callers put a group of them in a FlowRow, so
 * a set of short options reads as one row instead of a stack of full-width buttons. The pill
 * carries [tag] and its label carries "[tag]-label".
 */
@Composable
internal fun SettingsPill(label: String, tag: String, selected: Boolean = false, enabled: Boolean = true,
    role: Role = Role.RadioButton, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 56.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) SettingsAccent else SettingsSurfaceRaised)
            .border(1.dp, if (selected) SettingsAccent else SettingsBorder, RoundedCornerShape(24.dp))
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = when { !enabled -> Color(0xFF69747A); selected -> Color.Black; else -> Color.White },
            fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(IntrinsicSize.Max).testTag("$tag-label"))
    }
}

@Composable
internal fun SettingsPillRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/**
 * A labelled on/off setting: the label takes the row and the switch sits at its end, instead of
 * a switch stacked under its label. The switch carries [tag]; the label carries [labelTag].
 */
@Composable
internal fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    labelTag: String? = null,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp).then(labelTag?.let { Modifier.testTag(it) } ?: Modifier),
            color = if (enabled) Color.White else SettingsMuted)
        androidx.compose.material3.Switch(checked, onCheckedChange, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag(tag).semantics { contentDescription = label })
    }
}

/** Colour choices as a row of swatches; the selected one carries a ring and a check, not only colour. */
@Composable
internal fun <T> SettingsSwatches(
    choices: List<T>,
    selected: T,
    color: (T) -> Color,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    tag: ((T) -> String)? = null,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            val on = choice == selected
            val name = label(choice)
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(if (on) 3.dp else 1.dp, if (on) Color.White else SettingsBorder, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(color(choice))
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(choice) }
                    .semantics { contentDescription = name }
                    .then(tag?.let { Modifier.testTag(it(choice)) } ?: Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (on) CineGlyph(CineIcon.CHECK, Color.Black, Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Long explanations stay one tap away instead of pushing the controls down the page. With a
 * [tag], the toggle carries "[tag]-toggle" and the expanded explanation carries [tag].
 */
@Composable
internal fun SettingsHelp(text: String, modifier: Modifier = Modifier, tag: String? = null) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            stringResource(if (open) R.string.settings_help_hide else R.string.settings_help_show),
            color = Color(0xFF5BD6E5),
            fontSize = 13.sp,
            modifier = Modifier.heightIn(min = 40.dp).clickable { open = !open }.padding(vertical = 10.dp)
                .then(tag?.let { Modifier.testTag("$it-toggle") } ?: Modifier),
        )
        if (open) Text(text, color = SettingsMuted, fontSize = 13.sp, modifier = tag?.let { Modifier.testTag(it) } ?: Modifier)
    }
}
