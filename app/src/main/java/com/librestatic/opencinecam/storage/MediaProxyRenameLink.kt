/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Optional dependency of an already-reserved original rename. No additional reservation is
 * acquired here. The caller records an attempted apply before invoking it, compensates on failure,
 * and includes verify in both success and compensation acceptance. Not a crash-recovery journal.
 */
internal class MediaProxyRenameLink private constructor(
    private val context: Context,
    private val selected: LocalMediaTake,
    private val newName: String,
    private val proxy: MediaProxyResult,
    private val originalRow: MediaDeleteRow,
    private val proxyRow: MediaDeleteRow,
    private val metadataRow: MediaDeleteRow,
    private val record: AtomicFile,
    private val before: ByteArray,
    private val after: ByteArray,
) {
    private val rows = MediaStoreRenameAccess(context)

    fun apply() {
        verifyMedia(newName)
        val current = observeMetadata()
        check(rows.readMetadata(current).contentEquals(before)) { "Proxy relation changed before rename" }
        check(readReceipt().contentEquals(before)) { "Proxy receipt changed before rename" }
        if (!before.contentEquals(after)) {
            rows.writeMetadata(current, before, after)
            replaceReceipt(before, after)
        }
        verify(restored = false)
    }

    /** Restore only exact known before/after states. Unknown bytes are left untouched and reported
     * to the enclosing rename's partial/unknown outcome, never overwritten by a stale backup. */
    fun restore() {
        val current = observeMetadata()
        val metadata = rows.readMetadata(current)
        val receipt = readReceipt()
        check(known(metadata) && known(receipt)) { "Proxy rename compensation found unknown bytes" }
        verifyMediaHashes()
        if (!metadata.contentEquals(before)) rows.writeMetadata(current, after, before)
        if (!receipt.contentEquals(before)) replaceReceipt(after, before)
        check(rows.readMetadata(observeMetadata()).contentEquals(before)) { "Proxy relation restoration differs" }
        check(readReceipt().contentEquals(before)) { "Proxy receipt restoration differs" }
        // The enclosing rename restores original filenames separately; verify(true) is called only
        // after those operations, so restoration never requires the old name prematurely.
    }

    fun verify(restored: Boolean) {
        val expected = if (restored) before else after
        val name = if (restored) selected.primary.name else newName
        verifyMedia(name)
        val actual = rows.readMetadata(observeMetadata())
        check(actual.contentEquals(expected) && readReceipt().contentEquals(expected)) {
            "Proxy relation and receipt did not remain coherent after rename"
        }
        val json = parse(actual)
        check(json.getValue("originalDisplayName").jsonPrimitive.content == name)
    }

    private fun observeMetadata(): MediaDeleteRow = rows.observe(metadataRow).also {
        check(sameRenameScope(metadataRow, it) && it.artifact.name == metadataRow.artifact.name &&
            it.artifact.sizeBytes in setOf(before.size.toLong(), after.size.toLong())) {
            "Proxy rename metadata identity differs"
        }
    }

    private fun verifyMedia(name: String) {
        val original = rows.observe(originalRow)
        check(sameRenameScope(originalRow, original) && original.artifact.name == name) {
            "Proxy rename original identity or filename differs"
        }
        verifyMediaHashes()
    }

    private fun verifyMediaHashes() {
        // Original name can be either side during compensation, but ownership/URI/length cannot.
        val original = rows.observe(originalRow)
        check(sameRenameScope(originalRow, original) &&
            original.artifact.name in setOf(selected.primary.name, newName)) { "Proxy rename original scope differs" }
        check(rows.observe(proxyRow) == proxyRow) { "Proxy video identity changed during original rename" }
        runBlocking {
            check(proxyHash(context, proxy.originalUri.toUri()) == proxy.originalSha256) { "Original video bytes changed during rename" }
            check(proxyHash(context, proxy.proxyUri.toUri()) == proxy.proxySha256) { "Proxy video bytes changed during rename" }
        }
    }

    private fun known(bytes: ByteArray) = bytes.contentEquals(before) || bytes.contentEquals(after)
    private fun readReceipt(): ByteArray {
        checkReceiptPath(record)
        val bytes = record.openRead().use(::boundedBytes)
        parse(bytes)
        return bytes
    }
    private fun replaceReceipt(expected: ByteArray, replacement: ByteArray) {
        check(readReceipt().contentEquals(expected)) { "Proxy receipt changed before replacement" }
        val output = record.startWrite()
        try { output.write(replacement); record.finishWrite(output) }
        catch (failure: Throwable) { record.failWrite(output); throw failure }
        check(readReceipt().contentEquals(replacement)) { "Proxy receipt replacement readback differs" }
    }

    companion object {
        /** Called off the UI thread while executeMediaRename owns its media-mutation reservation. */
        fun prepare(context: Context, selected: LocalMediaTake, newName: String): MediaProxyRenameLink? {
            if (selected.kind != LocalMediaKind.VIDEO) return null
            requirePortableShareNames(listOf(newName))
            val app = context.applicationContext
            val proxy = runBlocking { MediaProxyRepository(app).existing(selected) } ?: return null
            val repository = LocalMediaRepository(app)
            val original = repository.observeMutationRow(selected.primary)
            check(original.artifact == selected.primary && original.owner == app.packageName && original.pending == 0)
            val key = MessageDigest.getInstance("SHA-256").digest(selected.id.toByteArray()).proxyHex()
            // Android's filesDir may have an alias in its parent. Resolve that parent, but still
            // reject a symlink at the owned directory or any receipt artifact itself.
            val parent = app.filesDir.canonicalFile
            val directory = File(parent, "media-proxies")
            check(directory.canonicalFile == directory && directory.isDirectory) { "Proxy receipt directory changed" }
            val record = AtomicFile(File(directory, "$key.json"))
            checkReceiptPath(record)
            val before = record.openRead().use(::boundedBytes)
            val json = parse(before)
            check(json.getValue("takeId").jsonPrimitive.content == selected.id &&
                json.getValue("proxyId").jsonPrimitive.content == proxy.proxyId &&
                json.getValue("originalUri").jsonPrimitive.content == selected.primary.uri &&
                json.getValue("proxyUri").jsonPrimitive.content == proxy.proxyUri &&
                json.getValue("metadataUri").jsonPrimitive.content == proxy.metadataUri &&
                json.getValue("originalDisplayName").jsonPrimitive.content == selected.primary.name) {
                "Proxy rename receipt is not the selected original's current relation"
            }
            fun observed(uri: String, name: String, mime: String, bytes: Long): MediaDeleteRow =
                repository.observeMutationRow(LocalMediaArtifact(uri, name, mime, bytes, 0))
            val video = observed(proxy.proxyUri, proxy.proxyDisplayName, "video/mp4", proxy.proxyBytes)
            val metadata = observed(proxy.metadataUri, "proxy-${proxy.proxyId}.json", "application/json", before.size.toLong())
            check(video.owner == app.packageName && metadata.owner == app.packageName && video.pending == 0 && metadata.pending == 0)
            check(video.relativePath == "Movies/OpenCineCamProxies/${proxy.proxyId}/" &&
                metadata.relativePath == "Download/OpenCineCamProxies/${proxy.proxyId}/")
            requireProxyFilename(proxy.proxyDisplayName)
            check(video.artifact.name == proxy.proxyDisplayName && video.artifact.mimeType == "video/mp4" && video.artifact.sizeBytes == proxy.proxyBytes)
            check(metadata.artifact.name == "proxy-${proxy.proxyId}.json" && metadata.artifact.mimeType == "application/json" && metadata.artifact.sizeBytes == before.size.toLong())
            val access = MediaStoreRenameAccess(app)
            check(access.readMetadata(metadata).contentEquals(before)) { "Proxy metadata and receipt bytes differ before rename" }
            val after = if (newName == selected.primary.name) before else
                JsonObject(json + ("originalDisplayName" to JsonPrimitive(newName))).toString().toByteArray(Charsets.UTF_8)
            require(after.size in 1..MAX_BYTES)
            val link = MediaProxyRenameLink(app, selected, newName, proxy, original, video, metadata, record, before, after)
            link.verify(restored = true)
            return link
        }

        private const val MAX_BYTES = 64 * 1024
        private fun checkReceiptPath(record: AtomicFile) {
            val file = record.baseFile
            check(file.parentFile?.canonicalFile == file.parentFile && file.parentFile?.isDirectory == true)
            for (path in listOf(file, File(file.path + ".bak"), File(file.path + ".new"))) {
                check(path.canonicalFile == path.absoluteFile && (!path.exists() || path.isFile)) { "Proxy receipt artifact identity differs" }
            }
        }
        private fun boundedBytes(input: java.io.InputStream): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Proxy rename relation exceeds byte bound" }
                output.write(buffer, 0, count)
            }
            require(output.size() > 0)
            return output.toByteArray()
        }
        private fun parse(bytes: ByteArray): JsonObject {
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            require(jsonNestingWithinBound(text) && jsonObjectKeysUnique(text)) { "Ambiguous proxy rename relation" }
            val value = Json.parseToJsonElement(text).jsonObject
            check(value.getValue("schema").jsonPrimitive.content == "opencinecam.proxy.v1")
            check(value.getValue("originalDisplayName").jsonPrimitive.isString)
            return value
        }
    }
}
