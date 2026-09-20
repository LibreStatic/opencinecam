/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.net.toUri
import com.librestatic.opencinecam.storage.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MediaProxyDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    @Test fun galleryGeneratesRealProxyAndReopensDurableRelationWithoutChangingOriginal() {
        val token = "proxy-test-${UUID.randomUUID()}"
        val originalBytes = instrumentation.context.assets.open("e1-video.mp4").use { it.readBytes() }
        val original = requireNotNull(resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/OpenCineCam/")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var result: MediaProxyResult? = null
        var take: LocalMediaTake? = null
        val visible = mutableStateOf(true)
        try {
            resolver.openOutputStream(original, "w")!!.use { it.write(originalBytes) }
            assertEquals(1,resolver.update(original,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null))
            take = LocalMediaRepository(context).page(GallerySettings(),token).takes.single()
            val settings = mutableStateOf(ProxySettings(640,1))
            compose.setContent { MaterialTheme { if (visible.value) MediaCatalogScreen(GallerySettings(),{},
                proxySettings=settings.value,onProxySettings={settings.value=it}) } }
            compose.onNodeWithTag("gallery-search",true).performTextInput(token)
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("gallery-proxy-${take.id}",true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("gallery-proxy-${take.id}",true).performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-proxy-busy",true).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("media-proxy-create",true).performClick()
            compose.waitUntil(90_000) { listOf("media-proxy-result","media-proxy-error").any { compose.onAllNodesWithTag(it,true).fetchSemanticsNodes().isNotEmpty() } }
            compose.onNodeWithTag("media-proxy-error",true).assertDoesNotExist()
            compose.onNodeWithTag("media-proxy-result",true).performScrollTo().assertTextEquals(context.getString(R.string.proxy_complete))
            result = runBlocking { MediaProxyRepository(context).existing(take) }
            val proxy = requireNotNull(result)
            assertEquals(128,proxy.width); assertEquals(96,proxy.height)
            assertEquals(sha(originalBytes),proxy.originalSha256)
            assertArrayEquals(originalBytes,resolver.openInputStream(original)!!.use { it.readBytes() })
            val relation = resolver.openInputStream(proxy.metadataUri.toUri())!!.use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
            assertEquals(take.id,relation.getValue("takeId").jsonPrimitive.content)
            assertEquals(proxy.proxySha256,relation.getValue("proxySha256").jsonPrimitive.content)
            assertEquals(listOf(take),LocalMediaRepository(context).page(GallerySettings(),token).takes)
            runBlocking {
                val before=probeProxyMedia(context,original);val after=probeProxyMedia(context,proxy.proxyUri.toUri())
                verifyProxyCorrespondence(before,after,VideoDisplayGeometry(128,96))
            }
            compose.onNodeWithTag("media-proxy-close",true).performClick()
            compose.onNodeWithTag("gallery-proxy-${take.id}",true).performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("media-proxy-result",true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media-proxy-create",true).assertDoesNotExist()
            assertEquals(proxy,runBlocking { MediaProxyRepository(context).existing(take) })
            // A valid receipt must not hide provider pending state or altered relation bytes.
            assertEquals(1,resolver.update(proxy.proxyUri.toUri(),ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,1) },null,null))
            try { runBlocking { MediaProxyRepository(context).existing(take) }; fail("Pending proxy accepted") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("pending")) }
            finally { resolver.update(proxy.proxyUri.toUri(),ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null) }
            val relationBytes=resolver.openInputStream(proxy.metadataUri.toUri())!!.use { it.readBytes() }
            resolver.openOutputStream(proxy.metadataUri.toUri(),"wt")!!.use { it.write(ByteArray(relationBytes.size) { 32 }) }
            try { runBlocking { MediaProxyRepository(context).existing(take) }; fail("Changed relation accepted") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("relation bytes")) }
            finally { resolver.openOutputStream(proxy.metadataUri.toUri(),"wt")!!.use { it.write(relationBytes) } }
            assertEquals(proxy,runBlocking { MediaProxyRepository(context).existing(take) })
            assertArrayEquals(originalBytes,resolver.openInputStream(original)!!.use { it.readBytes() })
            Log.i("E17ProxyProbe","publishedPair=true visibleGallery=true durableReopen=true originalSha256=${proxy.originalSha256} proxySha256=${proxy.proxySha256} dimensions=${proxy.width}x${proxy.height} frames=${proxy.frames} durationUs=${proxy.durationUs} originalBytes=${proxy.originalBytes} proxyBytes=${proxy.proxyBytes} catalogOriginalOnly=true")
        } finally {
            compose.runOnIdle { visible.value=false }
            result?.let { resolver.delete(it.proxyUri.toUri(),null,null);resolver.delete(it.metadataUri.toUri(),null,null) }
            take?.let { File(context.filesDir,"media-proxies/${sha(it.id.toByteArray())}.json").delete() }
            resolver.delete(original,null,null)
        }
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
