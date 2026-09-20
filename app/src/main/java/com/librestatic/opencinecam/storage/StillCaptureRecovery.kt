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

/** Restart recovery deliberately discards uncommitted still groups, including visible members.
 * A committed group is never compensated. This is not cross-provider or power-loss atomicity. */
data class StillRecoveryReport(val discardedGroups: Int = 0, val unresolvedGroups: Int = 0)

/** Private, pre-insert ownership journal. Each group reserves its own UUID subdirectory in both
 * collections, so a provider-renamed item is still attributable in the insert/receipt crash gap.
 * No prefix scans, timestamp heuristics, transfer receipts, or inference from IS_PENDING=0.
 * The process-wide monitor spans the whole writer, not just journal operations: another service
 * can start while the old service's storage executor is draining.
 */
internal class StillCaptureRecovery(context: Context,
    private val checkpoint: (String, String) -> Unit = { _, _ -> },
) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val databaseFile = File(this.context.noBackupFilesDir, "still-recovery.sqlite")

    fun recover(): StillRecoveryReport = synchronized(processLock) { open().use(::recoverLocked) }

    fun <T> publish(block: (String, String, String) -> T): T = synchronized(processLock) {
        open().use { db ->
            recoverLocked(db)
            check(count(db) < MAX_GROUPS) { "Interrupted still journal is full" }
            val id = UUID.randomUUID().toString()
            // Reserve an empty namespace before allocating even the first provider row.
            for (metadata in listOf(false, true)) check(rows(id, metadata, ownedOnly = false).isEmpty()) {
                "Still capture namespace already exists"
            }
            db.execSQL("INSERT INTO captures(id, committed) VALUES(?, 0)", arrayOf(id))
            val result: T
            try {
                checkpoint("RESERVED", id)
                result = block(id, relativePath(id, false), relativePath(id, true))
                checkpoint("BEFORE_COMMIT", id)
            } catch (failure: Throwable) {
                try { discard(db, id) } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
            try {
                check(db.update("captures", ContentValues().apply { put("committed", 1) }, "id = ? AND committed = 0", arrayOf(id)) == 1)
            } catch (failure: Throwable) {
                // A commit can report an ambiguous failure. Never delete if its durable state
                // says COMMITTED, and never guess if reading that state also fails.
                val committed = try { committed(db, id) } catch (readFailure: Throwable) {
                    failure.addSuppressed(readFailure); throw failure
                }
                if (!committed) {
                    try { discard(db, id) } catch (cleanup: Throwable) {
                        if (cleanup !== failure) failure.addSuppressed(cleanup)
                    }
                    throw failure
                }
            }
            checkpoint("COMMITTED", id)
            // Cleanup is outside the compensation region. A retained COMMITTED receipt is inert.
            runCatching { db.delete("captures", "id = ? AND committed = 1", arrayOf(id)) }
            result
        }
    }

    private fun open(): SQLiteDatabase {
        check(context.noBackupFilesDir.isDirectory) { "Private capture storage unavailable" }
        val db = SQLiteDatabase.openDatabase(databaseFile.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            // Never let SQLite's default corruption handler delete ownership evidence.
            { throw IllegalStateException("Interrupted still journal is corrupt") })
        try {
            db.execSQL("PRAGMA synchronous=FULL")
            check(db.version in 0..1) { "Unsupported interrupted still journal version" }
            if (db.version == 0) {
                db.transaction {
                    execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY NOT NULL, committed INTEGER NOT NULL CHECK(committed IN (0,1)))")
                    version = 1
                }
            }
            check(count(db) <= MAX_GROUPS) { "Interrupted still journal exceeds capacity" }
            return db
        } catch (failure: Throwable) { db.close(); throw failure }
    }

    private fun count(db: SQLiteDatabase): Int = db.rawQuery("SELECT COUNT(*) FROM captures", null).use {
        check(it.moveToFirst()); it.getLong(0).also { count -> check(count in 0..MAX_GROUPS.toLong()) }.toInt()
    }

    private fun committed(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery("SELECT committed FROM captures WHERE id = ?", arrayOf(id)).use {
            check(it.count == 1 && it.moveToFirst()) { "Still ownership receipt disappeared" }
            check(it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) in 0L..1L)
            it.getLong(0) == 1L
        }

    private fun recoverLocked(db: SQLiteDatabase): StillRecoveryReport {
        val receipts = db.rawQuery("SELECT id, committed FROM captures ORDER BY id LIMIT ?", arrayOf((MAX_GROUPS + 1).toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getType(1), cursor.getLong(1))) }
        }
        check(receipts.size <= MAX_GROUPS)
        var discarded = 0
        var unresolved = 0
        for ((id, type, state) in receipts) {
            try {
                requireId(id)
                check(type == Cursor.FIELD_TYPE_INTEGER && state in 0L..1L)
                if (state == 1L) db.delete("captures", "id = ? AND committed = 1", arrayOf(id))
                else { discard(db, id); discarded++ }
            } catch (failure: Exception) {
                unresolved++
                android.util.Log.e("StillRecovery", "Retained unresolved capture receipt $id", failure)
            }
        }
        return StillRecoveryReport(discarded, unresolved)
    }

    private fun discard(db: SQLiteDatabase, id: String) {
        requireId(id)
        check(!committed(db, id)) { "Committed captures must never be discarded" }
        var failure: Throwable? = null
        for (metadata in listOf(false, true)) {
            try {
                // The nonce directory is exclusively allocated by this writer. Foreign owners
                // are never selected, even if they place a similarly named item there.
                val candidates = rows(id, metadata, ownedOnly = true)
                for (row in candidates) {
                    try {
                        // Repeat ownership/path selection in the mutation to avoid stale queries.
                        val deleted = resolver.delete(row, ownershipSelection, ownershipArgs(id, metadata))
                        check(deleted in 0..1)
                        check(row !in rows(id, metadata, ownedOnly = true)) { "Interrupted capture row remains" }
                    } catch (problem: Throwable) {
                        if (failure == null) failure = problem else if (failure !== problem) failure!!.addSuppressed(problem)
                    }
                }
                check(rows(id, metadata, ownedOnly = true).isEmpty()) { "Interrupted capture group remains" }
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else if (failure !== problem) failure!!.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
        check(db.delete("captures", "id = ? AND committed = 0", arrayOf(id)) == 1)
    }

    private val ownershipSelection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
    private fun ownershipArgs(id: String, metadata: Boolean) = arrayOf(relativePath(id, metadata), context.packageName)

    private fun rows(id: String, metadata: Boolean, ownedOnly: Boolean): List<Uri> {
        requireId(id)
        check(MediaStore.VOLUME_EXTERNAL_PRIMARY in MediaStore.getExternalVolumeNames(context)) { "Capture volume unavailable" }
        val collection = collection(metadata)
        @Suppress("DEPRECATION")
        val includingPending = MediaStore.setIncludePending(collection)
        val selection = if (ownedOnly) ownershipSelection else "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = if (ownedOnly) ownershipArgs(id, metadata) else arrayOf(relativePath(id, metadata))
        return requireNotNull(resolver.query(includingPending, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)) {
            "Interrupted capture ownership query unavailable"
        }.use { cursor ->
            check(cursor.count <= MAX_ROWS) { "Interrupted capture namespace exceeds row bound" }
            buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }
    }

    companion object {
        private val processLock = Any()
        private const val MAX_GROUPS = 128
        private const val MAX_ROWS = 11
        private fun requireId(id: String) { require(UUID.fromString(id).toString() == id) }
        fun relativePath(id: String, metadata: Boolean): String {
            requireId(id)
            return "${if (metadata) "Download" else "DCIM"}/OpenCineCam/OCC_$id/"
        }
        fun collection(metadata: Boolean): Uri = if (metadata)
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
