/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Caller owns repository mutex + media-mutation reservation + noncancellable IO for this call.
 * Renames the derivative only. Compensation is verified best effort, not a crash transaction.
 */
internal class MediaProxyVideoRenamer(context: Context, private val access: MediaRenameAccess = MediaStoreRenameAccess(context)) {
    private val context = context.applicationContext
    private val repository = LocalMediaRepository(this.context)

    fun rename(take: LocalMediaTake, expected: MediaProxyResult, stem: String): MediaProxyResult {
        proxyFilename(stem)
        check(runBlocking { MediaProxyRepository(context).existing(take) } == expected) {
            "Proxy selection changed before rename"
        }
        val original = repository.observeMutationRow(take.primary)
        check(original.artifact == take.primary && original.owner == context.packageName && original.pending == 0)
        return renamePair(take.id, take.primary.name, expected, stem, null) {
            check(access.observe(original) == original) { "Original identity changed during derivative rename" }
            runBlocking {
                check(proxyHash(context, expected.originalUri.toUri()) == expected.originalSha256) { "Original bytes changed during derivative rename" }
            }
        }
    }

    /** The original is a historical reference here, even when present; only derivative bytes are read. */
    fun rename(entry: ProxyCatalogEntry, stem: String): ProxyCatalogEntry {
        proxyFilename(stem)
        runBlocking { MediaProxyRepository(context).verifyCatalogEntry(entry) }
        val renamed = renamePair(entry.takeId, entry.originalDisplayName, entry.result, stem, entry.receiptSha256) {}
        val digest = MessageDigest.getInstance("SHA-256").digest(readReceipt(receipt(entry.takeId))).proxyHex()
        return entry.copy(result = renamed, receiptSha256 = digest)
    }

    private fun renamePair(takeId: String, originalDisplayName: String, expected: MediaProxyResult,
        stem: String, expectedReceiptSha256: String?, verifyOriginal: () -> Unit): MediaProxyResult {
        val newName = proxyFilename(stem)
        fun row(uri: String, name: String, mime: String, size: Long) =
            repository.observeMutationRow(LocalMediaArtifact(uri, name, mime, size, 0))
        val video = row(expected.proxyUri, expected.proxyDisplayName, "video/mp4", expected.proxyBytes)
        val receipt = receipt(takeId)
        val before = readReceipt(receipt)
        check(expectedReceiptSha256 == null || MessageDigest.getInstance("SHA-256").digest(before).proxyHex() == expectedReceiptSha256) {
            "Proxy rename receipt selection changed"
        }
        val json = parse(before)
        check(json.getValue("takeId").jsonPrimitive.content == takeId &&
            json.getValue("proxyId").jsonPrimitive.content == expected.proxyId &&
            json.getValue("originalUri").jsonPrimitive.content == expected.originalUri &&
            json.getValue("proxyUri").jsonPrimitive.content == expected.proxyUri &&
            json.getValue("metadataUri").jsonPrimitive.content == expected.metadataUri &&
            json.getValue("originalDisplayName").jsonPrimitive.content == originalDisplayName) { "Proxy rename receipt identity differs" }
        val declaredName = json["proxyDisplayName"]?.let { node ->
            val value = node.jsonPrimitive; check(value.isString); value.content
        } ?: "proxy-${expected.proxyId}.mp4"
        check(declaredName == expected.proxyDisplayName)
        val metadata = row(expected.metadataUri, "proxy-${expected.proxyId}.json", "application/json", before.size.toLong())
        check(video.owner == context.packageName && video.pending == 0 && video.artifact.name == expected.proxyDisplayName &&
            video.artifact.mimeType == "video/mp4" && video.artifact.sizeBytes == expected.proxyBytes &&
            video.relativePath == "Movies/OpenCineCamProxies/${expected.proxyId}/") { "Proxy video row changed before rename" }
        check(metadata.owner == context.packageName && metadata.pending == 0 &&
            metadata.artifact.name == "proxy-${expected.proxyId}.json" && metadata.artifact.mimeType == "application/json" &&
            metadata.artifact.sizeBytes == before.size.toLong() && metadata.relativePath == "Download/OpenCineCamProxies/${expected.proxyId}/") {
            "Proxy relation row changed before rename"
        }
        check(access.readMetadata(metadata).contentEquals(before)) { "Proxy relation differs from receipt before rename" }
        // A same-name request is genuinely a no-op, including legacy receipts without the new key.
        val after = if (newName == expected.proxyDisplayName) before else
            JsonObject(json + ("proxyDisplayName" to JsonPrimitive(newName))).toString().toByteArray(Charsets.UTF_8)
        require(after.size in 1..MAX_BYTES)
        val knownNames = linkedSetOf(expected.proxyDisplayName, newName)
        fun renameVideo(row: MediaDeleteRow, name: String): MediaDeleteRow {
            requireProxyFilename(name)
            val condition = proxyRenameCondition(row, expected.proxyId, context.packageName)
            check(context.contentResolver.update(row.artifact.uri.toUri(), android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            }, condition.selection, condition.arguments.toTypedArray()) == 1) { "Conditional proxy rename identity changed" }
            return access.observe(row).also { check(sameRenameScope(row, it)) { "Proxy rename row scope changed" } }
        }
        fun currentVideo(): MediaDeleteRow = access.observe(video).also {
            check(sameRenameScope(video, it) && it.artifact.name in knownNames) { "Proxy rename found unknown video identity" }
        }
        fun currentMetadata(): MediaDeleteRow = access.observe(metadata).also {
            check(sameRenameScope(metadata, it) && it.artifact.name == metadata.artifact.name &&
                it.artifact.sizeBytes in setOf(before.size.toLong(), after.size.toLong())) { "Proxy rename found unknown relation identity" }
        }
        fun verifyMedia() {
            verifyOriginal()
            runBlocking {
                check(proxyHash(context, expected.proxyUri.toUri()) == expected.proxySha256) { "Proxy video bytes changed during rename" }
            }
        }
        fun verify(name: String, bytes: ByteArray) {
            check(currentVideo().artifact.name == name) { "Proxy video filename verification failed" }
            check(access.readMetadata(currentMetadata()).contentEquals(bytes) && readReceipt(receipt).contentEquals(bytes)) {
                "Proxy relation/receipt verification failed"
            }
            verifyMedia()
        }
        verify(expected.proxyDisplayName, before)
        access.checkDestinations(listOf(video), mapOf(video.artifact.uri to newName))
        var attempted = false
        try {
            if (newName != expected.proxyDisplayName) {
                attempted = true // Includes a provider mutation that throws before returning its row.
                val renamed = renameVideo(video, newName)
                check(sameRenameScope(video, renamed)) { "Proxy rename changed row scope" }
                knownNames += renamed.artifact.name // Preserve actual provider suffix for compensation.
                check(renamed.artifact.name == newName) { "Provider did not preserve requested proxy filename" }
                access.writeMetadata(currentMetadata(), before, after)
                replaceReceipt(receipt, before, after)
            }
            verify(newName, after)
            return expected.copy(proxyDisplayName = newName)
        } catch (failure: Exception) {
            if (!attempted) throw failure
            val restoration = runCatching {
                val current = currentVideo()
                val relationRow = currentMetadata()
                val relation = access.readMetadata(relationRow)
                val recorded = readReceipt(receipt)
                fun known(bytes: ByteArray) = bytes.contentEquals(before) || bytes.contentEquals(after)
                check(known(relation) && known(recorded)) { "Unknown proxy rename bytes; compensation did not overwrite them" }
                verifyMedia()
                if (!relation.contentEquals(before)) access.writeMetadata(relationRow, after, before)
                if (!recorded.contentEquals(before)) replaceReceipt(receipt, after, before)
                if (current.artifact.name != expected.proxyDisplayName) {
                    access.checkDestinations(listOf(current), mapOf(current.artifact.uri to expected.proxyDisplayName))
                    val restored = renameVideo(current, expected.proxyDisplayName)
                    check(sameRenameScope(video, restored) && restored.artifact.name == expected.proxyDisplayName) {
                        "Provider did not restore original proxy filename"
                    }
                }
                verify(expected.proxyDisplayName, before)
            }.exceptionOrNull()
            if (restoration != null) {
                throw IllegalStateException("Proxy rename partial or unknown; compensation incomplete: ${restoration.message}", failure)
                    .also { it.addSuppressed(restoration) }
            }
            throw IllegalStateException("Proxy rename failed; previous filename and relation restored: ${failure.message}", failure)
        }
    }

    private fun receipt(takeId: String): AtomicFile {
        val directory = File(context.filesDir.canonicalFile, "media-proxies")
        check(directory.canonicalFile == directory && directory.isDirectory) { "Proxy receipt directory identity differs" }
        val key = MessageDigest.getInstance("SHA-256").digest(takeId.toByteArray()).proxyHex()
        return AtomicFile(File(directory, "$key.json"))
    }
    private fun readReceipt(record: AtomicFile): ByteArray {
        val file = record.baseFile
        check(file.parentFile?.canonicalFile == file.parentFile && file.parentFile?.isDirectory == true)
        for (path in listOf(file, File(file.path + ".new"), File(file.path + ".bak"))) {
            check(path.canonicalFile == path.absoluteFile && (!path.exists() || path.isFile)) { "Proxy receipt artifact identity differs" }
        }
        return record.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Proxy receipt exceeds bound" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().also { require(it.isNotEmpty()); parse(it) }
        }
    }
    private fun replaceReceipt(record: AtomicFile, expected: ByteArray, replacement: ByteArray) {
        check(readReceipt(record).contentEquals(expected)) { "Proxy receipt changed before write" }
        val output = record.startWrite()
        try { output.write(replacement); record.finishWrite(output) }
        catch (failure: Throwable) { record.failWrite(output); throw failure }
        check(readReceipt(record).contentEquals(replacement)) { "Proxy receipt write readback differs" }
    }
    private fun parse(bytes: ByteArray): JsonObject {
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require(jsonNestingWithinBound(text) && jsonObjectKeysUnique(text)) { "Ambiguous proxy relation" }
        return Json.parseToJsonElement(text).jsonObject.also {
            check(it.getValue("schema").jsonPrimitive.content == "opencinecam.proxy.v1")
        }
    }
    private companion object { const val MAX_BYTES = 64 * 1024 }
}
