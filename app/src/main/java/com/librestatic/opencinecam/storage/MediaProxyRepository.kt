/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.core.net.toUri
import com.librestatic.opencinecam.ProxySettings
import com.librestatic.opencinecam.transfers.WebDavTransferRuntime
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

internal class ProxyRecoveryBusyException(cause: Throwable? = null) : IllegalStateException("Proxy recovery waits for media ownership", cause)

internal data class MediaProxyResult(val originalUri: String, val proxyUri: String, val metadataUri: String,
    val originalSha256: String, val proxySha256: String, val originalBytes: Long, val proxyBytes: Long,
    val width: Int, val height: Int, val frames: Int, val durationUs: Long, val requestedBitrate: Int, val proxyId: String,
    val proxyDisplayName: String = "proxy-$proxyId.mp4")

/** Manual post-capture generation. Dedicated roots prevent proxies becoming catalog originals.
 * The private commit receipt is written last; an incomplete pair is never returned as a result.
 * The queue supplies a stable operation ID for crash reconciliation before retry. */
internal class MediaProxyRepository(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val catalog = LocalMediaRepository(this.context)
    private val directory = File(this.context.filesDir, "media-proxies")

    private val deletions = ProxyDeletionJournal(File(this.context.filesDir, "proxy-deletions"))

    /** Independent derivative library. Never queries or hashes the historically referenced source. */
    suspend fun catalog(): List<ProxyCatalogEntry> = withContext(Dispatchers.IO) {
        check(active.tryLock()) { "Another proxy operation is active" }
        try {
            check(deletions.pending().isEmpty()) { "Proxy deletion pending recovery" }
            val root = catalogDirectory()
            if (!root.exists()) return@withContext emptyList()
            val files = mutableListOf<File>()
            java.nio.file.Files.newDirectoryStream(root.toPath()).use { entries ->
                for (path in entries) {
                    currentCoroutineContext().ensureActive()
                    check(files.size < 1024) { "Proxy catalog exceeds1024receipts" }
                    val file = path.toFile()
                    check(file.name.matches(Regex("[0-9a-f]{64}\\.json"))) { "Unknown or unfinished proxy receipt artifact" }
                    files += file
                }
            }
            val results = mutableListOf<ProxyCatalogEntry>()
            for (file in files.sortedBy { it.name }) results += readCatalogEntry(file)
            check(results.map { it.takeId }.distinct().size == results.size &&
                results.map { it.result.proxyId }.distinct().size == results.size &&
                results.flatMap { listOf(it.result.proxyUri, it.result.metadataUri) }.distinct().size == results.size * 2) {
                "Duplicate proxy catalog identity"
            }
            results.toList()
        } finally { active.unlock() }
    }

    /** Only the derivative is granted; original reference remains historical even when readable. */
    suspend fun prepareShare(entry: ProxyCatalogEntry): android.content.Intent {
        check(active.tryLock()) { "Another proxy operation is active" }
        var reservation: AutoCloseable? = null
        try {
            reservation = WebDavTransferRuntime.get(context).reserveIdleMediaMutation()
            return withContext(Dispatchers.IO) {
                val verified = verifyCatalogEntry(entry)
                val uri = verified.result.proxyUri.toUri()
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("Proxy", uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        } finally { try { reservation?.close() } finally { active.unlock() } }
    }

    suspend fun prepareOpen(entry: ProxyCatalogEntry): android.content.Intent = prepareShare(entry).apply {
        action = android.content.Intent.ACTION_VIEW
        setDataAndType(entry.result.proxyUri.toUri(), "video/mp4")
        removeExtra(android.content.Intent.EXTRA_STREAM)
    }

    suspend fun renameProxy(entry: ProxyCatalogEntry, stem: String): ProxyCatalogEntry {
        proxyFilename(stem)
        check(active.tryLock()) { "Another proxy operation is active" }
        var reservation: AutoCloseable? = null
        try {
            reservation = WebDavTransferRuntime.get(context).reserveIdleMediaMutation()
            return withContext(NonCancellable + Dispatchers.IO) {
                verifyCatalogEntry(entry)
                val renamed = MediaProxyVideoRenamer(context).rename(entry, stem)
                verifyCatalogEntry(renamed)
            }
        } finally { try { reservation?.close() } finally { active.unlock() } }
    }

    /** Caller retains its operation/media ownership when using this for effects. No original IO. */
    internal suspend fun verifyCatalogEntry(entry: ProxyCatalogEntry): ProxyCatalogEntry {
        check(deletions.pending().none { it.takeId == entry.takeId }) { "Proxy deletion pending recovery" }
        val verified = readCatalogEntry(catalogReceipt(entry.takeId))
        check(verified == entry) { "Proxy catalog selection changed; reopen its actions" }
        return verified
    }

    private fun catalogReceipt(takeId: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(takeId.toByteArray()).proxyHex()
        return File(catalogDirectory(), "$key.json")
    }

    private fun catalogDirectory(): File {
        val root = File(context.filesDir.canonicalFile, "media-proxies")
        check(root.canonicalFile == root && (!root.exists() || root.isDirectory)) { "Proxy receipt directory identity differs" }
        return root
    }

    private fun readCatalogReceipt(file: File): ByteArray {
        check(file.parentFile == catalogDirectory() && file.canonicalFile == file.absoluteFile && file.isFile) {
            "Proxy receipt file identity differs"
        }
        check(listOf(".bak", ".new").none { suffix ->
            java.nio.file.Files.exists(File(file.path + suffix).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        }) { "Proxy receipt has unfinished atomic state" }
        require(file.length() in 1L..65_536L) { "Proxy receipt exceeds64KiB or is empty" }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= 65_536) { "Proxy receipt exceeds64KiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private suspend fun readCatalogEntry(file: File): ProxyCatalogEntry {
        currentCoroutineContext().ensureActive()
        val before = readCatalogReceipt(file)
        val text = before.decodeToString(throwOnInvalidSequence = true)
        require(jsonNestingWithinBound(text) && jsonObjectKeysUnique(text)) { "Ambiguous proxy receipt JSON" }
        val obj = Json.parseToJsonElement(text).jsonObject
        val required = setOf("schema", "takeId", "proxyId", "originalUri", "proxyUri", "metadataUri", "originalSha256",
            "proxySha256", "originalBytes", "proxyBytes", "width", "height", "frames", "durationUs", "requestedBitrate",
            "encoder", "audioPolicy", "audioMime", "firstVideoPtsUs", "lastVideoPtsUs", "videoPtsSha256", "originalDisplayName")
        require(obj.keys == required || obj.keys == required + "proxyDisplayName" || obj.keys == required + setOf("proxyDisplayName", "sidecarSource")) { "Unknown proxy receipt fields" }
        fun string(key: String): String = obj.getValue(key).jsonPrimitive.let {
            require(it.isString); it.content.also { value -> require(value.isNotEmpty() && value.length <= 4096) }
        }
        fun number(key: String): Long = obj.getValue(key).jsonPrimitive.let {
            require(!it.isString); val value = requireNotNull(it.longOrNull)
            require(it.content == value.toString()); value
        }
        require(string("schema") == "opencinecam.proxy.v1")
        val takeId = string("takeId")
        require(file.name == MessageDigest.getInstance("SHA-256").digest(takeId.toByteArray()).proxyHex() + ".json") {
            "Proxy receipt filename does not bind its take"
        }
        require(canonicalMediaId(string("proxyId")))
        require(mediaOriginalIdentity(string("originalUri"))?.first == LocalMediaKind.VIDEO)
        require(takeId == "legacy:${string("originalUri")}" || takeId.startsWith("take:") && canonicalMediaId(takeId.removePrefix("take:")))
        require(mediaDeleteIdentity(string("proxyUri"))?.collection == MediaDeleteCollection.VIDEO &&
            mediaDeleteIdentity(string("metadataUri"))?.collection == MediaDeleteCollection.METADATA)
        require(setOf(string("originalUri"), string("proxyUri"), string("metadataUri")).size == 3)
        for (key in listOf("originalSha256", "proxySha256", "videoPtsSha256")) require(string(key).matches(Regex("[0-9a-f]{64}")))
        for (key in listOf("originalBytes", "proxyBytes", "durationUs")) require(number(key) > 0)
        for (key in listOf("width", "height", "frames", "requestedBitrate")) require(number(key) in 1..Int.MAX_VALUE.toLong())
        require(number("firstVideoPtsUs") >= 0 && number("lastVideoPtsUs") >= number("firstVideoPtsUs"))
        if ("sidecarSource" in obj) {
            val schema = obj.getValue("sidecarSource").jsonObject.getValue("schema").jsonPrimitive.content
            require(string("audioPolicy") == if (schema == "opencinecam.proxy-sidecar.v2")
                "sidecar-pcm-normalized-calibrated-aac" else "sidecar-pcm16-calibrated-aac")
            require(string("audioMime") == "audio/mp4a-latm")
            validateProxySidecarReceipt(obj.getValue("sidecarSource").jsonObject)
            require(obj.getValue("sidecarSource").jsonObject.getValue("sourceSha256").jsonObject
                .getValue(string("originalUri")).jsonPrimitive.content == string("originalSha256"))
        } else require(string("audioPolicy") == "preserve-packets")
        require(string("audioMime") == "none" || string("audioMime").startsWith("audio/"))
        string("encoder")
        val originalName = string("originalDisplayName")
        require(originalName.isNotBlank() && originalName.none { it == '/' || it == '\\' || it.isISOControl() } &&
            Charsets.UTF_8.newEncoder().canEncode(originalName))
        if ("proxyDisplayName" in obj) requireProxyFilename(string("proxyDisplayName"))
        val result = decode(obj)
        // Check the exact committed JSON bytes too; no acceptance of merely equivalent rewrites.
        require(before.contentEquals(obj.toString().toByteArray())) { "Proxy receipt bytes are not canonical" }
        verifyPair(obj, result)
        check(readCatalogReceipt(file).contentEquals(before)) { "Proxy receipt changed during catalog verification" }
        return ProxyCatalogEntry(takeId, originalName, result, MessageDigest.getInstance("SHA-256").digest(before).proxyHex())
    }

    suspend fun existing(take: LocalMediaTake): MediaProxyResult? = withContext(Dispatchers.IO) {
        check(deletions.pending().none { it.takeId == take.id }) { "Proxy deletion pending recovery" }
        val receipt = receipt(take)
        if (!receipt.baseFile.exists()) return@withContext null
        require(receipt.baseFile.length() <= 64 * 1024)
        val obj = Json.parseToJsonElement(receipt.openRead().use { it.readBytes().decodeToString() }).jsonObject
        val result = decode(obj)
        check(result.originalUri == take.primary.uri && obj.getValue("takeId").jsonPrimitive.content == take.id)
        check(obj.getValue("originalDisplayName").jsonPrimitive.content == take.primary.name) { "Proxy original name changed; relation requires reconciliation" }
        check(proxyHash(context, result.originalUri.toUri()) == result.originalSha256) { "Proxy original changed" }
        check(proxyHash(context, result.proxyUri.toUri()) == result.proxySha256) { "Proxy missing or changed" }
        verifyPair(obj, result)
        result
    }

    /** Grants only the verified derivative. Preparing a chooser is not delivery to a receiver. */
    suspend fun prepareShare(take: LocalMediaTake, expected: MediaProxyResult): android.content.Intent {
        check(active.tryLock()) { "Another proxy operation is active" }
        var reservation: AutoCloseable? = null
        try {
            reservation = WebDavTransferRuntime.get(context).reserveIdleMediaMutation()
            return withContext(Dispatchers.IO) {
                catalog.freshSnapshot(take)
                val verified = requireNotNull(existing(take)) { "Proxy no longer available" }
                check(verified == expected) { "Proxy selection changed; reopen its actions" }
                val uri = verified.proxyUri.toUri()
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("Proxy", uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        } finally { try { reservation?.close() } finally { active.unlock() } }
    }

    suspend fun renameProxy(take: LocalMediaTake, expected: MediaProxyResult, stem: String): MediaProxyResult {
        proxyFilename(stem)
        check(active.tryLock()) { "Another proxy operation is active" }
        var reservation: AutoCloseable? = null
        try {
            reservation = WebDavTransferRuntime.get(context).reserveIdleMediaMutation()
            return withContext(NonCancellable + Dispatchers.IO) {
                catalog.freshSnapshot(take)
                check(existing(take) == expected) { "Proxy selection changed; reopen its actions" }
                MediaProxyVideoRenamer(context).rename(take, expected, stem)
            }
        } finally { try { reservation?.close() } finally { active.unlock() } }
    }

    suspend fun create(take: LocalMediaTake, settings: ProxySettings, id: String = UUID.randomUUID().toString()): MediaProxyResult {
        require(canonicalMediaId(id))
        check(active.tryLock()) { "Another proxy operation is active" }
        var candidate: File? = null
        val inserted = mutableListOf<Uri>()
        var committed = false
        var reservation: AutoCloseable? = null
        try {
            reservation = WebDavTransferRuntime.get(context).reserveIdleMediaMutation()
            return withContext(Dispatchers.IO) {
                require(take.kind == LocalMediaKind.VIDEO && take.primary.mimeType.startsWith("video/"))
                catalog.freshSnapshot(take)
                check(existing(take) == null) { "This take already has a proxy" }
                val original = take.primary.uri.toUri()
                val beforeHash = proxyHash(context, original)
                val before = probeProxyMedia(context, original)
                val sidecar = if (take.originals.any { it.mimeType.startsWith("audio/") }) {
                    require(before.audio == null) { "Sidecar cannot replace embedded audio" }
                    prepareProxySidecarSource(context, take, before.video.timestampsUs.min(), probeProxyVideoEndUs(context, original))
                } else null
                val target = proxyDimensions(before.geometry, settings, before.visibleRaster)
                // Reserve space for the private candidate and published copy plus a fixed margin.
                val estimate = before.video.durationUs / 1_000_000.0 * settings.videoBitrateMbps * 125_000
                val storage = requireNotNull(context.getSystemService(android.os.storage.StorageManager::class.java))
                require(storage.getAllocatableBytes(storage.getUuidForPath(context.cacheDir)) > estimate * 2 + 64L * 1024 * 1024) { "Insufficient proxy working space" }
                val output = File(context.cacheDir, "proxy-$id.mp4").also { candidate = it }
                val export = ProxyTranscoder(context).transcode(original, output, target.width, target.height, settings.videoBitrateMbps * 1_000_000,
                    sidecar?.let { ProxySidecarExportInput(it.selection.audio.uri.toUri(), it.pcm, it.selection.timeline) })
                val after = probeProxyMedia(context, Uri.fromFile(output))
                if (sidecar == null) verifyProxyCorrespondence(before, after, target)
                else {
                    verifyProxyVideoCorrespondence(before, after, target)
                    require(probeProxyVideoEndUs(context, Uri.fromFile(output)) == sidecar.selection.timeline.videoEndUs)
                    require(after.audio?.mime == "audio/mp4a-latm" && after.audio.sampleRate == sidecar.pcm.sampleRateHz &&
                        after.audio.channels == sidecar.pcm.channels)
                    verifyProxySidecarSource(context, take, sidecar)
                }
                check(proxyHash(context, original) == beforeHash) { "Original changed during proxy generation" }
                catalog.freshSnapshot(take)
                val proxyHash = proxyHash(context, Uri.fromFile(output))
                currentCoroutineContext().ensureActive()
                // Once publication starts, finish or compensate the pair before releasing ownership.
                withContext(NonCancellable) {
                    fun insert(collection: Uri, name: String, mime: String, root: String): Uri =
                        requireNotNull(resolver.insert(collection, ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, mime)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/OpenCineCamProxies/$id/")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        })).also(inserted::add)
                    val video = insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "proxy-$id.mp4", "video/mp4", "Movies")
                    val metadata = insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "proxy-$id.json", "application/json", "Download")
                    requireNotNull(resolver.openOutputStream(video, "w")).use { dest -> output.inputStream().use { it.copyTo(dest) } }
                    check(proxyHash(context, video) == proxyHash) { "Published proxy bytes differ" }
                    val result = MediaProxyResult(original.toString(), video.toString(), metadata.toString(), beforeHash, proxyHash,
                        take.primary.sizeBytes, output.length(), target.width, target.height, after.video.timestampsUs.size,
                        after.video.durationUs, settings.videoBitrateMbps * 1_000_000, id)
                    val baseRelation = encode(take, result, before, export.videoEncoderName, id)
                    val relation = if (sidecar == null) baseRelation else JsonObject(baseRelation + mapOf(
                        "audioPolicy" to JsonPrimitive("sidecar-pcm-normalized-calibrated-aac"),
                        "audioMime" to JsonPrimitive("audio/mp4a-latm"),
                        "sidecarSource" to proxySidecarReceipt(sidecar, requireNotNull(export.sidecarCalibration))))
                    requireNotNull(resolver.openOutputStream(metadata, "w")).use { it.write(relation.toString().toByteArray()) }
                    val operations = inserted.map { uri -> ContentProviderOperation.newUpdate(uri)
                        .withValue(MediaStore.MediaColumns.IS_PENDING, 0).withExpectedCount(1).build() }
                    resolver.applyBatch(MediaStore.AUTHORITY, ArrayList(operations))
                    verifyPair(relation, result)
                    check(proxyHash(context, original) == beforeHash) { "Original changed before proxy commit" }
                    catalog.freshSnapshot(take)
                    if (sidecar != null) verifyProxySidecarSource(context, take, sidecar)
                    check(directory.isDirectory || directory.mkdirs())
                    val record = receipt(take); val stream = record.startWrite()
                    try { stream.write(relation.toString().toByteArray()); record.finishWrite(stream) }
                    catch (failure: Throwable) { record.failWrite(stream); throw failure }
                    committed = true
                    result
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (!committed) {
                        val failures = inserted.mapNotNull { uri -> runCatching { check(resolver.delete(uri, null, null) == 1) }.exceptionOrNull() }
                        check(failures.isEmpty()) { "Proxy cleanup incomplete: ${failures.joinToString { it.message.orEmpty() }}" }
                    }
                } finally {
                    try { candidate?.let { check(!it.exists() || it.delete()) { "Proxy temporary file cleanup failed" } } }
                    finally { try { reservation?.close() } finally { active.unlock() } }
                }
            }
        }
    }

    /** A stable queued ID discovers rows even if the process died between insert and journaling.
     * A committed receipt always wins. Unknown/corrupt identity halts rather than deleting media. */
    suspend fun reconcile(take: LocalMediaTake, id: String): MediaProxyResult? {
        require(canonicalMediaId(id))
        check(active.tryLock()) { "Another proxy operation is active" }
        var reservation: AutoCloseable? = null
        try {
            reservation = WebDavTransferRuntime.get(context).reserveIdleMediaMutation()
            return withContext(Dispatchers.IO) {
                val result = existing(take)
                check(result == null || result.proxyId == id) { "Proxy receipt belongs to a different job" }
                if (result == null) {
                    check(MediaStore.VOLUME_EXTERNAL_PRIMARY in MediaStore.getExternalVolumeNames(context)) { "Proxy volume unavailable" }
                    fun rows(collection: Uri, root: String, extension: String, mime: String): List<Uri> {
                        @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
                        return requireNotNull(resolver.query(all, arrayOf("_id", "_display_name", "relative_path", "mime_type", "owner_package_name", "is_pending"),
                            "relative_path = ?", arrayOf("$root/OpenCineCamProxies/$id/"), "_id ASC")) { "Proxy recovery enumeration unavailable" }.use { cursor ->
                            check(cursor.count <= 1) { "Unexpected proxy namespace members" }
                            buildList {
                                while (cursor.moveToNext()) {
                                    check(cursor.getLong(0) > 0 && cursor.getString(1) == "proxy-$id.$extension" &&
                                        cursor.getString(2) == "$root/OpenCineCamProxies/$id/" && cursor.getString(3) == mime &&
                                        cursor.getString(4) == context.packageName && cursor.getInt(5) in 0..1) { "Proxy recovery identity differs" }
                                    add(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)))
                                }
                            }
                        }
                    }
                    val video = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val metadata = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    fun ownedRows() = rows(video, "Movies", "mp4", "video/mp4") + rows(metadata, "Download", "json", "application/json")
                    val abandoned = ownedRows() // Validate BOTH namespaces before any deletion.
                    withContext(NonCancellable) {
                        abandoned.forEach { uri ->
                            val isVideo = mediaDeleteIdentity(uri.toString())?.collection == MediaDeleteCollection.VIDEO
                            val root = if (isVideo) "Movies" else "Download"
                            val extension = if (isVideo) "mp4" else "json"
                            val mime = if (isVideo) "video/mp4" else "application/json"
                            check(resolver.delete(uri, "owner_package_name = ? AND relative_path = ? AND _display_name = ? AND mime_type = ?",
                                arrayOf(context.packageName, "$root/OpenCineCamProxies/$id/", "proxy-$id.$extension", mime)) == 1) { "Proxy recovery delete failed" }
                        }
                        check(ownedRows().isEmpty()) { "Proxy recovery remains incomplete" }
                    }
                }
                // Even a committed export may have died before deleting its private candidate.
                val candidate = File(context.cacheDir, "proxy-$id.mp4")
                check(!candidate.exists() || candidate.delete()) { "Proxy candidate recovery failed" }
                result
            }
        } finally { try { reservation?.close() } finally { active.unlock() } }
    }

    suspend fun recoverDeletions(committed: suspend (String, String) -> Unit) {
        if (deletions.pending().isEmpty()) return
        withDeletionOwnership {
            for (intent in deletions.pending()) deletions.recover(intent.jobId, deletionAccess(committed))
        }
    }

    /** Historical source identity is retained in the tombstone but never read or deleted. */
    suspend fun deleteProxy(entry: ProxyCatalogEntry, committed: suspend (String, String) -> Unit) {
        withDeletionOwnership {
            val expected = entry.result
            val pending = deletions.read(expected.proxyId)
            if (pending != null) {
                val intent = catalogDeletionIntent(entry, pending.metadata.bytes)
                check(pending == intent) { "Proxy deletion catalog selection changed" }
                deletions.recover(expected.proxyId, deletionAccess(committed))
            } else {
                verifyCatalogEntry(entry)
                val metadata = readCatalogReceipt(catalogReceipt(entry.takeId))
                check(MessageDigest.getInstance("SHA-256").digest(metadata).proxyHex() == entry.receiptSha256) {
                    "Proxy deletion receipt changed before intent"
                }
                deletions.begin(catalogDeletionIntent(entry, metadata.size.toLong()), deletionAccess(committed))
            }
        }
    }

    private fun catalogDeletionIntent(entry: ProxyCatalogEntry, metadataBytes: Long): ProxyDeletionIntent {
        val proxy = entry.result
        return ProxyDeletionIntent(proxy.proxyId, entry.takeId, proxy.originalUri, proxy.originalSha256,
            ProxyDeletionMember(proxy.proxyUri, proxy.proxyDisplayName, "video/mp4", "Movies/OpenCineCamProxies/${proxy.proxyId}/",
                context.packageName, proxy.proxyBytes, proxy.proxySha256),
            ProxyDeletionMember(proxy.metadataUri, "proxy-${proxy.proxyId}.json", "application/json", "Download/OpenCineCamProxies/${proxy.proxyId}/",
                context.packageName, metadataBytes, entry.receiptSha256))
    }

    suspend fun deleteProxy(take: LocalMediaTake, expected: MediaProxyResult, committed: suspend (String, String) -> Unit) {
        withDeletionOwnership {
            val pending = deletions.read(expected.proxyId)
            if (pending != null) {
                check(pending.takeId == take.id && pending.video.uri == expected.proxyUri && pending.metadata.uri == expected.metadataUri)
                deletions.recover(expected.proxyId, deletionAccess(committed))
            } else {
                check(existing(take) == expected) { "Proxy selection changed; reopen its actions" }
                val metadata = receipt(take).openRead().use { it.readBytes() }
                fun member(uri: String, root: String, extension: String, mime: String, bytes: Long, hash: String) =
                    ProxyDeletionMember(uri, if (extension == "mp4") expected.proxyDisplayName else "proxy-${expected.proxyId}.$extension", mime,
                        "$root/OpenCineCamProxies/${expected.proxyId}/", context.packageName, bytes, hash)
                val intent = ProxyDeletionIntent(expected.proxyId, take.id, expected.originalUri, expected.originalSha256,
                    member(expected.proxyUri, "Movies", "mp4", "video/mp4", expected.proxyBytes, expected.proxySha256),
                    member(expected.metadataUri, "Download", "json", "application/json", metadata.size.toLong(),
                        MessageDigest.getInstance("SHA-256").digest(metadata).proxyHex()))
                deletions.begin(intent, deletionAccess(committed))
            }
        }
    }

    private suspend fun withDeletionOwnership(block: suspend () -> Unit) {
        if (!active.tryLock()) throw ProxyRecoveryBusyException()
        var reservation: AutoCloseable? = null
        try {
            reservation = try { WebDavTransferRuntime.get(context).reserveIdleMediaMutation() }
                catch (busy: IllegalStateException) { throw ProxyRecoveryBusyException(busy) }
            withContext(Dispatchers.IO) { block() }
        } finally { try { reservation?.close() } finally { active.unlock() } }
    }

    private fun deletionAccess(committed: suspend (String, String) -> Unit) = object : ProxyDeletionAccess {
        private fun available() {
            check(MediaStore.VOLUME_EXTERNAL_PRIMARY in MediaStore.getExternalVolumeNames(context)) { "Proxy volume unavailable" }
        }
        override suspend fun isAbsent(member: ProxyDeletionMember): Boolean {
            available()
            @Suppress("DEPRECATION") val uri = MediaStore.setIncludePending(member.uri.toUri())
            return requireNotNull(resolver.query(uri, arrayOf("_id"), null, null, null)) {
                "Proxy absence query unavailable"
            }.use { it.count == 0 }
        }
        override suspend fun validate(intent: ProxyDeletionIntent) {
            available()
            for ((member, video) in listOf(intent.video to true, intent.metadata to false)) {
                val root = if (video) "Movies" else "Download"
                val extension = if (video) "mp4" else "json"
                val mime = if (video) "video/mp4" else "application/json"
                check(member.owner == context.packageName && member.relativePath == "$root/OpenCineCamProxies/${intent.jobId}/" &&
                    (if (video) runCatching { requireProxyFilename(member.name) }.isSuccess else member.name == "proxy-${intent.jobId}.$extension") && member.mimeType == mime &&
                    mediaDeleteIdentity(member.uri)?.collection == if (video) MediaDeleteCollection.VIDEO else MediaDeleteCollection.METADATA) {
                    "Proxy deletion identity differs"
                }
                if (isAbsent(member)) continue
                @Suppress("DEPRECATION") val uri = MediaStore.setIncludePending(member.uri.toUri())
                requireNotNull(resolver.query(uri, arrayOf("_display_name", "relative_path", "mime_type", "owner_package_name", "is_pending", "_size"), null, null, null)).use { row ->
                    check(row.count == 1 && row.moveToFirst() && row.getString(0) == member.name &&
                        row.getString(1) == member.relativePath && row.getString(2) == member.mimeType &&
                        row.getString(3) == member.owner && row.getInt(4) == 0 && row.getLong(5) == member.bytes) {
                        "Proxy deletion member changed"
                    }
                }
                check(proxyHash(context, member.uri.toUri()) == member.sha256) { "Proxy deletion bytes changed" }
            }
        }
        override suspend fun delete(member: ProxyDeletionMember) {
            val count = resolver.delete(member.uri.toUri(),
                "owner_package_name = ? AND relative_path = ? AND _display_name = ? AND mime_type = ? AND is_pending = 0 AND _size = ?",
                arrayOf(member.owner, member.relativePath, member.name, member.mimeType, member.bytes.toString()))
            check(count in 0..1 && isAbsent(member)) { "Proxy deletion not confirmed" }
        }
        override suspend fun verifyEmpty(intent: ProxyDeletionIntent) {
            available()
            for ((member, collection) in listOf(intent.video to MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                intent.metadata to MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))) {
                check(isAbsent(member)) { "Proxy member remains" }
                @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
                requireNotNull(resolver.query(all, arrayOf("_id"), "relative_path = ?", arrayOf(member.relativePath), null)).use {
                    check(it.count == 0) { "Proxy namespace is not empty" }
                }
            }
        }
        override suspend fun commitDeletion(intent: ProxyDeletionIntent) {
            val record = receiptForId(intent.takeId)
            if (record.baseFile.exists()) {
                check(record.baseFile.length() <= 64 * 1024)
                val bytes = record.openRead().use { it.readBytes() }
                check(bytes.size.toLong() == intent.metadata.bytes && MessageDigest.getInstance("SHA-256").digest(bytes).proxyHex() == intent.metadata.sha256) {
                    "Proxy receipt bytes changed"
                }
                val obj = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                val result = decode(obj)
                check(obj.getValue("takeId").jsonPrimitive.content == intent.takeId && result.proxyId == intent.jobId &&
                    result.proxyUri == intent.video.uri && result.metadataUri == intent.metadata.uri) { "Proxy receipt identity changed" }
                record.delete()
                check(!record.baseFile.exists()) { "Proxy receipt removal failed" }
            }
            committed(intent.jobId, intent.takeId)
        }
    }

    private suspend fun verifyPair(relation: JsonObject, result: MediaProxyResult) {
        val id = relation.getValue("proxyId").jsonPrimitive.content
        require(canonicalMediaId(id))
        requireProxyFilename(result.proxyDisplayName)
        val metadataBytes = relation.toString().toByteArray()
        suspend fun row(uri: String, root: String, extension: String, mime: String, bytes: Long) {
            require(mediaDeleteIdentity(uri)?.collection == if (extension == "mp4") MediaDeleteCollection.VIDEO else MediaDeleteCollection.METADATA)
            repeat(21) { attempt ->
                currentCoroutineContext().ensureActive()
                val observedSize = requireNotNull(resolver.query(uri.toUri(), arrayOf("_display_name", "relative_path", "mime_type", "owner_package_name", "is_pending", "_size"), null, null, null)).use { cursor ->
                    check(cursor.count == 1 && cursor.moveToFirst()) { "Proxy row missing" }
                    check(cursor.getString(0) == (if (extension == "mp4") result.proxyDisplayName else "proxy-$id.$extension") && cursor.getString(1) == "$root/OpenCineCamProxies/$id/" &&
                        cursor.getString(2) == mime && cursor.getString(3) == context.packageName && cursor.getInt(4) == 0) {
                        "Proxy publication identity or pending state differs"
                    }
                    cursor.getLong(5)
                }
                if (observedSize == bytes) return
                check(attempt < 20) { "Proxy published length differs: expected=$bytes observed=$observedSize" }
                delay(50) // MediaProvider may finish size refresh asynchronously after writer close.
            }
        }
        row(result.proxyUri, "Movies", "mp4", "video/mp4", result.proxyBytes)
        row(result.metadataUri, "Download", "json", "application/json", metadataBytes.size.toLong())
        val actual = requireNotNull(resolver.openInputStream(result.metadataUri.toUri())).use { input ->
            val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(bytes.size() + count <= 64 * 1024); bytes.write(buffer, 0, count) }
            bytes.toByteArray()
        }
        check(actual.contentEquals(metadataBytes)) { "Proxy relation bytes differ" }
        check(proxyHash(context, result.proxyUri.toUri()) == result.proxySha256) { "Proxy bytes differ after publication" }
    }

    private fun receipt(take: LocalMediaTake): AtomicFile = receiptForId(take.id)
    private fun receiptForId(takeId: String): AtomicFile {
        val key = MessageDigest.getInstance("SHA-256").digest(takeId.toByteArray()).proxyHex()
        return AtomicFile(File(directory, "$key.json"))
    }
    private fun encode(take: LocalMediaTake, result: MediaProxyResult, source: ProxyMediaProbe, encoder: String, id: String) = buildJsonObject {
        put("schema", "opencinecam.proxy.v1"); put("takeId", take.id); put("proxyId", id)
        put("originalUri", result.originalUri); put("proxyUri", result.proxyUri); put("metadataUri", result.metadataUri)
        put("proxyDisplayName", result.proxyDisplayName)
        put("originalSha256", result.originalSha256); put("proxySha256", result.proxySha256)
        put("originalBytes", result.originalBytes); put("proxyBytes", result.proxyBytes)
        put("width", result.width); put("height", result.height); put("frames", result.frames)
        put("durationUs", result.durationUs); put("requestedBitrate", result.requestedBitrate)
        put("encoder", encoder); put("audioPolicy", "preserve-packets"); put("audioMime", source.audio?.mime ?: "none")
        put("firstVideoPtsUs", source.video.timestampsUs.min()); put("lastVideoPtsUs", source.video.timestampsUs.max())
        put("videoPtsSha256", MessageDigest.getInstance("SHA-256").digest(source.video.timestampsUs.sorted().joinToString(",").toByteArray()).proxyHex())
        put("originalDisplayName", take.primary.name)
    }
    private fun decode(obj: JsonObject): MediaProxyResult {
        fun text(key: String) = obj.getValue(key).jsonPrimitive.content
        check(text("schema") == "opencinecam.proxy.v1")
        return MediaProxyResult(text("originalUri"), text("proxyUri"), text("metadataUri"), text("originalSha256"), text("proxySha256"),
            text("originalBytes").toLong(), text("proxyBytes").toLong(), text("width").toInt(), text("height").toInt(),
            text("frames").toInt(), text("durationUs").toLong(), text("requestedBitrate").toInt(), text("proxyId"),
            obj["proxyDisplayName"]?.jsonPrimitive?.also { require(it.isString) }?.content ?: "proxy-${text("proxyId")}.mp4")
    }
    private companion object { val active = Mutex() }
}
