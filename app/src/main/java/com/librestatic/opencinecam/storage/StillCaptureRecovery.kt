/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.database.sqlite.transaction
import androidx.core.net.toUri
import java.io.File
import java.util.UUID

/** Restart recovery deliberately discards uncommitted still groups, including visible members.
 * A committed group is never compensated. This is not cross-provider or power-loss atomicity.
 * [deferredGroups] uncommitted groups on a drive (PLAN-072) were kept because the drive is not mounted or its
 * folder permission is gone; a later pass with the drive back resolves them. */
data class StillRecoveryReport(val discardedGroups: Int = 0, val unresolvedGroups: Int = 0, val deferredGroups: Int = 0)

/** Private, pre-insert ownership journal. Each group reserves its own UUID subdirectory in both
 * collections, so a provider-renamed item is still attributable in the insert/receipt crash gap.
 * No prefix scans, timestamp heuristics, transfer receipts, or inference from IS_PENDING=0.
 * The process-wide monitor spans the whole writer, not just journal operations: another service
 * can start while the old service's storage executor is draining.
 *
 * PLAN-072: a group is on MediaStore (NULL `tree`) or in its own folder on a drive (`tree`, `volume`, and `folder`
 * once it exists, journalled before the first file in it). [SafStillRows] keeps the drive's IS_PENDING as the
 * folder's in-progress marker; deleting it is the drive group's durable commit point. [mounted] says whether a
 * drive volume (UUID) is mounted; tests replace it.
 *
 * Phone groups and drive groups are bounded apart ([MAX_GROUPS], [MAX_DRIVE_GROUPS]), so groups deferred on drives that
 * never return cannot refuse a phone still. A drive group that finds its bound full forgets the oldest deferred drive
 * entry (the journal row only; what is on that drive stays).
 */
internal class StillCaptureRecovery(context: Context,
    private val checkpoint: (String, String) -> Unit = { _, _ -> },
    mounted: ((String) -> Boolean)? = null,
) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val databaseFile = File(this.context.noBackupFilesDir, DATABASE)
    private val mounted: (String) -> Boolean = mounted ?: { volume -> RecordingCaptureRecovery.volumeMounted(this.context, volume) }

    fun recover(): StillRecoveryReport = synchronized(processLock) { open().use(::recoverLocked) }

    /** A MediaStore group: [block] gets the id and the images and metadata relative paths of its namespace. */
    fun <T> publish(block: (String, String, String) -> T): T = publish(RecordingDestination.Phone) { id, _ ->
        block(id, relativePath(id, false), relativePath(id, true))
    }

    /**
     * A group on [destination]. [block] gets the id and, on a drive, the store that writes the group's folder
     * (null on the phone, where the caller builds its MediaStore store from [relativePath]).
     */
    internal fun <T> publish(destination: RecordingDestination, block: (String, SafStillRows?) -> T): T = synchronized(processLock) {
        open().use { db ->
            recoverLocked(db)
            val id = UUID.randomUUID().toString()
            val drive = when (destination) {
                RecordingDestination.Phone -> {
                    check(count(db, phone = true) < MAX_GROUPS) { "Interrupted still journal is full" }
                    // Reserve an empty namespace before allocating even the first provider row.
                    for (metadata in listOf(false, true)) check(rows(id, metadata, ownedOnly = false).isEmpty()) {
                        "Still capture namespace already exists"
                    }
                    db.execSQL("INSERT INTO captures(id, committed) VALUES(?, 0)", arrayOf(id))
                    null
                }
                // The group folder is created at the first file, so it is new: no namespace to check.
                is RecordingDestination.UsbFolder -> {
                    require(destination.treeUri.authority == EXTERNAL_STORAGE_AUTHORITY) { "Not a drive folder" }
                    if (count(db, phone = false) >= MAX_DRIVE_GROUPS) forgetOldestDeferred(db)
                    db.execSQL("INSERT INTO captures(id, committed, tree, volume) VALUES(?, 0, ?, ?)",
                        arrayOf(id, destination.treeUri.toString(), destination.volumeId.uppercase()))
                    SafStillRows(context, destination.treeUri, null) { folder ->
                        check(db.update("captures", ContentValues().apply { put("folder", folder.toString()) },
                            "id = ? AND committed = 0 AND tree IS NOT NULL AND folder IS NULL", arrayOf(id)) == 1) {
                            "Still group folder was not journalled"
                        }
                    }
                }
            }
            val result: T
            try {
                checkpoint("RESERVED", id)
                result = block(id, drive)
                checkpoint("BEFORE_COMMIT", id)
                // The drive's durable commit point: the folder no longer reads as in progress.
                drive?.commit()
            } catch (failure: Throwable) {
                // An unconfirmed commit may have removed the marker: the files stay and recovery reads the drive.
                if (failure !is StillCommitUnconfirmed) try { discard(db, id) } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
            try {
                check(db.update("captures", ContentValues().apply { put("committed", 1) }, "id = ? AND committed = 0", arrayOf(id)) == 1)
            } catch (failure: Throwable) {
                if (drive != null) {
                    // Its marker is gone, so the drive group is committed: recovery prunes the entry, never the files.
                    android.util.Log.w("StillRecovery", "Committed drive group $id kept its journal entry", failure)
                    return result
                }
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
            check(db.version in 0..2) { "Unsupported interrupted still journal version" }
            if (db.version == 0) {
                db.transaction {
                    execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY NOT NULL, committed INTEGER NOT NULL CHECK(committed IN (0,1)), " +
                        "tree TEXT, volume TEXT, folder TEXT)")
                    version = 2
                }
            } else if (db.version == 1) {
                db.transaction {
                    // Version 1 rows are MediaStore groups: the new columns stay NULL. A column already present is kept.
                    val columns = rawQuery("PRAGMA table_info(captures)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
                    listOf("tree", "volume", "folder").filter { it !in columns }.forEach { execSQL("ALTER TABLE captures ADD COLUMN $it TEXT") }
                    version = 2
                }
            }
            check(count(db, phone = true) <= MAX_GROUPS && count(db, phone = false) <= MAX_DRIVE_GROUPS) {
                "Interrupted still journal exceeds capacity"
            }
            return db
        } catch (failure: Throwable) { db.close(); throw failure }
    }

    /** The entries of phone groups (NULL `tree`) or of drive groups, committed or not. */
    private fun count(db: SQLiteDatabase, phone: Boolean): Long =
        db.rawQuery("SELECT COUNT(*) FROM captures WHERE tree IS ${if (phone) "NULL" else "NOT NULL"}", null).use {
            check(it.moveToFirst()); it.getLong(0)
        }

    /**
     * Makes room for a drive group: deletes the entry of the oldest uncommitted drive group that [recoverLocked] would
     * defer (its drive absent or its grant gone). Only the entry goes; the group's folder stays on its drive, marker
     * included. Fails when every drive entry could still be resolved. Under [processLock], which spans every writer, so
     * no entry here belongs to a group being written.
     */
    private fun forgetOldestDeferred(db: SQLiteDatabase) {
        val held = heldReadWriteTrees(resolver)
        val oldest = db.rawQuery("SELECT id, tree, volume, folder FROM captures WHERE tree IS NOT NULL AND committed = 0 ORDER BY rowid", null).use { c ->
            generateSequence { if (c.moveToNext()) listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) else null }.firstOrNull { (_, tree, volume, folder) ->
                volume == null || resolveDriveLocation(Uri.parse(tree), volume, folder?.let(Uri::parse), held, mounted) == null
            }
        }
        checkNotNull(oldest) { "Interrupted still journal is full" }
        check(db.delete("captures", "id = ? AND committed = 0", arrayOf(oldest[0])) == 1)
        android.util.Log.w("StillRecovery", "Recovery journal full: forgot interrupted still group ${oldest[0]} on drive ${oldest[2]}, " +
            "deferred since its drive went; its files stay on the drive")
    }

    private fun committed(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery("SELECT committed FROM captures WHERE id = ?", arrayOf(id)).use {
            check(it.count == 1 && it.moveToFirst()) { "Still ownership receipt disappeared" }
            check(it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) in 0L..1L)
            it.getLong(0) == 1L
        }

    private data class Receipt(val id: String, val type: Int, val state: Long, val tree: String?, val volume: String?, val folder: String?)
    private enum class DriveRecovery { DEFERRED, DISCARDED, COMMITTED }

    private fun recoverLocked(db: SQLiteDatabase): StillRecoveryReport {
        val receipts = db.rawQuery("SELECT id, committed, tree, volume, folder FROM captures ORDER BY id LIMIT ?", arrayOf((MAX_GROUPS + MAX_DRIVE_GROUPS + 1).toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Receipt(cursor.getString(0), cursor.getType(1), cursor.getLong(1),
                cursor.getString(2), cursor.getString(3), cursor.getString(4))) }
        }
        check(receipts.size <= MAX_GROUPS + MAX_DRIVE_GROUPS)
        var discarded = 0
        var unresolved = 0
        var deferred = 0
        for (receipt in receipts) {
            val id = receipt.id
            try {
                requireId(id)
                check(receipt.type == Cursor.FIELD_TYPE_INTEGER && receipt.state in 0L..1L)
                if (receipt.state == 1L) db.delete("captures", "id = ? AND committed = 1", arrayOf(id))
                else if (receipt.tree == null) { discard(db, id); discarded++ }
                else when (recoverDriveGroup(db, receipt)) {
                    DriveRecovery.DEFERRED -> deferred++
                    DriveRecovery.DISCARDED -> discarded++
                    DriveRecovery.COMMITTED -> Unit
                }
            } catch (failure: Exception) {
                unresolved++
                android.util.Log.e("StillRecovery", "Retained unresolved capture receipt $id", failure)
            }
        }
        return StillRecoveryReport(discarded, unresolved, deferred)
    }

    /**
     * An uncommitted drive group. Its volume absent or its grant gone: kept for a later pass. No folder journalled:
     * nothing reached the drive. A folder without the marker was committed (the marker goes just before the journal
     * update), so the entry is only pruned. Otherwise the group is discarded like an uncommitted MediaStore one. A
     * drive that does not answer is never read as empty: [SafStillRows] fails instead and the entry stays.
     */
    private fun recoverDriveGroup(db: SQLiteDatabase, receipt: Receipt): DriveRecovery {
        val tree = Uri.parse(receipt.tree)
        val volume = requireNotNull(receipt.volume) { "Drive still group without a volume" }
        // The drive under vold's other name answers through the folder picked there (resolveDriveLocation).
        val location = resolveDriveLocation(tree, volume, receipt.folder?.let(Uri::parse), heldReadWriteTrees(resolver), mounted)
            ?: return DriveRecovery.DEFERRED
        val store = SafStillRows(context, location.tree, location.folder) { error("A recovered still group never creates its folder") }
        if (receipt.folder != null && store.folderExists() && !store.markerExists()) {
            // The marker read as missing, which a drive that vanished meanwhile or a provider that gave no answer also
            // reads as: only the folder's own listing without it confirms the commit.
            check(store.listsNoMarker()) { "Still group marker state is unconfirmed" }
            check(db.delete("captures", "id = ? AND committed = 0", arrayOf(receipt.id)) == 1)
            return DriveRecovery.COMMITTED
        }
        discard(db, receipt.id, location)
        return DriveRecovery.DISCARDED
    }

    /** [resolved] is where a recovered drive group answers now; without it the journalled location is used. */
    private fun discard(db: SQLiteDatabase, id: String, resolved: DriveLocation? = null) {
        requireId(id)
        check(!committed(db, id)) { "Committed captures must never be discarded" }
        val location = db.rawQuery("SELECT tree, folder FROM captures WHERE id = ?", arrayOf(id)).use {
            check(it.count == 1 && it.moveToFirst()) { "Still ownership receipt disappeared" }
            it.getString(0) to it.getString(1)
        }
        location.first?.let { tree ->
            val at = resolved ?: DriveLocation(Uri.parse(tree), location.second?.let(Uri::parse))
            SafStillRows(context, at.tree, at.folder) { error("A discarded still group never creates its folder") }.discard()
            check(db.delete("captures", "id = ? AND committed = 0", arrayOf(id)) == 1)
            return
        }
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
        private const val DATABASE = "still-recovery.sqlite"
        /** Phone groups (NULL `tree`) journalled at once; a phone still beyond it is refused. */
        internal const val MAX_GROUPS = 128
        /** Drive groups journalled at once; a drive group beyond it forgets the oldest deferred one ([publish]). */
        internal const val MAX_DRIVE_GROUPS = 128
        private const val MAX_ROWS = 11
        private fun requireId(id: String) { require(UUID.fromString(id).toString() == id) }
        /** The trees of uncommitted drive groups in the journal, which recovery still needs the grants of ([journalledDriveTrees]). */
        internal fun journalledTrees(context: Context): Set<String> =
            journalledDriveTrees(File(context.applicationContext.noBackupFilesDir, DATABASE))
        fun relativePath(id: String, metadata: Boolean): String {
            requireId(id)
            return "${if (metadata) "Download" else "DCIM"}/OpenCineCam/OCC_$id/"
        }
        fun collection(metadata: Boolean): Uri = if (metadata)
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}

/**
 * The drive stopped answering right after a still group's marker was deleted, so whether the group is committed is
 * unknown. Its files and journal entry stay: recovery prunes the entry when the marker is gone and discards the group
 * when it is still there.
 */
internal class StillCommitUnconfirmed(cause: Throwable? = null) : IllegalStateException("Still group marker removal could not be confirmed", cause)

/**
 * PLAN-072: one still group on a drive, in its own folder `<tree>/OpenCineCam/Stills/<stem>/` (the provider may make it
 * `<stem> (1)`), where `<stem>` is the first file's name before its first dot. Files keep their final names from the
 * start. The hidden [SafRecordingRows.MARKER] in the folder is the group's IS_PENDING: it is created with the folder,
 * before [recordFolder] journals it and before any file, and deleted only at [commit]. [folder] is null until the
 * first [insert]; recovery passes the journalled one.
 */
internal class SafStillRows(
    context: Context,
    private val tree: Uri,
    folder: Uri?,
    private val recordFolder: (Uri) -> Unit,
) : StillPublicationStore {
    private val resolver = context.contentResolver
    private val docs = SafDocuments(resolver, tree)
    internal var folder: Uri? = folder
        private set

    override fun insert(displayName: String, mimeType: String, metadata: Boolean): String {
        require(SafDocuments.isPlainName(displayName) && !displayName.startsWith('.')) { "Not a still file name: $displayName" }
        return docs.newFile(folder ?: createFolder(displayName), displayName).uri.toString()
    }

    private fun createFolder(firstName: String): Uri {
        val base = docs.directory(docs.directory(docs.root, SafRecordingRows.BASE_FOLDER), STILLS_FOLDER)
        val created = docs.newDirectory(base, firstName.substringBefore('.'))
        try {
            docs.newFile(created.uri, SafRecordingRows.MARKER)
            recordFolder(created.uri)
        } catch (failure: Throwable) {
            try { check(docs.delete(created.uri)) { "The still folder could not be removed" } } catch (problem: Throwable) { failure.addSuppressed(problem) }
            throw failure
        }
        folder = created.uri
        return created.uri
    }

    override fun writeClosed(uri: String, bytes: ByteArray) {
        // A large DNG to a slow drive can block here, so the drive leaving must be able to close the stream.
        val document = uri.toUri()
        requireNotNull(resolver.openOutputStream(document, "wt")) { "Still artifact writer unavailable" }.useOnDrive(document) { it.write(bytes) }
    }

    /** Name, place in the group folder, size and read-back SHA-256, with the group still in progress before and after. */
    override fun verify(uri: String, displayName: String, mimeType: String, size: Long, sha256: String) {
        val document = uri.toUri()
        fun verifyIdentity() {
            val actual = docs.describe(document)
            check(actual != null && actual.name == displayName && actual.mime != DocumentsContract.Document.MIME_TYPE_DIR && inFolder(document)) {
                "Still artifact identity changed"
            }
            check(markerExists()) { "Still group is no longer in progress" }
        }
        verifyIdentity()
        resolver.verifyStillBytes(document, size, sha256)
        verifyIdentity()
    }

    /** Nothing to flip on a drive: the group becomes visible at [commit]. 1 while the file still exists. */
    override fun publish(uri: String): Int = if (docs.describe(uri.toUri()) != null && inFolder(uri.toUri())) 1 else 0

    override fun delete(uri: String) { check(docs.delete(uri.toUri())) { "Still artifact compensation did not delete its document" } }

    /**
     * The group's durable commit point. [SafDocuments.delete] calls a document on an unplugged drive gone too, so the
     * drive must still answer; when it does not, the marker may or may not be gone ([StillCommitUnconfirmed]).
     */
    fun commit() {
        val group = folder ?: return
        // A drive that throws instead of answering leaves the marker's fate as unknown as one that is gone.
        val removed = try { docs.delete(markerOf(group)) } catch (failure: Exception) { throw StillCommitUnconfirmed(failure) }
        check(removed) { "Still group marker could not be removed" }
        val answers = try { reachable() } catch (failure: Exception) { throw StillCommitUnconfirmed(failure) }
        if (!answers) throw StillCommitUnconfirmed()
    }

    /** Deletes the files, then the marker, then the folder: an interruption leaves the group pending for recovery. */
    fun discard() {
        val group = folder ?: return
        if (!folderExists()) { check(reachable()) { "Still drive unavailable" }; return }
        var failure: Throwable? = null
        for (doc in files(group)) {
            try { check(docs.delete(doc.uri)) { "Interrupted still file could not be deleted" } } catch (problem: Throwable) {
                if (failure == null) failure = problem else if (failure !== problem) failure!!.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
        check(files(group).isEmpty()) { "Interrupted still file remains" }
        check(docs.delete(markerOf(group)) && reachable()) { "Still group marker could not be removed" }
        if (docs.children(group).isEmpty()) check(docs.delete(group)) { "Still group folder could not be removed" }
    }

    /** The picked folder answers: the drive is mounted and the grant holds. */
    fun reachable(): Boolean = docs.describe(docs.root) != null

    fun folderExists(): Boolean = folder?.let { docs.describe(it)?.mime == DocumentsContract.Document.MIME_TYPE_DIR } ?: false
    fun markerExists(): Boolean = folder?.let { docs.describe(markerOf(it)) != null } ?: false

    /** True when the folder's listing answers and holds no marker; a listing the drive does not give throws. */
    fun listsNoMarker(): Boolean = folder?.let { group -> docs.children(group).none { it.name == SafRecordingRows.MARKER } } ?: false

    private fun files(group: Uri): List<SafDocument> = docs.children(group).filter { it.name != SafRecordingRows.MARKER }
        .also { check(it.size <= MAX_FILES) { "Still group folder exceeds its file bound" } }

    private fun markerOf(group: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree,
        SafDocuments.childDocumentId(DocumentsContract.getDocumentId(group), SafRecordingRows.MARKER))

    private fun inFolder(uri: Uri): Boolean {
        val group = folder ?: return false
        val parent = DocumentsContract.getDocumentId(group)
        val child = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return false
        return child.startsWith("$parent/") && '/' !in child.substring(parent.length + 1)
    }

    internal companion object {
        const val STILLS_FOLDER = "Stills"
        /** Both MediaStore namespaces of a group hold up to 11 rows each; on a drive they share one folder. */
        private const val MAX_FILES = 22
    }
}
