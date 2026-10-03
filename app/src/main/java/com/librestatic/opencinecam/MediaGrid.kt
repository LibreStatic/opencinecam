/* SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
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
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** What MediaStore knows about a file beyond its name: duration, frame size and HDR transfer. */
internal data class GalleryFacts(val durationMs: Long? = null, val width: Int? = null, val height: Int? = null, val hdr: Boolean = false)

/** A LOG recording carries its OCLog sidecar among the take's metadata. */
internal fun takeIsLog(take: LocalMediaTake): Boolean = take.metadata.any { it.name.endsWith(".oclog.json") }

/** Relation states the operator has to know about from the grid, before opening the take. */
internal fun takeNeedsAttention(status: LocalMediaRelationStatus): Boolean =
    status != LocalMediaRelationStatus.DECLARED && status != LocalMediaRelationStatus.LEGACY

internal fun LocalMediaKind.galleryKind(): GalleryMediaKind = when (this) {
    LocalMediaKind.PHOTO -> GalleryMediaKind.PHOTO
    LocalMediaKind.VIDEO -> GalleryMediaKind.VIDEO
    LocalMediaKind.AUDIO -> GalleryMediaKind.AUDIO
}

/** A photo opens to be looked at; only clips and recordings play. */
internal fun LocalMediaKind.primaryActionLabel(): Int =
    if (this == LocalMediaKind.PHOTO) R.string.media_action_view else R.string.media_action_play

internal fun LocalMediaKind.primaryActionGlyph(): CineIcon = if (this == LocalMediaKind.PHOTO) CineIcon.MEDIA else CineIcon.PLAY

internal fun LocalMediaKind.glyph(): CineIcon = when (this) {
    LocalMediaKind.PHOTO -> CineIcon.CAMERA
    LocalMediaKind.VIDEO -> CineIcon.VIDEO
    LocalMediaKind.AUDIO -> CineIcon.AUDIO
}

/** Facts are read once per file and kept while the gallery is open; a failed read stays unknown. */
@Composable
internal fun rememberGalleryFacts(artifact: LocalMediaArtifact, source: MediaCatalogSource,
    cache: MutableMap<String, GalleryFacts>): GalleryFacts? {
    var facts by remember(artifact) { mutableStateOf(cache[artifact.uri]) }
    LaunchedEffect(source, artifact) {
        if (facts != null) return@LaunchedEffect
        try {
            val read = source.facts(artifact) ?: return@LaunchedEffect
            currentCoroutineContext().ensureActive()
            cache[artifact.uri] = read; facts = read
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
    }
    return facts
}

/** Pictures already decoded, so scrolling back or opening a take's details does not decode them again. */
internal class GalleryThumbnailCache(maxBytes: Int = 24 * 1024 * 1024) {
    private val cache = object : android.util.LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    operator fun get(uri: String): Bitmap? = cache.get(uri)
    operator fun set(uri: String, bitmap: Bitmap) { cache.put(uri, bitmap) }
}

internal class GalleryThumbnailState(initial: Bitmap?) {
    var request by mutableIntStateOf(0)
    var bitmap by mutableStateOf(initial)
    var loading by mutableStateOf(false)
    var unavailable by mutableStateOf(false)
}

/**
 * Loads a take's thumbnail when its card composes (so only for takes scrolled into view) or, with
 * automatic thumbnails off, only after an explicit request.
 */
@Composable
internal fun rememberGalleryThumbnail(artifact: LocalMediaArtifact, source: MediaCatalogSource, automatic: Boolean,
    cache: GalleryThumbnailCache): GalleryThumbnailState {
    val state = remember(artifact.uri) { GalleryThumbnailState(cache[artifact.uri]) }
    LaunchedEffect(source, artifact.uri, state.request, automatic) {
        if (state.request == 0 && !automatic) return@LaunchedEffect
        if (state.bitmap != null) return@LaunchedEffect
        state.loading = true; state.unavailable = false
        try {
            val result = source.thumbnail(artifact)
            currentCoroutineContext().ensureActive()
            state.bitmap = result; state.unavailable = result == null
            if (result != null) cache[artifact.uri] = result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state.unavailable = true }
        finally { state.loading = false }
    }
    return state
}

/** The grid's version of [rememberListEntrance]: one clock for the tiles of the first paint. */
@Composable
internal fun rememberGridEntrance(state: LazyGridState, columns: Int, hasItems: () -> Boolean): ListEntrance? {
    if (LocalReducedMotion.current) return null
    val entrance = remember(columns) { ListEntrance(columns) }
    val currentHasItems by rememberUpdatedState(hasItems)
    LaunchedEffect(entrance) {
        snapshotFlow { currentHasItems() && state.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        entrance.firstIndex = state.firstVisibleItemIndex
        entrance.started = true
        val clock = EntranceMaxDelayMillis + EntranceTileMillis
        entrance.elapsed.animateTo(clock, tween(clock.toInt(), easing = LinearEasing))
    }
    return entrance
}

@Composable
internal fun GalleryDayHeader(day: LocalDate, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Text(remember(day) { takeDayText(context, day) }, modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp).testTag("gallery-day-$day"),
        color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

/** Everything the gallery can do with one take; a null action is not offered. */
internal class GalleryTakeActions(
    val play: () -> Unit,
    val details: () -> Unit,
    val share: (() -> Unit)?,
    val rename: (() -> Unit)?,
    val proxy: (() -> Unit)?,
    val delete: (() -> Unit)?,
)

/**
 * One take in the grid: picture, name, duration and badges, and a single ⋮ for its actions.
 * [onClick] plays the take, or selects it when an inspector sits beside the grid.
 */
@Composable
internal fun GalleryTakeCard(take: LocalMediaTake, settings: GallerySettings, source: MediaCatalogSource,
    thumbnails: GalleryThumbnailCache, facts: GalleryFacts?, selected: Boolean, clickLabel: String, actions: GalleryTakeActions, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    val title = takeTitleText(context, take)
    val slated = takeTitle(take.slate) != TakeTitle.CaptureTime
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) SettingsSurfaceRaised else SettingsSurface)
            .border(if (selected) 2.dp else 1.dp, if (selected) SettingsAccent else SettingsBorder, shape)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag("gallery-take-${take.id}"),
    ) {
        val thumbnail = rememberGalleryThumbnail(take.primary, source, settings.autoThumbnails, thumbnails)
        GalleryThumbnailTile(take, thumbnail, settings.autoThumbnails, facts, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, Modifier.fillMaxWidth().testTag("gallery-name-${take.id}"), color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // A slated take shows when it was shot underneath; a dated one says what it is.
                    val subtitle = if (slated) remember(take.primary.modifiedSeconds) { takeTimeText(context, take.primary.modifiedSeconds) }
                        else stringResource(galleryKindLabel(take.kind.galleryKind()))
                    Text(subtitle, Modifier.weight(1f, fill = false), color = SettingsMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (take.slate?.goodTake == true) {
                        val good = stringResource(R.string.gallery_good_take)
                        CineGlyph(CineIcon.STAR, SettingsAccent, Modifier.size(14.dp).semantics { contentDescription = good })
                    }
                }
            }
            GalleryTakeMenu(take, title, actions)
        }
        if (takeNeedsAttention(take.relationStatus)) Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp).testTag("gallery-warning-${take.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CineGlyph(CineIcon.WARNING, GalleryWarning, Modifier.size(14.dp))
            Text(stringResource(relationLabel(take.relationStatus)), color = GalleryWarning, fontSize = 12.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun relationLabel(status: LocalMediaRelationStatus): Int = when (status) {
    LocalMediaRelationStatus.DECLARED -> R.string.gallery_declared
    LocalMediaRelationStatus.LEGACY -> R.string.gallery_legacy
    LocalMediaRelationStatus.MISSING_METADATA -> R.string.gallery_missing
    LocalMediaRelationStatus.INVALID_METADATA -> R.string.gallery_invalid
    LocalMediaRelationStatus.INCOMPLETE -> R.string.gallery_incomplete
}

internal val GalleryWarning: Color @Composable @ReadOnlyComposable get() = LocalCineColors.current.pending

@Composable
private fun GalleryTakeMenu(take: LocalMediaTake, title: String, actions: GalleryTakeActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        MoreButton("gallery-menu-${take.id}", stringResource(R.string.media_more_actions, title)) { open = true }
        DropdownMenu(open, { open = false }, Modifier.testTag("gallery-menu-${take.id}-items")) {
            val close = { open = false }
            val plain = MaterialTheme.colorScheme.onSurface
            GalleryMenuItem("gallery-primary-${take.id}", take.kind.primaryActionGlyph(), take.kind.primaryActionLabel(), plain, close, actions.play)
            GalleryMenuItem("gallery-info-${take.id}", CineIcon.INFO, R.string.media_action_details, plain, close, actions.details)
            actions.share?.let { GalleryMenuItem("gallery-share-${take.id}", CineIcon.SHARE, R.string.media_action_share, plain, close, it) }
            actions.rename?.let { GalleryMenuItem("gallery-rename-${take.id}", CineIcon.RENAME, R.string.media_action_rename, plain, close, it) }
            actions.proxy?.let { GalleryMenuItem("gallery-proxy-${take.id}", CineIcon.PROXY, R.string.media_action_proxy, plain, close, it) }
            actions.delete?.let { GalleryMenuItem("gallery-delete-${take.id}", CineIcon.DELETE, R.string.media_action_delete,
                MaterialTheme.colorScheme.error, close, it) }
        }
    }
}

@Composable
private fun GalleryMenuItem(tag: String, icon: CineIcon, label: Int, tint: Color, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label), color = tint) },
        onClick = { close(); action() },
        leadingIcon = { CineGlyph(icon, tint, Modifier.size(20.dp)) },
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
    )
}

/** ⋮: the take's actions. The glyph set has no "more" icon, so the three dots are drawn here. */
@Composable
internal fun MoreButton(tag: String, label: String, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val r = size.minDimension * 0.09f
            for (i in -1..1) drawCircle(tint, r, center.copy(y = center.y + i * size.height * 0.3f))
        }
    }
}

/** ×: closes a details pane or sheet; drawn for the same reason as [MoreButton]. */
@Composable
internal fun CloseButton(tag: String, label: String, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = size.minDimension * 0.12f
            drawLine(tint, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, size.height), stroke)
            drawLine(tint, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), stroke)
        }
    }
}

/** A short technical label over the picture ("4K", "LOG", "0:42"). */
@Composable
internal fun GalleryBadge(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(text, modifier
        .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.82f), RoundedCornerShape(6.dp))
        .padding(horizontal = 6.dp, vertical = 2.dp),
        color = if (accent) SettingsAccent else MaterialTheme.colorScheme.onSurface, fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1)
}

@Composable
internal fun GalleryThumbnailTile(take: LocalMediaTake, state: GalleryThumbnailState, automatic: Boolean, facts: GalleryFacts?,
    modifier: Modifier = Modifier, tag: String = "gallery") {
    val id = take.id
    val bitmap = state.bitmap
    val requestLabel = stringResource(R.string.gallery_thumbnail)
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .then(
                // Without automatic thumbnails the empty tile is the button that loads the picture;
                // otherwise a tap anywhere on the card belongs to the card.
                if (bitmap != null || automatic) Modifier
                else Modifier
                    .clickable(enabled = !state.loading, onClickLabel = requestLabel) { state.request++ }
                    .semantics { contentDescription = requestLabel }
                    .testTag("$tag-thumbnail-load-$id"),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), stringResource(R.string.gallery_thumbnail_description, take.primary.name),
                Modifier.fillMaxSize().testTag("$tag-thumbnail-$id"), contentScale = ContentScale.Crop)
        } else if (!state.loading) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CineGlyph(take.kind.glyph(), SettingsMuted, Modifier.size(28.dp))
            when {
                state.unavailable -> Text(stringResource(R.string.gallery_thumbnail_unavailable), Modifier.testTag("$tag-thumbnail-error-$id"),
                    color = SettingsMuted, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1)
                !automatic -> Text(stringResource(R.string.gallery_thumbnail_tap), color = SettingsMuted, fontSize = 11.sp,
                    textAlign = TextAlign.Center, maxLines = 1)
            }
        }
        val reducedMotion = LocalReducedMotion.current
        AnimatedVisibility(
            visible = bitmap == null && state.loading,
            enter = if (reducedMotion) EnterTransition.None else fadeIn(),
            exit = if (reducedMotion) ExitTransition.None else fadeOut(),
        ) { LoadingIndicator(color = SettingsAccent) }
        // Like a camera's playback index: what the file is at the top, its length at the bottom.
        Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.82f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center) { CineGlyph(take.kind.glyph(), MaterialTheme.colorScheme.onSurface, Modifier.size(14.dp)) }
            resolutionBadge(facts?.width, facts?.height)?.let { GalleryBadge(it) }
            if (takeIsLog(take)) GalleryBadge("LOG", accent = true)
            if (facts?.hdr == true) GalleryBadge("HDR")
        }
        formatTakeDuration(facts?.durationMs)?.let {
            GalleryBadge(it, Modifier.align(Alignment.BottomEnd).padding(6.dp).testTag("$tag-duration-$id"))
        }
    }
}
