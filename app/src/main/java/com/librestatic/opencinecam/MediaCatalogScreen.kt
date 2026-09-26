/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
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
        onReview = { review = it }, onProxy = { proxyTake = it }, onProxyCatalog = { proxyCatalog = true }) { artifact ->
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(artifact.uri.toUri(), artifact.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    if (proxyCatalog) ProxyCatalogDialog(onDismiss = { proxyCatalog = false })
    review?.let { selected -> MediaPlaybackDialog(selected, playbackSettings, onPlaybackSettings,
        onDismiss = { review = null }, onPage = { cursor -> source.page(selected.settings, selected.query, cursor, 60) }) }
    sharingTake?.let { MediaShareDialog(it, sharingSettings, onSharingSettings, onDismiss = { sharingTake = null }) }
    deletingTake?.let { MediaDeleteDialog(it, onDismiss = { deletingTake = null }, onCompleted = { deletionRefresh++ }) }
    proxyTake?.let { MediaProxyDialog(it, proxySettings, onProxySettings, onDismiss = { proxyTake = null }) }
    renamingTake?.let { MediaRenameDialog(it, onDismiss = { renamingTake = null }, onCompleted = { deletionRefresh++ }) }
}

private data class GalleryRequest(val kind: GalleryMediaKind, val newestFirst: Boolean, val goodTakesOnly: Boolean, val query: String, val refresh: Int, val externalRefresh: Int)
private data class GalleryLoad(val request: GalleryRequest? = null, val takes: List<LocalMediaTake> = emptyList(),
    val next: LocalMediaCursor? = null, val loading: Boolean = true, val failed: Boolean = false)

@Composable
internal fun MediaCatalogContent(settings: GallerySettings, onSettings: (GallerySettings) -> Unit,
    source: MediaCatalogSource, onShare: ((LocalMediaTake) -> Unit)? = null,
    onDelete: ((LocalMediaTake) -> Unit)? = null, refreshGeneration: Int = 0,
    onRename: ((LocalMediaTake) -> Unit)? = null, onReview: ((MediaReviewSelection) -> Unit)? = null,
    onProxy: ((LocalMediaTake) -> Unit)? = null, onProxyCatalog: (() -> Unit)? = null, onOpen: (LocalMediaArtifact) -> Unit) {
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
    LazyColumn(Modifier.fillMaxSize().testTag("gallery-list"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "controls") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.gallery_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge)
                onProxyCatalog?.let { action ->
                    OutlinedButton(action, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gallery-proxy-catalog")) {
                        Text(stringResource(R.string.proxy_catalog_title))
                    }
                }
                Text(stringResource(R.string.gallery_help), Modifier.fillMaxWidth().testTag("gallery-help"))
                val queryInvalid = !validGalleryQuery(typedQuery)
                OutlinedTextField(typedQuery, { candidate ->
                    typedQuery = galleryQueryInput(candidate)
                    // Only a query we would actually accept reaches the loader.
                    if (validGalleryQuery(candidate)) query = candidate
                }, label = { Text(stringResource(R.string.gallery_search), Modifier.fillMaxWidth().testTag("gallery-search-label")) },
                    isError = queryInvalid, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("gallery-search"))
                if (queryInvalid) Text(stringResource(R.string.gallery_search_invalid), Modifier.fillMaxWidth().testTag("gallery-search-invalid"))
                GalleryButton("filters", R.string.gallery_filters) { filters = !filters }
                if (filters) GallerySettingsControls(settings, onSettings)
                GalleryButton("refresh", R.string.gallery_refresh) { refresh++; openFailed = false }
                if (openFailed) Text(stringResource(R.string.gallery_open_failed), Modifier.fillMaxWidth().testTag("gallery-open-failed"))
            }
        }
        items(visible.takes, key = { it.id }) { take ->
            GalleryTakeCard(take, settings, source, onShare, onDelete, onRename, onProxy) { artifact ->
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
                when {
                    visible.loading -> Text(stringResource(R.string.gallery_loading), Modifier.fillMaxWidth().testTag("gallery-loading"))
                    visible.failed -> {
                        Text(stringResource(R.string.gallery_load_failed), Modifier.fillMaxWidth().testTag("gallery-error"))
                        GalleryButton("retry", R.string.gallery_retry) { load = load.copy(loading = true); batch++ }
                    }
                    visible.takes.isEmpty() -> Text(stringResource(R.string.gallery_empty), Modifier.fillMaxWidth().testTag("gallery-empty"))
                }
                if (!visible.loading && !visible.failed && visible.next != null) {
                    GalleryButton("more", R.string.gallery_more) { load = load.copy(loading = true); batch++ }
                } else if (!visible.loading && !visible.failed && visible.takes.isNotEmpty()) {
                    Text(stringResource(R.string.gallery_no_more), Modifier.fillMaxWidth().testTag("gallery-end"))
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

@Composable
private fun GalleryTakeCard(take: LocalMediaTake, settings: GallerySettings, source: MediaCatalogSource,
    onShare: ((LocalMediaTake) -> Unit)?, onDelete: ((LocalMediaTake) -> Unit)?,
    onRename: ((LocalMediaTake) -> Unit)?, onProxy: ((LocalMediaTake) -> Unit)?, onOpen: (LocalMediaArtifact) -> Unit) {
    var expanded by rememberSaveable(take.id) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().testTag("gallery-take-${take.id}"), tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(take.primary.name, Modifier.fillMaxWidth().testTag("gallery-name-${take.id}"), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(when (take.kind) {
                LocalMediaKind.PHOTO -> R.string.gallery_photo
                LocalMediaKind.VIDEO -> R.string.gallery_video
                LocalMediaKind.AUDIO -> R.string.gallery_audio
            }), Modifier.fillMaxWidth())
            Text(stringResource(when (take.relationStatus) {
                LocalMediaRelationStatus.DECLARED -> R.string.gallery_declared
                LocalMediaRelationStatus.LEGACY -> R.string.gallery_legacy
                LocalMediaRelationStatus.MISSING_METADATA -> R.string.gallery_missing
                LocalMediaRelationStatus.INVALID_METADATA -> R.string.gallery_invalid
                LocalMediaRelationStatus.INCOMPLETE -> R.string.gallery_incomplete
            }), Modifier.fillMaxWidth().testTag("gallery-relation-${take.id}"))
            if (settings.showSlate) {
                val slate = take.slate
                Text(if (slate == null) stringResource(R.string.gallery_slate_absent) else stringResource(R.string.gallery_slate,
                    slate.project.ifEmpty { "—" }, slate.camera.ifEmpty { "—" }, slate.scene.ifEmpty { "—" },
                    slate.reel.ifEmpty { "—" }, slate.lens.ifEmpty { "—" }, slate.takeNumber,
                    stringResource(if (slate.goodTake) R.string.gallery_yes else R.string.gallery_no)),
                    Modifier.fillMaxWidth().testTag("gallery-slate-${take.id}"))
                if (slate != null) Text(stringResource(R.string.gallery_scene_conditions,
                    stringResource(when (slate.location) {
                        ProductionSlateLocation.UNSPECIFIED -> R.string.production_slate_unspecified
                        ProductionSlateLocation.INTERIOR -> R.string.production_slate_interior
                        ProductionSlateLocation.EXTERIOR -> R.string.production_slate_exterior
                    }), stringResource(when (slate.timeOfDay) {
                        ProductionSlateTimeOfDay.UNSPECIFIED -> R.string.production_slate_unspecified
                        ProductionSlateTimeOfDay.DAY -> R.string.production_slate_day
                        ProductionSlateTimeOfDay.NIGHT -> R.string.production_slate_night
                    })), Modifier.fillMaxWidth().testTag("gallery-scene-conditions-${take.id}"))
            }
            if (settings.showTechnical) GalleryTechnical(take.primary, "primary-${take.id}")
            GalleryThumbnail(take.id, take.primary, source)
            GalleryButton("primary-${take.id}", R.string.gallery_open_primary) { onOpen(take.primary) }
            if (onShare != null) GalleryButton("share-${take.id}", R.string.media_share_action) { onShare(take) }
            if (onDelete != null) GalleryButton("delete-${take.id}", R.string.media_delete_action) { onDelete(take) }
            if (onProxy != null && take.kind == LocalMediaKind.VIDEO) GalleryButton("proxy-${take.id}", R.string.proxy_title) { onProxy(take) }
            if (onRename != null) GalleryButton("rename-${take.id}", R.string.media_rename_action) { onRename(take) }
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gallery-files-${take.id}")) {
                Text(stringResource(R.string.gallery_files, take.originals.size, take.metadata.size),
                    Modifier.weight(1f).testTag("gallery-files-${take.id}-label"), textAlign = TextAlign.Center)
            }
            if (expanded) {
                take.originals.forEach { GalleryArtifact(it, false, settings.showTechnical, onOpen) }
                take.metadata.forEach { GalleryArtifact(it, true, settings.showTechnical, onOpen) }
            }
        }
    }
}

@Composable
private fun GalleryArtifact(artifact: LocalMediaArtifact, metadata: Boolean, technical: Boolean,
    onOpen: (LocalMediaArtifact) -> Unit) {
    OutlinedButton(onClick = { onOpen(artifact) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gallery-open-${artifact.uri}")) {
        Text(stringResource(if (metadata) R.string.gallery_metadata else R.string.gallery_original, artifact.name),
            Modifier.weight(1f).testTag("gallery-open-${artifact.uri}-label"), textAlign = TextAlign.Center)
    }
    if (technical) GalleryTechnical(artifact, artifact.uri)
}

@Composable
private fun GalleryTechnical(artifact: LocalMediaArtifact, tag: String) {
    Text(stringResource(R.string.gallery_technical, artifact.mimeType, artifact.sizeBytes, artifact.modifiedSeconds),
        Modifier.fillMaxWidth().testTag("gallery-technical-$tag"))
}

@Composable
private fun GalleryThumbnail(id: String, artifact: LocalMediaArtifact, source: MediaCatalogSource) {
    var request by remember(artifact.uri) { mutableIntStateOf(0) }
    var bitmap by remember(artifact.uri) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(artifact.uri) { mutableStateOf(false) }
    var unavailable by remember(artifact.uri) { mutableStateOf(false) }
    LaunchedEffect(source, artifact.uri, request) {
        if (request == 0) return@LaunchedEffect
        loading = true; unavailable = false
        try {
            val result = source.thumbnail(artifact)
            currentCoroutineContext().ensureActive()
            bitmap = result; unavailable = result == null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable = true }
        finally { loading = false }
    }
    bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.gallery_thumbnail_description, artifact.name),
        Modifier.fillMaxWidth().heightIn(max = 256.dp).testTag("gallery-thumbnail-$id")) }
    if (loading) Text(stringResource(R.string.gallery_thumbnail_loading), Modifier.fillMaxWidth())
    if (unavailable) Text(stringResource(R.string.gallery_thumbnail_unavailable), Modifier.fillMaxWidth().testTag("gallery-thumbnail-error-$id"))
    if (bitmap == null) GalleryButton("thumbnail-load-$id", R.string.gallery_thumbnail, enabled = !loading) { request++ }
}
