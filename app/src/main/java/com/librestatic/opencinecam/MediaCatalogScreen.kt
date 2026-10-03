/* SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaFormat
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.librestatic.opencinecam.storage.*
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** A small injectable boundary for cancellable UI tests; production uses the real MediaStore repository. */
internal interface MediaCatalogSource {
    suspend fun page(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int): LocalMediaPage
    suspend fun thumbnail(artifact: LocalMediaArtifact): Bitmap?
    /** Duration, frame size and HDR transfer for the card badges; null when unknown. */
    suspend fun facts(artifact: LocalMediaArtifact): GalleryFacts? = null
    /** Proxy state of the loaded takes, by take id; takes with no proxy are absent. */
    fun proxyStates(ids: Set<String>): Flow<Map<String, TakeProxyState>> = flowOf(emptyMap())
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
        val app = context.applicationContext
        val repository = LocalMediaRepository(app)
        val queue by lazy { MediaProxyQueue.get(app) }
        object : MediaCatalogSource {
            override suspend fun page(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int) =
                withContext(Dispatchers.IO) { repository.page(settings, query, cursor, limit) }
            override suspend fun thumbnail(artifact: LocalMediaArtifact) = withContext(Dispatchers.IO) { galleryThumbnail(app, artifact) }
            override suspend fun facts(artifact: LocalMediaArtifact) = withContext(Dispatchers.IO) { galleryFacts(app, artifact) }
            // The queue for work in flight, the receipts (listed once per page load) for proxies already made.
            override fun proxyStates(ids: Set<String>): Flow<Map<String, TakeProxyState>> = if (ids.isEmpty()) flowOf(emptyMap())
                else combine(queue.states, flow { emit(committedProxyTakes(app, ids)) }.flowOn(Dispatchers.IO)) { state, committed ->
                    takeProxyStates(ids, state.jobs, committed)
                }
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

private fun artifactGone(context: Context, uri: String): Boolean =
    runCatching { context.contentResolver.openFileDescriptor(uri.toUri(), "r")?.use { false } ?: true }
        .getOrElse { it is java.io.FileNotFoundException }

/** Grid cards reach 260 dp, so the picture is asked for at twice the catalog's old 256 px. */
private fun galleryThumbnail(context: Context, artifact: LocalMediaArtifact): Bitmap? = runCatching {
    context.contentResolver.loadThumbnail(artifact.uri.toUri(), Size(512, 512), null)
}.getOrNull()

/** One MediaStore row read by URI: no file is opened, decoded or probed for the badges. */
private fun galleryFacts(context: Context, artifact: LocalMediaArtifact): GalleryFacts? {
    val video = artifact.mimeType.startsWith("video/")
    if (!video && !artifact.mimeType.startsWith("audio/")) return null
    val transfer = video && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val columns = buildList {
        add(MediaStore.MediaColumns.DURATION)
        if (video) { add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT) }
        if (transfer) add(MediaStore.Video.VideoColumns.COLOR_TRANSFER)
    }.toTypedArray()
    return runCatching {
        context.contentResolver.query(artifact.uri.toUri(), columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun long(column: String): Long? = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            val colorTransfer = if (transfer) long(MediaStore.Video.VideoColumns.COLOR_TRANSFER) else null
            GalleryFacts(long(MediaStore.MediaColumns.DURATION), if (video) long(MediaStore.MediaColumns.WIDTH)?.toInt() else null,
                if (video) long(MediaStore.MediaColumns.HEIGHT)?.toInt() else null,
                colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084.toLong() || colorTransfer == MediaFormat.COLOR_TRANSFER_HLG.toLong())
        }
    }.getOrNull()
}

private data class GalleryRequest(val kind: GalleryMediaKind, val newestFirst: Boolean, val goodTakesOnly: Boolean, val query: String, val refresh: Int, val externalRefresh: Int)
private data class GalleryLoad(val request: GalleryRequest? = null, val takes: List<LocalMediaTake> = emptyList(),
    val next: LocalMediaCursor? = null, val loading: Boolean = true, val failed: Boolean = false,
    val encodings: Map<String, LocalMediaEncoding> = emptyMap())

@Composable
internal fun MediaCatalogContent(settings: GallerySettings, onSettings: (GallerySettings) -> Unit,
    source: MediaCatalogSource, onShare: ((LocalMediaTake) -> Unit)? = null,
    onDelete: ((LocalMediaTake) -> Unit)? = null, refreshGeneration: Int = 0,
    onRename: ((LocalMediaTake) -> Unit)? = null, onReview: ((MediaReviewSelection) -> Unit)? = null,
    onProxy: ((LocalMediaTake) -> Unit)? = null, onProxyCatalog: (() -> Unit)? = null,
    initialSelection: String? = null, onOpen: (LocalMediaArtifact) -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    var query by rememberSaveable { mutableStateOf("") }
    // What the operator typed, which is not always what we search for. Keeping it lets the field
    // show a rejected edit long enough to explain it: a controlled field that silently discards the
    // edit calls onValueChange a second time with the reverted text, and a flag set on the first
    // call would be cleared by that second one before anything could be drawn.
    var typedQuery by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
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
            load = GalleryLoad(request, merged.values.toList(), cursor, loading = false, encodings = previous.encodings + page.encodings)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (currentTask == task) {
                android.util.Log.w("MediaCatalog", "Catalog read failed; existing results retained", failure)
                load = previous.copy(next = cursor, loading = false, failed = true)
            }
        }
    }

    // The take the inspector shows. While a refresh reads again it keeps the last copy; once a read
    // has finished without it (deleted, renamed away by a filter) the selection goes.
    // [initialSelection] opens a take's details on arrival (the Compose Driver review uses it).
    var selectedId by rememberSaveable { mutableStateOf(initialSelection) }
    var detailsOpen by rememberSaveable { mutableStateOf(initialSelection != null) }
    var lastSelected by remember { mutableStateOf<LocalMediaTake?>(null) }
    val selectedTake = selectedId?.let { id -> visible.takes.firstOrNull { it.id == id } ?: lastSelected?.takeIf { it.id == id && visible.loading } }
    SideEffect { if (selectedTake != null) lastSelected = selectedTake }
    LaunchedEffect(selectedId, visible.loading, visible.failed, visible.takes) {
        if (selectedId != null && !visible.loading && !visible.failed && visible.takes.none { it.id == selectedId }) {
            selectedId = null; detailsOpen = false
        }
    }
    val facts = remember(source) { mutableMapOf<String, GalleryFacts>() }
    val proxies by remember(source, visible.takes) { source.proxyStates(visible.takes.mapTo(HashSet()) { it.id }) }
        .collectAsState(emptyMap())
    fun badges(take: LocalMediaTake) = TakeBadges(codecBadge(take, visible.encodings[take.id]), proxies[take.id] ?: TakeProxyState.NONE)
    val thumbnails = remember(source) { GalleryThumbnailCache() }
    val groups = remember(visible.takes) { groupByTakeDay(visible.takes, ZoneId.systemDefault()) { it.primary.modifiedSeconds } }
    val gridState = rememberLazyGridState()

    fun open(artifact: LocalMediaArtifact) {
        try {
            if (onReview != null && artifact.mimeType.substringBefore('/') in setOf("video", "audio", "image"))
                onReview(MediaReviewSelection(visible.takes.toList(), artifact, settings, query, visible.next))
            else onOpen(artifact)
            openFailed = false
        }
        catch (_: Exception) { openFailed = true }
    }
    /** [before] runs ahead of every action, so an on-demand sheet can get out of the way first. */
    fun actions(take: LocalMediaTake, before: () -> Unit = {}): GalleryTakeActions = GalleryTakeActions(
        play = { before(); open(take.primary) },
        details = { selectedId = take.id; detailsOpen = true },
        share = onShare?.let { action -> { before(); action(take) } },
        rename = onRename?.let { action -> { before(); action(take) } },
        proxy = onProxy?.takeIf { take.kind == LocalMediaKind.VIDEO }?.let { action -> { before(); action(take) } },
        delete = onDelete?.let { action -> { before(); action(take) } },
    )

    val controls: @Composable () -> Unit = {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // One header row: the title and the catalog actions as icon keys, instead of a
            // stack of full-width buttons above the first take.
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.media_tab), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold)
                onProxyCatalog?.let { action -> CineIconButton("gallery-proxy-catalog", CineIcon.PROXY, R.string.proxy_catalog_title, onClick = action) }
                CineIconButton("gallery-filters", CineIcon.FILTER, R.string.gallery_filters, selected = filters) { filters = !filters }
                CineIconButton("gallery-refresh", CineIcon.REFRESH, R.string.gallery_refresh) { refresh++; openFailed = false }
                CineIconButton("gallery-help-toggle", CineIcon.INFO, if (help) R.string.settings_help_hide else R.string.settings_help_show,
                    selected = help) { help = !help }
            }
            if (help) SettingsHelpText(stringResource(R.string.gallery_help), "gallery-help")
            SubjectReviewOperatorBar()
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
            if (openFailed) Text(stringResource(R.string.gallery_open_failed), Modifier.fillMaxWidth().testTag("gallery-open-failed"),
                color = GalleryWarning, fontSize = 13.sp)
        }
    }
    val status: @Composable () -> Unit = {
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
    val details: @Composable (LocalMediaTake, Modifier, () -> Unit, Boolean) -> Unit = { take, modifier, close, onDemand ->
        MediaTakeDetails(take, settings, source, thumbnails, rememberGalleryFacts(take.primary, source, facts), badges(take),
            if (onDemand) actions(take, close) else actions(take), onOpen = { open(it) }, onClose = close, modifier)
    }

    val coordinator = LocalFoldDisplayCoordinator.current
    val foldFallback = remember { MutableStateFlow(FoldDisplayState()) }
    val fold by (coordinator?.states ?: foldFallback).collectAsState()
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val areaWidth = maxWidth.value
        val widthClass = windowWidthClass(areaWidth)
        val heightClass = windowHeightClass(maxHeight.value)
        val landscape = maxWidth > maxHeight
        val density = LocalDensity.current
        // Half-opened on a hinge: the grid on one side, the inspector on the other, never across it.
        val panes = with(density) {
            foldPanes(constraints.maxWidth, constraints.maxHeight, origin.x.roundToInt(), origin.y.roundToInt(), fold.hinge,
                gutter = 16.dp.roundToPx(), minimum = 280.dp.roundToPx(), swap = false)
        }
        val side = panes != null || mediaInspectorSide(widthClass, heightClass, landscape)
        LaunchedEffect(side) { if (side) detailsOpen = false }
        ShortcutHandler(enabled = side && selectedId != null) { action ->
            if (action == ShortcutAction.DISMISS) { selectedId = null; true } else false
        }
        val card: @Composable (LocalMediaTake, Modifier) -> Unit = { take, modifier ->
            GalleryTakeCard(take, settings, source, thumbnails, rememberGalleryFacts(take.primary, source, facts), badges(take),
                selected = side && take.id == selectedId,
                clickLabel = stringResource(if (side) R.string.media_action_details else take.kind.primaryActionLabel()),
                actions = actions(take), modifier = modifier) {
                // Beside an inspector a tap shows the take; on its own the grid plays it.
                if (side) selectedId = take.id else open(take.primary)
            }
        }
        val inspector: @Composable (Modifier) -> Unit = { modifier ->
            MediaInspectorPane(selectedTake, modifier) { take, contentModifier -> details(take, contentModifier, { selectedId = null }, false) }
        }
        when {
            panes != null -> with(density) {
                val grid = panes.preview
                val pane = panes.controls
                val gridWidth = grid.width.toDp().value
                GalleryGrid(Modifier.absoluteOffset(grid.left.toDp(), grid.top.toDp()).size(grid.width.toDp(), grid.height.toDp()),
                    galleryColumns(gridWidth, windowWidthClass(gridWidth), windowHeightClass(grid.height.toDp().value)),
                    gridState, groups, controls, status, card)
                inspector(Modifier.absoluteOffset(pane.left.toDp(), pane.top.toDp()).size(pane.width.toDp(), pane.height.toDp()).padding(12.dp))
            }
            side -> Row(Modifier.fillMaxSize()) {
                val inspectorWidth = mediaInspectorWidthDp(areaWidth)
                GalleryGrid(Modifier.weight(1f).fillMaxHeight(), galleryColumns(areaWidth - inspectorWidth, widthClass, heightClass),
                    gridState, groups, controls, status, card)
                inspector(Modifier.width(inspectorWidth.dp).padding(top = 12.dp, end = 12.dp, bottom = 12.dp))
            }
            else -> GalleryGrid(Modifier.fillMaxSize(), galleryColumns(areaWidth, widthClass, heightClass), gridState, groups,
                controls, status, card)
        }
        if (!side && detailsOpen) selectedTake?.let { take ->
            MediaInspectorSheet(take, mediaDetailsAsBottomSheet(widthClass, landscape), onDismiss = { detailsOpen = false }) { shown, modifier, close ->
                details(shown, modifier, close, true)
            }
        }
    }
}

/** Controls, then the takes under a header per shooting day, then the paging status. */
@Composable
private fun GalleryGrid(modifier: Modifier, columns: Int, state: LazyGridState, groups: List<Pair<LocalDate, List<LocalMediaTake>>>,
    controls: @Composable () -> Unit, status: @Composable () -> Unit, card: @Composable (LocalMediaTake, Modifier) -> Unit) {
    val entrance = rememberGridEntrance(state, columns) { groups.isNotEmpty() }
    LazyVerticalGrid(GridCells.Fixed(columns), modifier.testTag("gallery-list"), state,
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }, contentType = "controls") { controls() }
        // The controls are item 0; every header and take after it counts toward the entrance order.
        var index = 1
        for ((day, takes) in groups) {
            item(key = "day-$day-${takes.first().id}", span = { GridItemSpan(maxLineSpan) }, contentType = "day") { GalleryDayHeader(day) }
            val first = index + 1
            itemsIndexed(takes, key = { _, take -> take.id }, contentType = { _, _ -> "take" }) { offset, take ->
                card(take, Modifier.listEntrance(entrance, first + offset))
            }
            index = first + takes.size
        }
        item(key = "status", span = { GridItemSpan(maxLineSpan) }, contentType = "status") { status() }
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
