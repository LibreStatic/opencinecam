/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * An action as a 48 dp icon key; [label] is the spoken name and the click label. The key carries
 * [tag] verbatim, so callers keep their own tag scheme (the gallery uses "gallery-…").
 */
@Composable
internal fun CineIconButton(tag: String, icon: CineIcon, label: String, modifier: Modifier = Modifier, selected: Boolean = false,
    enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    val glyph = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> SettingsAccent
        else -> tint
    }
    Box(
        modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) SettingsAccent.copy(alpha = if (enabled) 0.18f else 0.08f) else Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label; if (selected) this.selected = true }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { CineGlyph(icon, glyph, Modifier.size(22.dp)) }
}

@Composable
internal fun CineIconButton(tag: String, icon: CineIcon, @StringRes label: Int, modifier: Modifier = Modifier, selected: Boolean = false,
    enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) =
    CineIconButton(tag, icon, stringResource(label), modifier, selected, enabled, tint, onClick)
