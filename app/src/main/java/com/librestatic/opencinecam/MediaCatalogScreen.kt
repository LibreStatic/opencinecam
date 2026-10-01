/* SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.librestatic.opencinecam.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** A small injectable boundary for cancellable UI tests; production uses the real MediaStore repository. */
internal interface MediaCatalogSource {
    suspend fun page(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int): LocalMediaPage
    suspend fun thumbnail(artifact: LocalMediaArtifact): Bitmap?
}

@Composable
internal fun MediaCatalogScreen(settings: GallerySettings, onSettings: (GallerySettings) -> Unit,
    sharingSettings: MediaSharingSettings = MediaSharingSettings(), onSharingSettings: (MediaSharingSettings) -> Unit = {},
    playbackSettings: PlaybackSettings = PlaybackSettings(), onPlaybackSettings: (PlaybackSettings) -> Unit = {},
    proxySettings: ProxySettings = ProxySettings(), onProxySettings: (ProxySettings) -> Unit = {}) {
    val context = LocalContext.current
    var review by remember { mutableStateOf<MediaReviewSelection?>(null) }
    var sharingTake by remember { mutableStateOf<LocalMediaTake?>(null) }
    var deletingTake by remember { mutableStateOf<LocalMediaTake?>(null) }
    var proxyCatalog by remember { mutableStateOf(false) }
    var proxyTake by remember { mutableStateOf<LocalMediaTake?>(null) }
    var renamingTake by remember { mutableStateOf<LocalMediaTake?>(null) }
    var deletionRefresh by remember { mutableIntStateOf(0) }
    var deletedTakeIds by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()
    val source = remember(context.applicationContext) {
        val repository = LocalMediaRepository(context.applicationContext)
        object : MediaCatalogSource {
            override suspend fun page(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int) =
                withContext(Dispatchers.IO) { repository.page(settings, query, cursor, limit) }
            override suspend fun thumbnail(artifact: LocalMediaArtifact) = withContext(Dispatchers.IO) { repository.thumbnail(artifact) }
        }
    }
    MediaCatalogContent(settings, onSettings, source, onShare = { sharingTake = it },
        onDelete = { deletingTake = it }, onRename = { renamingTake = it }, refreshGeneration = deletionRefresh,
        onReview = { deletedTakeIds = emptySet(); review = it }, onProxy = { proxyTake = it }, onProxyCatalog = { proxyCatalog = true }) { artifact ->
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(artifact.uri.toUri(), artifact.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    if (proxyCatalog) ProxyCatalogDialog(onDismiss = { proxyCatalog = false })
    // Mounted before the share/delete dialogs so those open above the review window.
    review?.let { selected -> MediaPlaybackDialog(selected, playbackSettings, onPlaybackSettings,
        onDismiss = { review = null }, onPage = { cursor -> source.page(selected.settings, selected.query, cursor, 60) },
        onShare = { sharingTake = it }, onDelete = { deletingTake = it }, deleted = deletedTakeIds) }
    sharingTake?.let { MediaShareDialog(it, sharingSettings, onSharingSettings, onDismiss = { sharingTake = null }) }
    deletingTake?.let { take -> MediaDeleteDialog(take, onDismiss = { deletingTake = null },
        onCompleted = {
            deletionRefresh++
            // The dialog reports completion, not success: the review drops the take only once its originals are gone.
            scope.launch { if (withContext(Dispatchers.IO) { take.originals.all { artifactGone(context, it.uri) } }) deletedTakeIds = deletedTakeIds + take.id }
        }) }
    proxyTake?.let { MediaProxyDialog(it, proxySettings, onProxySettings, onDismiss = { proxyTake = null }) }
    renamingTake?.let { MediaRenameDialog(it, onDismiss = { renamingTake = null }, onCompleted = { deletionRefresh++ }) }
}

private fun artifactGone(context: android.content.Context, uri: String): Boolean =
    runCatching { context.contentResolver.openFileDescriptor(uri.toUri(), "r")?.use { false } ?: true }
        .getOrElse { it is java.io.FileNotFoundException }

private data class GalleryRequest(val kind: GalleryMediaKind, val newestFirst: Boolean, val goodTakesOnly: Boolean, val query: String, val refresh: Int, val externalRefresh: Int)
private data class GalleryLoad(val request: GalleryRequest? = null, val takes: List<LocalMediaTake> = emptyList(),
    val next: LocalMediaCursor? = null, val loading: Boolean = true, val failed: Boolean = false)

@Composable
internal fun MediaCatalogContent(settings: GallerySettings, onSettings: (GallerySettings) -> Unit,
    source: MediaCatalogSource, onShare: ((LocalMediaTake) -> Unit)? = null,
    onDelete: ((LocalMediaTake) -> Unit)? = null, refreshGeneration: Int = 0,
    onRename: ((LocalMediaTake) -> Unit)? = null, onReview: ((MediaReviewSelection) -> Unit)? = null,
    onProxy: ((LocalMediaTake) -> Unit)? = null, onProxyCatalog: (() -> Unit)? = null, onOpen: (LocalMediaArtifact) -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    var query by rememberSaveable { mutableStateOf("") }
    // What the operator typed, which is not always what we search for. Keeping it lets the field
    // show a rejected edit long enough to explain it: a controlled field that silently discards the
    // edit calls onValueChange a second time with the reverted text, and a flag set on the first
    // call would be cleared by that second one before anything could be drawn.
    var typedQuery by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var batch by remember { mutableIntStateOf(0) }
    var load by remember(source) { mutableStateOf(GalleryLoad()) }
    var openFailed by remember { mutableStateOf(false) }
    val request = GalleryRequest(settings.kind, settings.newestFirst, settings.goodTakesOnly, query, refresh, refreshGeneration)
    val task = request to batch
    val currentTask by rememberUpdatedState(task)
    val visible = load.takeIf { it.request == request } ?: GalleryLoad()
    LaunchedEffect(source, task) {
        val previous = load.takeIf { it.request == request } ?: GalleryLoad(request = request)
        load = previous.copy(loading = true, failed = false)
        var cursor = previous.next
        try {
            var page: LocalMediaPage
            do {
                currentCoroutineContext().ensureActive()
                page = source.page(settings, query, cursor, 60)
                currentCoroutineContext().ensureActive()
                check(page.next == null || page.next !== cursor) { "Catalog continuation did not advance" }
                cursor = page.next
                if (page.takes.isEmpty() && cursor != null) yield()
            } while (page.takes.isEmpty() && cursor != null)
            if (currentTask != task) return@LaunchedEffect
            val merged = linkedMapOf<String, LocalMediaTake>()
            previous.takes.forEach { merged[it.id] = it }
            page.takes.forEach { merged[it.id] = it }
            load = GalleryLoad(request, merged.values.toList(), cursor, loading = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (currentTask == task) {
                android.util.Log.w("MediaCatalog", "Catalog read failed; existing results retained", failure)
                load = previous.copy(next = cursor, loading = false, failed = true)
            }
        }
    }
    val listState = rememberLazyListState()
    val entrance = rememberListEntrance(listState) { visible.takes.isNotEmpty() }
    // On tablets and unfolded screens the list, its buttons and fields stop at a readable width.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("gallery-list"), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "controls") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // One header row: the title and the catalog actions as icon keys, instead of a
                // stack of full-width buttons above the first take.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.media_tab), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    onProxyCatalog?.let { action -> CineIconButton("gallery-proxy-catalog", CineIcon.PROXY, R.string.proxy_catalog_title, onClick = action) }
                    CineIconButton("gallery-filters", CineIcon.FILTER, R.string.gallery_filters, selected = filters) { filters = !filters }
                    CineIconButton("gallery-refresh", CineIcon.REFRESH, R.string.gallery_refresh) { refresh++; openFailed = false }
                }
                val queryInvalid = !validGalleryQuery(typedQuery)
                OutlinedTextField(typedQuery, { candidate ->
                    typedQuery = galleryQueryInput(candidate)
                    // Only a query we would actually accept reaches the loader.
                    if (validGalleryQuery(candidate)) query = candidate
                }, label = { Text(stringResource(R.string.gallery_search), Modifier.fillMaxWidth().testTag("gallery-search-label")) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    isError = queryInvalid, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("gallery-search"))
                if (queryInvalid) Text(stringResource(R.string.gallery_search_invalid), Modifier.fillMaxWidth().testTag("gallery-search-invalid"))
                // The media type is the filter used most, so it stays one tap away above the list.
                SettingsPillRow { for (kind in GalleryMediaKind.entries) {
                    SettingsPill(stringResource(galleryKindLabel(kind)), "gallery-type-$kind", settings.kind == kind) {
                        onSettings(settings.copy(kind = kind))
                    }
                } }
                if (filters) SettingsCard { GallerySettingsControls(settings, onSettings, showKinds = false) }
                SettingsHelp(stringResource(R.string.gallery_help), tag = "gallery-help")
                if (openFailed) Text(stringResource(R.string.gallery_open_failed), Modifier.fillMaxWidth().testTag("gallery-open-failed"),
                    color = GalleryWarning, fontSize = 13.sp)
            }
        }
        itemsIndexed(visible.takes, key = { _, take -> take.id }) { index, take ->
            // The controls row is item 0, so a take's list index is its position plus one.
            GalleryTakeCard(take, settings, source, onShare, onDelete, onRename, onProxy, Modifier.listEntrance(entrance, index + 1)) { artifact ->
                try {
                    if (onReview != null && artifact.mimeType.substringBefore('/') in setOf("video", "audio", "image"))
                        onReview(MediaReviewSelection(visible.takes.toList(), artifact, settings, query, visible.next))
                    else onOpen(artifact)
                    openFailed = false
                }
                catch (_: Exception) { openFailed = true }
            }
        }
        item(key = "status") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Full height: a wavy indicator clipped to a strip looks like the old flat bar.
                AnimatedVisibility(
                    visible = visible.loading,
                    enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
                    exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(400)) + shrinkVertically(tween(500, delayMillis = 150)),
                ) {
                    Column(Modifier.fillMaxWidth().testTag("gallery-loading"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                        Text(stringResource(R.string.gallery_loading), Modifier.fillMaxWidth(), color = SettingsMuted, fontSize = 13.sp)
                    }
                }
                when {
                    visible.failed -> {
                        Text(stringResource(R.string.gallery_load_failed), Modifier.fillMaxWidth().testTag("gallery-error"), color = GalleryWarning)
                        GalleryButton("retry", R.string.gallery_retry) { load = load.copy(loading = true); batch++ }
                    }
                    visible.takes.isEmpty() -> Text(stringResource(R.string.gallery_empty), Modifier.fillMaxWidth().testTag("gallery-empty"), color = SettingsMuted)
                }
                if (!visible.loading && !visible.failed && visible.next != null) {
                    GalleryButton("more", R.string.gallery_more) { load = load.copy(loading = true); batch++ }
                } else if (!visible.loading && !visible.failed && visible.takes.isNotEmpty()) {
                    Text(stringResource(R.string.gallery_no_more), Modifier.fillMaxWidth().testTag("gallery-end"),
                        color = SettingsMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
    }
}

internal fun validGalleryQuery(query: String): Boolean = query.length <= 128 && query.none { it.isISOControl() }
/** Saveable-state memory bound, like the rename fields. Anything cut here is already invalid (>128),
 * so the bound never turns a paste into a different accepted query. */
internal fun galleryQueryInput(candidate: String): String = candidate.take(512)

@Composable
private fun GalleryButton(tag: String, label: Int, enabled: Boolean = true, action: () -> Unit) {
    OutlinedButton(action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gallery-$tag")) {
        Text(stringResource(label), Modifier.weight(1f).testTag("gallery-$tag-label"), textAlign = TextAlign.Center)
    }
}

private val GalleryWarning: Color @Composable @ReadOnlyComposable get() = LocalCineColors.current.pending

@Composable
private fun GalleryTakeCard(take: LocalMediaTake, settings: GallerySettings, source: MediaCatalogSource,
    onShare: ((LocalMediaTake) -> Unit)?, onDelete: ((LocalMediaTake) -> Unit)?,
    onRename: ((LocalMediaTake) -> Unit)?, onProxy: ((LocalMediaTake) -> Unit)?, modifier: Modifier = Modifier,
    onOpen: (LocalMediaArtifact) -> Unit) {
    var expanded by rememberSaveable(take.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SettingsSurface)
            .border(1.dp, SettingsBorder, shape)
            .padding(10.dp)
            .testTag("gallery-take-${take.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val thumbnail = rememberGalleryThumbnail(take.primary, source, settings.autoThumbnails)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GalleryThumbnailTile(take.id, take, thumbnail) { onOpen(take.primary) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(take.primary.name, Modifier.fillMaxWidth().testTag("gallery-name-${take.id}"), color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val slate = take.slate
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(listOfNotNull(stringResource(galleryKindLabel(take.kind.galleryKind())),
                        slate?.let { stringResource(R.string.gallery_take_number, it.takeNumber) }).joinToString(" · "),
                        color = SettingsMuted, fontSize = 12.sp, maxLines = 1)
                    if (slate?.goodTake == true) {
                        val good = stringResource(R.string.gallery_good_take)
                        CineGlyph(CineIcon.STAR, SettingsAccent, Modifier.size(14.dp).semantics { contentDescription = good })
                    }
                }
                val relationWarning = take.relationStatus !in setOf(LocalMediaRelationStatus.DECLARED, LocalMediaRelationStatus.LEGACY)
                Text(stringResource(when (take.relationStatus) {
                    LocalMediaRelationStatus.DECLARED -> R.string.gallery_declared
                    LocalMediaRelationStatus.LEGACY -> R.string.gallery_legacy
                    LocalMediaRelationStatus.MISSING_METADATA -> R.string.gallery_missing
                    LocalMediaRelationStatus.INVALID_METADATA -> R.string.gallery_invalid
                    LocalMediaRelationStatus.INCOMPLETE -> R.string.gallery_incomplete
                }), Modifier.fillMaxWidth().testTag("gallery-relation-${take.id}"),
                    color = if (relationWarning) GalleryWarning else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
        if (thumbnail.unavailable) Text(stringResource(R.string.gallery_thumbnail_unavailable),
            Modifier.fillMaxWidth().testTag("gallery-thumbnail-error-${take.id}"), color = SettingsMuted, fontSize = 12.sp)
        if (settings.showSlate) {
            val slate = take.slate
            // Only the slate fields that were filled in, on one line: the take number and good
            // mark already sit under the name, and a column of dashes says nothing.
            val slateLine = if (slate == null) stringResource(R.string.gallery_slate_absent) else listOfNotNull(
                slate.project.ifEmpty { null },
                slate.scene.ifEmpty { null }?.let { stringResource(R.string.gallery_slate_scene, it) },
                slate.reel.ifEmpty { null }?.let { stringResource(R.string.gallery_slate_reel, it) },
                slate.lens.ifEmpty { null }?.let { stringResource(R.string.gallery_slate_lens, it) },
                slate.camera.ifEmpty { null }?.let { stringResource(R.string.gallery_slate_camera, it) },
            ).joinToString(" · ").ifEmpty { null }
            slateLine?.let { Text(it, Modifier.fillMaxWidth().testTag("gallery-slate-${take.id}"), color = SettingsMuted,
                fontSize = 12.sp, lineHeight = 16.sp) }
            if (slate != null && (slate.location != ProductionSlateLocation.UNSPECIFIED || slate.timeOfDay != ProductionSlateTimeOfDay.UNSPECIFIED)) Text(stringResource(R.string.gallery_scene_conditions,
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
        if (settings.showTechnical) GalleryTechnical(take.primary, "primary-${take.id}")
        // Every action of a take on one row of icon keys; delete sits apart at the end.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CineIconButton("gallery-primary-${take.id}", CineIcon.PLAY, R.string.gallery_open_primary) { onOpen(take.primary) }
            if (onShare != null) CineIconButton("gallery-share-${take.id}", CineIcon.SHARE, R.string.media_share_action) { onShare(take) }
            if (onProxy != null && take.kind == LocalMediaKind.VIDEO) CineIconButton("gallery-proxy-${take.id}", CineIcon.PROXY, R.string.proxy_title) { onProxy(take) }
            if (onRename != null) CineIconButton("gallery-rename-${take.id}", CineIcon.RENAME, R.string.media_rename_action) { onRename(take) }
            val filesLabel = stringResource(R.string.gallery_files, take.originals.size, take.metadata.size)
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (expanded) SettingsAccent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable(onClickLabel = filesLabel) { expanded = !expanded }
                    .semantics { contentDescription = filesLabel; selected = expanded }
                    .padding(horizontal = 12.dp)
                    .testTag("gallery-files-${take.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CineGlyph(CineIcon.FILES, if (expanded) SettingsAccent else MaterialTheme.colorScheme.onSurface, Modifier.size(20.dp))
                Text("${take.originals.size + take.metadata.size}", color = if (expanded) SettingsAccent else MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("gallery-files-${take.id}-label"))
            }
            Spacer(Modifier.weight(1f))
            if (onDelete != null) CineIconButton("gallery-delete-${take.id}", CineIcon.DELETE, R.string.media_delete_action,
                tint = MaterialTheme.colorScheme.error) { onDelete(take) }
        }
        if (expanded) {
            take.originals.forEach { GalleryArtifact(it, false, settings.showTechnical, onOpen) }
            take.metadata.forEach { GalleryArtifact(it, true, settings.showTechnical, onOpen) }
        }
    }
}

private fun LocalMediaKind.galleryKind(): GalleryMediaKind = when (this) {
    LocalMediaKind.PHOTO -> GalleryMediaKind.PHOTO
    LocalMediaKind.VIDEO -> GalleryMediaKind.VIDEO
    LocalMediaKind.AUDIO -> GalleryMediaKind.AUDIO
}

@Composable
private fun GalleryArtifact(artifact: LocalMediaArtifact, metadata: Boolean, technical: Boolean,
    onOpen: (LocalMediaArtifact) -> Unit) {
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

@Composable
private fun GalleryTechnical(artifact: LocalMediaArtifact, tag: String) {
    Text(stringResource(R.string.gallery_technical, artifact.mimeType, artifact.sizeBytes, artifact.modifiedSeconds),
        Modifier.fillMaxWidth().testTag("gallery-technical-$tag"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
}

private class GalleryThumbnailState {
    var request by mutableIntStateOf(0)
    var bitmap by mutableStateOf<Bitmap?>(null)
    var loading by mutableStateOf(false)
    var unavailable by mutableStateOf(false)
}

/**
 * Loads a take's thumbnail when its card composes (so only for takes scrolled into view) or, with
 * automatic thumbnails off, only after an explicit request.
 */
@Composable
private fun rememberGalleryThumbnail(artifact: LocalMediaArtifact, source: MediaCatalogSource, automatic: Boolean): GalleryThumbnailState {
    val state = remember(artifact.uri) { GalleryThumbnailState() }
    LaunchedEffect(source, artifact.uri, state.request, automatic) {
        if (state.request == 0 && !automatic) return@LaunchedEffect
        if (state.bitmap != null) return@LaunchedEffect
        state.loading = true; state.unavailable = false
        try {
            val result = source.thumbnail(artifact)
            currentCoroutineContext().ensureActive()
            state.bitmap = result; state.unavailable = result == null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state.unavailable = true }
        finally { state.loading = false }
    }
    return state
}

@Composable
private fun GalleryThumbnailTile(id: String, take: LocalMediaTake, state: GalleryThumbnailState, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val bitmap = state.bitmap
    val requestLabel = stringResource(R.string.gallery_thumbnail)
    Box(
        Modifier
            .size(width = 104.dp, height = 78.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, SettingsBorder, shape)
            .then(
                if (bitmap != null) Modifier.clickable(onClick = onOpen)
                else Modifier
                    .clickable(enabled = !state.loading, onClickLabel = requestLabel) { state.request++ }
                    .semantics { contentDescription = requestLabel }
                    .testTag("gallery-thumbnail-load-$id"),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), stringResource(R.string.gallery_thumbnail_description, take.primary.name),
                Modifier.fillMaxSize().testTag("gallery-thumbnail-$id"), contentScale = ContentScale.Crop)
        } else if (!state.loading) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CineGlyph(when (take.kind) {
                LocalMediaKind.PHOTO -> CineIcon.CAMERA
                LocalMediaKind.VIDEO -> CineIcon.VIDEO
                LocalMediaKind.AUDIO -> CineIcon.AUDIO
            }, SettingsMuted, Modifier.size(24.dp))
            if (!state.unavailable) Text(stringResource(R.string.gallery_thumbnail_tap), color = SettingsMuted, fontSize = 10.sp,
                textAlign = TextAlign.Center, maxLines = 1)
        }
        val reducedMotion = LocalReducedMotion.current
        AnimatedVisibility(
            visible = bitmap == null && state.loading,
            enter = if (reducedMotion) EnterTransition.None else fadeIn(),
            exit = if (reducedMotion) ExitTransition.None else fadeOut(),
        ) { LoadingIndicator(color = SettingsAccent) }
        // Video and audio takes carry their kind over the picture, like a camera's playback index.
        if (take.kind != LocalMediaKind.PHOTO && bitmap != null) Box(
            Modifier.align(Alignment.BottomStart).padding(4.dp).size(20.dp).background(Color(0xCC090C0E), RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) { CineGlyph(if (take.kind == LocalMediaKind.VIDEO) CineIcon.PLAY else CineIcon.AUDIO, Color.White, Modifier.size(12.dp)) }
    }
}
