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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** No real media is deleted here. These tests exercise explicit consent, confirmed-work retirement,
 * honest results and requery. Separate MediaStore tests verify actual deletion and ownership. */
class MediaDeletionUiTest {
    @get:Rule val compose = createComposeRule()
    private val take = fixture()
    private val shown = mutableStateOf(true)
    private val calls = AtomicInteger()
    private val returned = AtomicInteger()
    private val refreshed = AtomicInteger()
    private var hold: Hold? = null
    private var read: (LocalMediaTake) -> MediaDeleteResult = { selected ->
        MediaDeleteResult((selected.originals + selected.metadata).map { MediaDeleteFileResult(it, MediaDeleteStatus.ABSENT_VERIFIED) })
    }
    private val source = MediaDeleteSource { selected ->
        calls.incrementAndGet()
        hold?.awaitRelease()
        try { read(selected) } finally { returned.incrementAndGet() }
    }

    @Test fun openingListsEveryOriginalAndMetadataButCancelNeverDeletes() {
        show()
        for ((index, artifact) in (take.originals + take.metadata).withIndex()) {
            reveal("member-$index-label").assertTextContains(artifact.name, substring = true)
            // Provider URIs are not operator language; nothing in the dialog spells one out.
            compose.onAllNodes(hasText(artifact.uri, substring = true), useUnmergedTree = true).assertCountEquals(0)
        }
        node("take-label").assertTextEquals(takeTitleText(InstrumentationRegistry.getInstrumentation().targetContext, take))
        node("confirm").assertIsNotEnabled()
        node("cancel").performClick()
        node("dialog").assertDoesNotExist()
        assertEquals(0, calls.get()); assertEquals(0, refreshed.get())
    }

    @Test fun acknowledgementIsMandatoryAndBusyDisablesCancellationAndDuplicateConfirmation() {
        val barrier = Hold(); hold = barrier
        try {
            show(); node("confirm").assertIsNotEnabled()
            reveal("acknowledge").performClick().assertIsOn()
            node("confirm").performClick()
            await { barrier.entered.count == 0L }
            node("confirm").assertIsNotEnabled(); node("cancel").assertIsNotEnabled()
            reveal("acknowledge").assertIsNotEnabled()
            reveal("busy").assertTextEquals(text(R.string.media_delete_busy))
            assertEquals(1, calls.get()); assertEquals(0, refreshed.get())
            barrier.release.countDown()
            awaitResult()
            reveal("result").assertTextEquals(text(R.string.media_delete_complete))
            node("confirm").assertIsNotEnabled(); node("cancel").assertIsEnabled()
            assertEquals(1, calls.get()); assertEquals(1, refreshed.get())
        } finally { barrier.release.countDown() }
    }

    @Test fun disposalDoesNotAbandonConfirmedDeletionAndNeverCallsRetiredScreen() {
        val barrier = Hold(); hold = barrier
        try {
            show(); confirm(); await { barrier.entered.count == 0L }
            compose.runOnIdle { shown.value = false }
            node("dialog").assertDoesNotExist()
            barrier.release.countDown()
            await { returned.get() == 1 }
            compose.waitForIdle()
            assertEquals(1, calls.get()); assertEquals(0, refreshed.get())
        } finally { barrier.release.countDown() }
    }

    @Test fun partialResultNamesAbsentRetainedAndUnattemptedMembersAndRefreshesOnce() {
        read = { selected ->
            MediaDeleteResult((selected.originals + selected.metadata).mapIndexed { index, artifact ->
                MediaDeleteFileResult(artifact, listOf(MediaDeleteStatus.ABSENT_VERIFIED, MediaDeleteStatus.RETAINED,
                    MediaDeleteStatus.NOT_ATTEMPTED)[index])
            }, "Fixture failure after one member")
        }
        show(); confirm(); awaitResult()
        reveal("result").assertTextEquals(text(R.string.media_delete_partial))
        for ((index, label) in listOf(R.string.media_delete_absent, R.string.media_delete_remaining, R.string.media_delete_not_attempted).withIndex()) {
            reveal("member-$index-status").assertTextEquals(text(label))
        }
        assertEquals(1, refreshed.get()); node("confirm").assertIsNotEnabled()
    }

    @Test fun rejectedPreflightNeverClaimsAbsentOrOffersBlindRetry() {
        read = { selected -> MediaDeleteResult((selected.originals + selected.metadata).map {
            MediaDeleteFileResult(it, MediaDeleteStatus.NOT_ATTEMPTED)
        }, "Fixture changed selection") }
        show(); confirm(); awaitResult()
        reveal("result").assertTextEquals(text(R.string.media_delete_rejected))
        for (index in 0..2) reveal("member-$index-status").assertTextEquals(text(R.string.media_delete_not_attempted))
        node("confirm").assertIsNotEnabled(); node("cancel").assertIsEnabled()
        assertEquals(1, refreshed.get())
    }

    @Test fun thrownIoReportsUnknownPerMemberAndStillRequestsCatalogRefresh() {
        read = { throw IOException("Fixture unknown outcome") }
        show(); confirm(); awaitResult()
        reveal("result").assertTextEquals(text(R.string.media_delete_unknown))
        for (index in 0..2) reveal("member-$index-status").assertTextEquals(text(R.string.media_delete_member_unknown))
        assertEquals(1, refreshed.get()); assertEquals(1, calls.get())
    }

    @Test fun incompleteResultCannotReportCompleteEvenIfReportedSubsetIsAbsent() {
        read = { selected -> MediaDeleteResult(listOf(MediaDeleteFileResult(selected.primary, MediaDeleteStatus.ABSENT_VERIFIED))) }
        show(); confirm(); awaitResult()
        reveal("result").assertTextEquals(text(R.string.media_delete_unknown))
        reveal("member-1-status").assertTextEquals(text(R.string.media_delete_member_unknown))
        assertEquals(1, refreshed.get())
    }

    @Test fun completionRequeriesCatalogRatherThanRemovingRowsOptimistically() {
        val barrier = Hold(); hold = barrier
        val generation = mutableIntStateOf(0)
        val dialog = mutableStateOf<LocalMediaTake?>(null)
        val pageCalls = AtomicInteger()
        val catalog = object : MediaCatalogSource {
            override suspend fun page(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int): LocalMediaPage {
                pageCalls.incrementAndGet()
                // Deliberately retained row proves that UI uses the provider result, not a local remove.
                return LocalMediaPage(listOf(take), null)
            }
            override suspend fun thumbnail(artifact: LocalMediaArtifact): Bitmap? = null
        }
        try {
            compose.setContent {
                MaterialTheme {
                    MediaCatalogContent(GallerySettings(), {}, catalog, onDelete = { dialog.value = it },
                        refreshGeneration = generation.intValue) {}
                    dialog.value?.let { MediaDeleteDialogContent(it, { dialog.value = null },
                        { refreshed.incrementAndGet(); generation.intValue++ }, source) }
                }
            }
            compose.galleryMenuAction(take.id, "delete")
            confirm(); await { barrier.entered.count == 0L }
            assertEquals(1, pageCalls.get()); assertEquals(0, refreshed.get())
            barrier.release.countDown(); awaitResult(); await { pageCalls.get() == 2 }
            node("cancel").performClick()
            compose.revealInGallery("gallery-name-${take.id}")
                .assertTextEquals(takeTitleText(InstrumentationRegistry.getInstrumentation().targetContext, take))
            assertEquals(1, refreshed.get())
        } finally { barrier.release.countDown() }
    }

    @Test fun doubleFontConfirmationAndAllFileLabelsAreUnclippedWithAccessibleTargets() {
        show(doubleFont = true)
        reveal("acknowledge").assertHeightIsAtLeast(48.dp)
        node("confirm").assertHeightIsAtLeast(48.dp)
        node("cancel").assertHeightIsAtLeast(48.dp)
        for (tag in listOf("help", "acknowledge-label") + (0..2).flatMap { listOf("member-$it-label") }) {
            unclipped(reveal(tag), tag)
        }
        unclipped(node("confirm-label"), "confirm-label")
        unclipped(node("cancel-label"), "cancel-label")
        assertEquals(0, calls.get())
    }

    private fun show(doubleFont: Boolean = false) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(if (doubleFont) 2f else 1f)) {
                MaterialTheme {
                    if (shown.value) MediaDeleteDialogContent(take, { shown.value = false }, { refreshed.incrementAndGet() }, source)
                }
            }
        }
    }
    private fun confirm() { reveal("acknowledge").performClick(); node("confirm").performClick() }
    private fun node(tag: String) = compose.onNodeWithTag("media-delete-$tag", useUnmergedTree = true)
    private fun reveal(tag: String) = node(tag).performScrollTo()
    private fun await(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 5000, condition = condition)
    private fun awaitResult() = await { compose.onAllNodesWithTag("media-delete-result").fetchSemanticsNodes().isNotEmpty() }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
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
            val jpeg = LocalMediaArtifact("content://media/external_primary/images/media/1", "capture.jpg", "image/jpeg", 100, 1700000000)
            val raw = LocalMediaArtifact("content://media/external_primary/images/media/2", "capture.dng", "image/x-adobe-dng", 200, 1700000000)
            val metadata = LocalMediaArtifact("content://media/external_primary/downloads/3", "capture.still.json", "application/json", 300, 1700000000)
            return LocalMediaTake("delete-fixture", jpeg, listOf(jpeg, raw), listOf(metadata), LocalMediaKind.PHOTO,
                ProductionSlateSettings(project = "Fixture"), LocalMediaRelationStatus.DECLARED)
        }
    }
}
