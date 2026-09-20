/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the real pure preview and injected write boundary. Actual MediaStore bytes, compensation
 * and pending-transfer holds are verified independently; these tests never rename real files. */
class MediaRenamingUiTest {
    @get:Rule val compose = createComposeRule()
    private val take = fixture()
    private val shown = mutableStateOf(true)
    private val calls = AtomicInteger()
    private val returned = AtomicInteger()
    private val refreshed = AtomicInteger()
    private val stems = CopyOnWriteArrayList<String>()
    private var hold: Hold? = null
    private var outcome: (LocalMediaTake, String) -> MediaRenameResult = { selected, stem ->
        MediaRenameResult(mediaRenamePreview(selected, stem).files.map {
            MediaRenameFileResult(it.artifact, it.newName, it.newName, MediaRenameStatus.RENAMED)
        })
    }
    private val source = MediaRenameSource { selected, stem ->
        calls.incrementAndGet(); stems += stem; hold?.awaitRelease()
        try { outcome(selected, stem) } finally { returned.incrementAndGet() }
    }

    @Test fun editingShowsExactPreviewForEveryFileAndCancelNeverWrites() {
        show(); node("confirm").assertIsNotEnabled()
        enter("Scene_12")
        val preview = mediaRenamePreview(take, "Scene_12")
        for ((index, artifact) in (take.originals + take.metadata).withIndex()) {
            reveal("member-$index-old").assertTextEquals(text(R.string.media_rename_old, artifact.name))
            reveal("member-$index-uri").assertTextEquals(artifact.uri)
            reveal("member-$index-new").assertTextEquals(text(R.string.media_rename_new, preview.files.single { it.artifact == artifact }.newName))
        }
        reveal("remote-warning").assertTextEquals(text(R.string.media_rename_remote_warning))
        node("confirm").assertIsEnabled(); node("cancel").performClick()
        node("dialog").assertDoesNotExist(); assertEquals(0, calls.get()); assertEquals(0, refreshed.get())
    }

    @Test fun invalidOrExcessiveInputNeverConfirmsOrSilentlyTruncatesToAcceptedName() {
        show()
        for (invalid in listOf("", "../escape", "a".repeat(129), "a".repeat(513))) {
            enter(invalid)
            node("confirm").assertIsNotEnabled(); reveal("invalid").assertExists()
        }
        enter("Valid"); node("confirm").assertIsEnabled()
        enter("b".repeat(513)); node("confirm").assertIsNotEnabled()
        node("stem").assertTextContains("Valid")
        node("stem").performImeAction()
        node("confirm").assertIsNotEnabled(); reveal("invalid").assertExists()
        enter("Recovered"); node("confirm").assertIsEnabled()
        assertEquals(0, calls.get())
    }

    @Test fun confirmationFreezesExactStemAndBlocksDuplicateWritesOrFakeCancellation() {
        val barrier = Hold(); hold = barrier
        try {
            show(); enter("Scene_12"); node("confirm").performClick()
            await { barrier.entered.count == 0L }
            node("confirm").assertIsNotEnabled(); node("cancel").assertIsNotEnabled()
            reveal("stem").assertIsNotEnabled()
            reveal("busy").assertTextEquals(text(R.string.media_rename_busy))
            assertEquals(listOf("Scene_12"), stems.toList()); assertEquals(0, refreshed.get())
            barrier.release.countDown(); awaitResult()
            reveal("result").assertTextEquals(text(R.string.media_rename_complete))
            node("confirm").assertIsNotEnabled(); node("cancel").assertIsEnabled()
            assertEquals(1, calls.get()); assertEquals(1, refreshed.get())
        } finally { barrier.release.countDown() }
    }

    @Test fun disposalDoesNotAbandonConfirmedIoOrPublishLateCallbacks() {
        val barrier = Hold(); hold = barrier
        try {
            show(); enter("Scene_12"); node("confirm").performClick(); await { barrier.entered.count == 0L }
            compose.runOnIdle { shown.value = false }
            node("dialog").assertDoesNotExist()
            barrier.release.countDown(); await { returned.get() == 1 }; compose.waitForIdle()
            assertEquals(1, calls.get()); assertEquals(0, refreshed.get())
        } finally { barrier.release.countDown() }
    }

    @Test fun verifiedCompensationReportsRestoredNamesAndDoesNotOfferBlindRetry() {
        outcome = { selected, stem -> MediaRenameResult(mediaRenamePreview(selected, stem).files.map {
            MediaRenameFileResult(it.artifact, it.newName, it.artifact.name, MediaRenameStatus.RESTORED)
        }, error = "Fixture write failed", compensationAttempted = true, compensationComplete = true) }
        show(); enter("Scene_12"); node("confirm").performClick(); awaitResult()
        reveal("result").assertTextEquals(text(R.string.media_rename_compensated))
        for ((index, artifact) in (take.originals + take.metadata).withIndex()) {
            reveal("member-$index-current").assertTextEquals(text(R.string.media_rename_current, artifact.name))
            reveal("member-$index-status").assertTextEquals(text(R.string.media_rename_status_restored))
        }
        node("confirm").assertIsNotEnabled(); assertEquals(1, refreshed.get())
    }

    @Test fun partialAndUnknownMemberResultsNeverClaimCompleteOrRestored() {
        outcome = { selected, stem -> MediaRenameResult(mediaRenamePreview(selected, stem).files.mapIndexed { index, target ->
            MediaRenameFileResult(target.artifact, target.newName, if (index == 0) target.newName else null,
                if (index == 0) MediaRenameStatus.PARTIAL else MediaRenameStatus.UNKNOWN)
        }, error = "Fixture compensation incomplete", compensationAttempted = true) }
        show(); enter("Scene_12"); node("confirm").performClick(); awaitResult()
        reveal("result").assertTextEquals(text(R.string.media_rename_partial))
        reveal("member-1-current").assertTextEquals(text(R.string.media_rename_unknown_name))
        reveal("member-1-status").assertTextEquals(text(R.string.media_rename_status_unknown))
        assertEquals(1, refreshed.get())
    }

    @Test fun thrownIoIsUnknownAndRequestsRefreshWithoutInventingCurrentNames() {
        outcome = { _, _ -> throw IOException("Fixture unknown result") }
        show(); enter("Scene_12"); node("confirm").performClick(); awaitResult()
        reveal("result").assertTextEquals(text(R.string.media_rename_unknown))
        for (index in 0..2) reveal("member-$index-current").assertTextEquals(text(R.string.media_rename_unknown_name))
        assertEquals(1, refreshed.get()); node("confirm").assertIsNotEnabled()
    }

    @Test fun completedOperationRequeriesCatalogRatherThanReplacingNamesOptimistically() {
        val barrier = Hold(); hold = barrier
        val generation = mutableIntStateOf(0)
        val dialog = mutableStateOf<LocalMediaTake?>(null)
        val pageCalls = AtomicInteger()
        val catalog = object : MediaCatalogSource {
            override suspend fun page(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int): LocalMediaPage {
                pageCalls.incrementAndGet()
                // Deliberately unchanged response proves that the screen trusts the requery only.
                return LocalMediaPage(listOf(take), null)
            }
            override suspend fun thumbnail(artifact: LocalMediaArtifact): Bitmap? = null
        }
        try {
            compose.setContent { MaterialTheme {
                MediaCatalogContent(GallerySettings(), {}, catalog, refreshGeneration = generation.intValue,
                    onRename = { dialog.value = it }) {}
                dialog.value?.let { MediaRenameDialogContent(it, { dialog.value = null },
                    { refreshed.incrementAndGet(); generation.intValue++ }, source) }
            } }
            compose.onNodeWithTag("gallery-list").performScrollToNode(hasTestTag("gallery-rename-${take.id}"))
            compose.onNodeWithTag("gallery-rename-${take.id}").performClick()
            enter("Scene_12"); node("confirm").performClick(); await { barrier.entered.count == 0L }
            assertEquals(1, pageCalls.get()); assertEquals(0, refreshed.get())
            barrier.release.countDown(); awaitResult(); await { pageCalls.get() == 2 }
            node("cancel").performClick()
            compose.onNodeWithTag("gallery-list").performScrollToNode(hasTestTag("gallery-name-${take.id}"))
            compose.onNodeWithTag("gallery-name-${take.id}").assertTextEquals(take.primary.name)
            assertEquals(1, refreshed.get())
        } finally { barrier.release.countDown() }
    }

    @Test fun doubleFontFieldButtonsWarningAndNamesRemainAccessibleAndUnclipped() {
        show(doubleFont = true); enter("Scene_12")
        reveal("stem").assertHeightIsAtLeast(48.dp)
        node("confirm").assertHeightIsAtLeast(48.dp); node("cancel").assertHeightIsAtLeast(48.dp)
        for (tag in listOf("help", "remote-warning", "stem-label") + (0..2).flatMap { listOf("member-$it-old", "member-$it-new", "member-$it-uri") }) {
            unclipped(reveal(tag), tag)
        }
        unclipped(node("confirm-label"), "confirm-label"); unclipped(node("cancel-label"), "cancel-label")
        assertEquals(0, calls.get())
    }

    private fun show(doubleFont: Boolean = false) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(if (doubleFont) 2f else 1f)) { MaterialTheme {
                if (shown.value) MediaRenameDialogContent(take, { shown.value = false }, { refreshed.incrementAndGet() }, source)
            } }
        }
    }
    private fun enter(value: String) { reveal("stem").performTextReplacement(value); node("stem").performImeAction() }
    private fun node(tag: String) = compose.onNodeWithTag("media-rename-$tag", useUnmergedTree = true)
    private fun reveal(tag: String) = node(tag).performScrollTo()
    private fun await(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 5000, condition = condition)
    private fun awaitResult() = await { compose.onAllNodesWithTag("media-rename-result").fetchSemanticsNodes().isNotEmpty() }
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    private fun unclipped(node: SemanticsNodeInteraction, tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        assertEquals(1, results.size)
        assertFalse("Overflow: $tag ${results.single().size}", results.single().hasVisualOverflow)
    }
    private class Hold {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun awaitRelease() { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) { "Fixture release timed out" } }
    }
    companion object {
        private fun fixture(): LocalMediaTake {
            val id = "7ba4ad1a-6a75-48e5-91ee-45732830f57b"
            val jpeg = LocalMediaArtifact("content://media/external_primary/images/media/1", "OCC_$id.jpg", "image/jpeg", 100, 1700000000)
            val raw = LocalMediaArtifact("content://media/external_primary/images/media/2", "OCC_$id.dng", "image/x-adobe-dng", 200, 1700000000)
            val metadata = LocalMediaArtifact("content://media/external_primary/downloads/3", "OCC_$id.still.json", "application/json", 300, 1700000000)
            return LocalMediaTake("still:$id", jpeg, listOf(jpeg, raw), listOf(metadata), LocalMediaKind.PHOTO,
                ProductionSlateSettings(project = "Fixture"), LocalMediaRelationStatus.DECLARED)
        }
    }
}
