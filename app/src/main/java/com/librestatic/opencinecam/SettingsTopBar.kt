/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The header of a page opened from Settings (About, Camera capabilities): back, then the title on
 * the left at the hub's own title size, an optional muted [subtitle] and an optional [action] at the
 * end. One header, so every settings page reads the same.
 */
@Composable
internal fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    backTag: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    /** Names the way back when it leads somewhere other than Settings, such as the welcome tour. */
    backLabel: String? = null,
) {
    val back = backLabel ?: stringResource(R.string.about_back)
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = back, role = Role.Button, onClick = onBack)
                .semantics { contentDescription = back }.testTag(backTag),
            contentAlignment = Alignment.Center,
        ) { CineGlyph(CineIcon.BACK, MaterialTheme.colorScheme.onSurface, Modifier.size(22.dp)) }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, color = SettingsMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        action?.invoke()
    }
}
