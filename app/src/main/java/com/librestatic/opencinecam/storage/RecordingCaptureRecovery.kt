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
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val recordingRecoveryLock = Any()
// IDs only: the registry must not retain a service/context. A process restart starts empty.
private val liveRecordingGroups = mutableSetOf<String>()
internal enum class RecordingMemberKind { VIDEO, AUDIO }
data class RecordingRecoveryReport(val discardedGroups: Int = 0, val unresolvedGroups: Int = 0)

/** A take spans asynchronous native writers; unlike a still it cannot hold a thread monitor
 * throughout capture. This registry protects the exact take until its real writers retire.
 * Recovery discards only durable uncommitted namespaces, never infers a commit from visible rows. */
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
            var discarded = 0; var unresolved = 0
            records.forEach { (id, type, state) ->
                try {
                    requireId(id); check(type == Cursor.FIELD_TYPE_INTEGER && state in 0L..1L)
                    if (key(id) !in liveRecordingGroups) {
                        if (state == 1L) db.delete("captures", "id = ? AND committed = 1", arrayOf(id))
                        else { discardRows(db, id); discarded++ }
                    }
                } catch (failure: Exception) {
                    unresolved++
                    android.util.Log.e("RecordingRecovery", "Retained unresolved take $id", failure)
                }
            }
            RecordingRecoveryReport(discarded, unresolved)
        }
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

internal data class RecordingRow(val uri: Uri, val name: String, val mime: String, val root: String, val metadata: Boolean)

/** Enrolment closes when preparation begins. All members must publish before a durable commit. */
class RecordingRecoveryGroup internal constructor(private val journal: RecordingCaptureRecovery, val id: String) {
    private val members = linkedMapOf<RecordingMemberKind, RecordingRecoveryMember>()
    private var preparationStarted = false
    private var aborted = false
    private var committed = false
    private var discarded = false
    private var nativeWriters = 0

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

    internal fun insert(member: RecordingRecoveryMember, name: String, mime: String, metadata: Boolean): Uri = synchronized(recordingRecoveryLock) {
        check(!aborted && !committed && !member.prepared && member.rows.size < 2)
        check(member.rows.none { it.metadata == metadata })
        require(name.isNotBlank() && '/' !in name && name.length <= 180)
        require(if (metadata) mime == "application/json" else when (member.kind) {
            RecordingMemberKind.VIDEO -> mime == "video/mp4"
            RecordingMemberKind.AUDIO -> mime in setOf("audio/wav", "audio/flac")
        })
        val root = if (metadata) "Download" else if (member.kind == RecordingMemberKind.VIDEO) "DCIM" else "Music"
        journal.insert(id, root, name, mime).also { uri -> member.rows += RecordingRow(uri, name, journal.allocatedMime(uri, mime), root, metadata) }
    }

    internal fun prepared(member: RecordingRecoveryMember, artifacts: List<PreparedCaptureArtifact>) = synchronized(recordingRecoveryLock) {
        // The caller certifies its native writer and both stream wrappers actually closed.
        member.retired = true
        check(!aborted && !committed) { "Take has already terminated" }
        preparationStarted = true
        check(artifacts.size == member.rows.size && artifacts.map { it.uri }.toSet().size == artifacts.size)
        val required = if (member.kind == RecordingMemberKind.VIDEO) setOf(CaptureArtifactRole.VIDEO) else
            setOf(CaptureArtifactRole.AUDIO, CaptureArtifactRole.AUDIO_METADATA)
        val allowed = if (member.kind == RecordingMemberKind.VIDEO) required + CaptureArtifactRole.VIDEO_METADATA else required
        check(artifacts.map { it.role }.toSet().containsAll(required) && artifacts.all { it.role in allowed })
        artifacts.forEach { artifact ->
            val row = member.rows.single { it.uri.toString() == artifact.uri }
            check(row.name == artifact.displayName)
            check(row.metadata == (artifact.role in setOf(CaptureArtifactRole.AUDIO_METADATA, CaptureArtifactRole.VIDEO_METADATA)))
            journal.verify(id, row, 1)
        }
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
        } else if (members.isNotEmpty() && members.values.all { it.prepared && it.published }) {
            val rows = members.values.flatMap { it.rows }
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
    fun insert(displayName: String, mimeType: String, metadata: Boolean): Uri = group.insert(this, displayName, mimeType, metadata)
    fun prepared(artifacts: List<PreparedCaptureArtifact>) = group.prepared(this, artifacts)
    fun published() = group.published(this)
    fun abort() = group.abort(this)
}
