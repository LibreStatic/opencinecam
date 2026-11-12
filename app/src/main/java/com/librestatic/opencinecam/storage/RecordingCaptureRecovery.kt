/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.database.sqlite.transaction
import com.librestatic.opencinecam.media.storage.ClipRows
import com.librestatic.opencinecam.media.storage.ContentResolverClipRows
import com.librestatic.opencinecam.rawvideo.RawTakeFileDecision
import com.librestatic.opencinecam.rawvideo.recoverRawTakeFile
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val recordingRecoveryLock = Any()
// IDs only: the registry must not retain a service/context. A process restart starts empty.
private val liveRecordingGroups = mutableSetOf<String>()
// Drive RAW takes a recovery pass is repairing outside the lock: no other pass, and no eviction, touches their entries.
private val repairingRecordingGroups = mutableSetOf<String>()
// The live takes that record to a drive, a subset of liveRecordingGroups. While one records no drive RAW take is scanned:
// the scan reads a whole take, and on the drive being written those reads can push a RAW take's ring past its limit.
private val liveDriveGroups = mutableSetOf<String>()
// The scans in flight; a take that starts on a drive cancels them.
private val rawRepairScans = mutableSetOf<RawRepairScan>()
// A scan gave way to a live drive take: the last one to end calls [RecordingCaptureRecovery.onRawRepairDue].
private var rawRepairWaiting = false

/** Whether a take of the journal at [database] records to a drive; under [recordingRecoveryLock]. */
private fun driveTakeLive(database: String) = liveDriveGroups.any { it.startsWith("$database:") }

/** Take [key] leaves the live registry; under [recordingRecoveryLock]. The last drive take to go lets a waiting RAW repair run. */
private fun releaseLive(key: String) {
    liveRecordingGroups.remove(key)
    if (liveDriveGroups.remove(key) && !driveTakeLive(key.substringBeforeLast(':')) && rawRepairWaiting) {
        rawRepairWaiting = false
        RecordingCaptureRecovery.onRawRepairDue?.invoke()
    }
}

/**
 * The scan of one drive RAW take, run with the journal unlocked. [cancel] (a take starting on a drive) closes what the
 * scan reads through, so it fails with an I/O error and its entry waits for the pass after that take.
 */
internal class RawRepairScan {
    private var held: AutoCloseable? = null
    @get:Synchronized var cancelled = false
        private set

    /** The scan reads through [closeable] from here on; fails, closing it, when the scan was cancelled before. */
    @Synchronized fun hold(closeable: AutoCloseable) {
        if (cancelled) {
            runCatching { closeable.close() }
            throw java.io.IOException("RAW repair cancelled: a take is recording to a drive")
        }
        held = closeable
    }

    @Synchronized fun cancel() {
        cancelled = true
        held?.let { runCatching { it.close() } }
        held = null
    }
}
internal enum class RecordingMemberKind { VIDEO, AUDIO }
/** [incompleteGroups] interrupted takes were kept: their outputs with bytes were published as incomplete (ADR-0011).
 * [rawTakes] is the PLAN-071 RAW video pass ([RawVideoRecovery]). It is not part of [RecordingCaptureRecovery.recover],
 * which runs before every video take: the service runs it once at start and before each RAW take, and merges it here.
 * [deferredGroups] interrupted drive takes (PLAN-072) were left alone because their drive is not mounted or its folder
 * permission is gone; they stay journalled and the next [RecordingCaptureRecovery.recover] retries them. */
data class RecordingRecoveryReport(val discardedGroups: Int = 0, val unresolvedGroups: Int = 0, val incompleteGroups: Int = 0,
    val rawTakes: RawVideoRecoveryReport = RawVideoRecoveryReport(), val deferredGroups: Int = 0)

/** A take spans asynchronous native writers; unlike a still it cannot hold a thread monitor
 * throughout capture. This registry protects the exact take until its real writers retire.
 * Recovery handles only durable uncommitted namespaces, never infers a commit from visible rows:
 * outputs with bytes are published as incomplete, empty ones are deleted (ADR-0011).
 *
 * PLAN-072: each take has one storage backend ([RecordingRowStore]), chosen at [start] and journalled with the take:
 * MediaStore on the phone (NULL `tree`), or a SAF folder on a drive (`tree`, `volume`, and `folder` once the take
 * folder exists). [mounted] says whether a drive volume (UUID) is mounted; tests replace it.
 *
 * Phone takes and drive takes are bounded apart ([MAX_PHONE_TAKES], [MAX_DRIVE_TAKES]), so takes deferred on drives that
 * never return cannot refuse a phone take. A drive take that finds its bound full forgets the oldest deferred drive entry
 * (the journal row only; what is on that drive stays). */
internal class RecordingCaptureRecovery(context: Context, mounted: ((String) -> Boolean)? = null) {
    internal val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val database = File(this.context.noBackupFilesDir, DATABASE)
    private val mounted: (String) -> Boolean = mounted ?: { volume -> volumeMounted(this.context, volume) }
    private val mediaStore = MediaStoreRecordingRows(this.context)
    /** Repairs a drive RAW take's `.ocraw` in place and says whether to keep it ([finishDriveRawTake]); tests replace it. */
    internal var repairRaw: (Uri, RawRepairScan) -> RawTakeFileDecision = { uri, scan ->
        // The repair scans and checks the whole take: registered, so a drive that leaves ends it with an I/O error and
        // the entry stays for the next pass (ADR-0037). Held by the scan, so a take starting on a drive ends it the same way.
        requireNotNull(resolver.openFileDescriptor(uri, "rw")) { "RAW take could not be opened" }.useOnDrive(uri) { descriptor ->
            scan.hold(descriptor)
            DuplexFileChannel.open(descriptor.fileDescriptor).use { channel -> recoverRawTakeFile(channel).decision }
        }
    }
    internal fun key(id: String) = "${database.absolutePath}:$id"

    private fun open(): SQLiteDatabase {
        val db = SQLiteDatabase.openDatabase(database.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            { throw IllegalStateException("Recording recovery journal is corrupt") })
        try {
            db.execSQL("PRAGMA synchronous=FULL")
            check(db.version in 0..2) { "Unsupported recording recovery journal version" }
            if (db.version == 0) db.transaction {
                execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY NOT NULL, committed INTEGER NOT NULL CHECK(committed IN (0,1)), " +
                    "tree TEXT, volume TEXT, folder TEXT)")
                version = 2
            } else if (db.version == 1) db.transaction {
                // Version 1 rows are MediaStore takes: the new columns stay NULL. A column already present is kept.
                val columns = rawQuery("PRAGMA table_info(captures)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
                listOf("tree", "volume", "folder").filter { it !in columns }.forEach { execSQL("ALTER TABLE captures ADD COLUMN $it TEXT") }
                version = 2
            }
            check(count(db, phone = true) <= MAX_PHONE_TAKES && count(db, phone = false) <= MAX_DRIVE_TAKES) { "Recording recovery capacity exceeded" }
            return db
        } catch (failure: Throwable) { db.close(); throw failure }
    }

    /** The entries of phone takes (NULL `tree`) or of drive takes, committed or not. */
    private fun count(db: SQLiteDatabase, phone: Boolean): Long =
        db.rawQuery("SELECT COUNT(*) FROM captures WHERE tree IS ${if (phone) "NULL" else "NOT NULL"}", null).use {
            check(it.moveToFirst()); it.getLong(0)
        }

    /**
     * The recovery pass runs before the lock is taken for the new take. A take starts on the main thread, where a drive
     * that stopped answering would hold up even a phone take, so this pass resolves phone takes only: an interrupted
     * drive take waits for a pass off it ([recover], at a mount, at service start and before a RAW take).
     */
    internal fun start(destination: RecordingDestination = RecordingDestination.Phone): RecordingRecoveryGroup {
        recover(driveTakes = false)
        return synchronized(recordingRecoveryLock) {
            val id = UUID.randomUUID().toString()
            val store: RecordingRowStore = when (destination) {
                RecordingDestination.Phone -> mediaStore.also { rows -> collections.forEach { check(rows.rows(id, it, false).isEmpty()) { "Recording namespace already exists" } } }
                is RecordingDestination.UsbFolder -> driveRows(id, destination)
            }
            journal(id, destination)
            RecordingRecoveryGroup(this, id, store)
        }
    }

    /**
     * PLAN-072 Delivery 3: a RAW video take on a drive. It is journalled like an encoded take on that drive (same columns,
     * schema 2), and its folder holds only `<stem>.ocraw`: recovery tells the two apart by that file ([recoverDriveTake]).
     * Runs a recovery pass first, as [start] does: without reading drive takes, so an interrupted one (a RAW take is
     * scanned in full) waits for its next mount or service start instead of holding this take's start.
     */
    internal fun startRaw(destination: RecordingDestination.UsbFolder): SafRawTakeJournal {
        recover(driveTakes = false)
        return synchronized(recordingRecoveryLock) {
            val id = UUID.randomUUID().toString()
            val store = driveRows(id, destination)
            journal(id, destination)
            SafRawTakeJournal(this, id, store)
        }
    }

    /** The take folder is created at the first output, so it is new: no namespace to check. */
    private fun driveRows(id: String, destination: RecordingDestination.UsbFolder): SafRecordingRows {
        require(destination.treeUri.authority == EXTERNAL_STORAGE_AUTHORITY) { "Not a drive folder" }
        return SafRecordingRows(context, destination.treeUri, null) { folder -> recordFolder(id, folder) }
    }

    /** Journals take [id] on [destination] and registers it live; under [recordingRecoveryLock]. */
    private fun journal(id: String, destination: RecordingDestination) {
        open().use { db ->
            when (destination) {
                RecordingDestination.Phone -> {
                    check(count(db, phone = true) < MAX_PHONE_TAKES) { "Recording recovery capacity exceeded" }
                    db.execSQL("INSERT INTO captures(id, committed) VALUES(?, 0)", arrayOf(id))
                }
                is RecordingDestination.UsbFolder -> {
                    if (count(db, phone = false) >= MAX_DRIVE_TAKES) forgetOldestDeferred(db)
                    db.execSQL("INSERT INTO captures(id, committed, tree, volume) VALUES(?, 0, ?, ?)",
                        arrayOf(id, destination.treeUri.toString(), destination.volumeId.uppercase()))
                }
            }
        }
        liveRecordingGroups.add(key(id))
        if (destination is RecordingDestination.UsbFolder) {
            liveDriveGroups.add(key(id))
            if (rawRepairScans.isNotEmpty()) {
                rawRepairWaiting = true
                rawRepairScans.forEach { it.cancel() }
            }
        }
    }

    /**
     * Makes room for a drive take: deletes the entry of the oldest drive take no live group owns, no pass is repairing and
     * that [recover] would defer (its drive absent or its grant gone). An entry without a folder goes first: nothing of
     * it reached a drive. Otherwise only the entry goes; the take's folder stays on its drive, marker included, where the
     * gallery leaves it out and a computer still reads it. Fails when every drive entry could still be resolved. Under [recordingRecoveryLock].
     */
    private fun forgetOldestDeferred(db: SQLiteDatabase) {
        val held = heldReadWriteTrees(resolver)
        val oldest = db.rawQuery("SELECT id, tree, volume, folder FROM captures WHERE tree IS NOT NULL AND committed = 0 ORDER BY folder IS NOT NULL, rowid", null).use { c ->
            generateSequence { if (c.moveToNext()) listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) else null }.firstOrNull { (id, tree, volume, folder) ->
                key(id) !in liveRecordingGroups && key(id) !in repairingRecordingGroups &&
                    (volume == null || resolveDriveLocation(Uri.parse(tree), volume, folder?.let(Uri::parse), held, mounted) == null)
            }
        }
        checkNotNull(oldest) { "Recording recovery capacity exceeded" }
        check(db.delete("captures", "id = ? AND committed = 0", arrayOf(oldest[0])) == 1)
        android.util.Log.w("RecordingRecovery", "Recovery journal full: forgot interrupted take ${oldest[0]} on drive ${oldest[2]}, " +
            "deferred since its drive went; its files stay on the drive")
    }

    /** The live registry gives take [id] up; the next [recover] may resolve it. */
    internal fun release(id: String) = synchronized(recordingRecoveryLock) { releaseLive(key(id)) }

    /** The take folder exists on the drive: journal it before any output is created in it. */
    private fun recordFolder(id: String, folder: Uri) = open().use { db ->
        check(db.update("captures", ContentValues().apply { put("folder", folder.toString()) },
            "id = ? AND committed = 0 AND tree IS NOT NULL AND folder IS NULL", arrayOf(id)) == 1) { "Recording take folder was not journalled" }
    }

    /**
     * Every journalled take no live group owns. A drive take whose volume is not mounted or whose tree permission is gone
     * is skipped and kept ([RecordingRecoveryReport.deferredGroups]); calling this again once the drive is back (a mount
     * event) retries it. Cheap when nothing is journalled: one SQLite read.
     *
     * Every entry is decided under [recordingRecoveryLock] except the scan of a drive RAW take, which can take minutes for
     * a large take on USB: its entry is marked repairing under the lock, scanned with the lock released (as
     * [RawVideoRecovery] does on the phone), and committed or discarded under the lock again. Meanwhile takes start and
     * stop, and a concurrent pass skips the entry. No caller holds [recordingRecoveryLock]: it would stay held through the scan.
     *
     * No RAW take is scanned while a take records to a drive, and a take that starts on one cancels a scan in flight: the
     * entry is deferred, and [onRawRepairDue] is called when the last such take ends.
     */
    fun recover(): RecordingRecoveryReport = recover(driveTakes = true)

    /** [recover]; without [driveTakes] every drive entry is deferred unread, and no drive is asked anything. */
    private fun recover(driveTakes: Boolean): RecordingRecoveryReport {
        val repairs = mutableListOf<RawRepair>()
        try {
            val decided = synchronized(recordingRecoveryLock) { open().use { db -> decide(db, repairs.takeIf { driveTakes }) } }
            var rawPublished = 0; var rawDeleted = 0; var unresolved = 0; var waiting = 0
            repairs.forEach { repair ->
                val scan = RawRepairScan()
                try {
                    val scans = synchronized(recordingRecoveryLock) {
                        if (!driveTakeLive(database.absolutePath)) rawRepairScans.add(scan) else { rawRepairWaiting = true; false }
                    }
                    if (!scans) { waiting++; return@forEach }
                    val decision = repairRaw(repair.raw.uri, scan)
                    when (synchronized(recordingRecoveryLock) { open().use { db -> finishDriveRawTake(db, repair, decision) } }) {
                        RawTakeFileDecision.PUBLISH -> rawPublished++
                        RawTakeFileDecision.DELETE -> rawDeleted++
                    }
                } catch (failure: Exception) {
                    if (scan.cancelled) {
                        waiting++
                        android.util.Log.i("RecordingRecovery", "RAW take ${repair.id} waits for the take recording to a drive")
                    } else {
                        unresolved++
                        android.util.Log.e("RecordingRecovery", "Retained unresolved take ${repair.id}", failure)
                    }
                } finally {
                    synchronized(recordingRecoveryLock) { rawRepairScans.remove(scan) }
                }
            }
            val report = decided.copy(unresolvedGroups = decided.unresolvedGroups + unresolved, deferredGroups = decided.deferredGroups + waiting,
                rawTakes = decided.rawTakes + RawVideoRecoveryReport(rawPublished, rawDeleted))
            with(report) {
                if (discardedGroups + unresolvedGroups + incompleteGroups + deferredGroups + rawTakes.published + rawTakes.deleted > 0) {
                    android.util.Log.i("RecordingRecovery", "Recovered interrupted takes: publishedIncomplete=$incompleteGroups " +
                        "discarded=$discardedGroups unresolved=$unresolvedGroups deferred=$deferredGroups " +
                        "rawPublished=${rawTakes.published} rawDeleted=${rawTakes.deleted}")
                }
            }
            return report
        } finally {
            if (repairs.isNotEmpty()) synchronized(recordingRecoveryLock) { repairs.forEach { repairingRecordingGroups.remove(key(it.id)) } }
        }
    }

    /**
     * The locked part of [recover]: every entry no live group owns and no pass is repairing. A drive RAW take is only
     * marked repairing and added to [repairs]; the caller scans it with the lock released. Without [repairs] every drive
     * entry is deferred unread.
     */
    private fun decide(db: SQLiteDatabase, repairs: MutableList<RawRepair>?): RecordingRecoveryReport {
        val records = db.rawQuery("SELECT id, committed, tree, volume, folder FROM captures ORDER BY id LIMIT ?",
            arrayOf((MAX_PHONE_TAKES + MAX_DRIVE_TAKES + 1).toString())).use { c ->
            buildList { while (c.moveToNext()) add(JournalRow(c.getString(0), c.getType(1), c.getLong(1), c.getString(2), c.getString(3), c.getString(4))) }
        }
        check(records.size <= MAX_PHONE_TAKES + MAX_DRIVE_TAKES)
        var discarded = 0; var unresolved = 0; var incomplete = 0; var deferred = 0
        records.forEach { record ->
            val id = record.id
            try {
                requireId(id); check(record.type == Cursor.FIELD_TYPE_INTEGER && record.state in 0L..1L)
                if (key(id) !in liveRecordingGroups && key(id) !in repairingRecordingGroups) {
                    if (record.state == 1L) db.delete("captures", "id = ? AND committed = 1", arrayOf(id))
                    else if (record.tree == null) {
                        if (publishIncomplete(db, id, mediaStore) > 0) incomplete++
                        else { discardRows(db, id, mediaStore); discarded++ }
                    } else if (repairs == null) deferred++
                    else when (recoverDriveTake(db, record, repairs)) {
                        DriveRecovery.DEFERRED -> deferred++
                        DriveRecovery.INCOMPLETE -> incomplete++
                        DriveRecovery.DISCARDED -> discarded++
                        DriveRecovery.COMMITTED -> Unit
                        DriveRecovery.RAW_REPAIR -> repairingRecordingGroups.add(key(id))
                    }
                }
            } catch (failure: Exception) {
                unresolved++
                android.util.Log.e("RecordingRecovery", "Retained unresolved take $id", failure)
            }
        }
        return RecordingRecoveryReport(discarded, unresolved, incomplete, deferredGroups = deferred)
    }

    private data class JournalRow(val id: String, val type: Int, val state: Long, val tree: String?, val volume: String?, val folder: String?)
    /** [RAW_REPAIR]: a RAW take, added to the pass's repairs for [recover] to scan with the lock released. */
    private enum class DriveRecovery { DEFERRED, INCOMPLETE, DISCARDED, COMMITTED, RAW_REPAIR }
    /** A drive RAW take [recover] marked repairing: its entry [id], its folder [store] as resolved, its `.ocraw` [raw]. */
    private data class RawRepair(val id: String, val store: SafRecordingRows, val raw: SafDocument)

    /**
     * An uncommitted drive take. The `.occ-in-progress` marker in its folder is the drive's IS_PENDING: a folder without
     * it was committed (the marker went just before the journal update), so the entry is only pruned. No folder recorded
     * means nothing reached the drive. A folder holding an `.ocraw` is a RAW take, added to [repairs] ([finishDriveRawTake]).
     * Otherwise the take is recovered like a MediaStore one, and the marker goes once its outputs with bytes are kept.
     */
    private fun recoverDriveTake(db: SQLiteDatabase, record: JournalRow, repairs: MutableList<RawRepair>): DriveRecovery {
        val tree = Uri.parse(record.tree)
        val volume = requireNotNull(record.volume) { "Drive take without a volume" }
        // The drive under vold's other name answers through the folder picked there (resolveDriveLocation).
        val location = resolveDriveLocation(tree, volume, record.folder?.let(Uri::parse), heldReadWriteTrees(resolver), mounted)
            ?: return DriveRecovery.DEFERRED
        val store = SafRecordingRows(context, location.tree, location.folder) { error("A recovered take never creates its folder") }
        return when {
            record.folder == null -> { discardRows(db, record.id, store); DriveRecovery.DISCARDED }
            !store.folderExists() -> {
                // A drive that went quiet, or gave no answer, reads as "no folder" too: its parent's listing confirms it.
                check(store.listsNoFolder()) { "Recording take folder state is unconfirmed" }
                check(store.reachable()) { "Recording drive unavailable" }
                discardRows(db, record.id, store); DriveRecovery.DISCARDED
            }
            !store.markerExists() -> {
                // The marker read as missing: only the folder's own listing without it confirms the commit.
                check(store.listsNoMarker()) { "Recording take marker state is unconfirmed" }
                check(store.folderExists()) { "Recording drive unavailable" }
                check(db.delete("captures", "id = ? AND committed = 0", arrayOf(record.id)) == 1)
                DriveRecovery.COMMITTED
            }
            else -> store.rawOutput()?.let { raw -> repairs += RawRepair(record.id, store, raw); DriveRecovery.RAW_REPAIR }
                ?: if (publishIncomplete(db, record.id, store) > 0) DriveRecovery.INCOMPLETE
                else { discardRows(db, record.id, store); DriveRecovery.DISCARDED }
        }
    }

    /**
     * PLAN-072 Delivery 3: an interrupted RAW take on a drive, repaired in place as [RawVideoRecovery] repairs one on the
     * phone ([recoverRawTakeFile]: torn tail cut, index and end appended). With a frame its marker goes (the take is
     * published); without one the take is discarded, but only while the drive still answers: a read that failed because
     * the drive went quiet must never pass for an empty take. A failure leaves the entry for the next pass.
     *
     * [recover] runs the repair ([repairRaw]) with the lock released and then this, under the lock, with its [decision]:
     * the entry must still be the uncommitted take the repair was decided for.
     */
    private fun finishDriveRawTake(db: SQLiteDatabase, repair: RawRepair, decision: RawTakeFileDecision): RawTakeFileDecision {
        val (id, store, raw) = repair
        check(state(db, id) == 0L && key(id) !in liveRecordingGroups) { "RAW take changed during its repair" }
        when (decision) {
            RawTakeFileDecision.PUBLISH -> {
                store.commit(id)
                check(db.update("captures", ContentValues().apply { put("committed", 1) }, "id = ? AND committed = 0", arrayOf(id)) == 1)
                runCatching { db.delete("captures", "id = ? AND committed = 1", arrayOf(id)) }
            }
            RawTakeFileDecision.DELETE -> {
                check(store.reachable() && store.describe(raw.uri) != null) { "Recording drive unavailable" }
                discardRows(db, id, store)
            }
        }
        return decision
    }

    /**
     * Publishes the outputs with bytes of an interrupted take as incomplete and deletes the empty ones,
     * then marks the journal entry committed so no later recovery deletes what was kept. Returns the
     * number of rows published; when 0 the entry is untouched and the caller discards it.
     */
    private fun publishIncomplete(db: SQLiteDatabase, id: String, store: RecordingRowStore): Int {
        requireId(id)
        check(state(db, id) == 0L) { "Only an uncommitted take can be recovered" }
        val published = recoverIncompleteTake(id, store.recovered(id), store.clipRows,
            insertSidecar = { name -> store.insert(id, "Download", name, "application/json") },
            readText = { uri -> runCatching { resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() })
        if (published == 0) return 0
        store.commit(id)
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

    internal fun discard(id: String, store: RecordingRowStore) = open().use { discardRows(it, id, store) }
    private fun state(db: SQLiteDatabase, id: String): Long? = db.rawQuery("SELECT committed FROM captures WHERE id = ?", arrayOf(id)).use {
        if (!it.moveToFirst()) null else {
            check(it.count == 1 && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) in 0L..1L)
            it.getLong(0)
        }
    }

    private fun discardRows(db: SQLiteDatabase, id: String, store: RecordingRowStore) {
        requireId(id)
        val state = state(db, id)
        check(state != 1L) { "Committed take must never be compensated" }
        if (state == null) {
            check(store.allRows(id).isEmpty()) { "Take receipt disappeared while outputs remain" }
            return
        }
        store.discard(id)
        check(db.delete("captures", "id = ? AND committed = 0", arrayOf(id)) == 1)
    }

    /** The MediaStore rows of take [id] (device tests and diagnostics). */
    internal fun allRows(id: String): Set<Uri> = mediaStore.allRows(id)

    companion object {
        private const val DATABASE = "recording-recovery.sqlite"
        /**
         * Called when the last take recording to a drive ends and a RAW repair gave way to it: the owner queues a
         * [recover] pass. Runs under the journal's lock, so it must only hand the pass to another thread. The capture
         * service sets it while it lives.
         */
        @Volatile internal var onRawRepairDue: (() -> Unit)? = null
        /** Phone takes (NULL `tree`) journalled at once; a phone take beyond it is refused. */
        internal const val MAX_PHONE_TAKES = 128
        /** Drive takes journalled at once; a drive take beyond it forgets the oldest deferred one ([journal]). */
        internal const val MAX_DRIVE_TAKES = 128
        internal val collections = listOf("DCIM", "Music", "Download")
        internal const val selection = "relative_path = ? AND owner_package_name = ?"
        internal fun path(id: String, root: String): String { requireId(id); require(root in collections); return "$root/OpenCineCam/OCC_TAKE_$id/" }
        internal fun requireId(id: String) { require(id.length == 36 && UUID.fromString(id).toString() == id) }
        internal fun collection(root: String): Uri = when (root) {
            "DCIM" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "Music" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "Download" -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> error("Unknown recording collection")
        }
        /** A take on [destination]; its video and audio members share the group and so the backend. */
        fun begin(context: Context, destination: RecordingDestination = RecordingDestination.Phone): RecordingRecoveryGroup =
            RecordingCaptureRecovery(context).start(destination)

        /** The trees of uncommitted drive takes in the journal, which recovery still needs the grants of ([journalledDriveTrees]). */
        internal fun journalledTrees(context: Context): Set<String> =
            journalledDriveTrees(File(context.applicationContext.noBackupFilesDir, DATABASE))

        /** Whether the volume with UUID [volume] is mounted read-write now. */
        internal fun volumeMounted(context: Context, volume: String): Boolean = runCatching {
            requireNotNull(context.getSystemService(StorageManager::class.java)).storageVolumes
                .any { it.uuid.equals(volume, ignoreCase = true) && it.state == Environment.MEDIA_MOUNTED }
        }.getOrDefault(false)
    }
}

/**
 * The distinct `tree` of the uncommitted entries of the recovery journal [file] (recording or still: same table and
 * columns), for [RecordingDestinationStore] to keep their grants. Takes no lock: SQLite serialises the read with a
 * journal writer, so it is safe from any thread and under any lock. Never creates the journal: none, or one older than
 * schema 2 (phone entries only), reads as empty. Never deletes one SQLite finds corrupt; the read fails instead.
 */
internal fun journalledDriveTrees(file: File): Set<String> {
    if (!file.isFile) return emptySet()
    // Read-write only so SQLite can roll back a writer's hot journal; no collator table is created.
    return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        { throw IllegalStateException("Recovery journal is corrupt") }).use { db ->
        if (db.version < 2) return@use emptySet()
        db.rawQuery("SELECT DISTINCT tree FROM captures WHERE tree IS NOT NULL AND committed = 0", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }
}

/**
 * PLAN-072: where one take's outputs live. Every operation of [RecordingRecoveryGroup] and of recovery that touches an
 * output goes through it. [root] is the output's kind as MediaStore files it: "DCIM" video, "Music" audio, "Download"
 * metadata and attachments. "Pending" is MediaStore's IS_PENDING, or the drive take's in-progress marker.
 */
internal interface RecordingRowStore {
    /** A new pending output [name] of take [id]; the exact name or a failure. */
    fun insert(id: String, root: String, name: String, mime: String): Uri
    /** The MIME the backend froze for [uri] at allocation; later checks require exactly it. */
    fun allocatedMime(uri: Uri, requested: String): String
    /** The output still is [row] in take [id]; [pending] 1 also requires the take to be pending. */
    fun verify(id: String, row: RecordingRow, pending: Int)
    /** Publishes one prepared output; fails with [failure] when the backend did not. */
    fun publish(row: RecordingRow, failure: String)
    fun delete(uri: Uri)
    /** Idempotent compensation of an owned output, also after commit; [failure] when the backend misbehaved. */
    fun deleteOwned(uri: Uri, failure: String)
    /** Every output of take [id], pending or not. */
    fun allRows(id: String): Set<Uri>
    /** The outputs of interrupted take [id] with what [recoverIncompleteTake] decides on. */
    fun recovered(id: String): List<RecoveredRow<Uri>>
    /** Row operations of the incomplete path ([recoverIncompleteTake], `finalizeClip`). */
    val clipRows: ClipRows<Uri>
    /** The take's durable commit on the backend, before the journal records it: nothing for MediaStore, the marker on a drive. */
    fun commit(id: String)
    /** Deletes every output of uncommitted take [id]; [allRows] is then empty. */
    fun discard(id: String)
}

/** The phone: rows in `<collection>/OpenCineCam/OCC_TAKE_<id>/` on the primary volume, pending until published. */
internal class MediaStoreRecordingRows(private val context: Context) : RecordingRowStore {
    private val resolver = context.contentResolver
    override val clipRows: ClipRows<Uri> = ContentResolverClipRows(resolver)

    override fun insert(id: String, root: String, name: String, mime: String): Uri =
        requireNotNull(resolver.insert(RecordingCaptureRecovery.collection(root), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RecordingCaptureRecovery.path(id, root))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })) { "Recording output allocation failed" }

    /** MediaProvider canonicalizes audio/wav to audio/x-wav at insertion on API30.
     * Freeze that actual identity once; later preparation/publication still require exact equality. */
    override fun allocatedMime(uri: Uri, requested: String): String = requireNotNull(resolver.query(
        uri, arrayOf(MediaStore.MediaColumns.MIME_TYPE), null, null, null,
    )).use {
        check(it.count == 1 && it.moveToFirst())
        requireNotNull(it.getString(0)).also { actual ->
            check(actual == requested || requested == "audio/wav" && actual == "audio/x-wav") {
                "Recording MIME changed during allocation: requested=$requested actual=$actual"
            }
        }
    }

    override fun verify(id: String, row: RecordingRow, pending: Int) {
        val path = RecordingCaptureRecovery.path(id, row.root)
        requireNotNull(resolver.query(row.uri, arrayOf("_display_name", "mime_type", "relative_path", "owner_package_name", "is_pending"), null, null, null)).use {
            check(it.count == 1 && it.moveToFirst())
            check(it.getString(0) == row.name && it.getString(1) == row.mime && it.getString(2) == path &&
                it.getString(3) == context.packageName && it.getInt(4) == pending) {
                "Recording output identity or publication changed: expected=${row.name}|${row.mime}|$path|${context.packageName}|$pending; " +
                    "actual=${it.getString(0)}|${it.getString(1)}|${it.getString(2)}|${it.getString(3)}|${it.getInt(4)}"
            }
        }
    }

    override fun publish(row: RecordingRow, failure: String) {
        check(resolver.update(row.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { failure }
    }

    override fun delete(uri: Uri) { check(resolver.delete(uri, null, null) in 0..1) }

    override fun deleteOwned(uri: Uri, failure: String) {
        // A group sweep may already have removed the row. Collection+exact ID deletion remains
        // idempotent without asking MediaStore to grant access to a now-nonexistent item URI.
        val deleted = resolver.delete(ContentUris.removeId(uri),
            "${MediaStore.MediaColumns._ID} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf(ContentUris.parseId(uri).toString(), context.packageName))
        check(deleted in 0..1) { failure }
    }

    override fun allRows(id: String): Set<Uri> = RecordingCaptureRecovery.collections.flatMap { rows(id, it, true) }.toSet()

    override fun recovered(id: String): List<RecoveredRow<Uri>> = RecordingCaptureRecovery.collections.flatMap { root ->
        rows(id, root, true).map { uri ->
            val (name, mime) = requireNotNull(resolver.query(uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE), null, null, null)).use {
                check(it.moveToFirst()) { "Interrupted take row disappeared" }
                requireNotNull(it.getString(0)) to requireNotNull(it.getString(1))
            }
            RecoveredRow(root, uri, name, mime, resolver.ownedRowBytes(uri), uri.toString())
        }
    }

    override fun commit(id: String) = Unit

    override fun discard(id: String) {
        var failure: Throwable? = null
        for (root in RecordingCaptureRecovery.collections) {
            try {
                for (uri in rows(id, root, true)) {
                    try {
                        val count = resolver.delete(uri, RecordingCaptureRecovery.selection, arrayOf(RecordingCaptureRecovery.path(id, root), context.packageName))
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
    }

    internal fun rows(id: String, root: String, owned: Boolean): List<Uri> {
        RecordingCaptureRecovery.requireId(id)
        check(MediaStore.VOLUME_EXTERNAL_PRIMARY in MediaStore.getExternalVolumeNames(context)) { "Recording volume unavailable" }
        val collection = RecordingCaptureRecovery.collection(root)
        val path = RecordingCaptureRecovery.path(id, root)
        @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
        return requireNotNull(resolver.query(all, arrayOf("_id"), if (owned) RecordingCaptureRecovery.selection else "relative_path = ?",
            if (owned) arrayOf(path, context.packageName) else arrayOf(path), null)).use {
            check(it.count <= 4) { "Recording namespace exceeds its row bound" }
            buildList { while (it.moveToNext()) add(ContentUris.withAppendedId(collection, it.getLong(0))) }
        }
    }
}

/**
 * The drive stopped answering right after a drive take's marker was deleted, so whether the take is committed is
 * unknown. Its outputs and journal entry stay: recovery prunes the entry when the marker is gone and recovers the take
 * when it is still there.
 */
internal class RecordingCommitUnconfirmed(cause: Throwable? = null) : IllegalStateException("Recording take marker removal could not be confirmed", cause)

/**
 * PLAN-072: a take on a drive, in its own folder `<tree>/OpenCineCam/<stem>/` (the provider may make it `<stem> (1)`).
 * Outputs are created under their final names and never renamed, so their document URIs stay valid in sidecars and
 * prepared artifacts. The hidden [MARKER] in the folder is the take's IS_PENDING: it is created with the folder,
 * before [recordFolder] journals it and before any output, and deleted only at the take's [commit]. [folder] is null
 * until the first [insert]; recovery passes the journalled one.
 */
internal class SafRecordingRows(
    context: Context,
    private val tree: Uri,
    folder: Uri?,
    private val recordFolder: (Uri) -> Unit,
) : RecordingRowStore {
    private val resolver = context.contentResolver
    private val docs = SafDocuments(resolver, tree)
    private var folder: Uri? = folder

    override val clipRows: ClipRows<Uri> = object : ClipRows<Uri> {
        override fun delete(uri: Uri) = this@SafRecordingRows.delete(uri)
        override fun publish(uri: Uri) { check(docs.describe(uri) != null) { "Recording output disappeared before publication" } }
        override fun writeSidecar(uri: Uri, bytes: ByteArray) {
            (resolver.openOutputStream(uri, "wt") ?: error("Clip sidecar could not be opened.")).use { it.write(bytes) }
        }
    }

    override fun insert(id: String, root: String, name: String, mime: String): Uri {
        require(SafDocuments.isPlainName(name) && !name.startsWith('.')) { "Not a take output name: $name" }
        return docs.newFile(folder ?: createFolder(name), name).uri
    }

    private fun createFolder(firstName: String): Uri {
        val base = docs.directory(docs.root, BASE_FOLDER)
        val created = docs.newDirectory(base, firstName.substringBefore('.'))
        try {
            docs.newFile(created.uri, MARKER)
            recordFolder(created.uri)
        } catch (failure: Throwable) {
            try { check(docs.delete(created.uri)) { "The take folder could not be removed" } } catch (problem: Throwable) { failure.addSuppressed(problem) }
            throw failure
        }
        folder = created.uri
        return created.uri
    }

    /** The provider derives a document's MIME from its name; the identity of a drive output is its name and folder. */
    override fun allocatedMime(uri: Uri, requested: String): String = requested

    override fun verify(id: String, row: RecordingRow, pending: Int) {
        val actual = docs.describe(row.uri)
        check(actual != null && actual.name == row.name && inFolder(row.uri)) {
            "Recording output identity or publication changed: expected=${row.name} in ${folder}; actual=${actual?.name}"
        }
        if (pending == 1) check(markerExists()) { "Recording take is no longer in progress" }
    }

    override fun publish(row: RecordingRow, failure: String) { check(docs.describe(row.uri)?.name == row.name) { failure } }

    override fun delete(uri: Uri) { check(docs.delete(uri)) { "Recording output could not be deleted" } }

    override fun deleteOwned(uri: Uri, failure: String) { check(docs.delete(uri)) { failure } }

    override fun allRows(id: String): Set<Uri> = outputs().mapTo(LinkedHashSet()) { it.uri }

    override fun recovered(id: String): List<RecoveredRow<Uri>> = outputs().map { doc ->
        val root = when (doc.name.substringAfterLast('.', "").lowercase()) {
            "mp4" -> "DCIM"
            "wav", "flac" -> "Music"
            else -> "Download"
        }
        val mime = if (doc.name.endsWith(".json", ignoreCase = true)) "application/json" else doc.mime
        RecoveredRow(root, doc.uri, doc.name, mime, resolver.ownedRowBytes(doc.uri), doc.uri.toString())
    }

    /**
     * The take's durable commit point. [SafDocuments.delete] calls a document on an unplugged drive gone too, so the
     * folder must list without the marker and the drive still answer; when that cannot be read, the marker may or may
     * not be gone ([RecordingCommitUnconfirmed]).
     */
    override fun commit(id: String) {
        val take = folder ?: return
        // A drive that throws instead of answering leaves the marker's fate as unknown as one that is gone.
        val removed = try { docs.delete(markerOf(take)) } catch (failure: Exception) { throw RecordingCommitUnconfirmed(failure) }
        check(removed) { "Recording take marker could not be removed" }
        // A provider that gave no answer reads as a marker already gone: only the folder's own listing confirms it.
        val confirmed = try { listsNoMarker() && reachable() } catch (failure: Exception) { throw RecordingCommitUnconfirmed(failure) }
        if (!confirmed) throw RecordingCommitUnconfirmed()
    }

    /** Deletes the outputs, then the marker, then the folder: an interruption leaves the take pending for recovery. */
    override fun discard(id: String) {
        val take = folder ?: return
        if (!folderExists()) {
            check(listsNoFolder()) { "Recording take folder state is unconfirmed" }
            check(reachable()) { "Recording drive unavailable" }
            return
        }
        var failure: Throwable? = null
        for (doc in outputs()) {
            try { delete(doc.uri) } catch (problem: Throwable) {
                if (failure == null) failure = problem else if (failure !== problem) failure!!.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
        check(outputs().isEmpty()) { "Interrupted take output remains" }
        deleteMarker(take)
        if (docs.children(take).isEmpty()) check(docs.delete(take)) { "Recording take folder could not be removed" }
    }

    /**
     * [SafDocuments.delete] calls a document on an unplugged drive, or one the provider gave no answer for, gone too:
     * the folder must list without the marker and the drive still answer afterwards.
     */
    private fun deleteMarker(take: Uri) {
        check(docs.delete(markerOf(take)) && listsNoMarker() && reachable()) { "Recording take marker could not be removed" }
    }

    /** True when the folder's listing answers and holds no marker; a listing the drive does not give throws. */
    internal fun listsNoMarker(): Boolean = folder?.let { take -> docs.children(take).none { it.name == MARKER } } ?: false

    /** True when a listing confirms the folder is not there ([SafDocuments.listsNo]); a listing the drive does not give throws. */
    internal fun listsNoFolder(): Boolean = folder?.let(docs::listsNo) ?: false

    /** The picked folder answers: the drive is mounted and the grant holds. */
    internal fun reachable(): Boolean = docs.describe(docs.root) != null

    internal fun describe(uri: Uri): SafDocument? = docs.describe(uri)

    /** PLAN-072 Delivery 3: the take's `.ocraw`, when this folder holds a RAW take; it never shares the folder with an MP4. */
    internal fun rawOutput(): SafDocument? = outputs().firstOrNull { it.name.endsWith(RawVideoOutput.EXTENSION, ignoreCase = true) }

    internal fun folderExists(): Boolean = folder?.let { docs.describe(it)?.mime == DocumentsContract.Document.MIME_TYPE_DIR } ?: false
    internal fun markerExists(): Boolean = folder?.let { docs.describe(markerOf(it)) != null } ?: false

    /**
     * The take's documents other than the marker; none before the folder exists or once it is gone. A folder that does
     * not answer because the drive does not is an error, never "empty": the take must stay journalled for recovery.
     */
    private fun outputs(): List<SafDocument> {
        val take = folder ?: return emptyList()
        if (!folderExists()) {
            check(reachable()) { "Recording drive unavailable" }
            return emptyList()
        }
        return docs.children(take).filter { it.name != MARKER }.also { check(it.size <= 16) { "Recording namespace exceeds its row bound" } }
    }

    private fun markerOf(take: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, SafDocuments.childDocumentId(DocumentsContract.getDocumentId(take), MARKER))

    private fun inFolder(uri: Uri): Boolean {
        val take = folder ?: return false
        val parent = DocumentsContract.getDocumentId(take)
        val child = DocumentsContract.getDocumentId(uri)
        return child.startsWith("$parent/") && '/' !in child.substring(parent.length + 1)
    }

    internal companion object {
        const val BASE_FOLDER = "OpenCineCam"
        const val MARKER = ".occ-in-progress"
    }
}

internal data class RecordingRow(val uri: Uri, val name: String, val mime: String, val root: String, val metadata: Boolean,
    /** A take attachment such as the Gyroflow log: journalled and published with the take, never a prepared artifact. */
    val auxiliary: Boolean = false,
    /** The video member's second metadata row, the canonical clip sidecar; prepared as CLIP_METADATA. */
    val clip: Boolean = false)

/** Enrolment closes when preparation begins. All members must publish before a durable commit. */
class RecordingRecoveryGroup internal constructor(private val journal: RecordingCaptureRecovery, val id: String,
    private val store: RecordingRowStore) {
    private val members = linkedMapOf<RecordingMemberKind, RecordingRecoveryMember>()
    private var preparationStarted = false
    private var aborted = false
    private var committed = false
    private var discarded = false
    /** The drive's commit point ran but could not be confirmed ([RecordingCommitUnconfirmed]): recovery owns the take. */
    private var commitUnknown = false
    private var nativeWriters = 0
    private var salvaging = false
    private var relinquished = false

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
            if (retired.compareAndSet(false, true)) { nativeWriters--; finishIfReady(); releaseIfRelinquished() }
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
        store.insert(id, root, name, mime).also { uri -> member.rows += RecordingRow(uri, name, store.allocatedMime(uri, mime), root, metadata, clip = clip) }
    }

    /** Withdraws the clip sidecar row of a take whose sidecar could not be written; the take itself is untouched. */
    internal fun removeClip(member: RecordingRecoveryMember) = synchronized(recordingRecoveryLock) {
        check(!aborted && !committed && !member.prepared)
        member.rows.firstOrNull { it.clip }?.let { row ->
            store.delete(row.uri)
            member.rows.remove(row)
        }
        Unit
    }

    /** The one extra Download row a video take may own. A provider that mangles the identity is undone. */
    internal fun insertAuxiliary(member: RecordingRecoveryMember, name: String, mime: String): Uri = synchronized(recordingRecoveryLock) {
        check(!aborted && !committed && !member.prepared && member.kind == RecordingMemberKind.VIDEO)
        check(member.rows.none { it.auxiliary })
        require(name.isNotBlank() && '/' !in name && name.length <= 180 && mime == GyroflowLog.MIME_TYPE)
        val uri = store.insert(id, "Download", name, mime)
        try {
            member.rows += RecordingRow(uri, name, store.allocatedMime(uri, mime), "Download", metadata = false, auxiliary = true)
        } catch (failure: Throwable) {
            try { store.delete(uri) } catch (problem: Throwable) { failure.addSuppressed(problem) }
            throw failure
        }
        uri
    }

    internal fun removeAuxiliary(member: RecordingRecoveryMember) = synchronized(recordingRecoveryLock) {
        check(!aborted && !committed && !member.prepared)
        member.rows.firstOrNull { it.auxiliary }?.let { row ->
            store.delete(row.uri)
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
            store.verify(id, row, 1)
        }
        member.rows.filter { it.auxiliary }.forEach { store.verify(id, it, 1) }
        member.prepared = true
    }

    internal fun published(member: RecordingRecoveryMember) = synchronized(recordingRecoveryLock) {
        if (committed) return@synchronized
        // A retry never reports as published a take whose commit is left to recovery.
        if (commitUnknown) throw RecordingCommitUnconfirmed()
        check(!aborted && member.prepared && member.retired && nativeWriters == 0) { "Native recording output remains owned" }
        member.rows.forEach { store.verify(id, it, 0) }
        member.published = true
        finishIfReady()
    }

    /** Publishes one of [member]'s prepared outputs on the take's backend (MediaStore: IS_PENDING=0); [failure] when it did not. */
    internal fun publish(member: RecordingRecoveryMember, uri: Uri, failure: String) = synchronized(recordingRecoveryLock) {
        store.publish(member.rows.firstOrNull { it.uri == uri } ?: error(failure), failure)
    }

    /** Idempotent compensation of an output [member] created, also after commit (an explicit legacy discard). */
    internal fun deleteOwned(uri: Uri, failure: String) = synchronized(recordingRecoveryLock) { store.deleteOwned(uri, failure) }

    /** Row operations for a member's own incomplete publication (`finalizeClip`). */
    internal val clipRows: ClipRows<Uri> get() = store.clipRows

    /**
     * The take's owner is done with it: no in-process step will publish, salvage or discard it any more. A take still
     * pending (its publication failed, e.g. a drive pulled mid-take) leaves the live registry once no native writer
     * remains, so the next [RecordingCaptureRecovery.recover] (a drive's mount event) resolves it instead of only a new process.
     * True when a drive's take is left pending: a mount pass may have skipped it while it was live, so the caller queues one.
     */
    fun relinquish(): Boolean = synchronized(recordingRecoveryLock) {
        relinquished = true
        releaseIfRelinquished()
        !committed && !discarded && store is SafRecordingRows
    }

    private fun releaseIfRelinquished() {
        if (relinquished && !committed && !discarded && nativeWriters == 0) releaseLive(journal.key(id))
    }

    internal fun abort(member: RecordingRecoveryMember) = synchronized(recordingRecoveryLock) {
        member.retired = true
        // A commit whose marker may be gone leaves the take to recovery, which reads the drive: nothing is deleted here.
        if (committed || discarded || commitUnknown) return@synchronized
        if (member.keepBytes) {
            android.util.Log.w("RecordingRecovery", "Take $id keeps ${member.kind} bytes for incomplete publication or recovery")
            return@synchronized
        }
        if (salvaging || members.values.any { it.keepBytes }) {
            // Only this member is empty or failed: remove its rows, the rest of the take survives.
            var failure: Throwable? = null
            for (row in member.rows.toList()) {
                try { store.delete(row.uri); member.rows.remove(row) } catch (problem: Throwable) {
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
        if (committed || discarded || commitUnknown || nativeWriters != 0 || members.values.any { !it.retired }) return
        if (aborted) {
            // No native owner remains; failed cleanup is eligible for the next recovery retry.
            releaseLive(journal.key(id))
            journal.discard(id, store)
            discarded = true
        } else if (members.isNotEmpty() && members.values.all { it.withdrawn }) {
            // Every output was empty and has been removed: nothing is left to publish.
            releaseLive(journal.key(id))
            journal.discard(id, store)
            discarded = true
        } else if (members.isNotEmpty() && members.values.filter { !it.withdrawn }.all { it.prepared && it.published }) {
            val rows = members.values.filter { !it.withdrawn }.flatMap { it.rows }
            check(store.allRows(id) == rows.map { it.uri }.toSet()) { "Take membership changed before commit" }
            rows.forEach { store.verify(id, it, 0) }
            // A drive take's durable commit is its marker going; recovery prunes an entry whose folder lost it.
            try { store.commit(id) } catch (failure: RecordingCommitUnconfirmed) {
                // The take may be committed: no later abort may discard it. The next recovery pass resolves the entry,
                // pruning it when the folder lists no marker and recovering the take when the marker is still there.
                commitUnknown = true
                releaseLive(journal.key(id))
                throw failure
            }
            try { journal.commit(id) } catch (failure: Exception) {
                // A drive take whose marker went is committed on the drive whatever the journal says: no later abort may
                // discard it, and recovery prunes the entry.
                if (store is SafRecordingRows) {
                    commitUnknown = true
                    releaseLive(journal.key(id))
                }
                throw failure
            }
            committed = true
            releaseLive(journal.key(id))
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
    /** Publishes one prepared output of this member; see [RecordingRecoveryGroup.publish]. */
    fun publish(uri: Uri, failure: String) = group.publish(this, uri, failure)
    fun deleteOwned(uri: Uri, failure: String) = group.deleteOwned(uri, failure)
    val clipRows: ClipRows<Uri> get() = group.clipRows
    fun abort() = group.abort(this)
}

/**
 * PLAN-072 Delivery 3: the journal side of one RAW video take on a drive ([SafRawVideoOutput]). Unlike an encoded take
 * it has no members: one `.ocraw` in its own folder, created by [create] (the folder and its marker first, journalled
 * before the file), kept by [commit] (the marker goes, then the journal entry) or removed by [discard]. Until one of
 * them succeeds the take is in the live registry, so no recovery pass touches it; [relinquish] hands a take whose
 * finalisation failed (a drive pulled mid-take) to the next pass, which a mount of the drive runs.
 */
internal class SafRawTakeJournal internal constructor(private val journal: RecordingCaptureRecovery, val id: String,
    private val store: SafRecordingRows) {
    private var done = false
    /** [commit] ran but could not confirm its marker went ([RecordingCommitUnconfirmed]): recovery owns the take. */
    private var unconfirmed = false

    /** The take's `.ocraw` document, named exactly [name]. */
    fun create(name: String): Uri = synchronized(recordingRecoveryLock) {
        check(!done) { "RAW take has already terminated" }
        store.insert(id, "Download", name, SafDocuments.OCTET_STREAM)
    }

    /** The take is complete on the drive: its marker goes, then its journal entry. A failure leaves both for recovery. */
    fun commit() = synchronized(recordingRecoveryLock) {
        if (done) return@synchronized
        // A retry never reports as committed a take left to recovery.
        if (unconfirmed) throw RecordingCommitUnconfirmed()
        try { store.commit(id) } catch (failure: RecordingCommitUnconfirmed) {
            // The take may be committed: a later discard must not delete it. The next recovery pass reads the drive.
            unconfirmed = true
            journal.release(id)
            throw failure
        }
        try { journal.commit(id) } catch (failure: Exception) {
            // The marker went, so the take is committed on the drive: a later discard must not delete it.
            unconfirmed = true
            journal.release(id)
            throw failure
        }
        done = true
        journal.release(id)
    }

    /** The take holds no frame: its file, marker and folder go, then the entry. A drive that does not answer keeps them. */
    fun discard() = synchronized(recordingRecoveryLock) {
        if (done || unconfirmed) return@synchronized
        journal.release(id)
        journal.discard(id, store)
        done = true
    }

    /** This process gives the take up without finishing it: the next [RecordingCaptureRecovery.recover] repairs it. */
    fun relinquish() = synchronized(recordingRecoveryLock) { if (!done) journal.release(id) }
}
