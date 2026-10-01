/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Injected pages test UI concurrency and actions. Real MediaStore relations have separate device tests. */
class MediaCatalogUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings = mutableStateOf(GallerySettings())
    private val opened = mutableListOf<LocalMediaArtifact>()
    private val pageCalls = mutableListOf<Pair<String, LocalMediaCursor?>>()
    private var thumbnails = 0
    private var failOpen = false
    private var read: suspend (GallerySettings, String, LocalMediaCursor?) -> LocalMediaPage = { _, _, _ -> LocalMediaPage(emptyList(), null) }
    private val source = object : MediaCatalogSource {
        override suspend fun page(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int): LocalMediaPage {
            assertEquals(60, limit); pageCalls += query to cursor
            return read(settings, query, cursor)
        }
        override suspend fun thumbnail(artifact: LocalMediaArtifact): Bitmap? { thumbnails++; return null }
    }
    private fun artifact(id: Int, name: String = "take$id.jpg", mime: String = "image/jpeg") =
        LocalMediaArtifact("content://media/external_primary/images/media/$id", name, mime, 100L + id, 1700000000L)
    private fun take(id: String, status: LocalMediaRelationStatus = LocalMediaRelationStatus.DECLARED): LocalMediaTake {
        val image = artifact(if (id == "one") 1 else 2)
        return LocalMediaTake(id, image, listOf(image), emptyList(), LocalMediaKind.PHOTO,
            ProductionSlateSettings(project = "Project", scene = "Scene", takeNumber = 7, goodTake = true,
                location = ProductionSlateLocation.INTERIOR, timeOfDay = ProductionSlateTimeOfDay.NIGHT), status)
    }
    private fun cursor(index: Long, settings: GallerySettings, query: String) = LocalMediaCursor(catalogFilter(settings, query),
        mapOf(LocalMediaKind.PHOTO to MediaPosition(1000 - index, index)), emptySet())

    @Test fun emptyFilteredPagesContinueAutomaticallyAndPresentationFlagsDoNotReread() {
        var pages = 0
        read = { value, query, _ -> pages++; if (pages < 3) LocalMediaPage(emptyList(), cursor(pages.toLong(), value, query))
            else LocalMediaPage(listOf(take("one")), null) }
        settings.value = settings.value.copy(autoThumbnails = false)
        show()
        reveal("name-one").assertTextEquals("take1.jpg")
        compose.runOnIdle { assertEquals(3, pages); assertEquals(0, thumbnails) }
        reveal("filters").performClick()
        reveal("slate").performClick()
        reveal("technical").performClick()
        compose.runOnIdle { assertEquals(3, pages); assertFalse(settings.value.showSlate); assertTrue(settings.value.showTechnical) }
        node("slate-one").assertDoesNotExist()
        reveal("technical-primary-one").assertTextEquals(text(R.string.gallery_technical, "image/jpeg", 101L, 1700000000L))
        reveal("end").assertTextEquals(text(R.string.gallery_no_more))
    }

    @Test fun failuresRemainErrorsAndRetryPreservesPreviousResultsAndContinuation() {
        var calls = 0
        var expectedRetry: LocalMediaCursor? = null
        read = { value, query, previous ->
            calls++
            when (calls) {
                1 -> LocalMediaPage(listOf(take("one")), cursor(1, value, query))
                2 -> throw java.io.IOException("Fixture provider failed").also { expectedRetry = previous }
                else -> { assertSame(expectedRetry, previous); LocalMediaPage(listOf(take("two")), null) }
            }
        }
        show()
        reveal("more").performClick()
        reveal("error").assertTextEquals(text(R.string.gallery_load_failed))
        node("empty").assertDoesNotExist()
        reveal("name-one").assertTextEquals("take1.jpg")
        reveal("retry").performClick()
        reveal("name-two").assertTextEquals("take2.jpg")
        reveal("name-one").assertTextEquals("take1.jpg")
        compose.runOnIdle { assertEquals(3, calls) }
    }

    @Test fun cancelledOldSearchCannotReplaceNewResultsAndOverlongQueryIsNotSubmitted() {
        val oldEntered = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        read = { _, query, _ ->
            if (query == "old") withContext(NonCancellable) { oldEntered.complete(Unit); releaseOld.await() }
            LocalMediaPage(if (query == "new") listOf(take("two")) else listOf(take("one")), null)
        }
        try {
            show()
            reveal("search").performTextReplacement("old")
            compose.waitUntil(5000) { oldEntered.isCompleted }
            reveal("search").performTextReplacement("new")
            reveal("name-two").assertTextEquals("take2.jpg")
            compose.runOnIdle { releaseOld.complete(Unit) }
            compose.waitForIdle()
            reveal("name-two").assertTextEquals("take2.jpg")
            node("name-one").assertDoesNotExist()
            reveal("search").performTextReplacement("x".repeat(129))
            reveal("search-invalid").assertTextEquals(text(R.string.gallery_search_invalid))
            compose.runOnIdle { assertFalse(pageCalls.any { it.first.length > 128 }) }
        } finally { releaseOld.complete(Unit) }
    }

    @Test fun everyOriginalAndMetadataOpenExplicitlyAndThumbnailNeverLoadsEagerly() {
        val first = take("one")
        val second = artifact(3, "pair.dng", "image/x-adobe-dng")
        val metadata = artifact(4, "relation.json", "application/json")
        val group = first.copy(originals = listOf(first.primary, second), metadata = listOf(metadata))
        read = { _, _, _ -> LocalMediaPage(listOf(group), null) }
        // With automatic thumbnails off, nothing is decoded until the operator asks for it.
        settings.value = settings.value.copy(autoThumbnails = false)
        show()
        reveal("scene-conditions-one").assertTextEquals(text(R.string.gallery_scene_conditions,
            text(R.string.production_slate_interior), text(R.string.production_slate_night)))
        compose.runOnIdle { assertEquals(0, thumbnails); assertTrue(opened.isEmpty()) }
        reveal("files-one").performClick()
        for (file in group.originals + group.metadata) reveal("open-${file.uri}").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(group.originals + group.metadata, opened) }
        reveal("thumbnail-load-one").performClick()
        reveal("thumbnail-error-one").assertTextEquals(text(R.string.gallery_thumbnail_unavailable))
        compose.runOnIdle { assertEquals(1, thumbnails); failOpen = true }
        reveal("primary-one").performClick()
        reveal("open-failed").assertTextEquals(text(R.string.gallery_open_failed))
    }

    @Test fun automaticThumbnailsLoadOncePerVisibleTake() {
        read = { _, _, _ -> LocalMediaPage(listOf(take("one")), null) }
        show()
        reveal("name-one").assertTextEquals("take1.jpg")
        compose.waitUntil(5000) { thumbnails == 1 }
        // The fixture has no picture, so the take says so instead of offering a load button.
        reveal("thumbnail-error-one").assertTextEquals(text(R.string.gallery_thumbnail_unavailable))
        compose.runOnIdle { assertEquals(1, thumbnails) }
    }

    @Test fun allRelationStatusesAndEmptyStateAreExplicitWithoutHashVerificationClaims() {
        val labels = listOf(LocalMediaRelationStatus.DECLARED to R.string.gallery_declared,
            LocalMediaRelationStatus.LEGACY to R.string.gallery_legacy,
            LocalMediaRelationStatus.MISSING_METADATA to R.string.gallery_missing,
            LocalMediaRelationStatus.INVALID_METADATA to R.string.gallery_invalid,
            LocalMediaRelationStatus.INCOMPLETE to R.string.gallery_incomplete)
        var status: LocalMediaRelationStatus? = labels.first().first
        read = { _, _, _ -> LocalMediaPage(status?.let { listOf(take("one", it)) }.orEmpty(), null) }
        show()
        for ((value, label) in labels) {
            compose.runOnIdle { status = value }
            reveal("refresh").performClick()
            reveal("relation-one").assertTextEquals(text(label))
        }
        compose.runOnIdle { status = null }
        reveal("refresh").performClick()
        reveal("empty").assertTextEquals(text(R.string.gallery_empty))
        node("error").assertDoesNotExist()
    }

    @Test fun doubleFontFilterControlsWrapAndEverySettingCanBeChanged() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).height(420.dp).verticalScroll(rememberScrollState())) {
                        GallerySettingsControls(settings.value, { settings.value = it })
                    }
                }
            }
        }
        val kinds = GalleryMediaKind.entries.map { "kind-$it" }
        for (tag in kinds + listOf("newest", "good", "slate", "technical")) {
            node(tag).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        }
        compose.runOnIdle { assertEquals(GallerySettings(GalleryMediaKind.AUDIO, false, true, false, true), settings.value) }
        node("settings-help-toggle").performScrollTo().performClick()
        for (tag in listOf("settings-help", "newest-label", "good-label", "slate-label", "technical-label") + kinds.map { "$it-label" }) {
            val results = mutableListOf<TextLayoutResult>()
            node(tag).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
            assertEquals(1, results.size)
            assertFalse("Overflow: $tag ${results.single().size}", results.single().hasVisualOverflow)
        }
    }
    private fun show() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).height(600.dp)) {
                    MediaCatalogContent(settings.value, { settings.value = it }, source) {
                        if (failOpen) throw IllegalStateException("No fixture viewer") else opened += it
                    }
                }
            }
        }
    }
    private fun node(tag: String) = compose.settingsNode("gallery-$tag", Regex("^kind-[A-Z_]+(-label)?$").matches(tag))
    private fun reveal(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("gallery-list").performScrollToNode(hasTestTag("gallery-$tag"))
        return node(tag)
    }
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
}
