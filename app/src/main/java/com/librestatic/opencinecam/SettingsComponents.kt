/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.runtime.ReadOnlyComposable
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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

internal val SettingsSurface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
internal val SettingsSurfaceRaised: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh
internal val SettingsBorder: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
internal val SettingsAccent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
internal val SettingsMuted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

/**
 * One settings card in the list. Every card takes the full width of the capped column, so cards
 * share their edges and the switches and value fields inside them line up.
 */
internal fun LazyStaggeredGridScope.settingsCard(key: String, content: @Composable () -> Unit) {
    item(key = key, span = StaggeredGridItemSpan.FullLine) {
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

/**
 * A card or group heading: optional symbol, title and a one-line description. A [help] text puts
 * one info button at the end of the heading instead of a "Show help" line under every control;
 * with a [helpTag] the button carries "[helpTag]-toggle" and the opened text carries [helpTag].
 */
@Composable
internal fun SettingsHeading(title: String, description: String? = null, icon: CineIcon? = null, help: String? = null, helpTag: String? = null) {
    var open by rememberSaveable(help) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (help != null) 48.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { CineGlyph(it, SettingsAccent, Modifier.padding(end = 10.dp).size(20.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                description?.let { Text(it, color = SettingsMuted, fontSize = 13.sp) }
            }
            if (help != null) SettingsHelpButton(open, helpTag) { open = !open }
        }
        if (open && help != null) SettingsHelpText(help, helpTag)
    }
}

/**
 * The title of a settings section, one style everywhere, with its explanation behind an info
 * button at the end of the line. Tags follow [SettingsHeading].
 */
@Composable
internal fun SettingsSectionTitle(title: String, help: String? = null, helpTag: String? = null, modifier: Modifier = Modifier) {
    var open by rememberSaveable(help) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (help != null) SettingsHelpButton(open, helpTag) { open = !open }
        }
        if (open && help != null) SettingsHelpText(help, helpTag)
    }
}

/**
 * Mutually exclusive choices as one full-width tappable row showing the current value; tapping it
 * opens a single-choice dialog. Unlike a row of pills, this never wraps or stacks on narrow panes
 * such as a flip phone's cover screen. The row carries [rowTag], or else the first choice's [tag]
 * with its last "-segment" replaced by "-row" (e.g. "appearance-row"); each
 * dialog option carries its [tag] and its label "[tag]-label".
 */
@Composable
internal fun <T> SettingsChips(
    title: String,
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tag: ((T) -> String)? = null,
    enabled: (T) -> Boolean = { true },
    rowEnabled: Boolean = true,
    rowTag: String? = null,
) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val labels = choices.map { label(it) }
    val current = choices.indexOf(selected).takeIf { it >= 0 }?.let { labels[it] }
    SettingsValueRow(
        title = title,
        value = current,
        enabled = rowEnabled,
        modifier = modifier.then(
            (rowTag ?: tag?.takeIf { choices.isNotEmpty() }?.let { t -> t(choices.first()).substringBeforeLast('-') + "-row" })
                ?.let { Modifier.testTag(it) } ?: Modifier,
        ),
    ) { open = true }
    if (open) SettingsChoiceDialog(
        title = title,
        labels = labels,
        selected = choices.indexOf(selected),
        tags = tag?.let { t -> choices.map(t) },
        enabled = choices.map(enabled),
        onDismiss = { open = false },
    ) { index -> open = false; onSelect(choices[index]) }
}

/**
 * A Material 3 Expressive list row: title, current value as supporting text, and a chevron. The
 * whole row is the touch target (≥ 56 dp), so it reads the same on a cover screen and a tablet.
 */
@Composable
internal fun SettingsValueRow(
    title: String,
    value: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val layout = LocalSettingsRowLayout.current
    if (layout.sideBySide) {
        // Wide rows: the title on the left and the value as a field in the control column, the
        // whole row still one touch target.
        Row(
            modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SETTINGS_ROW_GAP_DP.dp),
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f))
            Row(Modifier.width(layout.controlWidthDp.dp).heightIn(min = 48.dp)
                .border(1.dp, SettingsBorder, MaterialTheme.shapes.small).padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(value.orEmpty(), Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f))
                CineGlyph(CineIcon.CHEVRON, if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f), Modifier.size(20.dp))
            }
        }
        return
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.large)
            .background(SettingsSurfaceRaised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f))
            value?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f))
            }
        }
        CineGlyph(CineIcon.CHEVRON, if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f), Modifier.size(20.dp))
    }
}

/** Single-choice dialog with radio rows, matching the ugallery settings pattern. */
@Composable
internal fun SettingsChoiceDialog(
    title: String,
    labels: List<String>,
    selected: Int,
    tags: List<String>?,
    enabled: List<Boolean> = labels.map { true },
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                labels.forEachIndexed { index, text ->
                    val available = enabled.getOrElse(index) { true }
                    val tag = tags?.getOrNull(index)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clip(MaterialTheme.shapes.large)
                            .selectable(selected = index == selected, enabled = available, role = Role.RadioButton) { onSelect(index) }
                            .then(tag?.let { Modifier.testTag(it) } ?: Modifier)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = index == selected, onClick = null, enabled = available)
                        Text(text, style = MaterialTheme.typography.bodyLarge,
                            color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.weight(1f).padding(start = 16.dp).then(tag?.let { Modifier.testTag("$it-label") } ?: Modifier))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * One choice or action as a pill sized to its label. Callers put a group of them in a FlowRow, so
 * a set of short options reads as one row instead of a stack of full-width buttons. The pill
 * carries [tag] and its label carries "[tag]-label". An [icon] leads the label and tightens the
 * side padding, so a row of iconned pills stays about as wide as the plain ones.
 */
@Composable
internal fun SettingsPill(label: String, tag: String, selected: Boolean = false, enabled: Boolean = true,
    role: Role = Role.RadioButton, icon: CineIcon? = null, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 56.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) SettingsAccent else SettingsSurfaceRaised)
            .border(1.dp, if (selected) SettingsAccent else SettingsBorder, RoundedCornerShape(24.dp))
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = if (icon == null) 16.dp else 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val content = when { !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f); selected -> MaterialTheme.colorScheme.onPrimary; else -> MaterialTheme.colorScheme.onSurface }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) CineGlyph(icon, content, Modifier.size(18.dp))
            Text(label, color = content, maxLines = 1,
                fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.width(IntrinsicSize.Max).testTag("$tag-label"))
        }
    }
}

@Composable
internal fun SettingsPillRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/**
 * A labelled on/off setting: the label takes the row and the switch sits at its end, instead of
 * a switch stacked under its label. Every card is the same width, so the switches of a page line
 * up. The switch carries [tag]; the label carries [labelTag].
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
        Text(label, Modifier.weight(1f).padding(end = 16.dp).then(labelTag?.let { Modifier.testTag(it) } ?: Modifier),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else SettingsMuted)
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
                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else SettingsBorder, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(color(choice))
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(choice) }
                    .semantics { contentDescription = name }
                    .then(tag?.let { Modifier.testTag(it(choice)) } ?: Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (on) CineGlyph(CineIcon.CHECK, MaterialTheme.colorScheme.onPrimary, Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Help for one control that has no heading of its own: a small info button rather than a
 * repeated "Show help" line, the explanation under it once opened. With a [tag], the button
 * carries "[tag]-toggle" and the expanded explanation carries [tag]. Section-wide help belongs on
 * [SettingsSectionTitle] or [SettingsHeading].
 */
@Composable
internal fun SettingsHelp(text: String, modifier: Modifier = Modifier, tag: String? = null) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier) {
        // The glyph lines up with the text edge; the touch target spills into the card padding.
        SettingsHelpButton(open, tag, Modifier.offset(x = (-14).dp)) { open = !open }
        if (open) SettingsHelpText(text, tag)
    }
}

/** The info button that opens or closes a help text; named for what a tap does next. */
@Composable
internal fun SettingsHelpButton(open: Boolean, tag: String?, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val label = stringResource(if (open) R.string.settings_help_hide else R.string.settings_help_show)
    androidx.compose.foundation.layout.Box(
        modifier.size(48.dp).clip(CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = label }
            .then(tag?.let { Modifier.testTag("$it-toggle") } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        CineGlyph(CineIcon.INFO, if (open) LocalCineColors.current.verified else SettingsMuted, Modifier.size(20.dp))
    }
}

@Composable
internal fun SettingsHelpText(text: String, tag: String?) {
    Text(text, color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 4.dp).then(tag?.let { Modifier.testTag(it) } ?: Modifier))
}

/** One entry of a [SettingsActionsDialog]. */
internal data class SettingsAction(val label: String, val tag: String? = null, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The actions of one list item (a preset, a LUT) as a dialog of full-width rows, instead of a
 * wrapping row of buttons under the item. Picking an action dismisses the dialog first. Each row
 * carries its action's tag and its label "[tag]-label".
 */
@Composable
internal fun SettingsActionsDialog(title: String, actions: List<SettingsAction>, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                actions.forEach { action ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clip(MaterialTheme.shapes.large)
                            .clickable(enabled = action.enabled, role = Role.Button) { onDismiss(); action.onClick() }
                            .then(action.tag?.let { Modifier.testTag(it) } ?: Modifier)
                            .padding(horizontal = 8.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            action.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (action.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.weight(1f).then(action.tag?.let { Modifier.testTag("$it-label") } ?: Modifier),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * A labelled slider: the label on the left and the slider in the control column on wide rows,
 * the slider under its label (never wider than 480 dp) on narrow ones. The slider is described
 * by [label], which carries the current value.
 */
@Composable
internal fun SettingsSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    sliderModifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    SettingsRow(modifier,
        label = { Text(label, color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f), fontSize = 14.sp) },
        control = {
            CineSlider(value, onValueChange, enabled = enabled, onValueChangeFinished = onValueChangeFinished, steps = steps, valueRange = valueRange,
                modifier = sliderModifier.widthIn(max = SETTINGS_SLIDER_MAX_DP.dp).fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label })
        })
}
