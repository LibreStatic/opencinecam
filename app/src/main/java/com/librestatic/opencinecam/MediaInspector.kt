/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.storage.LocalMediaArtifact
import com.librestatic.opencinecam.storage.LocalMediaKind
import com.librestatic.opencinecam.storage.LocalMediaRelationStatus
import com.librestatic.opencinecam.storage.LocalMediaTake
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import kotlinx.coroutines.launch

/** What the relation state means for the operator, in one plain sentence. */
internal fun integrityExplanation(status: LocalMediaRelationStatus): Int = when (status) {
    LocalMediaRelationStatus.DECLARED -> R.string.media_integrity_declared
    LocalMediaRelationStatus.LEGACY -> R.string.media_integrity_legacy
    LocalMediaRelationStatus.MISSING_METADATA -> R.string.media_integrity_missing
    LocalMediaRelationStatus.INVALID_METADATA -> R.string.media_integrity_invalid
    LocalMediaRelationStatus.INCOMPLETE -> R.string.media_integrity_incomplete
}

/**
 * A take's details: poster, name, technical chips, its actions, integrity in plain words, the
 * slate and the files. The same content fills the side inspector, the bottom sheet and the side
 * sheet; [onClose] closes whichever holds it.
 */
@Composable
internal fun MediaTakeDetails(take: LocalMediaTake, settings: GallerySettings, source: MediaCatalogSource,
    thumbnails: GalleryThumbnailCache, facts: GalleryFacts?, badges: TakeBadges, actions: GalleryTakeActions,
    onOpen: (LocalMediaArtifact) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val window = LocalAdaptiveWindow.current
    Column(modifier.fillMaxWidth().testTag("gallery-details-${take.id}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.media_details_title), Modifier.weight(1f), color = SettingsMuted, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            val close = stringResource(R.string.media_details_close)
            CloseButton("gallery-info-close", if (window.hardwareKeyboard) withShortcut(close, ShortcutAction.DISMISS) else close, onClose)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            val poster = rememberGalleryThumbnail(take.primary, source, settings.autoThumbnails, thumbnails)
            GalleryThumbnailTile(take, poster, settings.autoThumbnails, facts, Modifier.matchParentSize(), tag = "gallery-poster")
            if (take.kind != LocalMediaKind.PHOTO && (poster.bitmap != null || settings.autoThumbnails)) {
                val play = stringResource(R.string.media_action_play)
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.82f))
                        .clickable(onClickLabel = play, role = Role.Button, onClick = actions.play)
                        .semantics { contentDescription = play }
                        .testTag("gallery-inspector-poster-play"),
                    contentAlignment = Alignment.Center,
                ) { CineGlyph(CineIcon.PLAY, MaterialTheme.colorScheme.onSurface, Modifier.size(24.dp)) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(takeTitleText(context, take), Modifier.fillMaxWidth().testTag("gallery-title-${take.id}"),
                color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            // Without a slate the title already is the capture time.
            if (takeTitle(take.slate) != TakeTitle.CaptureTime) {
                Text(remember(take.primary.modifiedSeconds) { takeTimeText(context, take.primary.modifiedSeconds) },
                    Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Text(take.primary.name, Modifier.fillMaxWidth().testTag("gallery-file-name-${take.id}"), color = SettingsMuted,
                fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DetailChip(stringResource(galleryKindLabel(take.kind.galleryKind())))
            formatTakeDuration(facts?.durationMs)?.let { DetailChip(it) }
            resolutionBadge(facts?.width, facts?.height)?.let { DetailChip(it) }
            badges.codec?.let { DetailChip(codecBadgeText(it), tag = "gallery-details-codec-${take.id}") }
            if (takeIsLog(take)) DetailChip("LOG", accent = true)
            if (facts?.hdr == true) DetailChip("HDR")
            DetailChip(remember(take.originals) { Formatter.formatShortFileSize(context, take.originals.sumOf { it.sizeBytes }) })
            if (take.slate?.goodTake == true) DetailChip(stringResource(R.string.gallery_good_take), accent = true)
            badges.proxy.label()?.let { DetailChip(stringResource(it), color = badges.proxy.tint, tag = "gallery-details-proxy-${take.id}") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InspectorAction("gallery-inspector-play", take.kind.primaryActionGlyph(), take.kind.primaryActionLabel(), SettingsAccent, actions.play)
            actions.share?.let { InspectorAction("gallery-inspector-share", CineIcon.SHARE, R.string.media_action_share, action = it) }
            actions.rename?.let { InspectorAction("gallery-inspector-rename", CineIcon.RENAME, R.string.media_action_rename, action = it) }
            actions.proxy?.let { InspectorAction("gallery-inspector-proxy", CineIcon.PROXY, R.string.media_action_proxy, action = it) }
            actions.delete?.let { InspectorAction("gallery-inspector-delete", CineIcon.DELETE, R.string.media_action_delete,
                MaterialTheme.colorScheme.error, it) }
        }
        DetailSection(R.string.media_section_integrity) {
            val attention = takeNeedsAttention(take.relationStatus)
            val tint = if (attention) GalleryWarning else LocalCineColors.current.ok
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CineGlyph(if (attention) CineIcon.WARNING else CineIcon.CHECK, tint, Modifier.size(18.dp))
                Text(stringResource(relationLabel(take.relationStatus)), Modifier.weight(1f).testTag("gallery-relation-${take.id}"),
                    color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(integrityExplanation(take.relationStatus)), Modifier.fillMaxWidth(), color = SettingsMuted,
                fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (settings.showSlate) DetailSection(R.string.media_section_slate) { GallerySlate(take) }
        if (settings.showTechnical) DetailSection(R.string.media_section_properties) { GalleryTechnical(take.primary, "primary-${take.id}") }
        GalleryFiles(take, settings.showTechnical, onOpen)
    }
}

/** [color] tints a chip that reports a state (a proxy) in its own colour, border included. */
@Composable
private fun DetailChip(text: String, accent: Boolean = false, color: Color? = null, tag: String? = null) {
    val tint = color ?: if (accent) SettingsAccent else null
    Text(text, Modifier
        .border(1.dp, tint ?: SettingsBorder, RoundedCornerShape(8.dp))
        .padding(horizontal = 10.dp, vertical = 5.dp)
        .then(tag?.let { Modifier.testTag(it) } ?: Modifier),
        color = tint ?: MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, maxLines = 1)
}

@Composable
private fun InspectorAction(tag: String, icon: CineIcon, label: Int, tint: Color = MaterialTheme.colorScheme.onSurface, action: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .widthIn(min = 72.dp)
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(SettingsSurfaceRaised)
            .border(1.dp, SettingsBorder, shape)
            .clickable(role = Role.Button, onClick = action)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        CineGlyph(icon, tint, Modifier.size(22.dp))
        Text(stringResource(label), Modifier.testTag("$tag-label"), color = tint, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun DetailSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(SettingsSurfaceRaised).border(1.dp, SettingsBorder, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(title), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** Only the slate fields that were filled in, on one line, plus where and when the scene plays. */
@Composable
private fun GallerySlate(take: LocalMediaTake) {
    val slate = take.slate
    val slateLine = if (slate == null) stringResource(R.string.gallery_slate_absent) else listOfNotNull(
        slate.project.ifEmpty { null },
        slate.scene.ifEmpty { null }?.let { stringResource(R.string.gallery_slate_scene, it) },
        slate.reel.ifEmpty { null }?.let { stringResource(R.string.gallery_slate_reel, it) },
        slate.lens.ifEmpty { null }?.let { stringResource(R.string.gallery_slate_lens, it) },
        slate.camera.ifEmpty { null }?.let { stringResource(R.string.gallery_slate_camera, it) },
    ).joinToString(" · ").ifEmpty { null }
    slateLine?.let { Text(it, Modifier.fillMaxWidth().testTag("gallery-slate-${take.id}"), color = SettingsMuted, fontSize = 12.sp, lineHeight = 16.sp) }
    if (slate != null && (slate.location != ProductionSlateLocation.UNSPECIFIED || slate.timeOfDay != ProductionSlateTimeOfDay.UNSPECIFIED)) Text(
        stringResource(R.string.gallery_scene_conditions,
            stringResource(when (slate.location) {
                ProductionSlateLocation.UNSPECIFIED -> R.string.production_slate_unspecified
                ProductionSlateLocation.INTERIOR -> R.string.production_slate_interior
                ProductionSlateLocation.EXTERIOR -> R.string.production_slate_exterior
            }), stringResource(when (slate.timeOfDay) {
                ProductionSlateTimeOfDay.UNSPECIFIED -> R.string.production_slate_unspecified
                ProductionSlateTimeOfDay.DAY -> R.string.production_slate_day
                ProductionSlateTimeOfDay.NIGHT -> R.string.production_slate_night
            })), Modifier.fillMaxWidth().testTag("gallery-scene-conditions-${take.id}"), color = SettingsMuted, fontSize = 12.sp)
}

@Composable
private fun GalleryFiles(take: LocalMediaTake, technical: Boolean, onOpen: (LocalMediaArtifact) -> Unit) {
    var expanded by rememberSaveable(take.id) { mutableStateOf(false) }
    val summary = stringResource(R.string.media_files_summary, take.originals.size, take.metadata.size)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (expanded) SettingsAccent.copy(alpha = 0.18f) else Color.Transparent)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { selected = expanded }
                .padding(horizontal = 12.dp)
                .testTag("gallery-files-${take.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tint = if (expanded) SettingsAccent else MaterialTheme.colorScheme.onSurface
            CineGlyph(CineIcon.FILES, tint, Modifier.size(20.dp))
            Text(stringResource(R.string.media_section_files), color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(summary, Modifier.weight(1f).testTag("gallery-files-${take.id}-label"), color = SettingsMuted, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            CineGlyph(if (expanded) CineIcon.COLLAPSE else CineIcon.EXPAND, tint, Modifier.size(16.dp))
        }
        if (expanded) {
            take.originals.forEach { GalleryArtifact(it, false, technical, onOpen) }
            take.metadata.forEach { GalleryArtifact(it, true, technical, onOpen) }
        }
    }
}

@Composable
private fun GalleryArtifact(artifact: LocalMediaArtifact, metadata: Boolean, technical: Boolean, onOpen: (LocalMediaArtifact) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(SettingsSurfaceRaised)
            .clickable { onOpen(artifact) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("gallery-open-${artifact.uri}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CineGlyph(if (metadata) CineIcon.INFO else CineIcon.MEDIA, SettingsMuted, Modifier.size(18.dp))
        Text(stringResource(if (metadata) R.string.gallery_metadata else R.string.gallery_original, artifact.name),
            Modifier.weight(1f).testTag("gallery-open-${artifact.uri}-label"), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (technical) GalleryTechnical(artifact, artifact.uri)
}

/** "Type: video/mp4 · Size: 1.2 GB · Modified: Oct 2, 14:31". */
internal fun galleryTechnicalText(context: android.content.Context, artifact: LocalMediaArtifact): String =
    context.getString(R.string.gallery_technical, artifact.mimeType, Formatter.formatShortFileSize(context, artifact.sizeBytes),
        takeTimeText(context, artifact.modifiedSeconds))

@Composable
private fun GalleryTechnical(artifact: LocalMediaArtifact, tag: String) {
    val context = LocalContext.current
    Text(remember(artifact) { galleryTechnicalText(context, artifact) }, Modifier.fillMaxWidth().testTag("gallery-technical-$tag"),
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
}

/** The list-detail inspector beside the grid; it keeps its place even with nothing selected. */
@Composable
internal fun MediaInspectorPane(take: LocalMediaTake?, modifier: Modifier = Modifier, details: @Composable (LocalMediaTake, Modifier) -> Unit) {
    Surface(modifier.fillMaxHeight().testTag("gallery-inspector"), shape = RoundedCornerShape(16.dp), color = SettingsSurface) {
        if (take == null) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.media_details_empty), Modifier.testTag("gallery-inspector-empty"), color = SettingsMuted,
                fontSize = 14.sp, textAlign = TextAlign.Center)
        } else key(take.id) {
            details(take, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp))
        }
    }
}

/**
 * A take's details on demand: a bottom sheet in a compact portrait window, a side sheet
 * otherwise. Esc and Back close it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaInspectorSheet(take: LocalMediaTake, bottom: Boolean, onDismiss: () -> Unit,
    details: @Composable (LocalMediaTake, Modifier, () -> Unit) -> Unit) {
    if (!bottom) {
        MediaDialogFrame("gallery-info-sheet", onDismiss, MediaDialogPlacement.SIDE) {
            details(take, Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), onDismiss)
        }
        return
    }
    // Hidden or fully open, no half-height stop: what skipPartiallyExpanded used to say.
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { dismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, modifier = Modifier.testTag("gallery-info-sheet"),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        details(take, Modifier
            .fillMaxWidth()
            .dialogShortcuts { if (it == ShortcutAction.DISMISS) { close(); true } else false }
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp), close)
    }
}
