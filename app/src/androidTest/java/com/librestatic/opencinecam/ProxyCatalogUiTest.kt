/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual orphaned derivative discovery. Share affordance only: no external chooser is launched. */
class ProxyCatalogUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val video get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val metadata get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Test fun deletedOriginalLeavesHistoricalProxyAccessibleFromEmptyGalleryAcrossRefreshAndReopen() = runBlocking<Unit> {
        val token = "proxy-catalog-ui-${UUID.randomUUID()}"
        val proxyId = UUID.randomUUID().toString()
        val sourceBytes = instrumentation.context.assets.open("e1-video.mp4").use { it.readBytes() }
        assertEquals(5404, sourceBytes.size)
        assertEquals("a32963539eefe4fc0d14e76770d51e49c25108a31b9eda9176a022a94a9a99b6", hash(sourceBytes))
        val original = requireNotNull(resolver.insert(video, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/OpenCineCam/")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val visible = mutableStateOf(true)
        val repository = MediaProxyRepository(context)
        val policiesBefore = ProxyPolicies.get(context).states.value
        val settingsBefore = SettingsRepositories.get(context).states.value
        var take: LocalMediaTake? = null
        var mounted = false
        try {
            resolver.openOutputStream(original, "w")!!.use { it.write(sourceBytes) }
            assertEquals(1, resolver.update(original, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            val selected = withTimeout(10_000) {
                var found: LocalMediaTake? = null
                while (found == null) {
                    found = LocalMediaRepository(context).page(GallerySettings(), token).takes.singleOrNull()
                        ?.takeIf { it.primary.sizeBytes == sourceBytes.size.toLong() }
                    if (found == null) delay(25)
                }
                found
            }
            take = selected
            val queue = MediaProxyQueue.get(context)
            withTimeout(20_000) { queue.states.first { it.loaded || it.error != null } }.also { assertNull(it.error) }
            val proxy = repository.create(selected, ProxySettings(640, 1), proxyId)
            assertNull(queue.states.value.jobs.firstOrNull { it.take.id == selected.id })
            assertArrayEquals(sourceBytes, read(original))
            val proxyBytes = read(proxy.proxyUri.toUri())
            val metadataBytes = read(proxy.metadataUri.toUri())
            val receipt = receipt(selected)
            val receiptBytes = receipt.openRead().use { it.readBytes() }
            assertArrayEquals(metadataBytes, receiptBytes)
            val before = repository.catalog().single { it.takeId == selected.id }
            assertEquals(selected.primary.name, before.originalDisplayName)
            assertEquals(proxy, before.result)
            assertEquals(hash(receiptBytes), before.receiptSha256)

            // Deliberate source-only deletion through the same production ownership boundary as UI.
            val deletion = withContext(Dispatchers.IO) { MediaTakeDeleter(context).delete(selected) }
            assertTrue("Original deletion: $deletion", deletion.complete)
            assertAbsent(original)
            assertTrue(LocalMediaRepository(context).page(GallerySettings(), token).takes.isEmpty())
            assertEquals(before, repository.catalog().single { it.takeId == selected.id })
            assertArrayEquals(proxyBytes, read(proxy.proxyUri.toUri()))
            assertArrayEquals(metadataBytes, read(proxy.metadataUri.toUri()))
            assertArrayEquals(receiptBytes, receipt.openRead().use { it.readBytes() })

            compose.setContent { MaterialTheme {
                if (visible.value) MediaCatalogScreen(GallerySettings(),
                    onSettings = { error("Catalog browsing must not alter gallery settings") },
                    proxySettings = ProxySettings(640, 1),
                    onProxySettings = { error("Catalog browsing must not alter proxy settings") })
            } }
            mounted = true
            // Empty is scoped to this deleted fixture, preserving unrelated user media.
            node("gallery-search").performScrollTo().performTextInput(token)
            node("gallery-list").performScrollToKey("status")
            compose.waitUntil(20_000) { exists("gallery-empty") || exists("gallery-error") }
            node("gallery-error").assertDoesNotExist()
            node("gallery-empty").assertIsDisplayed()
            openCatalog()
            assertCatalogEntry(selected.id, proxyId, proxy.proxyDisplayName, selected.primary.name)

            node("proxy-catalog-refresh").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
            assertCatalogEntry(selected.id, proxyId, proxy.proxyDisplayName, selected.primary.name)
            node("proxy-catalog-close").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
            node("proxy-catalog-dialog").assertDoesNotExist()
            openCatalog()
            assertCatalogEntry(selected.id, proxyId, proxy.proxyDisplayName, selected.primary.name)
            assertEquals(before, repository.catalog().single { it.takeId == selected.id })
            assertArrayEquals(proxyBytes, read(proxy.proxyUri.toUri()))
            assertArrayEquals(metadataBytes, read(proxy.metadataUri.toUri()))
            assertArrayEquals(receiptBytes, receipt.openRead().use { it.readBytes() })
            assertAbsent(original)
            assertNull(queue.states.value.jobs.firstOrNull { it.take.id == selected.id })
            assertEquals(policiesBefore, ProxyPolicies.get(context).states.value)
            assertEquals(settingsBefore, SettingsRepositories.get(context).states.value)
            node("proxy-catalog-close").performClick()
            node("proxy-catalog-dialog").assertDoesNotExist()
            Log.i("E17ProxyCatalogUi", "PASS proxyId=$proxyId takeId=${selected.id} originalAbsent=true historicalName=${selected.primary.name} emptyScopedGallery=true shareEnabled48dp=true refreshedAndReopened=true unchangedPairAndReceipt=true noChooser=true")
        } finally {
            withContext(NonCancellable) {
                if (mounted) {
                    if (exists("proxy-catalog-dialog")) awaitCatalogIdle()
                    compose.runOnIdle { visible.value = false }
                    compose.waitForIdle()
                }
                ownedRows(proxyId).forEach(::deleteOwned)
                assertTrue(ownedRows(proxyId).isEmpty())
                take?.let { receipt(it).delete() }
                val candidate = File(context.cacheDir, "proxy-$proxyId.mp4")
                check(!candidate.exists() || candidate.delete())
                deleteOwned(original)
            }
        }
    }

    private fun openCatalog() {
        node("gallery-list").performScrollToKey("controls")
        node("gallery-proxy-catalog").performScrollTo().assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        node("proxy-catalog-dialog").assertExists()
    }
    private fun awaitCatalogIdle() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("proxy-catalog-refresh", true).fetchSemanticsNodes()
                .any { !it.config.contains(SemanticsProperties.Disabled) }
        }
    }
    private fun assertCatalogEntry(takeId: String, proxyId: String, proxyName: String, historicalName: String) {
        awaitCatalogIdle()
        node("proxy-catalog-error").assertDoesNotExist()
        node("proxy-catalog-list").performScrollToKey(takeId)
        node("proxy-catalog-entry-$proxyId").assertExists()
        compose.onNode(hasText(proxyName) and hasAnyAncestor(hasTestTag("proxy-catalog-entry-$proxyId")), true).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.proxy_catalog_original, historicalName)) and
            hasAnyAncestor(hasTestTag("proxy-catalog-entry-$proxyId")), true).assertIsDisplayed()
        node("proxy-catalog-share-$proxyId").performScrollTo().assertIsEnabled().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }
    private fun node(tag: String) = compose.onNodeWithTag(tag, true)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, true).fetchSemanticsNodes().isNotEmpty()
    private fun read(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).proxyHex()
    private fun receipt(take: LocalMediaTake) = AtomicFile(File(context.filesDir, "media-proxies/${hash(take.id.toByteArray())}.json"))
    private fun assertAbsent(uri: Uri) {
        @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(uri)
        resolver.query(all, arrayOf("_id"), null, null, null)!!.use { assertEquals(0, it.count) }
    }
    private fun ownedRows(id: String): List<Uri> = listOf(video to "Movies", metadata to "Download").flatMap { (collection, root) ->
        @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
        resolver.query(all, arrayOf("_id"), "relative_path = ? AND owner_package_name = ?",
            arrayOf("$root/OpenCineCamProxies/$id/", context.packageName), null)!!.use { cursor ->
            assertTrue(cursor.count <= 1)
            buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }
    }
    private fun deleteOwned(uri: Uri) {
        val identity = requireNotNull(mediaDeleteIdentity(uri.toString()))
        val collection = if (identity.collection == MediaDeleteCollection.VIDEO) video else metadata
        assertTrue(resolver.delete(collection, "_id = ? AND owner_package_name = ?",
            arrayOf(identity.id.toString(), context.packageName)) in 0..1)
    }
}
