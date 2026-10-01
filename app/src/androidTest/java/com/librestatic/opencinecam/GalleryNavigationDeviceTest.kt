/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.LocalMediaRepository
import com.librestatic.opencinecam.storage.StillImageSaver
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real activity → media tab → MediaStore catalog → live shared settings, without a mock source. */
class GalleryNavigationDeviceTest {
    /** Starts on the camera: past the first-run wizard (OnboardingUiTest covers it) with the camera allowed. */
    @get:Rule(order = 0) val onboardingDone = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            OnboardingStore(instrumentation.targetContext).markCompleted()
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, android.Manifest.permission.CAMERA)
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun activityGalleryFindsSavedSlateAndFilterEditsPreserveCaptureIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SettingsRepositories.get(context)
        val before = repository.states.value
        val token = "Navigation-${UUID.randomUUID()}"
        val saved = ProductionSlateSettings(project = token, scene = "Gallery Ñ", takeNumber = 28, goodTake = true)
        val bitmap = Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        val bytes = try { java.io.ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,out));out.toByteArray() } }
            finally { bitmap.recycle() }
        val original = StillImageSaver(context).saveJpeg(bytes,saved)
        var related = emptyList<String>()
        try {
            val take = LocalMediaRepository(context).page(GallerySettings(),token).takes.single()
            related = take.metadata.map { it.uri }
            compose.runOnUiThread { repository.set(before.copy(audioEnabled = false, gallery = GallerySettings(), productionSlate = saved.copy(takeNumber = 29))) }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-action").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media-action").performClick()
            compose.onNodeWithTag("gallery-search").performTextReplacement(token)
            // The IME can leave only the controls composed. Scroll the LazyColumn to materialize
            // the actual result instead of waiting for an off-screen semantics node to appear.
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithTag("gallery-list").performScrollToKey(take.id) }.isSuccess
            }
            compose.onNodeWithTag("gallery-name-${take.id}",useUnmergedTree = true).assertTextEquals(take.primary.name)
            compose.onNodeWithTag("gallery-slate-${take.id}",useUnmergedTree = true).assertTextContains(token,substring = true)
            compose.onNodeWithTag("gallery-list").performScrollToKey("controls")
            compose.onNodeWithTag("gallery-filters").performClick()
            compose.onNodeWithTag("gallery-technical",useUnmergedTree = true).performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(repository.states.value.gallery.showTechnical)
                assertEquals(saved.copy(takeNumber = 29),repository.states.value.productionSlate)
                assertEquals(before.audioRecordingGain,repository.states.value.audioRecordingGain)
            }
            compose.onNodeWithTag("gallery-list").performScrollToKey(take.id)
            compose.onNodeWithTag("gallery-technical-primary-${take.id}",useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("gallery-primary-${take.id}",useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("media-playback-dialog",useUnmergedTree = true).assertExists()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-playback-frame",useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media-playback-close",useUnmergedTree = true).performClick()
            compose.onNodeWithTag("media-playback-dialog",useUnmergedTree = true).assertDoesNotExist()
            assertArrayEquals(bytes, context.contentResolver.openInputStream(original)!!.use { it.readBytes() })
            compose.onNodeWithTag("gallery-share-${take.id}",useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("media-share-dialog",useUnmergedTree = true).assertExists()
            compose.pickChoice("media-share-content-METADATA_ONLY")
            compose.pickChoice("media-share-metadata-PRODUCTION")
            compose.runOnIdle {
                assertEquals(MediaShareContent.METADATA_ONLY, repository.states.value.mediaSharing.content)
                assertEquals(MediaShareMetadata.PRODUCTION, repository.states.value.mediaSharing.metadata)
                assertEquals(saved.copy(takeNumber = 29),repository.states.value.productionSlate)
                assertTrue(repository.states.value.gallery.showTechnical)
            }
            compose.onNodeWithTag("media-share-cancel",useUnmergedTree = true).performClick()
            compose.onNodeWithTag("media-share-dialog",useUnmergedTree = true).assertDoesNotExist()
            assertArrayEquals(bytes, context.contentResolver.openInputStream(original)!!.use { it.readBytes() })
            // Rename through the real activity, preserving URI identity, original bytes and slate.
            val originalMetadata = related.associateWith { uri -> context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() } }
            compose.onNodeWithTag("gallery-rename-${take.id}",useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("media-rename-stem",useUnmergedTree = true).performTextReplacement("Vista previa")
            compose.onNodeWithTag("media-rename-cancel",useUnmergedTree = true).performClick()
            compose.onNodeWithTag("media-rename-dialog",useUnmergedTree = true).assertDoesNotExist()
            assertEquals(take, LocalMediaRepository(context).page(GallerySettings(),token).takes.single())
            for ((uri, content) in originalMetadata) assertArrayEquals(content, context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() })
            compose.onNodeWithTag("gallery-rename-${take.id}",useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("media-rename-stem",useUnmergedTree = true).performTextReplacement("Renamed café")
            compose.onNodeWithTag("media-rename-stem",useUnmergedTree = true).performImeAction()
            compose.onNodeWithTag("media-rename-confirm",useUnmergedTree = true).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-rename-result",useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media-rename-result",useUnmergedTree = true).performScrollTo().assertTextEquals(context.getString(R.string.media_rename_complete))
            val renamed = LocalMediaRepository(context).page(GallerySettings(),token).takes.single()
            assertEquals(take.id, renamed.id)
            assertEquals(take.primary.uri, renamed.primary.uri)
            assertEquals(take.slate, renamed.slate)
            assertEquals(take.relationStatus, renamed.relationStatus)
            assertEquals("Renamed café.jpg", renamed.primary.name)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(original)!!.use { it.readBytes() })
            compose.onNodeWithTag("media-rename-cancel",useUnmergedTree = true).performClick()
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithTag("gallery-list").performScrollToKey(take.id)
                    compose.onNodeWithTag("gallery-name-${take.id}",useUnmergedTree = true).assertTextEquals(renamed.primary.name) }.isSuccess
            }
            // Real activity confirmation drives production deleter and the subsequent catalog query.
            compose.onNodeWithTag("gallery-delete-${take.id}",useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("media-delete-confirm",useUnmergedTree = true).assertIsNotEnabled()
            compose.onNodeWithTag("media-delete-cancel",useUnmergedTree = true).performClick()
            compose.onNodeWithTag("media-delete-dialog",useUnmergedTree = true).assertDoesNotExist()
            assertArrayEquals(bytes, context.contentResolver.openInputStream(original)!!.use { it.readBytes() })
            compose.onNodeWithTag("gallery-delete-${take.id}",useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("media-delete-acknowledge",useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("media-delete-confirm",useUnmergedTree = true).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-delete-result",useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media-delete-result",useUnmergedTree = true).performScrollTo().assertTextEquals(context.getString(R.string.media_delete_complete))
            for (uri in related.map(Uri::parse)+original) context.contentResolver.query(uri,arrayOf("_id"),null,null,null)!!.use { assertFalse(it.moveToFirst()) }
            compose.onNodeWithTag("media-delete-cancel",useUnmergedTree = true).performClick()
            compose.onNodeWithTag("media-delete-dialog",useUnmergedTree = true).assertDoesNotExist()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("gallery-loading").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("gallery-take-${take.id}").assertDoesNotExist()
            assertTrue(LocalMediaRepository(context).page(GallerySettings(),token).takes.isEmpty())
            compose.runOnIdle {
                assertEquals(saved.copy(takeNumber = 29),repository.states.value.productionSlate)
                assertEquals(before.audioRecordingGain,repository.states.value.audioRecordingGain)
            }
        } finally {
            (related.map(Uri::parse)+original).forEach { uri ->
                val remains=context.contentResolver.query(uri,arrayOf("_id"),null,null,null)!!.use { it.moveToFirst() }
                if(remains) assertEquals(1,context.contentResolver.delete(uri,null,null))
            }
            compose.runOnUiThread { repository.set(before) }
        }
    }
}
