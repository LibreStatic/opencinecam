/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import com.librestatic.opencinecam.GallerySettings
import java.io.File

/** Compatibility surface for the capture button; only selected recent items load thumbnails. */
data class LocalMediaItem(val uri: Uri, val name: String, val mimeType: String,
    val sizeBytes: Long, val modifiedSeconds: Long, val thumbnail: Bitmap?)

class LocalMediaRepository(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    /** One bounded scan, not necessarily one full page. Empty filtered pages can have continuation.
     * The UI must keep requesting next until enough matches or next == null, off the main thread.
     * Provider/journal failures throw rather than masquerading as a successfully empty library. */
    fun page(settings: GallerySettings, query: String = "", cursor: LocalMediaCursor? = null,
        limit: Int = 60): LocalMediaPage {
        require(limit in 1..60)
        require(query.length <= 512)
        val filter = catalogFilter(settings, query)
        require(cursor == null || cursor.filter == filter) { "Gallery cursor belongs to a different filter" }
        val positions = cursor?.positions.orEmpty().toMutableMap()
        val exhausted = cursor?.exhausted.orEmpty().toMutableSet()
        // Collect provider candidates BEFORE observing committed journal state. No writer lock is
        // held while doing provider/metadata IO; the durable SQLite receipt snapshot is sufficient.
        val batches = LocalMediaKind.entries.filter { it !in exhausted }.associateWith { kind ->
            candidates(kind, positions[kind], settings.newestFirst)
        }
        val blocked = uncommittedNamespaces()
        val candidates = batches.values.flatten().sortedWith(catalogComparator(settings.newestFirst))
        val consumed = mutableMapOf<LocalMediaKind, Int>()
        val groups = mutableMapOf<MediaNamespace, LocalMediaTake?>()
        val result = mutableListOf<LocalMediaTake>()
        for (candidate in candidates.take(SCAN_BUDGET)) {
            val kind = requireNotNull(candidate.kind)
            positions[kind] = MediaPosition(candidate.artifact.modifiedSeconds, candidate.id)
            consumed[kind] = (consumed[kind] ?: 0) + 1
            if (!candidate.eligible || candidate.namespace?.key in blocked) continue
            val take = candidate.namespace?.let { namespace ->
                if (!groups.containsKey(namespace)) groups[namespace] = readTake(namespace, candidate)
                groups[namespace]
            } ?: if (candidate.namespace == null) LocalMediaTake(
                "legacy:${candidate.artifact.uri}", candidate.artifact, listOf(candidate.artifact), emptyList(),
                kind, null, LocalMediaRelationStatus.LEGACY,
            ) else null
            // Only the durable primary's candidate may emit the group. Secondary members are
            // consumed without emitting, so keyset pages never need an ever-growing seen-ID set.
            if (take != null && take.primary.uri == candidate.artifact.uri && catalogMatches(take, settings, query)) result += take
            if (result.size == limit) break
        }
        batches.forEach { (kind, batch) ->
            if (batch.size < CANDIDATE_BATCH && (consumed[kind] ?: 0) == batch.size) exhausted += kind
        }
        val next = if (exhausted.size == LocalMediaKind.entries.size) null
            else LocalMediaCursor(filter, positions.toMap(), exhausted.toSet())
        return LocalMediaPage(result.toList(), next)
    }

    fun thumbnail(artifact: LocalMediaArtifact): Bitmap? = runCatching {
        resolver.loadThumbnail(artifact.uri.toUri(), Size(256, 256), null)
    }.getOrNull()

    fun recent(limit: Int = 60): List<LocalMediaItem> {
        require(limit in 1..60)
        // Capture thumbnail lookup is bounded too. Gallery continuation is owned by page callers.
        return page(GallerySettings(), limit = limit).takes.map { take ->
            val artifact = take.primary
            LocalMediaItem(artifact.uri.toUri(), artifact.name, artifact.mimeType, artifact.sizeBytes,
                artifact.modifiedSeconds, thumbnail(artifact))
        }
    }


    /** Targeted re-observation for sharing. It never searches the library or repairs capture state. */
    internal fun freshSnapshot(selected: LocalMediaTake): MediaShareSnapshot {
        checkShareRunning()
        require(selected.originals.size in 1..32 && selected.metadata.size <= 4)
        require(selected.originals.map { it.uri }.distinct().size == selected.originals.size)
        require(selected.primary in selected.originals)
        val candidates = selected.originals.map { artifact ->
            checkShareRunning()
            val identity = requireNotNull(mediaOriginalIdentity(artifact.uri)) { "Untrusted selected media URI" }
            val found = rows(collection(identity.first), identity.first,
                "_id = ? AND owner_package_name = ? AND is_pending = 0", arrayOf(identity.second.toString(), context.packageName), "_id ASC", 2)
            check(found.size == 1 && found.single().eligible && found.single().artifact == artifact) { "Selected media changed; refresh gallery" }
            found.single()
        }
        val blocked = uncommittedNamespaces()
        check(candidates.none { it.namespace?.key in blocked }) { "Selected take is not committed" }
        val primary = candidates.single { it.artifact.uri == selected.primary.uri }
        var documents = emptyList<MetadataDocument>()
        val fresh = primary.namespace?.let { namespace ->
            check(candidates.all { it.namespace == namespace }) { "Selected media belongs to different takes" }
            requireNotNull(readTake(namespace, primary) { documents = it })
        } ?: run {
            check(candidates.size == 1) { "Legacy files have no declared grouping" }
            LocalMediaTake("legacy:${primary.artifact.uri}", primary.artifact, listOf(primary.artifact), emptyList(),
                requireNotNull(primary.kind), null, LocalMediaRelationStatus.LEGACY)
        }
        requireUnchangedShareTake(selected, fresh)
        checkShareRunning()
        return MediaShareSnapshot(fresh, documents)
    }


    /** Deletion requires a complete provider enumeration, including pending rows. A gallery page
     * may display a bounded incomplete take, but that is never authority to delete a truncated set. */
    internal fun deletionSnapshot(selected: LocalMediaTake): MediaDeletePlan {
        val snapshot = freshSnapshot(selected)
        check(snapshot.documents.all { it.text != null && !it.disappeared }) { "Deletion metadata read is uncertain or exceeds bounds" }
        val primaryIdentity = requireNotNull(mediaDeleteIdentity(selected.primary.uri))
        val primary = strictDeleteRows(deleteCollection(primaryIdentity.collection),
            "_id = ?", arrayOf(primaryIdentity.id.toString()), 1).single()
        check(primary.artifact == selected.primary && primary.owner == context.packageName && primary.pending == 0)
        val namespace = mediaNamespace(primary.relativePath)
        val observed = namespace?.let(::strictDeleteNamespace) ?: listOf(primary)
        // The actual candidates precede the read-only journal observation, as in gallery queries.
        check(namespace?.key !in uncommittedNamespaces()) { "Deletion take is not committed" }
        return mediaDeletePlan(selected, observed, context.packageName)
    }

    internal fun observeMutationRow(artifact: LocalMediaArtifact): MediaDeleteRow {
        val identity = requireNotNull(mediaDeleteIdentity(artifact.uri)) { "Untrusted mutation URI" }
        return strictDeleteRows(deleteCollection(identity.collection), "_id = ?", arrayOf(identity.id.toString()), 1).single()
    }

    internal fun verifyDeletionEmpty(plan: MediaDeletePlan) {
        val namespace = plan.namespace
        if (namespace != null) {
            check(strictDeleteNamespace(namespace).isEmpty()) { "Deletion namespace has retained or new members" }
            check(namespace.key !in uncommittedNamespaces()) { "Deletion namespace commit state changed" }
        }
    }

    private fun strictDeleteNamespace(namespace: MediaNamespace): List<MediaDeleteRow> {
        val kinds = if (namespace.recording) listOf(MediaDeleteCollection.VIDEO, MediaDeleteCollection.AUDIO, MediaDeleteCollection.METADATA)
            else listOf(MediaDeleteCollection.PHOTO, MediaDeleteCollection.METADATA)
        return kinds.flatMap { kind ->
            val root = when (kind) { MediaDeleteCollection.PHOTO, MediaDeleteCollection.VIDEO -> "DCIM"; MediaDeleteCollection.AUDIO -> "Music"; MediaDeleteCollection.METADATA -> "Download" }
            strictDeleteRows(deleteCollection(kind), "owner_package_name = ? AND relative_path LIKE ? ESCAPE '\\'",
                arrayOf(context.packageName, escapeLike(namespace.path(root)) + "%"), if (kind == MediaDeleteCollection.METADATA) METADATA_BOUND else MEMBER_BOUND)
                .also { rows -> check(rows.all { it.owner == context.packageName && mediaNamespace(it.relativePath) == namespace && it.pending == 0 }) {
                    "Deletion namespace contains pending or uncertain members"
                } }
        }
    }

    private fun deleteCollection(kind: MediaDeleteCollection): Uri = when (kind) {
        MediaDeleteCollection.PHOTO -> collection(LocalMediaKind.PHOTO)
        MediaDeleteCollection.VIDEO -> collection(LocalMediaKind.VIDEO)
        MediaDeleteCollection.AUDIO -> collection(LocalMediaKind.AUDIO)
        MediaDeleteCollection.METADATA -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    private fun strictDeleteRows(collection: Uri, selection: String, args: Array<String>, bound: Int): List<MediaDeleteRow> {
        @Suppress("DEPRECATION") val includingPending = MediaStore.setIncludePending(collection)
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "_id ASC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, bound + 1)
        }
        return requireNotNull(resolver.query(includingPending, PROJECTION, query, null)) { "Deletion enumeration unavailable" }.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check(size < bound) { "Deletion namespace exceeds member bound" }
                    check(listOf(0, 3, 4, 7).all { cursor.getType(it) == Cursor.FIELD_TYPE_INTEGER }) { "Deletion row has uncertain scalar identity" }
                    check(listOf(1, 2, 5, 6).all { cursor.getType(it) == Cursor.FIELD_TYPE_STRING }) { "Deletion row has uncertain text identity" }
                    val id = cursor.getLong(0)
                    check(id > 0 && cursor.getLong(3) >= 0 && cursor.getLong(4) >= 0 && cursor.getLong(7) in 0L..1L)
                    val artifact = LocalMediaArtifact(ContentUris.withAppendedId(collection, id).toString(), cursor.getString(1),
                        cursor.getString(2), cursor.getLong(3), cursor.getLong(4))
                    add(MediaDeleteRow(artifact, cursor.getString(5), cursor.getString(6), cursor.getInt(7)))
                }
            }
        }
    }

    private fun candidates(kind: LocalMediaKind, position: MediaPosition?, newestFirst: Boolean): List<CatalogRow> {
        val path = "${root(kind)}/OpenCineCam/"
        val args = mutableListOf(context.packageName, "$path%")
        var selection = "owner_package_name = ? AND is_pending = 0 AND relative_path LIKE ?"
        if (position != null) {
            selection += " AND (date_modified ${if (newestFirst) "<" else ">"} ? OR (date_modified = ? AND _id > ?))"
            args += listOf(position.modified.toString(), position.modified.toString(), position.id.toString())
        }
        return rows(collection(kind), kind, selection, args.toTypedArray(),
            "date_modified ${if (newestFirst) "DESC" else "ASC"}, _id ASC", CANDIDATE_BATCH)
    }

    private fun readTake(namespace: MediaNamespace, candidate: CatalogRow,
        captureDocuments: ((List<MetadataDocument>) -> Unit)? = null): LocalMediaTake? {
        var incomplete = false
        val kinds = if (namespace.recording) listOf(LocalMediaKind.VIDEO, LocalMediaKind.AUDIO) else listOf(LocalMediaKind.PHOTO)
        val members = kinds.flatMap { kind ->
            val found = namespaceRows(namespace, root(kind), collection(kind), kind, MEMBER_BOUND + 1)
            if (found.size > MEMBER_BOUND) incomplete = true
            found.take(MEMBER_BOUND)
        }.toMutableList()
        // Concurrent deletion is observable as incomplete, never a request to compensate/delete.
        if (members.none { it.artifact.uri == candidate.artifact.uri }) {
            members += candidate
            incomplete = true
        }
        val metadata = namespaceRows(namespace, "Download",
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), null, METADATA_BOUND + 1)
        if (metadata.size > METADATA_BOUND) incomplete = true
        val documents = metadata.take(METADATA_BOUND).map { row ->
            if (row.artifact.mimeType == "application/json") readMetadata(row.artifact) else MetadataDocument(row.artifact, null)
        }
        captureDocuments?.invoke(documents)
        return catalogTake(namespace, members, documents, incomplete)
    }

    private fun namespaceRows(namespace: MediaNamespace, root: String, uri: Uri,
        kind: LocalMediaKind?, bound: Int): List<CatalogRow> = rows(uri, kind,
        "owner_package_name = ? AND is_pending = 0 AND relative_path LIKE ?",
        arrayOf(context.packageName, escapeLike(namespace.path(root)) + "%"), "_id ASC", bound,
        escapePath = true).filter { it.eligible && it.namespace == namespace }

    private fun rows(collection: Uri, kind: LocalMediaKind?, selection: String, args: Array<String>,
        order: String, bound: Int, escapePath: Boolean = false): List<CatalogRow> {
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection + if (escapePath) " ESCAPE '\\'" else "")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
            putInt(ContentResolver.QUERY_ARG_LIMIT, bound)
        }
        return requireNotNull(resolver.query(collection, PROJECTION, bundle, null)) {
            "Gallery MediaStore query failed"
        }.use { cursor ->
            buildList {
                var scanned = 0
                // Providers may ignore QUERY_ARG_LIMIT: never iterate/decode beyond our cap.
                while (scanned < bound && cursor.moveToNext()) {
                    scanned++
                    val id = cursor.getLong(0)
                    val path = cursor.getString(5).orEmpty()
                    val name = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2).orEmpty()
                    val owner = cursor.getString(6)
                    val pending = cursor.getInt(7)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val namespace = mediaNamespace(path)
                    val compatible = when (kind) {
                        LocalMediaKind.PHOTO -> namespace?.recording != true && mime.startsWith("image/")
                        LocalMediaKind.VIDEO -> namespace?.recording != false && mime.startsWith("video/")
                        LocalMediaKind.AUDIO -> namespace?.recording != false && mime.startsWith("audio/")
                        null -> true
                    }
                    add(CatalogRow(LocalMediaArtifact(uri, name, mime, cursor.getLong(3), cursor.getLong(4)),
                        id, kind, namespace, id > 0 && owner == context.packageName && pending == 0 &&
                            isCatalogPath(path) && compatible))
                }
            }
        }
    }

    private fun readMetadata(artifact: LocalMediaArtifact): MetadataDocument = runCatching {
        require(artifact.sizeBytes in 1..METADATA_BYTES.toLong())
        requireNotNull(resolver.openInputStream(artifact.uri.toUri())).use { stream ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (bytes.size() <= METADATA_BYTES) {
                checkShareRunning()
                val count = stream.read(buffer, 0, minOf(buffer.size, METADATA_BYTES + 1 - bytes.size()))
                if (count == -1) break
                check(count > 0)
                bytes.write(buffer, 0, count)
            }
            require(bytes.size() in 1..METADATA_BYTES)
            // Reject malformed UTF-8 rather than accepting replacement characters in identities.
            MetadataDocument(artifact, Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString())
        }
    }.getOrElse { failure ->
        if (failure is java.util.concurrent.CancellationException) throw failure
        MetadataDocument(artifact, null, failure is java.io.FileNotFoundException)
    }

    private fun uncommittedNamespaces(): Set<String> = buildSet {
        check(context.noBackupFilesDir.isDirectory && context.noBackupFilesDir.canRead()) { "Gallery recovery directory unavailable" }
        for ((name, prefix) in listOf("still-recovery.sqlite" to "still:", "recording-recovery.sqlite" to "take:")) {
            val file = File(context.noBackupFilesDir, name)
            if (!file.exists()) continue
            check(file.isFile) { "Gallery recovery journal is not a file: $name" }
            try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY,
                    { throw IllegalStateException("Gallery recovery journal is corrupt: $name") }).use { db ->
                    check(db.version == 1) { "Unsupported gallery recovery journal version: $name" }
                    db.rawQuery("SELECT id, committed FROM captures ORDER BY id LIMIT 129", null).use { cursor ->
                        var count = 0
                        val ids = mutableSetOf<String>()
                        while (cursor.moveToNext()) {
                            check(++count <= 128) { "Gallery recovery journal exceeds capacity: $name" }
                            check(cursor.getType(0) == Cursor.FIELD_TYPE_STRING && canonicalMediaId(cursor.getString(0)))
                            check(ids.add(cursor.getString(0))) { "Duplicate gallery recovery identity: $name" }
                            check(cursor.getType(1) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(1) in 0L..1L)
                            if (cursor.getLong(1) == 0L) add(prefix + cursor.getString(0))
                        }
                    }
                }
            } catch (failure: Exception) {
                throw IllegalStateException("Gallery recovery state unavailable: $name", failure)
            }
        }
    }

    private fun root(kind: LocalMediaKind): String = if (kind == LocalMediaKind.AUDIO) "Music" else "DCIM"
    private fun collection(kind: LocalMediaKind): Uri = when (kind) {
        LocalMediaKind.PHOTO -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        LocalMediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        LocalMediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
    private fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    companion object {
        private const val CANDIDATE_BATCH = 64
        private const val SCAN_BUDGET = 120
        private const val MEMBER_BOUND = 16
        private const val METADATA_BOUND = 4
        private const val METADATA_BYTES = 512 * 1024
        private val PROJECTION = arrayOf("_id", "_display_name", "mime_type", "_size", "date_modified",
            "relative_path", "owner_package_name", "is_pending")
    }
}
