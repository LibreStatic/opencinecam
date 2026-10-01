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
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real catalog rename/deletion after source deletion; external open/chooser delivery is not exercised. */
class ProxyCatalogActionsUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val video get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val metadata get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Test fun orphanedProxyRenameNormalizesNameAndConfirmedDeleteRetiresOnlyDerivativePair() = runBlocking<Unit> {
        val token = "proxy-catalog-actions-ui-${UUID.randomUUID()}"
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

            val stem = "Edicio\u0301n proxy-${proxyId.take(8)}"
            val expectedName = "Edici\u00f3n proxy-${proxyId.take(8)}.mp4"
            compose.setContent { MaterialTheme {
                if (visible.value) ProxyCatalogDialog(onDismiss = { visible.value = false })
            } }
            mounted = true
            awaitIdle()
            node("proxy-catalog-error").assertDoesNotExist()
            node("proxy-catalog-list").performScrollToKey(selected.id)
            action("open", proxyId).performScrollTo().assertIsEnabled().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)

            action("rename", proxyId).performScrollTo().assertIsEnabled().performClick()
            action("rename-stem", proxyId).performScrollTo().performTextReplacement("/")
            action("rename-confirm", proxyId).performScrollTo().assertIsNotEnabled()
            action("rename-preview", proxyId).assertDoesNotExist()
            action("rename-stem", proxyId).performScrollTo().performTextReplacement(stem)
            action("rename-preview", proxyId).performScrollTo().assertTextEquals(expectedName)
            action("rename-confirm", proxyId).performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            action("rename-cancel", proxyId).performScrollTo().performClick()
            action("rename-stem", proxyId).assertDoesNotExist()
            assertEquals(before, repository.catalog().single { it.takeId == selected.id })
            assertArrayEquals(proxyBytes, read(proxy.proxyUri.toUri()))
            assertArrayEquals(metadataBytes, read(proxy.metadataUri.toUri()))
            assertArrayEquals(receiptBytes, receipt.openRead().use { it.readBytes() })

            action("rename", proxyId).performScrollTo().performClick()
            action("rename-stem", proxyId).performScrollTo().performTextReplacement(stem)
            action("rename-confirm", proxyId).performScrollTo().assertIsEnabled().performClick()
            awaitIdle()
            node("proxy-catalog-error").assertDoesNotExist()
            action("rename-stem", proxyId).assertDoesNotExist()
            node("proxy-catalog-list").performScrollToKey(selected.id)
            compose.onNode(hasText(expectedName) and hasAnyAncestor(hasTestTag("proxy-catalog-entry-$proxyId")), true)
                .assertIsDisplayed()
            val renamed = repository.catalog().single { it.takeId == selected.id }
            assertEquals(proxy.copy(proxyDisplayName = expectedName), renamed.result)
            assertEquals(before.takeId, renamed.takeId)
            assertEquals(before.originalDisplayName, renamed.originalDisplayName)
            assertEquals(expectedName, displayName(proxy.proxyUri.toUri()))
            assertArrayEquals(proxyBytes, read(proxy.proxyUri.toUri()))
            val renamedMetadata = read(proxy.metadataUri.toUri())
            assertArrayEquals(renamedMetadata, receipt.openRead().use { it.readBytes() })
            assertEquals(hash(renamedMetadata), renamed.receiptSha256)
            assertEquals(JsonObject(Json.parseToJsonElement(metadataBytes.toString(Charsets.UTF_8)).jsonObject +
                ("proxyDisplayName" to JsonPrimitive(expectedName))),
                Json.parseToJsonElement(renamedMetadata.toString(Charsets.UTF_8)))
            assertAbsent(original)

            action("delete", proxyId).performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
            action("delete-confirm", proxyId).performScrollTo().assertIsNotEnabled()
            action("delete-ack", proxyId).performScrollTo().assertIsOff().assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp).performClick()
            action("delete-confirm", proxyId).performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            action("delete-cancel", proxyId).performScrollTo().performClick()
            action("delete-confirm", proxyId).assertDoesNotExist()
            assertEquals(renamed, repository.catalog().single { it.takeId == selected.id })
            assertArrayEquals(proxyBytes, read(proxy.proxyUri.toUri()))
            assertArrayEquals(renamedMetadata, read(proxy.metadataUri.toUri()))
            assertArrayEquals(renamedMetadata, receipt.openRead().use { it.readBytes() })

            action("delete", proxyId).performScrollTo().performClick()
            action("delete-confirm", proxyId).performScrollTo().assertIsNotEnabled()
            action("delete-ack", proxyId).performScrollTo().assertIsOff().performClick()
            action("delete-confirm", proxyId).performScrollTo().assertIsEnabled().performClick()
            awaitIdle()
            node("proxy-catalog-error").assertDoesNotExist()
            node("proxy-catalog-entry-$proxyId").assertDoesNotExist()
            assertTrue(repository.catalog().none { it.takeId == selected.id || it.result.proxyId == proxyId })
            assertAbsent(proxy.proxyUri.toUri())
            assertAbsent(proxy.metadataUri.toUri())
            assertTrue(ownedRows(proxyId).isEmpty())
            assertFalse(receipt.baseFile.exists())
            assertFalse(File(receipt.baseFile.path + ".bak").exists())
            assertFalse(File(receipt.baseFile.path + ".new").exists())
            assertFalse(File(context.filesDir, "proxy-deletions/$proxyId.json").exists())
            assertFalse(File(context.cacheDir, "proxy-$proxyId.mp4").exists())
            assertAbsent(original)
            assertNull(queue.states.value.jobs.firstOrNull { it.take.id == selected.id })
            assertEquals(policiesBefore, ProxyPolicies.get(context).states.value)
            assertEquals(settingsBefore, SettingsRepositories.get(context).states.value)
            node("proxy-catalog-refresh").assertIsEnabled().performClick()
            awaitIdle()
            node("proxy-catalog-error").assertDoesNotExist()
            node("proxy-catalog-entry-$proxyId").assertDoesNotExist()
            node("proxy-catalog-close").assertIsEnabled().performClick()
            node("proxy-catalog-dialog").assertDoesNotExist()
            Log.i("E17ProxyCatalogActionsUi", "PASS proxyId=$proxyId originalAbsent=true invalidRenameRejected=true renameCancelPreserved=true normalizedName=$expectedName videoBytesStable=true deleteAckRequired=true deleteCancelPreserved=true pairReceiptAndCatalogAbsent=true openEnabled48dp=true noExternalActivity=true")
        } finally {
            withContext(NonCancellable) {
                if (mounted) {
                    if (exists("proxy-catalog-dialog")) awaitIdle()
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

    private fun awaitIdle() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("proxy-catalog-refresh", true).fetchSemanticsNodes()
                .any { !it.config.contains(SemanticsProperties.Disabled) }
        }
    }
    private fun action(action: String, id: String) = node("proxy-catalog-$action-$id")
    private fun displayName(uri: Uri): String = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
        null, null, null)!!.use { cursor ->
        assertEquals(1, cursor.count); assertTrue(cursor.moveToFirst()); cursor.getString(0)
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
