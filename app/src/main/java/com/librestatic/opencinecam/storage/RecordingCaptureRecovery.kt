/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.MediaStore
import androidx.core.database.sqlite.transaction
import com.librestatic.opencinecam.media.storage.ContentResolverClipRows
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val recordingRecoveryLock = Any()
// IDs only: the registry must not retain a service/context. A process restart starts empty.
private val liveRecordingGroups = mutableSetOf<String>()
internal enum class RecordingMemberKind { VIDEO, AUDIO }
/** [incompleteGroups] interrupted takes were kept: their outputs with bytes were published as incomplete (ADR-0011). */
data class RecordingRecoveryReport(val discardedGroups: Int = 0, val unresolvedGroups: Int = 0, val incompleteGroups: Int = 0)

/** A take spans asynchronous native writers; unlike a still it cannot hold a thread monitor
 * throughout capture. This registry protects the exact take until its real writers retire.
 * Recovery handles only durable uncommitted namespaces, never infers a commit from visible rows:
 * outputs with bytes are published as incomplete, empty ones are deleted (ADR-0011). */
internal class RecordingCaptureRecovery(context: Context) {
    internal val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val database = File(this.context.noBackupFilesDir, "recording-recovery.sqlite")
    internal fun key(id: String) = "${database.absolutePath}:$id"

    private fun open(): SQLiteDatabase {
        val db = SQLiteDatabase.openDatabase(database.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            { throw IllegalStateException("Recording recovery journal is corrupt") })
        try {
            db.execSQL("PRAGMA synchronous=FULL")
            check(db.version in 0..1) { "Unsupported recording recovery journal version" }
            if (db.version == 0) db.transaction {
                execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY NOT NULL, committed INTEGER NOT NULL CHECK(committed IN (0,1)))")
                version = 1
            }
            db.rawQuery("SELECT COUNT(*) FROM captures", null).use {
                check(it.moveToFirst() && it.getLong(0) in 0L..128L) { "Recording recovery capacity exceeded" }
            }
            return db
        } catch (failure: Throwable) { db.close(); throw failure }
    }

    internal fun start(): RecordingRecoveryGroup = synchronized(recordingRecoveryLock) {
        recover()
        val id = UUID.randomUUID().toString()
        collections.forEach { check(rows(id, it, false).isEmpty()) { "Recording namespace already exists" } }
        open().use { db ->
            db.rawQuery("SELECT COUNT(*) FROM captures", null).use { check(it.moveToFirst() && it.getLong(0) < 128) }
            db.execSQL("INSERT INTO captures(id, committed) VALUES(?, 0)", arrayOf(id))
        }
        liveRecordingGroups.add(key(id))
        RecordingRecoveryGroup(this, id)
    }

    fun recover(): RecordingRecoveryReport = synchronized(recordingRecoveryLock) {
        open().use { db ->
            val records = db.rawQuery("SELECT id, committed FROM captures ORDER BY id LIMIT 129", null).use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getType(1), c.getLong(1))) }
            }
            check(records.size <= 128)
            var discarded = 0; var unresolved = 0; var incomplete = 0
            records.forEach { (id, type, state) ->
                try {
                    requireId(id); check(type == Cursor.FIELD_TYPE_INTEGER && state in 0L..1L)
                    if (key(id) !in liveRecordingGroups) {
                        if (state == 1L) db.delete("captures", "id = ? AND committed = 1", arrayOf(id))
                        else if (publishIncomplete(db, id) > 0) incomplete++
                        else { discardRows(db, id); discarded++ }
                    }
                } catch (failure: Exception) {
                    unresolved++
                    android.util.Log.e("RecordingRecovery", "Retained unresolved take $id", failure)
                }
            }
            if (discarded + unresolved + incomplete > 0) {
                android.util.Log.i("RecordingRecovery", "Recovered interrupted takes: publishedIncomplete=$incomplete discarded=$discarded unresolved=$unresolved")
            }
            RecordingRecoveryReport(discarded, unresolved, incomplete)
        }
    }

    /**
     * Publishes the outputs with bytes of an interrupted take as incomplete and deletes the empty ones,
     * then marks the journal entry committed so no later recovery deletes what was kept. Returns the
     * number of rows published; when 0 the entry is untouched and the caller discards it.
     */
    private fun publishIncomplete(db: SQLiteDatabase, id: String): Int {
        requireId(id)
        check(state(db, id) == 0L) { "Only an uncommitted take can be recovered" }
        val found = collections.flatMap { root ->
            rows(id, root, true).map { uri ->
                val (name, mime) = requireNotNull(resolver.query(uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE), null, null, null)).use {
                    check(it.moveToFirst()) { "Interrupted take row disappeared" }
                    requireNotNull(it.getString(0)) to requireNotNull(it.getString(1))
                }
                RecoveredRow(root, uri, name, mime, resolver.ownedRowBytes(uri), uri.toString())
            }
        }
        val published = recoverIncompleteTake(id, found, ContentResolverClipRows(resolver),
            insertSidecar = { name -> insert(id, "Download", name, "application/json") },
            readText = { uri -> runCatching { resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() })
        if (published == 0) return 0
        check(db.update("captures", ContentValues().apply { put("committed", 1) }, "id = ? AND committed = 0", arrayOf(id)) == 1)
        runCatching { db.delete("captures", "id = ? AND committed = 1", arrayOf(id)) }
        return published
    }

    internal fun commit(id: String) = open().use { db ->
        try {
            check(db.update("captures", ContentValues().apply { put("committed", 1) }, "id = ? AND committed = 0", arrayOf(id)) == 1)
        } catch (failure: Throwable) {
            val state = try { state(db, id) } catch (readFailure: Throwable) { failure.addSuppressed(readFailure); throw failure }
            if (state != 1L) throw failure
        }
        // Never make failure to prune COMMITTED revoke a valid capture.
        runCatching { db.delete("captures", "id = ? AND committed = 1", arrayOf(id)) }
    }

    internal fun discard(id: String) = open().use { discardRows(it, id) }
    private fun state(db: SQLiteDatabase, id: String): Long? = db.rawQuery("SELECT committed FROM captures WHERE id = ?", arrayOf(id)).use {
        if (!it.moveToFirst()) null else {
            check(it.count == 1 && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) in 0L..1L)
            it.getLong(0)
        }
    }

    private fun discardRows(db: SQLiteDatabase, id: String) {
        requireId(id)
        val state = state(db, id)
        check(state != 1L) { "Committed take must never be compensated" }
        if (state == null) {
            check(collections.all { rows(id, it, true).isEmpty() }) { "Take receipt disappeared while outputs remain" }
            return
        }
        var failure: Throwable? = null
        for (root in collections) {
            try {
                for (uri in rows(id, root, true)) {
                    try {
                        val count = resolver.delete(uri, selection, arrayOf(path(id, root), context.packageName))
                        check(count in 0..1 && uri !in rows(id, root, true)) { "Interrupted take row remains" }
                    } catch (problem: Throwable) {
                        if (failure == null) failure = problem else if (failure !== problem) failure!!.addSuppressed(problem)
                    }
                }
                check(rows(id, root, true).isEmpty())
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else if (failure !== problem) failure!!.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
        check(db.delete("captures", "id = ? AND committed = 0", arrayOf(id)) == 1)
    }

    internal fun insert(id: String, root: String, name: String, mime: String): Uri =
        requireNotNull(resolver.insert(collection(root), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path(id, root))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })) { "Recording output allocation failed" }

    /** MediaProvider canonicalizes audio/wav to audio/x-wav at insertion on API30.
     * Freeze that actual identity once; later preparation/publication still require exact equality. */
    internal fun allocatedMime(uri: Uri, requested: String): String = requireNotNull(resolver.query(
        uri, arrayOf(MediaStore.MediaColumns.MIME_TYPE), null, null, null,
    )).use {
        check(it.count == 1 && it.moveToFirst())
        requireNotNull(it.getString(0)).also { actual ->
            check(actual == requested || requested == "audio/wav" && actual == "audio/x-wav") {
                "Recording MIME changed during allocation: requested=$requested actual=$actual"
            }
        }
    }

    internal fun verify(id: String, row: RecordingRow, pending: Int) {
        requireNotNull(resolver.query(row.uri, arrayOf("_display_name", "mime_type", "relative_path", "owner_package_name", "is_pending"), null, null, null)).use {
            check(it.count == 1 && it.moveToFirst())
            check(it.getString(0) == row.name && it.getString(1) == row.mime && it.getString(2) == path(id, row.root) &&
                it.getString(3) == context.packageName && it.getInt(4) == pending) {
                "Recording output identity or publication changed: expected=${row.name}|${row.mime}|${path(id, row.root)}|${context.packageName}|$pending; " +
                    "actual=${it.getString(0)}|${it.getString(1)}|${it.getString(2)}|${it.getString(3)}|${it.getInt(4)}"
            }
        }
    }

    internal fun delete(uri: Uri) { check(resolver.delete(uri, null, null) in 0..1) }
    internal fun allRows(id: String): Set<Uri> = collections.flatMap { rows(id, it, true) }.toSet()
    private fun rows(id: String, root: String, owned: Boolean): List<Uri> {
        requireId(id)
        check(MediaStore.VOLUME_EXTERNAL_PRIMARY in MediaStore.getExternalVolumeNames(context)) { "Recording volume unavailable" }
        val collection = collection(root)
        @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
        return requireNotNull(resolver.query(all, arrayOf("_id"), if (owned) selection else "relative_path = ?",
            if (owned) arrayOf(path(id, root), context.packageName) else arrayOf(path(id, root)), null)).use {
            check(it.count <= 4) { "Recording namespace exceeds its row bound" }
            buildList { while (it.moveToNext()) add(ContentUris.withAppendedId(collection, it.getLong(0))) }
        }
    }

    companion object {
        private val collections = listOf("DCIM", "Music", "Download")
        private const val selection = "relative_path = ? AND owner_package_name = ?"
        internal fun path(id: String, root: String): String { requireId(id); require(root in collections); return "$root/OpenCineCam/OCC_TAKE_$id/" }
        private fun requireId(id: String) { require(id.length == 36 && UUID.fromString(id).toString() == id) }
        internal fun collection(root: String): Uri = when (root) {
            "DCIM" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "Music" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "Download" -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> error("Unknown recording collection")
        }
        fun begin(context: Context): RecordingRecoveryGroup = RecordingCaptureRecovery(context).start()
    }
}

internal data class RecordingRow(val uri: Uri, val name: String, val mime: String, val root: String, val metadata: Boolean,
    /** A take attachment such as the Gyroflow log: journalled and published with the take, never a prepared artifact. */
    val auxiliary: Boolean = false,
    /** The video member's second metadata row, the canonical clip sidecar; prepared as CLIP_METADATA. */
    val clip: Boolean = false)

/** Enrolment closes when preparation begins. All members must publish before a durable commit. */
class RecordingRecoveryGroup internal constructor(private val journal: RecordingCaptureRecovery, val id: String) {
    private val members = linkedMapOf<RecordingMemberKind, RecordingRecoveryMember>()
    private var preparationStarted = false
    private var aborted = false
    private var committed = false
    private var discarded = false
    private var nativeWriters = 0
    private var salvaging = false

    /**
     * ADR-0011 incomplete publication: from here a member's abort withdraws only that member (its rows
     * are deleted) instead of discarding the whole take, and a member that [keepBytes] never deletes.
     */
    internal fun beginSalvage() = synchronized(recordingRecoveryLock) {
        check(!committed && !discarded) { "Take has already terminated" }
        salvaging = true
    }

    /** The member holds bytes that must not be deleted by any later compensation; [published] still needs the real publication. */
    internal fun keepBytes(member: RecordingRecoveryMember) = synchronized(recordingRecoveryLock) { member.keepBytes = true }

    internal fun member(kind: RecordingMemberKind): RecordingRecoveryMember = synchronized(recordingRecoveryLock) {
        check(!preparationStarted && !aborted && !committed && kind !in members) { "Take enrolment is closed or duplicate" }
        RecordingRecoveryMember(this, kind).also { members[kind] = it }
    }

    /** Extra native ownership, independent of the caller's ParcelFileDescriptor wrapper. */
    internal fun retainNativeWriter(): AutoCloseable = synchronized(recordingRecoveryLock) {
        check(!preparationStarted && !aborted && !committed)
        nativeWriters++
        val retired = AtomicBoolean(false)
        AutoCloseable { synchronized(recordingRecoveryLock) {
            if (retired.compareAndSet(false, true)) { nativeWriters--; finishIfReady() }
        } }
    }

    internal fun insert(member: RecordingRecoveryMember, name: String, mime: String, metadata: Boolean, clip: Boolean = false): Uri = synchronized(recordingRecoveryLock) {
        check(!clip || metadata && member.kind == RecordingMemberKind.VIDEO)
        check(!aborted && !committed && !member.prepared && member.rows.count { !it.auxiliary } < (if (member.kind == RecordingMemberKind.VIDEO) 3 else 2))
        check(member.rows.none { !it.auxiliary && it.metadata == metadata && it.clip == clip })
        require(name.isNotBlank() && '/' !in name && name.length <= 180)
        require(if (metadata) mime == "application/json" else when (member.kind) {
            RecordingMemberKind.VIDEO -> mime == "video/mp4"
            RecordingMemberKind.AUDIO -> mime in setOf("audio/wav", "audio/flac")
        })
        val root = if (metadata) "Download" else if (member.kind == RecordingMemberKind.VIDEO) "DCIM" else "Music"
        journal.insert(id, root, name, mime).also { uri -> member.rows += RecordingRow(uri, name, journal.allocatedMime(uri, mime), root, metadata, clip = clip) }
    }

    /** Withdraws the clip sidecar row of a take whose sidecar could not be written; the take itself is untouched. */
    internal fun removeClip(member: RecordingRecoveryMember) = synchronized(recordingRecoveryLock) {
        check(!aborted && !committed && !member.prepared)
        member.rows.firstOrNull { it.clip }?.let { row ->
            journal.delete(row.uri)
            member.rows.remove(row)
        }
        Unit
    }

    /** The one extra Download row a video take may own. A provider that mangles the identity is undone. */
    internal fun insertAuxiliary(member: RecordingRecoveryMember, name: String, mime: String): Uri = synchronized(recordingRecoveryLock) {
        check(!aborted && !committed && !member.prepared && member.kind == RecordingMemberKind.VIDEO)
        check(member.rows.none { it.auxiliary })
        require(name.isNotBlank() && '/' !in name && name.length <= 180 && mime == GyroflowLog.MIME_TYPE)
        val uri = journal.insert(id, "Download", name, mime)
        try {
            member.rows += RecordingRow(uri, name, journal.allocatedMime(uri, mime), "Download", metadata = false, auxiliary = true)
        } catch (failure: Throwable) {
            try { journal.delete(uri) } catch (problem: Throwable) { failure.addSuppressed(problem) }
            throw failure
        }
        uri
    }

    internal fun removeAuxiliary(member: RecordingRecoveryMember) = synchronized(recordingRecoveryLock) {
        check(!aborted && !committed && !member.prepared)
        member.rows.firstOrNull { it.auxiliary }?.let { row ->
            journal.delete(row.uri)
            member.rows.remove(row)
        }
        Unit
    }

    internal fun prepared(member: RecordingRecoveryMember, artifacts: List<PreparedCaptureArtifact>) = synchronized(recordingRecoveryLock) {
        // The caller certifies its native writer and both stream wrappers actually closed.
        member.retired = true
        check(!aborted && !committed) { "Take has already terminated" }
        preparationStarted = true
        check(artifacts.size == member.rows.count { !it.auxiliary } && artifacts.map { it.uri }.toSet().size == artifacts.size)
        val required = if (member.kind == RecordingMemberKind.VIDEO) setOf(CaptureArtifactRole.VIDEO) else
            setOf(CaptureArtifactRole.AUDIO, CaptureArtifactRole.AUDIO_METADATA)
        val allowed = if (member.kind == RecordingMemberKind.VIDEO) required + CaptureArtifactRole.VIDEO_METADATA + CaptureArtifactRole.CLIP_METADATA else required
        check(artifacts.map { it.role }.toSet().containsAll(required) && artifacts.all { it.role in allowed })
        artifacts.forEach { artifact ->
            val row = member.rows.single { !it.auxiliary && it.uri.toString() == artifact.uri }
            check(row.name == artifact.displayName)
            check(row.metadata == (artifact.role in setOf(CaptureArtifactRole.AUDIO_METADATA, CaptureArtifactRole.VIDEO_METADATA, CaptureArtifactRole.CLIP_METADATA)))
            check(row.clip == (artifact.role == CaptureArtifactRole.CLIP_METADATA))
            journal.verify(id, row, 1)
        }
        member.rows.filter { it.auxiliary }.forEach { journal.verify(id, it, 1) }
        member.prepared = true
    }

    internal fun published(member: RecordingRecoveryMember) = synchronized(recordingRecoveryLock) {
        if (committed) return@synchronized
        check(!aborted && member.prepared && member.retired && nativeWriters == 0) { "Native recording output remains owned" }
        member.rows.forEach { journal.verify(id, it, 0) }
        member.published = true
        finishIfReady()
    }

    internal fun abort(member: RecordingRecoveryMember) = synchronized(recordingRecoveryLock) {
        member.retired = true
        if (committed || discarded) return@synchronized
        if (member.keepBytes) {
            android.util.Log.w("RecordingRecovery", "Take $id keeps ${member.kind} bytes for incomplete publication or recovery")
            return@synchronized
        }
        if (salvaging || members.values.any { it.keepBytes }) {
            // Only this member is empty or failed: remove its rows, the rest of the take survives.
            var failure: Throwable? = null
            for (row in member.rows.toList()) {
                try { journal.delete(row.uri); member.rows.remove(row) } catch (problem: Throwable) {
                    if (failure == null) failure = problem else if (failure !== problem) failure.addSuppressed(problem)
                }
            }
            failure?.let { throw it }
            member.withdrawn = true
            finishIfReady()
            return@synchronized
        }
        aborted = true
        finishIfReady()
    }

    private fun finishIfReady() {
        if (committed || discarded || nativeWriters != 0 || members.values.any { !it.retired }) return
        if (aborted) {
            // No native owner remains; failed cleanup is eligible for the next recovery retry.
            liveRecordingGroups.remove(journal.key(id))
            journal.discard(id)
            discarded = true
        } else if (members.isNotEmpty() && members.values.all { it.withdrawn }) {
            // Every output was empty and has been removed: nothing is left to publish.
            liveRecordingGroups.remove(journal.key(id))
            journal.discard(id)
            discarded = true
        } else if (members.isNotEmpty() && members.values.filter { !it.withdrawn }.all { it.prepared && it.published }) {
            val rows = members.values.filter { !it.withdrawn }.flatMap { it.rows }
            check(journal.allRows(id) == rows.map { it.uri }.toSet()) { "Take membership changed before commit" }
            rows.forEach { journal.verify(id, it, 0) }
            journal.commit(id)
            committed = true
            liveRecordingGroups.remove(journal.key(id))
        }
    }
}

internal class RecordingRecoveryMember(internal val group: RecordingRecoveryGroup, internal val kind: RecordingMemberKind) {
    internal val rows = mutableListOf<RecordingRow>()
    internal var retired = false
    internal var prepared = false
    internal var published = false
    internal var withdrawn = false
    internal var keepBytes = false
    fun beginSalvage() = group.beginSalvage()
    fun keepBytes() = group.keepBytes(this)
    fun insert(displayName: String, mimeType: String, metadata: Boolean): Uri = group.insert(this, displayName, mimeType, metadata)
    fun insertClipSidecar(displayName: String): Uri = group.insert(this, displayName, "application/json", metadata = true, clip = true)
    fun removeClipSidecar() = group.removeClip(this)
    fun insertAuxiliary(displayName: String, mimeType: String): Uri = group.insertAuxiliary(this, displayName, mimeType)
    fun removeAuxiliary() = group.removeAuxiliary(this)
    fun prepared(artifacts: List<PreparedCaptureArtifact>) = group.prepared(this, artifacts)
    fun published() = group.published(this)
    fun abort() = group.abort(this)
}
