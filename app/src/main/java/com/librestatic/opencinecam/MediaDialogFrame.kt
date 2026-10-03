/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.librestatic.opencinecam.storage.LocalMediaTake

internal enum class MediaDialogPlacement { CENTER, SIDE }

/**
 * The frame every Media dialog shares. It draws its own scrim over the whole window, so the dim
 * also covers the navigation and the system bars, keeps the card at a readable width on a tablet
 * or a desktop window, and closes on Esc. [SIDE] opens a long form as a sheet at the window's end.
 */
@Composable
internal fun MediaDialogFrame(tag: String, onDismiss: () -> Unit, placement: MediaDialogPlacement = MediaDialogPlacement.CENTER,
    content: @Composable ColumnScope.() -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        val side = placement == MediaDialogPlacement.SIDE
        Box(Modifier.fillMaxSize(), contentAlignment = if (side) Alignment.CenterEnd else Alignment.Center) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                .clickable(interactionSource = null, indication = null) { dismiss() })
            val shape = if (side) RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp) else MaterialTheme.shapes.large
            val frame = if (side) Modifier.fillMaxHeight().widthIn(max = 440.dp).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
            else Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).widthIn(max = 560.dp).heightIn(max = 680.dp)
            Surface(frame.testTag(tag).dialogShortcuts { if (it == ShortcutAction.DISMISS) { dismiss(); true } else false },
                shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}

/** Which take a dialog acts on: its gallery label, with the file name as small secondary text. */
@Composable
internal fun MediaDialogTake(take: LocalMediaTake, tag: String) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(takeTitleText(context, take), Modifier.fillMaxWidth().testTag("$tag-label"), color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(take.primary.name, Modifier.fillMaxWidth().testTag("$tag-file"), color = SettingsMuted, fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Technical detail (provider messages, identifiers, URIs) stays out of the way until the
 * operator asks for it.
 */
@Composable
internal fun MediaDetails(tag: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(tag) { mutableStateOf(false) }
    val label = stringResource(if (open) R.string.media_details_hide else R.string.media_details_show)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = 8.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CineGlyph(if (open) CineIcon.COLLAPSE else CineIcon.EXPAND, SettingsAccent, Modifier.size(16.dp))
            Text(label, Modifier.testTag("$tag-label"), color = SettingsAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides SettingsMuted) { content() }
        }
    }
}
