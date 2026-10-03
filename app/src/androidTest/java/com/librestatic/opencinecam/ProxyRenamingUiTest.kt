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

/** Real derivative rename through the production dialog; no chooser delivery or deletion claim. */
class ProxyRenamingUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val video get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val metadata get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Test fun invalidStemAndCancelPreservePairThenConfirmedNameSurvivesReopen() = runBlocking<Unit> {
        val token = "proxy-rename-ui-${UUID.randomUUID()}"
        val proxyId = UUID.randomUUID().toString()
        val stem = "Café proxy"
        val requestedName = "$stem.mp4"
        val bytes = instrumentation.context.assets.open("e1-video.mp4").use { it.readBytes() }
        assertEquals(5404, bytes.size)
        assertEquals("a32963539eefe4fc0d14e76770d51e49c25108a31b9eda9176a022a94a9a99b6", hash(bytes))
        val original = requireNotNull(resolver.insert(video, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/OpenCineCam/")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val visible = mutableStateOf(true)
        val repository = MediaProxyRepository(context)
        var take: LocalMediaTake? = null
        var mounted = false
        val policiesBefore = ProxyPolicies.get(context).states.value
        val settingsBefore = SettingsRepositories.get(context).states.value
        try {
            resolver.openOutputStream(original, "w")!!.use { it.write(bytes) }
            assertEquals(1, resolver.update(original, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val selected = withTimeout(10_000) {
                var found: LocalMediaTake? = null
                while (found == null) {
                    found = LocalMediaRepository(context).page(GallerySettings(), token).takes.singleOrNull()
                        ?.takeIf { it.primary.sizeBytes == bytes.size.toLong() }
                    if (found == null) delay(25)
                }
                found
            }
            take = selected
            val queue = MediaProxyQueue.get(context)
            withTimeout(20_000) { queue.states.first { it.loaded || it.error != null } }.also { assertNull(it.error) }
            val proxy = repository.create(selected, ProxySettings(640, 1), proxyId)
            assertNull(queue.states.value.jobs.firstOrNull { it.take.id == selected.id })
            val proxyBytes = read(proxy.proxyUri.toUri())
            val metadataBytes = read(proxy.metadataUri.toUri())
            val receipt = receipt(selected)
            assertTrue(receipt.baseFile.isFile)
            val receiptBytes = receipt.openRead().use { it.readBytes() }
            assertArrayEquals(metadataBytes, receiptBytes)
            val originalName = displayName(original)
            val proxyName = displayName(proxy.proxyUri.toUri())
            val metadataName = displayName(proxy.metadataUri.toUri())
            assertEquals(proxy.proxyDisplayName, proxyName)
            compose.setContent { MaterialTheme {
                if (visible.value) MediaProxyDialog(selected, ProxySettings(640, 1),
                    onSettings = { error("Rename must not alter encoder settings") }, onDismiss = { visible.value = false })
            } }
            mounted = true
            awaitResult()
            node("name").assertTextEquals(proxyName)
            node("rename").performScrollTo().assertIsEnabled()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            node("rename-stem").performScrollTo().performTextReplacement("/")
            node("rename-confirm").performScrollTo().assertIsNotEnabled()
            node("rename-preview").assertDoesNotExist()
            node("rename-stem").performScrollTo().performTextReplacement(stem)
            node("rename-preview").assertTextEquals(requestedName)
            node("rename-confirm").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            node("rename-cancel").performScrollTo().assertIsEnabled().performClick()
            node("rename-stem").assertDoesNotExist()
            node("error").assertDoesNotExist()
            assertArrayEquals(bytes, read(original))
            assertArrayEquals(proxyBytes, read(proxy.proxyUri.toUri()))
            assertArrayEquals(metadataBytes, read(proxy.metadataUri.toUri()))
            assertArrayEquals(receiptBytes, receipt.openRead().use { it.readBytes() })
            assertEquals(originalName, displayName(original))
            assertEquals(proxyName, displayName(proxy.proxyUri.toUri()))
            assertEquals(metadataName, displayName(proxy.metadataUri.toUri()))
            assertEquals(proxy, repository.existing(selected))

            node("rename").performScrollTo().performClick()
            node("rename-stem").performScrollTo().performTextReplacement(stem)
            node("rename-preview").assertTextEquals(requestedName)
            node("rename-confirm").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(20_000) { nodeExists("error") || !nodeExists("rename-stem") }
            node("error").assertDoesNotExist()
            node("name").assertTextEquals(requestedName)
            val renamed = requireNotNull(repository.existing(selected))
            assertEquals(proxy.copy(proxyDisplayName = requestedName), renamed)
            assertEquals(original.toString(), renamed.originalUri)
            assertArrayEquals(bytes, read(original))
            assertArrayEquals(proxyBytes, read(renamed.proxyUri.toUri()))
            assertEquals(originalName, displayName(original))
            assertEquals(requestedName, displayName(renamed.proxyUri.toUri()))
            assertEquals(metadataName, displayName(renamed.metadataUri.toUri()))
            val renamedJson = read(renamed.metadataUri.toUri())
            assertArrayEquals(renamedJson, receipt.openRead().use { it.readBytes() })
            val expectedJson = JsonObject(Json.parseToJsonElement(metadataBytes.toString(Charsets.UTF_8)).jsonObject +
                ("proxyDisplayName" to JsonPrimitive(requestedName)))
            assertEquals(expectedJson, Json.parseToJsonElement(renamedJson.toString(Charsets.UTF_8)))

            // The close button is fixed outside the scroll; reopening must reload the actual receipt.
            node("close").assertIsEnabled().performClick()
            node("result").assertDoesNotExist()
            compose.runOnIdle { assertFalse(visible.value); visible.value = true }
            awaitResult()
            node("name").assertTextEquals(requestedName)
            node("details").performScrollTo().performClick()
            node("uri").performScrollTo().assertTextEquals(proxy.proxyUri)
            node("share").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            node("delete").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            assertEquals(renamed, repository.existing(selected))
            assertNull(queue.states.value.jobs.firstOrNull { it.take.id == selected.id })
            assertEquals(policiesBefore, ProxyPolicies.get(context).states.value)
            assertEquals(settingsBefore, SettingsRepositories.get(context).states.value)
            node("close").performClick()
            node("result").assertDoesNotExist()
            Log.i("E17ProxyRenamingUi", "PASS proxyId=$proxyId invalidRejected=true cancelPreservedPair=true name=$requestedName reopened=true shareAndDeleteEnabled=true sameUrisAndVideoBytes=true originalSha256=${hash(bytes)}")
        } finally {
            withContext(NonCancellable) {
                if (mounted && visible.value) {
                    // Admitted rename IO must retire before fixture rows are removed.
                    compose.waitUntil(30_000) { compose.onAllNodesWithTag("media-proxy-close", true).fetchSemanticsNodes()
                        .any { !it.config.contains(SemanticsProperties.Disabled) } }
                    compose.runOnIdle { visible.value = false }; compose.waitForIdle()
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

    private fun awaitResult() {
        compose.waitUntil(20_000) { nodeExists("error") || (nodeExists("result") && !nodeExists("busy")) }
        node("error").assertDoesNotExist()
        node("result").assertExists()
    }
    private fun displayName(uri: Uri): String = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
        null, null, null)!!.use { cursor ->
        assertEquals(1, cursor.count); assertTrue(cursor.moveToFirst()); cursor.getString(0)
    }
    private fun node(tag: String) = compose.onNodeWithTag("media-proxy-$tag", true)
    private fun nodeExists(tag: String) = compose.onAllNodesWithTag("media-proxy-$tag", true).fetchSemanticsNodes().isNotEmpty()
    private fun read(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).proxyHex()
    private fun receipt(take: LocalMediaTake) = AtomicFile(File(context.filesDir, "media-proxies/${hash(take.id.toByteArray())}.json"))
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
