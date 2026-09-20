/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.librestatic.opencinecam.LutLibrary
import com.librestatic.opencinecam.MediaSharingSettings
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

private val mediaExportLock = Any()

/** Preparation is not proof of delivery. Published cache expires 24h after preparation; original
 * provider files are never copied, changed or deleted. UI marks immediately before chooser launch. */
class PreparedMediaShare internal constructor(files: List<PreparedMediaShareFile>,
    val expiresAtEpochMs: Long, private val session: File) {
    val files: List<PreparedMediaShareFile> = Collections.unmodifiableList(files.toList())
    private var published = false
    private var discarded = false
    @Synchronized fun markPublished() { check(!discarded); published = true }
    @Synchronized fun buildIntent(): Intent {
        check(!discarded && files.isNotEmpty())
        requirePortableShareNames(files.map { it.name })
        val uris = ArrayList(files.map { it.uri.toUri() })
        val types = files.map { it.mimeType }.distinct()
        val clips = ClipData(ClipDescription("Take files", types.toTypedArray()), ClipData.Item(uris.first()))
        uris.drop(1).forEach { clips.addItem(ClipData.Item(it)) }
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = types.singleOrNull() ?: "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = clips
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    /** IO; cancellation may discard only before markPublished. Retained sessions expire naturally. */
    fun discard() {
        val remove = synchronized(this) { if (published || discarded) false else { discarded = true; true } }
        if (remove) synchronized(mediaExportLock) { deleteOwnMediaShareSession(session) }
    }
}

class MediaShareExporter(context: Context) {
    private val context = context.applicationContext
    private val repository = LocalMediaRepository(this.context)

    fun prepare(take: LocalMediaTake, settings: MediaSharingSettings): PreparedMediaShare = synchronized(mediaExportLock) {
        checkShareRunning()
        val snapshot = repository.freshSnapshot(take)
        val plan = mediaSharePlan(snapshot, settings)
        val now = System.currentTimeMillis()
        val root = File(context.cacheDir.canonicalFile, "media-exports")
        check(root.canonicalFile == root.absoluteFile) { "Unexpected export cache location" }
        check(root.isDirectory || root.mkdir()) { "Export cache unavailable" }
        val sessions = boundedChildren(root, MEDIA_SHARE_MAX_SESSIONS)
        sessions.forEach { session ->
            requireOwnMediaShareSession(session)
            if (mediaShareSessionExpired(session.name, session.lastModified(), now)) deleteOwnMediaShareSession(session)
        }
        val retained = boundedChildren(root, MEDIA_SHARE_MAX_SESSIONS)
        check(retained.size < MEDIA_SHARE_MAX_SESSIONS) { "Export cache session capacity reached; retained sessions expire after 24 hours" }
        val used = retained.sumOf { session -> requireOwnMediaShareSession(session).sumOf { it.length() } }
        check(used <= MEDIA_SHARE_MAX_CACHE_BYTES) { "Export cache byte capacity exceeded" }
        val session = File(root, UUID.randomUUID().toString())
        check(session.mkdir()) { "Export session allocation failed" }
        try {
            var written = 0L
            val files = mutableListOf<PreparedMediaShareFile>()
            fun generate(name: String, mime: String, role: MediaShareRole, bytes: ByteArray) {
                checkShareRunning()
                require(ownedShareFilename(name))
                check(used + written + bytes.size <= MEDIA_SHARE_MAX_CACHE_BYTES) { "Export cache byte capacity reached" }
                val output = File(session, name)
                check(!output.exists())
                output.outputStream().use { stream ->
                    var offset = 0
                    while (offset < bytes.size) {
                        checkShareRunning()
                        val size = minOf(8192, bytes.size - offset)
                        stream.write(bytes, offset, size); offset += size
                    }
                }
                written += bytes.size
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.mediaexports", output)
                files += PreparedMediaShareFile(uri.toString(), name, mime, role)
            }
            plan.originals.forEach { files += PreparedMediaShareFile(it.uri, it.name, it.mimeType, MediaShareRole.ORIGINAL) }
            plan.generated.forEach { generate(it.name, "application/json", it.role, it.text.toByteArray(Charsets.UTF_8)) }
            var lutBytes = 0L
            val library = if (settings.includeReferencedLut && plan.referencedLuts.isNotEmpty()) LutLibrary(context) else null
            val luts = plan.referencedLuts.map { hash ->
                checkShareRunning()
                if (!settings.includeReferencedLut) MediaShareLut(hash, null, null, false) else {
                    val bytes = requireNotNull(library).export(hash)
                    lutBytes += bytes.size
                    check(bytes.isNotEmpty() && lutBytes <= MEDIA_SHARE_MAX_LUT_BYTES) { "Referenced LUT byte budget exceeded" }
                    val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    check(actualHash == hash) { "Referenced LUT original hash mismatch" }
                    val name = "$hash.cube"
                    generate(name, "application/octet-stream", MediaShareRole.LUT, bytes)
                    MediaShareLut(hash, name, bytes.size.toLong(), true)
                }
            }
            generate("relationships.json", "application/json", MediaShareRole.RELATIONSHIP,
                mediaShareManifest(plan, luts).toByteArray(Charsets.UTF_8))
            // Re-read the exact take and exact bounded source texts after export IO. No silently
            // changed slate/member/file or query/read race is accepted as the user's selection.
            check(repository.freshSnapshot(take) == snapshot) { "Selected metadata changed during preparation; refresh gallery" }
            checkShareRunning()
            check(session.setLastModified(now))
            PreparedMediaShare(files, now + MEDIA_SHARE_EXPIRY_MS, session)
        } catch (failure: Throwable) {
            try { deleteOwnMediaShareSession(session) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}

private fun ownedShareFilename(name: String): Boolean = name in setOf("relationships.json", "take.production.json", "take.technical.json") ||
    name.matches(Regex("[0-9a-f]{64}\\.cube"))
private fun boundedChildren(directory: File, limit: Int): List<File> = buildList {
    Files.newDirectoryStream(directory.toPath()).use { entries ->
        for (entry in entries) {
            check(size < limit) { "Export cache entry capacity exceeded" }
            add(entry.toFile())
        }
    }
}
private fun requireOwnMediaShareSession(session: File): List<File> {
    check(canonicalMediaId(session.name) && session.isDirectory && session.canonicalFile == session.absoluteFile) { "Unexpected export cache entry" }
    return boundedChildren(session, MEDIA_SHARE_MAX_LUTS + 3).also { files ->
        check(files.all { it.isFile && it.canonicalFile == it.absoluteFile && ownedShareFilename(it.name) }) { "Unexpected export session contents" }
    }
}
private fun deleteOwnMediaShareSession(session: File) {
    if (!session.exists()) return
    requireOwnMediaShareSession(session).forEach { check(it.delete()) { "Export session cleanup failed" } }
    check(session.delete()) { "Export session cleanup failed" }
}
