/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.net.toUri
import com.librestatic.opencinecam.transfers.WebDavTransferRuntime
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.runBlocking

/** A confirmed, noncancellable local mutation. Compensation is verified best effort, not a
 * cross-provider transaction or crash-recovery protocol; original media bytes are never opened for writing. */
class MediaTakeRenamer internal constructor(private val access: MediaRenameAccess) {
    constructor(context: Context) : this(MediaStoreRenameAccess(context))
    fun rename(selected: LocalMediaTake, stem: String): MediaRenameResult {
        val preview = try { mediaRenamePreview(selected, stem) } catch (error: Exception) {
            return MediaRenameResult((selected.originals + selected.metadata).map {
                MediaRenameFileResult(it, it.name, null, MediaRenameStatus.NOT_ATTEMPTED)
            }, error.message ?: "Invalid rename preview")
        }
        return executeMediaRename(selected, preview, access)
    }
}

internal class MediaStoreRenameAccess(context: Context) : MediaRenameAccess {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val repository = LocalMediaRepository(this.context)
    private var linkedProxy: MediaProxyRenameLink? = null
    private var sourceLease: ProxySourceMutationLease? = null
    override fun reserve(): AutoCloseable {
        val lease = runBlocking { MediaProxyQueue.get(context).acquireSourceMutation() }
        val media = try {
            WebDavTransferRuntime.get(context).reserveIdleMediaMutation()
        } catch (error: Exception) {
            try { lease.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
        sourceLease = lease
        return AutoCloseable {
            var failure: Exception? = null
            try { media.close() } catch (error: Exception) { failure = error }
            try { lease.close() } catch (error: Exception) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            } finally { sourceLease = null }
            failure?.let { throw it }
        }
    }
    override fun preflight(selected: LocalMediaTake, preview: MediaRenamePreview): MediaRenamePlan {
        linkedProxy = null
        val scope = repository.deletionSnapshot(selected)
        val snapshot = repository.freshSnapshot(selected)
        val plan = mediaRenamePlan(selected, preview, scope.ordered, snapshot.documents)
        check(plan.rows.all { observe(it) == it }) { "Rename identity changed during preflight" }
        sourceLease?.bind(selected)
        linkedProxy = MediaProxyRenameLink.prepare(context, selected, preview.files.single { it.artifact == selected.primary }.newName)
        return plan
    }
    override fun holdOutbox(plan: MediaRenamePlan) = MediaRenameOutboxGuard.prepare(context, plan.rows.map { it.artifact.uri }.toSet())
    override fun observe(row: MediaDeleteRow): MediaDeleteRow = repository.observeMutationRow(row.artifact)

    override fun checkDestinations(rows: List<MediaDeleteRow>, names: Map<String, String>) {
        require(rows.map { it.artifact.uri }.toSet() == names.keys)
        requirePortableShareNames(names.values.toList())
        val directories = rows.groupBy { it.relativePath }
        for ((path, targets) in directories) {
            @Suppress("DEPRECATION") val files = MediaStore.setIncludePending(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            val query = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path = ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(path))
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "_id ASC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, 513)
            }
            requireNotNull(resolver.query(files, arrayOf("_id", "_display_name", "relative_path"), query, null)) { "Rename collision query unavailable" }.use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    check(++count <= 512) { "Rename directory exceeds collision-check bound" }
                    check(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getType(1) == Cursor.FIELD_TYPE_STRING && cursor.getString(2) == path)
                    val id = cursor.getLong(0)
                    val key = filenameKey(cursor.getString(1))
                    for (target in targets) {
                        if (requireNotNull(mediaDeleteIdentity(target.artifact.uri)).id != id)
                            check(key != filenameKey(names.getValue(target.artifact.uri))) { "Rename destination already exists" }
                    }
                }
            }
        }
    }
    override fun rename(row: MediaDeleteRow, newName: String): MediaDeleteRow {
        require(row.owner == context.packageName)
        requirePortableShareNames(listOf(newName))
        val condition = mediaDeleteCondition(row)
        check(resolver.update(row.artifact.uri.toUri(), ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) },
            condition.selection, condition.arguments.toTypedArray()) == 1) { "Conditional rename identity no longer matches" }
        // Return the actual provider name, including auto-suffixes, as a compensation witness.
        return observe(row).also { check(sameRenameScope(row, it)) { "Renamed row changed ownership or scope" } }
    }
    override fun readMetadata(row: MediaDeleteRow): ByteArray {
        requireMetadataRow(row)
        check(observe(row) == row) { "Metadata identity changed before reading" }
        val bytes = requireNotNull(resolver.openInputStream(row.artifact.uri.toUri())).use(::readBoundedMetadata)
        check(observe(row) == row) { "Metadata identity changed during reading" }
        return bytes
    }
    override fun writeMetadata(row: MediaDeleteRow, expected: ByteArray, replacement: ByteArray): MediaDeleteRow {
        requireMetadataRow(row)
        require(expected.size in 1..METADATA_BOUND && replacement.size in 1..METADATA_BOUND)
        check(row.artifact.sizeBytes == expected.size.toLong() && observe(row) == row) { "Metadata write identity changed" }
        requireNotNull(resolver.openFileDescriptor(row.artifact.uri.toUri(), "rw")).use { descriptor ->
            check(descriptor.statSize == expected.size.toLong()) { "Metadata descriptor length changed" }
            val before = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                input.channel.position(0); readBoundedMetadata(input)
            }
            check(before.contentEquals(expected) && observe(row) == row) { "Metadata bytes or identity changed before writing" }
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { output ->
                output.channel.position(0)
                output.write(replacement)
                output.channel.truncate(replacement.size.toLong())
                output.flush()
                descriptor.fileDescriptor.sync()
            }
            // Verify the actual descriptor before trusting asynchronously refreshed MediaStore columns.
            check(descriptor.statSize == replacement.size.toLong()) {
                "Metadata descriptor write length differed: actual=${descriptor.statSize}, expected=${replacement.size}"
            }
            val written = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                input.channel.position(0); readBoundedMetadata(input)
            }
            check(written.contentEquals(replacement)) { "Metadata descriptor write readback differed: actualBytes=${written.size}, expectedBytes=${replacement.size}" }
        }
        // Closing a MediaProvider writer schedules its row refresh. Waiting is bounded and never
        // retries a write or accepts stale SIZE; foreign identity/bytes terminate immediately.
        return awaitMediaRenameMetadataRow(row, replacement.size.toLong(), observe = { observe(row) }, verifyBytes = {
            val bytes = requireNotNull(resolver.openInputStream(row.artifact.uri.toUri())).use(::readBoundedMetadata)
            check(bytes.contentEquals(replacement)) { "Metadata write readback differed: actualBytes=${bytes.size}, expectedBytes=${replacement.size}" }
        }, pause = { Thread.sleep(it) })
    }
    override fun beginSourceMutation(plan: MediaRenamePlan) { sourceLease?.begin() }
    override fun applyLinked(plan: MediaRenamePlan) { linkedProxy?.apply() }
    override fun restoreLinked(plan: MediaRenamePlan) { linkedProxy?.restore() }
    override fun verify(plan: MediaRenamePlan, restored: Boolean) {
        linkedProxy?.verify(restored)
        val names = plan.preview.files.associate { it.artifact.uri to if (restored) it.artifact.name else it.newName }
        val current = plan.rows.map { row -> observe(row).also {
            check(sameRenameScope(row, it) && it.artifact.name == names.getValue(row.artifact.uri)) { "Final rename identity mismatch" }
        } }
        val byUri = current.associateBy { it.artifact.uri }
        val documents = plan.metadata.map { change ->
            val row = byUri.getValue(change.artifact.uri)
            val expected = if (restored) change.before else change.after
            val bytes = readMetadata(row)
            check(bytes.contentEquals(expected) && row.artifact.sizeBytes == expected.size.toLong()) { "Final metadata bytes mismatch" }
            MetadataDocument(row.artifact, Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString())
        }
        val primary = byUri.getValue(plan.selected.primary.uri)
        val namespace = mediaNamespace(primary.relativePath)
        val take = if (namespace != null) {
            val rows = current.filter { it.artifact.uri in plan.selected.originals.map { original -> original.uri } }.map { row ->
                val identity = requireNotNull(mediaOriginalIdentity(row.artifact.uri))
                CatalogRow(row.artifact, identity.second, identity.first, namespace)
            }
            requireNotNull(catalogTake(namespace, rows, documents))
        } else plan.selected.copy(primary = primary.artifact, originals = listOf(primary.artifact))
        check(take.id == plan.selected.id && take.primary.uri == plan.selected.primary.uri && take.slate == plan.selected.slate && take.relationStatus == plan.selected.relationStatus) {
            "Renamed take relationships did not remain coherent"
        }
        val strict = repository.deletionSnapshot(take)
        check(strict.ordered.toSet() == current.toSet()) { "Final rename membership changed" }
        val snapshot = repository.freshSnapshot(take)
        check(snapshot.documents == documents) { "Final rename metadata changed during verification" }
        sourceLease?.update(take)
    }
    private fun requireMetadataRow(row: MediaDeleteRow) {
        require(mediaDeleteIdentity(row.artifact.uri)?.collection == MediaDeleteCollection.METADATA && row.owner == context.packageName &&
            row.pending == 0 && row.artifact.mimeType == "application/json" && row.artifact.sizeBytes in 1..METADATA_BOUND.toLong())
    }
    private fun readBoundedMetadata(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= METADATA_BOUND) {
            val count = input.read(buffer, 0, minOf(buffer.size, METADATA_BOUND + 1 - output.size()))
            if (count == -1) break
            check(count > 0)
            output.write(buffer, 0, count)
        }
        check(output.size() in 1..METADATA_BOUND) { "Rename metadata exceeds byte bound" }
        return output.toByteArray()
    }
    private fun filenameKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private companion object { const val METADATA_BOUND = 512 * 1024 }
}

/** A provider refresh deadline, not a delay-based success criterion. Only a stable, exact row
 * together with exact bytes succeeds; transient old size/date may converge after writer close. */
internal fun awaitMediaRenameMetadataRow(
    before: MediaDeleteRow,
    expectedSize: Long,
    observe: () -> MediaDeleteRow,
    verifyBytes: () -> Unit,
    pause: (Long) -> Unit,
    nanoTime: () -> Long = System::nanoTime,
): MediaDeleteRow {
    val started = nanoTime()
    fun validate(row: MediaDeleteRow) {
        check(sameRenameScope(before, row) && row.artifact.name == before.artifact.name &&
            row.artifact.sizeBytes in setOf(before.artifact.sizeBytes, expectedSize)) {
            "Metadata write identity changed: actual=$row, expectedName=${before.artifact.name}, expectedSize=$expectedSize"
        }
    }
    var last = observe().also(::validate)
    repeat(80) { attempt ->
        check(nanoTime() - started < 2_000_000_000L) {
            "Metadata write identity could not be verified before refresh deadline: actual=$last, expectedSize=$expectedSize, observations=${attempt + 1}"
        }
        verifyBytes()
        val current = observe().also(::validate)
        if (current == last && current.artifact.sizeBytes == expectedSize) return current
        last = current
        if (attempt == 79 || nanoTime() - started >= 2_000_000_000L) {
            error("Metadata write identity could not be verified before refresh deadline: actual=$last, expectedSize=$expectedSize, observations=${attempt + 2}")
        }
        val remainingNanos = 2_000_000_000L - (nanoTime() - started)
        if (remainingNanos > 0) pause(minOf(25L, (remainingNanos + 999_999L) / 1_000_000L))
    }
    error("Unreachable metadata refresh state")
}
